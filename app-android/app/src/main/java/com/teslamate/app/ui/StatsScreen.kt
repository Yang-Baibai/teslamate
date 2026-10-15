@file:OptIn(ExperimentalMaterial3Api::class)

package com.teslamate.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teslamate.app.AppSettings
import com.teslamate.app.BatteryHealth
import com.teslamate.app.Stats
import com.teslamate.app.fmt
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
            title = { Text("统计", fontWeight = FontWeight.Bold) },
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SingleChoiceSegmentedButtonRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    periods.forEachIndexed { i, (key, label) ->
                        SegmentedButton(
                            selected = period == key,
                            onClick = { period = key },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = periods.size),
                        ) {
                            Text(label)
                        }
                    }
                }
            }

            if (error != null) {
                item { Column(Modifier.padding(horizontal = 16.dp)) { ErrorBanner(error!!) } }
                return@LazyColumn
            }

            val s = stats
            if (s == null) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                SectionCard("里程 (km)", Modifier.animateItem()) {
                    StackedBarChart(
                        series = listOf(
                            ChartSeries(
                                values = s.buckets.map { it.km.toFloat() },
                                color = MaterialTheme.colorScheme.primary,
                                label = "里程",
                            ),
                        ),
                    )
                    RangeHint(s)
                }
            }

            item {
                SectionCard("充电量 (kWh)", Modifier.animateItem()) {
                    ChartLegend(
                        listOf(
                            "直流快充" to MaterialTheme.colorScheme.primary,
                            "交流慢充" to MaterialTheme.colorScheme.tertiary,
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
                                color = MaterialTheme.colorScheme.tertiary,
                                label = "交流",
                            ),
                        ),
                    )
                    RangeHint(s)
                }
            }

            item {
                SectionCard("行车用电 (kWh)", Modifier.animateItem()) {
                    StackedBarChart(
                        series = listOf(
                            ChartSeries(
                                values = s.buckets.map { it.kwh_used.toFloat() },
                                color = MaterialTheme.colorScheme.secondary,
                                label = "用电",
                            ),
                        ),
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
                        b.health_percent?.let { KV("健康度", "${it.fmt(1)}%") }
                        val series = b.series.map { it.capacity_kwh?.toFloat() }
                        if (series.count { it != null } >= 2) {
                            LineChart(
                                values = series,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RangeHint(s: Stats) {
    if (s.buckets.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            s.buckets.first().start.take(10),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            s.buckets.last().start.take(10),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
