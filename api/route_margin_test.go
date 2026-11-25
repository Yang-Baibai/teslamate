package main

import (
	"fmt"
	"math"
	"testing"
)

// ⚠️ **v0.188.0：余量表的判定单测搬到服务端。**
//
// ## 为什么搬
//
// 「到下一站还剩多少电 / 够不够到 20% 保底」原来在**客户端**算
// （app-android/.../ui/RoutePlan.kt 的 buildRoutePlan，单测 RoutePlanBuildTest.kt）
// ⇒ ① 飞书里问不出来；② 两端各算一套阈值，必然漂（ADR #38）。
// ⇒ 现在判定在**服务端**（api/route_margin.go / api/route_margin_build.go）
// ⇒ **单测也跟着搬**（客户端那份已删除，覆盖不许净减）。
//
// ## 这里的断言是什么
//
// 下面每个数字都来自客户端 RoutePlanBuildTest 的同一组夹具
// （400 公里：3 段行程 + 2 个沿途站；180 Wh/km；容量 65 度）
// ⇒ **服务端算出来的数必须与当初客户端算出来的一模一样**，
// 否则等于「搬一次口径就变一次」。

// ---------- 夹具 ----------

func rmT_I(v int) *int         { return &v }
func rmT_F(v float64) *float64 { return &v }
func rmT_S(v string) *string   { return &v }

// rmT_Leg 造一段行程。
func rmT_Leg(id int, fromKm, toKm float64, durMin, startSoc, endSoc int) RoutePlanLeg {
	return RoutePlanLeg{
		DriveID:     id,
		StartKm:     fromKm,
		EndKm:       toKm,
		DistanceKm:  toKm - fromKm,
		StartSoc:    rmT_I(startSoc),
		EndSoc:      rmT_I(endSoc),
		DurationMin: rmT_I(durMin),
	}
}

// rmT_Stop 造一个沿途站（里程按**线路起点**算，即客户端的 kmAlong）。
func rmT_Stop(chargeID int, kmAlong float64, arrival, socBefore, socAfter int) RoutePlanStop {
	return RoutePlanStop{
		ChargeID:   chargeID,
		KmAlong:    kmAlong,
		IsEndpoint: false,
		ArrivalSoc: rmT_I(arrival),
		SocBefore:  rmT_I(socBefore),
		SocAfter:   rmT_I(socAfter),
		Charge: RoutePlanChargeMeta{
			Name:     fmt.Sprintf("站%d", chargeID),
			StartSoc: rmT_I(socBefore),
			EndSoc:   rmT_I(socAfter),
		},
	}
}

// rmT_Template 造一条 400 公里的线路（3 段行程 + 2 个沿途站）。
//
// ⚠️ 平均车速 = 400 km ÷ 240 min = **100 km/h**（照抄客户端夹具的算法）。
func rmT_Template() RoutePlanTemplate {
	return RoutePlanTemplate{
		Key:            "journey-1-3",
		FromLabel:      "起点",
		ToLabel:        "终点",
		DistanceKm:     400,
		StartSoc:       rmT_I(100),
		EndSoc:         rmT_I(80),
		LegCount:       3,
		TotalLegCount:  3,
		StopCount:      2,
		StartOdoKm:     100000,
		LastDistanceKm: 400,
		AvgSpeedKph:    rmT_F(100),
		LastStartSoc:   rmT_I(100),
		LastEndSoc:     rmT_I(80),
		LegIDs:         []int{1, 2, 3},
		Legs: []RoutePlanLeg{
			rmT_Leg(1, 100000, 100100, 60, 100, 70),
			rmT_Leg(2, 100100, 100300, 120, 70, 40),
			rmT_Leg(3, 100300, 100400, 60, 40, 80),
		},
		Stops: []RoutePlanStop{
			rmT_Stop(11, 100.0, 70, 70, 95),
			rmT_Stop(12, 300.0, 40, 40, 90),
		},
	}
}

