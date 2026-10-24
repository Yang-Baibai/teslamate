@file:OptIn(ExperimentalMaterial3Api::class)

package com.teslamate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teslamate.app.AppSettings
import com.teslamate.app.BatteryHealth
import com.teslamate.app.Stats
import com.teslamate.app.fmt
import com.teslamate.app.fmtDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 统计：周/月/年 里程、能耗、充电、电池健康 */
@Composable
fun StatsScreen(settings: AppSettings, modifier: Modifier) {
    val api = remember(settings) { settings.api() }
    val periods = remember {
        listOf("week" to "周", "month" to "月", "year" to "年")
    }
    var period by remember { mutableStateOf("month") }
    var stats by remember { mutableStateOf<Stats?>(null) }
    var battery by remember { mutableStateOf<BatteryHealth?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settings, period) {
        if (api == null) {
            error = if (settings.configured) "服务器地址或令牌无效" else "尚未配置服务器，请到「设置」页"
            return@LaunchedEffect
        }
        runCatching {
            withContext(Dispatchers.IO) {
                Pair(api.stats(settings.carId, period), api.battery(settings.carId))
            }
        }.onSuccess {
            stats = it.first
            battery = it.second
            error = null
        }.onFailure { error = it.message }
    }


    Column(modifier) {
        TopAppBar(
            title = { ScreenTitle("统计") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            // ⚠️ v0.7.1：原来只有 bottom padding，SectionCard 又是 fillMaxWidth
            // → 卡片左右**贴边 0dp**，而同页分段按钮留了 16dp，加载完还会左右跳一下。
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PeriodSegmented(
                    periods = periods,
                    current = period,
                    onSelect = { period = it },
                )
            }

            if (error != null) {
                item { ErrorBanner(error!!) }
                return@LazyColumn
            }

            val s = stats
            if (s == null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SkeletonBox(Modifier.fillMaxWidth().height(140.dp))
                        SkeletonBox(Modifier.fillMaxWidth().height(200.dp))
                        SkeletonBox(Modifier.fillMaxWidth().height(200.dp))
                    }
                }
                return@LazyColumn
            }

            val sum = s.summary
            val periodLabel = periods.first { it.first == period }.second
            item {
                SectionCard("$periodLabel 汇总", Modifier.animateItem()) {
                    if (sum != null) {
                        KV("总里程", "${sum.km.fmt(1)} km")
                        KV("行车用电", "${sum.kwh_used.fmt(1)} kWh")
                        KV("充入电量", "${sum.charge_kwh.fmt(1)} kWh（快充 ${sum.dc_kwh.fmt(1)} / 慢充 ${sum.ac_kwh.fmt(1)}）")
                        KV("充电费用", if (sum.cost > 0) "${sum.cost.fmt(2)} 元" else "未记录")
                        sum.wh_per_km?.let { KV("平均能耗", "${it.fmt(0)} Wh/km") }
                        KV("行程 / 充电次数", "${sum.drive_count} 次 / ${sum.charge_count} 次")
                    }
                }
            }

            item {
                SectionCard("行驶里程 (km)", Modifier.animateItem()) {
                    StackedBarChart(
                        series = listOf(
                            ChartSeries(
                                values = s.buckets.map { it.km.toFloat() },
                                // ⚠️ v0.7.1：原来用 primary（特斯拉红）—— 「里程」没有红色语义，
                                // 红色在本 App 里只表示告警/品牌点缀。改用能量蓝。
                                color = LocalAccentColors.current.energyBlue,
                                label = "里程",
                            ),
                        ),
                        yUnit = "km",
                        xLabels = bucketAxisLabels(s.buckets.map { it.start }),
                        contentDescription = statsChartDescription("行驶里程", s.buckets.map { it.start to it.km }),
                    )
                    RangeHint(s)
                }
            }

            item {
                SectionCard("充入电量 (kWh)", Modifier.animateItem()) {
                    ChartLegend(
                        listOf(
                            "直流快充" to MaterialTheme.colorScheme.primary,
                            "交流慢充" to MaterialTheme.colorScheme.secondary,
                        ),
                    )
                    StackedBarChart(
                        series = listOf(
                            ChartSeries(
                                values = s.buckets.map { it.dc_kwh.toFloat() },
                                color = MaterialTheme.colorScheme.primary,
                                label = "直流",
                            ),
                            ChartSeries(
                                values = s.buckets.map { it.ac_kwh.toFloat() },
                                color = MaterialTheme.colorScheme.secondary,
                                label = "交流",
                            ),
                        ),
                        yUnit = "kWh",
                        xLabels = bucketAxisLabels(s.buckets.map { it.start }),
                        contentDescription = statsChartDescription("充入电量", s.buckets.map { it.start to (it.dc_kwh + it.ac_kwh) }),
                    )
                    RangeHint(s)
                }
            }

            item {
                SectionCard("行驶耗电 (kWh)", Modifier.animateItem()) {
                    StackedBarChart(
                        series = listOf(
                            ChartSeries(
                                values = s.buckets.map { it.kwh_used.toFloat() },
                                color = MaterialTheme.colorScheme.secondary,
                                label = "用电",
                            ),
                        ),
                        yUnit = "kWh",
                        xLabels = bucketAxisLabels(s.buckets.map { it.start }),
                        contentDescription = statsChartDescription("行驶耗电", s.buckets.map { it.start to it.kwh_used }),
                    )
                    RangeHint(s)
                }
            }

            val b = battery
            item {
                SectionCard("电池健康", Modifier.animateItem()) {
                    if (b == null || b.current_capacity_kwh == null) {
                        Text("暂无足够数据（需要 ≥90% 的完整充电记录）", style = MaterialTheme.typography.bodySmall)
                    } else {
                        KV("当前可用容量", "${b.current_capacity_kwh?.fmt(1)} kWh")
                        KV("历史最佳容量", "${b.max_capacity_kwh?.fmt(1)} kWh")
                        b.nominal_capacity_kwh?.let { KV("铭牌容量", "${it.fmt(1)} kWh") }
                        b.health_percent?.let { KV("健康度", "${it.fmt(1)}%") }
                        val series = b.series.map { it.capacity_kwh?.toFloat() }
                        if (series.count { it != null } >= 2) {
                            // ⚠️ 关键：把「铭牌容量」作为基准线画出来。
                            //   原来 Y 轴各自归一化到自己的峰值，66.0 → 65.4 kWh 的
                            //   真实衰减画出来是一条几乎水平的直线——衰减 1% 和 10% 看起来一样平。
                            //   锚定到铭牌容量的 1.3 倍后，衰减趋势一眼可见。
                            LineChart(
                                values = series,
                                color = MaterialTheme.colorScheme.secondary,
                                yUnit = "kWh",
                                xLabels = bucketAxisLabels(b.series.map { it.date.fmtDate() }),
                                referenceValue = b.nominal_capacity_kwh?.toFloat(),
                                referenceLabel = "铭牌",
                                contentDescription = batteryChartDescription(b),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 自绘分段控件（iOS UISegmentedControl 风格）。
 *
 * ⚠️ v0.7.1：原来用 M3 的 `SingleChoiceSegmentedButtonRow` —— 它自带描边、圆角和
 * 一个选中态填充块，**纯 Material 味**，与整 App 的 iOS 语言冲突。
 * 现在：外层一个低对比容器 + 内部 2dp 间隙 + 选中块纯色（深 #3A3A3C / 浅 #FFFFFF），
 * 文字颜色区分选中与否，没有描边、没有 M3 指示器。
 */
@Composable
private fun PeriodSegmented(
    periods: List<Pair<String, String>>,
    current: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isDarkTheme()
    val trackColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFE9E9EC)
    val selectedBg = if (dark) Color(0xFF3A3A3C) else Color.White
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(trackColor)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        periods.forEach { (key, label) ->
            val selected = current == key
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) selectedBg else Color.Transparent)
                    .clickable { onSelect(key) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 图表下方的摘要行。
 *
 * ⚠️ v0.7.1：原来这里打的是**首末日期**，而图表 X 轴已经打了首/中/末三个日期标签 ——
 * 同一件事说两遍。换成真正有用的**峰值 + 均值**。
 */
@Composable
private fun RangeHint(s: Stats) {
    if (s.buckets.isEmpty()) return
    val km = s.buckets.map { it.km }.filter { it > 0 }
    if (km.isEmpty()) return
    val peak = km.max()
    val peakDate = s.buckets.firstOrNull { it.km == peak }?.start?.take(10)?.drop(5) ?: ""
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            "峰值 ${peak.fmt(0)} km（$peakDate）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "日均 ${(km.sum() / km.size).fmt(0)} km",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
