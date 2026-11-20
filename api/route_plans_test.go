package main

import (
	"testing"
	"time"
)

// ---------- 测试夹具 ----------

func rpT_F(v float64) *float64 { return &v }
func rpT_I(v int) *int         { return &v }
func rpT_S(v string) *string   { return &v }

func rpT_Time(s string) time.Time {
	t, err := time.Parse(time.RFC3339, s)
	if err != nil {
		panic(err)
	}
	return t
}

func rpT_PtrTime(s string) *time.Time {
	t := rpT_Time(s)
	return &t
}

// rpT_Drive 造一趟行程（里程、时刻、电量、地点键）。
func rpT_Drive(id int, odoStart float64, odoEnd float64, start string, end string,
	durMin int, startKey string, endKey string, startSoc int, endSoc int) *Drive {
	return &Drive{
		ID:            id,
		StartDate:     rpT_Time(start),
		EndDate:       rpT_PtrTime(end),
		DurationMin:   rpT_I(durMin),
		Distance:      rpT_F(odoEnd - odoStart),
		StartPlaceKey: rpT_S(startKey),
		EndPlaceKey:   rpT_S(endKey),
		StartSoc:      rpT_I(startSoc),
		EndSoc:        rpT_I(endSoc),
		OdoStart:      rpT_F(odoStart),
		OdoEnd:        rpT_F(odoEnd),
	}
}

// rpT_Charge 造一场充电。
func rpT_Charge(id int, odo float64, start string, startSoc int, endSoc int, placeKey string) *Charge {
	return &Charge{
		ID:         id,
		StartDate:  rpT_Time(start),
		EndDate:    rpT_PtrTime(start),
		StartSoc:   rpT_I(startSoc),
		EndSoc:     rpT_I(endSoc),
		StartOdoKm: rpT_F(odo),
		PlaceKey:   rpT_S(placeKey),
	}
}

// ---------- 判据：长途门槛 ----------

func TestRouteTemplates_长途门槛(t *testing.T) {
	short := []*Drive{
		rpT_Drive(1, 0, 100, "2026-10-01T08:00:00Z", "2026-10-01T09:00:00Z", 60, "a1", "a2", 100, 80),
		rpT_Drive(2, 100, 150, "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", 60, "a2", "a3", 80, 70),
	}
	if got := buildRouteTemplates(short, nil); len(got) != 0 {
		t.Fatalf("150 公里不算长途，应得 0 条，实际 %d 条", len(got))
	}
	long := []*Drive{
		rpT_Drive(1, 0, 100, "2026-10-01T08:00:00Z", "2026-10-01T09:00:00Z", 60, "a1", "a2", 100, 80),
		rpT_Drive(2, 100, 250, "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", 60, "a2", "a3", 80, 70),
	}
	if got := buildRouteTemplates(long, nil); len(got) != 1 {
		t.Fatalf("250 公里算长途，应得 1 条，实际 %d 条", len(got))
	}
}

// ---------- 判据：里程缺口断链 ＋ 倒序 ----------

func TestRouteTemplates_里程缺口断链且最近的在最前(t *testing.T) {
	drives := []*Drive{
		rpT_Drive(1, 90000, 90200, "2026-07-31T08:00:00Z", "2026-07-31T12:00:00Z", 240, "g1", "g2", 100, 60),
		rpT_Drive(2, 90200, 90400, "2026-07-31T20:00:00Z", "2026-08-01T02:00:00Z", 240, "g2", "g3", 60, 50),
		rpT_Drive(3, 100000, 100200, "2026-10-16T08:00:00Z", "2026-10-16T12:00:00Z", 240, "g1", "g2", 100, 60),
		rpT_Drive(4, 100200, 100400, "2026-10-16T20:00:00Z", "2026-10-17T02:00:00Z", 240, "g2", "g3", 60, 50),
	}
	got := buildRouteTemplates(drives, nil)
	if len(got) != 2 {
		t.Fatalf("里程缺口 9600 公里应切成 2 条，实际 %d 条", len(got))
	}
	if got[0].Key != "journey-3-4" {
		t.Fatalf("最近那次出门必须在最前，实际 %s", got[0].Key)
	}
	if got[1].Key != "journey-1-2" {
		t.Fatalf("第 2 条应当是最老那次，实际 %s", got[1].Key)
	}
	if got[0].LastDrivenAt <= got[1].LastDrivenAt {
		t.Fatalf("lastDrivenAt 必须倒序：%s vs %s", got[0].LastDrivenAt, got[1].LastDrivenAt)
	}
}