// rmT_Model 本车实测能耗模型（一档：0~60 km/h，180 Wh/km）。
func rmT_Model() *ConsumptionModelPayload {
	return &ConsumptionModelPayload{
		Buckets:   []ConsumptionModelBucket{{MinKph: 0, MaxKph: 60, WhPerKm: 180, Samples: 5}},
		CruiseKph: 50,
	}
}

// rmT_Climb 造一个爬升模型（标定法口径）。
func rmT_Climb(basis string) *ClimbModel {
	return &ClimbModel{
		Basis:          basis,
		CUpCalibNet:    6.4,
		CRegenCalibNet: 3.4,
		TypicalGrade: []TypicalGrade{
			{MinKph: 0, MaxKph: 60, GradeUpTyp: 2.0, GradeDownTyp: 1.0, N: 5},
		},
	}
}

// ---------- 判定 ----------

// ⚠️ **v0.43.0（第 25 轮车主复审 P0）：第 0 行是**合成的起点行**。**
func TestRouteMargin_处方数字自洽(t *testing.T) {
	p := buildRouteMargin(rmT_Template(), rmT_Model(), 65.0, nil)
	if p == nil {
		t.Fatal("余量表不该是 nil")
	}
	if len(p.Stops) != 3 {
		t.Fatalf("应有 3 行（起点行 + 2 个沿途站），实际 %d 行", len(p.Stops))
	}
	s := p.Stops[0]
	if !s.IsStart {
		t.Fatal("第 0 行必须是合成的起点行")
	}
	if s.KmAlong != 0 {
		t.Errorf("起点行里程应是 0，实际 %v", s.KmAlong)
	}
	// 0 → 100 公里，180 Wh/km，容量 65 度
	//   ⇒ 100×180/1000 = 18 度；18 ÷ 65 × 100 = 27.7 ⇒ **28 个点**
	if s.GapNeedPct == nil || *s.GapNeedPct != 28 {
		t.Errorf("起点行这一跳要耗 28 个点，实际 %v", s.GapNeedPct)
	}
	// 留 20% 保底 ⇒ 至少充到 48%
	if s.RequiredSocHere == nil || *s.RequiredSocHere != 48 {
		t.Errorf("起点行处方应是 48%%，实际 %v", s.RequiredSocHere)
	}
	if s.GapToNextKm == nil || *s.GapToNextKm != 100 {
		t.Errorf("起点行到下一站应是 100 公里，实际 %v", s.GapToNextKm)
	}
	// 起点行的真值 = 第一个站的到站电量（70）
	if s.NextStopRealSoc == nil || *s.NextStopRealSoc != 70 {
		t.Errorf("起点行的真值应是 70%%，实际 %v", s.NextStopRealSoc)
	}
	// 起点行估算：出发 100% − 28 个点 = 72%
	if s.SocAtNextStop == nil || *s.SocAtNextStop != 72 {
		t.Errorf("起点行估算到站应是 72%%，实际 %v", s.SocAtNextStop)
	}

	s1 := p.Stops[1]
	// 站间 200 公里 ⇒ 200×180/1000 = 36 度；36 ÷ 65 × 100 = 55.4 ⇒ **55 个点**
	if s1.GapNeedPct == nil || *s1.GapNeedPct != 55 {
		t.Errorf("第 1 站这一跳要耗 55 个点，实际 %v", s1.GapNeedPct)
	}
	// 留 20% 保底 ⇒ 至少充到 75%
	if s1.RequiredSocHere == nil || *s1.RequiredSocHere != 75 {
		t.Errorf("第 1 站处方应是 75%%，实际 %v", s1.RequiredSocHere)
	}
	// 充到 95% 出发 ⇒ 到站剩 95 − 55 = 40%
	if s1.SocAtNextStop == nil || *s1.SocAtNextStop != 40 {
		t.Errorf("第 1 站到站应剩 40%%，实际 %v", s1.SocAtNextStop)
	}
	if s1.GapToNextKm == nil || *s1.GapToNextKm != 200 {
		t.Errorf("第 1 站到下一站应是 200 公里，实际 %v", s1.GapToNextKm)
	}
	// 真值（下一站的到站电量）= 第 2 趟行程的结束电量 40
	if s1.NextStopRealSoc == nil || *s1.NextStopRealSoc != 40 {
		t.Errorf("第 1 站的真值应是 40%%，实际 %v", s1.NextStopRealSoc)
	}
}

