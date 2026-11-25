package main

import (
	"strings"
	"testing"
	"time"
)

// brT_I / brT_Time 小工具（取指针）。
func brT_I(v int) *int { return &v }

func brT_Time(s string) *time.Time {
	t, err := time.Parse(time.RFC3339, s)
	if err != nil {
		panic(err)
	}
	return &t
}

// ⚠️ **v0.190.0：电池体检报告的护栏。**
//
// 四条规矩：
//  1. 报告里的数**逐位等于** /battery 给的那份（同一个来源，不另算）；
//  2. 报告正文**一行一个事实**，每行不许超宽（ⓘ 的行宽上限 96 是同一个数）；
//  3. 没有数据 ⇒ **不编**（只说「还没有满充记录」）；
//  4. 衰减结论那一句的措辞**逐字钉住**（客户端现在透传它 ⇒ 两端同一句话）。

// brT_Health 线上真实形状（2026-11-25 那份 /battery）。
func brT_Health() *BatteryHealth {
	hp := 83.14471301020407
	bl := 98.8033221500129
	cap := 65.18545499999999
	days := 104
	span := 11559.216550000012
	decay := &BatteryDecay{
		N:                   20,
		StrictN:             20,
		ExcludedN:           15,
		ExcludedNearFullN:   15,
		DroppedEmpty:        1,
		SpanDays:            104,
		HasStep:             true,
		StepDate:            "2026-09-28",
		StepKwh:             -0.766,
		Conclusion:          DecayNotDetectable,
		UnderlyingPer1000Km: 0.002,
		UnderlyingCiLo:      -0.052,
		UnderlyingCiHi:      0.055,
	}
	decay.Headline = decayHeadline(decay)
	decay.Note = "20 个 100% 满充点 · 另有 15 场充到 90% 以上但没充满 · 11559 公里 / 104 天 · 剔除 1 场没实际充电的\n\n" +
		"有一次 -0.77 度落差，已扣除（车机重新标定还是电池变化，分不清）。"
	return &BatteryHealth{
		CurrentCapacityKwh:      &cap,
		HealthPercent:           &hp,
		HealthPercentOfBaseline: &bl,
		Measurement: &BatteryMeasurement{
			At:        brT_Time("2026-11-21T05:45:49.056Z"),
			AgeDays:   brT_I(4),
			Note:      "基于 11-21 那次充电实测（4 天前）",
			NoteShort: "11-21 实测",
		},
		Decay:          decay,
		SeriesSpanDays: &days,
		OdometerSpanKm: &span,
	}
}

// 标题与正文：该有的行都有，且**逐字**取服务端那份。
func TestBatteryReportTitleAndBody(t *testing.T) {
	title, body := renderBatteryReport("Model 3", brT_Health())
	if !strings.Contains(title, "电池体检报告") {
		t.Errorf("标题里没有「电池体检报告」：%q", title)
	}
	if !strings.Contains(title, "Model 3") {
		t.Errorf("标题里没有车名：%q", title)
	}
	want := []string{
		"电池健康：当前 83.1% · 11-21 实测",
		"相对首测 98.8%",
		"现在还能装 65.2 度",
		"连续衰减：检不出趋势（95% 区间 -0.052 ~ 0.055，跨过 0）",
		"20 个 100% 满充点",
		"有一次 -0.77 度落差，已扣除",
	}
	for _, w := range want {
		if !strings.Contains(body, w) {
			t.Errorf("正文里少了 %q：\n%s", w, body)
		}
	}
}

// ⚠️ **一行一个事实** ⇒ 每行都不许超宽（与 ⓘ 同一个上限 96）。
func TestBatteryReportLinesWithinWidth(t *testing.T) {
	_, body := renderBatteryReport("Model 3", brT_Health())
	for _, line := range strings.Split(body, "\n") {
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		if w := copyVisualWidth(line); w > noteLineWidthLimit {
			t.Errorf("报告行宽 %d > %d：%q", w, noteLineWidthLimit, line)
		}
	}
}

// ⚠️ **报告里的数必须与 /battery 逐位相等**（同一个来源，不另算）。
func TestBatteryReportReusesHealthNumbers(t *testing.T) {
	h := brT_Health()
	_, body := renderBatteryReport("Model 3", h)
	if !strings.Contains(body, "83.1%") || !strings.Contains(body, "98.8%") {
		t.Errorf("正文里的健康度 / 相对首测与服务端不一致：\n%s", body)
	}
	if !strings.Contains(body, "65.2 度") {
		t.Errorf("正文里的可用容量与服务端不一致：\n%s", body)
	}
	if got := batteryReportCapacityKwh(h); got != "65.2" {
		t.Errorf("容量格式化应是 65.2，实际 %q", got)
	}
}

// ⚠️ **没有数据就不编**（反幻觉）：只说清「还没有满充记录」。
func TestBatteryReportNoData(t *testing.T) {
	_, body := renderBatteryReport("Model 3", &BatteryHealth{})
	if !strings.Contains(body, "还没有满充记录") {
		t.Errorf("没有数据时该说「还没有满充记录」：%q", body)
	}
	if strings.Contains(body, "%") || strings.Contains(body, "度") {
		t.Errorf("没有数据时不该出现任何数：%q", body)
	}
	if batteryReportHasData(&BatteryHealth{}) {
		t.Error("没有数据时不该认为「有得报」")
	}
	if !batteryReportHasData(brT_Health()) {
		t.Error("有数据时该认为「有得报」")
	}
}

// ⚠️ **衰减结论那一句的措辞**（客户端现在透传它 ⇒ 两端同一句话）。
func TestDecayHeadlineWording(t *testing.T) {
	cases := []struct {
		name string
		d    *BatteryDecay
		want string
	}{
		{"检不出", &BatteryDecay{Conclusion: DecayNotDetectable, UnderlyingCiLo: -0.052, UnderlyingCiHi: 0.055, HasStep: true},
			"连续衰减：检不出趋势（95% 区间 -0.052 ~ 0.055，跨过 0）"},
		{"区分不开", &BatteryDecay{Conclusion: DecayWeak, NaivePer1000Km: -0.054, NaiveCiLo: -0.09, NaiveCiHi: 0.018},
			"连续衰减：与 0 区分不开（测到 -0.054 度/1000 公里，95% 区间 -0.090 ~ 0.018）"},
		{"检得出", &BatteryDecay{Conclusion: DecayDetectable, NaivePer1000Km: -0.12, NaiveCiLo: -0.2, NaiveCiHi: -0.04},
			"连续衰减 每 1000 公里 -0.120 度（95% 区间 -0.200 ~ -0.040）"},
		{"样本不够", &BatteryDecay{Conclusion: DecayInsufficient},
			"样本不足以判定衰减趋势"},
	}
	for _, c := range cases {
		got := decayHeadline(c.d)
		if got != c.want {
			t.Errorf("[%s] 措辞不符：\n got %q\nwant %q", c.name, got, c.want)
		}
	}
}

// ⚠️ **分析器必须把那句话填上**（客户端就是靠它显示的）。
func TestAnalyzeBatteryDecayFillsHeadline(t *testing.T) {
	// ⚠️ 用 battery_decay_test.go 里那份**真实形状**的夹具（同一个包）。
	d := analyzeBatteryDecay(fixtureStrict, 12, 3, 76, 1)
	if d == nil {
		t.Fatal("decay = nil")
	}
	if d.Headline == "" {
		t.Error("分析器没有填 Headline ⇒ 客户端会退回它本地那份措辞（两份）")
	}
}
