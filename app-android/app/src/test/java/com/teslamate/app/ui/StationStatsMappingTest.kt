package com.teslamate.app.ui

import com.teslamate.app.StationMeasuredSampleDto
import com.teslamate.app.StationStatDto
import com.teslamate.app.StationStatsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.182.0：按站画像的映射 —— 服务端 DTO ⇒ 界面类型。**
 *
 * 聚合本身在**服务端**（api/station_stats.go）⇒ 这里只测**映射**不丢字段：
 * 少映射一个字段 ⇒ 界面上的「典型 55 kW / 实测 31 分钟」就整段空掉。
 */
class StationStatsMappingTest {

    private val resp = StationStatsResponse(
        car_id = 1,
        capacity_kwh = 65.7128,
        sample_count = 84,
        station_count = 1,
        stations = listOf(
            StationStatDto(
                place_key = "a10",
                sessions = 12,
                typical_power_kw = 55.0,
                typical_power_dc_kw = 55.0,
                typical_minutes_to_80 = 31,
                p90_minutes_to_80 = 86,
                measured_samples = listOf(StationMeasuredSampleDto(28, 35)),
                dc_never_reached_80 = 2,
                dc_never_reached_80_short = 1,
            )
        ),
    )

    @Test
    fun `映射后按 place_key 索引`() {
        val stats = stationStatsFromServer(resp)
        assertEquals("应当只有 1 个站", 1, stats.size)
        assertTrue("键必须是 place_key", stats.containsKey("a10"))
    }

    @Test
    fun `每个字段都要映射到`() {
        val s = stationStatsFromServer(resp)["a10"]!!
        assertEquals(12, s.sessions)
        assertEquals(55.0, s.typicalPowerKw, 1e-9)
        assertEquals(55.0, s.typicalPowerDcKw!!, 1e-9)
        assertEquals(31, s.typicalMinutesTo80)
        assertEquals(86, s.p90MinutesTo80)
        assertEquals(65.7128, s.batteryCapacityKwh, 1e-9)
        assertEquals(1, s.measuredSamples.size)
        assertEquals(28, s.measuredSamples[0].fromSoc)
        assertEquals(35, s.measuredSamples[0].minutes)
        assertEquals(2, s.dcNeverReached80)
        assertEquals(1, s.dcNeverReached80Short)
    }

    @Test
    fun `没有站时是空表而不是编一份`() {
        val empty = resp.copy(stations = emptyList())
        assertTrue(stationStatsFromServer(empty).isEmpty())
    }

    @Test
    fun `可空的数不许被编出来`() {
        val one = resp.copy(
            stations = listOf(
                resp.stations[0].copy(
                    typical_power_dc_kw = null,
                    typical_minutes_to_80 = null,
                    p90_minutes_to_80 = null,
                )
            )
        )
        val s = stationStatsFromServer(one)["a10"]!!
        assertNull(s.typicalPowerDcKw)
        assertNull(s.typicalMinutesTo80)
        assertNull(s.p90MinutesTo80)
    }
}
