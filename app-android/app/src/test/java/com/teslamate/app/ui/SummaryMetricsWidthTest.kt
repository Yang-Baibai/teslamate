package com.teslamate.app.ui

import com.teslamate.app.StatsBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.104.0**：「最近 30 天」卡那两条指标的**宽度预算**。
 *
 * ## 盯住什么
 *
 * 那两条指标原来**并排**在一个 `Row` 里：
 * 左「平均能耗 17.4 kWh/100km」、右「直流快充 93%（按充入电量）」。
 * 两条加起来需要约 **353dp**，而卡片内容区在**最窄**的常见屏（360dp）上只有 **296dp**
 * ⇒ **放不下** ⇒ 右边那串被贪心折行劈成「…（按充入电」+「量）」，
 * Spacer 同时被挤成 0 ⇒ 两串贴死（真机截图，v0.103.0 记下、v0.104.0 修掉）。
 *
 * CJK 允许在**任意两个汉字之间**断行 ⇒ 窄栏里**没有任何写法**能保证不被劈开；
 * 而且可用宽度**随机型与字号变化** ⇒ 任何「刚好放得下」的写法都是运气，不是结构。
 *
 * ⇒ 结构由 `scripts/client-gates.sh` 的**闸门 16** 守住；
 *   这里钉的是**宽度预算本身**（与 `RangeConclusionWidthTest` 同一套路）。
 *
 * ## ⚠️ 字号口径
 *
 * 这两行用的是 `bodySmall`，而本 App **覆盖了整套排版**（`ui/Theme.kt`）：
 * `bodySmall = 13sp`（不是 M3 默认的 14sp）⇒ 200% 字号是 **26sp**。
 */
class SummaryMetricsWidthTest {

    /** 平均能耗那一行**独占整宽**时放得下。 */
    @Test
    fun `平均能耗那一行自己放得下`() {
        val line = avgEnergyLine(174.19)!!
        val needed = summaryLineNeededDp(line)
        assertTrue(
            "平均能耗那一行需要 %.0fdp，卡片内容区只有 %.0fdp ⇒ 放不下"
                .format(needed, SUMMARY_CARD_CONTENT_DP),
            needed <= SUMMARY_CARD_CONTENT_DP,
        )
    }

    /** 直流快充的数字那半放得下 ⇒ 它换行时换的是**整块**，不是半截。 */
    @Test
    fun `直流快充的数字那半自己放得下`() {
        val needed = summaryLineNeededDp(dcShareLine(93))
        assertTrue(
            "「直流快充 93%%」需要 %.0fdp，卡片内容区只有 %.0fdp ⇒ 放不下"
                .format(needed, SUMMARY_CARD_CONTENT_DP),
            needed <= SUMMARY_CARD_CONTENT_DP,
        )
    }

    /**
     * 口径限定语自己放得下 ⇒ 它**独立成段**时永远不会被劈开。
     *
     * ⚠️ 这是本轮修复的**核心不变式**：v0.94.0 的里程表教训（限定语与数字
     * 在同一段落里就会被劈成「估」+「算」）在「（按充入电量）」上重演了一次。
     */
    @Test
    fun `口径限定语自己放得下所以永远不会被劈开`() {
        val needed = summaryLineNeededDp(DC_SHARE_CALIBER)
        assertTrue(
            "口径限定语「%s」需要 %.0fdp，卡片内容区只有 %.0fdp ⇒ 放不下"
                .format(DC_SHARE_CALIBER, needed, SUMMARY_CARD_CONTENT_DP),
            needed <= SUMMARY_CARD_CONTENT_DP,
        )
    }

