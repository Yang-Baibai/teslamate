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
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import com.teslamate.app.fmt
import kotlin.math.roundToInt

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
        animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing),
        label = "chartGrow",
    )
    return progress
}

/**
 * 坐标轴公共绘制：Y 轴刻度 + 网格线 + X 轴标签
 *
 * @param ticks Y 轴刻度值（**由调用方用 [niceAxis] 算好**）
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAxes(
    yMax: Float,
    yUnit: String,
    xLabels: List<String>,
    yLabelWidth: Float,
    xLabelHeight: Float,
    gridColor: Color,
    labelColor: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    ticks: List<Float>,
    /** Y 轴下界（默认 0；细窄数据范围时会贴住数据，见 LineChart） */
    yBottom: Float = 0f,
) {
    val plotLeft = yLabelWidth
    val plotRight = size.width
    val plotTop = 0f
    val plotBottom = size.height - xLabelHeight
    val plotHeight = plotBottom - plotTop
    val ySpan = (yMax - yBottom).coerceAtLeast(0.0001f)

    // 水平网格线 + Y 轴刻度值
    ticks.forEach { t ->
        val y = plotBottom - ((t - yBottom) / ySpan) * plotHeight
        drawLine(
            color = gridColor,
            start = Offset(plotLeft, y),
            end = Offset(plotRight, y),
            strokeWidth = 1.dp.toPx(),
        )
        val txt = if (t >= 100) "${t.roundToInt()}" else "${trimNum(t)}"
        val layout = textMeasurer.measure(
            text = txt,
            style = TextStyle(fontSize = 9.sp, color = labelColor),
        )
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(
                x = (plotLeft - layout.size.width - 4.dp.toPx()).coerceAtLeast(0f),
                y = y - layout.size.height / 2f,
            ),
        )
    }
    // X 轴标签（首 / 中 / 末）
    if (xLabels.isNotEmpty()) {
        val step = if (xLabels.size > 1) (plotRight - plotLeft) / (xLabels.size - 1) else 0f
        xLabels.forEachIndexed { i, label ->
            val layout = textMeasurer.measure(
                text = label,
                style = TextStyle(fontSize = 9.sp, color = labelColor),
            )
            val rawX = plotLeft + step * i - layout.size.width / 2f
            val x = when (i) {
                0 -> plotLeft
                xLabels.lastIndex -> plotRight - layout.size.width
                else -> rawX
            }
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(x.coerceIn(0f, plotRight - layout.size.width), plotBottom + 4.dp.toPx()),
            )
        }
    }
}

/**
 * 取一个「好看」的刻度步长（1 / 2 / 5 × 10ⁿ）。
 *
 * 例：3.6 → 5；36 → 50；0.45 → 0.5；120 → 200。
 */
private fun niceStep(v: Float): Float {
    if (v <= 0f || !v.isFinite()) return 1f
    var base = 1f
    while (base * 10f <= v) base *= 10f
    val n = v / base
    return (when {
        n <= 1f -> 1f
        n <= 2f -> 2f
        n <= 5f -> 5f
        else -> 10f
    }) * base
}

/**
 * 算「好看的」Y 轴刻度（**只在绘图区内部**，不含上界）。
 *
 * ⚠️ v0.9.1：原来刻度是 `0 / max/2 / max`，直接取自数据最大值 ——
 * 于是「充入电量」图的刻度是 **23.7 / 11.9 / 0**。
 * 读者看到「23.7 度」根本读不出量级（是 20 还是 25？），中间那条 11.9 更糟。
 *
 * 现在刻度取 **1 / 2 / 5 × 10ⁿ** 的整数步长，且**只取严格小于上界**的那些：
 *   - 上界 23.7 → 刻度 0 / 10 / 20（峰值 23.7 由图下方的摘要行给出）
 *   - 上界 47.4 → 刻度 0 / 20 / 40
 *   - 上界 1002 → 刻度 0 / 500 / 1000
 *
 * ⚠️ **上界本身不动**（仍是真实数据最大值）——
 * v0.7.1 特意这么定过：把上界取整会让读者以为数据到过那个数
 * （1002 → 1500 就是凭空多出来 50%）。现在只让**刻度**变好看。
 */
private fun niceTicks(maxValue: Float, targetTicks: Int = 4): List<Float> {
    if (maxValue <= 0f || !maxValue.isFinite()) return listOf(0f)
    val rawStep = maxValue / targetTicks
    // base = 10^floor(log10(rawStep)) —— 用循环算，避免依赖 log10/pow 的重载解析。
    var base = 1f
    while (base * 10f <= rawStep) base *= 10f
    val n = rawStep / base
    val step = (when {
        n <= 1f -> 1f
        n <= 2f -> 2f
        n <= 5f -> 5f
        else -> 10f
    }) * base
    val out = ArrayList<Float>()
    var v = step
    while (v < maxValue - step * 0.001f) {
        out += v
        v += step
    }
    return listOf(0f) + out
}

