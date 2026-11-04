package com.teslamate.app.ui

import com.teslamate.app.BatteryDecay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** 实测本机：17 个 100% 满充点、9836 公里 / 83 天、剔除 1 场、2026-09-28 落差。 */
    private fun realDecay() = BatteryDecay(
        n = 17,
        excluded_n = 12,
        span_km = 9835.521468,
        span_days = 83,
        has_step = true,
        step_km = 96791.508598,
        step_date = "2026-09-28",
        step_kwh = -0.655,
        naive_overstate = 3.26,
        dropped_empty = 1,
        conclusion = "weak",
    )

    @Test
    fun `衰减结论依据在上限内`() {
        val note = BatteryDecayText.note(realDecay())
        assertTrue(
            "ⓘ 宽度 ${noteVisualWidth(note)} > $NOTE_WIDTH_LIMIT：$note",
            noteVisualWidth(note) <= NOTE_WIDTH_LIMIT,
        )
        assertTrue("必须给样本量：$note", note.contains("17 个 100% 满充点"))
        assertTrue("必须说清没计入的那些：$note", note.contains("12 个 90~98% 未计入"))
        assertTrue("必须给覆盖窗口：$note", note.contains("9836 公里 / 83 天"))
        assertTrue("必须说清剔了什么：$note", note.contains("剔除 1 场"))
        assertTrue("必须说落差已扣除：$note", note.contains("已扣除"))
        assertTrue("不能替用户下断言：$note", note.contains("分不清"))
    }

    /**
     * ⚠️ **客户端不再维护第二份口径副本** ——
     * 「容量口径」「不能外推」「高估几倍」都由**服务端 `caveat`** 呈现
     * （同卡的「怎么算的」折叠区），客户端留一份必然漂移。
     */
    @Test
    fun `衰减结论依据里不重复服务端口径`() {
        val note = BatteryDecayText.note(realDecay())
        assertFalse("不能出现物理测量口径：$note", note.contains("物理测量"))
        assertFalse("不能出现不能外推：$note", note.contains("不能外推"))
        assertFalse("不能出现高估倍数：$note", note.contains("高估"))
    }

    /** 没有落差时，那句「落差已扣除」不能出现。 */
    @Test
    fun `没有落差时不提落差`() {
        val note = BatteryDecayText.note(realDecay().copy(has_step = false, step_kwh = 0.0))
        assertFalse("不能提落差：$note", note.contains("落差"))
        assertTrue(
            "ⓘ 宽度 ${noteVisualWidth(note)} > $NOTE_WIDTH_LIMIT：$note",
            noteVisualWidth(note) <= NOTE_WIDTH_LIMIT,
        )
    }

    /** 没有未计入点、没有空会话时，那两段都不该出现（v0.14.0 P2-4：零值段是噪音）。 */
    @Test
    fun `没有未计入点时不提未计入`() {
        val note = BatteryDecayText.note(realDecay().copy(excluded_n = 0, dropped_empty = 0))
        assertFalse("不能提未计入：$note", note.contains("未计入"))
        assertFalse("不能提剔除：$note", note.contains("剔除"))
        assertTrue(
            "ⓘ 宽度 ${noteVisualWidth(note)} > $NOTE_WIDTH_LIMIT：$note",
            noteVisualWidth(note) <= NOTE_WIDTH_LIMIT,
        )
    }
}
