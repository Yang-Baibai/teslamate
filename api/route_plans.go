package main

// ⚠️ **v0.181.0：长途线路（journey）的选线与沿途站归属 —— 收归服务端。**
//
// ## 为什么搬
//
// 原来「哪些算一次长途出门」「沿途有哪些充电站」是在**客户端**算的
// （app-android/.../ui/RouteJourney.kt 的 buildJourneys）。
// ⇒ ① **飞书里问不出来**（mission §2.1：App 能看到的，Hermes 也要能取）；
//   ② **两端各算一套** —— 那套判据有 7 个阈值 ＋ 7 处评审修出来的坑，
//      只写在客户端 ⇒ 以后改一边，用户在 App 与飞书里会看到**不同的线路**。
//
// ## ⚠️ 样本总体必须与客户端**完全一致**
//
// 本文件**复用** sqlDrives / sqlCharges 与**同样的 limit**（drives 500 / charges 300）
// ⇒ 与客户端 api.drives(carId, 500) / api.charges(carId, 300) 取到的是**同一批数据**、
//   **同一个顺序**（sqlCharges 是 ORDER BY start_date DESC）
// ⇒ 同一套算法 ⇒ 两边算出来的线路**必然相同**。
// （这与 api/range_estimate_http.go 的做法一致：**必须读同一批行程**。）
//
// ## 本文件只做「选线 + 沿途站」
//
// 「到下一站还剩多少电 / 够不够到 20% 保底」那个判定仍在客户端（buildRoutePlan）
// ⇒ **判定仍只有一份**（客户端）⇒ 不构成两套算法。
// ⇒ 下一轮再把那个判定也搬过来 ⇒ 那时这条 gap 才算还清。
//
// ## ⚠️ 口径逐条照抄客户端
//
// 下面每个阈值与每条分支都来自客户端的 buildJourneys
// （v0.28.0 / v0.29.0 / v0.30.0 / v0.31.0 / v0.34.0 / v0.36.0 / v0.37.0 七轮评审修出来的），
// **包括那些看起来多余的细节** —— 少一条，线路就会被切开或合并。

import (
	"context"
	"fmt"
	"math"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
)

// ---------- 阈值（与客户端 RouteJourneyLimits 逐条对应） ----------
//
// ⚠️ **这些数是判据的一部分** —— 改一个，线路的切分就变了。
// ⇒ 客户端那份在 v0.181.0 一并删除 ⇒ **只留这一份**。
const (
	// routeMaxGapKm 相邻两趟之间的里程缺口上限（公里）。
	// 实测同一次出门的最大缺口是 31 公里（去充电桩的绕行）。
	routeMaxGapKm = 60.0

	// routeMaxGapHours 相邻两趟之间的时间间隔上限（小时）。
	routeMaxGapHours = 36.0

	// routeHomeReturnKm 「回到常驻点」的判据：同一个充电点相隔超过这么多公里。
	//
	// ⚠️ v0.28.0：50 → 120。50 会误判「印象雅緻酒店」两场相隔 59.97 公里
	// （那其实是里程表跳了一段，不是开出去又开回来）。
	routeHomeReturnKm = 120.0

	// routeHomeReturnMinHours 「回到常驻点」还要求时间上隔开了（至少 6 小时）。
	// 同一个桩 5 分钟内插拔两次是**一次插枪**被切成两段，不是「回来了」。
	routeHomeReturnMinHours = 6.0

	// routeBoundaryTolKm 充电与行程边界的匹配容差（公里）。
	// 实测充电里程与上一段 end_km 的误差 ≤ 0.1 公里。
	routeBoundaryTolKm = 3.0

	// routeSameStopMergeKm 同一地点键 ＋ 里程差 ≤ 1 公里 ⇒ 合并（一次插枪被切成两段）。
	routeSameStopMergeKm = 1.0

	// routeNearbyStopMergeKm 里程差 ≤ 3 公里 ⇒ 合并
	//（同一个桩被地理编码拆成两个 OSM 节点 —— 实测「修文县」两个节点相距 1.9 公里）。
	routeNearbyStopMergeKm = 3.0

	// routeMinDistanceKm 多远算「长途」。
	routeMinDistanceKm = 200.0

	// routeDrivesLimit / routeChargesLimit **必须与客户端一致** ⇒ 样本总体才相同。
	routeDrivesLimit  = 500
	routeChargesLimit = 300
)

