@file:OptIn(ExperimentalMaterial3Api::class)

package com.teslamate.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.teslamate.app.Api
import com.teslamate.app.AppSettings
import com.teslamate.app.Charge
import com.teslamate.app.CurvePoint
import com.teslamate.app.fmt
import com.teslamate.app.fmtFull
import com.teslamate.app.fmtMonth
import com.teslamate.app.fmtShort
import com.teslamate.app.minutesFmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ChargesScreen(settings: AppSettings, modifier: Modifier) {
    val api = remember(settings) { settings.api() }
    var list by remember { mutableStateOf<List<Charge>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Charge?>(null) }

    LaunchedEffect(settings) {
        if (api == null) {
            error = if (settings.configured) "服务器地址或令牌无效" else "尚未配置服务器，请到「设置」页"
            loading = false
            return@LaunchedEffect
        }
        runCatching { withContext(Dispatchers.IO) { api.charges(settings.carId, 100) } }
            .onSuccess { list = it; error = null; loading = false }
            .onFailure { error = it.message; loading = false }
    }

    // 列表 ⇄ 详情：Play 式推入/滑出
    AnimatedContent(
        targetState = selected,
        modifier = modifier,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(tween(300)) { it } + fadeIn(tween(240))) togetherWith
                    (fadeOut(tween(180)) + slideOutHorizontally(tween(280)) { -it / 10 })
            } else {
                (fadeIn(tween(240)) + slideInHorizontally(tween(280)) { -it / 10 }) togetherWith
                    (slideOutHorizontally(tween(300)) { it } + fadeOut(tween(180)))
            }
        },
        label = "chargeDetail",
    ) { sel ->
        if (sel == null) ChargeList(list, loading, error, settings, onSelect = { selected = it })
        else ChargeDetail(sel, api) { selected = null }
    }
}

