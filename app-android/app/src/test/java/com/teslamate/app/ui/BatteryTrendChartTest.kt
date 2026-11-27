package com.teslamate.app.ui

import com.teslamate.app.BatteryTrendPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.199.0：电池健康卡「实测容量趋势」迷你图的取值与退化。**
 *
 * ## 这条曲线只负责给**形状**
 *
 * 数据是**服务端挑好的那批严格档点**（与判定衰减同一批）⇒ 客户端**只画**：
 * 不挑点、不排序、不算斜率（ADR #38）。
 *
 * 本文件钉的就是「画」的退化行为：
 * · 点太少 ⇒ 不画；
 * · 横轴没有跨度 ⇒ 不画；
 * · 容量全一样 ⇒ 画一条水平线（**平**也是信息）。
 */
class BatteryTrendChartTest {

    /** 少于两个点 ⇒ 不画（一条线说明不了趋势）。 */
    @Test
    fun fewerThanTwoPointsMeansNoChart() {
        assertNull(batteryTrendScale(emptyList()))
        assertNull(batteryTrendScale(listOf(BatteryTrendPoint(1000.0, 65.4))))
    }

    /** 横轴没有跨度（都在同一里程）⇒ 不画。 */
    @Test
    fun sameOdometerMeansNoChart() {
        val pts = listOf(BatteryTrendPoint(1000.0, 65.4), BatteryTrendPoint(1000.0, 66.0))
        assertNull(batteryTrendScale(pts))
    }

    /** 范围要罩住所有点，且纵轴**留余量**（点不贴边）。 */
    @Test
    fun scaleCoversAllPointsWithMargin() {
        val pts = listOf(BatteryTrendPoint(1000.0, 64.0), BatteryTrendPoint(2000.0, 66.0))
        val s = batteryTrendScale(pts)
        assertNotNull(s)
        assertEquals(1000f, s!!.xMin, 0.001f)
        assertEquals(2000f, s.xMax, 0.001f)
        assertTrue("纵轴下界必须留余量", s.yMin < 64f)
        assertTrue("纵轴上界必须留余量", s.yMax > 66f)
    }

    /** 容量全一样 ⇒ 仍然要能画（一条水平线 ⇒ 「没在掉」也是信息）。 */
    @Test
    fun flatCapacityStillRenders() {
        val pts = listOf(BatteryTrendPoint(1000.0, 65.4), BatteryTrendPoint(2000.0, 65.4))
        val s = batteryTrendScale(pts)
        assertNotNull(s)
        assertTrue(s!!.yMax > s.yMin)
    }
}
