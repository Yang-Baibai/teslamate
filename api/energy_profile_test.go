package main

import (
	"math"
	"testing"
)

// ---------- 分档边界 ----------

// TestEnergyBandEdges 边界必须与 App 约定的完全一致。
//
// ⚠️ 这是**对外契约**：App 按这组边界画横轴，
// 服务端改了边界而 App 不知道，图就会错位。
func TestEnergyBandEdges(t *testing.T) {
	// ⚠️ v0.28.0（主控修正）：边界是**真实区间**，不是评审员表里的「档中心」。
	// 分箱键是中心（5/15/25/…），真实区间是 [0,10)/[10,20)/…
	want := []int{0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130, 140}
	got := energyBandEdges()
	if len(got) != len(want) {
		t.Fatalf("边界个数 = %d, want %d", len(got), len(want))
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("边界[%d] = %d, want %d", i, got[i], want[i])
		}
	}
}

// TestEnergyBandLabel 每一档的 (下界, 上界) 必须首尾相接、不留缝、不重叠。
func TestEnergyBandLabel(t *testing.T) {
	edges := energyBandEdges()
	n := len(edges) - 1
	for i := 0; i < n; i++ {
		minKph, maxKph := energyBandLabel(i)
		// ⚠️ 最后一档是**开区间**（上界 0 = 「及以上」）——
		// 因为 ≥140 km/h 的分箱也并进了那一档，标成具体上界会低估它的速度范围。
		wantHi := edges[i+1]
		if i == n-1 {
			wantHi = 0
		}
		if minKph != edges[i] || maxKph != wantHi {
			t.Fatalf("档位 %d = (%d, %d), want (%d, %d)", i, minKph, maxKph, edges[i], wantHi)
		}
	}
	// 越界要夹住，不能 panic
	if lo, hi := energyBandLabel(-5); lo != edges[0] || hi != edges[1] {
		t.Errorf("负下标应夹到第一档，实际 (%d, %d)", lo, hi)
	}
	if lo, hi := energyBandLabel(n + 3); lo != edges[n-1] || hi != 0 {
		t.Errorf("超出下标应夹到最后一档（开区间），实际 (%d, %d)", lo, hi)
	}
}

// TestEnergyBandIndexForKey SQL 分档键 → App 档位下标的映射。
//
// ⚠️ 关键用例：key ≥ 135 的（≥140 km/h 的更高速分箱）必须**并进最后一档**，
// 不能自己变成第 15 档 —— 那会让 App 多出一档没有边界的柱子。
func TestEnergyBandIndexForKey(t *testing.T) {
	cases := []struct {
		key  int
		want int
	}{
		{5, 0},   // [0,10) → 标签 (0,10)
		{15, 1},  // [10,20) → 标签 (10,20)
		{25, 2},
		{35, 3},
		{45, 4},
		{55, 5},
		{65, 6},
		{75, 7},
		{85, 8},
		{95, 9},
		{105, 10},
		{115, 11},
		{125, 12},
		{135, 13},
		{145, 13}, // 并进最后一档
		{155, 13},
		{165, 13},
	}
	for _, tc := range cases {
		if got := energyBandIndexForKey(tc.key); got != tc.want {
			t.Errorf("energyBandIndexForKey(%d) = %d, want %d", tc.key, got, tc.want)
		}
	}
}

// TestEnergyBandIndexForKeyOutOfRange 越界的键要夹住，不能 panic 也不能越界。
func TestEnergyBandIndexForKeyOutOfRange(t *testing.T) {
	last := len(energyBandEdges()) - 2
	if got := energyBandIndexForKey(-100); got != 0 {
		t.Errorf("负键应夹到 0，实际 %d", got)
	}
	if got := energyBandIndexForKey(100000); got != last {
		t.Errorf("超大键应夹到 %d，实际 %d", last, got)
	}
}

// ---------- 样本量门槛 ----------