@Composable
private fun ChargeList(
    list: List<Charge>,
    loading: Boolean,
    error: String?,
    settings: AppSettings,
    onSelect: (Charge) -> Unit,
) {

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("充电", fontWeight = FontWeight.Bold) },
        )
        when {
            error != null -> Column(Modifier.padding(16.dp)) { ErrorBanner(error!!) }
            loading -> ListSkeleton(items = 7)
            list.isEmpty() -> Column(Modifier.padding(16.dp)) { SectionCard { Text("暂无充电记录") } }
            else -> {
                val groups = remember(list) { list.groupBy { it.start_date.fmtMonth() } }
                val totalKwh = list.sumOf { it.energy_added_kwh ?: 0.0 }
                val totalCost = list.sumOf { it.cost ?: 0.0 }
                val dcCount = list.count { it.is_dc }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "summary") {
                        RecentSummaryCard(settings = settings, fallback = {
                            Text(
                                "共 ${list.size} 次 · 充入 ${totalKwh.fmt(1)} kWh",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        })
                    }
                    groups.forEach { (month, charges) ->
                        item(key = "h-$month") {
                            Text(
                                month,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp).animateItem(),
                            )
                        }
                        items(charges, key = { it.id }) { c ->
                            ChargeItem(c, Modifier.animateItem()) { onSelect(c) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChargeItem(c: Charge, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .pressScale()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.place ?: "未知地点",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (c.is_dc) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        if (c.is_dc) "快充" else "慢充",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (c.is_dc) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                }
            }
            Text(
                "${c.start_date.fmtShort()} · ${minutesFmt(c.duration_min)} · 充入 ${c.energy_added_kwh?.fmt(1)} kWh" +
                    " · ${c.start_soc ?: "—"}%→${c.end_soc ?: "—"}%" +
                    (c.cost?.let { " · ¥${it.fmt(2)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChargeDetail(charge: Charge, api: Api?, onClose: () -> Unit) {
    var curve by remember { mutableStateOf<List<CurvePoint>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(charge.id) {
        if (api != null) {
            runCatching { withContext(Dispatchers.IO) { api.curve(charge.id) } }
                .onSuccess { curve = it }
                .onFailure { err = it.message }
        }
    }

    BackHandler { onClose() }


    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("充电详情") },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            err?.let { item { ErrorBanner(it) } }

            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (charge.is_dc) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().animateItem(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            charge.place ?: "未知地点",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row {
                            Text(
                                if (charge.is_dc) "⚡ 直流快充" else "🔌 交流慢充",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            charge.geofence_name?.takeIf { it != charge.place }?.let {
                                Spacer(Modifier.padding(start = 8.dp))
                                Text(
                                    "· $it",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            item {
                SectionCard("时间与电量", Modifier.animateItem()) {
                    KV("开始", charge.start_date.fmtFull())
                    KV("结束", charge.end_date?.fmtFull() ?: "—")
                    KV("时长", minutesFmt(charge.duration_min))
                    KV("电量变化", "${charge.start_soc ?: "—"}% → ${charge.end_soc ?: "—"}%")
                    KV("续航变化", rangeDelta(charge.start_range_km, charge.end_range_km))
                }
            }

            item {
                SectionCard("能量", Modifier.animateItem()) {
                    KV("充入电量", charge.energy_added_kwh?.fmt(2, " kWh") ?: "—")
                    KV("表计用电", charge.energy_used_kwh?.fmt(2, " kWh") ?: "—")
                    charge.loss_percent?.let {
                        KV("充电损耗率", "${it.fmt(1)}%")
                    }
                }
            }

            item {
                SectionCard("充电路况", Modifier.animateItem()) {
                    KV("峰值功率", charge.peak_power_kw?.let { "$it kW" } ?: "—")
                    KV("平均功率", charge.avg_power_kw?.let { "$it kW" } ?: "—")
                    val cableCn = when (charge.cable_type) {
                        "GB_AC" -> "国标交流桩"
                        "GB_DC" -> "国标直流桩"
                        "Tesla", "Tesla_AC" -> "特斯拉交流桩"
                        "Tesla_DC" -> "特斯拉超充"
                        else -> null
                    }
                    if (cableCn != null) {
                        KV("充电桩类型", cableCn)
                    }
                    KV("峰值电压", charge.peak_voltage?.let { "$it V" } ?: "—")
                    KV("平均电压", charge.avg_voltage?.let { "$it V" } ?: "—")
                    KV("峰值电流", charge.peak_current?.let { "$it A" } ?: "—")
                    KV("平均电流", charge.avg_current?.let { "$it A" } ?: "—")
                    val phase = charge.charger_phases
                    val v = charge.charger_voltage
                    val a = charge.charger_current
                    if (phase != null && phase > 0) {
                        KV("相数", "$phase 相（当前 ${v ?: "—"}V / ${a ?: "—"}A）")
                    } else if (v != null && v > 0) {
                        KV("当前电压/电流", "$v V / $a A（直流）")
                    } else if (charge.is_dc) {
                        Text(
                            "本充电桩（直流快充）未上报电压与电流，仅记录功率。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SectionCard("温度", Modifier.animateItem()) {
                    charge.outside_temp_avg?.let {
                        KV("平均车外温度", "${it.fmt(1)}°C")
                    }
                    KV("起始车外", charge.start_outside_temp?.let { "${it.fmt(1)}°C" } ?: "—")
                    KV("结束车外", charge.end_outside_temp?.let { "${it.fmt(1)}°C" } ?: "—")
                    KV("起始车内", charge.start_indoor_temp?.let { "${it.fmt(1)}°C" } ?: "—")
                    KV("结束车内", charge.end_indoor_temp?.let { "${it.fmt(1)}°C" } ?: "—")
                }
            }

            if (curve.isNotEmpty()) {
                val powers = curve.mapNotNull { it.charger_power_kw }
                if (powers.isNotEmpty()) {
                    item {
                        SectionCard("充电功率曲线 (kW)", Modifier.animateItem()) {
                            LineChart(
                                values = curve.map { it.charger_power_kw?.toFloat() },
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    item {
                        SectionCard("电量曲线 (%)", Modifier.animateItem()) {
                            LineChart(
                                values = curve.map { it.battery_level?.toFloat() },
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            } else if (err == null) {
                item { SkeletonBox(Modifier.fillMaxWidth().height(150.dp)) }
            }
        }
    }
}

internal fun rangeDelta(start: Double?, end: Double?): String {
    if (start == null || end == null) return "—"
    val d = end - start
    val sign = if (d >= 0) "+" else "−"
    return "${start.fmt(0)} → ${end.fmt(0)} km（$sign${kotlin.math.abs(d).fmt(0)} km）"
}