// ---------- DTO ----------

// RoutePlanLeg 一段连续行驶（对应客户端的 JourneyLeg）。
type RoutePlanLeg struct {
	DriveID       int     `json:"drive_id"`
	StartKm       float64 `json:"start_km"`
	EndKm         float64 `json:"end_km"`
	DistanceKm    float64 `json:"distance_km"`
	StartSoc      *int    `json:"start_soc"`
	EndSoc        *int    `json:"end_soc"`
	StartPlace    *string `json:"start_place"`
	EndPlace      *string `json:"end_place"`
	StartDate     string  `json:"start_date"`
	EndDate       string  `json:"end_date"`
	DurationMin   *int    `json:"duration_min"`
	StartPlaceKey *string `json:"start_place_key"`
	EndPlaceKey   *string `json:"end_place_key"`
	AscentM       *int    `json:"ascent_m"`
	DescentM      *int    `json:"descent_m"`
}

// RoutePlanChargeMeta 这场充电的**显示字段**。
//
// ⚠️ 客户端本地通常有同一条充电记录（同一个 id）⇒ 直接用本地那份；
// 但服务端若比客户端**新**（刚多了一场充电），本地那份就没有这一条
// ⇒ 用这份兜底，保证沿途站**不丢**（丢了余量表就会缺一站）。
type RoutePlanChargeMeta struct {
	// Name 这场充电的**显示名**（服务端唯一一份）。
	//
	// ⚠️ 口径：place_label → poi_name → geofence_name → 地址第一段 → 「充电站」。
	// ⇒ 飞书里报站名**不用**自己再拼一遍（ADR #41：文案只有一份）。
	Name         string   `json:"name"`
	PlaceLabel   *string  `json:"place_label"`
	PoiName      *string  `json:"poi_name"`
	GeofenceName *string  `json:"geofence_name"`
	Place        *string  `json:"place"`
	PlaceKey     *string  `json:"place_key"`
	MapLat       *float64 `json:"map_lat"`
	MapLng       *float64 `json:"map_lng"`
	StartSoc     *int     `json:"start_soc"`
	EndSoc       *int     `json:"end_soc"`
	StartOdoKm   *float64 `json:"start_odometer_km"`
	StartDate    string   `json:"start_date"`
}

// RoutePlanStop 一个沿途充电停靠（对应客户端的 JourneyStop）。
//
// ⚠️ **只下发 charge_id**，不下发整条充电记录 ⇒ 客户端用 id 从**自己那份**充电列表里找
// ⇒ 显示名 / 起止电量全部本地取（与现在完全一致）⇒ JSON 也不臃肿。
type RoutePlanStop struct {
	ChargeID   int                 `json:"charge_id"`
	KmAlong    float64             `json:"km_along"`
	IsEndpoint bool                `json:"is_endpoint"`
	SocBefore  *int                `json:"soc_before"`
	SocAfter   *int                `json:"soc_after"`
	ArrivalSoc *int                `json:"arrival_soc"`
	Charge     RoutePlanChargeMeta `json:"charge"`
}

