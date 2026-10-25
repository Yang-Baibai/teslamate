package main

import (
	"math"
	"testing"
)

// ---------- limit 解析 ----------

// TestParseDrivingProfileLimit 越界必须报错 —— 默默截断会让 App 以为
// 「就这么多趟了」，而真实原因只是被砍掉了。
func TestParseDrivingProfileLimit(t *testing.T) {
	cases := []struct {
		name    string
		in      string
		want    int
		wantErr bool
	}{
		{"空串用默认值", "", 20, false},
		{"空白用默认值", "   ", 20, false},
		{"正常值", "30", 30, false},
		{"下限 1", "1", 1, false},
		{"上限 60", "60", 60, false},
		{"超过上限报错", "61", 0, true},
		{"远远超过上限报错", "1000", 0, true},
		{"零报错", "0", 0, true},
		{"负数报错", "-1", 0, true},
		{"非数字报错", "abc", 0, true},
		{"小数报错", "3.5", 0, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, err := parseDrivingProfileLimit(tc.in)
			if tc.wantErr {
				if err == nil {
					t.Fatalf("parseDrivingProfileLimit(%q) 应该报错，实际 %v", tc.in, got)
				}
				return
			}
			if err != nil {
				t.Fatalf("parseDrivingProfileLimit(%q): %v", tc.in, err)
			}
			if got != tc.want {
				t.Fatalf("parseDrivingProfileLimit(%q) = %d, want %d", tc.in, got, tc.want)
			}
		})
	}
}

// ---------- 评分公式 ----------

// TestDrivingScore 参考值必须逐条对上 ——
// 这些数是评审员在真实数据上实测跑出来的（2026-10-25），
// 服务端算出来的分数如果和它们不一致，说明口径被改坏了。
func TestDrivingScore(t *testing.T) {
	cases := []struct {
		name string
		in   drivingProfileDerived
		want float64
	}{
		{
			// drive_id=491（2026-10-24，85.4 km）：
			// 急刹车 11 次 → 12.88 次/100km → ×1.5 = 19.32
			// 急加速 2 次  → 2.34 次/100km
			// 超速 13.45%  → 13.45-3 = 10.45
			// 能耗 165.0 Wh/km → (165-150)/5 = 3.0
			// 夜间 0%
			// 100 - 19.32 - 2.34 - 10.45 - 3.0 - 0 = 64.89
			name: "drive 491",
			in: drivingProfileDerived{
				BrakePer100Km: 12.88, AccelPer100Km: 2.34,
				OverSpeedPct: 13.45, WhPerKmGross: 165.0, NightPct: 0,
			},
			want: 64.89,
		},
		{
			// drive_id=273（2026-09-29，15.9 km，夜间）：
			// 急刹车 6 次 → 37.74 次/100km → ×1.5 = 56.6 → 封顶 30
			// 急加速 3 次 → 18.87 次/100km
			// 超速 0% → 0；能耗 255.4 Wh/km → (255.4-150)/5 = 21.08 → 封顶 15
			// 夜间 99.91% → 99.91×0.2 = 19.98 → 封顶 15
			// 100 - 30 - 18.87 - 0 - 15 - 15 = 21.13
			name: "drive 273",
			in: drivingProfileDerived{
				BrakePer100Km: 37.74, AccelPer100Km: 18.87,
				OverSpeedPct: 0, WhPerKmGross: 255.4, NightPct: 99.91,
			},
			want: 21.13,
		},
		{
			// drive_id=179 / 443：零急刹零急加速零超速，能耗 149.4 / 132.5（都低于 150）→ 满分
			name: "drive 179",
			in:   drivingProfileDerived{WhPerKmGross: 149.4},
			want: 100,
		},
		{
			name: "drive 443",
			in:   drivingProfileDerived{WhPerKmGross: 132.5},
			want: 100,
		},
		{
			// 每一项都爆表：30+20+20+15+15 = 100 → 恰好 0 分，不会是负数
			name: "全部爆表不出现负分",
			in: drivingProfileDerived{
				BrakePer100Km: 1000, AccelPer100Km: 1000,
				OverSpeedPct: 100, WhPerKmGross: 1000, NightPct: 100,
			},
			want: 0,
		},
		{
			// 能耗刚好 150：GREATEST(0, 150-150)/5 = 0 → 不扣分
			name: "能耗正好 150 不扣分",
			in:   drivingProfileDerived{WhPerKmGross: 150},
			want: 100,
		},
		{
			// 超速 3% 是基准线：GREATEST(0, 3-3) = 0 → 不扣分
			name: "超速 3% 不扣分",
			in:   drivingProfileDerived{OverSpeedPct: 3},
			want: 100,
		},
		{
			// 超速 23% → (23-3) = 20 → 恰好封顶 20
			name: "超速封顶 20",
			in:   drivingProfileDerived{OverSpeedPct: 23},
			want: 80,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := drivingScore(tc.in)
			if math.Abs(got-tc.want) > 0.011 {
				t.Fatalf("drivingScore(%+v) = %.4f, want %.4f", tc.in, got, tc.want)
			}
		})
	}
}

