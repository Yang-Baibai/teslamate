package com.teslamate.app.ui

import com.teslamate.app.StationLastSessionDto
import com.teslamate.app.StationStatDto
import com.teslamate.app.StationStatsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ⚠️ **v0.187.0**：「该站最近一次」从服务端到界面的那一票。
 *
 * 判据：① 映射**逐项**相等（App 不自己算）；② 没有最近一次 ⇒ **那一行不出现**（不编）。
 */
class StationLastSessionTest {
    private fun dto(withSession: Boolean) = StationStatsResponse(
        capacity_kwh = 65.7128378378378,
        stations = listOf(
            StationStatDto(
                place_key = "a10",
                sessions = 13,
                typical_power_kw = 29.0,
                last_session = if (withSession) StationLastSessionDto(
                    // ⚠️ 用**中午**的 UTC 时刻 ⇒ 任何时区都落在同一天（断言不受时区影响）。
                    at = Instant.parse("2026-11-21T12:05:54Z"),
                    from_soc = 15,
                    to_soc = 100,
                    minutes = 100,
                    energy_kwh = 55.72,
                    power_kw = 33.432,
                    is_dc = true,
                    note = "最近一次 = 最近一场充入 ≥ 0.5 度的充电（不含「插了一下没充上」）",
                ) else null,
            )
        ),
    )

    @Test
    fun lastSession_mapsFromServer() {
        val stat = stationStatsFromServer(dto(true))["a10"]!!
        val ls = stat.lastSession!!
        assertEquals(100, ls.minutes)
        assertEquals(15, ls.fromSoc)
        assertEquals(100, ls.toSoc)
        assertEquals(33.432, ls.powerKw!!, 1e-9)
        assertEquals(55.72, ls.energyKwh!!, 1e-9)
        assertTrue(ls.isDc)
        assertTrue("口径没透传：" + ls.note, ls.note.contains("0.5 度"))
        val line = stat.lastSessionLine()!!
        assertTrue("日期不对：" + line, line.contains("2026-11-21"))
        assertTrue("起止电量不对：" + line, line.contains("15% → 100%"))
        assertTrue("分钟数不对：" + line, line.contains("100 分钟"))
        assertTrue("功率不对：" + line, line.contains("33.4 kW"))
    }

    @Test
    fun noLastSession_noLine() {
        val stat = stationStatsFromServer(dto(false))["a10"]!!
        assertNull(stat.lastSession)
        assertNull(stat.lastSessionLine())
    }
}