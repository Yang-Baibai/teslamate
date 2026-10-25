package com.teslamate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.37.0：电量环几何的**编译期**金标准。**
 *
 * ## 为什么既写 Python 闸门又写这个
 *
 * `scripts/client-gates.sh` 里的几何闸门是用**正则**从 `Widgets.kt` 源码里
 * 抠出常量再复算的 —— 它能挡住「有人改了公式」，但**读不懂代码**。
 * 这个测试**直接调用** [socRingDiameterDp]，所以：
 *   · 常量被改动 ⇒ 编译通过但**断言失败**（比正则更早、更准）；
 *   · 公式被改错 ⇒ 直接暴露。
 *
 * 两条闸门保留（覆盖面不同）：Python 那条能扫**全部** .kt 源码的文案。
 */
class GeometryTest {

    @Test
    fun `电量环直径的金标准`() {
        // 与 `scripts/client-gates.sh` 的期望值**逐条一致**。
        val expect = mapOf(
            1.0f to 140.0f,
            1.2f to 140.0f,
            1.35f to 140.0f,
            1.5f to 142.8f,
            1.75f to 166.6f,
            2.0f to 190.4f,
        )
        for ((fs, want) in expect) {
            assertEquals("socRingDiameterDp(fs=$fs)", want, socRingDiameterDp(140, fs), 0.05f)
        }
    }

    @Test
    fun `环直径有上下界`() {
        // 小于最小尺寸时用最小值
        assertEquals(140f, socRingDiameterDp(140, 0.5f), 0.01f)
        // 超过上限时截断
        assertEquals(280f, socRingDiameterDp(140, 3.0f), 0.01f)
    }

    @Test
    fun `主角在两种屏宽下都不被裁切`() {
        // 主角「204」是 3 个数字、44sp、tnum（0.56 em/字）
        val heroWidth = 3 * 0.56 * 44
        for (screen in intArrayOf(393, 360)) {
            val ring = socRingDiameterDp(140, 1.34f)
            val avail = screen - 32 - ring - 28
            assertTrue(
                "${screen}dp 屏在 fs=1.34 主角会被裁（可用 %.1fdp < 需要 %.1fdp）".format(avail, heroWidth * 1.34),
                avail >= heroWidth * 1.34,
            )
        }
    }
}
