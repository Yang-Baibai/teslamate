package com.teslamate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **v0.115.0**：车况页那些 **InfoTile 行**的宽度预算。
 *
 * ## 盯住什么
 *
 * 那些格子原来**并排写死**（`Row` + 固定 `weight(1f)`），每格的值放不下时靠
 * `maxLines = 2` + `Ellipsis` 硬扛 ⇒ **读数被裁掉**。
 *
 * | 行 | 格数 | 内容区 | 每格文字区 | 最宽的值 | 100% | 200% |
 * |---|---|---|---|---|---|---|
 * | 页面级（空调 / 车内温度、车外温度 / 功率） | 2 | 328dp | **130dp** | 「31.5°C」 | 69.19dp ✓ | **138.38dp ✗** |
 * | 卡片里（**v0.118.0 起两段**） | 2 + 1 | 296dp | **115dp + 268dp** | 「100.0 度」 | 73.61dp ✓ | 147.22dp ✓ |
 *
 * ⚠️ **v0.118.0**：卡片里那一行（充到 / 当时上限 / 充入）原来也是**三格并排**
 * （每格只有 64dp）⇒ 而「充入」是**度数**：「100.0 度」= **73.61dp** ⇒ **100% 下就放不下**
 * ⇒ 现在「充到 / 当时上限」并排（115dp）＋「充入」独占一行整宽（268dp）
 * ⇒ **卡片里再没有三格并排的行** ⇒ 也不会再有「0.93dp 余量上走钢丝」的排布。
 *
 * ⚠️ **字号口径**：值用 `titleMedium` = **17sp**、标签用 `labelMedium` = **13sp**
 * （App 覆盖了整套排版，`ui/Theme.kt`；⚠️ 别拿 M3 默认值算）。
 */
class InfoTileRowWidthTest {

    /** 页面级那两行（空调 / 车内温度、车外温度 / 功率）。 */
    private val pageTitles = listOf("空调", "车内温度")
    private val pageValues = listOf("开启", "31.5°C")

    /**
     * ⚠️ **v0.118.0**：卡片里那一行现在分成**两段** ——
     * 「充到 / 当时上限」并排一行（每格 **115dp**）＋「充入」独占一行整宽（**268dp**）。
     */
    private val cardPairTitles = listOf("充到", "当时上限")
    private val cardPairValues = listOf("100%", "80%")

    /** 卡片里那两格并排时的文字区（dp）：(296 − 10) ÷ 2 − 28。 */
    private val cardPairTextDp =
        (INFO_TILE_CARD_CONTENT_DP - INFO_TILE_GAP_CARD_DP) / 2 - INFO_TILE_PADDING_H_DP

    /**
     * 正常字号下**并排** —— 视觉与改之前一模一样。
     */
    @Test
    fun `正常字号下并排`() {
        assertEquals(
            "页面级那两格在 100%% 下不该拆成单列（视觉会变）",
            2,
            infoTileColumns(pageTitles, pageValues, 1.0f, INFO_TILE_PAGE_CONTENT_DP, INFO_TILE_GAP_PAGE_DP),
        )
        assertEquals(
            "卡片里那两格（充到 / 当时上限）在 100%% 下不该拆成单列（视觉会变）",
            2,
            infoTileColumns(cardPairTitles, cardPairValues, 1.0f, INFO_TILE_CARD_CONTENT_DP, INFO_TILE_GAP_CARD_DP),
        )
    }

    /**
     * 200% 字号下：页面级那两格**必须单列**，而卡片里那两格（v0.118.0 起）**仍并排**。
     *
     * ⚠️ 页面级：「31.5°C」要 **138.38dp** > 130dp ⇒ 单列；
     * ⚠️ 卡片里：「当时上限」标签在 200% 下要 **104dp** ≤ 115dp ⇒ 并排放得下。
     */
    @Test
    fun `两倍字号下页面级单列而卡片里那两格仍并排`() {
        assertEquals(
            "200%% 下「31.5°C」放不下 130dp ⇒ 必须单列",
            1,
            infoTileColumns(pageTitles, pageValues, 2.0f, INFO_TILE_PAGE_CONTENT_DP, INFO_TILE_GAP_PAGE_DP),
        )
        assertEquals(
            "200%% 下标签「当时上限」要 104dp ≤ 115dp ⇒ 仍并排",
            2,
            infoTileColumns(cardPairTitles, cardPairValues, 2.0f, INFO_TILE_CARD_CONTENT_DP, INFO_TILE_GAP_CARD_DP),
        )
    }

