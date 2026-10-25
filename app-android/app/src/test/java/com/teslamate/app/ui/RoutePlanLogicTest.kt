package com.teslamate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // ⚠️ **v0.41.0（第 25 轮车主复审 P1/P3 + HIG P0-1/P1-3）：决策句回到「中性事实」。**
        //
        // 这一跳**到不了 20% 保底**，但**不是「到不了」** —— 实测上次到站剩 13%，车是**到得了**的。
        //
        // 现在决策句只给两个**中性事实**（上次剩 / 估算剩）：
        // · 告警与**动作**全部交给紧随其后的琥珀行（同一张卡里说两遍 = HIG 反对）；
        // · energyBlue 在 `Theme.kt` 里的语义是「正常」，
        //   让**蓝色**承载「低于 20% 保底」这个**告警**，是把告警涂成正常色；
        // · HIG 实测：决策句加那半句后 fs=1.0 就破 2 行、fs=2.0 是 3 行，
        //   违反仓库 v0.34.0 P1-7 自己定的「决策句一行」不变量。
        val v = p.verdict()
        assertTrue("应含真值: $v", v.contains("上次剩 13%"))
        // ⚠️ 估算**始终给**（v0.36.0 曾在这一支把它藏掉，车主复审说那才是关键信息）
        assertTrue("应含估算: $v", v.contains("估算剩 8%"))
        // ⚠️ **绝不能**说「充满也到不了」—— 那是**假事实**
        // （实测那两跳上次都到得了：13% / 8%）。
        assertTrue("不应出现假事实「充满也到不了」: $v", !v.contains("充满也到不了"))
        // ⚠️ 决策句里**不该**出现告警事实（那是琥珀行的职责）
        assertTrue("决策句不应承载告警: $v", !v.contains("低于 20% 保底"))
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
        assertEquals("上次剩 22% · 估算剩 30%", v)
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

    // ── ⚠️ v0.44.0（第 25 轮车主复审 C2/D1）：「从第 N 站出发」 ──────

    private fun plan3Stations() = plan(
        listOf(
            // A → B：351 公里，要 88 个点（到不了保底）
            stop("A", 0.0, gapKm = 351.0, need = 88, required = 108, realSoc = 13, estSoc = 8),
            // B → C：120 公里，正常
            stop("B", 351.0, gapKm = 120.0, need = 30, required = 50, realSoc = 40, estSoc = 45),
            // C → D：300 公里，估算低于保底
            stop("C", 471.0, gapKm = 300.0, need = 70, required = 90, realSoc = 15, estSoc = 18),
        ),
    )

    @Test
    fun `从第 N 站起算 只给后面的跳`() {
        val p = plan3Stations()
        // 整条线：3 跳里 A→B（到不了保底）与 C→D（估 18 < 20）都要处理
        assertEquals("整条线两跳需要注意", 2, p.actionableHops.size)
        // 从 B 起算：B→C 正常、C→D 仍要注意 ⇒ 只剩 1 跳
        val fromB = p.actionableHopsFrom(1)
        assertEquals("从 B 起只剩 1 跳", 1, fromB.size)
        assertEquals("应是 C → D", "C", fromB[0].fromName)
    }

    @Test
    fun `从第 N 站起算 最长一跳也跟着变`() {
        val p = plan3Stations()
        // 整条线最长 = A→B 的 351 公里
        assertEquals(351.0, p.longestHop!!.km, 0.001)
        // 从 B 起算：B→C 是 120 公里、C→D 是 300 公里 ⇒ 最长变成 C→D
        val fromB = p.longestHopFrom(1)
        assertEquals(300.0, fromB!!.km, 0.001)
        // ⚠️ hop 以**出发站**命名（C 那一跳的出发站是 C）
        assertEquals("C", fromB.fromName)
        // ⚠️ 夹具里 C 是最后一站 ⇒ 它的「下一站」是终点
        assertEquals("终点", fromB.toName)
    }

    @Test
    fun `从第 N 站起算 决策句也跟着变`() {
        val p = plan3Stations()
        // 整条线的决策句说的是 A→B（最长那一跳，上次剩 13%）
        assertTrue("整条线: ${p.verdict()}", p.verdict().contains("13%"))
        // 从 B 起算 ⇒ 最长那一跳变成 C→D（上次剩 15%、估算剩 18%）
        val fromB = p.verdictFrom(1)
        assertTrue("从 B 起: $fromB", fromB.contains("15%"))
        assertTrue("从 B 起: $fromB", fromB.contains("18%"))
        assertFalse("不该再讲 A→B: $fromB", fromB.contains("13%"))
    }

    @Test
    fun `起点下标越界要夹住`() {
        val p = plan3Stations()
        // 越界 ⇒ 回退整条线（而不是悄悄藏掉数据），不崩
        assertEquals(p.actionableHops.size, p.actionableHopsFrom(999).size)
        assertEquals(p.actionableHops.size, p.actionableHopsFrom(-5).size)
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
        // ⚠️ v0.39.0（第 23 轮车主复审 C6）：最低点要带上**落点** ——
        // 「第 1237 公里」不在任何一个站上，用户看到 5% 也不知道在哪一站少充了。
        assertEquals("全程最低 5%（第 1237 公里 · A之后）", p.verdictDetail())
        assertEquals("A", p.lowestHopFrom)
    }

    @Test
    fun `最低点没有落点时就不写落点`() {
        val p = plan(
            listOf(stop("A", 5000.0, gapKm = 100.0)),
            profile = listOf(
                RouteSocPoint(0.0, 100),
                RouteSocPoint(1237.0, 5),
            ),
        )
        // 最低点在**所有站之前** ⇒ 没有「从哪一站之后」可说
        assertNull(p.lowestHopFrom)
        assertEquals("全程最低 5%（第 1237 公里）", p.verdictDetail())
    }

    @Test
    fun `保底电量只有一个出处`() {
        assertEquals(20, RESERVE_SOC)
    }

    @Test
    fun `需要注意的跳只包含真正要动手的那几跳`() {
        val p = plan(
            listOf(
                // ① 充满也到不了
                stop("A", 0.0, gapKm = 351.0, need = 88, required = 108, realSoc = 13, estSoc = 8),
                // ② 估算到站低于保底
                stop("B", 351.0, gapKm = 206.0, need = 50, required = 70, realSoc = 12, estSoc = 18),
                // ③ 估算说够、上次实测低于保底（模型偏乐观）
                stop("C", 557.0, gapKm = 128.0, need = 45, required = 65, realSoc = 17, estSoc = 21),
                // ④ 一切正常
                stop("D", 685.0, gapKm = 100.0, need = 40, required = 60, realSoc = 42, estSoc = 45),
            ),
        )
        val hops = p.actionableHops
        // ④ 不该在里面
        assertEquals(3, hops.size)
        assertEquals(listOf("A", "B", "C"), hops.map { it.fromName })
        // 无解跳的数量
        assertEquals(1, p.impossibleHopCount)
    }

    @Test
    fun `没有站时决策句不编数`() {
        val p = plan(emptyList())
        assertTrue(p.verdict().isNotEmpty())
        assertNull(p.longestHop)
    }
}