// TestDrivingScoreNeverOutOfRange 分数永远落在 0~100。
func TestDrivingScoreNeverOutOfRange(t *testing.T) {
	inputs := []drivingProfileDerived{
		{},
		{BrakePer100Km: 1e9, AccelPer100Km: 1e9, OverSpeedPct: 1e9, WhPerKmGross: 1e9, NightPct: 1e9},
		{BrakePer100Km: -100, AccelPer100Km: -100, OverSpeedPct: -100, WhPerKmGross: -100, NightPct: -100},
	}
	for _, in := range inputs {
		got := drivingScore(in)
		if got < 0 || got > 100 {
			t.Fatalf("drivingScore(%+v) = %f，跑出了 0~100", in, got)
		}
	}
}

// ---------- 派生指标 ----------

// TestDeriveDrivingProfile 派生公式（每百公里次数、Wh/km、占比）必须对得上。
func TestDeriveDrivingProfile(t *testing.T) {
	d := deriveDrivingProfile(drivingProfileRaw{
		DistanceKm: 85.4,
		HardBrake:  11,
		HardAccel:  2,
		OverN:      524,
		NValid:     3897,
		NClean:     3828,
		NightN:     0,
		Kwh:        14.09,
	})
	if math.Abs(d.BrakePer100Km-12.8801) > 0.001 {
		t.Errorf("BrakePer100Km = %.4f, want 12.8801", d.BrakePer100Km)
	}
	if math.Abs(d.AccelPer100Km-2.3420) > 0.001 {
		t.Errorf("AccelPer100Km = %.4f, want 2.3420", d.AccelPer100Km)
	}
	if math.Abs(d.WhPerKm-164.9883) > 0.001 {
		t.Errorf("WhPerKm = %.4f, want 164.9883", d.WhPerKm)
	}
	if math.Abs(d.OverSpeedPct-13.4462) > 0.001 {
		t.Errorf("OverSpeedPct = %.4f, want 13.4462", d.OverSpeedPct)
	}
	if d.NightPct != 0 {
		t.Errorf("NightPct = %.4f, want 0", d.NightPct)
	}
}

// TestDeriveDrivingProfileNoSamples 没有有效窗口时占比必须是 0，不能是 NaN。
//
// ⚠️ NaN 会被 JSON 编成 null 或直接报错 —— 前者让 App 崩，后者让整个端点 500。
func TestDeriveDrivingProfileNoSamples(t *testing.T) {
	d := deriveDrivingProfile(drivingProfileRaw{DistanceKm: 0})
	if d.NightPct != 0 || d.OverSpeedPct != 0 {
		t.Fatalf("没有窗口时占比必须是 0，拿到 %+v", d)
	}
	if d.WhPerKm != 0 || d.BrakePer100Km != 0 || d.AccelPer100Km != 0 {
		t.Fatalf("里程为 0 时各项比率必须是 0，拿到 %+v", d)
	}
	if d.Score != 100 {
		t.Fatalf("没有任何扣分项时分数应为 100，拿到 %v", d.Score)
	}
}

// ---------- 数值舍入 ----------

// TestRound1 保留一位小数。
func TestRound1(t *testing.T) {
	cases := []struct {
		in   float64
		want float64
	}{
		{64.89, 64.9},
		{21.13, 21.1},
		{100, 100},
		{0, 0},
		{-1.05, -1.1},
		{12.8801, 12.9},
	}
	for _, tc := range cases {
		if got := round1(tc.in); got != tc.want {
			t.Errorf("round1(%v) = %v, want %v", tc.in, got, tc.want)
		}
	}
}
