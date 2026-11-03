package com.teslamate.app.ui

import com.teslamate.app.AppSettings
import com.teslamate.app.MaintenanceBaseline
import com.teslamate.app.MaintenanceItem
import com.teslamate.app.MaintenanceReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.123.0：保养 / 轮胎提醒 —— 服务端结论优先，兜底路径只在取不到时用。**
 *
 * 这道测试盯的是**架构**（不是文案）：
 * 判定与文案都搬到服务端了（api/maintenance.go）⇒
 * 飞书与 App 必须同一个数、同一句话（ADR #38 / #41 / #52）。
 *
 * ⇒ 三条：
 * ① 服务端有结论 ⇒ **一律用它**，不掺任何本地计算；
 * ② 服务端的判定标志（超期 / 快到了）**原样透传**，客户端不再自己比阈值；
 * ③ 服务端取不到 ⇒ 才退回本地兜底（服务器挂掉时提醒不该整块消失）。
 */
class MaintenanceServerFactsTest {

    /** 服务端给的「保养已超期」那一项。 */
    private fun serverItem(
        label: String = "保养",
        remainingKm: Double = -21455.0,
        dueOdoKm: Double = 80000.0,
        overdue: Boolean = true,
        dueSoon: Boolean = false,
        text: String = "已超期 21455 公里",
        banner: String = "保养 超期 21455 公里 · 该在里程表 80,000 公里时做",
    ) = MaintenanceItem(
        label = label,
        due_odo_km = dueOdoKm,
        remaining_km = remainingKm,
        overdue = overdue,
        due_soon = dueSoon,
        text = text,
        banner_text = banner,
    )

    private fun settingsWithBaseline() = AppSettings(
        serverUrl = "http://127.0.0.1:8090",
        token = "t",
        carId = 1,
        serviceOdoKm = 60000.0,
        serviceIntervalKm = 20000,
    )

    // ① 服务端有结论 ⇒ 用它。
    @Test
    fun `服务端有结论时用的是服务端那份`() {
        val server = MaintenanceReport(
            car_id = 1,
            odometer_km = 101455.0,
            odometer_source = "车端",
            configured = true,
            service = serverItem(),
            summary = "已超期 21455 公里",
        )
        val items = resolveMaintenanceItems(
            server = server,
            vehicleOdometerKm = 101455.0,
            settings = settingsWithBaseline(),
        )
        assertEquals("应当只有服务端给的那一项", 1, items.size)
        assertEquals("保养", items[0].label)
        assertEquals("文案必须原样透传服务端那份", "已超期 21455 公里", items[0].describe())
    }

    // ① 服务端给两项 ⇒ 两项都在。
    @Test
    fun `服务端给两项时两项都在`() {
        val server = MaintenanceReport(
            car_id = 1,
            odometer_km = 101455.0,
            configured = true,
            service = serverItem(label = "保养"),
            tire = serverItem(
                label = "轮胎",
                remainingKm = 12000.0,
                dueOdoKm = 113455.0,
                overdue = false,
                dueSoon = false,
                text = "还有 12000 公里到期",
                banner = "轮胎 还有 12000 公里到期（该在里程表 113,455 公里时做）",
            ),
        )
        val items = resolveMaintenanceItems(server, 101455.0, settingsWithBaseline())
        assertEquals(2, items.size)
        assertEquals("保养", items[0].label)
        assertEquals("轮胎", items[1].label)
    }

    // ② 判定标志**原样透传**（客户端不再自己比阈值）。
    @Test
    fun `超期与快到了都用服务端的判定`() {
        // 服务端说「快到了」但**还没超期** ⇒ 客户端不许自己另判。
        val server = MaintenanceReport(
            car_id = 1,
            odometer_km = 78000.0,
            configured = true,
            service = serverItem(
                remainingKm = 2000.0,
                dueOdoKm = 80000.0,
                overdue = false,
                dueSoon = true,
                text = "还有 2000 公里到期",
            ),
        )
        val items = resolveMaintenanceItems(server, 78000.0, settingsWithBaseline())
        assertEquals(1, items.size)
        assertFalse("超期判定必须用服务端的", items[0].overdue)
        assertTrue("快到了判定必须用服务端的", items[0].dueSoon)
        assertTrue("该提醒（需要行动）", items[0].needsAction)
    }

