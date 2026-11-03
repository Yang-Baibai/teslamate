package com.teslamate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.122.0**：充电详情「实测充到 80% 用了多久」那一行。
 *
 * ## 这道测试盯住什么
 *
 * ① **标签说口径、值只放那个数** —— 不是一整句话；
 * ② **不许复活旧句式**（「用了 …」「（读自充电曲线）」这类自由句 / 括号披露）；
 * ③ **ⓘ 口径说明不许超长**（`NOTE_WIDTH_LIMIT = 160`）；
 * ④ **大字号下这一行放得下** —— 旧文案在**正常字号**就已经放不下
 *    （346.06dp > 328dp 窄屏内容区）⇒ 这就是本轮手术的理由。
 *
 * ⚠️ 宽度一律用**估算器**算（`estimateTextWidthDpWithNbsp`），别手算。
 */
class ChargeMeasuredTest {

    @Test
    fun `标签说清口径_值只放那个数`() {
        assertEquals("本次实测：68% 充到 80%", measuredTo80Label(68))
        assertEquals("36" + NBSP + "分钟", measuredTo80Value(36))
    }

    @Test
    fun `值里只有那个数`() {
        val v = measuredTo80Value(36)
        assertFalse("值里不该有「本次」: " + v, v.contains("本次"))
        assertFalse("值里不该有「充到」: " + v, v.contains("充到"))
    }

    @Test
    fun `不许复活旧句式`() {
        for (s in listOf(measuredTo80Label(68), measuredTo80Value(36))) {
            assertFalse("不该有「用了」: " + s, s.contains("用了"))
            assertFalse("不该有来源披露的全角括号: " + s, s.contains("（"))
            assertFalse("不该有来源披露的全角括号: " + s, s.contains("）"))
            assertFalse("不该有「读自充电曲线」: " + s, s.contains("读自充电曲线"))
        }
    }

    @Test
    fun `起点最大两位数时标签仍放得下`() {
        // 起点一定 < 80（服务端的 start_soc_ge_80 就是「起点已 ≥80%」）⇒ 最多两位数。
        for (soc in listOf(0, 5, 68, 79)) {
            val label = measuredTo80Label(soc)
            assertTrue(
                "「" + label + "」在 200%% 字号下要 %.0fdp > %.0fdp 预算 ⇒ 放不下"
                    .format(measuredTo80LabelNeededDp(label, 2f), MEASURED_ROW_CONTENT_DP),
                measuredTo80LabelNeededDp(label, 2f) <= MEASURED_ROW_CONTENT_DP,
            )
        }
    }

    @Test
    fun `值在大字号下也放得下`() {
        val v = measuredTo80Value(36)
        assertTrue(
            "值在 200%% 字号下要 %.0fdp > %.0fdp 预算 ⇒ 放不下"
                .format(measuredTo80ValueNeededDp(v, 2f), MEASURED_ROW_CONTENT_DP),
            measuredTo80ValueNeededDp(v, 2f) <= MEASURED_ROW_CONTENT_DP,
        )
    }

    /** 旧文案在**正常字号**下就放不下 ⇒ 这就是本轮手术的理由（钉住它，别删）。 */
    @Test
    fun `旧文案在正常字号下就放不下`() {
        val old = "本次实测：从 68% 充到 80% 用了 36 分钟（读自充电曲线）"
        val needed = estimateTextWidthDpWithNbsp(old, MEASURED_LABEL_SP)
        assertTrue(
            "旧文案在正常字号下只要 %.0fdp（预算 %.0fdp）⇒ 前提不成立"
                .format(needed, MEASURED_ROW_CONTENT_DP),
            needed > MEASURED_ROW_CONTENT_DP,
        )
    }

    @Test
    fun `口径说明不许超长`() {
        val note = noteMeasuredTo80()
        assertTrue(
            "ⓘ 口径说明 %d 视觉宽度 > %d ⇒ 点开就是一堵字墙"
                .format(noteVisualWidth(note), NOTE_WIDTH_LIMIT),
            noteVisualWidth(note) <= NOTE_WIDTH_LIMIT,
        )
    }
}
