package com.teslamate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.teslamate.app.ui.maintenanceReminders

/**
 * ⚠️ **v0.75.0（查库实测）：「里程表」那一行的口径。**
 *
 * ## 盯住什么
 *
 * `positions.odometer`（TeslaMate 按 GPS 点累计）的**绝对值被停车时的 GPS 抖动污染**：
 * 本机 car 1 实测**日跨度最大 1,143 公里**（一天 109,457 条采样），
 * 全窗口绝对值 101,477.4 vs **车端真值 63,055.1** ⇒ **差 38,422 公里**。
 *
 * 而它的**窗口跨度**是准的（只统计行驶中的采样）—— 所以**绝对值不能叫「里程表」**。
 *
 * ⇒ 规则（见 [odometerLine]）：
 * - 车端有值 ⇒ **只显示车端**（仪表盘上的里程表）；
 * - 车端读不到 ⇒ 显示 GPS 估算，并**明说它是估算**。
 *
 * ⚠️ 特别要盯住「**不并列两个数**」：
 * v0.21.0 把 GPS 估算与车端并排列出（还附「差 N 公里」），
 * 那恰恰会制造它本来想消灭的怀疑：「我拿 App 的里程表和特斯拉 App 对，
 * 于是我怀疑车被人动了」。
 */
class OdometerLineTest {

    @Test
    fun `车端有值时只显示车端`() {
        val s = odometerLine(gpsKm = 101477.4, vehicleKm = 63055.1)
        assertEquals("里程表 63,055 公里（车端）", s)
    }

    @Test
    fun `有车端时绝不出现 GPS 那个数`() {
        val s = odometerLine(gpsKm = 101477.4, vehicleKm = 63055.1)
        assertFalse("GPS 绝对值不该出现在里程表那一行", s.contains("101,477"))
        assertFalse("不该并列两个里程表", s.contains("GPS"))
        assertFalse("不该再显示「差 N 公里」", s.contains("差 "))
    }

    @Test
    fun `车端读不到时显示 GPS 估算并标明`() {
        val s = odometerLine(gpsKm = 101477.4, vehicleKm = null)
        assertEquals("里程表 101,477 公里（GPS 估算）", s)
        assertTrue("必须标明是估算", s.contains("估算"))
    }

    @Test
    fun `两个都没有时给破折号而不是崩`() {
        val s = odometerLine(gpsKm = null, vehicleKm = null)
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
        val (num, qualifier) = odometerParts(gpsKm = 101477.4, vehicleKm = null)
        assertEquals("里程表 101,477 公里", num)
        assertEquals("（GPS 估算）", qualifier)
        assertFalse("数字那半不该含限定语", num.contains("估算"))
        assertFalse("限定语那半不该含数字", qualifier.contains("101"))
    }

    @Test
    fun `车端分支的限定语也独立且不带 GPS`() {
        val (num, qualifier) = odometerParts(gpsKm = 101477.4, vehicleKm = 63055.1)
        assertEquals("里程表 63,055 公里", num)
        assertEquals("（车端）", qualifier)
        assertFalse("有车端时限定语里不该出现 GPS", qualifier.contains("GPS"))
    }

    @Test
    fun `保养提醒不能拿被污染的 GPS 绝对值去算`() {
        // ⚠️ 保养基准是**仪表盘上的数**（车端）。
        // 拿 GPS 绝对值算，差 38,422 公里 ⇒ 会报出「保养超期 38,422 公里」的假告警，
        // 而**误报的方向最危险**（让人以为车立刻要保养，白花钱）。
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
