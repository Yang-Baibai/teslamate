package com.teslamate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.109.0**：车控按钮网格的**列数**规则 + **宽度预算**（纯逻辑，可单测）。
 *
 * ## 为什么要有它
 *
 * 用户反馈 #25 的诉求「把文字的布局尽量统一」在**正常字号**下已解决（v0.107.0 的短标签），
 * 但 **200% 字号**下 7 个全角字放不进两列按钮的 140dp 文字区 ⇒ 仍会折行。
 * ⇒ 规则是「大字号改单列」；而**这条规则靠数字成立**，不能只写在注释里。
 *
 * ## 三条断言
 *
 * ① **阈值**：1.0 / 1.2 ⇒ 两列；1.3 / 2.0 ⇒ 单列。
 * ② **两列在 200% 下放不下最长标签**（否则就不必换列 ⇒ 规则没有依据）。
 * ③ **单列在 200% 下放得下**（否则换列也救不了 ⇒ 规则是错的）。
 *
 * ⚠️ 宽度用**真实 dp**算（`estimateTextWidthDp`），字号取**主题里实际覆盖**的
 * `bodyMedium = 15.sp`（**不是**框架默认的 14sp —— 本 App 覆盖过整套排版）。
 */
class CommandGridTest {

    /** 服务端目录里**最长**的短标签（7 个全角字）。 */
    private val longestLabel = "方向盘加热开关"

    /** `ui/Theme.kt` 里**实际覆盖**的 `bodyMedium`（不是框架默认 14sp）。 */
    private val bodyMediumSp = 15f

    @Test
    fun `正常字号两列 大字号单列`() {
        assertEquals(2, actionGridColumns(1.0f))
        assertEquals(2, actionGridColumns(1.2f))
        assertEquals(1, actionGridColumns(1.3f))
        assertEquals(1, actionGridColumns(2.0f))
    }

    @Test
    fun `两列在两倍字号下放不下最长标签`() {
        val at200 = bodyMediumSp * 2f
        assertTrue(
            "两列时最长标签 ${estimateTextWidthDp(longestLabel, at200)}dp " +
                "必须放不下 ${ACTION_GRID_TEXT_DP_TWO_COLUMNS}dp —— " +
                "放得下就不必换列，这条规则就没有依据",
            estimateTextWidthDp(longestLabel, at200) > ACTION_GRID_TEXT_DP_TWO_COLUMNS
        )
    }

    @Test
    fun `单列在两倍字号下放得下最长标签`() {
        val at200 = bodyMediumSp * 2f
        assertTrue(
            "单列时最长标签 ${estimateTextWidthDp(longestLabel, at200)}dp " +
                "必须放得下 ${ACTION_GRID_TEXT_DP_ONE_COLUMN}dp —— " +
                "放不下就说明换列也救不了（规则错了）",
            estimateTextWidthDp(longestLabel, at200) <= ACTION_GRID_TEXT_DP_ONE_COLUMN
        )
    }

    @Test
    fun `正常字号两列就够用 不必换列`() {
        assertTrue(
            "正常字号下最长标签必须放得下 ${ACTION_GRID_TEXT_DP_TWO_COLUMNS}dp",
            estimateTextWidthDp(longestLabel, bodyMediumSp) <= ACTION_GRID_TEXT_DP_TWO_COLUMNS
        )
    }
}