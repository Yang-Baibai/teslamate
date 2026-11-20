package com.teslamate.app.ui

import com.teslamate.app.Charge
import com.teslamate.app.ConsumptionModel
import com.teslamate.app.Drive
import com.teslamate.app.SocProfilePoint
import com.teslamate.app.SpeedBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ⚠️ **v0.181.0：余量表的输入改成**显式夹具**。**
 *
 * ## 为什么改了夹具
 *
 * 「哪些算一次长途出门」「沿途有哪些充电站」搬到服务端了（api/route_plans.go）
 * ⇒ 客户端不再有 `buildJourneys` / `buildRouteTemplates`。
 * ⇒ 这里原来靠 `buildRouteTemplates(drives, charges)` 造出来的模板，
 *   改成**显式给出段与站** —— 归属判据的测试**已经移到服务端 Go 单测**
 * （api/route_plans_test.go：长途门槛 / 断链 / 沿途站归属 / 合并 / 到站电量）。
 *
 * ## 什么还在这里测
 *
 * **余量表本身**（`buildRoutePlan`）：站间距离、到站电量口径、处方数字、
 * 终点前那一段、剖面插值标记 —— 这些**仍在客户端**（下一轮才搬）。
 */
class RoutePlanBuildTest {

    private val t0 = Instant.parse("2026-09-01T08:00:00Z")

    private fun drive(
        id: Int,
        fromKm: Double,
        toKm: Double,
        fromSoc: Int,
        toSoc: Int,
        startOffsetMin: Long,
        durMin: Int,
        avgKph: Int = 50,
    ) = Drive(
        id = id,
        start_date = t0.plusSeconds(startOffsetMin * 60),
        end_date = t0.plusSeconds((startOffsetMin + durMin) * 60),
        duration_min = durMin,
        distance_km = toKm - fromKm,
        start_soc = fromSoc,
        end_soc = toSoc,
        odometer_start = fromKm,
        odometer_end = toKm,
        avg_speed_kph = avgKph,
        wh_per_km = 180.0,
        start_place_key = "a1",
        end_place_key = "a2",
    )

    private fun charge(
        id: Int,
        odoKm: Double,
        fromSoc: Int,
        toSoc: Int,
        startOffsetMin: Long,
        placeKey: String,
    ) = Charge(
        id = id,
        start_date = t0.plusSeconds(startOffsetMin * 60),
        end_date = t0.plusSeconds((startOffsetMin + 30) * 60),
        duration_min = 30,
        start_soc = fromSoc,
        end_soc = toSoc,
        start_odometer_km = odoKm,
        place_key = placeKey,
        poi_name = "站$id",
        energy_added_kwh = 20.0,
    )

    /** 造一条 400 公里的旅程：3 趟行程 + 2 个充电站。 */
    private fun scenario(): Pair<List<Drive>, List<Charge>> {
        val drives = listOf(
            drive(1, 100000.0, 100100.0, 100, 70, 0, 60),
            drive(2, 100100.0, 100300.0, 70, 40, 70, 120),
            drive(3, 100300.0, 100400.0, 40, 80, 200, 60),
        )
        val charges = listOf(
            charge(11, 100100.0, 70, 95, 65, "a11"),
            charge(12, 100300.0, 40, 90, 195, "a12"),
        )
        return drives to charges
    }

    /**
     * ⚠️ **显式**造出这条线路的模板。
     *
     * 段来自行程；站**显式**给出（里程、插枪电量、到站电量）——
     * 「这一场充电算不算这条线的沿途站」是**服务端**的判定（api/route_plans.go）。
     */
    private fun templateOf(drives: List<Drive>, stops: List<JourneyStop>): RouteTemplate {
        val legs = drives.map { d ->
            JourneyLeg(
                driveId = d.id,
                startKm = d.odometer_start!!,
                endKm = d.odometer_end!!,
                distanceKm = d.distance_km ?: (d.odometer_end!! - d.odometer_start!!),
                startSoc = d.start_soc,
                endSoc = d.end_soc,
                startPlace = d.start_place,
                endPlace = d.end_place,
                startDate = d.start_date,
                endDate = d.end_date ?: d.start_date,
                durationMin = d.duration_min,
                startPlaceKey = d.start_place_key,
                endPlaceKey = d.end_place_key,
                ascentM = d.ascent_m,
                descentM = d.descent_m,
            )
        }
        val startKm = legs.first().startKm
        val endKm = legs.last().endKm
        // ⚠️ v0.30.0：真实均速 = 只统计有里程且有时间的段。
        val withTime = legs.filter { (it.durationMin ?: 0) > 0 && it.distanceKm >= 2.0 }
        val avg = if (withTime.isEmpty()) {
            null
        } else {
            val km = withTime.sumOf { it.distanceKm }
            val min = withTime.sumOf { it.durationMin!! }
            if (km <= 0.1 || min <= 0) null else km / (min / 60.0)
        }
        return RouteTemplate(
            key = "journey-${legs.first().driveId}-${legs.last().driveId}",
            fromLabel = "起点",
            toLabel = "终点",
            distanceKm = endKm - startKm,
            startSoc = legs.first().startSoc,
            endSoc = legs.last().endSoc,
            legCount = legs.count { it.distanceKm >= 2.0 },
            totalLegCount = legs.size,
            stopCount = stops.count { !it.isEndpoint },
            lastDrivenAt = legs.last().startDate,
            startOdoKm = startKm,
            lastDistanceKm = endKm - startKm,
            avgSpeedKph = avg,
            isRoundTrip = false,
            lastStartSoc = legs.first().startSoc,
            lastEndSoc = legs.last().endSoc,
            lastDriveId = legs.last().driveId,
            legIds = legs.map { it.driveId },
            legs = legs,
            stops = stops,
        )
    }

