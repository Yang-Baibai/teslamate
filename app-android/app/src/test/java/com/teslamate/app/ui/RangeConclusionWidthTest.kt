package com.teslamate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.95.0**：续航结论区**必须整宽**。
 *
 * ## 盯住什么
 *
 * 结论行「约 191 ~ 249 公里 · 88 条实测」原来渲染在电量环右侧的**窄栏**里
 * （真机实测约 171dp），而它需要约 152dp，加 ⓘ 徽标那一行的 20dp
 * ⇒ **放不下** ⇒ 贪心折行把「88 条实测」劈成「88 条实」+「测」
 * （真机截图；v0.92.0 记下，v0.93.0 / v0.94.0 顺延到 v0.95.0 修掉）。
 *
 * CJK 允许在**任意两个汉字之间**断行 ⇒ 窄栏里**没有任何写法**能保证不被劈开，
 * 而且窄栏宽度**随机型与字号变化** ⇒ 「刚好放得下」是运气，不是结构。
 *
 * ⇒ 结构性解法：**结论区整宽渲染**（界面约束由 `scripts/client-gates.sh`
 * 第 11 道闸门守住；这里钉的是**宽度预算**本身）。
 */
class RangeConclusionWidthTest {

    /** 区间与来源自曝**两个半句都在** —— 数据一点没丢。 */
    @Test
    fun `结论行的两个半句都在`() {
        assertEquals(
            "约 191 ~ 249 公里 · 88 条实测",
            rangeConclusionLine("约 191 ~ 249 公里", "88 条实测"),
        )
    }

    /** 没有区间时（数据不足），来源自曝仍然要自己站一行。 */
    @Test
    fun `没有区间时只剩来源自曝`() {
        assertEquals("88 条实测", rangeConclusionLine(null, "88 条实测"))
    }

    /** 两边都没有 ⇒ 什么都不渲染（不是空字符串）。 */
    @Test
    fun `什么都没有就不渲染`() {
        assertEquals(null, rangeConclusionLine(null, null))
    }

    /** 窄栏放不下 ⇒ 这就是「必须整宽」的全部理由。 */
    @Test
    fun `窄栏放不下结论行`() {
        val line = rangeConclusionLine("约 191 ~ 249 公里", "88 条实测")!!
        val needed = rangeConclusionNeededDp(line)
        assertTrue(
            "结论行需要 %.0fdp（含 ⓘ 徽标），窄栏只有 %.0fdp ⇒ 窄栏放不下，必须整宽"
                .format(needed, NARROW_COLUMN_DP),
            needed > NARROW_COLUMN_DP,
        )
    }

    /** 整宽放得下 ⇒ 这次的结构性解法是对的。 */
    @Test
    fun `整宽放得下结论行`() {
        val line = rangeConclusionLine("约 191 ~ 249 公里", "88 条实测")!!
        val needed = rangeConclusionNeededDp(line)
        assertTrue(
            "结论行需要 %.0fdp（含 ⓘ 徽标），整宽只有 %.0fdp ⇒ 连整宽都放不下，得再想办法"
                .format(needed, FULL_WIDTH_DP),
            needed <= FULL_WIDTH_DP,
        )
    }

    // ── v0.114.0：大字号下**整宽也放不下** ⇒ 必须拆成完整短语 ──────────
    //
    // ⚠️ 旧断言只问「整宽放得下吗」（而且用的是 12sp 的旧默认字号）⇒
    // **200% 下要 ≈400dp 这个事实从来没人钉过** ⇒
    // 「88 条实测」被劈成「88 条实」+「测」那个坑（v0.94.0）在整宽布局里**又回来了**，
    // 而单测是绿的。
    //
    // ⚠️ 这正是 v0.98.0 的教训：**只钉「有没有超过 N」⇒ 511 分钟也放行**。
    // ⇒ 这里钉**具体数值**。

    /** 200% 字号下整宽**放不下**（钉具体数值）。 */
    @Test
    fun `200 字号下整宽也放不下结论行`() {
        val text = rangeConclusionText(rangeBandText(191, 249), rangeScopeText(88))!!
        val needed = rangeConclusionNeededDp(text.joined, RANGE_CONCLUSION_SP * 2f)
        assertTrue(
            "200%% 字号下结论行需要 %.0fdp，整宽只有 %.0fdp ⇒ 必须拆行（不然「88 条实测」会被劈开）"
                .format(needed, FULL_WIDTH_DP),
            needed > FULL_WIDTH_DP,
        )
    }

    /** 正常字号下**不拆** —— 现在的样子（区间与来源并成一行）保持不变。 */
    @Test
    fun `正常字号下不拆行`() {
        val text = rangeConclusionText(rangeBandText(191, 249), rangeScopeText(88))!!
        assertFalse(
            "正常字号下不该拆行（并成一行才是现在的样子）",
            rangeConclusionNeedsSplit(text, RANGE_CONCLUSION_SP),
        )
    }

    /** 大字号下**要拆**，且拆开的两块**每一块都放得下** ⇒ 拆完不会再折行。 */
    @Test
    fun `大字号下拆行且每块都放得下`() {
        val text = rangeConclusionText(rangeBandText(191, 249), rangeScopeText(88))!!
        val fs = RANGE_CONCLUSION_SP * 2f
        assertTrue("200% 字号下应该拆行", rangeConclusionNeedsSplit(text, fs))
        assertEquals("拆成两块：区间 + 来源自曝", 2, text.parts.size)
        for (p in text.parts) {
            val need = rangeConclusionPartNeededDp(p, fs)
            assertTrue(
                "拆开后的「%s」需要 %.0fdp > %.0fdp ⇒ 它自己还会被折行（那就没解决）"
                    .format(p.replace(NBSP, " "), need, FULL_WIDTH_DP),
                need <= FULL_WIDTH_DP,
            )
        }
    }

    /** 拆开的两块**都是完整短语**（不是半句话）。 */
    @Test
    fun `拆开的两块都是完整短语`() {
        val text = rangeConclusionText(rangeBandText(191, 249), rangeScopeText(88))!!
        assertEquals(
            listOf("约 191 ~ 249 公里", "88 条实测"),
            text.parts.map { it.replace(NBSP, " ") },
        )
    }

    /** 文案**逐字不变**：不换行空格渲染出来就是一个空格 ⇒ 可见文字与改造前完全一致。 */
    @Test
    fun `文案逐字不变`() {
        assertEquals("约 191 ~ 249 公里", rangeBandText(191, 249).replace(NBSP, " "))
        assertEquals("88 条实测", rangeScopeText(88).replace(NBSP, " "))
    }

    /** 数与单位之间**必须**用不换行空格绑住（ADR #42 第三条）。 */
    @Test
    fun `数与单位之间用不换行空格绑住`() {
        assertTrue(
            "区间文案里「249」与「公里」之间必须是不换行空格",
            rangeBandText(191, 249).contains("249${NBSP}公里"),
        )
        assertTrue(
            "来源文案里「88」与「条」之间必须是不换行空格",
            rangeScopeText(88).contains("88${NBSP}条"),
        )
    }
}
