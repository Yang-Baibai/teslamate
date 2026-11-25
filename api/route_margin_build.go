package main

// ⚠️ **v0.188.0：余量判定的算法主体与服务端端点。**
//
// ## 口径：逐条照抄客户端 buildRoutePlan（十轮评审修出来的那些坑都在这儿）
//
// ⚠️ **剖面不搬**：余量曲线是**展示**，客户端继续用 /route_profile 画。

import (
	"context"
	"fmt"
	"math"
	"net/http"
	"strings"
	"time"
)

// marginFloatValue 取值（nil ⇒ 0）。
func marginFloatValue(p *float64) float64 {
	if p == nil {
		return 0
	}
	return *p
}

// marginStringPtr 取字符串地址。
func marginStringPtr(v string) *string { return &v }

// buildRouteMargin 算一条线路的余量表（**逐条照抄**客户端 buildRoutePlan）。
//
// @param template     服务端算好的线路模板（route_plans.go）。
// @param consumption  本车实测能耗模型（标定法，consumption_model.go）。
// @param capacityKwh  反推出的整包容量（kWh，station_stats.go）。
// @param climb        爬升能耗模型（calib_net 口径，climb_calib.go）。
func buildRouteMargin(
	template RoutePlanTemplate,
	consumption *ConsumptionModelPayload,
	capacityKwh float64,
	climb *ClimbModel,
) *RouteMarginPayload {
	// ⚠️ 里程窗口用**最近那一趟的真实里程**（照抄客户端 v0.14.0 P0-4）。
	runDistance := template.LastDistanceKm
	if runDistance <= 0 {
		runDistance = template.DistanceKm
	}
	if runDistance <= 0 {
		return nil
	}
	journeyStartKm := 0.0
	if len(template.Legs) > 0 {
		journeyStartKm = template.Legs[0].StartKm
	}
	ranges := make([]marginLegRange, 0, len(template.Legs))
	for _, l := range template.Legs {
		r := marginLegRange{startKm: l.StartKm - journeyStartKm, endKm: l.EndKm - journeyStartKm}
		if l.AscentM != nil {
			r.ascentM = *l.AscentM
		}
		if l.DescentM != nil {
			r.descentM = *l.DescentM
		}
		ranges = append(ranges, r)
	}
	// 整条线路的**计划车速**（照抄客户端：avgSpeedKph ?: 90.0）。
	planSpeed := 90.0
	if template.AvgSpeedKph != nil {
		planSpeed = *template.AvgSpeedKph
	}
	// 整条线路的 Wh/km（按计划车速选档）。
	var whPerKm *float64
	if consumption != nil {
		if b, _ := marginWhSample(planSpeed, consumption.Buckets); b != nil {
			whPerKm = marginFloatPtr(b.WhPerKm)
		}
	}
	// 最后一个**沿途**站的下标（照抄客户端 lastRouteStopIdx）。
	lastRouteStopIdx := -1
	for i := range template.Stops {
		if !template.Stops[i].IsEndpoint {
			lastRouteStopIdx = i
		}
	}

	rows := make([]RouteMarginStop, 0, len(template.Stops))
	for idx := range template.Stops {
		js := template.Stops[idx]
		// 到达后的那次充电没有「下一站」。
		var next *RoutePlanStop
		if idx+1 < len(template.Stops) && !js.IsEndpoint {
			next = &template.Stops[idx+1]
		}
		var gapKm *float64
		if next != nil {
			g := next.KmAlong - js.KmAlong
			if g > routeMarginMinGapKm {
				gapKm = &g
			}
		}
		// ⚠️ 这一段的能耗必须按**这一段**的速度算（照抄客户端 v0.32.0 P1-3）。
		gapLegs := make([]RoutePlanLeg, 0, 4)
		if gapKm != nil {
			for _, leg := range template.Legs {
				s := leg.StartKm - journeyStartKm
				e := leg.EndKm - journeyStartKm
				if e > js.KmAlong && s < js.KmAlong+*gapKm {
					gapLegs = append(gapLegs, leg)
				}
			}
		}
		gapMinutes := 0
		gapDist := 0.0
		for _, leg := range gapLegs {
			if leg.DurationMin != nil {
				gapMinutes += *leg.DurationMin
			}
			gapDist += leg.DistanceKm
		}
		var gapSegSpeed *float64
		if len(gapLegs) > 0 && gapMinutes > 0 {
			gapSegSpeed = marginFloatPtr(gapDist / (float64(gapMinutes) / 60.0))
		}
		var gapSample *ConsumptionModelBucket
		gapExact := false
		if gapSegSpeed != nil && consumption != nil {
			b, exact := marginWhSample(*gapSegSpeed, consumption.Buckets)
			if b != nil {
				gapSample, gapExact = b, exact
			}
		}
		var gapWhBase *float64
		if gapSample != nil {
			gapWhBase = marginFloatPtr(gapSample.WhPerKm)
		} else if whPerKm != nil {
			gapWhBase = marginFloatPtr(*whPerKm)
		}
		// ⚠️ 爬升/下降取**本段**区间（照抄客户端 v0.34.0 P0 的修正）。
		var gapAscentM, gapDescentM int
		if next != nil && gapKm != nil {
			gapAscentM = marginBetween(ranges, js.KmAlong, next.KmAlong, true)
			gapDescentM = marginBetween(ranges, js.KmAlong, next.KmAlong, false)
		}
		// ⚠️ delta-grade 修正（照抄客户端 v0.34.0 P0-2）：
		// 修正 = c_up_cal × (g_up − 该档典型 g_up) + c_regen_cal × (g_dn − 该档典型 g_dn)。
		var climbTyp *TypicalGrade
		if gapSample != nil && climb != nil {
			for i := range climb.TypicalGrade {
				if climb.TypicalGrade[i].MinKph == gapSample.MinKph {
					climbTyp = &climb.TypicalGrade[i]
					break
				}
			}
		}
		var gapClimbWh *float64
		if gapKm != nil && *gapKm >= routeMarginClimbMinGapKm && climb != nil &&
			climb.Basis == routeMarginClimbBasis && climbTyp != nil {
			gradeUp := float64(gapAscentM) / *gapKm
			gradeDown := float64(gapDescentM) / *gapKm
			gapClimbWh = marginFloatPtr(
				climb.CUpCalibNet*(gradeUp-climbTyp.GradeUpTyp) +
					climb.CRegenCalibNet*(gradeDown-climbTyp.GradeDownTyp))
		}
		var gapWh *float64
		if gapWhBase != nil {
			v := *gapWhBase
			if gapClimbWh != nil {
				v += *gapClimbWh
			}
			gapWh = &v
		}
		gapSpeedForDisplay := planSpeed
		if gapSegSpeed != nil {
			gapSpeedForDisplay = *gapSegSpeed
		}
		// 这一跳要消耗多少个电量点。
		var needPct *int
		if gapKm != nil && gapWh != nil && capacityKwh > 0 {
			needKwh := *gapKm * *gapWh / 1000.0
			needPct = marginIntPtr(int(math.Round(needKwh / capacityKwh * 100)))
		}
		// 到下一站还剩多少电（照抄客户端 v0.34.0 P0-3）。
		var socAtNext *int
		if needPct != nil && js.Charge.EndSoc != nil {
			v := *js.Charge.EndSoc - *needPct
			if v < -100 {
				v = -100
			}
			if v > 100 {
				v = 100
			}
			socAtNext = &v
		}
		// 处方：留 20% 保底，本站至少要充到多少（>100 = 充满也守不住保底）。
		var requiredHere *int
		if needPct != nil {
			requiredHere = marginIntPtr(*needPct + routeMarginReserveSoc)
		}
		// ⚠️ 下一站的**真实**到站电量（照抄客户端 v0.36.0 C1：到站电量，不是插枪电量）。
		var nextRealSoc *int
		if next != nil {
			if next.ArrivalSoc != nil {
				nextRealSoc = marginIntPtr(*next.ArrivalSoc)
			} else if next.SocBefore != nil {
				nextRealSoc = marginIntPtr(*next.SocBefore)
			}
		}
		var nextName *string
		if next != nil {
			nextName = marginStringPtr(next.Charge.Name)
		}
		// 到终点那一段（照抄客户端 v0.36.0 C4 / v0.38.0 P0-1：**只给最后一站**）。
		endGapKm := runDistance - js.KmAlong
		var endNeedPct *int
		if endGapKm > routeMarginMinGapKm && whPerKm != nil && capacityKwh > 0 {
			endNeedPct = marginIntPtr(int(math.Round((endGapKm * *whPerKm / 1000.0) / capacityKwh * 100)))
		}
		var estSocAtEnd *int
		if endNeedPct != nil {
			base := 100
			if js.SocAfter != nil {
				base = *js.SocAfter
			} else if js.Charge.EndSoc != nil {
				base = *js.Charge.EndSoc
			}
			estSocAtEnd = marginIntPtr(base - *endNeedPct)
		}
		// ⚠️ 插枪前 / 充完后：合并后的值优先，没有才用这场充电自己的（照抄客户端 v0.28.0）。
		socBefore := js.SocBefore
		if socBefore == nil {
			socBefore = js.Charge.StartSoc
		}
		socAfter := js.SocAfter
		if socAfter == nil {
			socAfter = js.Charge.EndSoc
		}
		row := RouteMarginStop{
			ChargeID:            js.ChargeID,
			Name:                js.Charge.Name,
			KmAlong:             js.KmAlong,
			IsEndpoint:          js.IsEndpoint,
			SocBefore:           socBefore,
			SocAfter:            socAfter,
			ArrivalSoc:          js.ArrivalSoc,
			GapToNextKm:         gapKm,
			SocAtNextStop:       socAtNext,
			NextStopRealSoc:     nextRealSoc,
			GapNeedPct:          needPct,
			RequiredSocHere:     requiredHere,
			NextStopName:        nextName,
			GapAscentM:          marginIntPtr(gapAscentM),
			GapDescentM:         marginIntPtr(gapDescentM),
			GapClimbWhPerKm:     gapClimbWh,
			GapSpeedBorrowed:    gapSample != nil && !gapExact,
			AssumedSpeedKph:     marginFloatPtr(gapSpeedForDisplay),
			AssumedWhPerKm:      gapWhBase,
			AssumedWhPerKmTotal: gapWh,
			GapErrorPoints:      marginSocErrorPoints(marginFloatValue(gapKm), capacityKwh, consumption),
		}
		if gapSample != nil {
			row.GapBucketLabel = marginStringPtr(marginBucketLabel(gapSample))
		}
		if gapKm != nil && gapClimbWh != nil {
			row.GapClimbKwh = marginFloatPtr(*gapClimbWh * *gapKm / 1000.0)
			if capacityKwh > 0 {
				row.GapClimbPct = marginFloatPtr(*gapClimbWh * *gapKm / 1000.0 / capacityKwh * 100.0)
			}
		}
		if idx == lastRouteStopIdx {
			if endGapKm > routeMarginMinGapKm {
				row.DistanceToEndKm = marginFloatPtr(endGapKm)
			}
			row.EstSocAtEnd = estSocAtEnd
		}
		rows = append(rows, row)
	}

	// ⚠️ 终点处的充电不算「沿途站点」（照抄客户端 v0.14.0）。
	routeRows := make([]RouteMarginStop, 0, len(rows))
	endpointRows := make([]RouteMarginStop, 0, 2)
	for i, r := range rows {
		if template.Stops[i].IsEndpoint {
			endpointRows = append(endpointRows, r)
		} else {
			routeRows = append(routeRows, r)
		}
	}

	// ⚠️ **合成的「起点行」**（起点 → 第一个站）—— 照抄客户端 v0.43.0。
	// 为什么必须建：出发 → 第一个站那一跳**完全没有模型**，
	// 而实测那条线出发 89%、第一个站到站只剩 6%（低于保底）⇒ 全程最险的一段之一。
	var startRow *RouteMarginStop
	if len(routeRows) > 0 {
		first := routeRows[0]
		km := first.KmAlong
		if km > routeMarginMinGapKm {
			startSoc := template.LastStartSoc
			if startSoc == nil {
				startSoc = template.StartSoc
			}
			if startSoc != nil {
				legSegs := make([]RoutePlanLeg, 0, 4)
				for _, leg := range template.Legs {
					s := leg.StartKm - journeyStartKm
					e := leg.EndKm - journeyStartKm
					if e > 0.0 && s < km {
						legSegs = append(legSegs, leg)
					}
				}
				legMinutes := 0
				legDist := 0.0
				for _, leg := range legSegs {
					if leg.DurationMin != nil {
						legMinutes += *leg.DurationMin
					}
					legDist += leg.DistanceKm
				}
				var segSpeed *float64
				if len(legSegs) > 0 && legMinutes > 0 {
					segSpeed = marginFloatPtr(legDist / (float64(legMinutes) / 60.0))
				}
				var sample *ConsumptionModelBucket
				exact := false
				if segSpeed != nil && consumption != nil {
					b, ex := marginWhSample(*segSpeed, consumption.Buckets)
					if b != nil {
						sample, exact = b, ex
					}
				}
				var whBase *float64
				if sample != nil {
					whBase = marginFloatPtr(sample.WhPerKm)
				} else if whPerKm != nil {
					whBase = marginFloatPtr(*whPerKm)
				}
				ascM := marginBetween(ranges, 0.0, km, true)
				descM := marginBetween(ranges, 0.0, km, false)
				var typ *TypicalGrade
				if sample != nil && climb != nil {
					for i := range climb.TypicalGrade {
						if climb.TypicalGrade[i].MinKph == sample.MinKph {
							typ = &climb.TypicalGrade[i]
							break
						}
					}
				}
				var climbWh *float64
				if km >= routeMarginClimbMinGapKm && climb != nil &&
					climb.Basis == routeMarginClimbBasis && typ != nil {
					gUp := float64(ascM) / km
					gDn := float64(descM) / km
					climbWh = marginFloatPtr(climb.CUpCalibNet*(gUp-typ.GradeUpTyp) +
						climb.CRegenCalibNet*(gDn-typ.GradeDownTyp))
				}
				var whTotal *float64
				if whBase != nil {
					v := *whBase
					if climbWh != nil {
						v += *climbWh
					}
					whTotal = &v
				}
				var need *int
				if whTotal != nil && capacityKwh > 0 {
					need = marginIntPtr(int(math.Round((km * *whTotal / 1000.0) / capacityKwh * 100)))
				}
				speed := planSpeed
				if segSpeed != nil {
					speed = *segSpeed
				}
				row := RouteMarginStop{
					Name:                template.FromLabel,
					KmAlong:             0.0,
					IsStart:             true,
					SocBefore:           startSoc,
					ArrivalSoc:          startSoc,
					GapToNextKm:         marginFloatPtr(km),
					GapNeedPct:          need,
					NextStopName:        marginStringPtr(first.Name),
					GapAscentM:          marginIntPtr(ascM),
					GapDescentM:         marginIntPtr(descM),
					GapClimbWhPerKm:     climbWh,
					AssumedSpeedKph:     marginFloatPtr(speed),
					AssumedWhPerKm:      whBase,
					AssumedWhPerKmTotal: whTotal,
					GapErrorPoints:      marginSocErrorPoints(km, capacityKwh, consumption),
				}
				if need != nil {
					row.RequiredSocHere = marginIntPtr(*need + routeMarginReserveSoc)
					v := *startSoc - *need
					if v < -100 {
						v = -100
					}
					if v > 100 {
						v = 100
					}
					row.SocAtNextStop = marginIntPtr(v)
				}
				if first.ArrivalSoc != nil {
					row.NextStopRealSoc = marginIntPtr(*first.ArrivalSoc)
				} else if first.SocBefore != nil {
					row.NextStopRealSoc = marginIntPtr(*first.SocBefore)
				}
				if sample != nil {
					row.GapBucketLabel = marginStringPtr(marginBucketLabel(sample))
				}
				row.GapSpeedBorrowed = sample != nil && !exact
				if climbWh != nil {
					row.GapClimbKwh = marginFloatPtr(*climbWh * km / 1000.0)
					if capacityKwh > 0 {
						row.GapClimbPct = marginFloatPtr(*climbWh * km / 1000.0 / capacityKwh * 100.0)
					}
				}
				startRow = &row
			}
		}
	}

	stops := make([]RouteMarginStop, 0, len(routeRows)+1)
	if startRow != nil {
		stops = append(stops, *startRow)
	}
	stops = append(stops, routeRows...)
	return &RouteMarginPayload{
		TemplateKey:            template.Key,
		Stops:                  stops,
		EndpointStops:          endpointRows,
		ClimbCorrectionApplied: climb != nil,
		ReserveSoc:             routeMarginReserveSoc,
		Note:                   routeMarginNote,
	}
}

