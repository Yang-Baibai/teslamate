package com.teslamate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ⚠️ **v0.60.0（第 30 轮 P1-4 附带发现）**：折算值与实测值的**相对偏差**。
 *
 * ## 为什么必须有这条测试
 *
 * 这个数是**长途党在服务区最该盯住的数**（「我要等多久」）——
 * 而第 30 轮审计发现它**从来没有被单测盯过**，
 * 而那轮审计偏偏发现它的**方向在脚注里是反的**。
 *
 * ## 盯住什么
 *
 * ① **正负方向**必须钉死：
 *    - **正** = 折算**偏大** = 你以为要等更久，实际更快到（直流桩降功率，常态）；
 *    - **负** = 折算**偏小** = 你以为很快到，实际等很久 ⇒ **最危险的方向**。
 * ② 缺任一输入 ⇒ **不给数**（不给一个编的百分比）。
 */
class FoldBiasTest {

    @Test
    fun `折算偏大是正 偏小是负`() {
        // 折算 43 分钟、实测 36 分钟 ⇒ 偏大 19%
        assertEquals(19, foldBiasPercent(43, 36))
        // 折算 21 分钟、实测 31 分钟（梧硕高速那一场）⇒ 偏小 32%
        assertEquals(-32, foldBiasPercent(21, 31))
    }

    @Test
    fun `完全相等时偏差是零`() {
        assertEquals(0, foldBiasPercent(36, 36))
    }

    @Test
    fun `缺输入时不给数`() {
        assertNull("折算缺了不给", foldBiasPercent(null, 36))
        assertNull("实测缺了不给", foldBiasPercent(43, null))
        assertNull("两个都缺不给", foldBiasPercent(null, null))
    }

    @Test
    fun `实测为零或负数时不许出现无意义的偏差`() {
        // 除以 0 会得到 Infinity / NaN —— 那不是百分比。
        assertNull("实测为 0 不给", foldBiasPercent(43, 0))
        assertNull("实测为负不给", foldBiasPercent(43, -5))
    }

    @Test
    fun `真实数据里两个方向都存在`() {
        // 本屏口径中位 +34.5%（偏大，直流桩降功率的常态）
        assertEquals(35, foldBiasPercent(135, 100))
        // 梧硕高速那一场 −32.3%（偏小，最危险的方向）
        assertEquals(-32, foldBiasPercent(21, 31))
    }
}
