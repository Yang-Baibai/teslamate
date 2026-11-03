package com.teslamate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.121.0**：保养卡底部那一行 —— 原来是「基准与间隔（爱车）· 点这里修改」。
 *
 * ## 这道测试盯住什么
 *
 * ① **不许有教学语**：那一行是全 App 最后一处「点这里 / 点击」类文案
 *    （可点性由右边的**箭头图标**承担 ⇒ 文字再说一遍就是同一件事说两遍）。
 * ② **不许有括号**：用户反馈 #25 的原话「各个按钮**可以不需要括号内的那些东西**」。
 * ③ **200% 字号下放得下**：旧文案要 367.20dp > 344dp 预算 ⇒ 会被折行 / 把箭头挤出屏幕。
 *
 * ⚠️ 宽度一律用**估算器**算（estimateTextWidthDpWithNbsp），别手算。
 */
class MaintenanceBaselineLabelTest {

    @Test
    fun `有车名时是事实句`() {
        assertEquals("基准与间隔 · 爱车", maintenanceBaselineLabel("爱车"))
    }

    @Test
    fun `没有车名时不留尾巴`() {
        assertEquals("基准与间隔", maintenanceBaselineLabel(null))
        assertEquals("基准与间隔", maintenanceBaselineLabel(""))
        assertEquals("基准与间隔", maintenanceBaselineLabel("   "))
    }

    @Test
    fun `不许出现教学语`() {
        for (name in listOf("爱车", "Model 3", null)) {
            val s = maintenanceBaselineLabel(name)
            assertFalse("不该有「点这里」: " + s, s.contains("点这里"))
            assertFalse("不该有「点击」: " + s, s.contains("点击"))
            assertFalse("不该有祈使句「修改」: " + s, s.contains("修改"))
        }
    }

    @Test
    fun `不许有括号`() {
        for (name in listOf("爱车", "Model 3", null)) {
            val s = maintenanceBaselineLabel(name)
            assertFalse("不该有全角左括号: " + s, s.contains("（"))
            assertFalse("不该有全角右括号: " + s, s.contains("）"))
        }
    }

    @Test
    fun `200 字号下这一行放得下`() {
        val label = maintenanceBaselineLabel("爱车")
        val needed = maintenanceBaselineNeededDp(label, 2f)
        assertTrue(
            "「" + label + "」在 200%% 字号下要 %.0fdp > %.0fdp 预算 ⇒ 放不下"
                .format(needed, BASELINE_ROW_TEXT_DP),
            needed <= BASELINE_ROW_TEXT_DP,
        )
    }

    /** 旧文案在 200% 字号下**放不下** ⇒ 这就是本轮手术的理由（钉住它，别删）。 */
    @Test
    fun `旧文案在 200 字号下放不下`() {
        val old = "基准与间隔（爱车）· 点这里修改"
        val needed = estimateTextWidthDpWithNbsp(old, BASELINE_ROW_SP * 2f)
        assertTrue(
            "旧文案「" + old + "」在 200%% 字号下只要 %.0fdp（预算 %.0fdp）⇒ 前提不成立"
                .format(needed, BASELINE_ROW_TEXT_DP),
            needed > BASELINE_ROW_TEXT_DP,
        )
    }

    /** 正常字号下**两版都放得下** ⇒ 这一轮改的不是「100% 就坏」，是 200% 与啰嗦。 */
    @Test
    fun `正常字号下两版都放得下`() {
        val old = "基准与间隔（爱车）· 点这里修改"
        assertTrue(
            "旧文案在正常字号下放不下 ⇒ 说明它坏在大字号，不是坏在 100%",
            estimateTextWidthDpWithNbsp(old, BASELINE_ROW_SP) <= BASELINE_ROW_TEXT_DP,
        )
        assertTrue(
            "新文案在正常字号下放不下",
            maintenanceBaselineNeededDp(maintenanceBaselineLabel("爱车"), 1f) <= BASELINE_ROW_TEXT_DP,
        )
    }
}
