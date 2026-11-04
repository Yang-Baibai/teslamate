package com.teslamate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.126.0**：能耗-车速曲线卡那两行对照数据的文案。
 *
 * 这两行是**同一类数据**（某个速度档 ＋ 你跑了多少公里［对照行还多一个能耗］）
 * ⇒ 必须**同形**：档位 · 公里（对照行多一个能耗）。
 *
 * ① 「跑了」这个动词**只有一行有** ⇒ 读者要在两行之间换一次阅读方式；
 * ② 标签与数据用**冒号**分隔，而全 App 的并列项一律用「·」（ADR #50）。
 */
class EnergyProfileTest {

    @Test
    fun `最省电那一行与对照行同形`() {
        assertEquals("100–110 km/h · 1234 公里", bestBandLine("100–110", 1234))
        // 没有里程时只给档位（原来也是这样：不写「· 跑了 0 公里」）
        assertEquals("130+ km/h", bestBandLine("130+", 0))
    }

    @Test
    fun `对照行的标签与数据用间隔号`() {
        // 187.0 Wh/km ⇒ 显示 18.7 kWh/100km（显示层 ÷10）
        assertEquals(
            "跑得最多的档 · 130+ km/h · 1156 公里 · 18.7 kWh/100km",
            mostDrivenBandLine("130+", 1156, 187.0),
        )
    }

    @Test
    fun `两行都不许有冒号与只有一行的动词`() {
        val lines = listOf(bestBandLine("100–110", 1234), mostDrivenBandLine("130+", 1156, 187.0))
        for (s in lines) {
            assertFalse("不该有全角冒号: " + s, s.contains("："))
            assertFalse("不该有只有一行才有的动词「跑了」: " + s, s.contains("跑了"))
            assertTrue("两行都该是数据行: " + s, s.contains(" · "))
        }
    }
}