    /** 场景的两站：**显式**给出里程与到站电量（那一趟行程的 end_soc）。 */
    private fun scenarioTemplate(): Pair<RouteTemplate, List<Charge>> {
        val (drives, charges) = scenario()
        val stops = listOf(
            JourneyStop(charges[0], 100.0, false, 70, 95, 70),
            JourneyStop(charges[1], 300.0, false, 40, 90, 40),
        )
        return templateOf(drives, stops) to charges
    }

    private fun model() = ConsumptionModel(
        buckets = listOf(SpeedBucket(0, 60, 180.0, 5)),
        cruiseKph = 50.0,
    )

    @Test
    fun `处方数字自洽`() {
        val (tpl, charges) = scenarioTemplate()
        val plan = buildRoutePlan(
            template = tpl,
            charges = charges,
            stationStats = emptyMap(),
            consumption = model(),
            batteryCapacityKwh = 65.0,
            planSpeedKph = 50.0,
        )
        assertNotNull("plan 不应为 null", plan)
        val p = plan!!
        // ⚠️ **v0.43.0（第 25 轮车主复审 P0）：`stops` 的第 0 位是**合成的起点行**。**
        assertEquals(3, p.stops.size)
        assertTrue("第 0 行必须是起点行", p.stops[0].isStart)
        assertEquals("起点行里程 = 0", 0.0, p.stops[0].kmAlongRoute, 0.001)
        // 起点行：0 → 100 公里，180 Wh/km，容量 65 度
        //   → 100×180/1000 = 18 度；18 ÷ 65 × 100 = 27.7 → **28 个点**
        assertEquals(28, p.stops[0].gapNeedPct)
        assertEquals(48, p.stops[0].requiredSocHere)
        assertEquals(100.0, p.stops[0].gapToNextKm!!, 0.001)
        // 起点行的真值 = 第一个站的到站电量（70）
        assertEquals(70, p.stops[0].nextStopRealSoc)
        // 起点行估算：出发 100% − 28 个点 = 72%
        assertEquals(72, p.stops[0].socAtNextStop)

        val s0 = p.stops[1]
        // 站间 200 公里，180 Wh/km，容量 65 度 → 需要 200×180/1000/65 = 55.4 → 55 个点
        assertEquals(55, s0.gapNeedPct)
        // 留 20% 保底 → 至少充到 75%
        assertEquals(75, s0.requiredSocHere)
        // 充到 95% 出发 → 到站剩 95 − 55 = 40%
        assertEquals(40, s0.socAtNextStop)
        assertEquals(200.0, s0.gapToNextKm!!, 0.001)
        // 真值（下一站的到站电量）= 第 2 趟行程的结束电量 40
        assertEquals(40, s0.nextStopRealSoc)
    }

    @Test
    fun `终点前那一段也给出余量`() {
        val (tpl, charges) = scenarioTemplate()
        val plan = buildRoutePlan(
            template = tpl,
            charges = charges,
            stationStats = emptyMap(),
            consumption = model(),
            batteryCapacityKwh = 65.0,
            planSpeedKph = 50.0,
        )!!
        val last = plan.stops.last()
        // 最后一站在 300 公里，全程 400 公里 → 还有 100 公里
        assertEquals(100.0, last.distanceToEndKm!!, 1.0)
        // 100 公里 × 180 Wh/km ÷ 1000 = 18 kWh；18 ÷ 65 × 100 = 27.7 → 28 个点
        // 最后一站充到 90% → 到终点剩 90 − 28 = 62%
        assertEquals(62, last.estSocAtEnd)
    }