// RoutePlanTemplate 一条长途线路（对应客户端的 RouteTemplate）。
type RoutePlanTemplate struct {
	Key            string          `json:"key"`
	FromLabel      string          `json:"from_label"`
	ToLabel        string          `json:"to_label"`
	DistanceKm     float64         `json:"distance_km"`
	StartSoc       *int            `json:"start_soc"`
	EndSoc         *int            `json:"end_soc"`
	LegCount       int             `json:"leg_count"`
	TotalLegCount  int             `json:"total_leg_count"`
	StopCount      int             `json:"stop_count"`
	LastDrivenAt   string          `json:"last_driven_at"`
	StartOdoKm     float64         `json:"start_odo_km"`
	LastDistanceKm float64         `json:"last_distance_km"`
	AvgSpeedKph    *float64        `json:"avg_speed_kph"`
	IsRoundTrip    bool            `json:"is_round_trip"`
	LastStartSoc   *int            `json:"last_start_soc"`
	LastEndSoc     *int            `json:"last_end_soc"`
	LastDriveID    int             `json:"last_drive_id"`
	LegIDs         []int           `json:"leg_ids"`
	Legs           []RoutePlanLeg  `json:"legs"`
	Stops          []RoutePlanStop `json:"stops"`
}

// ---------- 算法内部结构 ----------

// routeLeg 一段连续行驶（算法内部用）。
type routeLeg struct {
	driveID       int
	startKm       float64
	endKm         float64
	distanceKm    float64
	startSoc      *int
	endSoc        *int
	startPlace    *string
	endPlace      *string
	startDate     time.Time
	endDate       time.Time
	durationMin   *int
	startPlaceKey *string
	endPlaceKey   *string
	ascentM       *int
	descentM      *int
}

// routeCharge 一场充电（算法内部只需要这几个字段）。
type routeCharge struct {
	id         int
	startOdoKm *float64
	startDate  time.Time
	startSoc   *int
	endSoc     *int
	placeKey   string
	meta       RoutePlanChargeMeta
}

// routeStop 一个沿途站（算法内部用）。
type routeStop struct {
	rep        routeCharge
	kmAlong    float64
	isEndpoint bool
	socBefore  *int
	socAfter   *int
	arrivalSoc *int
}

// ---------- 小工具（口径照抄客户端） ----------

// routeFirstSegment 地址只取第一段（照抄客户端 Fmt.kt 的 firstSegment）。
func routeFirstSegment(s *string) *string {
	if s == nil {
		return nil
	}
	raw := *s
	seg := raw
	if i := strings.IndexRune(seg, '，'); i >= 0 {
		seg = seg[:i]
	}
	if i := strings.IndexRune(seg, ','); i >= 0 {
		seg = seg[:i]
	}
	seg = strings.TrimSpace(seg)
	if seg == "" {
		return &raw
	}
	return &seg
}

// routeKotlinRound2 四舍五入到 2 位小数（照抄客户端 Math.round(x*100)/100）。
func routeKotlinRound2(v float64) float64 {
	return math.Floor(v*100.0+0.5) / 100.0
}

// routePlaceKey 充电地点的**稳定键**（照抄客户端 RouteJourney.kt 的 placeKey）。
//
// ⚠️ 服务端已按真实距离聚类并下发 place_key ⇒ 正常路径直接用它；
// 兜底（老数据没有 place_key）才走「名字 + 坐标 2 位小数」的老逻辑。
func routePlaceKey(c *Charge) string {
	if c.PlaceKey != nil && strings.TrimSpace(*c.PlaceKey) != "" {
		return *c.PlaceKey
	}
	name := "未知地点"
	if c.PoiName != nil && strings.TrimSpace(*c.PoiName) != "" {
		name = *c.PoiName
	} else if c.GeofenceName != nil && strings.TrimSpace(*c.GeofenceName) != "" {
		name = *c.GeofenceName
	} else if seg := routeFirstSegment(c.Place); seg != nil {
		name = *seg
	}
	lat := "x"
	if c.MapLat != nil {
		lat = strconv.FormatFloat(routeKotlinRound2(*c.MapLat), 'f', -1, 64)
	}
	lng := "x"
	if c.MapLng != nil {
		lng = strconv.FormatFloat(routeKotlinRound2(*c.MapLng), 'f', -1, 64)
	}
	return name + "@" + lat + "," + lng
}