// TestEnergyBandSufficient 样本不足的档必须被丢掉。
//
// ⚠️ 宁可少一档，也不给一个噪声数 ——
// 5 个分箱 = 5 公里，一档 Wh/km 的误差能到 ±20%。
func TestEnergyBandSufficient(t *testing.T) {
	cases := []struct {
		name string
		bins int
		km   float64
		want bool
	}{
		{"刚好达标", 5, 30, true},
		{"分箱太少", 4, 100, false},
		{"公里太少", 100, 29.9, false},
		{"什么都没有", 0, 0, false},
		{"样本充足", 130, 65.3, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := energyBandSufficient(tc.bins, tc.km); got != tc.want {
				t.Fatalf("energyBandSufficient(%d, %v) = %v, want %v",
					tc.bins, tc.km, got, tc.want)
			}
		})
	}
}

// ---------- 分档合并 ----------

// TestMergeEnergyBands 合并后的 Wh/km 必须按里程加权，**不能**平均各档的 Wh/km。
//
// 真实数据（本机 car 1，2026-10-25 实测）：
//
//	键 5   → 130 分箱 /  65.3 km /  18.576 kWh → 284.4 Wh/km
//	键 135 → 1059 分箱 / 1087.0 km / 194.215 kWh → 178.7 Wh/km
//
// 把键 135 与键 145（62 分箱 / 62.0 km / 11.310 kWh）并进最后一档后：
//
//	分箱 1121，里程 1149.0 km，电量 205.525 kWh → 178.9 Wh/km
//
// ⚠️ 如果错成「两个 Wh/km 求平均」会得到 (178.7+182.3)/2 = 180.5 —— 看起来差不多，
// 但那是错的：比值不能直接平均。
func TestMergeEnergyBands(t *testing.T) {
	raws := []energyBandRaw{
		{Key: 5, Bins: 130, Km: 65.3, Kwh: 18.576},
		{Key: 135, Bins: 1059, Km: 1087.0, Kwh: 194.215},
		{Key: 145, Bins: 62, Km: 62.0, Kwh: 11.310},
	}
	merged := mergeEnergyBands(raws)
	last := len(energyBandEdges()) - 2

	if got := merged[0].Bins; got != 130 {
		t.Errorf("第一档分箱 = %d, want 130", got)
	}
	if got := merged[last].Bins; got != 1059+62 {
		t.Errorf("最后一档分箱 = %d, want %d", got, 1059+62)
	}
	if got := merged[last].Km; math.Abs(got-1149.0) > 1e-6 {
		t.Errorf("最后一档里程 = %v, want 1149.0", got)
	}
	whPerKm := merged[last].Kwh / merged[last].Km * 1000
	if math.Abs(whPerKm-178.9) > 0.05 {
		t.Errorf("最后一档 Wh/km = %.4f, want 178.9", whPerKm)
	}
	// 错误的算法（比值直接平均）必须与之不同，否则这个测试就没有鉴别力
	wrong := (194.215/1087.0*1000 + 11.310/62.0*1000) / 2
	if math.Abs(wrong-whPerKm) < 0.5 {
		t.Errorf("按里程加权与直接平均几乎相同（%.4f vs %.4f），测试失去鉴别力", whPerKm, wrong)
	}
}

// TestMergeEnergyBandsEmpty 没有数据时不能 panic，也不能返回 nil 切片。
func TestMergeEnergyBandsEmpty(t *testing.T) {
	merged := mergeEnergyBands(nil)
	if len(merged) != len(energyBandEdges())-1 {
		t.Fatalf("空输入应返回 %d 个空档，实际 %d", len(energyBandEdges())-1, len(merged))
	}
	for i, m := range merged {
		if m.Bins != 0 || m.Km != 0 || m.Kwh != 0 {
			t.Fatalf("第 %d 档应为空，实际 %+v", i, m)
		}
	}
}
