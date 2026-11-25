package com.teslamate.app.ui

import com.teslamate.app.RouteMarginDto
import com.teslamate.app.RouteMarginStopDto
import com.teslamate.app.SocProfilePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.188.0：客户端只剩「映射」，判定已在服务端。**
 *
 * ## 这里的职责边界
 *
 * 「到下一站还剩多少电 / 够不够到 20% 保底」搬到服务端了（api/route_margin.go）
 * ⇒ **判定**的单测也在服务端（api/route_margin_test.go，承接客户端删掉的
 * RoutePlanBuildTest.kt 的全部断言）。
 *
 * 客户端这一层只剩两件事，**本文件就钉这两件**：
 * ① 服务端给的每一站，**逐字**映射成界面用的 [RouteStop]（不许在客户端改数）；
 * ② 剖面点不够时**不给半截结果**（返回 null ⇒ 整张卡不出现）。
 */
class RouteMarginMappingTest {

    private fun tpl(
        startSoc: Int? = 100,
        endSoc: Int? = 53,
        lastStartSoc: Int? = 100,
        lastEndSoc: Int? = 53,
    ) = RouteTemplate(
        key = "journey-633-662",
        fromLabel = "石化大道西",
        toLabel = "家",
        distanceKm = 432.08,
        startSoc = startSoc,
        endSoc = endSoc,
        legCount = 17,
        stopCount = 2,
        lastDrivenAt = null,
        startOdoKm = 100000.0,
        lastDistanceKm = 432.08,
        avgSpeedKph = 90.0,
        lastStartSoc = lastStartSoc,
        lastEndSoc = lastEndSoc,
    )

    /** 服务端给的「合成起点行」＋一个沿途站（数值取线上实测）。 */
    private fun margin() = RouteMarginDto(
        car_id = 1,
        template_key = "journey-633-662",
        stops = listOf(
            RouteMarginStopDto(
                name = "石化大道西",
                km_along = 0.0,
                is_start = true,
                soc_before = 100,
                arrival_soc = 100,
                gap_to_next_km = 308.208587,
                soc_at_next_stop = 23,
                next_stop_real_soc = 14,
                gap_need_pct = 77,
                required_soc_here = 97,
                gap_error_points = 13,
                next_stop_name = "龙溪街道",
                gap_bucket_label = "0–60",
                assumed_speed_kph = 100.0,
                assumed_wh_per_km = 180.0,
                assumed_wh_per_km_total = 180.0,
            ),
            RouteMarginStopDto(
                charge_id = 12345,
                name = "龙溪街道",
                km_along = 308.208587,
                soc_before = 14,
                soc_after = 84,
                arrival_soc = 14,
            ),
        ),
        endpoint_stops = emptyList(),
        climb_correction_applied = true,
        reserve_soc = 20,
        note = "口径说明",
    )

    /**
     * ⚠️ **服务端的数，客户端不许改。**
     */
    @Test
    fun `逐字映射服务端的每一站`() {
        val plan = routePlanFromServer(
            margin = margin(),
            template = tpl(),
            charges = emptyList(),
            stationStats = emptyMap(),
            socProfile = null,
            consumption = null,
            batteryCapacityKwh = null,
        )
        assertNotNull("余量表应建得出来", plan)
        val p = plan!!
        assertEquals("应有 2 行（起点行 + 1 个沿途站）", 2, p.stops.size)

        val start = p.stops[0]
        assertTrue("第 0 行是合成的起点行", start.isStart)
        assertEquals("石化大道西", start.name)
        assertEquals(0.0, start.kmAlongRoute, 0.001)
        assertEquals(308.208587, start.gapToNextKm!!, 0.001)
        assertEquals(77, start.gapNeedPct)
        assertEquals(97, start.requiredSocHere)
        assertEquals(23, start.socAtNextStop)
        assertEquals(14, start.nextStopRealSoc)
        assertEquals("龙溪街道", start.nextStopName)
        assertEquals("0–60", start.gapBucketLabel)
        assertEquals(13, start.gapErrorPoints)

        val stop = p.stops[1]
        assertEquals("龙溪街道", stop.name)
        assertEquals(308.208587, stop.kmAlongRoute, 0.001)
        assertEquals(14, stop.socBefore)
        assertEquals(84, stop.socAfter)
        assertEquals(14, stop.arrivalSoc)
    }

    /**
     * ⚠️ **剖面点不够 ⇒ 不给半截结果**（宁可整张卡不出现，也不给一条假曲线）。
     */
    @Test
    fun `剖面不足两个点时不建表`() {
        val empty = RouteMarginDto(
            car_id = 1,
            template_key = "journey-633-662",
            stops = emptyList(),
            endpoint_stops = emptyList(),
            reserve_soc = 20,
        )
        val plan = routePlanFromServer(
            margin = empty,
            template = tpl(startSoc = null, endSoc = null, lastStartSoc = null, lastEndSoc = null),
            charges = emptyList(),
            stationStats = emptyMap(),
            socProfile = null,
            consumption = null,
            batteryCapacityKwh = null,
        )
        assertNull("没有站也没有起止电量 ⇒ 不该建表", plan)
    }

    /**
     * ⚠️ **起点行不进剖面**（起点在剖面里单独加过，见 v0.43.0）。
     */
    @Test
    fun `真实剖面里不含起点行`() {
        val plan = routePlanFromServer(
            margin = margin(),
            template = tpl(),
            charges = emptyList(),
            stationStats = emptyMap(),
            socProfile = listOf(
                SocProfilePoint(0.0, 100.0),
                SocProfilePoint(100.0, 70.0),
                SocProfilePoint(200.0, 40.0),
            ),
            consumption = null,
            batteryCapacityKwh = null,
        )
        assertNotNull(plan)
        val p = plan!!
        // 3 个真实剖面点 + 1 个充电跳升（沿途站充完 84%）= 4
        assertEquals(4, p.profile.size)
        assertTrue("剖面应按公里升序", p.profile.zipWithNext().all { (a, b) -> a.km <= b.km })
        assertTrue("有真实剖面时不该标记为插值", !p.socProfileInterpolated)
    }
}
