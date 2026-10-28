package main

import (
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

// ---------- 充电结局判定（唯一事实源） ----------
//
// ## 为什么需要它
//
// 旧版通知里，充电结束**一律**说「✅ 充电完成」——
// 不管它到底是充到上限了，还是在 80% 就停了。
//
// 实测本机最近一次充电（2026-10-28 04:32→04:51，Tesla 超充）：
// **54% → 80%**，充电上限 100%。
// 也就是说，车没充满，而通知说的是「充电完成」。
//
// 这和 v0.76.0 修掉的「车端拒绝过的事被报成已下发」是**同一类错**：
// **假成功**。用户看到「完成」就不会再看第二眼，
// 而真正需要知道「为什么没充满」的时刻被错过了。
//
// ## 判据：收尾曲线优先，充电上限只做交叉校验
//
// ### ① 收尾曲线（主判据）
//
// 电池逼近充电上限时，BMS 会把充电功率**一路压低**；
// 拔枪走人则是**满功率一刀切**。这两件事在物理上完全不同，
// 于是可以只看收尾那一刻的功率曲线形状就能分辨：
//
//	末次非零功率 / 本场峰值功率
//	  ≤ 0.18 ⇒ BMS 降流收尾 ⇒ 充到上限了
//	  > 0.18 ⇒ 满功率一刀切 ⇒ 有人拔了枪
//
// 实测本机 **30 场**会话，两簇分得很开，中间是空隙：
//
//	充到上限（BMS 降流）：0.031 ~ 0.104
//	拔枪结束（满功率）：0.260 ~ 1.000
//
// 30 场里**没有一个**落在空隙中间。
//
// ⚠️ **为什么不用「结束电量 vs 充电上限」来判？**
// 因为 `charge_limit_soc` 是**车现在**的上限，会变 ——
// 用户今天设 100%、明天设 80%，用今天的 100% 去比上个月那场会话，
// 判出来必然是错的。而曲线是那场会话**当时**留下的物理证据。
//
// ### ② 车端 charging_state（只回答「枪还在不在」）
//
// 官方枚举：Charging / Complete / Stopped / NoPower / Disconnected / Unknown。
// 它唯一不可替代的作用是回答**枪还插着吗**：
// `Stopped` / `NoPower` 说明枪还插着（车端亲口说的），
// 这时才能说「中断」「桩没供电」；
// 枪已经拔了（Disconnected）时，它对「充没充满」没有任何发言权。
//
// ### ③ 充电上限（兜底，且只能交叉校验）
//
// 只有在**曲线数据缺失**（没采到 charges 采样）时，
// 才退回「结束电量 vs 上限」。此时要认这个结论，
// 就得接受「上限没变过」这个前提。

// 车端 charging_state 的官方取值（Fleet API）。
// ⚠️ 只认官方列出来的；不认识的一律当 unknown，**不猜**。
const (
	ChargeStateCharging     = "Charging"
	ChargeStateComplete     = "Complete"
	ChargeStateStopped      = "Stopped"
	ChargeStateNoPower      = "NoPower"
	ChargeStateDisconnected = "Disconnected"
	ChargeStateUnknown      = "Unknown"
)

// 充电结局（服务端算好，App / 推送脚本 / Hermes 工具都只读结果）。
const (
	// OutcomeCharging 还在充。
	OutcomeCharging = "charging"
	// OutcomeComplete 充到上限了。
	OutcomeComplete = "complete"
	// OutcomeInterrupted 没充到上限就停了，**枪还插着**。
	OutcomeInterrupted = "interrupted"
	// OutcomeNoPower 枪插着，但充电桩没有供电。
	OutcomeNoPower = "no_power"
	// OutcomeEndedEarly 结束（枪已拔出），但没充到上限。
	OutcomeEndedEarly = "ended_early"
	// OutcomeUnknown 事实不足，**不猜**。
	OutcomeUnknown = "unknown"
)

// FleetCharge 车端 charge_state 里我们用得上的那几个字段。
type FleetCharge struct {
	State        string   // charging_state（官方枚举，空 = 没读到）
	LimitSoc     *int     // charge_limit_soc：充电上限（%）
	BatteryLevel *int     // battery_level：当前电量（%）
	EnergyAdded  *float64 // charge_energy_added：本次充入（kWh）
}

// ---------- 收尾曲线 ----------

// CurveFacts 收尾曲线的两个数（来自 charges 表这场会话的采样）。
type CurveFacts struct {
	// LastNonZeroPower 最后一个非零功率采样（kW）。
	// 「收尾那一刻车还在吃多少功率」。
	LastNonZeroPower *int
	// PeakPower 本场峰值功率（kW）。
	PeakPower *int
}

// TaperThreshold 收尾降流的判定阈值。
//
// ⚠️ 取 0.18 而不是两簇的几何中位数（≈0.164），是**故意偏向「不是完成」**：
// 说「完成」而实际没充满，正是本轮要消灭的假成功；
// 反过来只是少说一句「完成」，代价小得多。
const TaperThreshold = 0.18

// DetectTaper 判定这场会话是不是「BMS 降流收尾」（= 充到上限了）。
//
// 返回 nil = **判不出来**（没有采样 / 全程 0 kW），
// 调用方必须降级处理，**不能**当成 false（那等于说「肯定没充到上限」）。
func DetectTaper(c CurveFacts) *bool {
	if c.LastNonZeroPower == nil || c.PeakPower == nil {
		return nil
	}
	last, peak := *c.LastNonZeroPower, *c.PeakPower
	if peak <= 0 || last <= 0 {
		return nil
	}
	hit := float64(last) <= TaperThreshold*float64(peak)
	return &hit
}

// ChargeOutcome 「这次充电到底怎么了」。
type ChargeOutcome struct {
	// State 车端原始的 charging_state（便于排查，不解释）。
	State string `json:"state"`
	// Outcome 结局枚举（见上面的常量）。
	Outcome string `json:"outcome"`
	// Verdict 一句话**结论**（不含数字）。
	//
	// ⚠️ **文案只在这里有一份** —— App、推送脚本、Hermes 工具全都用它。
	// 三处各写一份文案，迟早漂移（v0.76.0 已经吃过一次亏：
	// 「完成后是什么样」在服务端与客户端各写了一份）。
	Verdict string `json:"verdict"`
	// Detail 完整一句话 = Verdict + 事实。
	//
	// 给「只要一句话」的场合用（推送正文、Hermes 的回答）。
	Detail string `json:"detail"`
	// LimitSoc 车**现在**的充电上限（%）。nil = 没读到。
	//
	// ⚠️ 这是**当前**状态，可能与会话当时不同 —— 判定不靠它。
	LimitSoc *int `json:"limit_soc"`
	// EndSoc 本次会话的**结束电量**（%，DB 精确值）。nil = 没有已结束的会话。
	//
	// ⚠️ **正在充电时它是上一场会话的结束电量**，所以充电中这一项恒为 nil ——
	//    拿上一场的数当本次显示，就是另一种假事实。
	EndSoc *int `json:"end_soc"`
	// BatteryLevel 当前电量（%）。nil = 没读到。
	BatteryLevel *int `json:"battery_level"`
	// EnergyAdded 本次充入（kWh，电池侧口径）。
	EnergyAdded *float64 `json:"energy_added_kwh"`
	// DurationMin 本次时长（分钟）。
	DurationMin *int `json:"duration_min"`
	// ReachedLimit 是否充到了（当时的）充电上限。
	// nil = 判不了（曲线与电量都缺）。
	ReachedLimit *bool `json:"reached_limit"`
	// ChargeID / EndedAt 最近一次已结束会话的 id 与结束时刻。
	ChargeID *int       `json:"charge_id"`
	EndedAt  *time.Time `json:"ended_at"`
	// LastPowerKW / PeakPowerKW 收尾曲线的原始证据。
	//
	// ⚠️ 把判据本身暴露出来，是为了**可审计**：
	// 「为什么说充到上限」这件事，用户应该能自己核对。
	LastPowerKW *int `json:"last_power_kw"`
	PeakPowerKW *int `json:"peak_power_kw"`
}

// ChargeOutcomeInput 判定结局所需的全部事实。
type ChargeOutcomeInput struct {
	Fleet          FleetCharge
	HasFleet       bool // 是否读到了车端 charge_state
	ActiveCharging bool // DB 里是否还有进行中的充电
	// 进行中这场的事实（正在充电时才有意义）。
	ActiveSoc    *int     // 本次充电的当前电量（%）
	ActiveEnergy *float64 // 本次充电的当前充入（kWh）
	Curve        CurveFacts
	// 最近一次**已结束**会话的事实。
	LastEndSoc      *int
	LastEnergy      *float64
	LastDurationMin *int
	LastChargeID    *int
	LastEndedAt     *time.Time
}

// 结论短语（不含数字）。⚠️ 文案只此一份，下游只读不写。
const (
	verdictCharging    = "正在充电"
	verdictComplete    = "已充到上限"
	verdictInterrupted = "没充到上限就停了，枪还插着"
	verdictNoPower     = "充电桩没有供电"
	verdictEndedEarly  = "没到充电上限"
	verdictUnknown     = "车端状态读不到，暂不能判断这次充电的结果"
)

// EvaluateChargeOutcome 判定「这次充电到底怎么了」（纯函数，可单测）。
//
// 判定顺序（每一步都只认事实，判不出来就降级，**不猜**）：
//
//	① DB 说还在充                        → charging
//	② 车端说 Stopped（枪插着、车端停了）  → interrupted
//	③ 车端说 NoPower（枪插着、桩没电）    → no_power
//	④ 收尾曲线能判：
//	     降流收尾（BMS 把功率压到峰值 18% 以下）→ complete
//	     满功率一刀切                        → ended_early
//	⑤ 车端说 Complete                       → complete
//	⑥ 曲线缺失 ⇒ 退回「结束电量 vs 上限」：
//	     到达上限 → complete，否则 → ended_early
//	⑦ 都判不出来                             → unknown
//
// ⚠️ 为什么 ②③ 排在曲线前面：
// 曲线回答「这场会话怎么结束的」，车端状态回答「**枪现在还在不在**」——
// 只有后者能支持「中断」「桩没供电」这类说法（枪还插着）。
// 枪已经拔出时（Disconnected），车端对「充没充满」没有任何发言权。
func EvaluateChargeOutcome(in ChargeOutcomeInput) ChargeOutcome {
	out := ChargeOutcome{
		State:        in.Fleet.State,
		LimitSoc:     in.Fleet.LimitSoc,
		EndSoc:       in.LastEndSoc,
		BatteryLevel: in.Fleet.BatteryLevel,
		EnergyAdded:  in.LastEnergy,
		DurationMin:  in.LastDurationMin,
		ChargeID:     in.LastChargeID,
		EndedAt:      in.LastEndedAt,
		LastPowerKW:  in.Curve.LastNonZeroPower,
		PeakPowerKW:  in.Curve.PeakPower,
	}

	// 收尾曲线判「充到上限了吗」（nil = 不知道）。
	taper := DetectTaper(in.Curve)

	// 「结束电量 vs 当前上限」—— 只在曲线缺失时兜底用。
	// ⚠️ 上限是会变的，所以这个结论的可信度**低于**曲线。
	if in.LastEndSoc != nil && in.Fleet.LimitSoc != nil {
		hit := *in.LastEndSoc >= *in.Fleet.LimitSoc
		out.ReachedLimit = &hit
	}

	if in.ActiveCharging {
		// ⚠️ **正在充电时，上一场会话的数字全部作废** ——
		//    EndSoc / EnergyAdded / DurationMin 都是**上一场**的，
		//    当成本次显示就是另一种假事实。
		out.Outcome = OutcomeCharging
		out.Verdict = verdictCharging
		out.EndSoc = nil
		out.EnergyAdded = nil
		out.DurationMin = nil
		out.ChargeID = nil
		out.EndedAt = nil
		out.ReachedLimit = nil
		if in.ActiveSoc != nil {
			out.BatteryLevel = in.ActiveSoc
		}
		if in.ActiveEnergy != nil {
			out.EnergyAdded = in.ActiveEnergy
		}
		out.Detail = chargeDetail(out, verdictCharging)
		return out
	}

	// ②③ 车端亲口说的「枪还插着，但充不了」。
	if in.HasFleet {
		switch in.Fleet.State {
		case ChargeStateStopped:
			out.Outcome = OutcomeInterrupted
			out.ReachedLimit = taper
			out.Verdict = verdictInterrupted
			out.Detail = chargeDetail(out, verdictInterrupted)
			return out
		case ChargeStateNoPower:
			out.Outcome = OutcomeNoPower
			out.ReachedLimit = taper
			out.Verdict = verdictNoPower
			out.Detail = chargeDetail(out, verdictNoPower)
			return out
		}
	}

	// ④ 曲线能判 ⇒ 以曲线为准（它是那场会话**当时**留下的物理证据）。
	if taper != nil {
		out.ReachedLimit = taper
		if *taper {
			out.Outcome = OutcomeComplete
			out.Verdict = verdictComplete
		} else {
			out.Outcome = OutcomeEndedEarly
			out.Verdict = endedEarlyVerdict(out)
		}
		out.Detail = chargeDetail(out, out.Verdict)
		return out
	}

	// ⑤ 车端说「充满了」（曲线缺失时的次选）。
	if in.HasFleet && in.Fleet.State == ChargeStateComplete {
		out.Outcome = OutcomeComplete
		out.Verdict = verdictComplete
		out.Detail = chargeDetail(out, verdictComplete)
		return out
	}

	// ⑥ 曲线缺失 ⇒ 退回「结束电量 vs 上限」（可信度较低，但仍是事实）。
	if out.ReachedLimit != nil {
		if *out.ReachedLimit {
			out.Outcome = OutcomeComplete
			out.Verdict = verdictComplete
		} else {
			out.Outcome = OutcomeEndedEarly
			out.Verdict = endedEarlyVerdict(out)
		}
		out.Detail = chargeDetail(out, out.Verdict)
		return out
	}

	// ⑦ 事实不足 ⇒ 说不知道，**不编一个结论**。
	out.Outcome = OutcomeUnknown
	out.Verdict = verdictUnknown
	out.Detail = verdictUnknown
	return out
}

// endedEarlyVerdict 「没到充电上限」这句话只在**确实**够不着上限时才说。
//
// ⚠️ 上限数字**不在结论里**，而是放在事实里（「充到 80%（上限 100%）」）——
//
//	结论词要短，App 的标题行、推送的正文都用得上；
//	数字属于事实，缺哪个就少说哪个。
func endedEarlyVerdict(o ChargeOutcome) string {
	return verdictEndedEarly
}

// chargeDetail 组装完整一句话 = 结论 + 事实。
//
// ⚠️ 数字全部来自事实（DB 的结束电量 / 车端上限 / 车端充入量），
// 缺哪个就少说哪个，**不填占位数字**。
func chargeDetail(o ChargeOutcome, verdict string) string {
	parts := []string{}
	if o.EndSoc != nil {
		// ⚠️ 只有「确实没到上限」才把上限摆出来（一眼看出差在哪）——
		//    结束电量高于当前上限说明上限是**之后**才改低的，
		//    拿现在的上限说「没到上限」就是撒谎。
		if o.LimitSoc != nil && o.ReachedLimit != nil && !*o.ReachedLimit && *o.EndSoc < *o.LimitSoc {
			parts = append(parts, fmt.Sprintf("充到 %d%%（上限 %d%%）", *o.EndSoc, *o.LimitSoc))
		} else {
			parts = append(parts, fmt.Sprintf("充到 %d%%", *o.EndSoc))
		}
	} else if o.BatteryLevel != nil {
		parts = append(parts, fmt.Sprintf("当前 %d%%", *o.BatteryLevel))
	}
	if o.EnergyAdded != nil {
		parts = append(parts, fmt.Sprintf("充入 %.1f kWh", *o.EnergyAdded))
	}
	if o.DurationMin != nil {
		parts = append(parts, fmt.Sprintf("用了 %d 分钟", *o.DurationMin))
	}
	if len(parts) == 0 {
		return verdict
	}
	return verdict + "：" + strings.Join(parts, "，")
}

// ---------- 从缓存的原始 vehicle_data 里取 charge_state ----------

// chargeContextFromRaw 解析车端 charge_state（纯函数，可单测）。
//
// ⚠️ 没读到的字段一律 nil，**不压成 0** ——
// 「上限 0%」和「没读到上限」是完全不同的两件事。
func chargeContextFromRaw(raw map[string]any) (FleetCharge, bool) {
	if raw == nil {
		return FleetCharge{}, false
	}
	cs, ok := raw["charge_state"].(map[string]any)
	if !ok || cs == nil {
		return FleetCharge{}, false
	}
	fc := FleetCharge{}
	if v, ok := cs["charging_state"].(string); ok {
		fc.State = strings.TrimSpace(v)
	}
	fc.LimitSoc = numIntField(cs, "charge_limit_soc")
	fc.BatteryLevel = numIntField(cs, "battery_level")
	fc.EnergyAdded = numFloatField(cs, "charge_energy_added")
	// 一个字段都没读到 ⇒ 当作「不知道」。
	if fc.State == "" && fc.LimitSoc == nil && fc.BatteryLevel == nil && fc.EnergyAdded == nil {
		return FleetCharge{}, false
	}
	return fc, true
}

// numFloatField 取一个数值字段（JSON 数字 → float64）。
func numFloatField(m map[string]any, key string) *float64 {
	v, ok := m[key]
	if !ok || v == nil {
		return nil
	}
	switch t := v.(type) {
	case float64:
		return &t
	case int:
		f := float64(t)
		return &f
	case json.Number:
		f, err := t.Float64()
		if err != nil {
			return nil
		}
		return &f
	default:
		return nil
	}
}

// numIntField 取一个整数字段。
func numIntField(m map[string]any, key string) *int {
	f := numFloatField(m, key)
	if f == nil {
		return nil
	}
	i := int(*f)
	return &i
}
