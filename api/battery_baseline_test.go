package main

import (
	"testing"
	"time"
)

func bs(kwh float64, day int) baselineSample {
	return baselineSample{kwh: kwh, at: time.Date(2026, 11, day, 0, 0, 0, 0, time.UTC)}
}

// ⚠️ **v0.200.0**：基线**日期**与基线**容量**必须是**同一个点**。
func TestPickBaselineSampleMatchesMedian(t *testing.T) {
	// 三个点，容量顺序与时间顺序**不一致** ⇒ 才测得出「排序后取中位数」。
	samples := []baselineSample{bs(66.5, 10), bs(64.0, 11), bs(65.0, 12)}
	got, ok := pickBaselineSample(samples)
	if !ok {
		t.Fatal("不该是 false")
	}
	// 排序后：64.0（11 号）/ 65.0（12 号）/ 66.5（10 号）⇒ 中位数 = 65.0（那天是 12 号）
	if got.kwh != 65.0 {
		t.Errorf("基线容量不对：%v", got.kwh)
	}
	if got.at.Day() != 12 {
		t.Errorf("基线日期必须与基线容量同源（应是 12 号）：%v", got.at)
	}
}

// ⚠️ 偶数个时取**靠后**的那个（⇒ 相对首测的百分比更保守）。
func TestPickBaselineSampleEvenCount(t *testing.T) {
	samples := []baselineSample{bs(64.0, 10), bs(66.0, 11)}
	got, ok := pickBaselineSample(samples)
	if !ok {
		t.Fatal("不该是 false")
	}
	if got.at.Day() != 11 {
		t.Errorf("偶数个时应取靠后的那个：%v", got.at)
	}
}

// ⚠️ 没有点 ⇒ 不给数（宁可不说，也不说半句）。
func TestPickBaselineSampleEmpty(t *testing.T) {
	if _, ok := pickBaselineSample(nil); ok {
		t.Error("没有点就不该给基线")
	}
}

// ⚠️ 最近一次满充点：取**日期最新**且**有**满电续航的那个。
func TestPickLatestFullCharge(t *testing.T) {
	r1, r2, r3 := 500.0, 512.0, 508.0
	cands := []fullChargeCandidate{
		{at: time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC), ratedKm: &r1},
		{at: time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC), ratedKm: &r2},
		{at: time.Date(2026, 10, 1, 0, 0, 0, 0, time.UTC), ratedKm: &r3},
	}
	got, at, ok := pickLatestFullCharge(cands)
	if !ok {
		t.Fatal("不该是 false")
	}
	if got != 512.0 {
		t.Errorf("应取日期最新的那个（512）：%v", got)
	}
	if at.Month() != 11 {
		t.Errorf("时刻必须与数值同源（应是 11 月）：%v", at)
	}
}

// ⚠️ 没有满电续航的候选 ⇒ 跳过（**不许**拿别的数凑）。
func TestPickLatestFullChargeSkipsNil(t *testing.T) {
	r1 := 500.0
	cands := []fullChargeCandidate{
		{at: time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC), ratedKm: nil},
		{at: time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC), ratedKm: &r1},
	}
	got, _, ok := pickLatestFullCharge(cands)
	if !ok {
		t.Fatal("不该是 false")
	}
	if got != 500.0 {
		t.Errorf("应跳过没有续航的那个：%v", got)
	}
	if _, _, ok := pickLatestFullCharge(nil); ok {
		t.Error("没有候选就不该给数")
	}
}
