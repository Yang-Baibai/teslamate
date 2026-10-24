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
)

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
