package main

import (
	"strings"
	"testing"
)

// ⚠️ **v0.192.0：充电习惯与累计循环的护栏。**
//
// 三条：
//  1. **一句话**是服务端渲染的（App 卡片与体检报告印**同一句** ⇒ ADR #101）；
//  2. **口径说明每行不超宽**（ⓘ 的行宽上限 96 是同一个数）；
//  3. **没有充电记录 ⇒ 不给数**（宁可不说，也不说半句）。

// buT_Usage 线上真实形状（2026-11-26 那份）。
func buT_Usage() *BatteryUsage {
	efc := 27.0651
	efcCharger := 27.3112
	ratio := 94.76
	charger := 2141.2
	battery := 2122.4
	return &BatteryUsage{
		Sessions:      87,
		DcSessions:    79,
		AcSessions:    8,
		DcEnergyRatio: &ratio,
		ChargerKwh:    &charger,
		BatteryKwh:    &battery,
		Efc:           &efc,
		EfcByCharger:  &efcCharger,
	}
}

// 一句话：**逐字**钉住（App 卡片与体检报告印的是同一句）。
func TestBatteryUsageLine(t *testing.T) {
	u := buT_Usage()
	got := batteryUsageLine(u)
	want := "累计等效满充循环 27.1 次（直流 79 / 交流 8 次充电）"
	if got != want {
		t.Errorf("一句话不符：\n got %q\nwant %q", got, want)
	}
	// 没有循环次数 ⇒ 不给这一句（不编）。
	if batteryUsageLine(&BatteryUsage{Sessions: 3}) != "" {
		t.Error("没有循环次数时不该给这一句")
	}
	if batteryUsageLine(nil) != "" {
		t.Error("nil 时不该给这一句")
	}
}

// 口径说明：**一行一个事实**，每行不许超宽。
func TestBatteryUsageNoteLinesWithinWidth(t *testing.T) {
	u := buT_Usage()
	u.Line = batteryUsageLine(u)
	u.Note = batteryUsageNote(u)
	for _, line := range strings.Split(u.Note, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		if w := copyVisualWidth(line); w > noteLineWidthLimit {
			t.Errorf("口径行宽 %d > %d：%q", w, noteLineWidthLimit, line)
		}
	}
	// ⚠️ **不许出现「伤电池」这类断言**（没有官方依据 ⇒ 反幻觉）。
	for _, banned := range []string{"伤电池", "伤电芯", "缩短寿命", "不健康"} {
		if strings.Contains(u.Note, banned) {
			t.Errorf("口径里出现了没有官方依据的断言 %q：%q", banned, u.Note)
		}
	}
}

// 没有充电记录 ⇒ 不给数。
func TestBatteryUsageNoCharges(t *testing.T) {
	if batteryUsageLine(&BatteryUsage{}) != "" {
		t.Error("没有充电记录时不该给结论句")
	}
	if batteryUsageNote(&BatteryUsage{}) == "" {
		t.Error("没有充电记录时也该有一句说明（别让界面空着）")
	}
}