// ⚠️ **v0.38.0（第 23 轮车主复审 P0-1）：「距终点」只给最后一站。**
func TestRouteMargin_距终点只给最后一站(t *testing.T) {
	p := buildRouteMargin(rmT_Template(), rmT_Model(), 65.0, nil)
	if p == nil {
		t.Fatal("余量表不该是 nil")
	}
	if p.Stops[0].DistanceToEndKm != nil {
		t.Errorf("起点行不该有「距终点」，实际 %v", *p.Stops[0].DistanceToEndKm)
	}
	if p.Stops[1].DistanceToEndKm != nil {
		t.Errorf("第 1 站不该有「距终点」，实际 %v", *p.Stops[1].DistanceToEndKm)
	}
	last := p.Stops[len(p.Stops)-1]
	// 最后一站在 300 公里，全程 400 公里 ⇒ 还有 100 公里
	if last.DistanceToEndKm == nil || *last.DistanceToEndKm != 100 {
		t.Errorf("最后一站距终点应是 100 公里，实际 %v", last.DistanceToEndKm)
	}
	// 100 公里 × 180 Wh/km ÷ 1000 = 18 度；18 ÷ 65 × 100 = 27.7 ⇒ 28 个点
	// 最后一站充到 90% ⇒ 到终点剩 90 − 28 = 62%
	if last.EstSocAtEnd == nil || *last.EstSocAtEnd != 62 {
		t.Errorf("到终点应剩 62%%，实际 %v", last.EstSocAtEnd)
	}
}

// ⚠️ **v0.38.0（P0-2）：到站电量用**到站口径**，不是插枪口径。**
func TestRouteMargin_到站口径(t *testing.T) {
	p := buildRouteMargin(rmT_Template(), rmT_Model(), 65.0, nil)
	if p == nil {
		t.Fatal("余量表不该是 nil")
	}
	if p.Stops[0].ArrivalSoc == nil || *p.Stops[0].ArrivalSoc != 100 {
		t.Errorf("起点行的到站电量应是 100%%，实际 %v", p.Stops[0].ArrivalSoc)
	}
	if p.Stops[1].ArrivalSoc == nil || *p.Stops[1].ArrivalSoc != 70 {
		t.Errorf("第 1 站的到站电量应是 70%%，实际 %v", p.Stops[1].ArrivalSoc)
	}
	if p.Stops[2].ArrivalSoc == nil || *p.Stops[2].ArrivalSoc != 40 {
		t.Errorf("第 2 站的到站电量应是 40%%，实际 %v", p.Stops[2].ArrivalSoc)
	}
	// 决策行的真值与之同源
	if p.Stops[1].NextStopRealSoc == nil || *p.Stops[1].NextStopRealSoc != *p.Stops[2].ArrivalSoc {
		t.Errorf("第 1 站的真值应等于第 2 站的到站电量")
	}
}

