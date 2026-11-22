package com.teslamate.app.ui

import com.teslamate.app.ConsumptionBucketDto
import com.teslamate.app.ConsumptionModel
import com.teslamate.app.ConsumptionModelDto
import com.teslamate.app.SpeedBucket
import com.teslamate.app.toConsumptionModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.183.0：能耗模型的**判定**已经搬到服务端**，这里只测两件事：**
 *   ① **映射**（服务端 DTO ⇒ 界面类型）不丢字段；
 *   ② **显示层的换算**（误差带、区间的非对称性、低温门槛）仍然对。
 *
 * ⚠️ **分桶 / 残差 / 参考车速那些判定已移到服务端**（api/consumption_model.go ＋
 * api/consumption_model_test.go）⇒ 客户端**不再自己拟合**（ADR #38：一份判定只许有一处）。
 */
class ConsumptionModelTest {

    /** 造一个模型（**直接构造** —— 判定在服务端）。 */
    private fun modelOf(
        bucket: SpeedBucket,
        cruise: Double = 70.0,
        residualP90: Double? = null,
        residualN: Int = 0,
        lowTemp: Int = 0,
        tempMin: Double? = 22.0,
        tempMax: Double? = 22.0,
    ) = ConsumptionModel(
        buckets = listOf(bucket),
        cruiseKph = cruise,
        tempMinC = tempMin,
        tempMaxC = tempMax,
        lowTempSamples = lowTemp,
        firstDrivenAt = "2026-09-01",
        lastDrivenAt = "2026-09-02",
        residualP90WhPerKm = residualP90,
        residualSamples = residualN,
    )

    /** 服务端那份模型（60–90 档 194 Wh/km，残差 P90 = 20）。 */
    private fun modelWithResidual(): ConsumptionModel =
        modelOf(SpeedBucket(60, 90, 194.0, 36), residualP90 = 20.0, residualN = 24)

    @Test
    fun `映射后字段一个不少`() {
        val dto = ConsumptionModelDto(
            car_id = 1,
            buckets = listOf(ConsumptionBucketDto(60, 90, 194.0, 36)),
            cruise_kph = 70.0,
            temp_min_c = 22.0,
            temp_max_c = 25.0,
            low_temp_samples = 3,
            first_driven_at = "2026-09-01",
            last_driven_at = "2026-09-02",
            residual_p90_wh_per_km = 20.0,
            residual_samples = 24,
            sample_count = 36,
        )
        val m = dto.toConsumptionModel()!!
        assertEquals(1, m.buckets.size)
        assertEquals(60, m.buckets[0].minKph)
        assertEquals(90, m.buckets[0].maxKph)
        assertEquals(194.0, m.buckets[0].whPerKm, 0.001)
        assertEquals(36, m.buckets[0].samples)
        assertEquals(70.0, m.cruiseKph, 0.001)
        assertEquals(22.0, m.tempMinC!!, 0.001)
        assertEquals(25.0, m.tempMaxC!!, 0.001)
        assertEquals(3, m.lowTempSamples)
        assertEquals("2026-09-01", m.firstDrivenAt)
        assertEquals("2026-09-02", m.lastDrivenAt)
        assertEquals(20.0, m.residualP90WhPerKm!!, 0.001)
        assertEquals(24, m.residualSamples)
    }

    @Test
    fun `没有桶就不给模型`() {
        val dto = ConsumptionModelDto(car_id = 1, buckets = emptyList())
        assertNull(dto.toConsumptionModel())
    }

    @Test
    fun `误差换算成电量百分点`() {
        val m = modelWithResidual()
        assertEquals(20.0, m.residualP90WhPerKm!!, 0.001)
        assertEquals(9, m.socErrorPoints(300.0, 65.8))
        assertNull(m.socErrorPoints(300.0, 0.0))
        assertNull(m.socErrorPoints(0.0, 65.8))
        assertNull(m.socErrorPoints(300.0, null))
    }

    @Test
    fun `低温样本数是服务端给的_不是写死的`() {
        val m = modelOf(SpeedBucket(0, 60, 170.0, 13), lowTemp = 3, tempMin = 8.0, tempMax = 25.0)
        assertEquals(3, m.lowTempSamples)
        assertTrue(!m.lowTempTrustworthy)
    }

    @Test
    fun `续航区间是有的且与点估计同源`() {
        val m = modelWithResidual()
        val base = m.trueRangeKm(ratedRangeKm = 300.0, speedKph = 70.0, outsideTempC = 22.0)
        assertTrue("点估计不该为 null", base != null)
        val band = m.trueRangeBandKm(ratedRangeKm = 300.0, speedKph = 70.0, outsideTempC = 22.0)
        assertTrue("区间不该为 null", band != null)
        val (lo, hi) = band!!
        assertTrue("区间下界应小于点估计: $lo vs $base", lo < base!!)
        assertTrue("区间上界应大于点估计: $hi vs $base", hi > base!!)
    }

    @Test
    fun `续航区间是非对称的`() {
        val m = modelWithResidual()
        val base = m.trueRangeKm(ratedRangeKm = 300.0, speedKph = 70.0, outsideTempC = 22.0)!!
        val (lo, hi) = m.trueRangeBandKm(ratedRangeKm = 300.0, speedKph = 70.0, outsideTempC = 22.0)!!
        val up = hi - base
        val down = base - lo
        assertTrue("上行应大于下行（乐观侧被放大得更多）: up=$up down=$down", up > down)
        val symmetric = base * 20.0 / 194.0
        assertTrue("对称近似应小于乐观侧: $symmetric vs $up", symmetric < up)
        assertTrue("对称近似应大于悲观侧: $symmetric vs $down", symmetric > down)
    }

    @Test
    fun `残差不足时不给区间`() {
        val m = modelOf(SpeedBucket(60, 90, 174.0, 23))
        assertNull(m.trueRangeBandKm(ratedRangeKm = 300.0, speedKph = 70.0, outsideTempC = 22.0))
        val m2 = modelWithResidual()
        assertNull(m2.trueRangeBandKm(ratedRangeKm = null, speedKph = 70.0, outsideTempC = 22.0))
        assertNull(m2.trueRangeBandKm(ratedRangeKm = 0.0, speedKph = 70.0, outsideTempC = 22.0))
    }

    @Test
    fun `低温不可信时连区间也不给`() {
        val m = modelOf(SpeedBucket(60, 90, 190.0, 39), lowTemp = 3, tempMin = 5.0, tempMax = 25.0)
        assertTrue(!m.lowTempTrustworthy)
        assertNull(m.trueRangeKm(ratedRangeKm = 300.0, speedKph = 70.0, outsideTempC = 5.0))
        assertNull(m.trueRangeBandKm(ratedRangeKm = 300.0, speedKph = 70.0, outsideTempC = 5.0))
    }
}
