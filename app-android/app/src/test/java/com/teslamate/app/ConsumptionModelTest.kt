package com.teslamate.app.ui

import com.teslamate.app.ConsumptionModel
import com.teslamate.app.Drive
import com.teslamate.app.SpeedBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ⚠️ **v0.37.0：客户端 JVM 单元测试（第一批）。**
 *
 * ## 为什么现在才做
 *
 * 这个项目连续三轮评审都只能靠「读源码 + 手工复算」发现问题 ——
 * 因为客户端**从来没有过任何单测**。
 * v0.36.0 那个「上次实测到站剩 Y% 在 6/35 个站取错场次」的 P0，
 * 如果当时有单测，**第一版就能挡住**。
 *
 * 这批测试盯的是**纯逻辑**（不碰 Compose、不碰 Android）：
 * 分桶口径、残差 P90、误差换算。
 */
class ConsumptionModelTest {

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

    @Test
    fun `分桶边界与客户端一致`() {
        // 每个档 3 条，wh 都取同一值 → 档位中位数就是那个值。
        val drives = buildList {
            var id = 1
            // 0–60
            repeat(3) { add(drive(id++, 30.0, 50, 168.0)) }
            // 60–90
            repeat(3) { add(drive(id++, 30.0, 70, 174.0)) }
            // 90–110
            repeat(3) { add(drive(id++, 30.0, 100, 183.0)) }
            // 110–130
            repeat(3) { add(drive(id++, 30.0, 120, 197.0)) }
            // 130+（末档开区间）
            repeat(3) { add(drive(id++, 30.0, 140, 210.0)) }
        }
        val m = ConsumptionModel.from(drives)
        assertTrue("模型不应为 null", m != null)
        val labels = m!!.buckets.map { it.label }
        assertEquals(listOf("0–60", "60–90", "90–110", "110–130", "130+"), labels)
        assertEquals(168.0, m.buckets[0].whPerKm, 0.001)
        assertEquals(210.0, m.buckets[4].whPerKm, 0.001)
        // 档位下界是**闭**的、上界是**开**的
        assertEquals(0, m.buckets[0].minKph)
        assertEquals(Int.MAX_VALUE, m.buckets[4].maxKph)
    }

    @Test
    fun `样本不足 2 条的档不建`() {
        val drives = buildList {
            var id = 1
            repeat(3) { add(drive(id++, 30.0, 50, 168.0)) }
            // 60–90 只有 1 条 → 不建档
            add(drive(id++, 30.0, 70, 174.0))
        }
        val m = ConsumptionModel.from(drives)
        assertTrue(m != null)
        assertEquals(listOf("0–60"), m!!.buckets.map { it.label })
    }

    @Test
    fun `残差 P90 只用长途样本`() {
        // 同一档里放三组：长途 174、长途 194（档位中位数 = 194）、短途 374。
        // 于是长途的残差是 |174−194| = 20 与 |194−194| = 0，短途的是 180。
        // 如果错误地把短途也算进来，P90 会被拉飞到 180 附近。
        val drives = buildList {
            var id = 1
            repeat(12) { add(drive(id++, 150.0, 70, 174.0)) }   // 长途，残差 20
            repeat(12) { add(drive(id++, 150.0, 70, 194.0)) }   // 长途，残差 0
            repeat(12) { add(drive(id++, 25.0, 70, 374.0)) }    // 短途，残差 180
        }
        val m = ConsumptionModel.from(drives)
        assertTrue(m != null)
        // 只用 km≥100 的 24 条 → 残差 20×12 + 0×12 → P90 = 20 Wh/km
        assertEquals(20.0, m!!.residualP90WhPerKm!!, 0.001)
    }

    @Test
    fun `残差样本不足时不给数`() {
        // km≥100 的不足 10 条 → residualP90 = null（宁可不给数）
        val drives = buildList {
            var id = 1
            repeat(3) { add(drive(id++, 150.0, 70, 194.0)) }
            repeat(20) { add(drive(id++, 25.0, 70, 174.0)) }
        }
        val m = ConsumptionModel.from(drives)
        assertTrue(m != null)
        assertNull(m!!.residualP90WhPerKm)
        assertNull(m.socErrorPoints(300.0, 65.8))
    }

    @Test
    fun `误差换算成电量百分点`() {
        val drives = buildList {
            var id = 1
            repeat(12) { add(drive(id++, 150.0, 70, 174.0)) }
            repeat(12) { add(drive(id++, 150.0, 70, 194.0)) }
            repeat(12) { add(drive(id++, 25.0, 70, 374.0)) }
        }
        val m = ConsumptionModel.from(drives)!!
        assertEquals(20.0, m.residualP90WhPerKm!!, 0.001)
        // 20 Wh/km × 300 km ÷ 1000 = 6 kWh；6 ÷ 65.8 × 100 = 9.1 个点
        assertEquals(9, m.socErrorPoints(300.0, 65.8))
        // 容量为 0 或里程为 0 → 不给数
        assertNull(m.socErrorPoints(300.0, 0.0))
        assertNull(m.socErrorPoints(0.0, 65.8))
        assertNull(m.socErrorPoints(300.0, null))
    }

    @Test
    fun `低温样本数是算出来的不是写死的`() {
        val drives = buildList {
            var id = 1
            repeat(10) { add(drive(id++, 40.0, 60, 170.0, temp = 25.0)) }
            repeat(3) { add(drive(id++, 40.0, 60, 170.0, temp = 8.0)) }
        }
        val m = ConsumptionModel.from(drives)!!
        assertEquals(3, m.lowTempSamples)
        // 3 < 5 → 低温修正不可信
        assertTrue(!m.lowTempTrustworthy)
    }
}