// ---------- 判据：「回家了」断链 ----------

func TestRouteTemplates_回家断链(t *testing.T) {
	// 同一地点（g1）相隔 130 公里、7 小时 ⇒ 第二场是「回到常驻点」
	// ⇒ 它落在 leg1 与 leg2 的边界窗口里 ⇒ 那条链必须在这里断开。
	drives := []*Drive{
		rpT_Drive(1, 0, 130, "2026-10-01T08:00:00Z", "2026-10-01T09:00:00Z", 60, "a1", "a2", 100, 70),
		rpT_Drive(2, 130, 300, "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", 60, "a2", "a3", 70, 40),
		rpT_Drive(3, 300, 500, "2026-10-01T12:00:00Z", "2026-10-01T13:00:00Z", 60, "a3", "a4", 40, 80),
	}
	charges := []*Charge{
		rpT_Charge(1, 0, "2026-10-01T08:00:00Z", 60, 95, "g1"),
		rpT_Charge(2, 130, "2026-10-01T15:00:00Z", 70, 99, "g1"),
	}
	got := buildRouteTemplates(drives, charges)
	if len(got) != 1 {
		t.Fatalf("应当只剩 1 条（leg1 被「回家」切掉），实际 %d 条", len(got))
	}
	if len(got[0].LegIDs) != 2 || got[0].LegIDs[0] != 2 || got[0].LegIDs[1] != 3 {
		t.Fatalf("留下的应当是 leg2 与 leg3，实际 %v", got[0].LegIDs)
	}
}

// ---------- 判据：沿途站归属 ＋ 到站电量 ----------

func TestRouteTemplates_沿途站归属与到站电量(t *testing.T) {
	drives := []*Drive{
		rpT_Drive(1, 0, 100, "2026-10-01T08:00:00Z", "2026-10-01T09:00:00Z", 60, "a1", "a2", 100, 70),
		rpT_Drive(2, 100, 200, "2026-10-01T10:00:00Z", "2026-10-01T11:00:00Z", 60, "a2", "a3", 70, 40),
		rpT_Drive(3, 200, 300, "2026-10-01T12:00:00Z", "2026-10-01T13:00:00Z", 60, "a3", "a4", 40, 80),
	}
	charges := []*Charge{
		rpT_Charge(11, 100, "2026-10-01T09:30:00Z", 70, 95, "a11"),
		rpT_Charge(12, 200, "2026-10-01T11:30:00Z", 40, 90, "a12"),
	}
	got := buildRouteTemplates(drives, charges)
	if len(got) != 1 {
		t.Fatalf("应得 1 条线路，实际 %d 条", len(got))
	}
	tpl := got[0]
	if len(tpl.Stops) != 2 {
		t.Fatalf("应有 2 个沿途站，实际 %d 个", len(tpl.Stops))
	}
	if tpl.Stops[0].KmAlong != 100 || tpl.Stops[1].KmAlong != 200 {
		t.Fatalf("沿途站里程应当是 100 / 200，实际 %v / %v", tpl.Stops[0].KmAlong, tpl.Stops[1].KmAlong)
	}
	// ⚠️ v0.36.0：到站电量 = **那一趟行程的 end_soc**（不是插枪电量）。
	if tpl.Stops[0].ArrivalSoc == nil || *tpl.Stops[0].ArrivalSoc != 70 {
		t.Fatalf("第 1 站到站电量应为 70，实际 %v", tpl.Stops[0].ArrivalSoc)
	}
	if tpl.Stops[1].ArrivalSoc == nil || *tpl.Stops[1].ArrivalSoc != 40 {
		t.Fatalf("第 2 站到站电量应为 40，实际 %v", tpl.Stops[1].ArrivalSoc)
	}
	if tpl.StopCount != 2 {
		t.Fatalf("沿途站数应为 2，实际 %d", tpl.StopCount)
	}
}

// ---------- 判据：一次插枪被切成两个 process 时合并 ----------

