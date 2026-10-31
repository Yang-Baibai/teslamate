package com.teslamate.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.96.0**：「直流桩边际成本」什么时候**不该出现**。
 *
 * ## 盯住什么
 *
 * 用量页汇总卡原来把 6 行费用**平铺**在表层（充电费用 / 每公里成本 /
 * 直流桩边际成本 / 实际每度电 / 电费每度 / 服务费每度）——
 * 一个主角下面挂 6 行等权小字，就是 v0.86.0 抱怨过的「同权重的灰字墙」。
 *
 * 本轮：表层只留「充电费用（总额）」与「每公里成本（单价）」两个**结论**，
 * 其余是**证据** ⇒ 折进「更多明细」。
 *
 * 另外有一行是**同一个数说两遍**：窗口里全是直流桩时
 * （本机今天 / 7 天段实测 100% 直流），
 * 「直流桩边际成本」与「每公里成本」**恒等** ⇒
 * 顶着一个像新指标一样的标签，把同一个数又说了一遍 ⇒ 更迷惑。
 *
 * v0.14.0 的先例：「两行说的是同一件事，比一行更让人烦」。
 */
class ChargeCostTest {

    /** 全直流窗口：两个数恒等 ⇒ 这一行是冗余的（必须隐藏）。 */
    @Test
    fun `全直流窗口里直流桩边际成本与每公里成本是同一个数`() {
        assertTrue(
            "每公里 0.22 与直流桩边际 0.22 是同一个数 ⇒ 冗余",
            dcMarginalIsRedundant(0.22, 0.22),
        )
    }

    /** 有交流慢充混算时：两个数**不同** ⇒ 这一行在回答另一个问题，必须显示。 */
    @Test
    fun `有交流慢充时两个数不同所以不能隐藏`() {
        assertFalse(
            "每公里 0.22 与直流桩边际 0.31 不同 ⇒ 必须显示",
            dcMarginalIsRedundant(0.22, 0.31),
        )
    }

    /** 差在浮点噪声范围内（1e-4 元/公里）按同一个数处理。 */
    @Test
    fun `浮点噪声不算差别`() {
        assertTrue(dcMarginalIsRedundant(0.22, 0.22000001))
        assertFalse(dcMarginalIsRedundant(0.22, 0.2205))
    }

    /** 缺一个数就不做隐藏判断（那一行本来也不会渲染）。 */
    @Test
    fun `缺一个数时不做隐藏判断`() {
        assertFalse(dcMarginalIsRedundant(null, 0.31))
        assertFalse(dcMarginalIsRedundant(0.22, null))
        assertFalse(dcMarginalIsRedundant(null, null))
    }
}