private fun trimNum(v: Float): String {
    val s = String.format(Locale.CHINA, "%.1f", v)
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

/**
 * 柱状图：支持多系列堆叠（如 直流+交流）。
 *
 * ⚠️ v0.7.0 前的三个硬伤（车主B + IA 专家共同指出），本轮修掉：
 *   1. **没有坐标轴** —— 只能看"哪根最高"，看不出"周三开了 180 km"；
 *      单位全靠卡片标题，图本身不自解释。
 *   2. **Y 轴各自归一化** —— 每张图独立缩放到自己的峰值，跨图不可比，
 *      更糟的是同一条 Wh/km 曲线，2 月 150 和 8 月 190 在各自图里都"顶格"，**趋势被归一化吃掉了**。
 *      现在提供 `referenceValue`：画一条基准线并把 Y 轴上界锚定到「基准的 1.3 倍」，
 *      基准以下的变化就看得见了。
 *   3. **峰值标注压到相邻柱上** —— 现在画在绘图区内上方，按柱宽自适应避让。
 */
@Composable
fun StackedBarChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    peakFmt: (Float) -> String = { String.format(Locale.CHINA, "%.0f", it) },
    yUnit: String = "",
    xLabels: List<String> = emptyList(),
    referenceValue: Float? = null,
    referenceLabel: String = "",
    contentDescription: String = "",
) {
    val n = series.firstOrNull()?.values?.size ?: 0
    val textMeasurer = rememberTextMeasurer()
    val contentColor = LocalContentColor.current
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val hasData = series.any { s -> s.values.any { (it ?: 0f) > 0f } }
    val progress = chartProgress(hasData)
    val density = LocalDensity.current
    // ⚠️ v0.9.0：刻度文字随 fontScale 放大，预留带也必须跟着放大，
    // 否则大字号下 X 轴标签会压到曲线、Y 轴标签被裁掉。
    val fs = LocalDensity.current.fontScale.coerceAtMost(1.6f)
    val yLabelWidth = with(density) { (34 * fs).dp.toPx() }
    val xLabelHeight = with(density) { if (xLabels.isEmpty()) 0f else (16 * fs).dp.toPx() }

    Box(
        modifier = modifier.semantics {
            if (contentDescription.isNotBlank()) this.contentDescription = contentDescription
        }
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
        ) {
            if (n == 0) return@Canvas
            val totals = (0 until n).map { i -> series.sumOf { (it.values.getOrNull(i) ?: 0f).toDouble() }.toFloat() }
            val dataMax = (totals.maxOrNull() ?: 0f).coerceAtLeast(0.001f)
            // Y 轴上界：有基准线时锚定到基准的 1.3 倍，让基准附近的变化可见
            val yMax = referenceValue?.let { max(dataMax, it * 1.3f) } ?: dataMax
            val yTop = max(yMax, dataMax)

            val plotTop = 0f
            val plotBottom = size.height - xLabelHeight
            val plotHeight = plotBottom - plotTop
            val plotLeft = yLabelWidth
            val gap = 3.dp.toPx()
            val bw = (size.width - plotLeft - gap * (n - 1)) / n

            // ⚠️ v0.9.2（苹果视觉评审 P1-4）：**绘图区铺一层极淡的底**。
            //
            // 原来空槽是全高色块、槽间 3dp 缝隙直接露出**卡片面** ——
            // 浅色下露白、深色下露黑，于是**贯穿全高的竖条读起来像划痕**，
            // 不像刻度（实测 08 图浅色底 #F5F5F6 + 竖线 #FFFFFF）。
            // 现在：缝隙露的是这层「底」，与空槽只差一点点明度 → 竖条消失。
            drawRoundRect(
                color = trackColor.copy(alpha = 0.08f),
                topLeft = Offset(plotLeft, plotTop),
                size = Size(size.width - plotLeft, plotHeight),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )

            drawAxes(
                yMax = yMax,
                yUnit = yUnit,
                xLabels = xLabels,
                yLabelWidth = plotLeft,
                xLabelHeight = xLabelHeight,
                gridColor = gridColor,
                labelColor = labelColor,
                textMeasurer = textMeasurer,
                // ⚠️ v0.9.1：刻度取整数步长（0/10/20 而不是 0/11.9/23.7），
                // 但**上界不变** —— v0.7.1 特意让上界等于真实数据最大值，
                // 取整会让读者以为数据到过那个数。
                ticks = niceTicks(yMax),
            )

            // 基准线（虚线感：用短划线绘制）
            referenceValue?.let { ref ->
                if (ref > 0f && ref <= yMax) {
                    val y = plotBottom - (ref / yMax) * plotHeight
                    val dash = 6.dp.toPx()
                    var x = plotLeft
                    while (x < size.width) {
                        drawLine(
                            color = contentColor.copy(alpha = 0.45f),
                            start = Offset(x, y),
                            end = Offset(minOf(x + dash, size.width), y),
                            strokeWidth = 1.5.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                        x += dash * 2
                    }
                    if (referenceLabel.isNotBlank()) {
                        val layout = textMeasurer.measure(
                            text = referenceLabel,
                            style = TextStyle(fontSize = 9.sp, color = contentColor.copy(alpha = 0.6f)),
                        )
                        drawText(
                            textLayoutResult = layout,
                            topLeft = Offset(
                                x = (plotLeft + 4.dp.toPx()).coerceAtMost(size.width - layout.size.width),
                                y = (y - layout.size.height - 2.dp.toPx()).coerceAtLeast(0f),
                            ),
                        )
                    }
                }
            }

            totals.forEachIndexed { i, t ->
                val x = plotLeft + i * (bw + gap)
                // 空槽只是"这里有量程"的结构提示，不是数据。
                // ⚠️ v0.9.1 修正：原来空槽是**全高**色块、槽间 3dp 缝隙直接露出卡片面 ——
                // 浅色下露白、深色下露黑，**贯穿全高的竖条读起来像划痕/缝隙**，不像刻度。
                // 现在：绘图区先铺一层极淡的底，缝隙露的是底而不是卡片面，
                // 空槽本身也压到 0.22 —— 竖条消失，量程结构还在。
                drawRoundRect(
                    color = trackColor.copy(alpha = 0.22f),
                    topLeft = Offset(x, plotTop),
                    size = Size(bw, plotHeight),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
                var curY = plotBottom
                series.forEach { s ->
                    val v = s.values.getOrNull(i) ?: 0f
                    if (v > 0f) {
                        val h = v / yTop * plotHeight * progress
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

            // 峰值标注：**不在图上标**。
            // ⚠️ v0.7.1：原来画在图上，但 30 根柱子时每根柱宽只有约 30px，
            // "1002" 四个字比柱还宽 —— 文字必然溢出柱子，
            // 溢出部分是白底 + 白字（浅色模式）→ 看起来像"数字被切了一半"。
            // 而且图下方的摘要行已经写了"峰值 1002 km（10-06）"，图上再标一遍是重复。
            // 现在峰值只在摘要行出现，图保持干净。
        }
    }
}

/**
 * 折线图（单系列，值可含 null 断点）。
 *
 * ⚠️ v0.7.0 前的三个硬伤，本轮修掉：
 *   1. **X 轴是"等距索引"不是时间** —— 采样间隔不均匀，等距画 = 时间轴失真。
 *      现在接收 `xLabels`（真实时间标签）与 `xValues`（真实时间戳），
 *      位置按真实时间比例分配。
 *   2. **clipRect 把填充和描边一起裁** —— 视觉是"一块布被拉开"。
 *      现在用 `PathMeasure.getSegment` 做**真·笔画生长**，填充独立淡入。
 *   3. **Y 轴各自归一化** —— 66.0 → 65.4 kWh 的真实衰减画成一条平线。
 *      现在 `referenceValue` 锚定 Y 轴上界，基准附近的变化看得见。
 */
@Composable
fun LineChart(
    values: List<Float?>,
    color: Color,
    modifier: Modifier = Modifier,
    fill: Boolean = true,
    yUnit: String = "",
    xLabels: List<String> = emptyList(),
    xWeights: List<Float>? = null,   // 真实时间权重（归一化到 0..1），为空则等距
    referenceValue: Float? = null,
    referenceLabel: String = "",
    contentDescription: String = "",
) {
    val textMeasurer = rememberTextMeasurer()
    val contentColor = LocalContentColor.current
    val hasData = values.any { it != null && it > 0f }
    val progress = chartProgress(hasData)
    val density = LocalDensity.current
    // 颜色必须在 @Composable 作用域取出再传进 DrawScope ——
    // DrawScope 里调 MaterialTheme.colorScheme 编译不过（不是 @Composable 上下文）。
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    // ⚠️ v0.9.0：刻度文字随 fontScale 放大，预留带也必须跟着放大，
    // 否则大字号下 X 轴标签会压到曲线、Y 轴标签被裁掉。
    val fs = LocalDensity.current.fontScale.coerceAtMost(1.6f)
    val yLabelWidth = with(density) { (34 * fs).dp.toPx() }
    val xLabelHeight = with(density) { if (xLabels.isEmpty()) 0f else (16 * fs).dp.toPx() }

    Box(
        modifier = modifier.semantics {
            if (contentDescription.isNotBlank()) this.contentDescription = contentDescription
        }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
        ) {
            if (values.size < 2) return@Canvas
            val present = values.filterNotNull()
            if (present.isEmpty()) return@Canvas
            val dataMin = present.min()
            val dataMax = present.max().coerceAtLeast(0.001f)
            val rawTop = referenceValue?.let { max(dataMax, it * 1.3f) } ?: dataMax

            val plotTop = 0f
            val plotBottom = size.height - xLabelHeight
            val plotHeight = plotBottom - plotTop
            val plotLeft = yLabelWidth
            val plotWidth = size.width - plotLeft

            // ⚠️ v0.9.2（口径审计 S3-3）：**细窄的数据范围不能从 0 起**。
            //
            // 电池健康曲线是 65.2 → 66.7 度（变化 1.5 度，占 2%），
            // 从 0 起画出来是**一条几乎不可见的平线** ——
            // 而这张图要表达的就是「它在缓慢下降」。
            // （旧注释写「锚定到标称容量的 1.3 倍后，衰减趋势一眼可见」，
            //  实际效果与这句话**相反**。）
            //
            // 现在：范围窄于最大值的一半时，Y 轴**贴住数据**（放大到能看出趋势）；
            // 范围宽时仍然从 0 起（柱状图必须从 0 起，折线图也要保持量级诚实）。
            val zoomed = dataMin > 0f && (dataMax - dataMin) < dataMax * 0.5f && referenceValue == null
            val (yTop, yBottom, yTicks) = if (zoomed) {
                val step = niceStep((dataMax - dataMin) * 1.6f)
                val lo = kotlin.math.floor((dataMin - (dataMax - dataMin) * 0.3f) / step) * step
                val hi = kotlin.math.ceil((dataMax + (dataMax - dataMin) * 0.3f) / step) * step
                Triple(
                    hi,
                    lo,
                    generateSequence(lo) { it + step }
                        .takeWhile { it <= hi + step * 0.001f }
                        .toList(),
                )
            } else {
                Triple(rawTop, 0f, niceTicks(rawTop))
            }
            val yMax = yTop

            // X 位置：按真实时间权重（xWeights）分配，无权重则等距
            val weights: List<Float> = if (xWeights != null && xWeights.size == values.size) {
                val lo = xWeights.min()
                val hi = xWeights.max()
                if (hi - lo < 1e-6f) List(values.size) { it / (values.size - 1f) }
                else xWeights.map { (it - lo) / (hi - lo) }
            } else {
                List(values.size) { it / (values.size - 1f) }
            }
            fun xAt(i: Int) = plotLeft + weights[i] * plotWidth
            fun yAt(v: Float) = plotBottom - ((v - yBottom) / (yTop - yBottom).coerceAtLeast(0.0001f)) * plotHeight

            drawAxes(
                yMax = yMax,
                yBottom = yBottom,
                yUnit = yUnit,
                xLabels = xLabels,
                yLabelWidth = plotLeft,
                xLabelHeight = xLabelHeight,
                gridColor = gridColor,
                labelColor = labelColor,
                textMeasurer = textMeasurer,
                // ⚠️ v0.9.1：刻度取整数步长，上界不变（同 StackedBarChart）
                ticks = yTicks,
            )

            // 基准线
            referenceValue?.let { ref ->
                if (ref > 0f && ref <= yMax) {
                    val y = yAt(ref)
                    val dash = 6.dp.toPx()
                    var x = plotLeft
                    while (x < size.width) {
                        drawLine(
                            color = contentColor.copy(alpha = 0.45f),
                            start = Offset(x, y),
                            end = Offset(minOf(x + dash, size.width), y),
                            strokeWidth = 1.5.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                        x += dash * 2
                    }
                    if (referenceLabel.isNotBlank()) {
                        val layout = textMeasurer.measure(
                            text = referenceLabel,
                            style = TextStyle(fontSize = 9.sp, color = contentColor.copy(alpha = 0.6f)),
                        )
                        drawText(
                            textLayoutResult = layout,
                            topLeft = Offset(
                                x = (plotLeft + 4.dp.toPx()).coerceAtMost(size.width - layout.size.width),
                                y = (y - layout.size.height - 2.dp.toPx()).coerceAtLeast(0f),
                            ),
                        )
                    }
                }
            }

            // 路径：null 断点处断开
            val path = Path()
            var started = false
            values.forEachIndexed { i, v ->
                if (v == null) {
                    started = false
                    return@forEachIndexed
                }
                val p = Offset(xAt(i), yAt(v))
                if (!started) {
                    path.moveTo(p.x, p.y)
                    started = true
                } else {
                    path.lineTo(p.x, p.y)
                }
            }

            // 真·笔画生长：PathMeasure 取前 progress 段
            val pathMeasure = PathMeasure()
            pathMeasure.setPath(path, false)
            val drawn = Path()
            pathMeasure.getSegment(0f, pathMeasure.length * progress, drawn, true)

            if (fill) {
                val fillPath = Path().apply {
                    addPath(drawn)
                    lineTo(size.width, plotBottom)
                    lineTo(plotLeft, plotBottom)
                    close()
                }
                drawPath(
                    fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(color.copy(alpha = 0.32f), Color.Transparent),
                        startY = plotTop,
                        endY = plotBottom,
                    ),
                    alpha = progress.coerceIn(0f, 1f),
                )
            }
            drawPath(
                drawn,
                color = color,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )

            // 峰值标注：尾段淡入
            if (progress >= 0.85f) {
                val maxi = values.indices.filter { values[it] != null }.maxByOrNull { values[it]!! } ?: -1
                if (maxi >= 0) {
                    val layout = textMeasurer.measure(
                        text = String.format(Locale.CHINA, "%.0f", present.max()),
                        style = TextStyle(fontSize = 11.sp, color = contentColor, fontWeight = FontWeight.Medium),
                    )
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(
                            x = (xAt(maxi) - layout.size.width / 2f)
                                .coerceIn(0f, size.width - layout.size.width),
                            y = 0f,
                        ),
                    )
                }
            }
        }
    }
}

/** 电池健康曲线的无障碍描述 */
fun batteryChartDescription(b: com.teslamate.app.BatteryHealth): String {
    val valid = b.series.filter { it.capacity_kwh != null }
    if (valid.isEmpty()) return "电池容量曲线：暂无数据"
    val first = valid.first()
    val last = valid.last()
    val maxV = valid.maxOf { it.capacity_kwh!! }
    val nominal = b.nominal_capacity_kwh
    return "电池可用容量曲线：共 ${valid.size} 个满充记录，" +
        "从 ${first.capacity_kwh!!.fmt(1)} kWh 到 ${last.capacity_kwh!!.fmt(1)} kWh，" +
        "最高 ${maxV.fmt(1)} kWh" +
        (nominal?.let { "，新电池标称容量 ${it.fmt(1)} kWh，当前健康度 ${b.health_percent?.fmt(1) ?: "未知"}%" } ?: "") +
        "。"
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

// ── 坐标轴与无障碍辅助 ────────────────────────────────────────────

/**
 * X 轴刻度标签：首 / 中 / 末，最多 3 个。
 * 输入是 `YYYY-MM-DD`，输出 `MM-DD`。
 */
fun bucketAxisLabels(starts: List<String>): List<String> {
    if (starts.isEmpty()) return emptyList()
    fun short(s: String) = if (s.length >= 10) s.substring(5, 10) else s
    return when {
        starts.size == 1 -> listOf(short(starts[0]))
        starts.size == 2 -> listOf(short(starts[0]), short(starts[1]))
        else -> listOf(short(starts[0]), short(starts[starts.size / 2]), short(starts.last()))
    }
}

/**
 * 图表的无障碍描述。
 *
 * ⚠️ Canvas 绘制的内容**完全不进语义树**（WCAG 1.1.1），
 * 屏幕阅读器用户拿不到任何数值——而这个 App 的全部价值就是数据。
 * 所以每张图都要给一段可朗读的等价描述。
 */
fun statsChartDescription(title: String, pairs: List<Pair<String, Double>>): String {
    if (pairs.isEmpty()) return "$title：暂无数据"
    val valid = pairs.filter { it.second != 0.0 }
    if (valid.isEmpty()) return "$title：${pairs.size} 个时间段均无数据"
    val max = valid.maxBy { it.second }
    val min = valid.minBy { it.second }
    val avg = valid.sumOf { it.second } / valid.size
    val head = valid.take(10).joinToString("，") { (d, v) ->
        "${d.substring(5).replace('-', '月')}日 ${v.fmt(1)}"
    }
    val tail = if (valid.size > 10) "，等 ${valid.size} 个时间段" else ""
    return "$title：共 ${valid.size} 个有数据的时间段，峰值 ${max.second.fmt(1)}" +
        "（${max.first.substring(5)}），最低 ${min.second.fmt(1)}，平均 ${avg.fmt(1)}。" +
        "逐段：$head$tail"
}

// ⚠️ v0.14.0（口径审计 P0-1）：两张共享里程横轴的图（用电曲线 / 电量条）
// 的**统一绘图区几何**。
//
// 原来两张图各算各的：
//   · 功率图 plotLeft = 34dp·fs（左边距让给 Y 轴刻度），plotRight = 画布右缘
//   · 电量条 plotLeft = 0，                 plotRight = 画布右缘 − 30dp·fs
//
// 于是「同一公里在两条线上是同一列」这个承诺**根本没成立**：
// 1080px 屏上 x(0) 在功率图是 102px、在电量条是 0px；
// 差值沿轴从 102px 变到 90px —— 不是常量平移，纵对齐语义彻底失效。
// 连带后果：「约第 N 公里降到 20%」标在电量条的 x 上，
// 而功率图里同公里的 x 在别处 —— 两个图给出的公里数无法互相校验。
//
// 现在两图共用同一份几何，公里 ↔ 像素的映射**逐像素一致**。
internal const val PROFILE_Y_LABEL_DP = 34f
internal const val PROFILE_RIGHT_LABEL_DP = 30f

internal class ProfileAxis(
    val plotLeft: Float,
    val plotRight: Float,
    val xStartKm: Double,
    val xEndKm: Double,
) {
    val plotWidth: Float get() = plotRight - plotLeft

    /** 里程 → X 像素。两张图必须用同一个函数，否则又会错开。 */
    fun xAt(km: Double): Float {
        val span = (xEndKm - xStartKm).coerceAtLeast(0.001)
        val ratio = ((km - xStartKm) / span).coerceIn(0.0, 1.0)
        return plotLeft + ratio.toFloat() * plotWidth
    }
}

/**
 * 功率剖面图：Y 轴 = 电池净功率（kW），**零线居中**，双向填充。
 *
 * 正值（耗电）用能量蓝，负值（能量回收）用回收绿 —— 这是全 App 唯一需要
 * 「一正一负」双语义同图的地方，所以零线必须居中，不能像普通折线那样从 0 起。
 *
 * X 轴是**累计里程**而不是时间：长途决策变量是「还剩多少公里到服务区」，
 * 而且时间轴在堵车时会骗人（原地烧电被画成"省电了"）。
 */
@Composable
fun PowerProfileChart(
    samples: List<PowerSample>,
    modifier: Modifier = Modifier,
    /**
     * 里程轴的真实起点与终点（km）。
     *
     * ⚠️ v0.9.2（口径审计 S2-2）：不传就用分箱**中心**的首尾 ——
     * 于是 4.51 km 的行程横轴显示成「1 / 2 / 5」，而「里程」栏写 4.51 km，
     * 三个数对不上（起点被写成 1 km、终点被写成 5 km）。
     * 传进来就能显示真实的「0 / 25 / 50」。
     */
    xStartKm: Double? = null,
    xEndKm: Double? = null,
    contentDescription: String = "",
) {
    val textMeasurer = rememberTextMeasurer()
    val accents = LocalAccentColors.current
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val contentColor = LocalContentColor.current
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    // ⚠️ v0.9.0：刻度文字随 fontScale 放大，**预留带也必须跟着放大**，
    // 否则大字号下刻度会压到图里（X 轴标签压住曲线、Y 轴标签被裁掉）。
    // 1.6 是上限 —— 再大刻度就该换成「自适应 Y 轴范围」而不是继续挤。
    val fs = LocalDensity.current.fontScale.coerceAtMost(1.6f)
    val yLabelWidth = with(density) { (PROFILE_Y_LABEL_DP * fs).dp.toPx() }
    // ⚠️ v0.14.0：右边也留出与电量条**同样宽**的刻度带，两图才能逐像素对齐。
    val rightLabelWidth = with(density) { (PROFILE_RIGHT_LABEL_DP * fs).dp.toPx() }
    val xLabelHeight = with(density) { (16 * fs).dp.toPx() }

    Box(
        modifier = modifier.semantics {
            if (contentDescription.isNotBlank()) this.contentDescription = contentDescription
        }
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(170.dp)
        ) {
            if (samples.size < 2) return@Canvas
            // ⚠️ **v0.9.0 修第二个真 bug（运算符优先级）**。
            //
            //   原式：`-samples.minOf { it.powerKw }.coerceAtLeast(0.0)`
            //   Kotlin 里前缀负号作用于**整个调用链**，所以它实际是
            //     `-(samples.minOf { ... }.coerceAtLeast(0.0))`
            //   —— 先把「最小功率」钳到 ≥ 0，**再取负**。
            //   于是 maxNeg 永远是 0 或负数（min=-6 → 0 → -0.0），
            //   两个后果：
            //     1. `maxNeg > 0.001` 永远为假 → 回收那半边从来没被正确画过；
            //     2. posRatio = maxPos/(maxNeg+maxPos) 可能 **> 1**
            //        → 零线掉到画布**下面** → 填充区溢出画布、
            //          盖住 X 轴标签与下方的说明文字、整张图错位。
            //   （实测：一条带回收的 4.5 km 行程，零线跑到画布外 190 px。）
            //
            //   现在：先取负再钳位，maxNeg 才是真正的「最大回收功率」。
            val maxPos = samples.maxOf { it.powerKw }.coerceAtLeast(0.0)
            val maxNeg = (0.0 - samples.minOf { it.powerKw }).coerceAtLeast(0.0)
            val plotTop = 0f
            val plotBottom = size.height - xLabelHeight
            val plotHeight = plotBottom - plotTop
            // ⚠️ v0.14.0（口径审计 P0-1）：与下方电量条**共用同一份几何**。
            val plotLeft = yLabelWidth
            val plotRight = size.width - rightLabelWidth
            val plotWidth = plotRight - plotLeft
            // ⚠️ v0.14.0（口径审计 P0-2）：量程分母必须是**真实里程**，不是分箱中心。
            //
            // 原来用 samples.last().km —— 那是最后一个 1 km 分箱的**中心**
            // （2.28 km 的行程 → 2.5），而 X 轴刻度标签用的是真实里程（0 / 2.28）。
            // 于是「刻度说的公里数」和「它所在位置的公里数」不是一回事：
            // 标签「1」标称 1.14 km 却画在 45.6% 处，1.5 km 的数据点却在 60% 处。
            // （分箱中心现已按真实行程长度重标定，见 toPowerProfile 的 realLengthKm。）
            val xFrom = xStartKm ?: samples.first().km
            val xTo = xEndKm ?: samples.last().km
            val axis = ProfileAxis(plotLeft, plotRight, xFrom, xTo)
            val totalKm = (xTo - xFrom).coerceAtLeast(0.001)

            // 零线位置**按实际正负范围自适应**，不是固定居中。
            //
            // ⚠️ **v0.9.0 修一个真 bug**（这行从 v0.8.1 起就一直是错的）：
            //
            //   原式：negRatio = maxNeg / (maxNeg + maxPos)
            //         zeroY   = plotTop + plotHeight * negRatio
            //         upH     = plotBottom - zeroY      // 零线**上方**的空间
            //         downH   = zeroY - plotTop         // 零线**下方**的空间
            //
            //   问题：`zeroY = plotTop + downH` 意味着「零线上方的空间」= downH，
            //   而 downH 又 ∝ maxNeg —— **上下两块的比例是反的**。
            //   后果：当回收比耗电小（最常见的情况，实测 maxPos=90 / maxNeg=61），
            //   零线只落在图高的 40% 处，而「上方空间」被算成了 60%，
            //   于是 yAt(maxPos) = zeroY − upH = **画布上方之外** ——
            //   **整条耗电曲线画到了画布外，图看起来几乎是空的。**
            //   （实测：drive 498 的 maxPos=90 kW 那一段完全不可见。）
            //
            //   现在：零线上方的空间 ∝ maxPos，下方 ∝ maxNeg —— 与几何一致。
            val posRatio = if (maxPos <= 0.001) {
                0f
            } else {
                // ⚠️ 再加一道钳位：即使 maxPos/maxNeg 的比例算错，
                // 零线也不会掉出画布（掉出去 = 整张图错位 + 填充区溢出压住下方文字）。
                (maxPos / (maxNeg + maxPos)).toFloat().coerceIn(0f, 1f)
            }
            val zeroY = plotTop + plotHeight * posRatio
            val upH = zeroY - plotTop
            val downH = plotBottom - zeroY

            // ⚠️ v0.14.0：改用与电量条共用的 axis，公里 ↔ 像素逐像素一致。
            fun xAt(km: Double) = axis.xAt(km)
            // ⚠️ 上下**各自**用自己的极值做标定（原来两边都用 maxAbs）：
            //   上面按 maxPos、下面按 maxNeg，曲线才能分别顶到轴的两端。
            fun yAt(p: Double) = if (p >= 0) {
                zeroY - (p / maxPos.coerceAtLeast(0.001)).toFloat() * upH
            } else {
                zeroY + (-p / maxNeg.coerceAtLeast(0.001)).toFloat() * downH
            }

            // 网格线：+max / 0 / -max（只有存在负值时才画下半区）
            val ticks = buildList {
                if (maxPos > 0.001) add(maxPos)
                add(0.0)
                if (maxNeg > 0.001) add(-maxNeg)
            }
            ticks.forEach { v ->
                val y = yAt(v)
                drawLine(
                    color = if (v == 0.0) contentColor.copy(alpha = 0.35f) else gridColor,
                    start = Offset(plotLeft, y),
                    end = Offset(plotRight, y),
                    strokeWidth = 1.dp.toPx(),
                )
                val txt = if (v == 0.0) "0" else "${v.roundToInt()}"
                // ⚠️ v0.9.2：原来用 abs()，于是回收区的刻度显示成「9」而不是「-9」——
                // 读者根本看不出那是**零线以下**（而这个图的核心就是正负两段）。
                val layout = textMeasurer.measure(
                    text = txt,
                    style = TextStyle(fontSize = 9.sp, color = labelColor),
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        x = (plotLeft - layout.size.width - 4.dp.toPx()).coerceAtLeast(0f),
                        y = y - layout.size.height / 2f,
                    ),
                )
            }

            // 折线
            val path = Path()
            samples.forEachIndexed { i, s ->
                val p = Offset(xAt(s.km), yAt(s.powerKw))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            val firstX = xAt(samples.first().km)
            val lastX = xAt(samples.last().km)

            // 上半区 = 耗电（能量蓝）
            clipRect(top = plotTop, bottom = zeroY) {
                val fill = Path().apply {
                    addPath(path)
                    lineTo(lastX, zeroY)
                    lineTo(firstX, zeroY)
                    close()
                }
                drawPath(fill, color = accents.energyBlue.copy(alpha = 0.20f))
                drawPath(
                    path,
                    color = accents.energyBlue,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            // 下半区 = 回收（回收绿）
            clipRect(top = zeroY, bottom = plotBottom) {
                val fill = Path().apply {
                    addPath(path)
                    lineTo(lastX, zeroY)
                    lineTo(firstX, zeroY)
                    close()
                }
                drawPath(fill, color = accents.chargeGreen.copy(alpha = 0.20f))
                drawPath(
                    path,
                    color = accents.chargeGreen,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            // X 轴：起点 / 中点 / 终点（里程）
            // ⚠️ v0.9.2：首尾用**真实**里程（调用方传入），不用分箱中心。
            // ⚠️ v0.14.0：xFrom / xTo 已在上方与 axis 一起算好，这里不再重复定义。
            listOf(
                xFrom to 0,
                (xFrom + xTo) / 2 to 1,
                xTo to 2,
            ).forEach { (km, align) ->
                val txt = "${km.roundToInt()}"
                val layout = textMeasurer.measure(
                    text = txt,
                    style = TextStyle(fontSize = 9.sp, color = labelColor),
                )
                val rawX = xAt(km) - layout.size.width / 2f
                val x = when (align) {
                    0 -> plotLeft
                    // ⚠️ v0.14.0：右端标签对齐到 plotRight（不再是画布右缘），
                    // 与电量条的绘图区右边界一致。
                    2 -> plotRight - layout.size.width
                    else -> rawX
                }
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(x.coerceIn(0f, size.width - layout.size.width), plotBottom + 4.dp.toPx()),
                )
            }
        }
    }
}

/**
 * **电量下降条**（SOC strip）—— 功率剖面正下方的第二条曲线。
 *
 * ## 为什么是「下方一条」而不是「右侧第二根 Y 轴」
 *
 * ⚠️ v0.9.0（长途自驾党复核）：
 * 「功率剖面只有功率没有电量，我看不到还能撑到哪个服务区。
 * 建议加一条电量曲线，或者在图上标出'到达 20% 时走了多少公里'。」
 *
 * 两根 Y 轴叠在一张图上是仪表盘的经典做法，但**极易误读**：
 * 功率的零线是自适应居中的（下面 PowerProfileChart 有说明），
 * 电量的 0% 在底部 —— 两个尺度共用一根竖轴，读者会把功率的高度
 * 当成电量的高度。这里改成**上下两块、共享同一条里程横轴**：
 *
 * ```
 * ┌──────────────────────────────┐  功率 (kW)
 * │  ╱╲    ╱╲___                 │
 * ├──────────────────────────────┤  ← 同一条里程轴
 * │ 100%╲                        │
 * │      ╲___ 20% ┄┄┄┄ 10% ┄┄┄   │  电量 (%)
 * └──────────────────────────────┘
 * ```
 *
 * 好处：横轴完全对齐（同一公里在两条线上是同一列），
 * 而且「到达 20% @ N 公里」可以**直接标在曲线上**。
 *
 * ⚠️ v0.14.0（口径审计 + 长途自驾党）：电量轴**不再是固定 0~100%**。
 *
 * 原来固定全量程的理由是「20% / 10% 的位置固定，跨行程可比」——
 * 但实测一条 82% → 70% 的 50 公里行程，曲线只占画布高度 12%，
 * 20% / 10% 两条参考线孤零零落在下方八成空白里，整张图**读不出任何东西**。
 * （同文件的 `LineChart` 本来就有自适应缩放，两套轴策略不一致。）
 *
 * 现在改成：**落差大 → 全量程（0~100%，保留参考线）；落差小 → 自适应量程**，
 * 并且右侧刻度**照实标出当前量程**（例如 85% / 65%，而不是 100% / 0%）——
 * 跨行程可比性靠**读刻度数字**保住，而不是靠轴上的位置。
 */
@Composable
fun SocStrip(
    samples: List<PowerSample>,
    modifier: Modifier = Modifier,
    milestones: SocMilestones = samples.socMilestones(),
    /**
     * 里程轴的真实起点与终点（km）。
     *
     * ⚠️ v0.14.0：必须与上方 [PowerProfileChart] 传**同一组值**，
     * 否则两图的「同一公里 = 同一列」对不上（见 [ProfileAxis]）。
     */
    xStartKm: Double = 0.0,
    xEndKm: Double? = null,
    contentDescription: String = "",
) {
    val textMeasurer = rememberTextMeasurer()
    val accents = LocalAccentColors.current
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    // ⚠️ v0.9.0：刻度文字随 fontScale 放大，预留带也必须跟着放大（见 PowerProfileChart 的说明）
    val fs = LocalDensity.current.fontScale.coerceAtMost(1.6f)
    // ⚠️ v0.14.0：左右预留带与功率图**完全一致**（否则两图横向错开）。
    val yLabelWidth = with(density) { (PROFILE_Y_LABEL_DP * fs).dp.toPx() }
    val rightLabelWidth = with(density) { (PROFILE_RIGHT_LABEL_DP * fs).dp.toPx() }
    val bottomLabelHeight = with(density) { (13 * fs).dp.toPx() }

    Box(
        modifier = modifier.semantics {
            if (contentDescription.isNotBlank()) this.contentDescription = contentDescription
        }
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
        ) {
            val withSoc = samples.filter { it.soc != null }
            if (withSoc.size < 2) return@Canvas

            val plotTop = 0f
            val plotBottom = size.height - bottomLabelHeight
            val plotHeight = plotBottom - plotTop
            // ⚠️ v0.14.0（口径审计 P0-1）：与上方功率图**共用同一份几何** ——
            // 「同一公里 = 同一列」这句话现在才真正成立。
            val plotLeft = yLabelWidth
            val plotRight = size.width - rightLabelWidth
            val xFrom = xStartKm
            val xTo = xEndKm ?: samples.last().km
            val axis = ProfileAxis(plotLeft, plotRight, xFrom, xTo)

            fun xAt(km: Double) = axis.xAt(km)

            // ⚠️ v0.14.0：电量轴**自适应量程** —— 但判据不是「落差大小」，
            // 而是**会不会跌破 20% 保底线**（口径审计 P1-1）。
            //
            //   · 最低点 ≤ 20% → 保持 0~100% 全量程，20%/10% 参考线照画
            //     （这才是长途规划要读的那条线，位置固定才有意义）
            //   · 最低点 > 20% → 这趟根本到不了保底线，20%/10% 是**够不到的线**，
            //     画出来只是两条悬在底部的孤线。此时改成自适应量程，
            //     并把真实上下界标在右侧刻度上。
            //
            // 原来固定 0~100% 的实测后果：一条 82% → 70% 的 50 公里行程，
            // 曲线只占画布高度 12%，整张图读不出任何东西。
            val socLevels = withSoc.mapNotNull { it.soc }
            val socMin = socLevels.minOrNull() ?: 0
            val socMax = socLevels.maxOrNull() ?: 100
            val useFullScale = socMin <= 20
            val axisLow: Float
            val axisHigh: Float
            if (useFullScale) {
                axisLow = 0f
                axisHigh = 100f
            } else {
                var lo = (kotlin.math.floor((socMin - 2) / 5.0) * 5.0).toFloat()
                var hi = (kotlin.math.ceil((socMax + 2) / 5.0) * 5.0).toFloat()
                if (hi - lo < 10f) hi = lo + 10f
                lo = lo.coerceAtLeast(0f)
                hi = hi.coerceAtMost(100f)
                if (hi - lo < 10f) lo = (hi - 10f).coerceAtLeast(0f)
                axisLow = lo
                axisHigh = hi
            }

            fun yAtSoc(soc: Int): Float {
                val v = soc.coerceIn(0, 100).toFloat()
                val span = (axisHigh - axisLow).coerceAtLeast(0.001f)
                return plotBottom - ((v - axisLow) / span) * plotHeight
            }

            // 20% / 10% 参考线（虚线）—— 只在**落进当前量程**时画。
            // 量程没缩放时它们是「保底线」；缩放后（全程到不了 20%）
            // 它们够不到，画出来只会让人误以为量程没缩。
            listOf(
                20 to accents.warnAmber,
                10 to accents.danger,
            ).forEach { (lvl, color) ->
                if (!useFullScale) return@forEach
                val y = yAtSoc(lvl)
                val dash = 5.dp.toPx()
                // ⚠️ v0.14.0：虚线从 plotLeft 起（与功率图绘图区左边界一致）
                var x = plotLeft
                while (x < plotRight) {
                    drawLine(
                        color = color.copy(alpha = 0.55f),
                        start = Offset(x, y),
                        end = Offset(minOf(x + dash, plotRight), y),
                        strokeWidth = 1.dp.toPx(),
                    )
                    x += dash * 2
                }
                val layout = textMeasurer.measure(
                    text = "$lvl%",
                    style = TextStyle(fontSize = 9.sp, color = color),
                )
                drawText(
                    textLayoutResult = layout,
                    // ⚠️ v0.14.0：刻度文字放进左侧留白带（与功率图的 Y 轴刻度同位），
                    // 不再压在绘图区上。
                    topLeft = Offset(
                        x = (plotLeft - layout.size.width - 4.dp.toPx()).coerceAtLeast(0f),
                        y = y - layout.size.height / 2f,
                    ),
                )
            }

            // 绘图区底边（0% / 100% 边界）
            drawLine(
                color = gridColor,
                start = Offset(plotLeft, plotBottom),
                end = Offset(plotRight, plotBottom),
                strokeWidth = 1.dp.toPx(),
            )

            // 电量曲线
            val path = Path()
            withSoc.forEachIndexed { i, s ->
                val p = Offset(xAt(s.km), yAtSoc(s.soc!!))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(
                path,
                color = accents.energyBlue,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )

            // 「到达 20% @ N 公里」标注
            //
            // ⚠️ v0.14.0：原来这里把标记点**写死画在 20% 线上**（yAtSoc(20)），
            // 于是 10% 的那个点也画在 20% 的高度上 —— 两条线共用一个 y 值，
            // 标注的点和它声称的百分比对不上。现在按各自的 level 画。
            fun mark(km: Double?, level: Int, text: String, color: Color) {
                if (km == null) return
                // 参考线不在当前量程内时不标（否则点会落在图外）
                if (level.toFloat() < axisLow || level.toFloat() > axisHigh) return
                val x = xAt(km)
                val dot = 3.5.dp.toPx()
                val y = yAtSoc(level)
                drawCircle(color = color, radius = dot, center = Offset(x, y))
                val layout = textMeasurer.measure(
                    text = text,
                    style = TextStyle(fontSize = 10.sp, color = color, fontWeight = FontWeight.SemiBold),
                )
                // 标签放在点的**上方**（电量在降，上方是「还有电」的方向）；
                // 上方放不下就改放下方，避免出界。
                val above = y - layout.size.height - 5.dp.toPx()
                val ty = if (above >= 0f) above else y + 5.dp.toPx()
                val tx = (x - layout.size.width / 2f)
                    .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f))
                drawText(textLayoutResult = layout, topLeft = Offset(tx, ty))
            }
            // ⚠️ v0.9.2（口径审计 S2-2）：措辞用「约」。
            // SOC 是整数百分比（1 个百分点 ≈ 3.65 km），加上 1 km 分箱的箱中心量化，
            // 真实不确定度约 ±2 km —— 写成精确的「第 243 公里」是把误差当成了精度。
            mark(milestones.kmAt20, 20, "约第 ${milestones.kmAt20?.roundToInt()} 公里降到 20%", accents.warnAmber)
            mark(milestones.kmAt10, 10, "约第 ${milestones.kmAt10?.roundToInt()} 公里降到 10%", accents.danger)

            // 右侧电量刻度（第二 Y 轴）—— 标**当前量程的真实上下界**。
            // 全量程时是 100% / 0%；缩放后是例如 85% / 65%。
            listOf(
                axisHigh.toInt() to "${axisHigh.toInt()}%",
                axisLow.toInt() to "${axisLow.toInt()}%",
            ).forEach { (lvl, txt) ->
                val layout = textMeasurer.measure(
                    text = txt,
                    style = TextStyle(fontSize = 9.sp, color = labelColor),
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        x = plotRight + 3.dp.toPx(),
                        y = yAtSoc(lvl) - layout.size.height / 2f,
                    ),
                )
            }

            // 底部：起始 / 结束电量
            val firstSoc = withSoc.first().soc
            val lastSoc = withSoc.last().soc
            if (firstSoc != null && lastSoc != null) {
                val layout = textMeasurer.measure(
                    text = "${firstSoc}% → ${lastSoc}%",
                    style = TextStyle(fontSize = 9.sp, color = labelColor),
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        // ⚠️ v0.14.0：与绘图区左边界对齐
                        x = plotLeft,
                        y = plotBottom + 2.dp.toPx(),
                    ),
                )
            }
        }
    }
}