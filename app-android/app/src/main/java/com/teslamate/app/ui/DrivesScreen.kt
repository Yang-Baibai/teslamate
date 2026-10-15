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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
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
import com.teslamate.app.Drive
import com.teslamate.app.TrackPoint
import com.teslamate.app.fmt
import com.teslamate.app.fmtFull
import com.teslamate.app.fmtMonth
import com.teslamate.app.fmtShort
import com.teslamate.app.minutesFmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DrivesScreen(settings: AppSettings, modifier: Modifier) {
    val api = remember(settings) { settings.api() }
    var list by remember { mutableStateOf<List<Drive>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Drive?>(null) }

    LaunchedEffect(settings) {
        if (api == null) {
            error = if (settings.configured) "服务器地址或令牌无效" else "尚未配置服务器，请到「设置」页"
            loading = false
            return@LaunchedEffect
        }
        runCatching { withContext(Dispatchers.IO) { api.drives(settings.carId, 50) } }
            .onSuccess { list = it; error = null; loading = false }
            .onFailure { error = it.message; loading = false }
    }

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
        label = "driveDetail",
    ) { sel ->
        if (sel == null) DriveList(list, loading, error, settings, onSelect = { selected = it })
        else DriveDetail(sel, api, settings) { selected = null }
    }
}

@Composable
private fun DriveList(
    list: List<Drive>,
    loading: Boolean,
    error: String?,
    settings: AppSettings,
    onSelect: (Drive) -> Unit,
) {

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("行程", fontWeight = FontWeight.Bold) },
        )
        when {
            error != null -> Column(Modifier.padding(16.dp)) { ErrorBanner(error!!) }
            loading -> ListSkeleton(items = 7)
            list.isEmpty() -> Column(Modifier.padding(16.dp)) { SectionCard { Text("暂无行程记录") } }
            else -> {
                val groups = remember(list) { list.groupBy { it.start_date.fmtMonth() } }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    // 与充电页保持一致：固定 padding，不用 systemBars。
                    // systemBars 含 top inset，会在 TopAppBar 下方重复加一次状态栏高度，
                    // 表现为标题与列表之间多出一块空白。
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "summary") {
                        RecentSummaryCard(settings = settings, fallback = {
                            Text(
                                "共 ${list.size} 次行程",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        })
                    }
                    groups.forEach { (month, drives) ->
                        item(key = "h-$month") {
                            Text(
                                month,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp).animateItem(),
                            )
                        }
                        items(drives, key = { it.id }) { d ->
                            DriveItem(d, Modifier.animateItem()) { onSelect(d) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveItem(d: Drive, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
                    "${d.start_place ?: "?"} → ${d.end_place ?: "?"}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${d.distance_km?.fmt(1) ?: "—"} km",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "${d.start_date.fmtShort()} · ${minutesFmt(d.duration_min)} · ${d.energy_kwh?.fmt(1)} kWh" +
                    " · ${d.wh_per_km?.fmt(0) ?: "—"} Wh/km · 最高 ${d.speed_max ?: "—"} km/h",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DriveDetail(drive: Drive, api: Api?, settings: AppSettings, onClose: () -> Unit) {
    var track by remember { mutableStateOf<List<TrackPoint>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(drive.id) {
        if (api != null) {
            runCatching { withContext(Dispatchers.IO) { api.track(drive.id) } }
                .onSuccess { track = it }
                .onFailure { err = it.message }
        }
    }

    BackHandler { onClose() }


    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("行程详情") },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )
        // 地图固定顶部：不随下方滚动消失（修"地图白屏/被滚走"）
        if (track.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().height(260.dp)) {
                TrackMap(
                    points = track.map { GeoPt(it.map_lat ?: it.lat, it.map_lng ?: it.lng) },
                    drawLine = true,
                    settings = settings,
                    startTitle = "起点：${drive.start_place?.take(20) ?: "?"}",
                    endTitle = "终点：${drive.end_place?.take(20) ?: "?"}",
                )
            }
            Text(
                "轨迹（${track.size} 个采样点）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        } else if (err == null) {
            Box(Modifier.fillMaxWidth().height(220.dp).padding(horizontal = 16.dp)) {
                SkeletonBox(Modifier.fillMaxWidth().height(220.dp))
            }
        }
        err?.let {
            Box(Modifier.padding(horizontal = 16.dp)) { ErrorBanner(it) }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard("时间与电量", Modifier.animateItem()) {
                    KV("开始", drive.start_date.fmtFull())
                    KV("结束", drive.end_date?.fmtFull() ?: "—")
                    KV("时长", minutesFmt(drive.duration_min))
                    KV("里程", drive.distance_km?.fmt(2, " km") ?: "—")
                    KV("电量变化", "${drive.start_soc ?: "—"}% → ${drive.end_soc ?: "—"}%")
                    KV("标定续航变化", rangeDelta(drive.start_rated_range_km, drive.end_rated_range_km))
                    KV("预估续航变化", rangeDelta(drive.start_ideal_range_km, drive.end_ideal_range_km))
                }
            }
            item {
                SectionCard("能耗", Modifier.animateItem()) {
                    KV("行车用电", drive.energy_kwh?.fmt(2, " kWh") ?: "—")
                    KV("百公里能耗", drive.wh_per_km?.let { "${it.fmt(0)} Wh/km" } ?: "—")
                    val odo = drive.odometer_end?.let { drive.odometer_start?.let { s -> it - s } }
                    if (odo != null && drive.energy_kwh != null && odo > 0) {
                        val eq = drive.energy_kwh * 1000 / odo
                        KV("按里程算能耗", "${eq.fmt(0)} Wh/km（odometer 差）")
                    }
                }
            }
            item {
                SectionCard("车速与功率", Modifier.animateItem()) {
                    KV("平均车速", drive.avg_speed_kph?.let { "$it km/h" } ?: "—")
                    KV("最高车速", drive.speed_max?.let { "$it km/h" } ?: "—")
                    KV("峰值功率", drive.power_max_kw?.let { "$it kW" } ?: "—")
                    drive.power_min_kw?.let { if (it < 0) KV("回收峰值", "${-it} kW") }
                }
            }
            item {
                SectionCard("地形与温度", Modifier.animateItem()) {
                    drive.ascent_m?.let { if (it > 0) KV("累计爬升", "$it m") }
                    drive.descent_m?.let { if (it > 0) KV("累计下降", "$it m") }
                    KV("平均车外温度", drive.outside_temp_avg?.fmt(1, "°C") ?: "—")
                    drive.inside_temp_avg?.let { KV("平均车内温度", "${it.fmt(1)}°C") }
                }
            }
            item {
                SectionCard("地点与归属", Modifier.animateItem()) {
                    KV("起点", drive.start_place ?: "—")
                    drive.start_geofence?.let { KV("起点围栏", it) }
                    KV("终点", drive.end_place ?: "—")
                    drive.end_geofence?.let { KV("终点围栏", it) }
                    if (drive.has_supercharger) {
                        KV("超充经过", "是（途中到达过超级充电桩）")
                    }
                }
            }
        }
    }
}
