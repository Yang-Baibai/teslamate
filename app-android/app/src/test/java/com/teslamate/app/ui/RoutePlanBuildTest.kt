package com.teslamate.app.ui

import com.teslamate.app.Charge
import com.teslamate.app.ConsumptionModel
import com.teslamate.app.Drive
import com.teslamate.app.SpeedBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ⚠️ **v0.37.0：端到端测 [buildJourneys] + [buildRoutePlan]。**
 *
 * ## 为什么盯这条链
 *
 * 这条链上出过**两个 P0**：
 *
 * ① v0.36.0：「上次实测到站剩 Y%」在 6/35 个站取错场次
 *    （用插枪电量而不是到站电量；合并时取的是**后**一场）。
 * ② v0.37.0：折叠态的 `longestHop` 仍在用 `nxt.socBefore` ——
 *    v0.36.0 修了逐站行却漏了折叠态。
 *
 * 两处都是**「口径对了、但取数那一层还错」**，
 * 只测最终文案抓不到 —— 必须测**整条链的输入输出**。
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

    @Test
    fun `journey 把两站都归到同一次出门`() {
        val (drives, charges) = scenario()
        val journeys = buildJourneys(drives, charges, 200.0)
        assertEquals(1, journeys.size)
        val j = journeys[0]
        assertEquals(3, j.legs.size)
        assertEquals(2, j.stops.size)
        assertEquals(100.0, j.stops[0].kmAlong, 0.001)
        assertEquals(300.0, j.stops[1].kmAlong, 0.001)
    }

    @Test
    fun `到站真值取那一趟行程的结束电量`() {
        val (drives, charges) = scenario()
        val journeys = buildJourneys(drives, charges, 200.0)
        // 第 2 趟行程结束时的电量是 40 —— 那才是「开到充电点时」的电量
        assertEquals(40, journeys[0].stops[1].arrivalSoc)
    }

    @Test
    fun `同一处被拆成两场充电时合并并取最早那一场的插枪电量`() {
        // 模拟「一次插枪被 TeslaMate 切成两个 process」：
        // 同一里程、同一地点，cp#16(8→27) 与 cp#17(27→86)。
        // ⚠️ `sqlCharges` 按 start_date DESC 返回（新的在前），
        //    客户端按里程**稳定排序** ⇒ 后一场排在前。
        val drives = listOf(
            drive(1, 100000.0, 100100.0, 100, 8, 0, 60),
            drive(2, 100100.0, 100300.0, 8, 30, 70, 120),
        )
        val charges = listOf(
            // ⚠️ 顺序按 API 的返回顺序（新的在前）
            charge(17, 100100.0, 27, 86, 65, "a11"),
            charge(16, 100100.0, 8, 27, 64, "a11"),
        )
        val journeys = buildJourneys(drives, charges, 200.0)
        assertEquals(1, journeys.size)
        val stops = journeys[0].stops
        // 同一处只应有**一个**站
        assertEquals(1, stops.size)
        // 到站电量 = **最早**那一场的插枪电量 = 8（不是 27）
        assertEquals(8, stops[0].socBefore)
        // 充完 = 充得最多的那一场的 end_soc = 86
        assertEquals(86, stops[0].socAfter)
    }

    @Test
    fun `处方数字自洽`() {
        val (drives, charges) = scenario()
        val templates = buildRouteTemplates(drives, charges, 200.0)
        assertEquals(1, templates.size)
        val tpl = templates[0]
        val model = ConsumptionModel(
            buckets = listOf(SpeedBucket(0, 60, 180.0, 5)),
            cruiseKph = 50.0,
        )
        val plan = buildRoutePlan(
            template = tpl,
            charges = charges,
            stationStats = emptyMap(),
            consumption = model,
            batteryCapacityKwh = 65.0,
            planSpeedKph = 50.0,
        )
        assertNotNull("plan 不应为 null", plan)
        val p = plan!!
        assertEquals(2, p.stops.size)
        val s0 = p.stops[0]
        // 站间 200 公里，180 Wh/km，容量 65 度 → 需要 200×180/1000/65 = 55.4 → 55 个点
        assertEquals(55, s0.gapNeedPct)
        // 留 20% 保底 → 至少充到 75%
        assertEquals(75, s0.requiredSocHere)
        // 充到 95% 出发 → 到站剩 95 − 55 = 40%
        assertEquals(40, s0.socAtNextStop)
        // 站间距离
        assertEquals(200.0, s0.gapToNextKm!!, 0.001)
        // 真值（下一站的到站电量）= 第 2 趟行程的结束电量 40
        assertEquals(40, s0.nextStopRealSoc)
    }

    @Test
    fun `终点前那一段也给出余量`() {
        val (drives, charges) = scenario()
        val tpl = buildRouteTemplates(drives, charges, 200.0)[0]
        val model = ConsumptionModel(
            buckets = listOf(SpeedBucket(0, 60, 180.0, 5)),
            cruiseKph = 50.0,
        )
        val plan = buildRoutePlan(
            template = tpl,
            charges = charges,
            stationStats = emptyMap(),
            consumption = model,
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
        // （它是「每站到终点的距离」），于是 36 个站每个都多渲染一行，
        // 还渲染出「估到终点剩 -1508%」这种物理上不存在的数。
        val (drives, charges) = scenario()
        val tpl = buildRouteTemplates(drives, charges, 200.0)[0]
        val model = ConsumptionModel(
            buckets = listOf(SpeedBucket(0, 60, 180.0, 5)),
            cruiseKph = 50.0,
        )
        val plan = buildRoutePlan(
            template = tpl,
            charges = charges,
            stationStats = emptyMap(),
            consumption = model,
            batteryCapacityKwh = 65.0,
            planSpeedKph = 50.0,
        )!!
        assertEquals(2, plan.stops.size)
        // 只有**最后一个**沿途站才有「距终点」
        assertNull("第一站不该有「距终点」", plan.stops[0].distanceToEndKm)
        val last = plan.stops.last()
        assertEquals(100.0, last.distanceToEndKm!!, 1.0)
        // 100 公里 × 180 Wh/km ÷ 1000 = 18 kWh；18 ÷ 65 × 100 = 27.7 → 28 个点
        // 最后一站充到 90% → 到终点剩 90 − 28 = 62%
        assertEquals(62, last.estSocAtEnd)
    }

    @Test
    fun `详情行的到站电量用到站口径而不是插枪口径`() {
        // ⚠️ v0.38.0（P0-2）：合并充电会话时，插枪电量（socBefore）
        // 与到站电量（arrivalSoc）会差很多 —— 实测 36 站里 12 站差，最多 57 个点。
        // 决策行与详情行必须**同一个口径**，否则同一个站两行打架。
        val (drives, charges) = scenario()
        val tpl = buildRouteTemplates(drives, charges, 200.0)[0]
        val model = ConsumptionModel(
            buckets = listOf(SpeedBucket(0, 60, 180.0, 5)),
            cruiseKph = 50.0,
        )
        val plan = buildRoutePlan(
            template = tpl,
            charges = charges,
            stationStats = emptyMap(),
            consumption = model,
            batteryCapacityKwh = 65.0,
            planSpeedKph = 50.0,
        )!!
        // 第 1 站的 arrivalSoc = 第 1 趟行程的 end_soc = 70
        assertEquals(70, plan.stops[0].arrivalSoc)
        // 第 2 站 = 第 2 趟的 end_soc = 40
        assertEquals(40, plan.stops[1].arrivalSoc)
        // 决策行的真值与之同源
        assertEquals(plan.stops[1].arrivalSoc, plan.stops[0].nextStopRealSoc)
    }

    @Test
    fun `没有站也没有能耗模型时不编数`() {
        // 两趟行程（journey 至少要 2 段）但**没有任何充电** → 没有站
        val drives = listOf(
            drive(1, 100000.0, 100150.0, 100, 70, 0, 90),
            drive(2, 100150.0, 100300.0, 70, 40, 100, 90),
        )
        val journeys = buildJourneys(drives, emptyList(), 200.0)
        assertEquals(1, journeys.size)
        assertTrue(journeys[0].stops.isEmpty())
        val tpl = buildRouteTemplates(drives, emptyList(), 200.0)[0]
        val plan = buildRoutePlan(
            template = tpl,
            charges = emptyList(),
            stationStats = emptyMap(),
            consumption = null,
            batteryCapacityKwh = null,
            planSpeedKph = 50.0,
        )
        // ⚠️ 没有能耗模型**只影响每站的到站估算**，余量剖面仍然建得出来
        // （起点/终点的真实电量是有的）—— 所以 plan 不是 null。
        assertNotNull("plan 仍应建得出来（剖面用真实起止电量）", plan)
        val p = plan!!
        assertTrue("不应有任何站", p.stops.isEmpty())
        // 没有站 → 没有「最长的一跳」，决策句不能编站间数据
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
}
