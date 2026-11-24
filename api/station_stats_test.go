package main

import (
	"math"
	"testing"
	"time"
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

// ⚠️ **v0.185.0** 的夹具：带**时间**与**地址**的一场充电。
func ssAt(id int, key, label, place string, added float64, startSoc, endSoc, pw, durMin int, at string) *Charge {
	tm, err := time.Parse(time.RFC3339, at)
	if err != nil {
		panic(err)
	}
	c := &Charge{
		ID:             id,
		PlaceKey:       ssS(key),
		Place:          ssS(place),
		EnergyAdded:    ssF(added),
		StartSoc:       ssI(startSoc),
		EndSoc:         ssI(endSoc),
		AvgPowerKwTrue: ssI(pw),
		IsDC:           true,
		DurationMin:    ssI(durMin),
		StartDate:      tm,
	}
	if label != "" {
		c.PlaceLabel = ssS(label)
	}
	return c
}

// TestStationStats_最近一次真充电
//
// ⚠️ **v0.185.0**：口径钉死 —— 「插了一下没充上」（0.2 度）**不算**一次充电，
// ⇒ 「最近一次」要往前找一场**真充电**（20 度 / 40 分钟 ⇒ 30 kW）。
func TestStationStats_最近一次真充电(t *testing.T) {
	charges := []*Charge{
		// ① 最新那场是「插了一下」（0.2 度）⇒ 不算。
		ssAt(1, "a10", "某服务区", "某地, 某市, 某省", 0.2, 60, 70, 5, 5, "2026-11-20T10:00:00Z"),
		// ② 再往前那场是真充电 ⇒ 它才是「最近一次」。
		ssAt(2, "a10", "某服务区", "某地, 某市, 某省", 20.0, 30, 60, 50, 40, "2026-11-10T10:00:00Z"),
	}
	_, stats, ok := buildStationStats(charges)
	if !ok {
		t.Fatal("应当算得出来")
	}
	if len(stats) != 1 {
		t.Fatalf("应当只有 1 个站，实际 %d 个", len(stats))
	}
	ls := stats[0].LastSession
	if ls == nil {
		t.Fatal("应当有「最近一次真充电」")
	}
	if ls.At != "2026-11-10T10:00:00Z" {
		t.Fatalf("最近一次应是 2026-11-10 那场（0.2 度那场不算），实际 %q", ls.At)
	}
	if ls.Minutes == nil || *ls.Minutes != 40 {
		t.Fatalf("最近一次应停 40 分钟，实际 %v", ls.Minutes)
	}
	if ls.EnergyKwh == nil || math.Abs(*ls.EnergyKwh-20.0) > 0.0001 {
		t.Fatalf("最近一次应充入 20 度，实际 %v", ls.EnergyKwh)
	}
	// 实际平均功率 = 20 度 ÷ 40 分钟 × 60 = 30 kW
	if ls.PowerKw == nil || math.Abs(*ls.PowerKw-30.0) > 0.0001 {
		t.Fatalf("最近一次实际平均功率应为 30 kW，实际 %v", ls.PowerKw)
	}
	if ls.FromSoc == nil || *ls.FromSoc != 30 || ls.ToSoc == nil || *ls.ToSoc != 60 {
		t.Fatalf("最近一次应是 30%% → 60%%，实际 %v → %v", ls.FromSoc, ls.ToSoc)
	}
}

// TestStationStats_按地址关键词查
//
// ⚠️ **v0.185.0**：站名只有「西乡街道」（地址第一段），
// 而用户记得住的是「宝安」⇒ **按地址也得能查到**。
func TestStationStats_按地址关键词查(t *testing.T) {
	charges := []*Charge{
		ssAt(1, "a10", "", "西乡街道, 宝安区, 深圳市, 中国", 20.0, 30, 60, 50, 40, "2026-11-10T10:00:00Z"),
		ssAt(2, "a20", "", "龙华街道, 龙岗区, 深圳市, 中国", 15.0, 30, 60, 50, 40, "2026-11-11T10:00:00Z"),
	}
	_, stats, ok := buildStationStats(charges)
	if !ok {
		t.Fatal("应当算得出来")
	}
	if len(stats) != 2 {
		t.Fatalf("应当有 2 个站，实际 %d 个", len(stats))
	}
	// ① 站名（地址第一段）命中。
	got := stationFilter(stats, "西乡街道")
	if len(got) != 1 || got[0].PlaceKey != "a10" {
		t.Fatalf("按「西乡街道」应只回 a10，实际 %v", got)
	}
	// ② **地址**里的地名碎片也能查到（这是 v0.185.0 新增的那条路）。
	got = stationFilter(stats, "宝安")
	if len(got) != 1 || got[0].PlaceKey != "a10" {
		t.Fatalf("按「宝安」应只回 a10，实际 %v", got)
	}
	// ③ 多关键词：**每个**都要命中。
	got = stationFilter(stats, "深圳 宝安")
	if len(got) != 1 || got[0].PlaceKey != "a10" {
		t.Fatalf("按「深圳 宝安」应只回 a10，实际 %v", got)
	}
	// ④ 关键词对不上 ⇒ 空（但容量照给 ⇒ 由 handler 负责）。
	got = stationFilter(stats, "宝安 龙岗")
	if len(got) != 0 {
		t.Fatalf("「宝安 龙岗」不该命中任何站，实际 %v", got)
	}
}

// TestStationStats_站名命中优先
//
// ⚠️ **v0.185.0**：加了地址检索之后，**必须**保住 v0.184.0 的老口径 ——
// 「按名字查只回 1 个站」⇒ 站名命中时**不再**把地址命中的一并带出来。
func TestStationStats_站名命中优先(t *testing.T) {
	charges := []*Charge{
		// 站名里有「金田」；地址里也有「金田」⇒ 同一个站。
		ssAt(1, "a10", "金田服务区", "宝安区, 深圳市, 中国", 20.0, 30, 60, 50, 40, "2026-11-10T10:00:00Z"),
		// 站名**不**含「金田」，但地址里含 ⇒ 地址检索本来会命中它。
		ssAt(2, "a20", "别的站", "金田服务区旁边, 宝安区, 深圳市", 15.0, 30, 60, 50, 40, "2026-11-11T10:00:00Z"),
	}
	_, stats, ok := buildStationStats(charges)
	if !ok {
		t.Fatal("应当算得出来")
	}
	got := stationFilter(stats, "金田")
	if len(got) != 1 || got[0].PlaceKey != "a10" {
		t.Fatalf("站名命中优先：应只回 a10，实际 %v", got)
	}
}

// TestStationStats_按最近一次排序
//
// ⚠️ **v0.185.0**：`?sort=recent` ⇒ 最近充过的排前面（不是按场次排）。
func TestStationStats_按最近一次排序(t *testing.T) {
	charges := []*Charge{
		ssAt(1, "a10", "甲站", "甲地, 某市", 20.0, 30, 60, 50, 40, "2026-11-01T10:00:00Z"),
		ssAt(2, "a20", "乙站", "乙地, 某市", 15.0, 30, 60, 50, 40, "2026-11-20T10:00:00Z"),
	}
	_, stats, ok := buildStationStats(charges)
	if !ok {
		t.Fatal("应当算得出来")
	}
	stationSortRecent(stats)
	if stats[0].PlaceKey != "a20" {
		t.Fatalf("最近一次充过的应排前面，实际第一个是 %s", stats[0].PlaceKey)
	}
}
