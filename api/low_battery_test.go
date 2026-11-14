package main

import "testing"

// ⚠️ **v0.162.0**：「低电量」的阈值**只在服务端一份**（ADR #38 / #41）。
//
// ## 为什么这组单测重要
//
// 低电量是**唯一一个**「App 上看得到、但飞书那边一条都没有」的告警 ——
// 原来 App 自己在 ui/Widgets.kt 里按 soc < 20 / soc < 10 判（三处），
// 而推送脚本**根本没有**这个判定 ⇒ 两端对「低电量」的定义不一样。
//
// 现在判定收归服务端（api/alerts.go 的 EvaluateLowBattery），
// App 与推送脚本都只读结果 ⇒ 这组单测钉的就是**唯一那份判据**。
//
// ## 三个阈值是怎么定的
//
//   - 20%（低电量）：车主常用的「该找桩了」那条线；
//   - 10%（严重偏低）：再往下就真的要紧张了（胶囊文案也换一句）；
//   - 2 小时（停着不动）：车况数据 ≥2 小时没更新 ⇒ 车在停着。
//
// ⚠️ 三个数都**不许悄悄改** —— 改了要同时改服务端常量与客户端兜底
// （客户端闸门 41 会逐值比对）⇒ 所以这里把它们钉死。

// TestLowBatteryThresholdsArePinned 三个阈值**不许悄悄改**。
func TestLowBatteryThresholdsArePinned(t *testing.T) {
	if LowBatterySoc != 20 {
		t.Fatalf("低电量阈值必须是 20%%，现在是 %d", LowBatterySoc)
	}
	if LowBatterySevereSoc != 10 {
		t.Fatalf("严重偏低阈值必须是 10%%，现在是 %d", LowBatterySevereSoc)
	}
	if LowBatteryParkedAgeHours != 2 {
		t.Fatalf("「停着不动」的数据年龄门槛必须是 2 小时，现在是 %d", LowBatteryParkedAgeHours)
	}
}

// TestEvaluateLowBatteryBoundaries 边界必须**钉具体数值**。
func TestEvaluateLowBatteryBoundaries(t *testing.T) {
	i := func(v int) *int { return &v }

	// 恰好等于阈值 ⇒ **不算低**（阈值是「低于」而不是「不高于」）。
	if got := EvaluateLowBattery(i(20), false, false, 0); got.Low || got.Severe {
		t.Fatalf("20%% 恰好等于阈值 ⇒ 不该报低电量：%+v", got)
	}
	if got := EvaluateLowBattery(i(10), false, false, 0); !got.Low || got.Severe {
		t.Fatalf("10%% ⇒ 低电量但不是严重偏低：%+v", got)
	}
	// 低一格 ⇒ 报。
	if got := EvaluateLowBattery(i(19), false, false, 0); !got.Low || got.Severe {
		t.Fatalf("19%% ⇒ 必须是低电量：%+v", got)
	}
	if got := EvaluateLowBattery(i(9), false, false, 0); !got.Low || !got.Severe {
		t.Fatalf("9%% ⇒ 必须是严重偏低：%+v", got)
	}
	// 读不到电量 ⇒ 一律不报（宁可说不知道，不可编）。
	if got := EvaluateLowBattery(nil, false, false, 0); got.Low || got.Severe || got.Parked {
		t.Fatalf("没读到电量 ⇒ 不许报低电量：%+v", got)
	}
}

// TestEvaluateLowBatteryCharging 充电中**不报**低电量。
//
// ⚠️ 为什么：充电时显示「低电量」是噪音（车正在补电，这是好事）。
func TestEvaluateLowBatteryCharging(t *testing.T) {
	soc := 5
	if got := EvaluateLowBattery(&soc, true, false, 10); got.Low || got.Severe || got.Parked {
		t.Fatalf("充电中不该报低电量：%+v", got)
	}
}

// TestEvaluateLowBatteryParked 「停着不动还低电量」的三条判据。
//
//   - 电量低 ＋ 没有正在跑的行程 ＋ 数据 ≥2 小时没更新 ⇒ 报；
//   - 车在跑 ⇒ 不报（开着呢，不是「停着不动」）；
//   - 数据才 1 小时没更新 ⇒ 不报（可能只是刚停下）。
func TestEvaluateLowBatteryParked(t *testing.T) {
	soc := 12
	if got := EvaluateLowBattery(&soc, false, false, 2); !got.Parked {
		t.Fatalf("低电量 ＋ 停着 ＋ 数据 2 小时没更新 ⇒ 必须报：%+v", got)
	}
	if got := EvaluateLowBattery(&soc, false, false, 1); got.Parked {
		t.Fatalf("数据才 1 小时没更新 ⇒ 不该报：%+v", got)
	}
	if got := EvaluateLowBattery(&soc, false, true, 10); got.Parked {
		t.Fatalf("车正在跑 ⇒ 不该报「停着不动」：%+v", got)
	}
	// 电量正常 ⇒ 停着也不报（这一条是「停着」与「低电量」必须同时成立）。
	ok := 80
	if got := EvaluateLowBattery(&ok, false, false, 10); got.Parked {
		t.Fatalf("电量正常时不该报：%+v", got)
	}
}
