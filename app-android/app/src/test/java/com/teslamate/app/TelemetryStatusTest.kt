package com.teslamate.app

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.79.0：故障码（Fleet Telemetry）的反序列化契约**。
 *
 * ## 盯住什么
 *
 * 故障码只能从 Fleet Telemetry 拿。服务端把遥测块塞进 `/status`，
 * App 靠 `CarStatus.telemetry` 读它。
 *
 * ⇒ **「服务端发了，App 没读到」是最阴的失效形态**：
 * 不报错、不崩，界面只是少了故障码卡 ——
 * 而故障码卡少显示 = 用户以为车没事。
 *
 * ⇒ 这里用**真实报文**（实测 `/status` 的 JSON）做反序列化断言：
 * 字段名一改、类型一改，这里立刻红。
 */
class TelemetryStatusTest {

    /** 实测 `/status` 报文里带遥测块的样子（已注入 2 条测试故障码）。 */
    private val sample = """
    {
      "car_id": 1,
      "updated_at": "2026-10-28T16:00:00Z",
      "state": "asleep",
      "battery_level": 74,
      "telemetry": {
        "connected": false,
        "ever_connected": true,
        "active_alerts": 2,
        "total_alerts": 2,
        "alerts": [
          {
            "code": "BMS_a035",
            "subsystem": "高压电池管理系统",
            "meaning": "高压电池绝缘故障",
            "summary": "高压电池管理系统 · 高压电池绝缘故障",
            "audiences": ["customer"],
            "started_at": "2026-10-28T14:52:49.435917Z",
            "ended_at": null,
            "active": true,
            "last_seen_at": "2026-10-28T16:52:49.435917Z"
          },
          {
            "code": "DI_a138",
            "subsystem": "驱动系统（逆变器 / 电机）",
            "meaning": "前电机被禁用",
            "summary": "驱动系统（逆变器 / 电机） · 前电机被禁用",
            "audiences": ["customer"],
            "started_at": "2026-10-28T15:52:49.435917Z",
            "ended_at": null,
            "active": true,
            "last_seen_at": "2026-10-28T16:52:49.435917Z"
          }
        ],
        "stream": {
          "broker_connected": true,
          "last_message_at": "2026-10-28T16:34:08.555886828Z",
          "messages": 6,
          "alerts": 0,
          "errors": 0,
          "fields": 5,
          "last_sample": "<VIN>/Soc = 73.92"
        },
        "updated_at": null
      }
    }
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `遥测块能被读出来`() {
        val st = json.decodeFromString<CarStatus>(sample)
        val tel = st.telemetry
        assertNotNull("遥测块必须能被读出来（读不到 = 故障码卡永远不显示）", tel)
        assertEquals(2, tel!!.active_alerts)
        assertEquals(2, tel.total_alerts)
        assertTrue("遥测流应该报已接入", tel.ever_connected)
        assertEquals("已接入", if (tel.ever_connected) "已接入" else "未接入")
    }

    @Test
    fun `故障码明细的字段要全`() {
        val tel = json.decodeFromString<CarStatus>(sample).telemetry!!
        assertEquals(2, tel.alerts.size)
        val a = tel.alerts[0]
        assertEquals("BMS_a035", a.code)
        assertEquals("高压电池管理系统", a.subsystem)
        assertEquals("高压电池绝缘故障", a.meaning)
        assertTrue(a.active)
        assertNull("进行中的故障码没有结束时刻", a.ended_at)
        assertEquals(listOf("customer"), a.audiences)
    }

    @Test
    fun `没有遥测块的旧服务端不该让解析崩掉`() {
        val st = json.decodeFromString<CarStatus>("""{"car_id":1,"updated_at":"2026-10-28T16:00:00Z"}""")
        assertNull("老服务端没有遥测块 —— 解析必须成功且拿到 null", st.telemetry)
    }

    @Test
    fun `空告警列表不是 null`() {
        val tel = json.decodeFromString<CarStatus>(
            """{"car_id":1,"updated_at":"2026-10-28T16:00:00Z","telemetry":{"ever_connected":true,"alerts":[]}}"""
        ).telemetry!!
        assertEquals(0, tel.alerts.size)
        assertEquals(0, tel.active_alerts)
    }
}
