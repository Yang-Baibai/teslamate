package main

import (
	"strings"
	"testing"
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
