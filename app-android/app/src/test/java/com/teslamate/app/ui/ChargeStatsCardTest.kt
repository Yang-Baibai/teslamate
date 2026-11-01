package com.teslamate.app.ui

import com.teslamate.app.ChargeStatsRange
import com.teslamate.app.ServerZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ⚠️ **v0.108.0（用户反馈 #27）**：充电记录页顶部统计卡的**区间**护栏。
 *
 * 用户原话（真机截图 `media/feedback/27.jpg`）：
 *
 * 	「充电记录顶上这个统计，需要可以手动切换范围，比如近 7 天，近 30 天，全部。」
 *
 * 三件事钉死：
 *
 *  ① **区间键与顺序**（服务端 `/charge_stats` 的 `ranges` 顺序 = 分段控件顺序）；
 *  ② **边界行的格式**（近 N 天显示窗口起止；全部显示数据首尾）；
 *  ③ **200% 字号下标签放得下**（这是把「近 30 天」改成「30 天」的理由）。
 *
 * ⚠️ 数字**全部来自服务端**（`/charge_stats`）—— 客户端一个都不自己算：
 * 区间口径只有一份、「全部」是真全表、与下面的列表同源。
 */
class ChargeStatsCardTest {

    /** 固定服务端时区（边界行按它渲染，避免测试机时区不同导致结果飘）。 */
    private fun pinServerZone() = ServerZone.set("Asia/Shanghai")

    @Test
    fun `区间下标认不出来时落在全部`() {
        assertEquals(0, chargeRangeIndex("7d"))
        assertEquals(1, chargeRangeIndex("30d"))
        assertEquals(2, chargeRangeIndex("all"))
        // ⚠️ 认不出来 ⇒ 落在「全部」（最保守：不会凭空少报数据）。
        assertEquals(2, chargeRangeIndex("bogus"))
    }

    @Test
    fun `三个区间的键与标签一一对应`() {
        assertEquals(3, chargeRangeKeys.size)
        assertEquals(chargeRangeKeys.size, chargeRangeLabels.size)
        assertEquals(listOf("7d", "30d", "all"), chargeRangeKeys)
    }

    @Test
    fun `近 N 天的边界行显示窗口起止`() {
        pinServerZone()
        // 2026-10-27 00:00 +08:00 ⇒ UTC 2026-10-26T16:00:00Z
        // 2026-11-02 15:44 +08:00 ⇒ UTC 2026-11-02T07:44:00Z
        val r = ChargeStatsRange(
            key = "7d",
            days = 7,
            window_start = Instant.parse("2026-10-26T16:00:00Z"),
            window_end = Instant.parse("2026-11-02T07:44:00Z"),
            count = 2,
        )
        assertEquals("10-27 至 11-02", chargeStatsWindowLine(r))
    }

    @Test
    fun `全部的边界行显示数据首尾`() {
        pinServerZone()
        val r = ChargeStatsRange(
            key = "all",
            days = 0,
            // 2026-08-15 07:00 +08:00 ⇒ UTC 2026-08-14T23:00:00Z
            window_start = Instant.parse("2026-08-14T23:00:00Z"),
            // 2026-11-02 13:17 +08:00 ⇒ UTC 2026-11-02T05:17:00Z
            window_end = Instant.parse("2026-11-02T05:17:00Z"),
            count = 72,
        )
        assertEquals("2026年8月 至 2026年11月", chargeStatsWindowLine(r))
    }

    @Test
    fun `没有边界时不给那一行`() {
        pinServerZone()
        assertNull(chargeStatsWindowLine(ChargeStatsRange(key = "7d", days = 7)))
        assertNull(chargeStatsWindowLine(ChargeStatsRange(key = "all", days = 0)))
    }

    @Test
    fun `两倍字号下分段标签放得下`() {
        // 360dp 屏：360 − 页面内边距 32 − 卡片内边距 28 − 控件内边距 4 − 段间隙 4 = 292 ⇒ 每段 97.3dp
        val segmentDp = (360f - 32f - 28f - 4f - 4f) / 3f
        // ⚠️ 这个 App **覆盖过整套排版**：labelLarge = 15.sp（不是框架默认的 14.sp）。
        val font200 = 15f * 2f
        for (label in chargeRangeLabels) {
            val w = estimateTextWidthDp(label, font200)
            assertTrue(
                "「$label」在 200% 下要 $w dp > 每段 $segmentDp dp ⇒ 会被省略号截断",
                w <= segmentDp,
            )
        }
        // 反证：用户原话的长标签在 200% 下**放不下** ⇒ 我们才改成短标签
        // （Widgets.kt 的 SegmentedTabs 注释里记着同一件事：「近 30 天」→「30 天」）。
        assertTrue(
            "「近 30 天」在 200% 下居然放得下 ⇒ 标签可以换回用户原话",
            estimateTextWidthDp("近 30 天", font200) > segmentDp,
        )
    }
}
