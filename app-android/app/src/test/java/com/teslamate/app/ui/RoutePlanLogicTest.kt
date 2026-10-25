package com.teslamate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ⚠️ **v0.37.0：线路余量表的**纯逻辑**单元测试。**
 *
 * ## 盯住什么
 *
 * ① [RoutePlan.longestHop] —— 「最长的一跳」取的对不对
 *    （v0.36.0 把它提成了 44sp 主角，取错就是**屏幕上最大的数字是错的**）。
 *
 * ② [RoutePlan.verdict] —— 决策句的**三种分支**：
 *    正常 / 估算为负 / **这一跳无解**。
 *    无解那一支最关键：它必须**不报估算**
 *    （「估算剩 8%」会被读成「紧但能到」，而真相是「充满也到不了」）。
 *
 * ③ [RoutePlan.verdictDetail] —— 全程最低点的落点。
 *
 * ④ [RoutePlan.reservation] —— 「保底 20%」只有一个出处。
 */
class RoutePlanLogicTest {

    private fun template(
        key: String = "j1",
        from: String = "永丰社区",
        to: String = "石化大道西",
        km: Double = 616.0,
        roundTrip: Boolean = false,
    ) = RouteTemplate(
        key = key,
        fromLabel = from,
        toLabel = to,
        distanceKm = km,
        startSoc = 100,
        endSoc = 50,
        legCount = 13,
        stopCount = 5,
        lastDrivenAt = Instant.parse("2026-10-18T00:00:00Z"),
        startOdoKm = 100000.0,
        isRoundTrip = roundTrip,
    )

    private fun stop(
        name: String,
        km: Double,
        socBefore: Int? = null,
        socAfter: Int? = null,
        gapKm: Double? = null,
        need: Int? = null,
        required: Int? = null,
        realSoc: Int? = null,
        estSoc: Int? = null,
    ) = RouteStop(
        name = name,
        kmAlongRoute = km,
        socBefore = socBefore,
        socAfter = socAfter,
        typicalPowerKw = null,
        minutesTo80 = null,
        sessions = 1,
        gapToNextKm = gapKm,
        gapNeedPct = need,
        requiredSocHere = required,
        nextStopRealSoc = realSoc,
        socAtNextStop = estSoc,
        nextStopName = null,
    )

    private fun plan(
        stops: List<RouteStop>,
        profile: List<RouteSocPoint> = emptyList(),
        tpl: RouteTemplate = template(),
    ) = RoutePlan(tpl, profile, stops)

    @Test
    fun `最长的一跳取最大的间距`() {
        // 真实的站间距挂在**出发站**上：A→B 191 公里、B→C 258.7 公里、
        // 最后一站没有「下一站」（gapToNextKm = null）。
        val p = plan(
            listOf(
                stop("A", 0.0, gapKm = 191.0),
                stop("B", 191.0, gapKm = 258.7),
                stop("C", 449.7, gapKm = null),
            ),
        )
        val hop = p.longestHop
        assertTrue("longestHop 不应为 null", hop != null)
        assertEquals(258.7, hop!!.km, 0.001)
        assertEquals("B", hop.fromName)
        assertEquals("C", hop.toName)
    }

    @Test
    fun `小于 2 公里的间距不算一跳`() {
        // 同一个桩被拆成两行会留下 1.9 公里的「假段」——
        // 它不能被当成「最短的一跳」。
        val p = plan(
            listOf(
                stop("A", 0.0, gapKm = 1.9),
                stop("B", 1.9, gapKm = 300.0),
                stop("C", 301.9, gapKm = null),
            ),
        )
        val hop = p.longestHop
        assertTrue(hop != null)
        assertEquals(300.0, hop!!.km, 0.001)
        assertEquals("B", hop.fromName)
    }

    @Test
    fun `决策句给出真值与估算`() {
        val p = plan(
            listOf(
                stop(
                    "A", 0.0, gapKm = 351.0,
                    need = 88, required = 108,
                    realSoc = 13, estSoc = 8,
                ),
            ),
        )
        // 这一跳无解（108 > 100）→ **不报估算**
        val v = p.verdict()
        assertTrue("应含真值: $v", v.contains("上次到站剩 13%"))
        assertTrue("无解时不应出现估算: $v", !v.contains("估算"))
    }

    @Test
    fun `决策句在正常时给出估算`() {
        val p = plan(
            listOf(
                stop(
                    "A", 0.0, gapKm = 120.0,
                    need = 45, required = 65,
                    realSoc = 22, estSoc = 30,
                ),
            ),
        )
        val v = p.verdict()
        assertEquals("上次到站剩 22% · 估算剩 30%", v)
    }

    @Test
    fun `决策句在估算为负时说差几个点`() {
        // 这一跳「有解」（充到 100% 能留 20% 保底），
        // 但**按上次的充法**到站是负的 —— 那要说清差几个点。
        val p = plan(
            listOf(
                stop(
                    "A", 0.0, gapKm = 100.0,
                    need = 80, required = 100,
                    realSoc = 13, estSoc = -10,
                ),
            ),
        )
        val v = p.verdict()
        assertTrue("应说明差几个点: $v", v.contains("差 10 个点"))
    }

    @Test
    fun `细节行给全程最低点的落点`() {
        val p = plan(
            listOf(stop("A", 0.0, gapKm = 100.0)),
            profile = listOf(
                RouteSocPoint(0.0, 100),
                RouteSocPoint(1237.0, 5),
                RouteSocPoint(2000.0, 30),
            ),
        )
        assertEquals("全程最低 5%（第 1237 公里）", p.verdictDetail())
    }

    @Test
    fun `保底电量只有一个出处`() {
        assertEquals(20, RESERVE_SOC)
    }

    @Test
    fun `没有站时决策句不编数`() {
        val p = plan(emptyList())
        assertTrue(p.verdict().isNotEmpty())
        assertNull(p.longestHop)
    }
}
