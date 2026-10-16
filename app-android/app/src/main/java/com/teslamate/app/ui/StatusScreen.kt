@file:OptIn(ExperimentalMaterial3Api::class)

package com.teslamate.app.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
                    .padding(16.dp),
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

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) {
                            SocRing(st.battery_level)
                            Spacer(Modifier.width(28.dp))
                            Column {
                                BigStat(st.rated_range_km?.fmt(0) ?: "—", "续航 (km)")
                                Spacer(Modifier.height(20.dp))
                                BigStat(st.odometer?.fmt(0) ?: "—", "总里程 (km)")
                            }
                        }

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
                                    if (!showMap) {
                                        Text(
                                            "点击展开地图",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            InfoTile("空调", if (st.is_climate_on == true) "开启" else "关闭", Modifier.weight(1f))
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
                        TpmsCard(st.tpms_fl, st.tpms_fr, st.tpms_rl, st.tpms_rr)
                    }
                }
            }
        }
    }
}

/**
 * 胎压卡（2×2 网格：左前/右前/左后/右后）。
 * 胎压是家用车最有价值的安全仪表——胎压异常（过高/过低/单胎明显偏低）
 * 会用橙色高亮提示，这是 Tesla App 也没有做但确实有用的一环。
 *
 * ⚠️ 阈值的来历（2026-10-16 改）：原先写死「2.4–2.9 bar」是抄来的通用值，与本车实际
 * 工况严重不符——查 51434 个真实采样点，本车范围 2.50–3.30、平均 2.96 bar，于是
 * **62.4% 的记录都会触发「建议检查」**。一个 62% 时间误报的告警等于没有告警：
 * 用户会把它当噪音忽略，连带把真正该响的漏气信号也一起忽略，比没有这功能更糟。
 * 现按本车实测分布改为 2.5–3.3 bar。真正能识别「单胎漏气」的是下面按中位数做的
 * 离群检测，保持不变。换车只需改上面两个常量。
 */
private const val TPMS_MIN_BAR = 2.5
private const val TPMS_MAX_BAR = 3.3
@Composable
private fun TpmsCard(fl: Double?, fr: Double?, rl: Double?, rr: Double?) {
    val all = listOfNotNull(fl, fr, rl, rr)
    if (all.isEmpty()) return // 一个都没有就不占版面

    fun warn(p: Double?): Boolean {
        if (p == null) return false
        return p < TPMS_MIN_BAR || p > TPMS_MAX_BAR
    }
    // 四胎中位数，用于"单胎明显偏低"判断（差 0.2 bar 以上）——这条才是漏气的主信号
    val median = if (all.isNotEmpty()) all.sorted()[all.size / 2] else null
    fun lowOutlier(p: Double?): Boolean {
        if (p == null || median == null) return false
        return median - p > 0.2
    }
    val anyWarn = warn(fl) || warn(fr) || warn(rl) || warn(rr) ||
        lowOutlier(fl) || lowOutlier(fr) || lowOutlier(rl) || lowOutlier(rr)

    SectionCard(
        title = if (anyWarn) "轮胎胎压 · 建议检查" else "轮胎胎压",
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TpmsCell("左前", fl, warn(fl) || lowOutlier(fl), Modifier.weight(1f))
            TpmsCell("右前", fr, warn(fr) || lowOutlier(fr), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TpmsCell("左后", rl, warn(rl) || lowOutlier(rl), Modifier.weight(1f))
            TpmsCell("右后", rr, warn(rr) || lowOutlier(rr), Modifier.weight(1f))
        }
        Text(
            "正常范围 $TPMS_MIN_BAR–$TPMS_MAX_BAR bar（冷胎）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