// routeStopName 这场充电的**显示名**（服务端唯一一份）。
//
// ⚠️ 口径与客户端的 stopDisplayName 一致：
// place_label → poi_name → geofence_name → 地址第一段 → 「充电站」。
func routeStopName(c *Charge) string {
	if c.PlaceLabel != nil && strings.TrimSpace(*c.PlaceLabel) != "" {
		return *c.PlaceLabel
	}
	if c.PoiName != nil && strings.TrimSpace(*c.PoiName) != "" {
		return *c.PoiName
	}
	if c.GeofenceName != nil && strings.TrimSpace(*c.GeofenceName) != "" {
		return *c.GeofenceName
	}
	if seg := routeFirstSegment(c.Place); seg != nil {
		return *seg
	}
	return "充电站"
}

// routeHoursBetween 两个时刻之间的**整小时**数。
//
// ⚠️ 照抄客户端 ChronoUnit.HOURS.between —— 它是**截断**（不是四舍五入）。
func routeHoursBetween(a time.Time, b time.Time) float64 {
	return float64(b.Sub(a) / time.Hour)
}

// routeFirstNonNil 两个可选指针，取第一个非 nil 的。
func routeFirstNonNil(a *string, b *string) *string {
	if a != nil {
		return a
	}
	return b
}

// ---------- 主算法 ----------

