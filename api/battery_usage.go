package main

import (
	"context"
	"fmt"
	"math"
	"strings"
	"time"
)

// ── 电池充电习惯与累计循环（v0.192.0）────────────────────────────
//
// ## 为什么加这个
//
// 定时任务使命 §3.2 点名了「电池健康度科学评估」该看的三样：
// **满充可用容量**（v0.22.0 已有）、**累计充放电循环**（一直没有）、
// **快慢充比例**（一直没有）。
// ⇒ 这一轮补上后两样。
//
// ## 反幻觉三条
//
//  1. **只给事实，不下方向性结论** —— 绝不写「快充伤电池」「深度放电伤电池」
//     这类**没有官方依据**的断言。Tesla 官方 Service Mode 的 Battery Health Test
//     看的是**容量与循环**，不是「你的充电习惯好不好」⇒ 我们也只给事实。
//  2. **两个口径并排给** —— 电池侧（按 SOC 差算）与电网侧（充电机送来的度数）
//     互相印证；电网侧含充电损耗 ⇒ **偏大**（不说清会被读成「电池掉得快」）。
//  3. **不是车机的循环计数器** —— 我们量的是「充了多少」，与官方 App 的
//     「Battery cycles」可能有出入 ⇒ 口径说明里写明。
//
// ## 口径
//
//   · **等效满充循环（EFC）** = 充入电池的电量 ÷ **铭牌可用容量**
//     （从空到满一次 = 1 循环，这是行业通用定义）。
//   · **电池侧充入** = Σ(结束 SOC − 开始 SOC) × **最近一次满充实测可用容量**
//     ⇒ 容量随时间衰减 ⇒ 早期算得略高（口径说明里写明）。
//   · **直交流判据**复用 cteChargeDC ⇒ 与充电列表、用电页**同一份**。

// BatteryUsage 充电习惯与累计等效满充循环（**事实**）。
type BatteryUsage struct {
	// Sessions 充电场次（真的充进电的会话）。
	Sessions int `json:"sessions"`
	// DcSessions / AcSessions 直交流场次（判据与充电统计同一份）。
	DcSessions int `json:"dc_sessions"`
	AcSessions int `json:"ac_sessions"`
	// DcEnergyRatio 直流**电量**占比（%）；nil = 没有样本。
	DcEnergyRatio *float64 `json:"dc_energy_ratio"`
	// ChargerKwh 电网侧（充电机送来的）总度数。
	ChargerKwh *float64 `json:"charger_kwh"`
	// BatteryKwh 电池侧充入（度）= Σ(结束SOC − 开始SOC) × 可用容量。
	BatteryKwh *float64 `json:"battery_kwh"`
	// Efc 等效满充循环（**电池侧** ÷ 铭牌可用容量）—— 主口径。
	Efc *float64 `json:"efc"`
	// EfcByCharger 另一口径（电网侧 ÷ 铭牌）—— 偏大，用来互证。
	EfcByCharger *float64 `json:"efc_by_charger"`
	// Line 结论的**一句话**（服务端渲染好的）——
	// App 车况页的卡片与体检报告**都印这一句** ⇒ 同一件事只有一份说法（ADR #101 的教训）。
	Line string `json:"line"`
	// Note 口径说明（**服务端唯一一份**，ADR #41）。
	Note string `json:"note"`

	// ── ⚠️ **v0.196.0**：充电习惯（**事实**，不下方向性结论）────────────
	//
	// ⚠️ **只给事实**：不说「这样伤电池」「那样伤电池」——
	//   那类断言**没有官方依据**（Tesla 官方 Service Mode 的 Battery Health Test
	//   看的是**容量与循环**，不是「你的充电习惯好不好」）⇒ 说了就是编（ADR #46 反幻觉）。
	// AvgStartSoc 平均**插枪前**的电量（%）—— 车主平时把电放到多低才充。
	AvgStartSoc *float64 `json:"avg_start_soc"`
	// DeepDischargeN 从 **10% 以下**才开始充的场次（项目自己的口径，写在文案里）。
	DeepDischargeN int `json:"deep_discharge_n"`
	// DaysSinceLastCharge 距**上次充电**多少天（nil = 从来没充过 / 取不到）。
	DaysSinceLastCharge *int `json:"days_since_last_charge"`
	// UsageLine 充电习惯的**一句话**（服务端渲染好的）——
	// App 卡片与体检报告印**同一句**（与 ADR #105 同一套做法）。
	UsageLine string `json:"usage_line"`
	// ── ⚠️ **v0.197.0**：充电习惯的**短句**（App **常驻层**印它）──────
	//
	// ⚠️ **为什么要短句**：UsageLine 那句实测 **60 宽** ⇒
	//   App 用独立 Text(...) 常驻印出来会**折成两行**（模拟器实测）⇒
	//   ⇒ 常驻层印**短句**（宽度受 appCopyWidthLimit = 44 管），
	//   **全文**折进「更多明细」⇒ 详情一点不丢。
	UsageHeadline string `json:"usage_headline"`
}