    /**
     * ⚠️ **核心不变式**：两条指标**并排**放不下 ⇒ 必须上下堆叠。
     *
     * 这条断言是整个修复的**理由**：如果哪天有人把并排改回来（或把文案缩短到刚好放得下），
     * 它会当场报红，而不是等到某台窄屏手机上再被发现一次。
     */
    @Test
    fun `两条指标并排放不下`() {
        val avg = summaryLineNeededDp(avgEnergyLine(174.19)!!)
        val dcRow = dcShareRowNeededDp(93)
        val gap = 8f                      // 两个 Text 之间的最小间距
        val sideBySide = avg + dcRow + gap
        assertTrue(
            "两条并排需要 %.0fdp（能耗 %.0f + 快充行 %.0f + 间距 %.0f），"
                .format(sideBySide, avg, dcRow, gap) +
                "而卡片内容区只有 %.0fdp ⇒ 并排**放得下** ⇒ 这条不变式已经失效，得重新设计"
                .format(SUMMARY_CARD_CONTENT_DP),
            sideBySide > SUMMARY_CARD_CONTENT_DP,
        )
    }

    /**
     * 200% 字号下，两条指标**各自**都放得下。
     *
     * ⚠️ 仓库规矩：布局问题必须在 **200%** 下也成立。
     * 200% 时「快充行」（数字 + 限定语）**整体**放不下（340dp > 296dp）——
     * 这是**设计好的**：两半都用 **FlowRow** 排，放不下就**整体换行**
     * ⇒ 数字整块与限定语**都不会被劈开**。
     */
    @Test
    fun `两倍字号下每一条各自都放得下`() {
        assertTrue(
            "200% 下「直流快充 93%」放不下 ⇒ 数字整块会被逼到换行",
            summaryLineNeededDp(dcShareLine(93), SUMMARY_BODY_SMALL_200_SP) <=
                SUMMARY_CARD_CONTENT_DP,
        )
        assertTrue(
            "200% 下口径限定语放不下 ⇒ 它会被劈开",
            summaryLineNeededDp(DC_SHARE_CALIBER, SUMMARY_BODY_SMALL_200_SP) <=
                SUMMARY_CARD_CONTENT_DP,
        )
    }

    /**
     * 200% 字号下，平均能耗那一行**折行也只折在标签后面**，值与单位不拆。
     *
     * ⚠️ 那一行 200% 时需要 349dp > 296dp ⇒ 必然会折行。
     *
     * ⚠️ **贪心折行取的是「最后一个放得下的断点」** ——
     * 所以「平均能耗 17.4 kWh/100km」里那个**普通空格**是致命的：
     * 贪心会把「17.4 kWh/100km」整块放到第二行，
     * 而断点落在「17.4」后面 ⇒ **数与单位分离**（半个句子）。
     *
     * ⇒ 那个空格必须是**不换行空格**（U+00A0）⇒ 整行只剩**一个**断行机会
     *   （「平均能耗」后面那个）⇒ 折行必然是「平均能耗」/「17.4 kWh/100km」。
     */
    @Test
    fun `两倍字号下平均能耗那行只折在标签后面`() {
        val line = avgEnergyLine(174.19)!!

        // ① 整行**只有一个**普通空格 ⇒ 只有「标签 | 值+单位」这一个断行机会。
        val spaces = line.count { it == ' ' }
        assertEquals("「$line」里普通空格有 $spaces 个 ⇒ 断行机会不止一个 ⇒ 折行可能落在词中间",
            1, spaces)

        // ② 值与单位之间是**不换行空格** ⇒ 那里**没有**断行机会。
        val unitAt = line.indexOf(ENERGY_UNIT)
        assertTrue("「$line」里找不到单位「$ENERGY_UNIT」", unitAt > 0)
        assertEquals("「${line.substring(0, unitAt + ENERGY_UNIT.length)}」的值与单位之间"
            + "不是不换行空格 ⇒ 200% 下会被折成「平均能耗 17.4」/「kWh/100km」",
            NBSP, line[unitAt - 1].toString())

        // ③ 标签那半在 200% 下放得下 ⇒ 折行必然发生在它后面。
        val sp = line.indexOf(' ')
        val head = line.substring(0, sp)      // 「平均能耗」
        val tail = line.substring(sp + 1)     // 「17.4 kWh/100km」（含不换行空格）
        assertTrue(
            "「%s」在 200%% 下需要 %.0fdp，放不下 ⇒ 折行会落在词中间"
                .format(head, summaryLineNeededDp(head, SUMMARY_BODY_SMALL_200_SP)),
            summaryLineNeededDp(head, SUMMARY_BODY_SMALL_200_SP) <= SUMMARY_CARD_CONTENT_DP,
        )
        // ④ 值+单位那一整块在 200% 下也放得下 ⇒ 它**不会**被拆成两行。
        assertTrue(
            "「%s」在 200%% 下需要 %.0fdp，放不下 ⇒ 数与单位会被拆到两行"
                .format(tail, summaryLineNeededDp(tail, SUMMARY_BODY_SMALL_200_SP)),
            summaryLineNeededDp(tail, SUMMARY_BODY_SMALL_200_SP) <= SUMMARY_CARD_CONTENT_DP,
        )
        // ⑤ 而 tail 里**没有**任何普通空格 ⇒ 它内部**不可能**有断点。
        assertTrue("「$tail」内部还有普通空格 ⇒ 它自己也能被折开",
            !tail.contains(' '))
    }

