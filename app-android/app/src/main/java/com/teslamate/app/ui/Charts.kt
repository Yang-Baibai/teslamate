package com.teslamate.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

data class ChartSeries(
    val values: List<Float?>,
    val color: Color,
    val label: String,
)

/** 数据入场生长动画进度（数据从无到有时 0→1） */
@Composable
private fun chartProgress(hasData: Boolean): Float {
    val progress by animateFloatAsState(
        targetValue = if (hasData) 1f else 0f,
        animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing),
        label = "chartGrow",
    )
    return progress
}

/**
 * 柱状图：支持多系列堆叠（如 直流+交流）。
 * 入场时从 0 生长；峰值自动标注。
 */
@Composable
fun StackedBarChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    peakFmt: (Float) -> String = { String.format(Locale.CHINA, "%.0f", it) },
) {
    val n = series.firstOrNull()?.values?.size ?: 0
    val textMeasurer = rememberTextMeasurer()
    val contentColor = LocalContentColor.current
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val hasData = series.any { s -> s.values.any { (it ?: 0f) > 0f } }
    val progress = chartProgress(hasData)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(150.dp),
    ) {
        if (n == 0) return@Canvas
        val totals = (0 until n).map { i -> series.sumOf { (it.values.getOrNull(i) ?: 0f).toDouble() }.toFloat() }
        val maxV = (totals.maxOrNull() ?: 0f).coerceAtLeast(0.001f)
        val topPad = 22.dp.toPx()
        val gap = 3.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n

        totals.forEachIndexed { i, t ->
            val x = i * (bw + gap)
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(x, topPad),
                size = Size(bw, size.height - topPad),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            var curY = size.height
            series.forEach { s ->
                val v = s.values.getOrNull(i) ?: 0f
                if (v > 0f) {
                    val h = v / maxV * (size.height - topPad) * progress
                    curY -= h
                    drawRoundRect(
                        color = s.color,
                        topLeft = Offset(x, curY),
                        size = Size(bw, h),
                        cornerRadius = CornerRadius(4.dp.toPx()),
                    )
                }
            }
        }
        val maxi = totals.indices.maxBy { totals[it] }
        if (totals[maxi] > 0f) {
            val x = (maxi * (bw + gap)).coerceIn(0f, size.width - 120f)
            drawText(
                textMeasurer = textMeasurer,
                text = peakFmt(totals[maxi]),
                topLeft = Offset(x, 0f),
                style = TextStyle(fontSize = 11.sp, color = contentColor),
            )
        }
    }
}

/** 折线图（单系列，值可含 null 断点）；入场从左到右描画 */
@Composable
fun LineChart(
    values: List<Float?>,
    color: Color,
    modifier: Modifier = Modifier,
    fill: Boolean = true,
) {
    val textMeasurer = rememberTextMeasurer()
    val contentColor = LocalContentColor.current
    val hasData = values.any { it != null && it > 0f }
    val progress = chartProgress(hasData)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(150.dp),
    ) {
        if (values.size < 2) return@Canvas
        val present = values.filterNotNull()
        if (present.isEmpty()) return@Canvas
        val maxV = present.max().coerceAtLeast(0.001f)
        val topPad = 22.dp.toPx()
        val stepX = size.width / (values.size - 1)
        fun y(v: Float) = size.height - (v / maxV) * (size.height - topPad)

        val path = Path()
        var started = false
        values.forEachIndexed { i, v ->
            if (v == null) {
                started = false
                return@forEachIndexed
            }
            val p = Offset(i * stepX, y(v))
            if (!started) {
                path.moveTo(p.x, p.y)
                started = true
            } else {
                path.lineTo(p.x, p.y)
            }
        }

        // 从左到右描画动画：clip 宽度随 progress 增长
        val clipRight = size.width * progress
        clipRect(right = clipRight) {
            if (fill) {
                val fillPath = Path().apply {
                    addPath(path)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(
                    fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(color.copy(alpha = 0.35f), Color.Transparent),
                        startY = topPad,
                        endY = size.height,
                    ),
                )
            }
            drawPath(path, color = color, style = Stroke(width = 2.5.dp.toPx()))
        }
        if (progress >= 1f) {
            val maxi = values.indices.filter { values[it] != null }.maxBy { values[it]!! }
            drawText(
                textMeasurer = textMeasurer,
                text = present.max().let { String.format(Locale.CHINA, "%.0f", it) },
                topLeft = Offset((maxi * stepX).coerceIn(0f, size.width - 90f), 0f),
                style = TextStyle(fontSize = 11.sp, color = contentColor),
            )
        }
    }
}

/** 图例 */
@Composable
fun ChartLegend(items: List<Pair<String, Color>>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (label, c) ->
            Box(
                Modifier
                    .width(10.dp)
                    .height(10.dp)
                    .background(c),
            )
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(12.dp))
        }
    }
}