    // ② 横幅文案也原样透传（不重拼）。
    @Test
    fun `横幅文案原样透传服务端那份`() {
        val server = MaintenanceReport(
            car_id = 1,
            odometer_km = 101455.0,
            configured = true,
            service = serverItem(),
        )
        val items = resolveMaintenanceItems(server, 101455.0, settingsWithBaseline())
        assertEquals(
            "保养 超期 21455 公里 · 该在里程表 80,000 公里时做",
            items[0].bannerText(101455.0),
        )
    }

    // ③ 服务端取不到 ⇒ 退回本地兜底。
    @Test
    fun `服务端取不到时退回本地兜底`() {
        val items = resolveMaintenanceItems(
            server = null,
            vehicleOdometerKm = 101455.0,
            settings = settingsWithBaseline(),
        )
        assertEquals("兜底路径也该给出一项", 1, items.size)
        assertEquals("保养", items[0].label)
        assertTrue("本地兜底：负数 = 已超期", items[0].overdue)
    }

    // ③ 兜底路径里，车端读不到 ⇒ 不报（v0.75.0 的教训）。
    @Test
    fun `兜底路径里车端读不到就不报`() {
        val items = resolveMaintenanceItems(
            server = null,
            vehicleOdometerKm = null,
            settings = settingsWithBaseline(),
        )
        assertTrue("车端读不到时不该报", items.none { it.needsAction })
    }

    // ③ 服务端「没设置基准」⇒ 不给任何判定（不制造噪音）。
    @Test
    fun `服务端说没设基准时不给判定`() {
        val server = MaintenanceReport(
            car_id = 1,
            odometer_km = 101455.0,
            configured = false,
            summary = "",
            note = "还没设置保养与轮胎基准",
        )
        val items = resolveMaintenanceItems(server, 101455.0, settingsWithBaseline())
        assertTrue("没设基准时不该有任何提醒", items.isEmpty())
    }

    // ③ 服务端「里程表读不到」⇒ 不给判定（宁可不说，也不误报）。
    @Test
    fun `服务端说里程表读不到时不给判定`() {
        val server = MaintenanceReport(
            car_id = 1,
            odometer_km = null,
            odometer_source = "未知",
            configured = true,
            note = "车端里程表暂时读不到（车可能睡着了）⇒ 暂不判定保养到期",
        )
        val items = resolveMaintenanceItems(server, null, settingsWithBaseline())
        assertTrue("里程表读不到时不该有任何提醒", items.isEmpty())
    }

    // ⚠️ 兜底阈值与服务端那个常量必须一致（Hermes 闸门 13 也在核对）。
    @Test
    fun `兜底阈值与服务端一致`() {
        assertEquals("客户端兜底阈值必须是 2000 公里", 2000.0, MAINTENANCE_DUE_SOON_KM, 0.001)
        val settings = settingsWithBaseline()
        // 基准 60000 + 间隔 20000 = 该在 80000 时做；现在 78000 ⇒ 还剩 2000（正好阈值）。
        val items = resolveMaintenanceItems(null, 78000.0, settings)
        assertTrue("正好 2000 公里应算「快到了」", items[0].dueSoon)
        // 现在 77999 ⇒ 还剩 2001 ⇒ 不算。
        val items2 = resolveMaintenanceItems(null, 77999.0, settings)
        assertFalse("2001 公里不该算「快到了」", items2[0].dueSoon)
    }

    // ⚠️ 服务端的基准要能原样显示在设置页（App 要显示它）。
    @Test
    fun `服务端基准原样透传`() {
        val base = MaintenanceBaseline(
            service_odo_km = 60000.0,
            service_interval_km = 20000,
            tire_odo_km = 60000.0,
            tire_interval_km = 40000,
        )
        val server = MaintenanceReport(car_id = 1, configured = true, baseline = base)
        assertEquals(60000.0, server.baseline.service_odo_km, 0.001)
        assertEquals(20000, server.baseline.service_interval_km)
        assertEquals(60000.0, server.baseline.tire_odo_km, 0.001)
        assertEquals(40000, server.baseline.tire_interval_km)
    }
}
