package com.teslamate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.teslamate.app.ui.maintenanceReminders

/**
 * ⚠️ **v0.127.0：「里程表」那一行的口径。**
 *
 * ## 盯住什么
 *
 * ① **单位**：官方 `vehicle_state.odometer` 给的是**英里** ⇒ 服务端换算成公里。
 *    本机实测：63,250.31 英里 = 101,791.508834 公里，与 TeslaMate 那份
 *    （101,791.508834）**逐位相同** ⇒ 两者是同一个数。
 *    ⚠️ v0.75.0 当年写的「偏 38,422 公里」是**单位错误**（把英里当公里）。
 *    ⇒ 这里的断言全部用**公里**（换算只在服务端，客户端一字不改地印）。
 *
 * ② **来源要写清楚，且只显示一个数**（v0.21.0）：
 *    - 车端有值 ⇒ 只显示车端（仪表盘上那个数）；
 *    - 车端读不到 ⇒ 显示 TeslaMate 那份，并**明说它是估算**；
 *    - **不并列两个数**（并列会制造「车被人动了」的怀疑）。
 *
 * ③ **限定语由服务端给**（ADR #41）：客户端不自己拼「（车端）」、不自己写日期。
 */
class OdometerLineTest {

    @Test
    fun `车端有值时只显示车端`() {
        val s = odometerLine(gpsKm = 101791.508834, vehicleKm = 101791.508834, note = "（车端 · 11-03 读数）")
        assertEquals("里程表 101,792 公里（车端 · 11-03 读数）", s)
    }

    @Test
    fun `有车端时绝不出现 GPS 那个数`() {
        val s = odometerLine(gpsKm = 101700.0, vehicleKm = 101791.508834, note = "（车端 · 11-03 读数）")
        assertFalse("GPS 那个数不该出现在里程表那一行", s.contains("101,700"))
        assertFalse("不该并列两个里程表", s.contains("GPS"))
        assertFalse("不该再显示「差 N 公里」", s.contains("差 "))
    }

    @Test
    fun `客户端不做任何单位换算`() {
        // ⚠️ v0.127.0：换算只在服务端（api/odometer_unit.go）——
        // 客户端拿到的公里数**一字不改**地印出来。
        val s = odometerLine(gpsKm = 101791.508834, vehicleKm = 101791.508834, note = "（车端 · 11-03 读数）")
        assertTrue("客户端不该自己换算：$s", s.contains("101,792"))
        assertFalse("客户端不该自己换算（英里 ×1.609 的话是 101,792…，这里只验它没多乘一次）：$s",
            s.contains("163,674") || s.contains("163,675"))
    }

    @Test
    fun `车端读不到时显示估算并标明`() {
        val s = odometerLine(gpsKm = 101791.508834, vehicleKm = null, note = "（GPS 估算）")
        assertEquals("里程表 101,792 公里（GPS 估算）", s)
        assertTrue("必须标明是估算", s.contains("估算"))
    }

    @Test
    fun `两个都没有时给破折号而不是崩`() {
        val s = odometerLine(gpsKm = null, vehicleKm = null, note = "（GPS 估算）")
        assertEquals("里程表 — 公里（GPS 估算）", s)
    }

    /**
     * ⚠️ **v0.94.0**：限定语必须与数字**拆成两半**。
     *
     * 原来整行是一个字符串、渲染在电量环右侧**窄栏**里 ——
     * 而「里程表 101,537 公里（GPS 估算）」需要约 168dp、窄栏只有约 171dp，
     * **差 2dp** ⇒ 贪心折行把限定语劈成「（GPS 估」+「算）」（真机实测）。
     *
     * CJK 允许在**任意两个汉字之间**断行 ⇒ 只有**独立成段**才不会被劈开。
     */
    @Test
    fun `限定语必须与数字拆成两半`() {
        val (num, qualifier) = odometerParts(gpsKm = 101791.508834, vehicleKm = null, note = "（GPS 估算）")
        assertEquals("里程表 101,792 公里", num)
        assertEquals("（GPS 估算）", qualifier)
        assertFalse("数字那半不该含限定语", num.contains("估算"))
        assertFalse("限定语那半不该含数字", qualifier.contains("101"))
    }

    @Test
    fun `限定语必须原样用服务端的`() {
        // ⚠️ v0.127.0：客户端不许自己拼「（车端）」「（GPS 估算）」——
        // 服务端没给就**不显示**（空），而不是自己编一句（ADR #41）。
        val (num, qualifier) = odometerParts(gpsKm = 101791.5, vehicleKm = 101791.5, note = null)
        assertEquals("", qualifier)
        assertFalse("客户端不许自己写「（车端）」", num.contains("车端"))
    }

    @Test
    fun `保养提醒不能拿带噪声的那份去算`() {
        // ⚠️ 保养基准是**仪表盘上的数**（车端）。
        // 拿带噪声的那份算，可能报出一大截假超期 ⇒ **误报的方向最危险**
        // （让人以为车立刻要保养，白花钱）。
        // ⇒ 车端读不到就**不报**（宁可不报，也不误报）。
        val reminders = maintenanceReminders(odometerKm = null, settings = appSettingsWithBaseline())
        assertTrue("车端读不到时不该报保养", reminders.none { it.needsAction })
    }

    // ── 测试脚手架 ────────────────────────────────────────────────

    private fun appSettingsWithBaseline(): AppSettings = AppSettings(
        serverUrl = "http://127.0.0.1:8090",
        token = "t",
        carId = 1,
        serviceOdoKm = 60000.0,
        serviceIntervalKm = 20000,
    )
}