// ⚠️ **档名与「借用邻档」**：数值随档走，档名与借用标记必须如实给。
func TestRouteMargin_档名与借用邻档(t *testing.T) {
	p := buildRouteMargin(rmT_Template(), rmT_Model(), 65.0, nil)
	if p == nil {
		t.Fatal("余量表不该是 nil")
	}
	// 这条线的均速是 100 km/h ⇒ 落在唯一的 0~60 档之外 ⇒ **借用邻档**
	for i, s := range p.Stops {
		// ⚠️ **最后一站没有「下一跳」** ⇒ 那一段没有档位（不给假数）。
		if s.GapToNextKm == nil {
			if s.GapBucketLabel != nil {
				t.Errorf("第 %d 行没有下一跳 ⇒ 不该给档名，实际 %q", i, *s.GapBucketLabel)
			}
			continue
		}
		if s.GapBucketLabel == nil {
			t.Fatalf("第 %d 行应给出档名", i)
		}
		// 档名用 **en-dash**（U+2013），与客户端逐字一致
		if *s.GapBucketLabel != "0\u201360" {
			t.Errorf("第 %d 行档名应是 0–60，实际 %q", i, *s.GapBucketLabel)
		}
	}
	if !p.Stops[0].GapSpeedBorrowed {
		t.Error("100 km/h 落在档外 ⇒ 应标记「借用邻档」")
	}
}

// ⚠️ **末档档名是「130+」**（不是「130–2147483647」）。
func TestRouteMargin_末档档名(t *testing.T) {
	last := ConsumptionModelBucket{MinKph: 130, MaxKph: consumptionMaxKph, WhPerKm: 200}
	if got := marginBucketLabel(&last); got != "130+" {
		t.Errorf("末档档名应是 130+，实际 %q", got)
	}
}

// ⚠️ **选档口径**：落在本档 = min ≤ speed < max；档外取**最近**的一档。
func TestRouteMargin_选档口径(t *testing.T) {
	buckets := []ConsumptionModelBucket{
		{MinKph: 0, MaxKph: 60, WhPerKm: 180},
		{MinKph: 60, MaxKph: 90, WhPerKm: 190},
		{MinKph: 90, MaxKph: 110, WhPerKm: 200},
	}
	b, exact := marginWhSample(70, buckets)
	if b == nil || !exact || b.WhPerKm != 190 {
		t.Errorf("70 km/h 应落在 60~90 档且是本档，实际 %+v exact=%v", b, exact)
	}
	// 档外：110 km/h 离 90~110 档最近（距离口径 speed − max + 1 = 1）
	b, exact = marginWhSample(110, buckets)
	if b == nil || exact || b.WhPerKm != 200 {
		t.Errorf("110 km/h 应借用 90~110 档，实际 %+v exact=%v", b, exact)
	}
	// 低速端：10 km/h 落在 0~60 档（开区间下界）
	b, exact = marginWhSample(10, buckets)
	if b == nil || !exact || b.WhPerKm != 180 {
		t.Errorf("10 km/h 应落在 0~60 档，实际 %+v exact=%v", b, exact)
	}
}

// ⚠️ **模型残差 P90 → 电量百分点**。
func TestRouteMargin_误差点数(t *testing.T) {
	m := rmT_Model()
	m.ResidualP90WhPerKm = rmT_F(10)
	// 100 km × 10 Wh/km = 1000 Wh = 1 度；1 ÷ 65 × 100 = 1.5 ⇒ 2 个点
	if got := marginSocErrorPoints(100, 65, m); got == nil || *got != 2 {
		t.Errorf("误差应是 2 个点，实际 %v", got)
	}
	// 没有残差 ⇒ 不给数（不给「0」冒充）
	if got := marginSocErrorPoints(100, 65, rmT_Model()); got != nil {
		t.Errorf("没有残差时不该给误差，实际 %v", *got)
	}
	// 容量算不出来 ⇒ 不给数
	if got := marginSocErrorPoints(100, 0, m); got != nil {
		t.Errorf("容量为 0 时不该给误差，实际 %v", *got)
	}
}

// ⚠️ **没有站时不编数**：不凭空造站，余量表仍然是「空的」而不是「假的」。
func TestRouteMargin_没有站时不编数(t *testing.T) {
	tpl := rmT_Template()
	tpl.Stops = nil
	tpl.StopCount = 0
	p := buildRouteMargin(tpl, rmT_Model(), 65.0, nil)
	if p == nil {
		t.Fatal("余量表不该是 nil")
	}
	if len(p.Stops) != 0 {
		t.Errorf("没有站就不该有站，实际 %d 行", len(p.Stops))
	}
	if len(p.EndpointStops) != 0 {
		t.Errorf("没有站就不该有终点站，实际 %d 行", len(p.EndpointStops))
	}
}

