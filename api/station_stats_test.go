package main

import (
	"testing"
)

func ssF(v float64) *float64 { return &v }
func ssI(v int) *int         { return &v }
func ssS(v string) *string   { return &v }

// ssCharge 造一场充电（按站画像的夹具）。
func ssCharge(id int, key string, added float64, startSoc int, endSoc int,
	pw int, dc bool, measuredMin *int, measuredFrom *int, reason *string, durMin int) *Charge {
	c := &Charge{
		ID:             id,
		Place:          ssS("某地, 某市, 某省, 中国"),
		PlaceKey:       ssS(key),
		EnergyAdded:    ssF(added),
		StartSoc:       ssI(startSoc),
		EndSoc:         ssI(endSoc),
		AvgPowerKwTrue: ssI(pw),
		IsDC:           dc,
		DurationMin:    ssI(durMin),
	}
	if measuredMin != nil {
		c.MeasuredMinutesTo80 = measuredMin
		c.MeasuredFromSoc = measuredFrom
	}
	if reason != nil {
		c.MeasuredUnavailableReason = reason
	}
	return c
}

// TestStationStats_按站画像口径
//
// ⚠️ 断言的是**具体数值**（阈值型断言要钉具体数值）：
// 容量 = median(66.667, 33.333) = 50.0；典型功率 = median(50, 40) = 45；
// 折算耗时 = 30 / 30 / 75 ⇒ 中位 30；P90 样本不足 ⇒ null。
func TestStationStats_按站画像口径(t *testing.T) {
	charges := []*Charge{
		ssCharge(1, "a10", 20.0, 30, 60, 50, true, ssI(35), ssI(28), nil, 40),
		ssCharge(2, "a10", 10.0, 40, 70, 40, true, nil, nil, nil, 30),
		ssCharge(3, "a10", 0.2, 50, 60, 20, true, nil, nil, ssS("never_reached_80"), 15),
	}
	capacity, stats, ok := buildStationStats(charges)
	if !ok {
		t.Fatal("应当算得出来")
	}
	if capacity < 49.999 || capacity > 50.001 {
		t.Fatalf("容量应为 50.0，实际 %v", capacity)
	}
	if len(stats) != 1 {
		t.Fatalf("应当只有 1 个站，实际 %d 个", len(stats))
	}
	s := stats[0]
	if s.Sessions != 3 {
		t.Fatalf("场次应为 3，实际 %d", s.Sessions)
	}
	if s.TypicalPowerKw < 44.999 || s.TypicalPowerKw > 45.001 {
		t.Fatalf("典型功率应为 45，实际 %v", s.TypicalPowerKw)
	}
	if s.TypicalPowerDcKw == nil || *s.TypicalPowerDcKw < 44.999 || *s.TypicalPowerDcKw > 45.001 {
		t.Fatalf("直流典型功率应为 45，实际 %v", s.TypicalPowerDcKw)
	}
	if s.TypicalMinutesTo80 == nil || *s.TypicalMinutesTo80 != 30 {
		t.Fatalf("折算耗时中位应为 30，实际 %v", s.TypicalMinutesTo80)
	}
	if s.P90MinutesTo80 != nil {
		t.Fatalf("样本不足 10 条时 P90 必须是 null，实际 %v", *s.P90MinutesTo80)
	}
	if len(s.MeasuredSamples) != 1 || s.MeasuredSamples[0].FromSoc != 28 || s.MeasuredSamples[0].Minutes != 35 {
		t.Fatalf("实测样本应只有 (28, 35)，实际 %v", s.MeasuredSamples)
	}
	if s.DcNeverReached80 != 1 || s.DcNeverReached80Short != 1 {
		t.Fatalf("没充到 80%% 应为 1 次（其中短的 1 次），实际 %d / %d", s.DcNeverReached80, s.DcNeverReached80Short)
	}
}

// TestStationStats_零电量尾段不参与功率统计
//
// ⚠️ v0.31.0 实测的坑：充入 0.04 度的场次会把典型功率从 32 kW 拉到 18.5 kW。
func TestStationStats_零电量尾段不参与功率统计(t *testing.T) {
	charges := []*Charge{
		ssCharge(1, "a10", 20.0, 30, 60, 50, true, nil, nil, nil, 40),
		// 充入 0.2 度 ⇒ 不进 powerList ⇒ 不影响典型功率
		ssCharge(2, "a10", 0.2, 60, 70, 5, true, nil, nil, nil, 5),
	}
	_, stats, ok := buildStationStats(charges)
	if !ok {
		t.Fatal("应当算得出来")
	}
	if len(stats) != 1 {
		t.Fatalf("应当只有 1 个站，实际 %d 个", len(stats))
	}
	if stats[0].TypicalPowerKw < 49.999 || stats[0].TypicalPowerKw > 50.001 {
		t.Fatalf("典型功率应为 50（零电量尾段不算），实际 %v", stats[0].TypicalPowerKw)
	}
}

// TestStationStats_阈值钉死具体数值
func TestStationStats_阈值钉死具体数值(t *testing.T) {
	cases := []struct {
		name string
		got  float64
		want float64
	}{
		{"stationCapacityMinKwh", stationCapacityMinKwh, 30.0},
		{"stationCapacityMaxKwh", stationCapacityMaxKwh, 120.0},
		{"stationMinAddedKwh", stationMinAddedKwh, 0.5},
		{"stationMinPowerKw", stationMinPowerKw, 1.0},
		{"stationFromSocMax", stationFromSocMax, 50},
		{"stationTargetSoc", stationTargetSoc, 80},
		{"stationMinutesMin", stationMinutesMin, 3.0},
		{"stationMinutesMax", stationMinutesMax, 600.0},
		{"stationShortStayMin", float64(stationShortStayMin), 20},
	}
	for _, c := range cases {
		if c.got != c.want {
			t.Errorf("%s = %v，应为 %v", c.name, c.got, c.want)
		}
	}
	if stationMinDeltaSoc != 5 || stationMinDcSamples != 2 || stationMinMinutesSamples != 2 || stationMinP90Samples != 10 {
		t.Error("样本数门槛被改了（ΔSOC 5 / 直流 2 / 耗时中位 2 / P90 10）")
	}
}