    /**
     * ⚠️ **v0.118.0**：「充入」独占一行整宽（268dp）⇒ 一次充 100 度也放得下。
     *
     * ⚠️ **历史（已弃用的三格排布）**：每格只有 64dp，
     * 而「100.0 度」= **73.61dp** ⇒ **100% 下就放不下** ⇒ 整行会塌成三行单列
     * ⇒ 这正是「充入」必须独占一行的理由（下面把它钉住）。
     */
    @Test
    fun `充入独占一行所以100度也放得下`() {
        val wide = "100.0" + NBSP + "度"
        val textDp = INFO_TILE_CARD_CONTENT_DP - INFO_TILE_PADDING_H_DP
        // ⚠️ **钉死具体数值**，别只写「放得下」—— 整宽有 268dp，
        //    任何像样的字符串都放得下 ⇒ 「放得下」这条断言**近乎恒真**（什么也没测到）。
        assertEquals("「100.0 度」@17sp 的宽度（估算器）", 73.61f, infoTileValueNeededDp(wide, 1.0f), 0.01f)
        assertEquals("「100.0 度」在 200% 下的宽度", 147.22f, infoTileValueNeededDp(wide, 2.0f), 0.01f)
        assertTrue(
            "整宽文字区 %.0fdp ⇒ 200%% 下 147.2dp 放得下（这是「独占一行」的意义）".format(textDp),
            infoTileValueNeededDp(wide, 2.0f) <= textDp,
        )
        // ⚠️ 已弃用的三格排布：100% 下就放不下 ⇒ 必须拆行（这就是 v0.118.0 的理由）
        assertEquals(
            "三格并排（已弃用）在 100%% 下放不下「100.0 度」⇒ 整行会塌成三行",
            1,
            infoTileColumns(
                listOf("充到", "当时上限", "充入"),
                listOf("100%", "80%", wide),
                1.0f, INFO_TILE_CARD_CONTENT_DP, INFO_TILE_GAP_CARD_DP,
            ),
        )
    }

    /**
     * ⚠️ **v0.117.0 更正**：负温**不比**正温早坏 ——
     * 「-3.5°C」按估算器要 **68.00dp**（负号 0.55em，**比数字 0.62em 还窄**）
     * ⇒ 反而比「31.5°C」的 69.19dp **更窄**。
     * ⇒ 页面级那两格（每格 130dp）：**190% 还并排**，**200% 才单列**。
     *
     * ⚠️ **上一轮这条断言是假绿**：它传的是**只有一格**的列表，
     * 而 `infoTileColumns` 对单格**永远**返回 1 ⇒ 断言恒成立、什么也没测到。
     * ⇒ 现在用**真实的两格行**来测（空调 + 车内温度）。
     */
    @Test
    fun `页面级那两格到190还并排`() {
        val titles = listOf("空调", "车内温度")
        val values = listOf("开启", "-3.5°C")
        assertEquals(
            "190%% 下「-3.5°C」要 129.2dp ≤ 130dp ⇒ 还并排",
            2,
            infoTileColumns(titles, values, 1.9f, INFO_TILE_PAGE_CONTENT_DP, INFO_TILE_GAP_PAGE_DP),
        )
        assertEquals(
            "200%% 下「-3.5°C」要 136.0dp > 130dp ⇒ 必须单列",
            1,
            infoTileColumns(titles, values, 2.0f, INFO_TILE_PAGE_CONTENT_DP, INFO_TILE_GAP_PAGE_DP),
        )
    }

    /**
     * 单列时 **200%** 也放得下 —— 否则「换单列」只是把问题往后挪。
     */
    @Test
    fun `单列时两倍字号放得下`() {
        listOf(
            "31.5°C" to INFO_TILE_PAGE_CONTENT_DP,
            "-3.5°C" to INFO_TILE_PAGE_CONTENT_DP,
            "12.3 度" to INFO_TILE_CARD_CONTENT_DP,
            "80%" to INFO_TILE_CARD_CONTENT_DP,
        ).forEach { (value, contentDp) ->
            val textDp = contentDp - INFO_TILE_PADDING_H_DP
            val needed = infoTileValueNeededDp(value, 2.0f)
            assertTrue(
                "「%s」在 200%% 下要 %.1fdp，单列文字区只有 %.0fdp ⇒ 放不下"
                    .format(value, needed, textDp),
                needed <= textDp,
            )
        }
    }

