package com.teslamate.app.ui

import com.teslamate.app.BatteryDecay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.99.0（第 9 轮 CDO Polish）：电池健康卡 4 条 ⓘ 的长度与口径。**
 *
 * ## 这一层盯住什么
 *
 * v0.97.0 给 ⓘ 立的宽度上限（160 视觉宽度）由**两层**把关：
 *
 * - **调用点字面量** ⇒ 闸门 13（`scripts/client-gates.sh`，源码级扫描）；
 * - **提供器函数** ⇒ **运行时单测**（本文件）——
 *   因为静态扫提供器会把**互斥分支也相加** ⇒ 必然假阳性。
 *
 * 电池健康卡的 4 条 ⓘ 现在**全部**是提供器形态
 * （`ui/BatteryNote.kt` 三条 + `BatteryDecayText.note(d)` 一条），
 * ⇒ 它们**只能**由本文件守住。
 *
 * ## 为什么必须用运行时单测而不是静态扫描
 *
 * 这 4 条 ⓘ 里**插值的内容比字面量还多**：
 * 日期（`2026-10-31`）、公里差（`90 km`）、百分比（`84.4%`）——
 * 静态扫描里模板占位只按 **1 个字符**算 ⇒ 它量到的是**偏松**的数。
 * 只有运行时单测才量得到**真正渲染出来的那串字**。
 *
 * ## 这一轮压掉的是「同一个数说两遍」
 *
 * 这些不是文案洁癖，各对应一个**真实缺陷**：
 *
 * - ② 的日期与行标题「自 2026-08-02」重复 ⇒ 同一个数说两遍；
 * - ③ 的「538 km」与下面那行「满电续航（按出厂折算）538 km」重复；
 * - ④ 的第 2、3 段是**服务端 `caveat` 的副本** ⇒ 两份口径必然漂移
 *   （v0.41.0 的注释早就写过「必须是同一份」）。
 */
class BatteryNoteTest {

    // ── ⓪ 副标题：必须带「哪一次充电测的」（v0.125.0）─────────────

    /**
     * 「现在还能装 X 度」来自**最近一次充到 90% 以上**的那场充电，
     * 不是此刻的读数 ⇒ 副标题必须带上测量时刻，否则是把旧值当现在讲。
     */
    @Test
    fun `副标题带上测量时刻`() {
        val s = batteryCapacitySubtitle(65.30476, 78.4, "10-31 实测")
        assertEquals("现在还能装 65.3 度 · 新电池标称 78.4 度 · 10-31 实测", s)
    }

    /** 没有测量口径时**不许编一个** —— 保持原样。 */
    @Test
    fun `没有测量口径时不编`() {
        val s = batteryCapacitySubtitle(65.30476, 78.4, null)
        assertEquals("现在还能装 65.3 度 · 新电池标称 78.4 度", s)
    }

    /** 空串 / 纯空格 ⇒ 当作没有（服务端没给就是没给）。 */
    @Test
    fun `空白测量口径不渲染`() {
        val s = batteryCapacitySubtitle(65.30476, null, "   ")
        assertEquals("现在还能装 65.3 度", s)
    }

    /** 容量都没有 ⇒ 不拼出「现在还能装 度」这种残句。 */
    @Test
    fun `缺容量时不拼残句`() {
        val s = batteryCapacitySubtitle(null, null, "10-31 实测")
        assertEquals("10-31 实测", s)
    }

    // ── ① 「电池健康度」ⓘ ───────────────────────────────────────

    /** 实测本机：strictMax = 66.142845 度、nominal = 78.4 度 ⇒ 84.4%。 */
    @Test
    fun `电池健康度口径在上限内`() {
        val note = noteBatteryHealth(strictMax = 66.142845, nominalKwh = 78.4)
        assertTrue(
            "ⓘ 宽度 ${noteVisualWidth(note)} > $NOTE_WIDTH_LIMIT：$note",
            noteVisualWidth(note) <= NOTE_WIDTH_LIMIT,
        )
        assertTrue("必须说清是相对出厂衰减：$note", note.contains("相对出厂衰减了多少"))
        assertTrue("必须提醒效率常数可改：$note", note.contains("效率常数"))
        assertTrue("必须给出天花板：$note", note.contains("84.4%"))
    }

    /** ⚠️ 去工程化：`cars.efficiency` 是服务端字段名，车主读不出它是什么。 */
    @Test
    fun `电池健康度口径里没有工程字段名`() {
        val note = noteBatteryHealth(strictMax = 66.142845, nominalKwh = 78.4)
        assertFalse("不能出现 cars.efficiency：$note", note.contains("cars.efficiency"))
    }

