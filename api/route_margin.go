package main

// ⚠️ **v0.188.0：路线余量表的**判定**搬服务端。**
//
// ## 为什么搬
//
// 「跑这条线到下一站还剩多少电 / 够不够到 20% 保底」原来在**客户端**算
// （app-android/.../ui/RoutePlan.kt 的 buildRoutePlan）⇒
// ① **飞书里问不出来**（mission §2.1；docs/app-conclusions.json 的 route_plan_margin 那条 gap）；
// ② **两端各算一套** —— 那套口径有 6 个阈值 ＋ 8 处评审修出来的坑 ⇒ 改一边就打架。
//
// ## 口径：逐条照抄客户端
//
// 下面每个阈值与每条分支都来自客户端 buildRoutePlan（v0.14.0 / v0.25.0 / v0.28.0 /
// v0.30.0 / v0.31.0 / v0.32.0 / v0.33.0 / v0.34.0 / v0.36.0 / v0.38.0 / v0.43.0 十轮评审）。
//
// ## 输入全部来自服务端自己已有的结论
//
// 模板（route_plans.go）/ 能耗模型（consumption_model.go）/ 容量（station_stats.go）/
// 爬升模型（climb_calib.go）⇒ 客户端不再自己算这些。
//
// ⚠️ **剖面（余量曲线）不搬**：它是**展示**，不是判定 ⇒ 客户端继续用 /route_profile 画。

import (
	"fmt"
	"math"
)

// ---------- 阈值（与客户端逐条对应） ----------
//
// ⚠️ **这些数是判据的一部分** —— 改一个，余量表的结论就变了。
const (
	// routeMarginReserveSoc 保底电量（%）。
	// ⚠️ 客户端也有一份（ui/RoutePlan.kt 的 RESERVE_SOC）⇒ 这里**下发**给客户端，
	// 别让任何一端自己改这个数。
	routeMarginReserveSoc = 20
	// routeMarginClimbMinGapKm 小于这么多公里的段**不给**爬升修正。
	// ⚠️ 照抄客户端 CLIMB_MIN_GAP_KM —— 那里坡度噪声与真实爬升完全不成比例。
	routeMarginClimbMinGapKm = 20.0
	// routeMarginClimbBasis 爬升系数的口径守卫（必须与能耗底档同源）。
	// ⚠️ 不是这个口径 ⇒ **不给**修正（宁可少算一个修正，也不能混口径）。
	routeMarginClimbBasis = "calib_net"
	// routeMarginMinGapKm 小于这么多公里不算「到下一站」（照抄客户端的 0.5）。
	routeMarginMinGapKm = 0.5
)

// routeMarginNote 余量表的口径说明（**服务端唯一一份**）。
const routeMarginNote = "「到下一站还剩多少电」按本车实测能耗模型 ＋ 可用容量算；爬升修正是加性的（按该车速档的典型坡度做 delta，不是绝对坡度）。"

// ---------- DTO ----------

// RouteMarginStop 一站的余量结论（**服务端算的**）。
//
// ⚠️ 不含站画像（典型功率 / 折算耗时）—— 那是**展示**数据，App 用自己的按站画像表。
type RouteMarginStop struct {
	// ChargeID 这场充电的 id（App 用它在自己那份充电列表里找显示字段）。
	ChargeID int `json:"charge_id"`
	// Name 这个站的显示名（服务端唯一一份）。
	Name string `json:"name"`
	// KmAlong 在这条线路上的第几公里。
	KmAlong float64 `json:"km_along"`
	// IsEndpoint 终点处的充电（不在沿途列表里）。
	IsEndpoint bool `json:"is_endpoint"`
	// IsStart **合成**的起点行（起点 → 第一个站那一跳）。
	IsStart bool `json:"is_start"`
	// SocBefore / SocAfter / ArrivalSoc 这站的插枪前 / 充完后 / **到站**电量。
	SocBefore  *int `json:"soc_before"`
	SocAfter   *int `json:"soc_after"`
	ArrivalSoc *int `json:"arrival_soc"`
	// GapToNextKm 到下一站还有多远（公里）。
	GapToNextKm *float64 `json:"gap_to_next_km"`
	// SocAtNextStop 充到 soc_after 后，到下一站还剩多少电（%）。
	SocAtNextStop *int `json:"soc_at_next_stop"`
	// NextStopRealSoc 下一站的**真实**到站电量（上次实测，%）。
	NextStopRealSoc *int `json:"next_stop_real_soc"`
	// GapNeedPct 这一跳要消耗多少个电量点。
	GapNeedPct *int `json:"gap_need_pct"`
	// RequiredSocHere 留 20% 保底，本站至少要充到多少（>100 = 充满也守不住保底）。
	RequiredSocHere *int `json:"required_soc_here"`
	// GapErrorPoints 这一跳的估计误差（模型残差 P90 换算成电量百分点）。
	GapErrorPoints *int `json:"gap_error_points"`
	// NextStopName 下一站的名字。
	NextStopName *string `json:"next_stop_name"`
	// DistanceToEndKm 到终点还有多远（只有最后一个沿途站有）。
	DistanceToEndKm *float64 `json:"distance_to_end_km"`
	// EstSocAtEnd 估到终点时剩多少电（只有最后一个沿途站有）。
	EstSocAtEnd *int `json:"est_soc_at_end"`
	// GapAscentM / GapDescentM 这一段的爬升 / 下降（米）。
	GapAscentM  *int `json:"gap_ascent_m"`
	GapDescentM *int `json:"gap_descent_m"`
	// GapClimbWhPerKm 这一段的爬升能耗修正（Wh/km，可为负）。
	GapClimbWhPerKm *float64 `json:"gap_climb_wh_per_km"`
	// GapBucketLabel 这一段用到的 Wh/km 档（如「90–110」）。
	GapBucketLabel *string `json:"gap_bucket_label"`
	// GapSpeedBorrowed 借用邻档（不是本档实测）。
	GapSpeedBorrowed bool `json:"gap_speed_borrowed"`
	// AssumedSpeedKph 算这一跳用的车速（km/h）。
	AssumedSpeedKph *float64 `json:"assumed_speed_kph"`
	// AssumedWhPerKm 档位**基数**（与档位表同源）。
	AssumedWhPerKm *float64 `json:"assumed_wh_per_km"`
	// AssumedWhPerKmTotal 含爬升修正后的**总数**。
	AssumedWhPerKmTotal *float64 `json:"assumed_wh_per_km_total"`
	// GapClimbKwh / GapClimbPct 爬升修正折合多少度 / 多少百分点。
	GapClimbKwh *float64 `json:"gap_climb_kwh"`
	GapClimbPct *float64 `json:"gap_climb_pct"`
}

