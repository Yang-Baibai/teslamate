package main

import (
	"strings"
	"testing"
	"time"
)

// TestCabinHotThresholdFromRealData 阈值与文案都必须对得上**真车实测**。
//
// ⚠️ 依据（2026-11-04 查库，71 天有采样的日子）：座舱峰值 ≥ 45℃ 的有 17 天（24%），
// 最高 62.4℃（2026-09-29，车外 34.0℃）⇒ 阈值取 45℃。
func TestCabinHotThresholdFromRealData(t *testing.T) {
	inside := 62.4
	outside := 34.0
	ch := cabinHeat(&inside, &outside, nil, false)
	if ch == nil || !ch.Hot {
		t.Fatalf("62.4℃ 必须是闷热：%+v", ch)
	}
	if !strings.Contains(ch.Text, "62.4") || !strings.Contains(ch.Text, "34.0") {
		t.Fatalf("文案必须带两个温度：%q", ch.Text)
	}
	if !strings.Contains(ch.Text, "上车前") {
		t.Fatalf("文案必须说该做什么：%q", ch.Text)
	}

	// 45℃ 恰好达阈值 ⇒ 算热（>=）
	at := 45.0
	if ch = cabinHeat(&at, &outside, nil, false); ch == nil || !ch.Hot {
		t.Fatalf("45℃ 恰好达阈值应算闷热：%+v", ch)
	}
	// 44.9℃ ⇒ 不热
	cool := 44.9
	if ch = cabinHeat(&cool, &outside, nil, false); ch == nil || ch.Hot || ch.Text != "" {
		t.Fatalf("44.9℃ 不该报闷热：%+v", ch)
	}
}

// TestCabinHeatUnknownWhenMissingOrStale 读不到 / 数据太旧 ⇒ **不知道**。
func TestCabinHeatUnknownWhenMissingOrStale(t *testing.T) {
	if got := cabinHeat(nil, nil, nil, false); got != nil {
		t.Fatalf("没读到必须是 nil（不知道 ≠ 正常），得到 %+v", got)
	}
	inside := 60.0
	if got := cabinHeat(&inside, nil, nil, true); got != nil {
		t.Fatalf("数据陈旧必须不给结论，得到 %+v", got)
	}
}

// TestCabinReadingCurrent 车端读数是不是「当前的」——**只看车醒着没有**。
//
// ⚠️ 依据（2026-11-10 查库 + 线上实测）：positions.inside_temp 是**车醒着时**
// 才更新的稀疏列（实测非空率仅 3.5%）⇒ 车睡着后它停在停车那一刻的值。
// 实测车停了 22 小时，App 仍显示停车时的「车内 25.8℃」⇒ 假新鲜度 / 假安全。
func TestCabinReadingCurrent(t *testing.T) {
	if !cabinReadingCurrent("online") {
		t.Fatal("车醒着 ⇒ 读数是当前的")
	}
	for _, st := range []string{"asleep", "offline", "", "unknown"} {
		if cabinReadingCurrent(st) {
			t.Fatalf("%q ⇒ 读数不是当前的（宁可说不知道，不可编）", st)
		}
	}
}

// TestCabinReadingCurrentSameAsClimate 座舱温度与空调**必须同一套判据**。
//
// ⚠️ 为什么：两者读的是**同一行 positions 数据** ⇒ 「是不是当前的」只能有一个答案。
// 原来一个用 24 小时门槛（DataStale）、一个用 pollSnapshotStale ⇒ 会给出相反答案。
func TestCabinReadingCurrentSameAsClimate(t *testing.T) {
	for _, st := range []string{"online", "asleep", "offline", "", "unknown", "ONLINE", " online "} {
		if got, want := cabinReadingCurrent(st), !pollSnapshotStale(st); got != want {
			t.Fatalf("state=%q ⇒ 座舱温度判据=%v，空调判据=%v ⇒ 两套口径", st, got, want)
		}
	}
}

// TestCabinHeatFollowsCarAwake 车睡着 ⇒ 不给「闷热」结论（哪怕读数很烫）。
//
// ⚠️ 这是 v0.137.0 修的那个 bug：车暴晒后停着（睡着）⇒ 读数停在停车那一刻 ⇒
// 旧口径（24 小时门槛）会把**停车那一刻**的闷热温度当成现在的 ⇒ 一条过期的告警。
func TestCabinHeatFollowsCarAwake(t *testing.T) {
	inside := 55.0
	outside := 30.0
	at := time.Now().Add(-22 * time.Hour)
	// 车睡着 ⇒ 读数不是当前的 ⇒ 不给结论。
	if got := cabinHeat(&inside, &outside, &at, !cabinReadingCurrent("asleep")); got != nil {
		t.Fatalf("车睡着时不该给闷热结论：%+v", got)
	}
	// 车醒着 ⇒ 给结论，且 `at` 用的是**那次读数的时刻**。
	got := cabinHeat(&inside, &outside, &at, !cabinReadingCurrent("online"))
	if got == nil || !got.Hot {
		t.Fatalf("车醒着时 55℃ 必须是闷热：%+v", got)
	}
	if got.At == nil || !got.At.Equal(at) {
		t.Fatalf("读数时刻必须原样带出：%+v", got.At)
	}
}

// TestCabinHeatTextHasNoDirectionWords 文案不许暗示方向。
func TestCabinHeatTextHasNoDirectionWords(t *testing.T) {
	inside := 55.0
	outside := 30.0
	ch := cabinHeat(&inside, &outside, nil, false)
	for _, bad := range []string{"↑", "↓", "→", "增加", "减少", "上升", "下降", "变高", "变低"} {
		if strings.Contains(ch.Text, bad) {
			t.Fatalf("文案不许暗示方向（%q）：%q", bad, ch.Text)
		}
	}
}

// TestCabinHeatWithoutOutsideTemp 没有车外温度就不许提车外。
func TestCabinHeatWithoutOutsideTemp(t *testing.T) {
	inside := 50.0
	ch := cabinHeat(&inside, nil, nil, false)
	if !strings.Contains(ch.Text, "50.0") || strings.Contains(ch.Text, "车外") {
		t.Fatalf("没有车外温度就不该提车外：%q", ch.Text)
	}
}
