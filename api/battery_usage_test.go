package main

import (
	"strings"
	"testing"
)

// ⚠️ **v0.192.0 / v0.196.0：充电习惯与累计循环的护栏。**
//
// 四条：
//  1. **两句话**都是服务端渲染的（App 卡片与体检报告印**同一句** ⇒ ADR #101）；
//     ⇒ 两个用途不同的句子**各自有名**（batteryCycleLine / batteryUsageLine）；
//  2. **口径说明每行不超宽**（ⓘ 的行宽上限 96 是同一个数）；
//  3. **没有充电记录 ⇒ 不给数**（宁可不说，也不说半句）；
//  4. **只报事实**，不许出现「伤电池」这类**没有官方依据**的断言（ADR #46）。

// buT_Usage 线上真实形状（2026-11-26 那份）。
func buT_Usage() *BatteryUsage {
	efc := 27.0651
	efcCharger := 27.3112
	ratio := 94.76
	charger := 2141.2
	battery := 2122.4
	avg := 35.1
	days := 0
	return &BatteryUsage{
		Sessions:            87,
		DcSessions:          79,
		AcSessions:          8,
		DcEnergyRatio:       &ratio,
		ChargerKwh:          &charger,
		BatteryKwh:          &battery,
		Efc:                 &efc,
		EfcByCharger:        &efcCharger,
		AvgStartSoc:         &avg,
		DeepDischargeN:      12,
		DaysSinceLastCharge: &days,
	}
}

// 累计循环那一句：**逐字**钉住（App 卡片与体检报告印的是同一句）。
//
// ⚠️ **v0.196.0**：它与「充电习惯」那句是两个用途不同的句子 ⇒ 各自有名。
func TestBatteryCycleLine(t *testing.T) {
	u := buT_Usage()
	got := batteryCycleLine(u)
	want := "累计等效满充循环 27.1 次（直流 79 / 交流 8 次充电）"
	if got != want {
		t.Errorf("累计循环那句不符：got %q want %q", got, want)
	}
	// 没有循环次数 ⇒ 不给这一句（不编）。
	if batteryCycleLine(&BatteryUsage{Sessions: 3}) != "" {
		t.Error("没有循环次数时不该给这一句")
	}
	if batteryCycleLine(nil) != "" {
		t.Error("nil 时不该给这一句")
	}
}

// ⚠️ **v0.196.0：充电习惯那一句**（**只报事实**，不下方向性结论）。
func TestBatteryUsageHabitLine(t *testing.T) {
	u := buT_Usage()
	got := batteryUsageLine(u)
	want := "充电习惯：平均从 35% 开始充 · 12 次从 10% 以下开始充 · 上次充电是今天"
	if got != want {
		t.Errorf("充电习惯那句不符：got %q want %q", got, want)
	}
	// ⚠️ **不许出现「伤电池」这类断言**（没有官方依据 ⇒ 反幻觉，ADR #46）。
	for _, banned := range []string{"伤电池", "伤电芯", "缩短寿命", "不健康"} {
		if strings.Contains(got, banned) {
			t.Errorf("充电习惯那句出现了没有官方依据的断言 %q：%q", banned, got)
		}
	}
	// 没有充电记录 ⇒ 不给这一句。
	if batteryUsageLine(&BatteryUsage{}) != "" {
		t.Error("没有充电记录时不该给这句话")
	}
	// 「昨天」与「N 天前」两种说法都要有（口径清楚 ⇒ 用户能对上账）。
	y := buT_Usage()
	one := 1
	y.DaysSinceLastCharge = &one
	if !strings.Contains(batteryUsageLine(y), "昨天") {
		t.Errorf("1 天前该说「昨天」：%q", batteryUsageLine(y))
	}
	three := 3
	y.DaysSinceLastCharge = &three
	if !strings.Contains(batteryUsageLine(y), "3 天前") {
		t.Errorf("3 天前该说「3 天前」：%q", batteryUsageLine(y))
	}
}

// 口径说明：**一行一个事实**，每行不许超宽。
func TestBatteryUsageNoteLinesWithinWidth(t *testing.T) {
	u := buT_Usage()
	u.Line = batteryCycleLine(u)
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
	if batteryCycleLine(&BatteryUsage{}) != "" {
		t.Error("没有充电记录时不该给结论句")
	}
	if batteryUsageNote(&BatteryUsage{}) == "" {
		t.Error("没有充电记录时也该有一句说明（别让界面空着）")
	}
}