// RouteMarginPayload 一条线路的余量结论。
type RouteMarginPayload struct {
	CarID       int    `json:"car_id"`
	TemplateKey string `json:"template_key"`
	// Stops 沿途站（**含合成的起点行**在最前）—— 顺序与客户端原来的一模一样。
	Stops []RouteMarginStop `json:"stops"`
	// EndpointStops 终点处的充电（不在沿途列表里）。
	EndpointStops []RouteMarginStop `json:"endpoint_stops"`
	// ClimbCorrectionApplied 有没有做爬升修正（前提行要如实说）。
	ClimbCorrectionApplied bool `json:"climb_correction_applied"`
	// ReserveSoc 保底电量（%）—— 客户端**不许**自己写死这个数。
	ReserveSoc int `json:"reserve_soc"`
	// Note 口径说明（服务端唯一一份）。
	Note string `json:"note"`
}

// ---------- 选档（照抄客户端 ConsumptionModel.whSample） ----------

// marginWhSample 按车速取实测 Wh/km；exact=false 表示**借用了邻档**。
//
// ⚠️ **逐条照抄**客户端 whSample：落在本档 = `speed >= min && speed < max`
// （末档的 max 是 Int.MAX_VALUE ⇒ 等价于开区间）；落在所有档外 ⇒ 取**最近**的一档，
// 距离口径是 `(min − speed)` / `(speed − max + 1)`。
func marginWhSample(speed float64, buckets []ConsumptionModelBucket) (*ConsumptionModelBucket, bool) {
	if len(buckets) == 0 {
		return nil, false
	}
	for i := range buckets {
		b := buckets[i]
		if speed >= float64(b.MinKph) && speed < float64(b.MaxKph) {
			return &b, true
		}
	}
	best := -1
	bestDist := 0.0
	for i := range buckets {
		b := buckets[i]
		var d float64
		switch {
		case speed < float64(b.MinKph):
			d = float64(b.MinKph) - speed
		case speed >= float64(b.MaxKph):
			d = speed - float64(b.MaxKph) + 1
		default:
			d = 0
		}
		if best < 0 || d < bestDist {
			best, bestDist = i, d
		}
	}
	if best < 0 {
		return nil, false
	}
	b := buckets[best]
	return &b, false
}

// marginBucketLabel 档位名（如「90–110」；末档是「130+」）。
//
// ⚠️ 与客户端 SpeedBucket.label **逐字一致**（含 en-dash 与「130+」那个特例）。
func marginBucketLabel(b *ConsumptionModelBucket) string {
	if b.MaxKph == consumptionMaxKph {
		return fmt.Sprintf("%d+", b.MinKph)
	}
	return fmt.Sprintf("%d–%d", b.MinKph, b.MaxKph)
}

// marginSocErrorPoints 模型残差 P90 换算成一段路程的**电量百分点**（照抄客户端）。
func marginSocErrorPoints(km float64, capacityKwh float64, consumption *ConsumptionModelPayload) *int {
	if consumption == nil || consumption.ResidualP90WhPerKm == nil {
		return nil
	}
	if capacityKwh <= 0 || km <= 0 {
		return nil
	}
	v := int(math.Round((*consumption.ResidualP90WhPerKm * km / 1000.0) / capacityKwh * 100.0))
	return &v
}

// marginLegRange 一段行程的里程区间与爬升/下降（分摊用）。
type marginLegRange struct {
	startKm  float64
	endKm    float64
	ascentM  int
	descentM int
}

// marginBetween 某段里程区间内的爬升 / 下降（按重叠比例分摊；照抄客户端 ascentBetween）。
func marginBetween(ranges []marginLegRange, fromKm, toKm float64, ascent bool) int {
	if toKm <= fromKm {
		return 0
	}
	sum := 0.0
	for _, r := range ranges {
		if r.endKm <= r.startKm {
			continue
		}
		ov := math.Min(r.endKm, toKm) - math.Max(r.startKm, fromKm)
		if ov <= 0 {
			continue
		}
		v := float64(r.descentM)
		if ascent {
			v = float64(r.ascentM)
		}
		sum += v * (ov / (r.endKm - r.startKm))
	}
	return int(sum)
}

// marginIntPtr / marginFloatPtr 小工具（取地址用）。
func marginIntPtr(v int) *int           { return &v }
func marginFloatPtr(v float64) *float64 { return &v }