// buildRouteTemplates 服务端版的「长途线路清单 + 沿途站」。
//
// ⚠️ **逐条照抄**客户端的 buildJourneys + buildRouteTemplates
// （两个函数合成一个：客户端那两层只是把 journey 再映射成模板）。
func buildRouteTemplates(drives []*Drive, charges []*Charge) []RoutePlanTemplate {
	out := []RoutePlanTemplate{}

	// ① 段（里程升序，稳定排序 —— 与客户端同一顺序）。
	legs := make([]routeLeg, 0, len(drives))
	for _, d := range drives {
		if d.OdoStart == nil || d.OdoEnd == nil {
			continue
		}
		s := *d.OdoStart
		e := *d.OdoEnd
		if e <= s {
			continue
		}
		dist := e - s
		if d.Distance != nil {
			dist = *d.Distance
		}
		endDate := d.StartDate
		if d.EndDate != nil {
			endDate = *d.EndDate
		}
		legs = append(legs, routeLeg{
			driveID:       d.ID,
			startKm:       s,
			endKm:         e,
			distanceKm:    dist,
			startSoc:      d.StartSoc,
			endSoc:        d.EndSoc,
			startPlace:    routeFirstNonNil(d.StartGeofence, routeFirstSegment(d.StartPlace)),
			endPlace:      routeFirstNonNil(d.EndGeofence, routeFirstSegment(d.EndPlace)),
			startDate:     d.StartDate,
			endDate:       endDate,
			durationMin:   d.DurationMin,
			startPlaceKey: d.StartPlaceKey,
			endPlaceKey:   d.EndPlaceKey,
			ascentM:       d.AscentM,
			descentM:      d.DescentM,
		})
	}
	sort.SliceStable(legs, func(i, j int) bool { return legs[i].startKm < legs[j].startKm })
	if len(legs) == 0 {
		return out
	}

	// ② 充电（里程升序，稳定排序 —— 与客户端同一顺序）。
	cs := make([]routeCharge, 0, len(charges))
	for _, c := range charges {
		if c.StartOdoKm == nil {
			continue
		}
		cs = append(cs, routeCharge{
			id:         c.ID,
			startOdoKm: c.StartOdoKm,
			startDate:  c.StartDate,
			startSoc:   c.StartSoc,
			endSoc:     c.EndSoc,
			placeKey:   routePlaceKey(c),
			meta: RoutePlanChargeMeta{
				Name:         routeStopName(c),
				PlaceLabel:   c.PlaceLabel,
				PoiName:      c.PoiName,
				GeofenceName: c.GeofenceName,
				Place:        c.Place,
				PlaceKey:     c.PlaceKey,
				MapLat:       c.MapLat,
				MapLng:       c.MapLng,
				StartSoc:     c.StartSoc,
				EndSoc:       c.EndSoc,
				StartOdoKm:   c.StartOdoKm,
				StartDate:    c.StartDate.UTC().Format(time.RFC3339),
			},
		})
	}
	sort.SliceStable(cs, func(i, j int) bool { return *cs[i].startOdoKm < *cs[j].startOdoKm })

	// ③ 「是否回家」的判定（按里程顺序走一遍，记下每个地点上次出现的里程与时刻）。
	type seenAt struct {
		odo  float64
		date time.Time
	}
	lastSeen := map[string]seenAt{}
	homeReturn := map[int]bool{}
	for _, c := range cs {
		odo := *c.startOdoKm
		prev, ok := lastSeen[c.placeKey]
		if ok {
			kmApart := odo - prev.odo
			hoursApart := routeHoursBetween(prev.date, c.startDate)
			// ⚠️ v0.28.0（P1-8）：同时看**时间** ——
			// 同一个桩在几分钟内插拔两次（一次插枪被切成两个 process）不是「回来了」。
			if kmApart > routeHomeReturnKm && hoursApart >= routeHomeReturnMinHours {
				homeReturn[c.id] = true
			}
		}
		lastSeen[c.placeKey] = seenAt{odo: odo, date: c.startDate}
	}

	// ④ 按连续性切 journey。
	var chains [][]routeLeg
	var cur []routeLeg
	for _, leg := range legs {
		if len(cur) > 0 {
			prev := cur[len(cur)-1]
			gapKm := leg.startKm - prev.endKm
			gapH := routeHoursBetween(prev.startDate, leg.startDate)
			brokeByGap := gapKm > routeMaxGapKm || gapH > routeMaxGapHours
			// 「回家」也要断：上一段结束时车回到了常驻点并充了电。
			homeBack := false
			for _, c := range cs {
				odo := *c.startOdoKm
				if odo >= prev.endKm-routeBoundaryTolKm &&
					odo <= leg.startKm+routeBoundaryTolKm && homeReturn[c.id] {
					homeBack = true
					break
				}
			}
			if brokeByGap || homeBack {
				chains = append(chains, cur)
				cur = nil
			}
		}
		cur = append(cur, leg)
	}
	if len(cur) > 0 {
		chains = append(chains, cur)
	}

	// ⑤ 每条 journey 分配沿途站。
	for _, chain := range chains {
		if len(chain) < 2 {
			continue
		}
		startKm := chain[0].startKm
		endKm := chain[len(chain)-1].endKm
		if endKm-startKm < routeMinDistanceKm {
			continue
		}
		stops := assignRouteStops(chain, cs, startKm)

		first := chain[0]
		last := chain[len(chain)-1]

		legsOut := make([]RoutePlanLeg, 0, len(chain))
		legIDs := make([]int, 0, len(chain))
		meaningful := 0
		var kmSum float64
		var minSum float64
		for _, l := range chain {
			legsOut = append(legsOut, RoutePlanLeg{
				DriveID:       l.driveID,
				StartKm:       l.startKm,
				EndKm:         l.endKm,
				DistanceKm:    l.distanceKm,
				StartSoc:      l.startSoc,
				EndSoc:        l.endSoc,
				StartPlace:    l.startPlace,
				EndPlace:      l.endPlace,
				StartDate:     l.startDate.UTC().Format(time.RFC3339),
				EndDate:       l.endDate.UTC().Format(time.RFC3339),
				DurationMin:   l.durationMin,
				StartPlaceKey: l.startPlaceKey,
				EndPlaceKey:   l.endPlaceKey,
				AscentM:       l.ascentM,
				DescentM:      l.descentM,
			})
			legIDs = append(legIDs, l.driveID)
			if l.distanceKm >= 2.0 {
				meaningful++
			}
			if l.durationMin != nil && *l.durationMin > 0 && l.distanceKm >= 2.0 {
				kmSum += l.distanceKm
				minSum += float64(*l.durationMin)
			}
		}

		// ⚠️ v0.30.0：真实均速（不再硬编码 90 km/h）。
		// 只统计**有里程**的段（挪车微行程的时长是噪声）。
		var avgSpeed *float64
		if kmSum > 0.1 && minSum > 0 {
			v := kmSum / (minSum / 60.0)
			avgSpeed = &v
		}

		// ⚠️ v0.31.0：起止地点键相同 ⇒ 这一趟是**往返**。
		isRoundTrip := first.startPlaceKey != nil && last.endPlaceKey != nil &&
			*first.startPlaceKey == *last.endPlaceKey

		stopsOut := make([]RoutePlanStop, 0, len(stops))
		midStops := 0
		for _, s := range stops {
			if !s.isEndpoint {
				midStops++
			}
			stopsOut = append(stopsOut, RoutePlanStop{
				ChargeID:   s.rep.id,
				KmAlong:    s.kmAlong,
				IsEndpoint: s.isEndpoint,
				SocBefore:  s.socBefore,
				SocAfter:   s.socAfter,
				ArrivalSoc: s.arrivalSoc,
				Charge:     s.rep.meta,
			})
		}

		fromLabel := "起点"
		if first.startPlace != nil {
			fromLabel = *first.startPlace
		}
		toLabel := "终点"
		if last.endPlace != nil {
			toLabel = *last.endPlace
		}

		out = append(out, RoutePlanTemplate{
			Key:            fmt.Sprintf("journey-%d-%d", first.driveID, last.driveID),
			FromLabel:      fromLabel,
			ToLabel:        toLabel,
			DistanceKm:     endKm - startKm,
			StartSoc:       first.startSoc,
			EndSoc:         last.endSoc,
			LegCount:       meaningful,
			TotalLegCount:  len(chain),
			StopCount:      midStops,
			LastDrivenAt:   last.startDate.UTC().Format(time.RFC3339),
			StartOdoKm:     startKm,
			LastDistanceKm: endKm - startKm,
			AvgSpeedKph:    avgSpeed,
			IsRoundTrip:    isRoundTrip,
			LastStartSoc:   first.startSoc,
			LastEndSoc:     last.endSoc,
			LastDriveID:    last.driveID,
			LegIDs:         legIDs,
			Legs:           legsOut,
			Stops:          stopsOut,
		})
	}

	// 按「最近一次走的时刻」倒序。
	sort.SliceStable(out, func(i, j int) bool { return out[i].LastDrivenAt > out[j].LastDrivenAt })
	return out
}

