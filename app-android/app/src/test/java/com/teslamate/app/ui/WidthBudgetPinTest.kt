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

        // ⚠️ **v0.118.0**：下面这几条是「把文档里没核过的宽度数字补核完」的结果 ——
        // **宽度数字必须连「口径」一起记**：同一个字符串，乐观口径（NBSP 按 0.30em）
        // 与保守口径（NBSP 按 1.0em）能差 30dp+ ⇒ 只写一个数不说口径 ⇒ 后人会当成同一个数。
        // 「100.0 度」@17sp ⇒ 73.61dp（没有不换行空格 ⇒ 两套口径同值）
        assertEquals(73.61f, estimateTextWidthDpWithNbsp("100.0 度", 17f), 0.01f)
        // 「近 7 天 243.3 公里 · 4 天有行程」@15sp ⇒ 乐观 230.55dp / 保守 262.05dp
        //   （3 个不换行空格 × 0.70em × 15sp = 31.5dp ⇒ 两套口径差这么多）
        val weekLong = "近" + NBSP + "7" + NBSP + "天 243.3" + NBSP + "公里 · 4 天有行程"
        assertEquals(230.55f, estimateTextWidthDpWithNbsp(weekLong, 15f), 0.01f)
        assertEquals(262.05f, estimateTextWidthDp(weekLong, 15f), 0.01f)
        // 「今天 4.4 公里 · 15 分钟」@15sp ⇒ 乐观 172.95dp / 保守 193.95dp（2 个不换行空格）
        assertEquals(193.95f, estimateTextWidthDp(today, 15f), 0.01f)
        // 「17.4 kWh/100km」@13sp ⇒ 乐观 109.46dp / 保守 118.56dp
        //   （v0.115.0 段那张表写的 119dp = 保守口径的取整 ⇒ 不是手算错，是口径不同）
        val kwh100 = "17.4" + NBSP + "kWh/100km"
        assertEquals(109.46f, estimateTextWidthDpWithNbsp(kwh100, 13f), 0.01f)
        assertEquals(118.56f, estimateTextWidthDp(kwh100, 13f), 0.01f)
    }

    /**
     * ⚠️ **v0.120.0**：首屏排布（环 + 主角并排 / 上下叠）的宽度预算。
     *
     * 口径：**乐观**（[estimateTextWidthDpWithNbsp]，不换行空格按 0.30em）。
     * ⚠️ 这两个串里**没有**不换行空格 ⇒ **保守口径同值**（不是两个不同的数）。
     *
     * ⇒ 「204」@44sp = 1.86em × 44sp = **81.84dp**
     * ⇒ 「现在能开（公里）」@13sp = 8em × 13sp = **104.00dp**
     *   （**绑定项是标签**，不是数字 ⇒ 排布由它决定）
     */
    @Test
    fun `v0_120_0_首屏排布的宽度数字`() {
        assertEquals(81.84f, estimateTextWidthDpWithNbsp("204", 44f), 0.01f)
        assertEquals(104.00f, estimateTextWidthDpWithNbsp("现在能开（公里）", 13f), 0.01f)
        // 保守口径对这两个串**同值**（没有不换行空格）
        assertEquals(81.84f, estimateTextWidthDp("204", 44f), 0.01f)
        assertEquals(104.00f, estimateTextWidthDp("现在能开（公里）", 13f), 0.01f)
    }
}