// ⚠️ **算不出里程就不给半截结果**（宁可 400，也不给假数）。
func TestRouteMargin_没有里程不给半截(t *testing.T) {
	tpl := rmT_Template()
	tpl.DistanceKm = 0
	tpl.LastDistanceKm = 0
	if p := buildRouteMargin(tpl, rmT_Model(), 65.0, nil); p != nil {
		t.Error("没有里程时不该给出余量表")
	}
}

// ⚠️ **爬升修正是有口径守卫的**：口径不是 calib_net ⇒ **不给**修正。
func TestRouteMargin_爬升修正要口径一致(t *testing.T) {
	// 口径一致 ⇒ 给修正（爬升 4000 米 / 200 公里 = 20 m/km，远高于该档典型 2 m/km）
	tpl := rmT_Template()
	tpl.Legs[1].AscentM = rmT_I(4000)
	tpl.Legs[1].DescentM = rmT_I(0)
	p := buildRouteMargin(tpl, rmT_Model(), 65.0, rmT_Climb("calib_net"))
	if p == nil {
		t.Fatal("余量表不该是 nil")
	}
	if !p.ClimbCorrectionApplied {
		t.Error("给了爬升模型 ⇒ 应标记「已做爬升修正」")
	}
	if p.Stops[1].GapClimbWhPerKm == nil || *p.Stops[1].GapClimbWhPerKm <= 0 {
		t.Errorf("上坡 20 m/km 远高于典型 ⇒ 修正应为正，实际 %v", p.Stops[1].GapClimbWhPerKm)
	}
	// 口径不一致 ⇒ 不给修正（宁可少算一个修正，也不能混口径）
	p2 := buildRouteMargin(tpl, rmT_Model(), 65.0, rmT_Climb("gross"))
	if p2 == nil {
		t.Fatal("余量表不该是 nil")
	}
	if p2.Stops[1].GapClimbWhPerKm != nil {
		t.Errorf("口径不一致时不该给爬升修正，实际 %v", *p2.Stops[1].GapClimbWhPerKm)
	}
}

// ⚠️ **保底电量由服务端下发**（客户端不许自己写死这个数）。
func TestRouteMargin_保底电量由服务端给(t *testing.T) {
	p := buildRouteMargin(rmT_Template(), rmT_Model(), 65.0, nil)
	if p == nil {
		t.Fatal("余量表不该是 nil")
	}
	if p.ReserveSoc != routeMarginReserveSoc {
		t.Errorf("保底电量应是 %d%%，实际 %d%%", routeMarginReserveSoc, p.ReserveSoc)
	}
	if p.Note == "" {
		t.Error("口径说明不该为空")
	}
}

// ⚠️ **档位 Wh/km 的基数与总数要分清**（爬升修正是加性的）。
func TestRouteMargin_基数与总数(t *testing.T) {
	p := buildRouteMargin(rmT_Template(), rmT_Model(), 65.0, nil)
	if p == nil {
		t.Fatal("余量表不该是 nil")
	}
	for i, s := range p.Stops {
		if s.AssumedWhPerKm == nil || *s.AssumedWhPerKm != 180 {
			t.Errorf("第 %d 行档位基数应是 180 Wh/km，实际 %v", i, s.AssumedWhPerKm)
		}
		if s.AssumedWhPerKmTotal == nil {
			t.Errorf("第 %d 行缺「含修正的总数」", i)
		}
		if math.Abs(*s.AssumedWhPerKmTotal-*s.AssumedWhPerKm) > 1e-9 {
			t.Errorf("第 %d 行没有爬升修正时，总数应等于基数", i)
		}
	}
}
