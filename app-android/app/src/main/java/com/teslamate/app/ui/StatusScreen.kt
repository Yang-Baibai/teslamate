@file:OptIn(ExperimentalMaterial3Api::class)

package com.teslamate.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.teslamate.app.ActiveCharge
import com.teslamate.app.AppSettings
import com.teslamate.app.CarStatus
import com.teslamate.app.fmt
import com.teslamate.app.fmtShort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 车况首页：大标题折叠栏 + 下拉刷新 + 15 秒自动刷新 */
@Composable
fun StatusScreen(settings: AppSettings, modifier: Modifier) {
    val api = remember(settings) { settings.api() }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<CarStatus?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(false) }

    // ⚠️ 修正：地图展开时按返回键原本会直接退出 App（没有 BackHandler）。
    BackHandler(enabled = showMap) { showMap = false }

    suspend fun load() {
        if (api == null) {
            error = if (settings.configured) "服务器地址或令牌无效，请到「设置」页检查" else "尚未配置服务器，请到「设置」页填写地址与令牌"
            return
        }
        runCatching { withContext(Dispatchers.IO) { api.status(settings.carId) } }
            .onSuccess { status = it; error = null }
            .onFailure { error = it.message ?: "网络错误" }
    }

    fun refresh() {
        scope.launch {
            refreshing = true
            load()
            refreshing = false
        }
    }

    // 15 秒自动刷新，但必须跟随界面生命周期：
    // 原写法 `while (isActive)` 在 Activity onStop 时并不会被取消（Compose 树仍存活），
    // 于是 App 一旦退到后台就继续每 15 秒打一次 API，白耗电白耗流量。
    // repeatOnLifecycle(STARTED) 保证：前台跑、后台停、回来再跑，且首次进入立刻刷一次。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, settings) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                load()
                delay(15_000)
            }
        }
    }


    Column(modifier) {
        TopAppBar(
            title = { Text("车况", fontWeight = FontWeight.Bold) },
            actions = {
                IconButton(onClick = { refresh() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            },
        )
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        // ⚠️ 底部必须留出导航栏高度：导航栏是半透明毛玻璃，
                        // 内容会滚到它下面；不留 padding 的话最后一项（胎压卡）
                        // 永远被盖住且滚不到。
                        bottom = 16.dp + navBarBottom(),
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                error?.let { ErrorBanner(it) }

                val st = status
                when {
                    st == null && error == null -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SkeletonBox(Modifier.fillMaxWidth().height(180.dp))
                        SkeletonBox(Modifier.fillMaxWidth().height(96.dp))
                        SkeletonBox(Modifier.fillMaxWidth().height(96.dp))
                    }

                    st != null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StateChip(st.state)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "更新于 ${st.updated_at.fmtShort()}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // 数据新鲜度：让「车睡着了（正常）」和「连不上服务器」不再长得一样。
                        // 连不上服务器的情况由上面的 ErrorBanner 负责（那是请求失败）；
                        // 这里说明的是「请求成功了，但车本身很久没上报」——多半是车停着不动。
                        // ⚠️ 措辞用「数据未更新」而不是「离线」——「离线」在状态胶囊里指的是
                        //    车辆离线，两个意思混用会让人张冠李戴。
                        val staleHours = st.data_age_hours
                        if (staleHours >= 24) {
                            NoticeBanner(
                                "数据已 $staleHours 小时未更新。车停着不动属正常；" +
                                    "若你人在外地，也可能是网络不通，请留意上方是否有红色提示。"
                            )
                        } else if (staleHours >= 6) {
                            NoticeBanner("数据已 $staleHours 小时未更新，可能不是最新的。")
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) {
                            SocRing(st.battery_level)
                            Spacer(Modifier.width(28.dp))
                            Column {
                                // 续航用「预估」口径（est → ideal → rated 依次降级）。
                                // 标定续航是 EPA 上限，车机实际显示的预估会随近期能耗浮动。
                                val range = st.est_range_km ?: st.ideal_range_km ?: st.rated_range_km
                                BigStat(range?.fmt(0) ?: "—", "预估续航 (km)")
                                Spacer(Modifier.height(20.dp))
                                BigStat(st.odometer?.fmt(0) ?: "—", "里程表 (km)")
                            }
                        }

                        // 能量条 —— 特斯拉最易识别的视觉母题，充电中转绿
                        EnergyBar(
                            soc = st.battery_level,
                            charging = st.active_charge != null,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )

                        st.active_charge?.let { ChargeNowCard(it) }
                        st.active_drive?.let { DriveNowCard(it, st) }

                        val lat = st.map_lat ?: st.lat
                        val lng = st.map_lng ?: st.lng
                        if (lat != null && lng != null) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pressScale()
                                    .clickable { showMap = !showMap },
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(
                                        "位置",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        st.location_name ?: "未命名地点",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    AnimatedVisibility(
                                        visible = showMap,
                                        enter = expandVertically() + fadeIn(),
                                        exit = shrinkVertically() + fadeOut(),
                                    ) {
                                        Column {
                                            Spacer(Modifier.height(10.dp))
                                            TrackMap(
                                                points = listOf(GeoPt(lat, lng)),
                                                drawLine = false,
                                                settings = settings,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(220.dp),
                                            )
                                        }
                                    }
                                    // ⚠️ 原来这里写「点击展开地图」——开发者口吻，
                                    // 而且整块已经可点了，不需要额外解释。
                                    if (!showMap) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = "展开地图",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            InfoTile("车外温度", st.outside_temp?.fmt(1, "°C") ?: "—", Modifier.weight(1f))
                            InfoTile("车内温度", st.inside_temp?.fmt(1, "°C") ?: "—", Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            InfoTile(
                                "空调",
                                // 车没上报时是 null，显示"关闭"会骗人 —— 必须区分「未知」
                                when (st.is_climate_on) {
                                    true -> "开启"
                                    false -> "关闭"
                                    null -> "未知"
                                },
                                Modifier.weight(1f),
                            )
                            InfoTile("功率", st.power?.let { "$it kW" } ?: "—", Modifier.weight(1f))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            InfoTile(
                                "海拔",
                                st.elevation?.let { "${it} m" } ?: "—",
                                Modifier.weight(1f),
                            )
                            InfoTile(
                                "可用电量",
                                st.usable_battery_level?.let { "$it%" } ?: "—",
                                Modifier.weight(1f),
                            )
                        }
                        TpmsCard(st)
                    }
                }
            }
        }
    }
}

