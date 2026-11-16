package com.teslamate.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * ⚠️ **v0.166.0**：**实心琥珀底上的文字**必须读得清。
 *
 * ## 背景
 *
 * 车控页的主角 CTA「解锁车门」是**一整块实心琥珀**，而主题里 `warnAmber` 是**图形色**
 * （画图形 ≥3:1 即可）⇒ 配黑字出来是「一团泥 + 几乎读不出」（2026-11-17 模拟器实拍）。
 * ⇒ 新增 `onWarnAmber`（实心底色上的文字色），并把对比度**钉成数字**
 * （浅色白字 / 深色近黑字，两套主题都要 ≥4.5:1）。
 *
 * ⚠️ 本项目**不做无障碍专项**（ADR #76）—— 这里量的是「看得清 / 看不看得清」，
 *   是一块**按钮的基本可读性**，不是无障碍达标工作。
 */
class ThemeContrastTest {

    /** 相对亮度（WCAG 定义）。 */
    private fun luminance(argb: Int): Double {
        fun ch(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = ch((argb shr 16) and 0xFF)
        val g = ch((argb shr 8) and 0xFF)
        val b = ch(argb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** 对比度（WCAG 定义）。 */
    private fun contrast(fg: Int, bg: Int): Double {
        val l1 = luminance(fg)
        val l2 = luminance(bg)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    // ⚠️ **不许**用 Color.value 当 ARGB —— 它不是 ARGB（实测取出来的值算出来是 1.00:1）。
    private fun argb(c: Color): Int = c.toArgb()

    @Test
    fun `实心琥珀底上的文字_两个主题都要读得清`() {
        val light = contrast(argb(LightAccents.onWarnAmber), argb(LightAccents.warnAmber))
        val dark = contrast(argb(DarkAccents.onWarnAmber), argb(DarkAccents.warnAmber))
        assertTrue(
            "浅色主题：实心琥珀底上的文字只有 %.2f:1（须 ≥4.5）" +
                "（字 #%06X / 底 #%06X）".format(
                    argb(LightAccents.onWarnAmber), argb(LightAccents.warnAmber), light),
            light >= 4.5
        )
        assertTrue(
            "深色主题：实心琥珀底上的文字只有 %.2f:1（须 ≥4.5）" +
                "（字 #%06X / 底 #%06X）".format(
                    argb(DarkAccents.onWarnAmber), argb(DarkAccents.warnAmber), dark),
            dark >= 4.5
        )
    }

    @Test
    fun `旧的黑字配琥珀底读不出来_把这个数字留作证据`() {
        val old = contrast(argb(Color.Black), argb(LightAccents.warnAmber))
        assertTrue(
            "旧配色（黑字压实心琥珀底）实测 %.2f:1 ⇒ 这就是它看着像一团泥的原因".format(old),
            old < 4.5
        )
    }

    @Test
    fun `两个主题的onWarnAmber不许是同一个值`() {
        // 浅色底是深琥珀 ⇒ 白字；深色底是亮琥珀 ⇒ 近黑字 ⇒ 同一个值必有一边读不清。
        assertTrue(
            "浅色与深色用了同一个 onWarnAmber ⇒ 有一边会读不清",
            LightAccents.onWarnAmber != DarkAccents.onWarnAmber
        )
    }
}