    @Test
    fun `终点前那一段只给最后一站`() {
        // ⚠️ v0.38.0（第 23 轮车主复审 P0-1）：原来 `distanceToEndKm` 对**每一站**都非空
        val (tpl, charges) = scenarioTemplate()
        val plan = buildRoutePlan(
            template = tpl,
            charges = charges,
            stationStats = emptyMap(),
            consumption = model(),
            batteryCapacityKwh = 65.0,
            planSpeedKph = 50.0,
        )!!
        assertEquals(3, plan.stops.size)
        assertNull("第一站不该有「距终点」", plan.stops[1].distanceToEndKm)
        assertNull("起点行也不该有「距终点」", plan.stops[0].distanceToEndKm)
        val last = plan.stops.last()
        assertEquals(100.0, last.distanceToEndKm!!, 1.0)
        assertEquals(62, last.estSocAtEnd)
    }

    @Test
    fun `详情行的到站电量用到站口径而不是插枪口径`() {
        // ⚠️ v0.38.0（P0-2）：插枪电量（socBefore）与到站电量（arrivalSoc）会差很多。
        val (tpl, charges) = scenarioTemplate()
        val plan = buildRoutePlan(
            template = tpl,
            charges = charges,
            stationStats = emptyMap(),
            consumption = model(),
            batteryCapacityKwh = 65.0,
            planSpeedKph = 50.0,
        )!!
        // 第 1 站的 arrivalSoc = 第 1 趟行程的 end_soc = 70
        assertEquals(100, plan.stops[0].arrivalSoc)
        assertEquals(70, plan.stops[1].arrivalSoc)
        // 第 2 站 = 第 2 趟的 end_soc = 40
        assertEquals(40, plan.stops[2].arrivalSoc)
        // 决策行的真值与之同源
        assertEquals(plan.stops[2].arrivalSoc, plan.stops[1].nextStopRealSoc)
    }

    @Test
    fun `没有站也没有能耗模型时不编数`() {
        // 两趟行程（journey 至少要 2 段）但**没有任何充电** → 没有站
        val drives = listOf(
            drive(1, 100000.0, 100150.0, 100, 70, 0, 90),
            drive(2, 100150.0, 100300.0, 70, 40, 100, 90),
        )
        val tpl = templateOf(drives, emptyList())
        val plan = buildRoutePlan(
            template = tpl,
            charges = emptyList(),
            stationStats = emptyMap(),
            consumption = null,
            batteryCapacityKwh = null,
            planSpeedKph = 50.0,
        )
        // ⚠️ 没有能耗模型**只影响每站的到站估算**，余量剖面仍然建得出来
        assertNotNull("plan 仍应建得出来（剖面用真实起止电量）", plan)
        val p = plan!!
        assertTrue("不应有任何站", p.stops.isEmpty())
        assertNull(p.longestHop)
        assertTrue(
            "没有站时决策句应说「能一次开到」: ${p.verdict()}",
            p.verdict().contains("能一次开到"),
        )
        assertTrue(
            "没有站时决策句不得出现站间数据: ${p.verdict()}",
            !p.verdict().contains("站间"),
        )
    }

    // ── ⚠️ v0.53.0（第 29 轮崩溃与健壮性审计 P2-5）：剖面是插值的要说出来 ──

    @Test
    fun `拿不到真实剖面时标记为插值`() {
        val (tpl, charges) = scenarioTemplate()
        val plan = buildRoutePlan(
            template = tpl,
            charges = charges,
            stationStats = emptyMap(),
            consumption = model(),
            batteryCapacityKwh = 65.0,
            planSpeedKph = 50.0,
            socProfile = null,
        )!!
        assertTrue("拿不到真实剖面时必须标记为插值", plan.socProfileInterpolated)
    }

    @Test
    fun `有真实剖面时不标记为插值`() {
        val (tpl, charges) = scenarioTemplate()
        val plan = buildRoutePlan(
            template = tpl,
            charges = charges,
            stationStats = emptyMap(),
            consumption = model(),
            batteryCapacityKwh = 65.0,
            planSpeedKph = 50.0,
            // 真实剖面至少要 3 个点（`socProfile?.takeIf { it.size >= 3 }`）
            socProfile = listOf(
                SocProfilePoint(0.0, 100.0),
                SocProfilePoint(100.0, 70.0),
                SocProfilePoint(200.0, 40.0),
            ),
        )!!
        assertTrue("有真实剖面时不该标记为插值", !plan.socProfileInterpolated)
    }

    @Test
    fun `真实剖面少于三个点时仍按插值处理`() {
        // ⚠️ `real = socProfile?.takeIf { it.size >= 3 }` ——
        // 少于 3 个点时**照样走插值**，所以标志也必须是 true。
        val (tpl, charges) = scenarioTemplate()
        val plan = buildRoutePlan(
            template = tpl,
            charges = charges,
            stationStats = emptyMap(),
            consumption = model(),
            batteryCapacityKwh = 65.0,
            planSpeedKph = 50.0,
            socProfile = listOf(SocProfilePoint(0.0, 100.0)),
        )!!
        assertTrue("真实剖面不足 3 点时按插值处理", plan.socProfileInterpolated)
    }
}
