package com.teslamate.app.ui

import com.teslamate.app.CarStatus
import com.teslamate.app.ConsumptionModel
import com.teslamate.app.RangeBand
import com.teslamate.app.RangeEstimate
import com.teslamate.app.SpeedBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ⚠️ **v0.119.0：续航结论「服务端优先 + 本地兜底」的映射单测。**
 *
 * ## 这几条断言在防什么
 *
 * - 「服务端结论原样落到事实里」⇒ 防止**字段接错**（把区间当成保底、把样本数当成档位样本数）；
 * - 「有服务端结论时优先用服务端」⇒ 防止**优先级写反**（又悄悄用回本地模型）；
 * - 「服务端缺席时退回本地模型」⇒ 防止服务器挂掉时**续航直接空掉**；
 * - 「本地兜底算出来的数与口径一致」⇒ 防止这次重构**动了兜底那条路的数**。
 */
class RangeFactsServerTest {

    private fun status(
        battery: Int? = 50,
        rated: Double? = 226.68,
        temp: Double? = 29.5,
    ) = CarStatus(
        car_id = 1,
        updated_at = Instant.parse("2026-11-03T04:00:00Z"),
        battery_level = battery,
        rated_range_km = rated,
        ideal_range_km = rated,
        outside_temp = temp,
        speed = 0,
    )

    /** 本地模型：一档市区桶 + 残差 P90（数值取自线上真实模型）。 */
    private fun localModel() = ConsumptionModel(
        buckets = listOf(SpeedBucket(0, 60, 167.10729916098938, 50)),
        cruiseKph = 51.0,
        tempMinC = 8.3,
        tempMaxC = 36.8,
        lowTempSamples = 1,
        firstDrivenAt = "2026-07-31",
        lastDrivenAt = "2026-11-01",
        residualP90WhPerKm = 23.149791358844254,
        residualSamples = 20,
    )

    @Test
    fun 服务端结论原样落到事实里() {
        val server = RangeEstimate(
            true_range_km = 170.56772567584196,
            true_range_band_km = RangeBand(151, 197),
            floor_range_km = 113.71181711722797,
            floor_range_band_km = RangeBand(96, 138),
            wh_per_km = 167.10729916098938,
            bucket_label = "0–60",
            bucket_samples = 50,
            bucket_exact = true,
            reference_speed_kph = 51.0,
            speed_assumed = false,
            temp_caveat = "",
            temp_min_c = 8.3,
            temp_max_c = 36.8,
            low_temp_samples = 1,
            residual_samples = 20,
            sample_count = 92,
            rated_range_km = 226.68,
            outside_temp_c = 29.5,
        )
        val f = rangeFactsFromServer(server)
        assertEquals(170.56772567584196, f.trueRange!!, 1e-9)
        assertEquals(151, f.bandLo!!)
        assertEquals(197, f.bandHi!!)
        assertEquals(113.71181711722797, f.floorKm!!, 1e-9)
        assertEquals(96, f.floorBandLo!!)
        assertEquals(138, f.floorBandHi!!)
        assertEquals(167.10729916098938, f.whPerKm!!, 1e-9)
        assertEquals("0–60", f.bucketLabel)
        assertTrue(f.whExact)
        assertEquals(92, f.sampleCount!!)
        assertEquals(20, f.residualSamples)
        assertEquals(8.3, f.tempMinC!!, 1e-9)
        assertEquals(36.8, f.tempMaxC!!, 1e-9)
        assertEquals(1, f.lowTempSamples)
        assertEquals(226.68, f.ratedRange!!, 1e-9)
        assertEquals(29.5, f.outsideTemp!!, 1e-9)
        assertEquals(51.0, f.refSpeed, 1e-9)
        // ⚠️ 空串 = 没有温度说明 ⇒ 事实层必须是 null（不能是空串 ⇒ 界面会多出一个「 · 」）。
        assertNull(f.tempCaveat)
    }

    @Test
    fun 有服务端结论时优先用服务端() {
        val server = RangeEstimate(
            true_range_km = 999.0,
            true_range_band_km = RangeBand(900, 1000),
            sample_count = 7,
        )
        val f = resolveRangeFacts(server, status(), localModel(), null, null)
        assertEquals(999.0, f.trueRange!!, 1e-9)
        assertEquals(900, f.bandLo!!)
        assertEquals(7, f.sampleCount!!)
    }

    @Test
    fun 服务端缺席时退回本地模型() {
        val f = resolveRangeFacts(null, status(), localModel(), null, null)
        // 本地模型的数：标定 226.68 × 146 ÷ (实测 Wh/km × 温度修正) × 0.9
        // ⚠️ **温度修正在分母里**（whEff = wh × tempFactor）—— 写成乘在分子上会差 9%。
        val wh = 167.10729916098938
        val tempFactor = 1.0 + minOf(0.10, 0.006 * (29.5 - 22.0))
        val expected = 226.68 * 146.0 / (wh * tempFactor) * 0.90
        // ⚠️ 这个数与**服务端端点线上实测值逐位相同**（2026-11-03）⇒ 顺手钉住它。
        assertEquals(170.56772567584196, expected, 1e-9)
        assertEquals(expected, f.trueRange!!, 1e-9)
        // 「留 20% 保底」：可用比例 = (50 − 20) ÷ 50 = 0.6
        val expectedFloor = 226.68 * 146.0 / (wh * tempFactor) * 0.6
        assertEquals(expectedFloor, f.floorKm!!, 1e-9)
        // 来源自曝用**不换行空格**（ADR #42）⇒ 断言的是同一个字符，不是普通空格。
        assertEquals("50\u00a0条实测", buildRangeModel(f).scopeLine)
        assertEquals(50, f.sampleCount!!)
    }

    @Test
    fun 本地兜底与服务端同源时数字一致() {
        // ⚠️ 同一个模型：服务端那份与本地那份算出来的数必须**相同**（同源样本 + 同一套规则）。
        val wh = 167.10729916098938
        val tempFactor = 1.0 + minOf(0.10, 0.006 * (29.5 - 22.0))
        val expected = 226.68 * 146.0 / (wh * tempFactor) * 0.90
        val server = RangeEstimate(
            true_range_km = expected,
            true_range_band_km = null,
            floor_range_km = 226.68 * 146.0 / (wh * tempFactor) * 0.6,
            floor_range_band_km = null,
            wh_per_km = wh,
            bucket_label = "0–60",
            bucket_exact = true,
            reference_speed_kph = 51.0,
            temp_min_c = 8.3,
            temp_max_c = 36.8,
            low_temp_samples = 1,
            sample_count = 50,
            rated_range_km = 226.68,
            outside_temp_c = 29.5,
        )
        val fromServer = rangeFactsFromServer(server)
        val fromLocal = rangeFactsFromLocal(status(), localModel(), null, null)
        assertEquals(fromLocal.trueRange!!, fromServer.trueRange!!, 1e-9)
        assertEquals(fromLocal.floorKm!!, fromServer.floorKm!!, 1e-9)
        assertEquals(fromLocal.whPerKm!!, fromServer.whPerKm!!, 1e-9)
    }
}
