package main

import (
	"math"
	"testing"
)

// ⚠️ **v0.62.0（第 31 轮电池出厂态核查）**：出厂同刻度满电标定续航的回归测试。
//
// 固定夹具 = 本机真实数据（2026-10-25 从库里复算）：
//   出厂可用容量 78.4 kWh（NOMINAL_BATTERY_KWH，本车 MY2022）
//   严格档（充到 100%）共 16 个点，其中 15 个有可用的能量口径（有一个 ΔSOC 太小被跳过）
//   严格档比值中位数 K = 6.8686 km/kWh ⇒ 出厂同刻度续航 = 538.50 km
//   最新一次满充（2026-10-24）车机读数 = 446.37 km ⇒ 差 92 km（App 取整到 90）
//
//   ⚠️ 若把 10 个**未充满**的点（90~98%）也混进来：
//      混合中位数掉到 6.8181 ⇒ 出厂续航 534.54 km —— **少算 3.96 km**。

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
	got := factoryRatedRangeKm(78.4, fixtureFactoryStrict)
	if got == nil {
		t.Fatal("期望有值，实际为 nil")
	}
	// 服务端实测值：538.50 km
	if math.Abs(*got-538.50) > 0.05 {
		t.Errorf("出厂同刻度续航 = %.3f km，期望 ≈ 538.50 km", *got)
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
		{"没有任何比值", 78.4, nil},
		{"比值全为 0", 78.4, []float64{0, 0}},
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

	clean := factoryRatedRangeKm(78.4, fixtureFactoryStrict)
	dirty := factoryRatedRangeKm(78.4, mixed)
	if clean == nil || dirty == nil {
		t.Fatal("两者都应有值")
	}
	if *dirty >= *clean {
		t.Errorf("混入未充满点后应被拉小：干净 %.3f km vs 混入 %.3f km", *clean, *dirty)
	}
	// 实测差距 3.96 km（538.50 → 534.54）
	if math.Abs(*clean-*dirty) < 3.0 {
		t.Errorf("预期差距至少 3 km，实测 %.3f km", *clean-*dirty)
	}
}

// ⚠️ **官方标称续航不能直接和车机读数比** —— 官方是**新车认证值**，
// 不是本车当前这把尺子上的读数（车机读数是保守估计，低于认证值属正常 derate）。
//
// 若误用官方认证续航（600~700 km 区间）去算「剩余比例」，
// 会得到 66%~74% 的**假结论**；真实是 446.37/538.50 = **82.9%**
// （≈ health_percent 84.86%）。
//
// ⚠️ 这条测试**不锁死任何一个具体官方公里数** —— 各年款官方 CLTC 不同，
// 锁死会让官方数字一改就假失败。要锁的是**原则**：用官方值算出来的比例
// 必须显著低于同刻度 SOH。
func TestFactoryRatedRangeKm_RejectsCltcScale(t *testing.T) {
	got := factoryRatedRangeKm(78.4, fixtureFactoryStrict)
	if got == nil {
		t.Fatal("期望有值")
	}
	sohReal := 446.37 / *got * 100
	if math.Abs(sohReal-82.9) > 0.5 {
		t.Errorf("同刻度 SOH = %.2f%%，期望 ≈ 82.9%%", sohReal)
	}
	// 官方认证续航取值区间（不同年款不同），每个都必须显著低于同刻度 SOH。
	for _, official := range []float64{600, 623, 627, 647, 675} {
		sohOfficial := 446.37 / official * 100
		if sohOfficial > sohReal-5 {
			t.Errorf("官方认证续航 %.0f km 算出的 %.2f%% 不应与同刻度 SOH %.2f%% 接近 —— "+
				"官方值是新车认证值，不是本车这把尺子上的读数",
				official, sohOfficial, sohReal)
		}
	}
}
