package com.teslamate.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.97.0**：ⓘ 口径说明的**长度上限**与**分支语义**。
 *
 * ## 盯住什么
 *
 * 上一轮 CHANGELOG 的头号痛点：「ⓘ 里的口径说明**没有长度上限**」。
 * 仓库原有的「啰嗦棘轮」（`scripts/client-gates.sh` 闸门 5）只管
 * **常驻可见**的长说明块 —— 文字折进 ⓘ 之后就看不见了。
 *
 * 于是用量页汇总卡那两条 ⓘ 一路涨到 **380 / 296 视觉宽度**：
 * 展开 ⓘ 得到的是五句话混三个话题的字墙，不是「一句话结论 + 关键限定」。
 *
 * 这一层用**单测**钉住（闸门只能做源码级的正则检查）：
 * ① **宽度**：每条 ⓘ 都必须 ≤ [NOTE_WIDTH_LIMIT]（160 ≈ 展开后 3 行）；
 * ② **语义**：零值段不出现、占比不出现、围栏限定只在真有交流时出现。
 *
 * ⚠️ 语义那几条不是"文案洁癖" —— 它们各自对应一个**真实缺陷**：
 * 零值段会让用户以为"家充 0 度"是个数据（v0.14.0 P2-4 先例）；
 * 占比是度数的派生量（同一个数说两遍 = 噪音）；
 * 没有交流时提围栏，是在讲一件与这个窗口**无关**的事（无事不打扰）。
 */
class NoteBudgetTest {

    // ── 宽度口径 ────────────────────────────────────────────────

    @Test
    fun `视觉宽度汉字算两格半角算一格`() {
        assertTrue("一个汉字 = 2", noteVisualWidth("电") == 2)
        assertTrue("一个 ASCII = 1", noteVisualWidth("A") == 1)
        assertTrue("混排", noteVisualWidth("电A") == 3)
        assertTrue("空串 = 0", noteVisualWidth("") == 0)
    }

    // ── 「充电费用」ⓘ ────────────────────────────────────────────

    /** 实测今天 / 7 天窗口：100% 直流，交流 0 度。 */
    @Test
    fun `全直流窗口的充电费用口径在上限内`() {
        val cost = ChargeCost(
            homeKwh = 0.0,
            acOtherKwh = 0.0,
            dcKwh = 39.47,
            publicMinutes = 70.0,
            energyYuan = 47.36,
            serviceYuan = 0.0,
            dcEnergyYuan = 47.36,
        )
        val note = noteChargeCost(cost, acUsesHomePrice = false)
        assertTrue(
            "ⓘ 宽度 ${noteVisualWidth(note)} > $NOTE_WIDTH_LIMIT：$note",
            noteVisualWidth(note) <= NOTE_WIDTH_LIMIT,
        )
    }

    /** 实测本月 / 1 年窗口：有交流慢充，且**没有围栏归属**。 */
    @Test
    fun `有交流无围栏时口径在上限内且说清按哪个价计`() {
        val cost = ChargeCost(
            homeKwh = 0.0,
            acOtherKwh = 57.58,
            dcKwh = 742.72,
            publicMinutes = 968.0,
            energyYuan = 891.26,
            serviceYuan = 484.0,
            dcEnergyYuan = 891.26,
        )
        val note = noteChargeCost(cost, acUsesHomePrice = false)
        assertTrue(
            "ⓘ 宽度 ${noteVisualWidth(note)} > $NOTE_WIDTH_LIMIT：$note",
            noteVisualWidth(note) <= NOTE_WIDTH_LIMIT,
        )
        assertTrue("必须说清按公共桩价计：$note", note.contains("公共桩价"))
        assertTrue("必须说清分不出在家还是在外面：$note", note.contains("分不出在家还是在外面"))
    }

    /** 车主要求「交流按家充电价」时，那句话必须跟着变。 */
    @Test
    fun `交流按家充电价时口径跟着变`() {
        val cost = ChargeCost(
            homeKwh = 0.0,
            acOtherKwh = 57.58,
            dcKwh = 742.72,
            publicMinutes = 968.0,
            energyYuan = 891.26,
            serviceYuan = 484.0,
            dcEnergyYuan = 891.26,
        )
        val note = noteChargeCost(cost, acUsesHomePrice = true)
        assertTrue("必须说家充电价：$note", note.contains("家充电价"))
        assertFalse("不能还说公共桩价：$note", note.contains("公共桩价"))
    }

    /** 三段同时有（家充 + 无围栏交流 + 直流）= 最坏情况，也不能超上限。 */
    @Test
    fun `三段同时有的最坏情况也在上限内`() {
        val cost = ChargeCost(
            homeKwh = 12.0,
            acOtherKwh = 57.58,
            dcKwh = 1864.03,
            publicMinutes = 2278.0,
            energyYuan = 2236.84,
            serviceYuan = 1139.0,
            dcEnergyYuan = 2236.84,
        )
        val note = noteChargeCost(cost, acUsesHomePrice = false)
        assertTrue(
            "ⓘ 宽度 ${noteVisualWidth(note)} > $NOTE_WIDTH_LIMIT：$note",
            noteVisualWidth(note) <= NOTE_WIDTH_LIMIT,
        )
    }

