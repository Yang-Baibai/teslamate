package main

import (
	"math"
	"testing"
)

// ⚠️ **v0.35.0：电池衰减判定的回归测试。**
//
// 夹具是**本机真实的 17 个严格档满充点**（2026-08-09 ~ 2026-10-24），
// 期望值来自**独立复算**（Python 自写 OLS，与本 Go 实现逐位吻合）。
//
// 这条测试的价值在于：它把「表面速率 −0.1375 度/1000km」
// 与「扣掉阶跃后的连续速率 −0.0595」这两个数**钉死** ——
// 以后任何人改了这个判定，测试会立刻告诉他改坏了。

var fixtureStrict = []decayPoint{
	{date: "2026-08-09", km: 91791.315360, kwh: 66.189100},
	{date: "2026-09-19", km: 93945.122462, kwh: 66.370140},
	{date: "2026-09-25", km: 96158.322567, kwh: 66.149680},
	{date: "2026-09-26", km: 96255.161572, kwh: 66.110260},
	{date: "2026-09-28", km: 96697.693592, kwh: 65.790520},
	{date: "2026-09-28", km: 96697.693592, kwh: 65.829940},
	{date: "2026-09-28", km: 96791.508598, kwh: 65.670800},
	{date: "2026-09-30", km: 97118.862614, kwh: 65.270760},
	{date: "2026-10-02", km: 97536.034633, kwh: 65.289740},
	{date: "2026-10-03", km: 97726.898642, kwh: 65.568600},
	{date: "2026-10-04", km: 98163.746662, kwh: 65.070740},
	{date: "2026-10-05", km: 98246.699666, kwh: 65.050300},
	{date: "2026-10-07", km: 99761.260738, kwh: 65.409460},
	{date: "2026-10-11", km: 100298.348764, kwh: 65.409460},
	{date: "2026-10-18", km: 101118.491803, kwh: 64.990440},
	{date: "2026-10-22", km: 101237.802809, kwh: 65.248860},
	{date: "2026-10-24", km: 101350.003814, kwh: 65.170020},
}

func near(t *testing.T, name string, got, want, tol float64) {
	t.Helper()
	if math.Abs(got-want) > tol {
		t.Errorf("%s = %.6f, want %.6f (±%.6f)", name, got, want, tol)
	}
}

func TestBatteryDecayStrictTier(t *testing.T) {
	d := analyzeBatteryDecay(fixtureStrict, 12, 76)
	if d == nil {
		t.Fatal("decay = nil")
	}
	if d.N != 17 {
		t.Errorf("N = %d, want 17", d.N)
	}
	if d.ExcludedN != 12 {
		t.Errorf("ExcludedN = %d, want 12", d.ExcludedN)
	}
	near(t, "SpanKm", d.SpanKm, 9558.688454, 0.001)

	// 朴素线性
	near(t, "NaivePer1000Km", d.NaivePer1000Km, -0.1375, 0.002)
	near(t, "NaiveCiLo", d.NaiveCiLo, -0.1940, 0.003)
	near(t, "NaiveCiHi", d.NaiveCiHi, -0.0809, 0.003)
	near(t, "R2Linear", d.R2Linear, 0.6412, 0.002)
	if !d.NaiveSignificant {
		t.Errorf("NaiveSignificant should be true (t = -5.18)")
	}

	// 阶跃
	if !d.HasStep {
		t.Fatal("HasStep = false, want true")
	}
	if d.StepDate != "2026-09-28" {
		t.Errorf("StepDate = %s, want 2026-09-28", d.StepDate)
	}
	near(t, "StepKm", d.StepKm, 96791.508598, 0.001)
	near(t, "StepKwh", d.StepKwh, -0.5624, 0.003)
	near(t, "StepCiLo", d.StepCiLo, -0.8697, 0.005)
	near(t, "StepCiHi", d.StepCiHi, -0.2551, 0.005)
	near(t, "R2Step", d.R2Step, 0.8292, 0.002)
	if !d.StepSignificant {
		t.Errorf("StepSignificant should be true (t = -3.92)")
	}

	// 扣掉阶跃后的连续速率
	near(t, "UnderlyingPer1000Km", d.UnderlyingPer1000Km, -0.0595, 0.002)
	near(t, "UnderlyingCiLo", d.UnderlyingCiLo, -0.1184, 0.003)
	near(t, "UnderlyingCiHi", d.UnderlyingCiHi, -0.0006, 0.005)
	// 朴素速率把连续速率高估了 2.31 倍
	near(t, "NaiveOverstate", d.NaiveOverstate, 2.31, 0.02)

	// 两段单独看都不显著
	if d.PreN != 6 || d.PostN != 11 {
		t.Errorf("segment sizes = %d/%d, want 6/11", d.PreN, d.PostN)
	}
	near(t, "PrePer1000Km", d.PrePer1000Km, -0.0733, 0.003)
	near(t, "PostPer1000Km", d.PostPer1000Km, -0.0507, 0.003)
	if d.PreSignificant {
		t.Errorf("PreSignificant should be false (t = -1.72)")
	}
	if d.PostSignificant {
		t.Errorf("PostSignificant should be false (t = -1.36)")
	}
}

func TestBatteryDecayTooFewPoints(t *testing.T) {
	d := analyzeBatteryDecay(fixtureStrict[:5], 24, 20)
	if d == nil {
		t.Fatal("decay = nil")
	}
	if d.N != 5 {
		t.Errorf("N = %d, want 5", d.N)
	}
	if d.HasStep {
		t.Errorf("HasStep should be false with 5 points")
	}
	if d.Caveat == "" {
		t.Errorf("missing caveat for insufficient sample")
	}
}

func TestBatteryDecayFlatSeriesHasNoStep(t *testing.T) {
	// 完全平的序列：既不该报阶跃，也不该报显著趋势。
	var pts []decayPoint
	for i := 0; i < 12; i++ {
		pts = append(pts, decayPoint{
			date: "2026-01-01",
			km:   100000 + float64(i)*1000,
			kwh:  65.0,
		})
	}
	d := analyzeBatteryDecay(pts, 0, 300)
	if d == nil {
		t.Fatal("decay = nil")
	}
	if d.HasStep {
		t.Errorf("HasStep should be false on a flat series")
	}
	if d.NaiveSignificant {
		t.Errorf("NaiveSignificant should be false on a flat series")
	}
	if math.Abs(d.NaivePer1000Km) > 1e-6 {
		t.Errorf("NaivePer1000Km = %f, want 0", d.NaivePer1000Km)
	}
}