    /** 文案没丢：数字 + 口径都还在（v0.47.0 特意补的**分母**不许消失）。 */
    @Test
    fun `文案没丢`() {
        assertEquals("直流快充 93%", dcShareLine(93))
        assertEquals("（按充入电量）", DC_SHARE_CALIBER)
        assertEquals("平均能耗 17.4" + NBSP + "kWh/100km", avgEnergyLine(174.19))
    }

    /** 没有能耗数据 ⇒ 整块不渲染（不给「—」占位，也不给 0）。 */
    @Test
    fun `没有能耗数据就不渲染`() {
        assertNull(avgEnergyLine(null))
        assertNull(avgEnergyLine(0.0))
        assertNull(avgEnergyLine(0.1))
    }

    // ── v0.113.0：上面那 2×2 指标网格 ───────────────────────────────────────

    /** 线上真实数据（`/cars/1/stats?period=month` 的 summary）。 */
    private val realBucket = StatsBucket(
        start = "2026-10-01",
        km = 3834.55012,
        minutes = 4135.0,
        kwh_used = 665.1867,
        wh_per_km = 173.4719,
        drive_count = 137,
        charge_kwh = 673.64,
        dc_kwh = 672.94,
        ac_kwh = 0.7000,
        cost = 0.0,
        charge_count = 31,
    )

    private fun realValues() = summaryMetrics(realBucket, driveDays = 22).map { it.value }

    /**
     * 正常字号下是**两列**，而且每个值在每格（148dp）里都放得下。
     *
     * ⚠️ 这条钉住「视觉与原来一模一样」：正常字号下不该变成 4 行。
     */
    @Test
    fun `正常字号下是两列且每格放得下`() {
        val values = realValues()
        assertEquals("正常字号下不该换成单列（视觉会变）", 2, summaryMetricColumns(values, 1.0f))
        values.forEach { v ->
            val needed = summaryMetricValueNeededDp(v, 1.0f)
            assertTrue(
                "「%s」需要 %.1fdp，每格只有 %.0fdp ⇒ 放不下"
                    .format(v, needed, SUMMARY_METRIC_CELL_DP_TWO_COLUMNS),
                needed <= SUMMARY_METRIC_CELL_DP_TWO_COLUMNS,
            )
        }
    }

    /**
     * 200% 字号下**必须单列**。
     *
     * ⚠️ 两列时每格只有 148dp，而「3834.6 公里」在 200% 下要 238dp（估算口径）
     * ⇒ 两列必然把它折成「3834.6」+「公里」（数与单位分离）。
     */
    @Test
    fun `两倍字号下必须单列`() {
        assertEquals(
            "200% 下两列每格只有 148dp，「3834.6 公里」放不下 ⇒ 必须是单列",
            1,
            summaryMetricColumns(realValues(), 2.0f),
        )
    }