/**
 * 胎压卡：俯视车辆 + 四轮数值。
 *
 * 俯视图是本 App **唯一能超越官方 Tesla App** 的地方（官方不展示四轮胎压）。
 * 异常轮用危险红实心 + 呼吸动画，接近阈值用琥珀，正常用中性灰。
 * 判定完全由服务端算好（api/alerts.go），App 只读结果——阈值也只有服务端一份。
 */
@Composable
private fun TpmsCard(st: CarStatus) {
    val fl = st.tpms_fl; val fr = st.tpms_fr; val rl = st.tpms_rl; val rr = st.tpms_rr
    if (fl == null && fr == null && rl == null && rr == null) return // 一个都没有就不占版面

    val reading = TpmsReadingUi(fl, fr, rl, rr, st.tpms_min_bar, st.tpms_max_bar)

    SectionCard(
        title = if (st.tpms_alert) "轮胎胎压 · 建议检查" else "轮胎胎压",
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            VehicleTopView(reading = reading, modifier = Modifier.weight(0.9f))
            Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TpmsValue("左前", fl, reading.isAlerting(fl))
                TpmsValue("右前", fr, reading.isAlerting(fr))
                TpmsValue("左后", rl, reading.isAlerting(rl))
                TpmsValue("右后", rr, reading.isAlerting(rr))
            }
        }
        // 告警不能只靠颜色（WCAG 1.4.1）——服务端已算好这句文案，直接用
        st.tpms_alert_detail?.takeIf { it.isNotBlank() }?.let {
            Text(
                "⚠ $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Text(
            "正常范围 ${st.tpms_min_bar}–${st.tpms_max_bar} bar（冷胎）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TpmsValue(label: String, value: Double?, alert: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(34.dp),
        )
        Text(
            value?.let { "${it.fmt(2)} bar" } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (alert) {
            Spacer(Modifier.width(4.dp))
            Text(
                "⚠",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun TpmsCell(label: String, value: Double?, alert: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (alert) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (alert) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                value?.let { "${it.fmt(2)} bar" } ?: "—",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (alert) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ChargeNowCard(c: ActiveCharge) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "⚡ 充电中${if (c.fast_charger_present == true) " · 直流快充" else ""}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "自 ${c.started_at.fmtShort()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoTile("功率", "${c.charger_power_kw ?: "—"} kW", Modifier.weight(1f))
                InfoTile("已充入", c.energy_added_kwh?.fmt(1, " kWh") ?: "—", Modifier.weight(1f))
                InfoTile("当前电量", "${c.battery_level ?: "—"}%", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DriveNowCard(d: com.teslamate.app.ActiveDrive, st: CarStatus) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🚗 行驶中", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    "自 ${d.started_at.fmtShort()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoTile("车速", st.speed?.let { "$it km/h" } ?: "—", Modifier.weight(1f))
                InfoTile("功率", st.power?.let { "$it kW" } ?: "—", Modifier.weight(1f))
                InfoTile("电量", "${st.battery_level ?: "—"}%", Modifier.weight(1f))
            }
        }
    }
}