    /**
     * **标签**也要放得下 —— 「当时上限」4 个字在 200% 下要 104dp，
     * 比三格那行的 64dp 宽 ⇒ 标签自己就会折行（值放得下、标签放不下 ⇒ 一样坏）。
     */
    @Test
    fun `标签也要放得下`() {
        val needed = infoTileTitleNeededDp("当时上限", 2.0f)
        assertTrue(
            "标签「当时上限」在 200%% 下要 %.1fdp，单列文字区只有 %.0fdp ⇒ 放不下"
                .format(needed, INFO_TILE_CARD_CONTENT_DP - INFO_TILE_PADDING_H_DP),
            needed <= INFO_TILE_CARD_CONTENT_DP - INFO_TILE_PADDING_H_DP,
        )
        assertTrue(
            "标签在 100%% 下（并排）也要放得下（v0.118.0 起那行是两格，预算 115dp）",
            infoTileTitleNeededDp("当时上限", 1.0f) <= cardPairTextDp,
        )
    }

    /** 只有一格时 ⇒ 单列（整宽）—— 与改之前的 `weight(1f)` 单子项一致。 */
    @Test
    fun `只有一格就是单列`() {
        assertEquals(
            1,
            infoTileColumns(listOf("车外温度"), listOf("31.5°C"), 1.0f, INFO_TILE_PAGE_CONTENT_DP, INFO_TILE_GAP_PAGE_DP),
        )
    }

    /**
     * **数与单位之间是不换行空格** —— 普通空格是断行机会 ⇒ 数与单位会分离。
     *
     * ⚠️ 功率是 `Int?`（`st.power` / `c.charger_power_kw`）⇒ 渲染成「11 kW」，
     * **不是**「11.0 kW」⇒ 这里传 `11`（Int）而不是 `11.0`（Double）。
     */
    @Test
    fun `数与单位之间是不换行空格`() {
        assertEquals("12.3" + NBSP + "度", infoTileKwhValue(12.34))
        assertEquals("11" + NBSP + "kW", infoTileKwValue(11))
        assertEquals("—", infoTileKwhValue(null))
        assertEquals("—", infoTileKwValue(null))
        assertTrue(
            "「12.3 度」里还有普通空格 ⇒ 折行会把数与单位拆开",
            !infoTileKwhValue(12.34).contains(' '),
        )
    }

    /** 文案逐字不变（NBSP 渲染出来就是一个空格 ⇒ 用户看到的还是「12.3 度」）。 */
    @Test
    fun `文案逐字不变`() {
        assertEquals("12.3 度", infoTileKwhValue(12.34).replace(NBSP, " "))
        assertEquals("11 kW", infoTileKwValue(11).replace(NBSP, " "))
    }

    // ── v0.116.0：「行驶中」那一行（车速 / 功率 / 电量）────────────────────────
    //
    // ⚠️ **上一轮记的「42 km/h ≈ 63.6dp」是手算**（把拉丁字母按 0.55em 算）；
    // App 自己的估算器给的是 4.07em × 17sp = **69.19dp**
    // ⇒ 三格并排时每格只有 **64dp** ⇒ **100% 就已经放不下**
    // ⇒ 值会被折成「42」/「km/h」（数与单位分离）。
    // ⇒ 现在「车速 / 功率」两格并排（每格 115dp）+「电量」独占一行整宽（268dp）。

    /** 「车速 / 功率」两格：100% 与 150% 并排，170% 必须单列。 */
    @Test
    fun `行驶中那两格在正常字号下并排`() {
        val titles = listOf("车速", "功率")
        val values = listOf(infoTileSpeedValue(42), infoTileKwValue(11))
        assertEquals(
            "100%% 下「42 km/h」要 69.2dp ≤ 115dp ⇒ 必须并排",
            2,
            infoTileColumns(titles, values, 1.0f, INFO_TILE_CARD_CONTENT_DP, INFO_TILE_GAP_CARD_DP),
        )
        assertEquals(
            "150%% 下「42 km/h」要 103.8dp ≤ 115dp ⇒ 还是并排",
            2,
            infoTileColumns(titles, values, 1.5f, INFO_TILE_CARD_CONTENT_DP, INFO_TILE_GAP_CARD_DP),
        )
        assertEquals(
            "170%% 下「42 km/h」要 117.6dp > 115dp ⇒ 必须单列",
            1,
            infoTileColumns(titles, values, 1.7f, INFO_TILE_CARD_CONTENT_DP, INFO_TILE_GAP_CARD_DP),
        )
    }