    /** 没有严格档/标称容量时，那句「天花板」不能出现（否则会显示一个编不出来的数）。 */
    @Test
    fun `缺天花板数据时不出现天花板那句`() {
        val note = noteBatteryHealth(strictMax = null, nominalKwh = null)
        assertFalse("不能出现天花板：$note", note.contains("最好的一次"))
        assertTrue(
            "ⓘ 宽度 ${noteVisualWidth(note)} > $NOTE_WIDTH_LIMIT：$note",
            noteVisualWidth(note) <= NOTE_WIDTH_LIMIT,
        )
    }

    // ── ② 「近期趋势」ⓘ ─────────────────────────────────────────

    @Test
    fun `近期趋势口径在上限内`() {
        val note = noteRecentTrend()
        assertTrue(
            "ⓘ 宽度 ${noteVisualWidth(note)} > $NOTE_WIDTH_LIMIT：$note",
            noteVisualWidth(note) <= NOTE_WIDTH_LIMIT,
        )
        assertTrue("必须说清分母是首测容量：$note", note.contains("首测容量"))
        assertTrue("必须说清首测不是新车：$note", note.contains("首测不是新车容量"))
        assertTrue("必须指回相对出厂：$note", note.contains("相对出厂"))
    }

    /** 行标题已经写着「近期趋势（自 2026-08-02）」⇒ ⓘ 里不该再出现标签或日期。 */
    @Test
    fun `近期趋势口径不重复行标题里的标签和日期`() {
        val note = noteRecentTrend()
        assertFalse("不能重复标签：$note", note.contains("近期趋势"))
        assertFalse(
            "不能重复行标题里的日期：$note",
            Regex("20\\d\\d").containsMatchIn(note),
        )
    }

    // ── ③ 「满电续航」ⓘ ─────────────────────────────────────────

    /** 实测本机：最近满充 2026-10-31（448.83 km）、出厂折算 538 km ⇒ 差 90。 */
    @Test
    fun `满电续航口径在上限内`() {
        val note = noteFullChargeRange("2026-10-31", 90)
        assertTrue(
            "ⓘ 宽度 ${noteVisualWidth(note)} > $NOTE_WIDTH_LIMIT：$note",
            noteVisualWidth(note) <= NOTE_WIDTH_LIMIT,
        )
        assertTrue("必须说明是车机自己报的：$note", note.contains("车机满电时报的读数"))
        assertTrue("必须带那次满充的日期：$note", note.contains("2026-10-31"))
        assertTrue("必须给出与出厂折算的差：$note", note.contains("90 km"))
        assertTrue("必须说清官方标称不能相除：$note", note.contains("官方标称续航"))
    }

    /** 没有满充点（日期/差都没有）时，那两句不能出现。 */
    @Test
    fun `没有满充点时不提日期和差值`() {
        val note = noteFullChargeRange(null, null)
        assertFalse("不能提日期：$note", note.contains("那次"))
        assertFalse("不能提差值：$note", note.contains("少约"))
        assertTrue(
            "ⓘ 宽度 ${noteVisualWidth(note)} > $NOTE_WIDTH_LIMIT：$note",
            noteVisualWidth(note) <= NOTE_WIDTH_LIMIT,
        )
    }

    // ── ④ 衰减结论 ⓘ（`BatteryDecayText.note`）──────────────────

    /**
     * 实测本机：17 个 100% 满充点、9836 公里 / 83 天、剔除 1 场、2026-09-28 落差。
     *
     * ⚠️ **v0.172.0**：`note` 现在是**服务端给的原文**（客户端只透传）。
     */
    private fun realDecay() = BatteryDecay(
        n = 17,
        excluded_n = 12,
        excluded_near_full_n = 3,
        span_km = 9835.521468,
        span_days = 83,
        has_step = true,
        step_km = 96791.508598,
        step_date = "2026-09-28",
        step_kwh = -0.655,
        naive_overstate = 3.26,
        dropped_empty = 1,
        conclusion = "weak",
        note = "17 个 100% 满充点 · 另有 12 场没充满（3 场充到 90% 以上） · 9836 公里 / 83 天 · 剔除 1 场没实际充电的" +
            "\n\n有一次 -0.66 度落差，已扣除（车机重新标定还是电池变化，分不清）。",
    )

    /**
     * ⚠️ **v0.172.0**：这条卡的说明**必须逐字来自服务端**。
     *
     * ⇒ 以前是客户端自己拼的，而它拼的时候**编了一个口径**
     *   （「12 个 90~98% 未计入」⇒ 服务的判据只是「没充到 100%」）。
     * ⇒ 现在**只透传**（ADR #41 / ADR #86）⇒ 这条断言就是防它再漂回去。
     */
    @Test
    fun `这条卡的说明逐字来自服务端`() {
        val d = realDecay()
        assertEquals("必须逐字等于服务端的 note", d.note, BatteryDecayText.note(d))
    }

    /** 服务端没给（老服务端）⇒ **不显示**（宁可不说，也不说半句）。 */
    @Test
    fun `服务端没给说明就不显示`() {
        assertNull(
            "服务端没给就不该显示",
            BatteryDecayText.note(realDecay().copy(note = "")),
        )
    }
}
