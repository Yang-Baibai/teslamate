package com.teslamate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.130.0**：充电列表页「只列最近 N 条」那一行的文案。
 *
 * 同一张卡里的邻居都是「· 平行并列」⇒ 这一行也得是（ADR #50）。
 */
class ChargeListCapTest {

    @Test
    fun `共几次用间隔号而不是括号`() {
        assertEquals("只列最近 100 条 · 共 128 次", chargeListCapLine(100, 128))
    }

    @Test
    fun `不许再用括号把并列信息降级`() {
        val s = chargeListCapLine(100, 128)
        assertFalse("不该有全角左括号: " + s, s.contains("（"))
        assertFalse("不该有全角右括号: " + s, s.contains("）"))
        assertTrue("该是「· 平行并列」: " + s, s.contains(" · "))
    }
}