    /** 「电量」那一格独占一行 ⇒ 整宽（268dp）⇒ 200% 也放得下。 */
    @Test
    fun `电量那格独占一行就是整宽`() {
        assertEquals(
            "只有一格 ⇒ 单列（整宽）",
            1,
            infoTileColumns(listOf("电量"), listOf("85%"), 2.0f, INFO_TILE_CARD_CONTENT_DP, INFO_TILE_GAP_CARD_DP),
        )
        val needed = infoTileValueNeededDp("85%", 2.0f)
        assertTrue(
            "「85%%」在 200%% 下要 %.1fdp，整宽文字区只有 %.0fdp ⇒ 放不下"
                .format(needed, INFO_TILE_CARD_CONTENT_DP - INFO_TILE_PADDING_H_DP),
            needed <= INFO_TILE_CARD_CONTENT_DP - INFO_TILE_PADDING_H_DP,
        )
    }

    /**
     * **v0.117.0**：「充电中」那两格（功率 / 已充入）。
     *
     * ⚠️ 功率是 **Double** ⇒ 恒带小数（「120.0 kW」= **79.05dp**）
     * ⇒ 三格并排时每格只有 64dp ⇒ **放不下** ⇒ 那一行**永远**塌成三行单列。
     * ⇒ 现在两格并排（每格 115dp）⇒ 100%~140% 都并排、150% 起单列。
     */
    @Test
    fun `充电中那两格在正常字号下并排`() {
        val titles = listOf("功率", "已充入")
        val values = listOf(infoTileKwValue(120.0), infoTileKwhValue(12.34))
        assertEquals(
            "100%% 下「120.0 kW」要 79.1dp ≤ 115dp ⇒ 必须并排",
            2,
            infoTileColumns(titles, values, 1.0f, INFO_TILE_CARD_CONTENT_DP, INFO_TILE_GAP_CARD_DP),
        )
        assertEquals(
            "140%% 下「120.0 kW」要 110.7dp ≤ 115dp ⇒ 还是并排",
            2,
            infoTileColumns(titles, values, 1.4f, INFO_TILE_CARD_CONTENT_DP, INFO_TILE_GAP_CARD_DP),
        )
        assertEquals(
            "150%% 下「120.0 kW」要 118.6dp > 115dp ⇒ 必须单列",
            1,
            infoTileColumns(titles, values, 1.5f, INFO_TILE_CARD_CONTENT_DP, INFO_TILE_GAP_CARD_DP),
        )
    }

    /**
     * ⚠️ **钉住那个真洞**：三格并排时「120.0 kW」放不下 64dp
     * ⇒ `infoTileColumns` 永远判单列 ⇒ 那张卡永远高 3 倍。
     * ⇒ 这条断言是**修法的前提**（不是修法本身 ⇒ 修法由界面代码与闸门 25 钉）。
     */
    @Test
    fun `三格并排放不下充电功率`() {
        assertEquals(
            "100%% 下三格并排时每格只有 64dp，而「120.0 kW」要 79.1dp ⇒ 必须单列",
            1,
            infoTileColumns(
                listOf("功率", "已充入", "当前电量"),
                listOf(infoTileKwValue(120.0), infoTileKwhValue(12.34), "85%"),
                1.0f,
                INFO_TILE_CARD_CONTENT_DP,
                INFO_TILE_GAP_CARD_DP,
            ),
        )
    }

    /** 车速的数与单位绑住（NBSP）—— 普通空格是断行机会 ⇒ 会把数与单位拆开。 */
    @Test
    fun `车速的数与单位绑住`() {
        assertEquals("42" + NBSP + "km/h", infoTileSpeedValue(42))
        assertEquals("—", infoTileSpeedValue(null))
        assertTrue(
            "「42 km/h」里还有普通空格 ⇒ 折行会把数与单位拆开",
            !infoTileSpeedValue(42).contains(' '),
        )
        assertEquals("42 km/h", infoTileSpeedValue(42).replace(NBSP, " "))
    }
}
