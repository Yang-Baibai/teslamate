package com.teslamate.app.ui

import com.teslamate.app.ConsumptionModel
import com.teslamate.app.Drive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ⚠️ **v0.146.0：本地兜底的口径不许丢。**
 *
 * ## 为什么要有这条
 *
 * 服务端那条路一直会说「车外温度读不到 ⇒ 未按气温修正 · 可能偏乐观」，
 * 而**本地兜底**那条路（服务端挂掉 / 还没取到）原来在读不到温度时**直接返回 null**
 * ⇒ 界面上**一句口径提示都没有** ⇒ 用户以为那个续航数是按气温修正过的 ⇒ **假安全**。
 *
 * ⇒ 这条测试钉住的是**行为**（「不许返回 null」）；
 * 两端文案**不许漂**由客户端闸门 35 跨文件比对（那个是源码级判据）。
 */
class TempCaveatCopyTest {

    private fun drive(
        id: Int,
        km: Double,
        kph: Int,
        wh: Double,
        temp: Double? = 22.0,
    ) = Drive(
        id = id,
        start_date = Instant.parse("2026-09-01T00:00:00Z").plusSeconds(id * 3600L),
        duration_min = (km / (kph / 60.0)).toInt(),
        distance_km = km,
        wh_per_km = wh,
        avg_speed_kph = kph,
        outside_temp_avg = temp,
    )

    private fun model(): ConsumptionModel {
        val drives = buildList {
            var id = 1
            repeat(3) { add(drive(id++, 30.0, 50, 168.0)) }
            repeat(3) { add(drive(id++, 30.0, 70, 174.0)) }
            repeat(3) { add(drive(id++, 30.0, 100, 183.0)) }
            repeat(3) { add(drive(id++, 30.0, 120, 197.0)) }
            repeat(3) { add(drive(id++, 30.0, 140, 210.0)) }
        }
        return ConsumptionModel.from(drives)!!
    }

    @Test
    fun `读不到车外温度时必须给出口径_不许是null`() {
        val cav = model().tempCaveat(null)
        assertNotNull("读不到温度时必须给出口径，不能是 null（那是假安全）", cav)
        for (must in listOf("读不到", "气温修正", "偏乐观")) {
            assertTrue("口径里丢了关键事实 $must：$cav", cav!!.contains(must))
        }
        assertEquals("车外温度读不到 ⇒ 未按气温修正 · 可能偏乐观", cav)
    }

    @Test
    fun `低温两条口径与服务端同文`() {
        val m = model()
        val low = m.tempCaveat(5.0)
        assertNotNull(low)
        assertTrue("低温口径丢了关键事实：$low", low!!.startsWith("低温未修正：≤10°C 只有 "))
        val mid = m.tempCaveat(15.0)
        assertNotNull(mid)
        assertTrue("未做低温修正口径丢了关键事实：$mid", mid!!.startsWith("未做低温修正（≤10°C 样本 "))
    }

    @Test
    fun `高温区间不做提示`() {
        assertNull(model().tempCaveat(30.0))
    }
}