    /** 零值段不出现（v0.14.0 P2-4 先例）—— 不给用户看「家充 0 度」。 */
    @Test
    fun `零值段不出现`() {
        val cost = ChargeCost(
            homeKwh = 0.0,
            acOtherKwh = 0.0,
            dcKwh = 39.47,
            publicMinutes = 70.0,
            energyYuan = 47.36,
            serviceYuan = 0.0,
            dcEnergyYuan = 47.36,
        )
        val note = noteChargeCost(cost, acUsesHomePrice = false)
        assertFalse("不能出现 0 度：$note", note.contains("0 度"))
        assertFalse("不能出现家充段：$note", note.contains("家充"))
        assertFalse("不能出现交流段：$note", note.contains("交流"))
    }

    /** 占比是度数的派生量 —— 同一个数说两遍就是噪音，必须去掉。 */
    @Test
    fun `占比不出现`() {
        val cost = ChargeCost(
            homeKwh = 12.0,
            acOtherKwh = 57.58,
            dcKwh = 1864.03,
            publicMinutes = 2278.0,
            energyYuan = 2236.84,
            serviceYuan = 1139.0,
            dcEnergyYuan = 2236.84,
        )
        val note = noteChargeCost(cost, acUsesHomePrice = false)
        assertFalse("不能出现占比：$note", note.contains("占 "))
        assertFalse("不能出现百分号：$note", note.contains("%"))
    }

    /** 没有交流时不谈围栏 —— 那是与这个窗口无关的事。 */
    @Test
    fun `没有交流时不谈围栏`() {
        val cost = ChargeCost(
            homeKwh = 0.0,
            acOtherKwh = 0.0,
            dcKwh = 39.47,
            publicMinutes = 70.0,
            energyYuan = 47.36,
            serviceYuan = 0.0,
            dcEnergyYuan = 47.36,
        )
        val note = noteChargeCost(cost, acUsesHomePrice = false)
        assertFalse("不谈围栏：$note", note.contains("围栏"))
        assertFalse("不谈分不出：$note", note.contains("分不出"))
    }

    /** 一场都没充 ⇒ 费用 ¥0.00，得说清为什么是 0（否则用户以为 App 坏了）。 */
    @Test
    fun `没充过电时说明为什么是零`() {
        val cost = ChargeCost(
            homeKwh = 0.0,
            acOtherKwh = 0.0,
            dcKwh = 0.0,
            publicMinutes = 0.0,
            energyYuan = 0.0,
            serviceYuan = 0.0,
        )
        val note = noteChargeCost(cost, acUsesHomePrice = false)
        assertTrue("必须说没有充入的电量：$note", note.contains("没有充入的电量"))
        assertTrue(
            "ⓘ 宽度 ${noteVisualWidth(note)} > $NOTE_WIDTH_LIMIT：$note",
            noteVisualWidth(note) <= NOTE_WIDTH_LIMIT,
        )
    }

    // ── 「每公里成本」ⓘ ──────────────────────────────────────────

    /** 实测今天 / 7 天 / 本月：缺口 0.10%~0.12% ⇒ 不构成区间。 */
    @Test
    fun `没有缺口时每公里成本口径在上限内`() {
        val note = noteCostPerKm(drivingKm = 4144.24, gapPercent = 0)
        assertTrue(
            "ⓘ 宽度 ${noteVisualWidth(note)} > $NOTE_WIDTH_LIMIT：$note",
            noteVisualWidth(note) <= NOTE_WIDTH_LIMIT,
        )
        assertFalse("没有缺口就不说区间：$note", note.contains("区间"))
    }

    /** 实测 1 年窗口：缺口 16% ⇒ 给区间，必须说清为什么。 */
    @Test
    fun `有缺口时每公里成本口径在上限内且说清是区间`() {
        val note = noteCostPerKm(drivingKm = 8946.71, gapPercent = 16)
        assertTrue(
            "ⓘ 宽度 ${noteVisualWidth(note)} > $NOTE_WIDTH_LIMIT：$note",
            noteVisualWidth(note) <= NOTE_WIDTH_LIMIT,
        )
        assertTrue("必须说是区间：$note", note.contains("区间"))
        assertTrue("必须说缺口百分比：$note", note.contains("16%"))
    }

    /** 缺口 <1% ⇒ 不提区间（阈值沿用 `costPerKm()` 的既有口径，不新增）。 */
    @Test
    fun `缺口不到百分之一时不提区间`() {
        val note = noteCostPerKm(drivingKm = 190.69, gapPercent = 0)
        assertFalse("不该提区间：$note", note.contains("区间"))
    }

    /** 分母必须是**实际用的那个**行驶里程（`sum.km`），不是里程表。 */
    @Test
    fun `分母写的是行驶里程`() {
        val note = noteCostPerKm(drivingKm = 8946.71, gapPercent = 16)
        assertTrue("必须写出分母：$note", note.contains("8,947"))
        assertTrue("必须说明是行驶里程：$note", note.contains("行驶里程"))
    }
}
