package com.teslamate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.40.0（第 24 轮复审）：「不说假话」专项单测。**
 *
 * ## 为什么单独开一个文件
 *
 * 第 24 轮的两份复审（对抗性审计 + 客户复审）报出来的**最严重**的两条，
 * 都不是算错，而是**说错了话**：
 *
 * ① 「官方标称 448 公里，实测**高 77%**」——
 *    真相是「实测**低 23%**」。方向完全反了。
 * ② 「充满也到不了 —— 得在前面加一站」——
 *    真相是「充满也**到不了 20% 保底**」，车**到得了**（实测 13% / 8%）。
 *
 * 这两条的性质是「**代码没算错，但结论是假的**」——
 * 所以光靠「值对不对」的断言抓不住，必须**断言界面上那句话本身**。
 *
 * 这也是本项目第一次把「**界面文案不许说假话**」变成可执行的断言。
 */
class RoutePlanHonestyTest {

    // ── ① 实测满电续航 vs 官方标称 ─────────────────────────────

    @Test
    fun `实测低于官方时必须报低 而不是报高`() {
        // 本车真实数据：中位 346.62 公里 / 官方标称 448.01 公里
        val diff = fullChargeVsOfficialPercent(346.6238, 448.01)
        assertEquals("应报 -23（实测低 23%）", -23, diff)
        assertTrue("必须是负数（实测低于官方）", diff!! < 0)
    }

    @Test
    fun `旧算法会把低 23% 报成高 77%`() {
        // 这是**回归的证明**：旧代码算的是 round(median/official*100) = 77，
        // 然后当成「超出 100 的百分比」用 ⇒ 打印「实测高 77%」。
        val oldWrong = Math.round((346.6238 / 448.01 * 100.0)).toInt()
        assertEquals("旧算法确实会算出 77", 77, oldWrong)
        // 新的必须与它不同，且是 -23
        assertEquals(-23, fullChargeVsOfficialPercent(346.6238, 448.01))
    }

    @Test
    fun `实测高于官方时如实报高`() {
        assertEquals(25, fullChargeVsOfficialPercent(560.0, 448.0))
    }

    @Test
    fun `官方标称缺失或非法时不报错`() {
        assertNull("官方标称 0 应返回 null", fullChargeVsOfficialPercent(346.0, 0.0))
        assertNull("官方标称为负应返回 null", fullChargeVsOfficialPercent(346.0, -1.0))
        assertNull("实测为 0 应返回 null", fullChargeVsOfficialPercent(0.0, 448.0))
    }

    @Test
    fun `正好等于官方时差是 0`() {
        assertEquals(0, fullChargeVsOfficialPercent(448.0, 448.0))
    }

    // ── ② 「到不了保底」而不是「到不了」 ────────────────────────

    private fun stop(
        name: String,
        km: Double,
        gapKm: Double? = null,
        need: Int? = null,
        required: Int? = null,
        realSoc: Int? = null,
        estSoc: Int? = null,
    ) = RouteStop(
        name = name,
        kmAlongRoute = km,
        socBefore = null,
        socAfter = null,
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

    @Test
    fun `requiredSoc 超过 100 只是到不了保底 不是到不了`() {
        // 修文县 → G210 车河镇：351 公里，要 88 个点，req = 108
        val s = stop("修文县", 0.0, gapKm = 351.0, need = 88, required = 108, realSoc = 13, estSoc = 8)
        assertTrue("应判定为到不了保底", s.gapBelowReserve)
        assertFalse("绝不能判成「到不了」—— 那是假事实", !s.gapBelowReserve)
        // 充满时能剩 100 − 88 = 12%
        assertEquals("充满时到站剩 12%", 12, s.fullArrivalSoc)
        // 上次实际到站剩 13% ⇒ **车到得了**
        assertEquals("上次实际到站剩 13%（车到得了）", 13, s.nextStopRealSoc)
    }

    @Test
    fun `req 不超过 100 时不是到不了保底`() {
        val s = stop("A", 0.0, gapKm = 120.0, need = 45, required = 65, realSoc = 22, estSoc = 30)
        assertFalse(s.gapBelowReserve)
        assertEquals(55, s.fullArrivalSoc)
    }

    @Test
    fun `恰好 100 也是到得了保底`() {
        val s = stop("A", 0.0, gapKm = 120.0, need = 80, required = 100)
        assertFalse("req=100 时不该判定为到不了保底", s.gapBelowReserve)
    }

    @Test
    fun `缺 need 时算不出充满剩多少`() {
        val s = stop("A", 0.0, gapKm = 120.0, required = 65)
        assertNull(s.fullArrivalSoc)
    }

    @Test
    fun `到不了保底的跳数被单独统计`() {
        val p = RoutePlan(
            RouteTemplate(
                key = "j1", fromLabel = "永丰社区", toLabel = "石化大道西",
                distanceKm = 6227.0, startSoc = 100, endSoc = 50,
                legCount = 191, stopCount = 36,
                lastDrivenAt = java.time.Instant.parse("2026-10-07T00:00:00Z"),
                startOdoKm = 100000.0,
            ),
            emptyList(),
            listOf(
                stop("A", 0.0, gapKm = 351.0, need = 88, required = 108, realSoc = 13),
                stop("B", 351.0, gapKm = 328.0, need = 83, required = 103, realSoc = 8),
                stop("C", 679.0, gapKm = 120.0, need = 45, required = 65, realSoc = 30),
            ),
        )
        assertEquals("两跳到不了保底", 2, p.belowReserveHopCount)
        // 旧名仍可用，但语义相同
        assertEquals(p.belowReserveHopCount, p.impossibleHopCount)
    }
}
