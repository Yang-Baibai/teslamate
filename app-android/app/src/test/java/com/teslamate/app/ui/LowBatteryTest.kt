package com.teslamate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.162.0**：「低电量」的判定**只在服务端做一次**（ADR #38 / #41）。
 *
 * ## 这组测试钉的是什么
 *
 * ① **服务端给了结论 ⇒ 客户端不许重算**（哪怕电量的数看着对不上）；
 * ② 服务端**没给**结论（老服务端 / 旧缓存）⇒ 客户端按兜底阈值算一次，
 *    免得把「低电量」悄悄说成「正常」（那是**假安全**）；
 * ③ 兜底的两个数与服务端**逐值相等**（跨文件比对由客户端闸门 41 做，
 *    这里钉的是「客户端这边不许自己长出第三个数」）。
 */
class LowBatteryTest {

    @Test
    fun `服务端给了结论就直接用_客户端不重算`() {
        val ui = resolveLowBattery(soc = 90, charging = false, serverLow = true, serverSevere = true)
        assertTrue("服务端的结论必须原样用", ui.low)
        assertTrue("服务端的结论必须原样用", ui.severe)
    }

    @Test
    fun `服务端说不低_客户端不许自己判低`() {
        // ⚠️ 哪怕电量只有 5%：只要服务端说「不低」，客户端就**不许**自己重算
        // （判定只在服务端做 —— ADR #38）。
        val ui = resolveLowBattery(soc = 5, charging = false, serverLow = false, serverSevere = false)
        assertFalse("客户端不许自己重算（判定只在服务端）", ui.low)
        assertFalse("客户端不许自己重算（判定只在服务端）", ui.severe)
    }

    @Test
    fun `服务端没给结论时按兜底阈值算`() {
        val at19 = resolveLowBattery(soc = 19, charging = false, serverLow = null, serverSevere = null)
        assertTrue("19% 必须是低电量", at19.low)
        assertFalse("19% 不是严重偏低", at19.severe)

        val at9 = resolveLowBattery(soc = 9, charging = false, serverLow = null, serverSevere = null)
        assertTrue("9% 必须是低电量", at9.low)
        assertTrue("9% 必须是严重偏低", at9.severe)

        // 恰好等于阈值 ⇒ 不算低（阈值是「低于」而不是「不高于」，与服务端同一套判据）。
        val at20 = resolveLowBattery(soc = 20, charging = false, serverLow = null, serverSevere = null)
        assertFalse("20% 恰好等于阈值 ⇒ 不算低电量", at20.low)

        val charging = resolveLowBattery(soc = 5, charging = true, serverLow = null, serverSevere = null)
        assertFalse("充电中不算低电量", charging.low)
        assertFalse("充电中不算低电量", charging.severe)

        val unknown = resolveLowBattery(soc = null, charging = false, serverLow = null, serverSevere = null)
        assertFalse("没读到电量 ⇒ 不许报低电量", unknown.low)
        assertFalse("没读到电量 ⇒ 不许报低电量", unknown.severe)
    }

    @Test
    fun `兜底阈值与服务端一致`() {
        assertEquals("低电量兜底阈值必须是 20", 20, LOW_BATTERY_FALLBACK_SOC)
        assertEquals("严重偏低兜底阈值必须是 10", 10, LOW_BATTERY_SEVERE_FALLBACK_SOC)
    }
}