// assignRouteStops 给一条 journey 分配沿途站。
//
// ⚠️ **口径逐条照抄客户端**（v0.28.0 / v0.29.0 / v0.34.0 / v0.36.0 / v0.37.0）：
//   - 归属**同时看里程与时间**；
//   - 查重**在判据之后**落标记（v0.29.0 的无条件消费 bug）；
//   - 同一插枪被切成两个 process 时**合并**；
//   - socBefore 取**时间最早**那场（v0.37.0）；
//   - arrivalSoc 取**那一趟行程的 end_soc**（v0.36.0）。
func assignRouteStops(chain []routeLeg, cs []routeCharge, startKm float64) []routeStop {
	stops := []routeStop{}
	seen := map[int]bool{}
	for i, leg := range chain {
		nextStart := leg.endKm + routeBoundaryTolKm
		notAfter := leg.endDate.Add(time.Duration(routeMaxGapHours*3600) * time.Second)
		if i+1 < len(chain) {
			nextStart = chain[i+1].startKm
			notAfter = chain[i+1].startDate
		}
		notBefore := leg.endDate
		for _, c := range cs {
			// ⚠️ **v0.29.0（P0-1）：查重必须在判据**之后**。
			// 原来写的是「无条件消费」⇒ 第 0 段就把全库充电标成已处理。
			if seen[c.id] {
				continue
			}
			odo := *c.startOdoKm
			if odo < leg.endKm-routeBoundaryTolKm {
				continue
			}
			if odo > nextStart+routeBoundaryTolKm {
				continue
			}
			// ⚠️ 时间约束（v0.28.0 P0-2）：缺口里的充电必须时间上落在
			// 这一段结束到下一段开始之间；末段允许宽到 36 小时。
			if c.startDate.Before(notBefore.Add(-time.Hour)) {
				continue
			}
			if c.startDate.After(notAfter) {
				continue
			}
			// ✅ 判据全过，现在才落「已处理」标记。
			seen[c.id] = true

			// ⚠️ **v0.28.0（P0-3）/ v0.34.0：合并。**
			//   ① 同一地点键 + 里程差 ≤ 1 公里 —— 一次插枪被切成两个 process；
			//   ② 里程差 ≤ 3 公里 —— 同一个桩被地理编码拆成两个 OSM 节点。
			merged := -1
			for idx := range stops {
				d := math.Abs(*stops[idx].rep.startOdoKm - odo)
				if (stops[idx].rep.placeKey == c.placeKey && d <= routeSameStopMergeKm) ||
					d <= routeNearbyStopMergeKm {
					merged = idx
					break
				}
			}
			if merged >= 0 {
				prev := stops[merged]
				oldRepID := prev.rep.id
				// socAfter 取「充得更高」的那一场。
				later := c
				prevEnd := 0
				if prev.rep.endSoc != nil {
					prevEnd = *prev.rep.endSoc
				}
				cEnd := 0
				if c.endSoc != nil {
					cEnd = *c.endSoc
				}
				if cEnd < prevEnd {
					later = prev.rep
				}
				// ⚠️ **v0.37.0：socBefore 必须取**时间最早**那一场的插枪电量。
				socBefore := prev.socBefore
				if c.startDate.Before(prev.rep.startDate) {
					if c.startSoc != nil {
						socBefore = c.startSoc
					}
				}
				stops[merged] = routeStop{
					rep:        later,
					kmAlong:    prev.kmAlong,
					isEndpoint: prev.isEndpoint,
					socBefore:  socBefore,
					socAfter:   later.endSoc,
					arrivalSoc: prev.arrivalSoc,
				}
				// 合并掉的那一场也要标记为已处理（否则它会被再处理一次）。
				seen[oldRepID] = true
				continue
			}

			kmAlong := odo - startKm
			if kmAlong < 0 {
				kmAlong = 0
			}
			stops = append(stops, routeStop{
				rep:        c,
				kmAlong:    kmAlong,
				isEndpoint: i == len(chain)-1,
				socBefore:  c.startSoc,
				socAfter:   c.endSoc,
				// ⚠️ **v0.36.0（C1）：到站真值取**那一趟行程的结束电量**。
				arrivalSoc: chain[i].endSoc,
			})
		}
	}
	// 按里程升序（稳定）。
	sort.SliceStable(stops, func(i, j int) bool { return stops[i].kmAlong < stops[j].kmAlong })
	return stops
}

