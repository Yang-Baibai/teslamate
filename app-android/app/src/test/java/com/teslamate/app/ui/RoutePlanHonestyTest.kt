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

    // ── ③ 告警行（琥珀）必须有**动作**，且不能重复决策句 ──────────

    private fun planWithBelowReserve(): RoutePlan = RoutePlan(
        RouteTemplate(
            key = "j1", fromLabel = "永丰社区", toLabel = "石化大道西",
            distanceKm = 6227.0, startSoc = 100, endSoc = 50,
            legCount = 191, stopCount = 36,
            lastDrivenAt = java.time.Instant.parse("2026-10-07T00:00:00Z"),
            startOdoKm = 100000.0,
        ),
        emptyList(),
        listOf(
            stop("修文县", 4929.0, gapKm = 351.0, need = 88, required = 108, realSoc = 13, estSoc = 8),
            stop("建设南路", 4262.0, gapKm = 328.0, need = 83, required = 103, realSoc = 8, estSoc = 17),
            stop("G210 车河镇", 5280.0, gapKm = 120.0, need = 45, required = 65, realSoc = 30),
        ),
    )

    @Test
    fun `告警行必须给出动作`() {
        val n = planWithBelowReserve().belowReserveNotice()
        assertTrue("必须给告警行: $n", n != null)
        // ⚠️ **v0.41.0（第 25 轮车主复审 P3）**：删掉假动作后必须给**真动作**。
        assertTrue("必须给动作: $n", n!!.contains("想留住就得提前一站补电"))
    }

    @Test
    fun `告警行不得说充满也到不了`() {
        val n = planWithBelowReserve().belowReserveNotice()!!
        assertTrue("不得出现假事实「充满也到不了」: $n", !n.contains("充满也到不了"))
        assertTrue("不得说「得在前面加一站」（那是空建议）: $n", !n.contains("得在前面加一站"))
        // 必须说清「充满也只剩多少」与「低于多少保底」
        assertTrue("应说明充满也只剩 12%: $n", n.contains("充满也只剩 12%"))
        assertTrue("应说明低于 20% 保底: $n", n.contains("低于 20% 保底"))
    }

    @Test
    fun `告警行不得重复决策句里的真值`() {
        // ⚠️ **v0.41.0（第 25 轮 HIG 复审 P0-2）**：同一张卡里说两遍 = 重复信息。
        // 决策句已经说了「上次剩 13%」，琥珀行就不该再说一次。
        val p = planWithBelowReserve()
        val v = p.verdict()
        val n = p.belowReserveNotice()!!
        assertTrue("决策句应含真值: $v", v.contains("上次剩 13%"))
        assertTrue("告警行不应重复真值: $n", !n.contains("上次剩 13%"))
    }

    @Test
    fun `够得着保底时没有告警行`() {
        val p = RoutePlan(
            RouteTemplate(
                key = "j1", fromLabel = "A", toLabel = "B",
                distanceKm = 120.0, startSoc = 100, endSoc = 50,
                legCount = 2, stopCount = 1,
                lastDrivenAt = java.time.Instant.parse("2026-10-07T00:00:00Z"),
                startOdoKm = 100000.0,
            ),
            emptyList(),
            listOf(stop("A", 0.0, gapKm = 120.0, need = 45, required = 65, realSoc = 22, estSoc = 30)),
        )
        assertNull("够得着保底就不该有告警行", p.belowReserveNotice())
    }

    @Test
    fun `三个数各有各的标签 不会读成 App 算错了`() {
        // ⚠️ **v0.41.0（第 25 轮车主复审 P2）**：
        // v0.40.0 把「上次到站剩 13%」与「充满也只剩 12%」并排，
        // 标签却都用「剩」字 —— 读者看到 13 > 12 会以为 App 算错了。
        // 现在三个标签互不相同。
        val p = planWithBelowReserve()
        val v = p.verdict()
        assertTrue("观测值标签: $v", v.contains("上次剩 13%"))
        assertTrue("模型估算标签: $v", v.contains("估算剩 8%"))
        assertFalse("观测标签不应再是「上次到站剩」: $v", v.contains("上次到站剩"))
        // 「充满也只剩」只出现在告警行里
        val n = p.belowReserveNotice()!!
        assertTrue(n.contains("充满也只剩 12%"))
    }

    // ── ④ 端到端单向偏差：从**这条线自己的数据**算 ────────────────

    private fun planWithErrors(vararg errs: Pair<Int, Int>): RoutePlan {
        // errs = (估算, 实测)
        val stops = errs.mapIndexed { i, (est, real) ->
            stop("S$i", i * 100.0, gapKm = 100.0, need = 20, required = 40, realSoc = real, estSoc = est)
        }
        return RoutePlan(
            RouteTemplate(
                key = "j1", fromLabel = "A", toLabel = "B",
                distanceKm = 1000.0, startSoc = 100, endSoc = 50,
                legCount = errs.size, stopCount = errs.size,
                lastDrivenAt = java.time.Instant.parse("2026-10-07T00:00:00Z"),
                startOdoKm = 100000.0,
            ),
            emptyList(),
            stops,
        )
    }

    @Test
    fun `端到端偏差是从这条线自己算的`() {
        // 估算比实测高 4 / 5 / 3 个点 ⇒ 中位 4、区间 3 ~ 5
        val p = planWithErrors(18 to 14, 20 to 15, 16 to 13)
        val e = p.endToEndErrorPoints
        assertTrue("应算出偏差: $e", e != null)
        assertEquals("中位偏差 = 4", 4, e!!.first)
        assertEquals("最小偏差 = 3", 3, e.second)
        assertEquals("最大偏差 = 5", 5, e.third)
    }

    @Test
    fun `端到端偏差样本不足时不给数`() {
        // 只有 2 个点 ⇒ 样本不足
        assertNull(planWithErrors(18 to 14, 20 to 15).endToEndErrorPoints)
    }

    @Test
    fun `端到端偏差能识别偏保守`() {
        // 估算比实测**低** ⇒ 中位为负 ⇒ 区间也全为负
        val p = planWithErrors(10 to 14, 10 to 15, 10 to 13)
        val e = p.endToEndErrorPoints!!
        assertTrue("中位应为负（模型保守）: ${e.first}", e.first < 0)
        assertTrue("最小也应为负: ${e.second}", e.second < 0)
        assertTrue("最大也应为负: ${e.third}", e.third < 0)
    }

    @Test
    fun `端到端偏差是双侧的 两端都要给出来`() {
        // ⚠️ **v0.43.0（第 26 轮口径审计 S3）**：
        // 实测那条线 35 跳是 **22 正 / 12 负 / 1 零** —— 误差是**双侧**的。
        // 旧实现只取乐观侧的尾部 ⇒ **真最坏（保守侧）被整段藏掉**。
        val p = planWithErrors(18 to 14, 10 to 15, 16 to 13)
        val e = p.endToEndErrorPoints!!
        assertEquals("中位 = 3", 3, e.first)
        assertEquals("保守侧真最坏必须给出来 = −5", -5, e.second)
        assertEquals("乐观侧 = 4", 4, e.third)
    }

    // ── ⑤ 起点行：站间距分布不能被它污染 ────────────────────────

    @Test
    fun `站间距分布不含起点`() {
        // ⚠️ **v0.43.0（第 26 轮口径审计 W-3）**：
        // 起点行是合成的虚拟行（km = 0），**不是站**。
        // 混进站间距 ⇒ 中位从 183 公里变成 187 公里。
        val p = RoutePlan(
            RouteTemplate(
                key = "j1", fromLabel = "家", toLabel = "B",
                distanceKm = 1000.0, startSoc = 100, endSoc = 50,
                legCount = 3, stopCount = 2,
                lastDrivenAt = java.time.Instant.parse("2026-10-07T00:00:00Z"),
                startOdoKm = 100000.0,
            ),
            emptyList(),
            listOf(
                // 起点行：km 0、到站 191 公里
                RouteStop(
                    name = "家", kmAlongRoute = 0.0, socBefore = 89, socAfter = null,
                    typicalPowerKw = null, minutesTo80 = null, sessions = 1,
                    gapToNextKm = 191.0, gapNeedPct = 50, requiredSocHere = 70,
                    nextStopRealSoc = 12, socAtNextStop = 18, nextStopName = "B",
                    isStart = true,
                ),
                // 真站：191 公里、383 公里
                stop("B", 191.0, gapKm = 192.0),
                stop("C", 383.0, gapKm = null),
            ),
        )
        val rhythm = p.stationRhythm()
        // 只算两个真站之间的 192 公里 ⇒ 不是 191~192
        assertTrue("站间距应只含真站之间那 192 公里: $rhythm", rhythm.contains("192 公里"))
        assertFalse("起点 0.0 不能混进站间距: $rhythm", rhythm.contains("191 公里"))
    }
}
