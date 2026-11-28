package main

import (
	"sort"
	"time"
)

// ---------- 「首测基线」与「最近满充点」的口径（v0.200.0） ----------

// ## 为什么这两个口径要收归服务端
//
// v0.200.0 实测：**用量页那张「电池健康度」卡有两处是客户端自己挑的点**：
//
// ① **基线日期** —— 客户端取 §series.take(3).sortedBy { capacity }.getOrNull(1)?.date§
//    （**序列的前 3 个**，不筛有没有容量）；
// ② **最近满充点的满电续航** —— 客户端取 §filter { end_soc >= 100 && rated_range_km != null }§
//    再按日期取最新。
//
// ⚠️ 而**服务端**算「相对首测」时，基线取的是「**前 3 个有容量**的点」的中位数
//   （§baselineCaps§）⇒ **两边可能根本不是同一个点**
//   ⇒ 界面显示的「基线日期」与服务端算相对首测用的点**对不上**。
//
// ⇒ 规矩：**同一件事只有一份**（ADR #38）⇒ 这两个口径都搬到服务端，
//   客户端只透传（**基线容量与基线日期必须是同一个点**）。

// baselineSample 一次**有容量**的测量（值 ＋ 时刻）。
type baselineSample struct {
	kwh float64
	at  time.Time
}

// pickBaselineSample 挑出「首测基线」的那一个点（**纯函数，可单测**）。
//
// ⚠️ 口径必须与 [medianOfFloats] 完全一致：
// 取**前 3 次有容量的测量**，按容量排序后取**中位数**那一个
// ⇒ 基线**容量**与基线**日期**永远是**同一个点**。
//
// ⚠️ 偶数个时：容量取两个中间值的**平均**（与 [medianOfFloats] 一样），
// 日期取**靠后**的那一个（⇒ 基线取晚的 ⇒ 相对首测的百分比更保守）。
func pickBaselineSample(samples []baselineSample) (baselineSample, bool) {
	if len(samples) == 0 {
		return baselineSample{}, false
	}
	s := append([]baselineSample(nil), samples...)
	sort.Slice(s, func(i, j int) bool { return s[i].kwh < s[j].kwh })
	// ⚠️ 奇数个：正中那一个；偶数个：**靠后**的那一个
	//   （容量仍取两个中间值的平均 ⇒ 与 [medianOfFloats] 一致；
	//     日期取靠后的 ⇒ 基线取晚的 ⇒ 相对首测的百分比更保守）。
	idx := len(s) / 2
	return s[idx], true
}

// fullChargeCandidate 一个**满充点**候选（时刻 ＋ 车机满电标定续航）。
type fullChargeCandidate struct {
	at      time.Time
	ratedKm *float64
}

// pickLatestFullCharge 挑出「最近一次满充点」的车机满电续航（km）**与那一刻**。
//
// ⚠️ 口径：**满充点** = 结束电量 **100%**（严格档，v0.35.0 起那套），
//
//	且车机给了满电标定续航 ⇒ 取**日期最新**的那一个。
//
// ⚠️ **数值与时刻必须来自同一个点**（界面上「448 km · 08-02 那次」是一件事）。
func pickLatestFullCharge(cands []fullChargeCandidate) (float64, time.Time, bool) {
	best := -1
	for i, c := range cands {
		if c.ratedKm == nil {
			continue
		}
		if best < 0 || c.at.After(cands[best].at) {
			best = i
		}
	}
	if best < 0 {
		return 0, time.Time{}, false
	}
	return *cands[best].ratedKm, cands[best].at, true
}