// climbModelCached 取爬升模型（**按车缓存 30 分钟** —— 与 /climb_model 端点同一份缓存）。
//
// ⚠️ 首算要扫 162 万个 positions（约 5 秒）⇒ 客户端不能等 ⇒ 必须走缓存。
func climbModelCached(carID int, minKm float64) (*ClimbModel, error) {
	key := fmt.Sprintf("%d:%.0f", carID, minKm)
	climbCacheMu.Lock()
	hit, ok := climbCache[key]
	climbCacheMu.Unlock()
	if ok && time.Since(hit.at) < climbCacheTTL {
		m := hit.model
		return &m, nil
	}
	model, err := computeClimbModel(carID, minKm)
	if err != nil {
		return nil, err
	}
	climbCacheMu.Lock()
	climbCache[key] = climbCacheEntry{at: time.Now(), model: model}
	climbCacheMu.Unlock()
	return &model, nil
}

// handleRouteMargin 余量表（**服务端算的结论**）。
//
// GET /api/v1/cars/{id}/route_margin?template=<key>
//
// ⚠️ **v0.188.0 新增**：原来这套判定在**客户端**（buildRoutePlan）
// ⇒ 飞书里问不出来（mission §2.1；清单里 route_plan_margin 那条 gap）⇒ 现在服务端出结论。
func handleRouteMargin(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	key := strings.TrimSpace(r.URL.Query().Get("template"))
	if key == "" {
		writeErr(w, http.StatusBadRequest, "template 不能为空")
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
	templates := buildRouteTemplates(drives, charges)
	var tpl *RoutePlanTemplate
	for i := range templates {
		if templates[i].Key == key {
			tpl = &templates[i]
			break
		}
	}
	if tpl == nil {
		writeErr(w, http.StatusNotFound, "没有这条线路（template="+key+"）")
		return
	}
	// 能耗模型（标定法；与 /consumption_model 端点**同一份**）。
	rows, err := queryConsumptionRows(ctx, id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	consumption := buildConsumptionModel(rows, serverLocation())
	// 容量（与 /station_stats 端点**同一份**）。
	capacityKwh, ok := stationCapacity(charges)
	if !ok {
		// ⚠️ 算不出容量 ⇒ 不给一份半截的余量表（宁可不显示，也不给假数）。
		writeErr(w, http.StatusBadRequest, "算不出电池容量，给不出余量表")
		return
	}
	// 爬升模型（同一份缓存）。拿不到 ⇒ **不给**爬升修正（宁可少算一个修正，也不能混口径）。
	climb, err := climbModelCached(id, 20)
	if err != nil {
		climb = nil
	}
	out := buildRouteMargin(*tpl, &consumption, capacityKwh, climb)
	if out == nil {
		writeErr(w, http.StatusBadRequest, "这条线路给不出余量表")
		return
	}
	out.CarID = id
	writeJSON(w, out)
}
