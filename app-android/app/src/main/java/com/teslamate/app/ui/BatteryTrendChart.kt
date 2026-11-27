package com.teslamate.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.teslamate.app.BatteryTrendPoint

/**
 * ⚠️ **v0.199.0：电池健康卡里的「实测容量趋势」迷你图。**
 *
 * ## 为什么是「迷你图」而不是完整坐标系
 *
 * Apple 式克制：卡上已经有数字（现在还能装 65.4 度 / 相对首测 98.8% / 检不出趋势）
 * ⇒ 图只负责给**形状**（有没有在掉），**不再重复数字**
 * ⇒ **不画坐标轴、不画刻度数字**。
 *
 * ## 数据来源：服务端挑好的那批点（[com.teslamate.app.BatteryHealth.trend_points]）
 *
 * ⚠️ **客户端只画，不挑**（ADR #38）—— 「哪些点算数」是**口径**：
 * 服务端给的就是**判定衰减用的那批严格档点**
 * ⇒ 图上看到的形状 **就是** 结论依据的形状（界面与结论不会自相矛盾）。
 *
 * ## 退化
 *
 * · 少于 2 个点 ⇒ **不画**（一条线说明不了趋势）；
 * · 里程全一样（横轴没有跨度）⇒ 不画；
 * · 容量全一样 ⇒ 以该值为中心画一条水平线（形状是「平的」⇒ **这也是信息**）。
 */
data class TrendScale(
    /** 横轴下界（km）。 */
    val xMin: Float,
    /** 横轴上界（km）。 */
    val xMax: Float,
    /** 纵轴下界（度，已留余量）。 */
    val yMin: Float,
    /** 纵轴上界（度，已留余量）。 */
    val yMax: Float,
)

/**
 * 算趋势图要用的取值范围（纯函数，可单测）。
 *
 * @return null ⇒ **不画**（点太少、或横轴没有跨度）。
 */
fun batteryTrendScale(points: List<BatteryTrendPoint>): TrendScale? {
    if (points.size < 2) return null
    var xMin = Float.MAX_VALUE
    var xMax = -Float.MAX_VALUE
    var yMin = Float.MAX_VALUE
    var yMax = -Float.MAX_VALUE
    for (p in points) {
        if (p.km < xMin) xMin = p.km.toFloat()
        if (p.km > xMax) xMax = p.km.toFloat()
        if (p.kwh < yMin) yMin = p.kwh.toFloat()
        if (p.kwh > yMax) yMax = p.kwh.toFloat()
    }
    // ⚠️ 横轴没跨度（所有点在同一里程）⇒ 画出来是一条竖线 ⇒ 没有信息 ⇒ 不画。
    if (xMax - xMin < 1e-3f) return null
    // ⚠️ 纵轴留 8% 余量 ⇒ 点不贴边（贴边的点看着像「顶到天花板」）。
    val pad = ((yMax - yMin) * 0.08f).coerceAtLeast(0.05f)
    return TrendScale(xMin, xMax, yMin - pad, yMax + pad)
}

/**
 * ⚠️ **v0.199.0**：实测容量趋势迷你图。
 *
 * @param points **服务端挑好**的那批点（已按里程升序）。
 */
@Composable
fun BatteryTrendChart(
    points: List<BatteryTrendPoint>,
    modifier: Modifier = Modifier,
) {
    val scale = batteryTrendScale(points) ?: return
    // ⚠️ 颜色用**能量蓝**（项目里「电量 / 功率 / SOC 曲线」的统一表达）
    // ⇒ 不用特斯拉红（红线只表示告警 / 品牌点缀，不用于表达健康状态）。
    val accents = LocalAccentColors.current
    val lineColor = accents.energyBlue
    val dotColor = accents.energyBlue

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
    ) {
        val w = size.width
        val h = size.height
        val xSpan = (scale.xMax - scale.xMin).coerceAtLeast(1e-6f)
        val ySpan = (scale.yMax - scale.yMin).coerceAtLeast(1e-6f)

        fun px(km: Float): Float = ((km - scale.xMin) / xSpan) * w
        fun py(kwh: Float): Float = h - ((kwh - scale.yMin) / ySpan) * h

        val path = Path()
        points.forEachIndexed { i, p ->
            val x = px(p.km.toFloat())
            val y = py(p.kwh.toFloat())
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
        )
        points.forEach { p ->
            drawCircle(
                color = dotColor,
                radius = 2.5.dp.toPx(),
                center = Offset(px(p.km.toFloat()), py(p.kwh.toFloat())),
            )
        }
        // ⚠️ **最新那一点**（里程最大）画实心大点 ⇒ 眼睛能一下找到「现在」。
        val last = points.maxByOrNull { it.km } ?: return@Canvas
        drawCircle(
            color = lineColor,
            radius = 4.5.dp.toPx(),
            center = Offset(px(last.km.toFloat()), py(last.kwh.toFloat())),
        )
    }
}