func TestRouteTemplates_同插枪两场合并取最早那场(t *testing.T) {
	// ⚠️ sqlCharges 是 ORDER BY start_date DESC（新的在前）⇒ 这里就按那个顺序给。
	drives := []*Drive{
		rpT_Drive(1, 0, 100, "2026-10-01T08:00:00Z", "2026-10-01T09:00:00Z", 60, "a1", "a2", 100, 8),
		rpT_Drive(2, 100, 300, "2026-10-01T10:00:00Z", "2026-10-01T11:30:00Z", 90, "a2", "a3", 8, 30),
	}
	charges := []*Charge{
		rpT_Charge(17, 100, "2026-10-01T09:40:00Z", 27, 86, "a11"),
		rpT_Charge(16, 100, "2026-10-01T09:20:00Z", 8, 27, "a11"),
	}
	got := buildRouteTemplates(drives, charges)
	if len(got) != 1 {
		t.Fatalf("应得 1 条线路，实际 %d 条", len(got))
	}
	stops := got[0].Stops
	if len(stops) != 1 {
		t.Fatalf("同一处只应有 1 个站，实际 %d 个", len(stops))
	}
	// ⚠️ v0.37.0：socBefore 取**时间最早**那一场的插枪电量 = 8（不是 27）。
	if stops[0].SocBefore == nil || *stops[0].SocBefore != 8 {
		t.Fatalf("插枪电量应取最早那场 = 8，实际 %v", stops[0].SocBefore)
	}
	if stops[0].SocAfter == nil || *stops[0].SocAfter != 86 {
		t.Fatalf("充完电量应取最高那场 = 86，实际 %v", stops[0].SocAfter)
	}
}

// ---------- 判据：往返 ＋ 真实均速 ----------

func TestRouteTemplates_往返判定与真实均速(t *testing.T) {
	drives := []*Drive{
		rpT_Drive(1, 0, 150, "2026-10-01T08:00:00Z", "2026-10-01T09:00:00Z", 60, "g1", "g2", 100, 70),
		rpT_Drive(2, 150, 300, "2026-10-01T10:00:00Z", "2026-10-01T12:00:00Z", 120, "g2", "g1", 70, 80),
	}
	got := buildRouteTemplates(drives, nil)
	if len(got) != 1 {
		t.Fatalf("应得 1 条线路，实际 %d 条", len(got))
	}
	if !got[0].IsRoundTrip {
		t.Fatal("起止地点键相同 ⇒ 这一趟是往返")
	}
	// ⚠️ v0.30.0：真实均速 = 300 公里 ÷ 3 小时 = 100 km/h（不是硬编码的 90）。
	if got[0].AvgSpeedKph == nil {
		t.Fatal("均速必须有值")
	}
	if *got[0].AvgSpeedKph < 99.9 || *got[0].AvgSpeedKph > 100.1 {
		t.Fatalf("均速应为 100 km/h，实际 %v", *got[0].AvgSpeedKph)
	}
	if got[0].LegCount != 2 || got[0].TotalLegCount != 2 {
		t.Fatalf("段数应都是 2，实际 %d / %d", got[0].LegCount, got[0].TotalLegCount)
	}
}

// ---------- 阈值：钉死具体数值 ----------

// TestRouteThresholds_阈值钉死具体数值
//
// ⚠️ **阈值型断言要钉具体数值**（AGENTS.md 的红线）：
// 选线的阈值是判据的一部分 ⇒ 改动必须**显式**改这里并说清为什么，
// 不能悄悄改一行常量让线路悄悄变了。
func TestRouteThresholds_阈值钉死具体数值(t *testing.T) {
	cases := []struct {
		name string
		got  float64
		want float64
	}{
		{"routeMaxGapKm 相邻两趟的里程缺口上限", routeMaxGapKm, 60.0},
		{"routeMaxGapHours 相邻两趟的时间间隔上限", routeMaxGapHours, 36.0},
		{"routeHomeReturnKm 回到常驻点的公里数", routeHomeReturnKm, 120.0},
		{"routeHomeReturnMinHours 回到常驻点的小时数", routeHomeReturnMinHours, 6.0},
		{"routeBoundaryTolKm 充电与行程边界的容差", routeBoundaryTolKm, 3.0},
		{"routeSameStopMergeKm 同一地点键的合并阈值", routeSameStopMergeKm, 1.0},
		{"routeNearbyStopMergeKm 相邻站的合并阈值", routeNearbyStopMergeKm, 3.0},
		{"routeMinDistanceKm 长途门槛", routeMinDistanceKm, 200.0},
	}
	for _, c := range cases {
		if c.got != c.want {
			t.Errorf("%s = %v，应为 %v", c.name, c.got, c.want)
		}
	}
}
