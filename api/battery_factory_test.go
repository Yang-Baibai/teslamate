package main

import (
	"math"
	"testing"
)

// ⚠️ **v0.62.0（第 31 轮电池出厂态核查）**：出厂同刻度满电标定续航的回归测试。
//
// 固定夹具 = 本机真实数据（2026-10-25 从库里复算）：
//   出厂可用容量 76.8 kWh（NOMINAL_BATTERY_KWH）
//   严格档（充到 100%）共 16 个点，其中 15 个有可用的能量口径（有一个 ΔSOC 太小被跳过）
//   严格档比值中位数 K = 6.8686 km/kWh ⇒ 出厂同刻度续航 = 527.51 km
//   最新一次满充（2026-10-24）车机读数 = 446.37 km ⇒ 差 81 km
//
//   ⚠️ 若把 10 个**未充满**的点（90~98%）也混进来：
//      混合中位数掉到 6.8181 ⇒ 出厂续航 523.63 km —— **少算 3.9 km**。

// 严格档（充到 100%）的比值：满电标定续航 km ÷ 能量反推容量 度。
var fixtureFactoryStrict = []float64{
	6.8305, 6.9091, 6.9035, 7.0752, 6.8421, 6.9263, 6.7674,
	6.8686, 6.8181, 7.1019, 6.8722, 6.8428, 6.8446, 6.7828, 6.9205,
}

// 未充满（90~98%）的比值 —— **不该混进来**。
var fixtureFactoryPartial = []float64{
	6.3992, 6.5551, 6.2822, 6.2922, 6.4524,
	6.4139, 6.6782, 6.7396, 6.1949, 6.4099,
}

func TestFactoryRatedRangeKm_MatchesRealCar(t *testing.T) {
	got := factoryRatedRangeKm(76.8, fixtureFactoryStrict)
	if got == nil {
		t.Fatal("期望有值，实际为 nil")
	}
	// 服务端实测值：527.51 km
	if math.Abs(*got-527.51) > 0.05 {
		t.Errorf("出厂同刻度续航 = %.3f km，期望 ≈ 527.51 km", *got)
	}
}

func TestFactoryRatedRangeKm_NilWhenDataMissing(t *testing.T) {
	cases := []struct {
		name    string
		nominal float64
		ratios  []float64
	}{
		{"没有出厂容量", 0, fixtureFactoryStrict},
		{"出厂容量为负", -1, fixtureFactoryStrict},
		{"没有任何比值", 76.8, nil},
		{"比值全为 0", 76.8, []float64{0, 0}},
	}
	for _, c := range cases {
		if got := factoryRatedRangeKm(c.nominal, c.ratios); got != nil {
			t.Errorf("%s：期望 nil，实际 = %v", c.name, *got)
		}
	}
}

// ⚠️ **混入未充满点会把结果算小** —— 这是本轮真实踩过的坑。
//
// 未充满点的 `rated_range_km` 是**那一刻的读数**（不是满电读数），
// 而能量反推容量是**折算到满电**的容量 —— 两者不在同一刻度。
// 实测：未充满点的比值中位数 6.4119，比严格档的 6.8686 低 6.6%。
func TestFactoryRatedRangeKm_NonFullPointBiasesLow(t *testing.T) {
	mixed := append(append([]float64{}, fixtureFactoryStrict...), fixtureFactoryPartial...)

	clean := factoryRatedRangeKm(76.8, fixtureFactoryStrict)
	dirty := factoryRatedRangeKm(76.8, mixed)
	if clean == nil || dirty == nil {
		t.Fatal("两者都应有值")
	}
	if *dirty >= *clean {
		t.Errorf("混入未充满点后应被拉小：干净 %.3f km vs 混入 %.3f km", *clean, *dirty)
	}
	// 实测差距 3.9 km（527.51 → 523.63）
	if math.Abs(*clean-*dirty) < 3.0 {
		t.Errorf("预期差距至少 3 km，实测 %.3f km", *clean-*dirty)
	}
}

// ⚠️ **官方 CLTC 刻度与本车刻度不是同一把尺子** —— 差 19%。
//
// 若有人误把 CLTC 627 km 当成本车刻度，
// 算出来的「剩余比例」会是 446.37/627 = 71.2% —— 那是假结论。
// 真实是 446.37/527.51 = 84.6%（≈ health_percent 84.86%）。
func TestFactoryRatedRangeKm_RejectsCltcScale(t *testing.T) {
	got := factoryRatedRangeKm(76.8, fixtureFactoryStrict)
	if got == nil {
		t.Fatal("期望有值")
	}
	sohReal := 446.37 / *got * 100
	sohCltc := 446.37 / 627.0 * 100
	if math.Abs(sohReal-84.6) > 0.5 {
		t.Errorf("同刻度 SOH = %.2f%%，期望 ≈ 84.6%%", sohReal)
	}
	if sohCltc > 75 {
		t.Errorf("CLTC 口径 %.2f%% 应显著偏低（<75%%），说明两把尺子确实不同", sohCltc)
	}
}
