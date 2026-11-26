package com.teslamate.app.ui

import com.teslamate.app.BatteryDecay
import com.teslamate.app.BatteryHealth
import com.teslamate.app.BatteryMeasurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.189.0：车况页「电池健康」卡的取值与降级。**
 *
 * ## 这张卡的原则：**只透传服务端，不自己算**
 *
 * 「健康度」「衰减结论」「哪一次充电测的」全都来自服务端 `GET /cars/{id}/battery`
 * ⇒ 客户端**只做排版**（ADR #38 / ADR #41）。
 * 本文件钉的就是这条：**取到的数与服务端逐字一致**，以及**缺什么就不显示什么**。
 */
class BatteryHealthCardTest {

    /** 线上真实形状（2026-11-25 那份 /battery）。 */
    private fun online(): BatteryHealth = BatteryHealth(
        current_capacity_kwh = 65.18545499999999,
        max_capacity_kwh = 66.49198437499999,
        nominal_capacity_kwh = 78.4,
        health_percent = 83.14471301020407,
        factory_rated_range_km = 538.7817389033943,
        health_percent_of_baseline = 98.8033221500129,
        baseline_capacity_kwh = 65.97496276595744,
        max_capacity_kwh_strict = 66.142845,
        series_span_days = 111,
        odometer_span_km = 11936.086567999999,
        decay = BatteryDecay(
            n = 20,
            conclusion = "not_detectable",
            has_step = true,
            underlying_ci_lo = -0.052,
            underlying_ci_hi = 0.054,
            note = "20 个 100% 满充点 · 11559 公里 / 104 天",
        ),
        measurement = BatteryMeasurement(
            at = "2026-11-21T05:45:49.056Z",
            age_days = 4,
            note = "基于 11-21 那次充电实测（4 天前）",
            note_short = "11-21 实测",
            stale = false,
        ),
    )

    /**
     * ⚠️ **什么都没有 ⇒ 整张卡不出现**（宁可不说，也不说半句）。
     */
    @Test
    fun `什么都没有时整张卡不出现`() {
        assertNull(batteryHealthCardData(BatteryHealth()))
    }

    /**
     * ⚠️ **只有容量、没有健康度** ⇒ 卡还在（还能装多少度也是车主要的），
     * 但**不许**显示一个空百分比。
     */
    @Test
    fun `有容量没健康度时卡还在但不显示百分比`() {
        val b = online().copy(health_percent = null)
        val d = batteryHealthCardData(b)
        assertNotNull("有容量就该有这张卡", d)
        assertNull("没有健康度就不许显示百分比", d!!.healthPercent)
        assertEquals(65.18545499999999, d.capacityKwh!!, 1e-9)
    }

    /**
     * ⚠️ **健康度必须是服务端那个数**（客户端不许拿两个容量相除）。
     */
    @Test
    fun `健康度逐字取服务端那个数`() {
        val b = online()
        val d = batteryHealthCardData(b)!!
        assertEquals(b.health_percent!!, d.healthPercent!!, 1e-12)
        assertEquals(b.health_percent_of_baseline!!, d.baselinePercent!!, 1e-12)
    }

    /**
     * ⚠️ **时间诚实性**：副标题必须带「哪一次充电测的」（服务端给的短文案）。
     */
    @Test
    fun `副标题带服务端给的测量时刻`() {
        val d = batteryHealthCardData(online())!!
        val subtitle = batteryCapacitySubtitle(d.capacityKwh, null, d.measurementNoteShort)
        assertTrue("副标题必须写明是哪一次充电测的：$subtitle", subtitle.contains("11-21 实测"))
    }

    /**
     * ⚠️ **衰减结论按服务端的结论强度说话**（客户端不下「显著 / 不显著」的判断）。
     */
    @Test
    fun `衰减结论按服务端强度`() {
        val b = online()
        val d = batteryHealthCardData(b)!!
        assertEquals(BatteryDecayText.headline(b.decay!!), d.decayHeadline)
        assertTrue(
            "本机是「检不出趋势」，不该写成「显著」：${d.decayHeadline}",
            d.decayHeadline!!.contains("检不出趋势"),
        )
    }

    /**
     * ⚠️ **服务端没给依据 ⇒ 不显示依据**（老服务端）⇒ 不说半句。
     */
    @Test
    fun `服务端没给依据时不显示依据`() {
        val b = online().copy(decay = online().decay!!.copy(note = ""))
        val d = batteryHealthCardData(b)!!
        assertNull("服务端没给依据就不许显示依据", d.decayNote)
    }

    /**
     * ⚠️ **累计循环那一句必须逐字取服务端**（App 卡片与飞书体检报告同一句）。
     */
    @Test
    fun `累计循环那一句逐字取服务端`() {
        val b = online().copy(
            usage = com.teslamate.app.BatteryUsage(
                sessions = 87,
                dc_sessions = 79,
                ac_sessions = 8,
                efc = 27.071918556122444,
                line = "累计等效满充循环 27.1 次（直流 79 / 交流 8 次充电）",
                note = "循环次数 = 充入电池的度 ÷ 铭牌可用度（从空到满算 1 次）。",
            )
        )
        val d = batteryHealthCardData(b)!!
        assertEquals("累计等效满充循环 27.1 次（直流 79 / 交流 8 次充电）", d.usageLine)
        assertNotNull("口径说明也要带过去", d.usageNote)
        // 服务端没给（老服务端 / 没充电记录）⇒ **不显示**（宁可不说，也不说半句）。
        val none = batteryHealthCardData(online().copy(usage = null))!!
        assertNull(none.usageLine)
        assertNull(none.usageNote)
    }

    /**
     * ⚠️ **覆盖范围与服务端一致**（不说清楚会被当成「电池的全部历史」）。
     */
    @Test
    fun `覆盖范围与服务端一致`() {
        val b = online()
        val d = batteryHealthCardData(b)!!
        assertEquals(b.series_span_days, d.spanDays)
        assertEquals(b.odometer_span_km!!, d.spanKm!!, 1e-9)
        assertEquals(b.decay!!.n, d.strictPoints)
    }
}
