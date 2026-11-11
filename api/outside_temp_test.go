package main

import (
	"testing"
	"time"
)

// ⚠️ **v0.142.0**：车外温度的「新鲜度」口径。
//
// ## 为什么要有这一组
//
// positions.outside_temp 与 positions.inside_temp 是**同一批稀疏行**
//（实测非空数完全相同：61709 行，非空率同为 3.32%，最后一条读数的时刻也相同）
// ⇒ 它们是**同一个读数问题的两个字段**。
//
// v0.137.0 只收口了车内温度 ⇒ 线上出现「车内温度 —」+「车外温度 22.5℃」
// **同屏自相矛盾**（实测车停了 28 小时，22.5℃ 是**停车那一刻**的气温）。
//
// ⇒ 顶层 /status 与续航模型 /range_estimate 必须**同一个判据**。

// TestRangeOutsideTempFollowsCarAwake 读数是不是「当前的」——**只看车醒着没有**。
func TestRangeOutsideTempFollowsCarAwake(t *testing.T) {
	v := 22.5
	at := time.Now().Add(-28 * time.Hour)

	// 车睡着 ⇒ 「当前读数」给 nil（不许拿停车那一刻冒充现在）。
	cur, last, readAt, stale := rangeOutsideTemp(&v, &at, "asleep")
	if cur != nil {
		t.Fatalf("车睡着时不该给当前车外温度，得到 %v", *cur)
	}
	if !stale {
		t.Fatal("车睡着 ⇒ 读数陈旧")
	}
	// ⚠️ 但**信息不许丢**：上次读到的值与它的时刻照样给。
	if last == nil || *last != 22.5 {
		t.Fatalf("上次读到的车外温度必须保留，得到 %v", last)
	}
	if readAt == nil || !readAt.Equal(at) {
		t.Fatalf("读数时刻必须原样保留，得到 %v", readAt)
	}

	// 车醒着 ⇒ 给当前读数。
	cur, _, _, stale = rangeOutsideTemp(&v, &at, "online")
	if cur == nil || *cur != 22.5 {
		t.Fatalf("车醒着时必须给当前车外温度，得到 %v", cur)
	}
	if stale {
		t.Fatal("车醒着 ⇒ 读数不陈旧")
	}
}

// TestRangeOutsideTempSameJudgmentAsStatus 续航那一路与顶层 **必须同一口径**。
//
// ⚠️ 为什么：两边读的是**同一份 positions 数据** ⇒ 「是不是当前的」只能有一个答案。
// 若两边各判一次 ⇒ 会出现「顶层说读不到、续航却按 22.5℃ 修正」的分裂。
func TestRangeOutsideTempSameJudgmentAsStatus(t *testing.T) {
	for _, st := range []string{"online", "asleep", "offline", "", "unknown", "ONLINE", " online "} {
		cur, _, _, stale := rangeOutsideTemp(nil, nil, st)
		if cur != nil {
			t.Fatalf("state=%q ⇒ 续航那一路不该给当前温度，得到 %v", st, *cur)
		}
		if want := !cabinReadingCurrent(st); stale != want {
			t.Fatalf("state=%q ⇒ 续航判据=%v，顶层判据=%v ⇒ 两套口径", st, stale, !cabinReadingCurrent(st))
		}
	}
}

// TestStaleOutsideTempDoesNotCorrectRange 陈旧的气温**不许**参与温度修正。
//
// ⚠️ 28 小时前的 22.5℃ 当成今天的气温 ⇒ 续航被算高（假安全）。
// ⇒ 读数陈旧 ⇒ 温度修正系数恒为 1.0（不修），而不是拿旧值修。
func TestStaleOutsideTempDoesNotCorrectRange(t *testing.T) {
	m := &rangeModel{Buckets: []rangeBucket{{MinKph: 0, MaxKph: 60, WhPerKm: 180, Samples: 5}}}
	stale := 22.5
	if got := m.tempFactor(&stale); got <= 1.0 {
		t.Fatalf("22.5℃ 的修正系数应 > 1（做热的修正），得到 %v", got)
	}
	// ⚠️ 陈旧 ⇒ 传 nil ⇒ 不修正（不是 0℃ ⇒ 也不是 -∞ 修正）。
	if got := m.tempFactor(nil); got != 1.0 {
		t.Fatalf("陈旧读数 ⇒ 不做温度修正，系数应为 1.0，得到 %v", got)
	}
}
