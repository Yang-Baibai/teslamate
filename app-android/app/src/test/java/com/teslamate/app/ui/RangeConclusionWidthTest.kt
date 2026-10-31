package com.teslamate.app.ui

import org.junit.Assert.assertEquals
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
}