// computeBatteryUsage 算充电习惯与累计等效满充循环（纯事实）。
//
// @param usableKwh 最近一次满充实测的可用容量（nil ⇒ 给不出循环次数）
// @param nominalKwh 铭牌可用容量（循环次数的分母；nil ⇒ 给不出）
// batteryUsageDeepSocPct 「放得很低」的门槛（%）：从 **10% 以下**开始充。
//
// ⚠️ 这是**本项目自己的口径**（写进文案里）⇒ 不是官方定义 ⇒
// **不许**由它推出「伤电池」这类结论（反幻觉，ADR #46）。
const batteryUsageDeepSocPct = 10

func computeBatteryUsage(carID int, usableKwh, nominalKwh *float64, now time.Time) (*BatteryUsage, error) {
	rows, err := db.Query(context.Background(), sqlBatteryUsage, carID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	u := &BatteryUsage{}
	var chargerKwh, dcKwh, socPoints, startSum float64
	var startN, deepN int
	var lastEnd time.Time
	for rows.Next() {
		var energy float64
		var startSoc, endSoc *int
		var isDC bool
		var endDate time.Time
		if err := rows.Scan(&energy, &startSoc, &endSoc, &isDC, &endDate); err != nil {
			return nil, err
		}
		u.Sessions++
		if isDC {
			u.DcSessions++
			dcKwh += energy
		} else {
			u.AcSessions++
		}
		chargerKwh += energy
		// ⚠️ 只算**真的涨上去**的部分（车停着时电量也会掉 ⇒ 那不是充电）。
		if startSoc != nil && endSoc != nil && *endSoc > *startSoc {
			socPoints += float64(*endSoc - *startSoc)
		}
		// ⚠️ **v0.196.0**：插枪前电量（平均）与「放得很低」的场次。
		if startSoc != nil {
			startSum += float64(*startSoc)
			startN++
			if *startSoc <= batteryUsageDeepSocPct {
				deepN++
			}
		}
		// 「最近一次」按**充电结束时刻**取最大（SQL 排序按开始时刻 ⇒ 不影响这里）。
		if endDate.After(lastEnd) {
			lastEnd = endDate
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	// 没有充电记录 ⇒ **不给数**（宁可不说，也不说半句）。
	if u.Sessions == 0 {
		return nil, nil
	}
	u.ChargerKwh = &chargerKwh
	// ── ⚠️ **v0.196.0**：充电习惯（**事实**）──────────────────────────
	if startN > 0 {
		v := startSum / float64(startN)
		u.AvgStartSoc = &v
	}
	u.DeepDischargeN = deepN
	if !lastEnd.IsZero() {
		d := int(now.Sub(lastEnd).Hours() / 24)
		if d < 0 {
			d = 0
		}
		u.DaysSinceLastCharge = &d
	}
	u.UsageLine = batteryUsageLine(u)
	// ⚠️ **v0.197.0**：短句（App 常驻层）—— 骨架在 api/app_copy.go（闸门 26 盖住宽度）。
	u.UsageHeadline = batteryUsageHeadline(u)
	if chargerKwh > 0 {
		v := dcKwh / chargerKwh * 100
		u.DcEnergyRatio = &v
	}
	if usableKwh != nil && nominalKwh != nil && *nominalKwh > 0 && *usableKwh > 0 {
		batteryKwh := socPoints / 100.0 * *usableKwh
		u.BatteryKwh = &batteryKwh
		efc := batteryKwh / *nominalKwh
		u.Efc = &efc
		efcCharger := chargerKwh / *nominalKwh
		u.EfcByCharger = &efcCharger
		u.Line = batteryCycleLine(u)
		u.Note = batteryUsageNote(u)
	}
	return u, nil
}

// batteryCycleLine 累计循环的**一句话**（服务端唯一一份 ⇒ App 卡片与报告印同一句）。
//
// ⚠️ **v0.196.0 改名**：原来叫 batteryUsageLine，与「充电习惯」那句撞名了
// ⇒ 两个用途不同的句子必须**各自有名**（一个说循环、一个说习惯）。
func batteryCycleLine(u *BatteryUsage) string {
	if u == nil || u.Efc == nil {
		return ""
	}
	return fmt.Sprintf("累计等效满充循环 %.1f 次（直流 %d / 交流 %d 次充电）",
		*u.Efc, u.DcSessions, u.AcSessions)
}

// batteryUsageLine 充电习惯的**一句话**（服务端唯一一份 ⇒ App 与报告印同一句）。
//
// ⚠️ **只报事实**，不下方向性结论（不说「这样伤电池」—— 没有官方依据）。
func batteryUsageLine(u *BatteryUsage) string {
	if u == nil || u.Sessions == 0 {
		return ""
	}
	parts := []string{}
	if u.AvgStartSoc != nil {
		parts = append(parts, fmt.Sprintf("平均从 %.0f%% 开始充", *u.AvgStartSoc))
	}
	parts = append(parts, fmt.Sprintf("%d 次从 %d%% 以下开始充",
		u.DeepDischargeN, batteryUsageDeepSocPct))
	if u.DaysSinceLastCharge != nil {
		switch *u.DaysSinceLastCharge {
		case 0:
			parts = append(parts, "上次充电是今天")
		case 1:
			parts = append(parts, "上次充电是昨天")
		default:
			parts = append(parts, fmt.Sprintf("上次充电在 %d 天前", *u.DaysSinceLastCharge))
		}
	}
	return "充电习惯：" + strings.Join(parts, " · ")
}

// batteryUsageHeadline 充电习惯的**短句**（服务端唯一一份）。
//
// ⚠️ **v0.197.0**：App 常驻层只印这句 ⇒ 它的宽度**必须** ≤ appCopyWidthLimit
//
//	（骨架在 api/app_copy.go ⇒ Hermes 闸门 26 的判据②自动盖住）。
//	全文在 [batteryUsageLine]（体检报告正文与 App 的「更多明细」里印它）。
func batteryUsageHeadline(u *BatteryUsage) string {
	if u == nil || u.AvgStartSoc == nil {
		return ""
	}
	return fmt.Sprintf(appCopyUsageHeadlineTmpl, int(math.Round(*u.AvgStartSoc)))
}

// batteryUsageNote 这段说明（**服务端唯一一份**，ADR #41）。
//
// ⚠️ **一行一个事实** ⇒ 每行都不许太长（ⓘ 的行宽上限 96 是同一个数）。
func batteryUsageNote(u *BatteryUsage) string {
	lines := []string{}
	if u.Efc != nil {
		lines = append(lines, fmt.Sprintf("累计等效满充循环 %.1f 次（从空到满算 1 次）。", *u.Efc))
	}
	if u.EfcByCharger != nil {
		lines = append(lines, fmt.Sprintf("按充电机送来的度算是 %.1f 次（含充电损耗 ⇒ 偏大）⇒ 两数互证。", *u.EfcByCharger))
	}
	if u.BatteryKwh != nil {
		lines = append(lines, fmt.Sprintf("充电 %d 次（直流 %d / 交流 %d）⇒ 充入电池 %.0f 度。",
			u.Sessions, u.DcSessions, u.AcSessions, *u.BatteryKwh))
	}
	lines = append(lines, "这是「充了多少」，不是车机自己的循环计数器，与官方 App 的数字可能有出入。")
	return strings.Join(lines, "\n")
}

// computeBatteryHealthWithUsage 算电池健康**并**带上充电习惯与累计循环。
//
// ⚠️ /battery 与 /battery_report **共用这一个** ⇒ 两处看到的数必然一致（ADR #38）。
func computeBatteryHealthWithUsage(carID int) (*BatteryHealth, error) {
	h, err := computeBatteryHealth(carID)
	if err != nil {
		return nil, err
	}
	usage, err := computeBatteryUsage(carID, h.CurrentCapacityKwh, h.NominalCapacityKwh, time.Now())
	if err != nil {
		return nil, err
	}
	h.Usage = usage
	return h, nil
}
