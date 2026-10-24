@file:OptIn(ExperimentalMaterial3Api::class)

package com.teslamate.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
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
import com.teslamate.app.firstSegment
import com.teslamate.app.fmtShort
import com.teslamate.app.minutesFmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
        transitionSpec = { pushDetailTransition(this) },
        label = "driveDetail",
    ) { sel ->
        Box(Modifier.fillMaxSize()) {
            if (sel == null) DriveList(list, loading, error, settings, onSelect = { selected = it })
            else DriveDetail(sel, api, settings) { selected = null }
            if (sel != null) PushEdgeShadow(Modifier.align(Alignment.CenterStart))
        }
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
            title = { ScreenTitle("行程") },
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

/**
 * 行程列表行。
 *
 * ⚠️ **v0.7.1 修正**：同充电列表的老问题 —— 一行 `maxLines=1` 把
 * "10-12 09:15 · 47分钟 · 12.3 kWh · 148 Wh/km · 最高 132 km/h" 截断，
 * **能耗与最高速**（数据控车主最关心的两项）永远看不到。
 * 改成三层结构：路线（可两行）→ 时间与时长 → 三个并排指标。
 */
@Composable
private fun DriveItem(d: Drive, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accents = LocalAccentColors.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .cardDepth()
            .pressable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = accents.cardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    // 起终点只取地址第一段（完整邮编地址在详情页里保留）
                    "${d.start_place.firstSegment() ?: "?"} → ${d.end_place.firstSegment() ?: "?"}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "${d.distance_km?.fmt(1) ?: "—"} km",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    // ⚠️ v0.8.3：原来用 primary（特斯拉红）。列表里重复几十次的红色数字
                    // 是「一眼看出是套模板」的廉价感来源 —— 红色在本 App 的语义里只表示
                    // 告警/品牌点缀，不该用来标里程。
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                "${d.start_date.fmtShort()} · ${minutesFmt(d.duration_min)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                InlineMetric("耗电", "${d.energy_kwh?.fmt(1) ?: "—"} kWh")
                InlineMetric("能耗", "${d.wh_per_km?.fmt(0) ?: "—"} Wh/km")
                InlineMetric("最高", "${d.speed_max ?: "—"} km/h")
            }
        }
    }
}

@Composable
private fun DriveDetail(drive: Drive, api: Api?, settings: AppSettings, onClose: () -> Unit) {
    var track by remember { mutableStateOf<List<TrackPoint>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    // 充电记录：用来关联「这趟行程到达时充的是哪一次电」
    var charges by remember { mutableStateOf<List<com.teslamate.app.Charge>>(emptyList()) }

    LaunchedEffect(drive.id) {
        if (api != null) {
            runCatching { withContext(Dispatchers.IO) { api.track(drive.id) } }
                .onSuccess { track = it }
                .onFailure { err = it.message }
        }
    }
    LaunchedEffect(drive.id, api) {
        if (api != null) {
            runCatching { withContext(Dispatchers.IO) { api.charges(settings.carId, 200) } }
                .onSuccess { charges = it }
        }
    }

    // ── 关联「到达时的充电」───────────────────────────────────
    // ⚠️ 服务端的 `charge_after_trip` 只能告诉你「附近有过充电」，
    // 没说**是哪一次**。长途党要的是这句话：
    //   「这趟 343 km，你在 G210 车河镇充的电，13%→87%，停了 49 分钟。」
    // 判据用**里程**：充电起始里程与行程结束里程相差 < 2 km 即视为同一次
    // （实测 17 次长途充电全部唯一命中，误差 ≤ 1 km）。
    // 时间窗判据在这里行不通 —— 充电永远发生在行程**结束之后**。
    val linkedCharge = remember(charges, drive) {
        val end = drive.odometer_end ?: return@remember null
        charges
            .filter { c ->
                val o = c.start_odometer_km ?: return@filter false
                kotlin.math.abs(o - end) < 2.0
            }
            .minByOrNull { kotlin.math.abs((it.start_odometer_km ?: 0.0) - end) }
    }

    // 功率剖面：把采样点转成「累计里程 → 净功率」，按 1 km 分箱取平均。
    // ⚠️ X 轴必须是里程不是时间 —— 时间轴在堵车时会骗人（原地烧电画成"省电"）。
    val powerProfile = remember(track) { track.toPowerProfile() }

    BackHandler { onClose() }


    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { ScreenTitle("行程详情") },
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
            // ── 功率剖面 ──────────────────────────────────────────
            // 长途自驾党在实测 43 条行程后选它当唯一最有价值的曲线：
            // 一条线同时给出能耗、地形、回收三件事（海拔曲线是它的子集），
            // 而且能解释 Wh/km 的异常 —— 看到某段爬坡功率跳到 48 kW，
            // 下次跑同一条高速就会主动避开。
            if (powerProfile.size >= 2) {
                item {
                    SectionCard("功率剖面 (kW · 对应里程)", Modifier.animateItem()) {
                        val peakUp = powerProfile.maxOf { it.powerKw }
                        val peakDown = powerProfile.minOf { it.powerKw }
                        PowerProfileChart(
                            samples = powerProfile,
                            contentDescription = "功率随里程变化曲线，" +
                                "全程 ${powerProfile.last().km.roundToInt()} 公里，" +
                                "最高耗电 ${peakUp.roundToInt()} 千瓦，" +
                                "最深回收 ${kotlin.math.abs(peakDown).roundToInt()} 千瓦",
                        )
                        Text(
                            "正值 = 耗电（蓝）· 负值 = 能量回收（绿）" +
                                (drive.wh_per_km?.let { " · 本程 ${it.fmt(0)} Wh/km" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
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
                    // ⚠️ v0.7.1：原来写「超充经过 / 途中到达过超级充电桩」，
                    // 但服务端那个判据（充电时间落在行程时间窗内）**永远命中不了** ——
                    // 充电发生在行程结束之后，于是这个字段恒为 false（200 条行程 0 条命中）。
                    // 服务端已改用里程关联修好，命中数变成 52/200。
                    // 同时措辞按新语义修正：说的是「到达时有充电」，不是「途中路过超充」。
                    if (drive.charge_after_trip || drive.has_supercharger) {
                        KV(
                            "到达时充电",
                            if (drive.has_supercharger) "是（到达时有直流快充）" else "是（到达时有交流充电）",
                        )
                    }
                    // 关联到具体哪一次充电：长途党最想复盘的就是这句
                    linkedCharge?.let { c ->
                        KV(
                            "这次充电",
                            buildString {
                                append(c.place.firstSegment() ?: "未知地点")
                                append(" · ")
                                append("${c.start_soc ?: "—"}%→${c.end_soc ?: "—"}%")
                                append(" · ")
                                append(minutesFmt(c.duration_min))
                                c.avg_power_kw?.let { append(" · ").append(it).append(" kW") }
                            },
                        )
                    }
                }
            }
        }
    }
}
