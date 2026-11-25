package com.teslamate.app.ui

import com.teslamate.app.Charge
import kotlin.math.roundToInt

/**
 * ⚠️ **v0.188.0**：把**服务端算的**余量结论映射成客户端的 [RoutePlan]（**只渲染，不判定**）。
 *
 * ## 为什么不再客户端算
 * 「到下一站还剩多少电 / 够不够到 20% 保底」原来在客户端 buildRoutePlan 里算
 * ⇒ **飞书里问不出来**（清单里 route_plan_margin 那条 gap）⇒ 现在服务端算，App 与飞书**同一个数**。
 *
 * ## 这里只做三件事
 * ① 把服务端的每一站映射成 [RouteStop]（站画像的显示字段仍取**本地**那份按站画像）；
 * ② 剖面（余量曲线）仍由客户端画（**展示**，不是判定）；
 * ③ 前提行那几个数（爬升有没有修正 / 低温样本 / 误差带）从服务端给的结论取。
 */
fun routePlanFromServer(
    margin: com.teslamate.app.RouteMarginDto,
    template: RouteTemplate,
    charges: List<Charge>,
    stationStats: Map<String, StationStat>,
    socProfile: List<com.teslamate.app.SocProfilePoint>?,
    consumption: com.teslamate.app.ConsumptionModel?,
    batteryCapacityKwh: Double?,
): RoutePlan? {
    val byId = charges.associateBy { it.id }
    fun toStop(dto: com.teslamate.app.RouteMarginStopDto): RouteStop {
        val c = byId[dto.charge_id]
        val stat = c?.place_key?.let { stationStats[it] }
        return RouteStop(
            name = dto.name,
            kmAlongRoute = dto.km_along,
            socBefore = dto.soc_before,
            socAfter = dto.soc_after,
            typicalPowerKw = stat?.typicalPowerDcKw ?: stat?.typicalPowerKw,
            minutesTo80 = stat?.typicalMinutesTo80,
            // ⚠️ 「从本次到站电量充到 socAfter 要多久」是**展示用的折算**（照抄原来的口径）。
            minutesToTarget = stat?.let { st ->
                val kw = st.typicalPowerDcKw ?: st.typicalPowerKw
                val from = c?.start_soc
                val to = c?.end_soc
                if (from == null || to == null) null
                else {
                    val measured = if (to == 80) st.measuredMedianNear(from) else null
                    measured ?: st.minutesTo(from, to, kw)
                }
            },
            sessions = stat?.sessions ?: 1,
            gapToNextKm = dto.gap_to_next_km,
            socAtNextStop = dto.soc_at_next_stop,
            nextStopRealSoc = dto.next_stop_real_soc,
            gapNeedPct = dto.gap_need_pct,
            requiredSocHere = dto.required_soc_here,
            isStart = dto.is_start,
            gapErrorPoints = dto.gap_error_points,
            nextStopName = dto.next_stop_name,
            arrivalSoc = dto.arrival_soc,
            distanceToEndKm = dto.distance_to_end_km,
            estSocAtEnd = dto.est_soc_at_end,
            gapAscentM = dto.gap_ascent_m,
            gapDescentM = dto.gap_descent_m,
            gapClimbWhPerKm = dto.gap_climb_wh_per_km,
            gapBucketLabel = dto.gap_bucket_label,
            gapSpeedBorrowed = dto.gap_speed_borrowed,
            assumedSpeedKph = dto.assumed_speed_kph,
            assumedWhPerKm = dto.assumed_wh_per_km,
            assumedWhPerKmTotal = dto.assumed_wh_per_km_total,
            gapClimbKwh = dto.gap_climb_kwh,
            gapClimbPct = dto.gap_climb_pct,
        )
    }

    val stops = margin.stops.map { toStop(it) }
    val endpointStops = margin.endpoint_stops.map { toStop(it) }
    // ⚠️ 剖面用**沿途站**（不含合成的起点行）—— 起点在剖面里单独加过。
    val routeStops = stops.filter { !it.isStart }
    val profile = ArrayList<RouteSocPoint>(routeStops.size * 2 + 2)
    val real = socProfile?.takeIf { it.size >= 3 }
    if (real != null) {
        real.forEach { p -> profile += RouteSocPoint(p.km, p.soc.roundToInt()) }
        routeStops.forEach { s ->
            s.socAfter?.let { profile += RouteSocPoint(s.kmAlongRoute, it, isCharge = true) }
        }
        profile.sortBy { it.km }
    } else {
        (template.lastStartSoc ?: template.startSoc)?.let { profile += RouteSocPoint(0.0, it) }
        for (s in routeStops) {
            (s.arrivalSoc ?: s.socBefore)?.let { profile += RouteSocPoint(s.kmAlongRoute, it) }
            s.socAfter?.let { profile += RouteSocPoint(s.kmAlongRoute, it, isCharge = true) }
        }
        (template.lastEndSoc ?: template.endSoc)?.let {
            profile += RouteSocPoint(template.lastDistanceKm ?: template.distanceKm, it)
        }
    }
    if (profile.size < 2) return null
    val maxGapKm = stops.maxOfOrNull { it.gapToNextKm ?: 0.0 } ?: 0.0
    return RoutePlan(
        template = template,
        profile = profile,
        stops = stops,
        endpointStops = endpointStops,
        climbAscentM = template.legs.sumOf { it.ascentM ?: 0 },
        climbDescentM = template.legs.sumOf { it.descentM ?: 0 },
        socProfileInterpolated = real == null,
        climbCorrectionApplied = margin.climb_correction_applied,
        lowTempSamples = consumption?.lowTempSamples,
        modelErrorPoints = consumption?.socErrorPoints(maxGapKm, batteryCapacityKwh),
    )
}