    /**
     * 单列时 200% 的值放得下（整宽 296dp）—— 否则「换单列」也只是把问题往后挪。
     */
    @Test
    fun `两倍字号下单列放得下`() {
        summaryMetrics(realBucket, driveDays = 22).forEach { m ->
            val needed = summaryMetricValueNeededDp(m.value, 2.0f)
            assertTrue(
                "「%s」在 200%% 下需要 %.1fdp，整宽只有 %.0fdp ⇒ 放不下"
                    .format(m.value, needed, SUMMARY_METRIC_CELL_DP_ONE_COLUMN),
                needed <= SUMMARY_METRIC_CELL_DP_ONE_COLUMN,
            )
        }
    }

    /**
     * 两列的**最大字号倍率钉死在 1.24** —— 不是「> N」那种模糊断言。
     *
     * 推导：最宽的值「3834.6 公里」= **119.0dp**（`estimateTextWidthDpWithNbsp` 口径：
     * 5 个数字 3.10em + 小数点 0.55em + 不换行空格 0.30em + 「公里」2.00em = 5.95em × 20sp）
     * ⇒ 两列每格 148dp ⇒ 148 ÷ 119.0 = **1.2437**
     * ⇒ **1.24 放得下（147.6dp ≤ 148dp）、1.25 放不下（148.8dp > 148dp）**。
     */
    @Test
    fun `两列的最大字号倍率是1_24`() {
        val values = realValues()
        assertEquals("1.24 倍下应仍是两列", 2, summaryMetricColumns(values, 1.24f))
        assertEquals("1.25 倍下必须换单列", 1, summaryMetricColumns(values, 1.25f))
    }

    /**
     * 值与单位之间必须是**不换行空格**（U+00A0）—— **逐处**检查。
     *
     * ⚠️ 普通空格是断行机会 ⇒ 放不下时贪心折行会把数与单位拆开（半个句子）。
     */
    @Test
    fun `值与单位之间是不换行空格`() {
        summaryMetrics(realBucket, driveDays = 22).forEach { m ->
            val unitAt = m.value.indexOfFirst { it.code > 127 && it.code != 0xA0 }
            assertTrue("「%s」里找不到单位".format(m.value), unitAt > 0)
            assertEquals(
                "「%s」的值与单位之间不是不换行空格 ⇒ 放不下时会被折成「%s」+「%s」"
                    .format(m.value, m.value.substring(0, unitAt), m.value.substring(unitAt)),
                NBSP,
                m.value[unitAt - 1].toString(),
            )
        }
    }

    /** 标签自己放得下（标签从不是那个「放不下」的东西）。 */
    @Test
    fun `标签在两倍字号下也放得下`() {
        summaryMetrics(realBucket, driveDays = 22).forEach { m ->
            val needed = estimateTextWidthDpWithNbsp(m.label, SUMMARY_METRIC_LABEL_SP) * 2f
            assertTrue(
                "标签「%s」在 200%% 下要 %.1fdp，每格只有 %.0fdp ⇒ 放不下"
                    .format(m.label, needed, SUMMARY_METRIC_CELL_DP_TWO_COLUMNS),
                needed <= SUMMARY_METRIC_CELL_DP_TWO_COLUMNS,
            )
        }
    }

    /** 文案逐字不变（用户看到的词一个都没变；Hermes 侧零影响）。 */
    @Test
    fun `文案逐字不变`() {
        val metrics = summaryMetrics(realBucket, driveDays = 22)
        assertEquals(
            listOf("行驶里程", "充入", "充电次数", "行驶天数"),
            metrics.map { it.label },
        )
        assertEquals("3834.6" + NBSP + "公里", metrics[0].value)
        assertEquals("674" + NBSP + "度", metrics[1].value)
        assertEquals("31" + NBSP + "次", metrics[2].value)
        assertEquals("22" + NBSP + "天", metrics[3].value)
    }

    /** 拿不到行程列表 ⇒ 退回「行程次数」（不给数总比给编的数好）。 */
    @Test
    fun `没有行程天数就退回行程次数`() {
        val metrics = summaryMetrics(realBucket, driveDays = null)
        assertEquals("行程次数", metrics[3].label)
        assertEquals("137" + NBSP + "次", metrics[3].value)
    }
}
