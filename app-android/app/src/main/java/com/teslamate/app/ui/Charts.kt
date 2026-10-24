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

/** 坐标轴公共绘制：Y 轴刻度 + 网格线 + X 轴标签 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAxes(
    yMax: Float,
    yUnit: String,
    xLabels: List<String>,
    yLabelWidth: Float,
    xLabelHeight: Float,
    gridColor: Color,
    labelColor: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    /**
     * Y 轴刻度的最大值。
     *
     * ⚠️ v0.7.1：它和「绘图区上界」**故意不是同一个值**。
     * 绘图区上界留 8% 顶部空白给峰值标注，否则顶格柱的标注只能压在柱身上；
     * 但刻度标签要显示**真实数据最大值**（1002），不能因为留白就显示 1082 ——
     * 那会让读者以为数据到过 1082。
     */
    tickMax: Float = yMax,
) {
    val plotLeft = yLabelWidth
    val plotRight = size.width
    val plotTop = 0f
    val plotBottom = size.height - xLabelHeight
    val plotHeight = plotBottom - plotTop

    // 水平网格线 + Y 轴刻度值（0 / 中 / 最大）
    val ticks = listOf(0f, tickMax / 2f, tickMax)
    ticks.forEach { t ->
        val y = plotBottom - (if (yMax > 0f) t / yMax else 0f) * plotHeight
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
    val yLabelWidth = with(density) { 34.dp.toPx() }
    val xLabelHeight = with(density) { if (xLabels.isEmpty()) 0f else 16.dp.toPx() }

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

            drawAxes(
                yMax = yMax,
                yUnit = yUnit,
                xLabels = xLabels,
                yLabelWidth = plotLeft,
                xLabelHeight = xLabelHeight,
                gridColor = gridColor,
                labelColor = labelColor,
                textMeasurer = textMeasurer,
                // 有基准线时用 yMax 作刻度（基准锚定场景本来就该按基准读数）；
                // 无基准线时用真实数据最大值，别让顶部留白变成"数据到过 1082"的错觉。
                tickMax = if (referenceValue != null) yMax else dataMax,
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
                // ⚠️ v0.7.1：原来用实色 surfaceContainerHighest —— 30 根实心灰条铺满绘图区，
                // 视觉上变成"条纹背景"，把真正的数据柱淹没。改成很淡的一层。
                drawRoundRect(
                    color = trackColor.copy(alpha = 0.45f),
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
    val yLabelWidth = with(density) { 34.dp.toPx() }
    val xLabelHeight = with(density) { if (xLabels.isEmpty()) 0f else 16.dp.toPx() }

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
            val dataMax = present.max().coerceAtLeast(0.001f)
            val yMax = referenceValue?.let { max(dataMax, it * 1.3f) } ?: dataMax
            val yTop = max(yMax, dataMax)

            val plotTop = 0f
            val plotBottom = size.height - xLabelHeight
            val plotHeight = plotBottom - plotTop
            val plotLeft = yLabelWidth
            val plotWidth = size.width - plotLeft

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
            fun yAt(v: Float) = plotBottom - (v / yTop) * plotHeight

            drawAxes(
                yMax = yMax,
                yUnit = yUnit,
                xLabels = xLabels,
                yLabelWidth = plotLeft,
                xLabelHeight = xLabelHeight,
                gridColor = gridColor,
                labelColor = labelColor,
                textMeasurer = textMeasurer,
                tickMax = if (referenceValue != null) yMax else dataMax,
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
        (nominal?.let { "，铭牌容量 ${it.fmt(1)} kWh，当前健康度 ${b.health_percent?.fmt(1) ?: "未知"}%" } ?: "") +
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
    contentDescription: String = "",
) {
    val textMeasurer = rememberTextMeasurer()
    val accents = LocalAccentColors.current
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val contentColor = LocalContentColor.current
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val yLabelWidth = with(density) { 34.dp.toPx() }
    val xLabelHeight = with(density) { 16.dp.toPx() }

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
            val maxPos = samples.maxOf { it.powerKw }.coerceAtLeast(0.0)
            val maxNeg = -samples.minOf { it.powerKw }.coerceAtLeast(0.0)
            val maxAbs = maxOf(maxPos, maxNeg).coerceAtLeast(1.0)
            val plotTop = 0f
            val plotBottom = size.height - xLabelHeight
            val plotHeight = plotBottom - plotTop
            val plotLeft = yLabelWidth
            val plotWidth = size.width - plotLeft
            val totalKm = samples.last().km.coerceAtLeast(0.001)

            // 零线位置**按实际正负范围自适应**，不是固定居中。
            // ⚠️ v0.8.1：固定居中时，纯耗电行程（无回收）会浪费下半张图，
            // 而且下方的 -maxAbs 刻度显示的是一个数据里根本不存在的值。
            // 现在：没有回收时零线贴底（图形占满可用高度），没有耗电时贴顶。
            val negRatio = if (maxNeg <= 0.001) 0f else (maxNeg / (maxNeg + maxPos)).toFloat()
            val zeroY = plotTop + plotHeight * negRatio
            val upH = plotBottom - zeroY
            val downH = zeroY - plotTop

            fun xAt(km: Double) = plotLeft + (km / totalKm).toFloat() * plotWidth
            fun yAt(p: Double) = if (p >= 0) {
                zeroY - (p / maxAbs).toFloat() * upH
            } else {
                zeroY + (-p / maxAbs).toFloat() * downH
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
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                )
                val txt = if (v == 0.0) "0" else "${kotlin.math.abs(v).roundToInt()}"
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
            listOf(
                samples.first().km to 0,
                totalKm / 2 to 1,
                totalKm to 2,
            ).forEach { (km, align) ->
                val txt = "${km.roundToInt()}"
                val layout = textMeasurer.measure(
                    text = txt,
                    style = TextStyle(fontSize = 9.sp, color = labelColor),
                )
                val rawX = xAt(km) - layout.size.width / 2f
                val x = when (align) {
                    0 -> plotLeft
                    2 -> size.width - layout.size.width
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