// ---------- 取数 ----------

// queryRouteDrives 取行程（**与客户端同一批**：同一条 SQL、同一个 limit）。
func queryRouteDrives(ctx context.Context, carID int) ([]*Drive, error) {
	rows, err := db.Query(ctx, sqlDrives, carID, routeDrivesLimit, 0)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*Drive{}
	for rows.Next() {
		d, err := scanDrive(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// queryRouteCharges 取充电（**与客户端同一批**：同一条 SQL、同一个 limit、同一个顺序）。
func queryRouteCharges(ctx context.Context, carID int) ([]*Charge, error) {
	rows, err := db.Query(ctx, sqlCharges, carID, routeChargesLimit, 0)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*Charge{}
	for rows.Next() {
		c, err := scanCharge(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// ---------- 端点 ----------

// handleRoutePlans 长途线路清单 + 沿途站（**服务端算的结论**）。
//
// GET /api/v1/cars/{id}/route_plans
//
// ⚠️ **v0.181.0 新增**：原来这套判定在**客户端**（buildJourneys）
// ⇒ 飞书里问不出来（mission §2.1）⇒ 现在服务端出结论，App 与飞书同一个数。
func handleRoutePlans(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	ctx := context.Background()
	drives, err := queryRouteDrives(ctx, id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	charges, err := queryRouteCharges(ctx, id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, buildRouteTemplates(drives, charges))
}
