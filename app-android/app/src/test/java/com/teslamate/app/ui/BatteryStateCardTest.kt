package com.teslamate.app.ui

import com.teslamate.app.ChargeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.198.0：车况页「电池状态」卡的取值与降级。**
 *
 * ## 这张卡的原则：**只透传服务端，不自己算、不自己拼**
 *
 * 「在不在充」「上限多少」「功率多少」全都来自服务端
 * （车端 charge_state，Fleet API 官方字段）⇒ 客户端**只做排版**
 * （ADR #38 / ADR #41）。
 *
 * 本文件钉的就是这条：**取到的值与服务端逐字一致**，
 * 以及**缺什么就不显示什么**（没给结论 ⇒ 整张卡不出现）。
 */
class BatteryStateCardTest {

    /** 结论与数字明细**逐字**上屏（客户端一个字都不改）。 */
    @Test
    fun valuesArePassedThroughVerbatim() {
        val cs = ChargeStatus(
            state = "Charging",
            conclusion = "正在充电",
            detail = "当前 68% · 上限 90% · 120 kW · 剩 12 分钟",
            soc = 68,
            limit_soc = 90,
            power_kw = 120.0,
            minutes_to_full = 12,
            available = true,
        )
        val d = batteryStateCardData(cs)
        assertNotNull(d)
        assertEquals("正在充电", d!!.conclusion)
        assertEquals("当前 68% · 上限 90% · 120 kW · 剩 12 分钟", d.detail)
        assertFalse(d.stale)
    }

    /** 服务端没给结论 ⇒ **整张卡不出现**（宁可不说，也不说半句）。 */
    @Test
    fun noConclusionMeansNoCard() {
        assertNull(batteryStateCardData(ChargeStatus()))
        assertNull(batteryStateCardData(ChargeStatus(state = "Unknown", conclusion = "   ")))
    }

    /** 车睡着 ⇒ stale ⇒ 界面显示「上次已知：…」（不当现状、不当告警）。 */
    @Test
    fun staleIsCarriedThrough() {
        val d = batteryStateCardData(
            ChargeStatus(conclusion = "未插枪", detail = "上次 41% · 上限 100%", stale = true, available = true)
        )
        assertNotNull(d)
        assertTrue(d!!.stale)
        assertEquals("未插枪", d.conclusion)
    }

    /** **读不到**时（车端没有任何实时数据）⇒ available=false ⇒ 界面降级成小字。 */
    @Test
    fun unavailableIsCarriedThrough() {
        val d = batteryStateCardData(
            ChargeStatus(conclusion = "车离线，读不到充电状态", available = false)
        )
        assertNotNull(d)
        assertFalse(d!!.available)
        assertEquals("车离线，读不到充电状态", d.conclusion)
        assertNull(d.detail)
    }

    /** 明细为空（服务端只给了结论）⇒ 不显示那一行，但卡还在。 */
    @Test
    fun detailMayBeAbsent() {
        val d = batteryStateCardData(ChargeStatus(conclusion = "未插枪", available = true))
        assertNotNull(d)
        assertNull(d!!.detail)
    }
}
