package com.teslamate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
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
    fun `需要注意的站下标与跳一一对应`() {
        // ⚠️ v0.44.0：「只看需要注意的站」筛选要用它 ——
        // 站区有 37 行，出发前 5 分钟真正要看的只有那十几行。
        val p = plan3Stations()
        // A→B（到不了保底）与 C→终点（估 18 < 20）两跳要注意
        assertEquals(setOf(0, 2), p.actionableStopIndices())
        // 从 B 起算 ⇒ 只有 C 那一跳
        assertEquals(setOf(2), p.actionableStopIndices(1))
    }

    // ── ⚠️ v0.46.0（第 27 轮验收）：v0.44.0 新功能的三处假话 ────────

    @Test
    fun `选中最后一站 决策句不能说没有充电记录`() {
        // ⚠️ **第 27 轮验收 P0-1**：这条线上有 36 个沿途充电记录，
        // 但选中**最后一站**（它后面没有下一站）时，
        // `longestHopFrom` 返回 null → 掉进兜底分支 →
        // 打出一句**假话**「这条线路上没有你的充电记录，中途补电给不出位置」。
        //
        // ⚠️ 夹具要**最后一站没有下一跳**（gapKm = null）才会复现。
        val p = plan(
            listOf(
                stop("A", 0.0, gapKm = 200.0, need = 60, required = 80, realSoc = 20, estSoc = 25),
                stop("B", 200.0, gapKm = 300.0, need = 80, required = 100, realSoc = 15, estSoc = 18),
                // ⚠️ 最后一站：**没有下一站**
                stop("C", 500.0, gapKm = null, need = null, required = null, realSoc = 30, estSoc = null),
            ),
        )
        val v = p.verdictFrom(2)
        assertFalse("绝不能出现「没有充电记录」这句假话: $v", v.contains("没有你的充电记录"))
        assertTrue("应说清是「到最后一站」: $v", v.contains("最后一站"))
    }

    @Test
    fun `还有 N 跳只数从选中站起算的`() {
        // ⚠️ **第 27 轮验收 P1-1**：原来用**全线**计数 ——
        // 选「修文县」时报的那 1 跳是**建设南路**，在我身后 668 公里。
        val p = plan3Stations()
        // A→B 是全线唯一一跳「到不了保底」
        assertEquals(1, p.belowReserveHopCount)
        // 从 B 起算 ⇒ 身后那一跳不在范围内
        assertEquals(0, p.belowReserveHopCountFrom(1))
    }

    @Test
    fun `选了别的起点 站区不再钉着起点行`() {
        // ⚠️ **第 27 轮验收 P0-2**：原来无条件把起点行（0）加进可见下标 ——
        // 选「修文县」时站区第一行仍是「出发前至少充到 93%」，
        // 而那个动作在我身后 4929 公里。
        val p = plan3Stations()
        // 从起点起算 ⇒ 起点行在
        assertTrue("从起点起算应含起点行: ${p.visibleStopIndices(0, false)}",
            0 in p.visibleStopIndices(0, false))
        // 从 B 起算 ⇒ **不含**起点行
        assertFalse("从 B 起算不应含起点行: ${p.visibleStopIndices(1, false)}",
            0 in p.visibleStopIndices(1, false))
        // 一键展开 ⇒ 全部站
        assertEquals(p.stops.size, p.visibleStopIndices(1, true).size)
    }

    // ── ⚠️ v0.47.0（第 28 轮自查）：v0.46.0 修 P0-2 时**顺手弄丢**了选中站自己 ──

    @Test
    fun `选中的那一站自己必须留在站区里`() {
        // ⚠️ **第 28 轮自查 P0**：v0.46.0 把起点行改成「只在 startIdx == 0 时出现」是对的，
        // 但 `visibleStopIndices` 收的是「下一跳**需要注意**」的站 ——
        // 用户选中的那一站**恰恰可能什么都不需要注意**，
        // 于是它自己从站区里消失了。
        //
        // 实测（真实数据）：川藏滇线 37 站里 **24 站**在选中后会消失。
        // 用户点了「印象雅緻酒店」，列表里没有这一行 ——
        // 而那一行正是「出发时 X% · 下一站 Y · N 公里」，
        // 也就是他选完之后最想读的那句话。看起来像 App 把选择弄丢了。
        val p = plan3Stations()
        // B → C 是 120 公里、required 50、估 45 ⇒ **不需要注意**
        assertFalse("B 本身不该是需要注意的站: ${p.actionableStopIndices(1)}",
            1 in p.actionableStopIndices(1))
        // 但选中了 B ⇒ B 自己必须在可见站区里
        assertTrue("选中的站自己必须在: ${p.visibleStopIndices(1, false)}",
            1 in p.visibleStopIndices(1, false))
        // 每一站都一样：选谁谁就在
        for (i in p.stops.indices) {
            assertTrue("选第 $i 站时它自己必须在可见站区里",
                i in p.visibleStopIndices(i, false))
        }
    }

    @Test
    fun `可见站区里不会出现重复行`() {
        // 选中的站**同时**是需要注意的站时，不能出现两行
        val p = plan3Stations()
        for (i in p.stops.indices) {
            for (showAll in listOf(false, true)) {
                val v = p.visibleStopIndices(i, showAll)
                assertEquals("选第 $i 站 showAll=$showAll 时不应有重复: $v",
                    v.size, v.distinct().size)
            }
        }
    }

    // ── ⚠️ v0.47.0（第 28 轮对抗性审计）：三个 P0 的回归锁 ──────────

    @Test
    fun `档位行的爬升修正不能再多除 10 倍`() {
        // ⚠️ **第 28 轮审计 P0-1**：原来内联写的是 `(c / 10.0).fmtKwh100()`，
        // 而 `fmtKwh100()` **本身就已经 ÷10** ⇒ 同屏两个数差 **10 倍**。
        // 实测：6227 公里那条线 34 个渲染档位行的站里 **25 个**与详情行冲突、
        // **17 个**显示成 `0.0 / -0.0`；虎跳峡栈道那跳详情行 `+4.9`、档位行 `0.5`。
        //
        // ⚠️ 这里的真值是 **Wh/km**（内部口径），显示层才换算成 kWh/100km。
        // 49 Wh/km ⇒ 4.9 kWh/100km。
        assertEquals(" · 爬升修正 4.9 kWh/100km", climbBucketSuffix(49.0))
        // 6 Wh/km ⇒ 0.6 kWh/100km（旧代码会打成 0.1）
        assertEquals(" · 爬升修正 0.6 kWh/100km", climbBucketSuffix(6.0))
        // 负值也要正确换算
        assertEquals(" · 爬升修正 -3.0 kWh/100km", climbBucketSuffix(-29.887))
    }

    @Test
    fun `爬升修正小于门槛就不给片段`() {
        // ⚠️ **第 28 轮审计 P2-1**：档位行必须与详情行**同门槛**（`abs >= 5.0`），
        // 否则会打「爬升修正 0.0 / -0.0」（实测 17 个站），而详情行那块不显示。
        assertEquals("", climbBucketSuffix(4.9))
        assertEquals("", climbBucketSuffix(-4.9))
        assertEquals("", climbBucketSuffix(0.0))
        assertEquals("", climbBucketSuffix(null))
    }

    @Test
    fun `负的爬升修正不能说多耗`() {
        // ⚠️ **第 28 轮审计 P0-2**：负修正 = 这一段比该档的典型坡度**更平** = **省电**。
        // 原来写死「多耗 X 度」，负值打出来就是「多耗 -2.9 度」—— 自相矛盾。
        // 真实数据：6227 公里那条线有 **10 个站**触发。
        val neg = climbDetailSuffix(-29.887, -2.9, -4.0)
        assertTrue("负修正不该说「多耗」: $neg", !neg.contains("多耗"))
        assertTrue("负修正该说「少耗」: $neg", neg.contains("少耗"))
        assertTrue("负修正的数要取绝对值: $neg", neg.contains("2.9"))
        assertTrue("负修正的百分点要取绝对值: $neg", neg.contains("4 个百分点"))

        val pos = climbDetailSuffix(49.0, 12.3, 19.0)
        assertTrue("正修正该说「多耗」: $pos", pos.contains("多耗"))
        assertTrue("正修正不该说「少耗」: $pos", !pos.contains("少耗"))
    }

    @Test
    fun `能一次开到那一句说的是上次实测不是预计`() {
        // ⚠️ **第 28 轮审计 P1-2**：`low` 来自 profile（上次那趟的真实到站电量），
        // 不是模型预测值。写「预计到达」是口径不符 ——
        // 同一张卡的模型口径是 47%，界面写 34%，差 13 个点。
        val t = singleHopArrivalText(34)
        assertFalse("不该说「预计」: $t", t.contains("预计"))
        assertTrue("该说「上次」: $t", t.contains("上次到达 34%"))
    }

    @Test
    fun `起点 chip 的可访问名把间隔号换成逗号`() {
        // ⚠️ **第 28 轮 a11y P1-3**：TTS 会把「 · 」念成「点」。
        // WCAG 2.5.3：可访问名仍要包含可见标签（站名原样保留）。
        assertEquals("中国石化，2173 公里", startChipAccessibleName("中国石化 · 2173 公里"))
        assertEquals("起点（永丰社区）", startChipAccessibleName("起点（永丰社区）"))
    }

    @Test
    fun `还有 N 跳的方向词是身前不是身后`() {
        // ⚠️ **第 28 轮审计 P0-3**：v0.46.0 把计数改成了「从选中站起算」（正确），
        // 但方向词写反了 —— `belowReserveHopCountFrom` 数的是 `from..lastIndex`，
        // 那是**身前**（还没开过去的那些）。
        // 真实数据：6227 公里那条线选「乐驿」（km 276.3），
        // 界面说「你身后还有 1 跳」，而那 1 跳在**身前 3985.5 公里**。
        val p = plan3Stations()
        // A→B 是全线唯一一跳「到不了保底」；C→D 是估算低于保底。
        // 从 B 起算 ⇒ 身后的 A→B 不该被算进来
        assertEquals(0, p.belowReserveHopCountFrom(1))
        // 从 A 起算 ⇒ 只有 A→B（required=108 > 100）
        assertEquals(1, p.belowReserveHopCountFrom(0))
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

    // ── ⚠️ v0.58.0（第 30 轮长途自驾党评审 P1-3）───────────────────────

    @Test
    fun `处方接近电池上限时给提示`() {
        // 「充到 100%」是**电池上限**，不是「一个可以充到的目标」——
        // 直流桩 80% 以后降功率，能不能真到 100% 取决于桩与电池温度。
        // 实测川藏滇线 36 跳里 =100% 的 1 跳、>90% 的 8 跳。
        assertNotNull("处方 100% 时必须给提示", chargeTargetHint(100))
        assertNotNull("处方 95% 时必须给提示", chargeTargetHint(95))
        assertNotNull("处方 98% 时必须给提示", chargeTargetHint(98))
    }

    @Test
    fun `处方离上限还远时不给提示`() {
        // 低于 95% 时那句话是噪音 —— 每个站都挂一句等于没挂。
        assertNull("处方 90% 时不该提示", chargeTargetHint(90))
        assertNull("处方 68% 时不该提示", chargeTargetHint(68))
        assertNull("处方 20% 时不该提示", chargeTargetHint(20))
    }

    @Test
    fun `提示里不能说充不满就不管了`() {
        // 提示是**事实性**的（直流桩会降功率），不是「所以别管了」。
        val t = chargeTargetHint(100)!!
        assertTrue("应说明是直流桩降功率: $t", t.contains("直流桩"))
        assertTrue("应说明 80% 以上: $t", t.contains("80%"))
        assertTrue("不能是放弃的口吻: $t", !t.contains("别管") && !t.contains("放弃"))
    }

    // ── ⚠️ v0.59.0（第 30 轮长途自驾党评审 P1-2）───────────────────────

    @Test
    fun `处方的误差带要上到 L1`() {
        // ⚠️ **第 30 轮 P1-2**：处方是**执行指令**（「充到 100%」），
        // 它带的余量看起来**正好是 20% 保底**，
        // 而模型自己的残差是 ±10 个点 —— 用户照做仍可能以 **10%** 到站。
        //
        // 实测（川藏滇线 #4 蔚来换电站，280 公里那一跳）：
        // 处方「充到 100%」，模型残差 ±10 点 ⇒ 实际到站可能 **10%~30%**。
        val t = prescriptionErrorText(requiredSoc = 100, errorPoints = 10)
        assertNotNull("必须有到站区间: $t", t)
        assertTrue("应给出到站区间: $t", t!!.contains("10%~30%"))
        // ⚠️ **v0.61.0**：**不得**重复详情行已经有的「模型残差 ±N 个点」
        // （同一张卡说两遍 = 重复信息）。
        assertFalse("不应重复「模型残差」: $t", t.contains("模型残差"))
    }

    @Test
    fun `没有误差带时不显示`() {
        // 样本不足 ⇒ 没有误差带 ⇒ **不给数**（不给一个编的范围）。
        assertNull("误差点为 null 时不给", prescriptionErrorText(100, null))
        assertNull("误差点为 0 时不给", prescriptionErrorText(100, 0))
    }

    @Test
    fun `连充满也守不住保底的那一支不叠加误差带`() {
        // req > 100 的文案已经是「充满也只剩 X%，低于 20% 保底」，
        // 语义不同，再叠一段误差带会读不通。
        assertNull("req > 100 时不给", prescriptionErrorText(108, 10))
    }

    @Test
    fun `误差带的下界不能是负数`() {
        // 残差比保底还大时，下界不能出现负百分比。
        val t = prescriptionErrorText(60, 35)!!
        assertTrue("下界不能为负: $t", t.contains("0%"))
        assertFalse("不该出现负号: $t", t.contains("-"))
    }
}
