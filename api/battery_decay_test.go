package main

import (
	"math"
	"strings"
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

// ⚠️ **v0.41.0（第 24 轮对抗性审计 ②⑧）：夹具改成 16 个点，并带上充电气温。**
//
// 两处变更：
//
// ① 删掉原第 6 行（`charging_processes.id=31`）——
//    它是 `100% → 100%、0.04 度、1 分钟`，
//    车本来就是满的、插了 1 分钟充进 0.04 度 ——
//    是同一场会话被 TeslaMate 拆成两段的尾巴，
//    不是一次**独立的容量测量**（且它还是阶跃点之一）。
// ② 每个点带上 `outside_temp_avg`（充电时气温）——
//    它是混杂因素，不建模就无法判断进度。
var fixtureStrict = []decayPoint{
	{date: "2026-08-09", km: 91791.315360, kwh: 66.189100, tempC: 38.5, hasT: true},
	{date: "2026-09-19", km: 93945.122462, kwh: 66.370140, tempC: 34.5, hasT: true},
	{date: "2026-09-25", km: 96158.322567, kwh: 66.149680, tempC: 22.0, hasT: true},
	{date: "2026-09-26", km: 96255.161572, kwh: 66.110260, tempC: 36.4, hasT: true},
	{date: "2026-09-28", km: 96697.693592, kwh: 65.790520, tempC: 11.0, hasT: true},
	{date: "2026-09-28", km: 96791.508598, kwh: 65.670800, tempC: 29.5, hasT: true},
	{date: "2026-09-30", km: 97118.862614, kwh: 65.270760, tempC: 22.5, hasT: true},
	{date: "2026-10-02", km: 97536.034633, kwh: 65.289740, tempC: 14.5, hasT: true},
	{date: "2026-10-03", km: 97726.898642, kwh: 65.568600, tempC: 18.6, hasT: true},
	{date: "2026-10-04", km: 98163.746662, kwh: 65.070740, tempC: 20.0, hasT: true},
	{date: "2026-10-05", km: 98246.699666, kwh: 65.050300, tempC: 22.0, hasT: true},
	{date: "2026-10-07", km: 99761.260738, kwh: 65.409460, tempC: 27.0, hasT: true},
	{date: "2026-10-11", km: 100298.348764, kwh: 65.409460, tempC: 32.7, hasT: true},
	{date: "2026-10-18", km: 101118.491803, kwh: 64.990440, tempC: 33.0, hasT: true},
	{date: "2026-10-22", km: 101237.802809, kwh: 65.248860, tempC: 27.2, hasT: true},
	{date: "2026-10-24", km: 101350.003814, kwh: 65.170020, tempC: 29.1, hasT: true},
}

func near(t *testing.T, name string, got, want, tol float64) {
	t.Helper()
	if math.Abs(got-want) > tol {
		t.Errorf("%s = %.6f, want %.6f (±%.6f)", name, got, want, tol)
	}
}

func TestBatteryDecayStrictTier(t *testing.T) {
	d := analyzeBatteryDecay(fixtureStrict, 12, 76, 1)
	if d == nil {
		t.Fatal("decay = nil")
	}
	// ⚠️ **v0.41.0：点数从 17 变成 16** ——
	// 原第 6 个点是「100%→100%、0.04 度、1 分钟」的空会话，
	// 不是一次独立测量。
	if d.N != 16 {
		t.Errorf("N = %d, want 16", d.N)
	}
	if d.ExcludedN != 12 {
		t.Errorf("ExcludedN = %d, want 12", d.ExcludedN)
	}
	if d.DroppedEmpty != 1 {
		t.Errorf("DroppedEmpty = %d, want 1", d.DroppedEmpty)
	}
	near(t, "SpanKm", d.SpanKm, 9558.688454, 0.001)

	// 朴素线性
	near(t, "NaivePer1000Km", d.NaivePer1000Km, -0.1360, 0.002)
	near(t, "NaiveCiLo", d.NaiveCiLo, -0.1950, 0.003)
	near(t, "NaiveCiHi", d.NaiveCiHi, -0.0770, 0.003)
	near(t, "R2Linear", d.R2Linear, 0.6380, 0.002)
	if !d.NaiveSignificant {
		t.Errorf("NaiveSignificant should be true")
	}

	// 落差
	if !d.HasStep {
		t.Fatal("HasStep = false, want true")
	}
	if d.StepDate != "2026-09-28" {
		t.Errorf("StepDate = %s, want 2026-09-28", d.StepDate)
	}
	near(t, "StepKm", d.StepKm, 96791.508598, 0.001)
	near(t, "StepKwh", d.StepKwh, -0.6170, 0.003)
	near(t, "StepCiLo", d.StepCiLo, -0.9550, 0.005)
	near(t, "StepCiHi", d.StepCiHi, -0.2790, 0.005)
	near(t, "R2Step", d.R2Step, 0.8350, 0.002)
	if !d.StepSignificant {
		t.Errorf("StepSignificant should be true")
	}

	// 扣掉落差后的连续速率
	//
	// ⚠️ **v0.41.0 最重要的变化**：
	// 剔除空会话后，连续速率的 95% 区间是
	// **[-0.1150, +0.0070] —— 跨过 0 了**，不再显著。
	//
	// v0.39.0 报的「显著」（区间上界 -0.0006）
	// 实际上**是靠那个 0.04 度的空会话撑着的**。
	near(t, "UnderlyingPer1000Km", d.UnderlyingPer1000Km, -0.0540, 0.002)
	near(t, "UnderlyingCiLo", d.UnderlyingCiLo, -0.1150, 0.003)
	near(t, "UnderlyingCiHi", d.UnderlyingCiHi, 0.0070, 0.005)
	if d.UnderlyingSignificant {
		t.Errorf("UnderlyingSignificant should be FALSE now (CI crosses 0)")
	}
	// 朴素速率把连续速率高估了 2.53 倍
	near(t, "NaiveOverstate", d.NaiveOverstate, 2.53, 0.02)

	// 两段单独看都不显著
	if d.PreN != 5 || d.PostN != 11 {
		t.Errorf("segment sizes = %d/%d, want 5/11", d.PreN, d.PostN)
	}
	near(t, "PrePer1000Km", d.PrePer1000Km, -0.0590, 0.003)
	near(t, "PostPer1000Km", d.PostPer1000Km, -0.0510, 0.003)
	if d.PreSignificant {
		t.Errorf("PreSignificant should be false")
	}
	if d.PostSignificant {
		t.Errorf("PostSignificant should be false")
	}
}

func TestBatteryDecayTooFewPoints(t *testing.T) {
	d := analyzeBatteryDecay(fixtureStrict[:5], 24, 20, 0)
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
	d := analyzeBatteryDecay(pts, 0, 300, 0)
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

// ⚠️ **v0.41.0：结论强度与稳健性检查。**
//
// 这些断言直接对应第 24 轮对抗性审计的三条批评：
// ① 气温是混杂因素（结果：加气温后仍然检不出）；
// ② 留一法（结果：16 次只有 1 次仍然显著）；
// ③ 不得把落差说成「物理事件」（它可能是车机重新标定）。
func TestBatteryDecayConclusionStrength(t *testing.T) {
	d := analyzeBatteryDecay(fixtureStrict, 12, 76, 1)
	if d == nil {
		t.Fatal("decay = nil")
	}
	// 本机数据的真实结论：方向一致，但检不出。
	if d.Conclusion != DecayWeak {
		t.Errorf("Conclusion = %s, want %s", d.Conclusion, DecayWeak)
	}
	// 加气温不能把结论翻过来
	if d.AdjustedSignificant {
		t.Errorf("AdjustedSignificant should be false")
	}
	if d.AdjustedCiHi <= 0 {
		t.Errorf("AdjustedCiHi = %.4f, should cross 0", d.AdjustedCiHi)
	}
	// 气温系数为正（气温高的时候车机上报的续航更高）
	if d.TempPerC <= 0 {
		t.Errorf("TempPerC = %.6f, want > 0", d.TempPerC)
	}
	// 气温只解释一小部分残差（不是「大部分」）
	if d.TempR2 >= 0.5 {
		t.Errorf("TempR2 = %.4f, should be < 0.5 (old copy claimed majority)", d.TempR2)
	}
	// 留一法：结论极不稳定
	if d.LeaveOneOutTotal != 16 {
		t.Errorf("LeaveOneOutTotal = %d, want 16", d.LeaveOneOutTotal)
	}
	if d.LeaveOneOutSignificant >= d.LeaveOneOutTotal {
		t.Errorf("LeaveOneOutSignificant = %d/%d, want < total",
			d.LeaveOneOutSignificant, d.LeaveOneOutTotal)
	}
	// caveat 里不得再出现旧那句「残差里大部分是充电时气温」
	// （caveat 里出现「不是『大部分』」这种**否定句**是允许的 —— 要判的是**那句断言本身**。）
	if strings.Contains(d.Caveat, "残差里大部分是充电时气温") {
		t.Errorf("caveat must not claim majority temperature: %s", d.Caveat)
	}
	// 反过来：caveat 必须**给出实测的方差解释比例**
	if !strings.Contains(d.Caveat, "解释残差方差") {
		t.Errorf("caveat must state the measured variance share: %s", d.Caveat)
	}
	// caveat 必须说清落差**可能不是**电池变化
	if !strings.Contains(d.Caveat, "可能不是") {
		t.Errorf("caveat must not assert the drop is a battery event: %s", d.Caveat)
	}
}
