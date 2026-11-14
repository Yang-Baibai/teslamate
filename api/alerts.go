package main

import (
	"math"
	"strconv"
	"strings"
	"time"
)

// ---------- 告警判定（数据口径唯一事实源） ----------
//
// 为什么放在服务端：
//   车况 App 和后台推送脚本（watch-car.ps1）都需要同一套判定。
//   如果两边各写一份阈值，改了一处忘了另一处，就会出现
//   「App 说胎压正常但微信推了告警」这种自相矛盾的情况。
//   所以这里算好，App 和脚本都只读结果。

const (
	// 胎压正常范围（bar，冷胎）。
	//
	// ⚠️ 取值依据：查本车 51434 个真实采样点，实际范围是 2.50–3.30、平均 2.96 bar。
	// 网上抄来的通用值 2.4–2.9 与本车工况不符，会导致 62.4% 的记录误报——
	// 一个 62% 时间误报的告警等于没有告警，用户会连真正的漏气信号一起忽略。
	// 换车请按新车实测分布重新设定。
	TpmsMinBar = 2.5
	TpmsMaxBar = 3.3

	// 单胎比四轮中位数低多少 bar 算「单胎漏气」（真正的危险信号）。
	TpmsOutlierDelta = 0.2

	// 数据多少小时未更新算「过期」。
	DataStaleHours = 24

	// 数据多少小时未更新算「该提醒一下了」（还不算过期）。
	//
	// ⚠️ v0.9.0：这个阈值原来**只存在于 App 里**（StatusScreen 自己写 6 小时），
	// 而服务端 DataStaleHours=24 才是唯一事实源 —— 两边各写一份，改一边就打架。
	// 现在两档都在服务端算，App 只读结果。
	DataWarnHours = 6

	// ⚠️ **v0.162.0**：「低电量」的阈值**只在服务端一份**（ADR #38 / #41）。
	//
	// 原来 App 自己在**客户端**按 soc < 20 / soc < 10 判（ui/Widgets.kt 三处）
	// ⇒ 阈值写死在客户端 ⇒ 改一边就打架；
	// 而且**飞书那边一条低电量提醒都没有**（App 角标有、飞书没有 ⇒ 两端不一样）。
	LowBatterySoc = 20
	// 严重偏低（胶囊文案从「低电量」变成「电量严重偏低」）。
	LowBatterySevereSoc = 10
	// 「停着不动还低电量」：车况数据已经多少小时没更新 ⇒ 判定为停着。
	//
	// ⚠️ 为什么用「数据年龄」而不是「里程没变」：
	//   里程要额外查库，而数据年龄本来就在状态里（DataAgeHours）⇒ 一个数就够。
	//   停着不动时车不会上报 ⇒ 数据年龄随时间涨 ⇒ ≥2 小时即视为停着。
	LowBatteryParkedAgeHours = 2
)

// LowBatteryResult 「低电量」的判定结果（纯函数 ⇒ 单测钉得住）。
//
// ⚠️ **v0.162.0**：App 的角标与飞书的提醒**共用这一份判定**。
type LowBatteryResult struct {
	// 电量低（且此刻不在充电 —— 充电中显示「低电量」是噪音）。
	Low bool
	// 严重偏低。
	Severe bool
	// 低电量 ＋ **停着不动**（给飞书那条提醒用；App 角标不用它）。
	Parked bool
}

// EvaluateLowBattery 判「电量低 / 严重偏低 / 停着不动还低电量」。
//
// ⚠️ **v0.162.0**：判定只在服务端做一次（ADR #38）。
//
//	App 只读结果（老服务端没有这些字段时才用客户端兜底）。
//
// 参数：
//   - soc：当前电量百分比（nil = 没读到 ⇒ 一律判「不知道」＝不报低电量）；
//   - charging：此刻是否在充电；
//   - driving：此刻是否有一趟行程正在进行；
//   - dataAgeHours：车况数据已经多少小时没更新（停着不动的代理指标）。
func EvaluateLowBattery(soc *int, charging, driving bool, dataAgeHours int) LowBatteryResult {
	if soc == nil {
		return LowBatteryResult{}
	}
	r := LowBatteryResult{
		Low:    !charging && *soc < LowBatterySoc,
		Severe: !charging && *soc < LowBatterySevereSoc,
	}
	// ⚠️ 停着不动 = 没有正在跑的行程 ＋ 车况数据已经 ≥2 小时没更新。
	r.Parked = r.Low && !driving && dataAgeHours >= LowBatteryParkedAgeHours
	return r
}

// TpmsAlertResult 胎压判定结果。
type TpmsAlertResult struct {
	Alerting bool     // 是否有异常
	Wheels   []string // 异常轮次的中文名
	Values   []string // 异常轮次的读数描述
}

// EvaluateTpms 判定四轮胎压。
//
// 两类异常：
//  1. 越界——读数超出 [TpmsMinBar, TpmsMaxBar]
//  2. 单胎离群——比四轮中位数低 TpmsOutlierDelta 以上
//     （这是真正能识别漏气的信号：一胎明显低于其余三胎）
func EvaluateTpms(fl, fr, rl, rr *float64) TpmsAlertResult {
	type wheel struct {
		name string
		val  *float64
	}
	wheels := []wheel{
		{"左前", fl}, {"右前", fr}, {"左后", rl}, {"右后", rr},
	}

	var vals []float64
	for _, w := range wheels {
		if w.val != nil {
			vals = append(vals, *w.val)
		}
	}
	var median *float64
	if len(vals) > 0 {
		sorted := append([]float64(nil), vals...)
		for i := 1; i < len(sorted); i++ {
			for j := i; j > 0 && sorted[j] < sorted[j-1]; j-- {
				sorted[j], sorted[j-1] = sorted[j-1], sorted[j]
			}
		}
		m := sorted[len(sorted)/2]
		median = &m
	}

	res := TpmsAlertResult{}
	for _, w := range wheels {
		if w.val == nil {
			continue
		}
		v := *w.val
		outOfRange := v < TpmsMinBar || v > TpmsMaxBar
		lowOutlier := median != nil && (*median-v) > TpmsOutlierDelta
		if outOfRange || lowOutlier {
			res.Alerting = true
			res.Wheels = append(res.Wheels, w.name)
			res.Values = append(res.Values, fmtBar(w.name, v))
		}
	}
	return res
}

func fmtBar(name string, v float64) string {
	return name + " " + trimFloat(v) + " bar"
}

// trimFloat 保留最多两位小数，去掉多余的 0。
func trimFloat(v float64) string {
	s := strconv.FormatFloat(v, 'f', 2, 64)
	s = strings.TrimRight(s, "0")
	return strings.TrimRight(s, ".")
}

// DataAge 数据新鲜度。
type DataAge struct {
	Hours int  // 数据距今多少小时（向下取整）
	Stale bool // 是否超过 DataStaleHours（过期）
	Warn  bool // 是否超过 DataWarnHours（该提醒，但还没过期）
}

// EvaluateDataAge 计算数据新鲜度。
func EvaluateDataAge(updatedAt time.Time) DataAge {
	h := int(math.Floor(time.Since(updatedAt).Hours()))
	if h < 0 {
		h = 0
	}
	return DataAge{Hours: h, Stale: h >= DataStaleHours, Warn: h >= DataWarnHours}
}
