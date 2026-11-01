package com.teslamate.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.103.0（第 11 轮 CDO Polish）：用量页「平均能耗」ⓘ 的长度与口径语义。**
 *
 * ## 这一层盯住什么
 *
 * ① **长度**：ⓘ 必须 ≤ [NOTE_WIDTH_LIMIT]（160 ≈ 展开后 3 行）。
 *    这条 ⓘ 是 provider 形态（`noteAvgEnergy()`）⇒ **静态闸门 13 看不见**
 *    ⇒ 只能靠运行时单测钉住。
 *
 * ② ⚠️ **不许引用 App 从不显示的数** ——
 *    原来的 ⓘ 说「两个都比**实测电池耗电**低」，
 *    而「实测电池耗电」在 App 里只用于费用计算内部，**从不作为耗电数字显示** ⇒ 悬空引用。
 *
 * ③ ⚠️ **不许对两个能耗口径断言谁高谁低** ——
 *    原来的 ⓘ 说的那句话**对用户看得见的那个数是反的**：
 *    它拿**净值**（≈155 Wh/km，比实测 ≈198 低 24%）推出来的，
 *    而车况页显示的是**毛耗电**（≈215~227 Wh/km，**高于**实测）。
 *
 *    ⇒ 而方向**本来就不固定**：
 *    平路没有下坡回充时，车轮端（不含传动损耗）**低于**电池侧；
 *    有下坡回充时，毛耗电（不抵扣回充）**高于**标定法。
 *    ⇒ 用户界面给不出条件 ⇒ 只能给「口径不同、数值会差一截，不是谁算错了」。
 *
 * ④ **不许把工程词放回用户可见文案** ——
 *    「标定续航差法」是内部术语（v0.90.0 已从标签里去掉「标定法」），
 *    界面里说人话：「按车机报的续航变化反推耗电」。
 *
 * ⑤ **必须保留的**：这个数怎么来的、另一个页面在哪、结论「不是谁算错了」。
 */
class EnergyNoteTest {

    /** 238 → 104 视觉宽度。 */
    @Test
    fun `平均能耗口径在上限内`() {
        val note = noteAvgEnergy()
        assertTrue(
            "ⓘ 宽度 ${noteVisualWidth(note)} > $NOTE_WIDTH_LIMIT：$note",
            noteVisualWidth(note) <= NOTE_WIDTH_LIMIT,
        )
    }

    /** 「实测电池耗电」App 从不显示 ⇒ 说它就是悬空引用。 */
    @Test
    fun `不许引用 App 从不显示的实测耗电`() {
        val note = noteAvgEnergy()
        assertFalse("不能提「实测电池耗电」：$note", note.contains("实测电池耗电"))
        assertFalse("不能提「实测耗电」：$note", note.contains("实测耗电"))
        assertFalse("不能提「电池实耗」：$note", note.contains("电池实耗"))
    }

    /**
     * ⚠️ 本轮修掉的那个错就长这样：「两个都比实测电池耗电低」。
     * ⇒ 「低」/「高」/「都比」这三个词在这条 ⓘ 里一律不许出现。
     */
    @Test
    fun `不许对两个能耗口径断言谁高谁低`() {
        val note = noteAvgEnergy()
        assertFalse("不许出现「都比」：$note", note.contains("都比"))
        assertFalse("不许断言「低」：$note", note.contains("低"))
        assertFalse("不许断言「高」：$note", note.contains("高"))
    }

    /** 必须说清这个数怎么来的、另一个页面在哪、以及结论。 */
    @Test
    fun `必须说清这个数怎么来的并指向另一个页面`() {
        val note = noteAvgEnergy()
        assertTrue("必须说清是按续航变化反推：$note", note.contains("续航变化"))
        assertTrue("必须指向车况页「驾驶画像」：$note", note.contains("驾驶画像"))
        assertTrue("必须点名另一个口径的算法：$note", note.contains("车轮功率积分"))
        assertTrue("必须给出结论「不是谁算错了」：$note", note.contains("不是谁算错了"))
    }

    /** 「标定续航差法」是工程词 ⇒ 不许放回用户可见文案。 */
    @Test
    fun `不许把工程词放回用户可见文案`() {
        val note = noteAvgEnergy()
        assertFalse("不能出现「标定续航差法」：$note", note.contains("标定续航差法"))
        assertFalse("不能出现「标定法」：$note", note.contains("标定法"))
    }
}
