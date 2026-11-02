package com.teslamate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **v0.117.0**：把**文档里引用过的宽度数字**钉死。
 *
 * ⚠️ **为什么有这份测试**：v0.115.0 段里「「42 km/h」≈ 63.6dp」是**手算**的
 * （把拉丁字母按 0.55em 算），而 App 自己的估算器给的是 **69.19dp**
 * ⇒ 差 5.19dp ⇒ 差点把一个「100% 就存在的 bug」当成「运气问题」放过去。
 * ⇒ 同一轮复核还查出：「-3.5°C」是 **68.00dp**（不是我写的 79dp ⇒ 负号 0.55em
 *   比数字 0.62em 还窄 ⇒ **负温并不比正温早坏**）。
 *
 * ⇒ 这里把每个**被 `docs/CHANGELOG.md` / `docs/adr.md` 引用**的宽度数字钉成断言：
 *   估算器行为一变 ⇒ 这里立刻报红 ⇒ 逼人**同步改文档**
 *   （客户端闸门 26 会交叉核对：文档里必须出现同一个数）。
 */
class WidthBudgetPinTest {

    @Test
    fun `文档引用的宽度数字与估算器一致`() {
        // 「42 km/h」⇒ 4.07em × 17sp = 69.19dp（v0.116.0 段）
        assertEquals(69.19f, estimateTextWidthDpWithNbsp("42 km/h", 17f), 0.01f)
        // 「31.5°C」⇒ 69.19dp（v0.115.0 段）
        assertEquals(69.19f, estimateTextWidthDpWithNbsp("31.5°C", 17f), 0.01f)
        // 「-3.5°C」⇒ **68.00dp**（负号 0.55em ⇒ 比正温还窄 ⇒ v0.117.0 更正）
        assertEquals(68.00f, estimateTextWidthDpWithNbsp("-3.5°C", 17f), 0.01f)
        // 「12.3 度」⇒ 63.07dp（三等分的每格只有 64dp ⇒ 只剩 0.93dp）
        assertEquals(63.07f, estimateTextWidthDpWithNbsp("12.3 度", 17f), 0.01f)
        // 「11 kW」⇒ 48.62dp
        assertEquals(48.62f, estimateTextWidthDpWithNbsp("11 kW", 17f), 0.01f)
        // 「120.0 kW」⇒ **79.05dp**（充电功率是 Double ⇒ 恒带小数 ⇒ 超过 64dp ⇒ v0.117.0 的真洞）
        assertEquals(79.05f, estimateTextWidthDpWithNbsp("120.0 kW", 17f), 0.01f)
        // 「85%」⇒ 30.43dp（200% ⇒ 60.86dp）
        assertEquals(30.43f, estimateTextWidthDpWithNbsp("85%", 17f), 0.01f)
        // 标签「当时上限」@13sp ⇒ 52.00dp（200% ⇒ 104.00dp）
        assertEquals(52.00f, estimateTextWidthDpWithNbsp("当时上限", 13f), 0.01f)
        // 「3834.6 公里」@20sp ⇒ 119.00dp（ADR #43）
        assertEquals(119.00f, estimateTextWidthDpWithNbsp("3834.6 公里", 20f), 0.01f)
        // 「近 7 天 243.3 公里」@15sp ⇒ 132.75dp；保守口径（NBSP 按 1.0em）⇒ 164.25dp
        val week = "近" + NBSP + "7" + NBSP + "天 243.3" + NBSP + "公里"
        assertEquals(132.75f, estimateTextWidthDpWithNbsp(week, 15f), 0.01f)
        assertEquals(164.25f, estimateTextWidthDp(week, 15f), 0.01f)
        // 「今天 4.4 公里 · 15 分钟」@15sp ⇒ 172.95dp
        val today = "今天 4.4" + NBSP + "公里 · 15" + NBSP + "分钟"
        assertEquals(172.95f, estimateTextWidthDpWithNbsp(today, 15f), 0.01f)
        // 续航结论句「约 191 ~ 249 公里 · 88 条实测」@13sp ⇒ 189.93dp（200% ⇒ 379.86dp > 361dp）
        val concl = "约 191 ~ 249 公里 · 88 条实测"
        assertEquals(189.93f, estimateTextWidthDpWithNbsp(concl, 13f), 0.01f)
        assertEquals(379.86f, estimateTextWidthDpWithNbsp(concl, 26f), 0.01f)
        // 车控最长标签「方向盘加热开关」@15sp ⇒ 105.00dp（200% ⇒ 210.00dp）
        assertEquals(105.00f, estimateTextWidthDpWithNbsp("方向盘加热开关", 15f), 0.01f)
    }
}
