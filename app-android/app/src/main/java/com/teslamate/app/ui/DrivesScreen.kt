@file:OptIn(ExperimentalMaterial3Api::class)

package com.teslamate.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.material3.Surface
import android.net.Uri
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.filled.Place
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.teslamate.app.Api
import com.teslamate.app.AppSettings
import com.teslamate.app.Drive
import com.teslamate.app.TrackPoint
import com.teslamate.app.fmt
import com.teslamate.app.fmtFull
import com.teslamate.app.fmtKwh100
import com.teslamate.app.fmtMonth
import com.teslamate.app.firstSegment
import com.teslamate.app.fmtShort
import com.teslamate.app.minutesFmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun DrivesScreen(
    settings: AppSettings,
    modifier: Modifier,
    /** v0.8.5：false = 嵌在「记录」页里（顶栏与「近 30 天」汇总统一收敛） */
    showHeader: Boolean = true,
) {
    val api = remember(settings) { settings.api() }
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Drive>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Drive?>(null) }

    // ⚠️ v0.9.0：抽成可复用的 load() —— 原来只有一次性加载，
    // 网络一断就再也不请求，用户必须「切走再切回」才能重试。
    suspend fun load() {
        if (api == null) {
            error = if (settings.configured) "服务器地址或令牌无效" else "尚未配置服务器，请到「设置」页"
            loading = false
            return
        }
        runCatching { withContext(Dispatchers.IO) { api.drives(settings.carId, 200) } }
            .onSuccess { list = it; error = null; loading = false }
            .onFailure { error = it.message; loading = false }
    }

    LaunchedEffect(settings) { load() }

    AnimatedContent(
        targetState = selected,
        modifier = modifier,
        transitionSpec = { pushDetailTransition(this) },
        label = "driveDetail",
    ) { sel ->
        Box(Modifier.fillMaxSize()) {
            if (sel == null) DriveList(
                list = list,
                loading = loading,
                error = error,
                settings = settings,
                showHeader = showHeader,
                refreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        load()
                        refreshing = false
                    }
                },
                onSelect = { selected = it },
            )
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
    showHeader: Boolean = true,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onSelect: (Drive) -> Unit,
) {

    Column(Modifier.fillMaxSize()) {
        if (showHeader) {
            TopAppBar(
                title = { ScreenTitle("行程") },
            )
        }
        // ⚠️ v0.9.0：加下拉刷新（原来失败后只能切 Tab 重试）。
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                error != null -> Column(Modifier.fillMaxSize().padding(16.dp)) { ErrorBanner(error!!) }
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
                        if (showHeader) {
                            item(key = "summary") {
                                RecentSummaryCard(settings = settings, fallback = {
                                    Text(
                                        "共 ${list.size} 次行程",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                })
                            }
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
}

/**
 * 行程列表行。
 *
 * ⚠️ **v0.7.1 修正**：同充电列表的老问题 —— 一行 `maxLines=1` 把
 * "10-12 09:15 · 47分钟 · 12.3 kWh · 148 Wh/km · 最高 132 km/h" 截断，
 * **能耗与最高速**（数据控车主最关心的两项）永远看不到。
 * 改成三层结构：路线（可两行）→ 时间与时长 → 三个并排指标。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DriveItem(
    d: Drive,
    modifier: Modifier = Modifier,
    /** ⚠️ v0.17.0：卡片底部的一行灰字摘要（放在**卡片内部**）。 */
    footnote: String? = null,
    onClick: () -> Unit,
) {
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
            // ⚠️ v0.9.2（苹果视觉评审 P0-3 + P2-6）：**起点与终点不允许被拆开**。
            //
            // 原来是一行文字「龙翔大道 → 永丰社区」，大字号下断行落在
            // 「永」和「丰社区」之间 —— 起点与终点被劈成两行，
            // 读起来像两个不相干的地名（「红棉路 → 龙岗大 / 道」同理）。
            //
            // 现在：
            //   - 文字箭头「→」换成 **chevron 图标**（12dp，固定宽度）——
            //     文字箭头与地名同字号同重，是纯噪点；
            //   - 起点 / chevron / 终点是一个**不换行的整体**（中间不许断），
            //     整体过长时在**终点名尾部**省略；
            //   - fontScale 大时**距离另起一行**（右对齐），不再与标题抢同一行。
            val fontScale = LocalDensity.current.fontScale
            val stacked = fontScale >= 1.15f
            val startName = d.start_place.firstSegment() ?: "?"
            val endName = d.end_place.firstSegment() ?: "?"
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        startName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "到",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(horizontal = 2.dp)
                            .size(16.dp),
                    )
                    Text(
                        endName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (stacked) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${d.distance_km?.fmt(1) ?: "—"} 公里",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    Spacer(Modifier.width(10.dp))
                }
            }
            if (!stacked) {
                // 常规字号：距离与标题同一行右对齐
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${d.distance_km?.fmt(1) ?: "—"} 公里",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        // ⚠️ v0.8.3：原来用 primary（特斯拉红）。列表里重复几十次的红色数字
                        // 是「一眼看出是套模板」的廉价感来源 —— 红色在本 App 的语义里只表示
                        // 告警/品牌点缀，不该用来标里程。
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // ⚠️ v0.9.2：disclosure indicator（会 push 出详情）
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                "${d.start_date.fmtShort()} · ${minutesFmt(d.duration_min)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // ⚠️ v0.8.10：原来用 Row(spacedBy(16.dp)) 横排各项，真机上放不下 ——
            // 最后一项被压成**竖排**（每个字符一行）。真机字体缩放大时才暴露。
            // 改用 FlowRow：放不下就自动换行，绝不挤压。
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                InlineMetric("耗电", "${d.energy_kwh?.fmt(1) ?: "—"} 度")
                // ⚠️ v0.14.0（单位审校 P0-1）：**短途不显示「能耗」**。
                //
                // 5.0 km 的行程会算出 22.4 kWh/100km、1.8 km 算出 21.3 ——
                // 那不是测量误差，是「整数公里标定续航 ÷ 行驶里程」的量纲必然结果
                // （分子 ±0.5 km 的量化误差在短途被放大成 20%+，
                // 停车开空调的耗电也摊在这 1.8 km 上）。
                //
                // 列表行是**扫读位**，放一个会误导的数字成本最高；
                // 30 公里这个阈值沿用已有的 `tooShortForSoc`，不新造第二个数。
                // 详情页仍然显示，但会加一句说明为什么偏高。
                if ((d.distance_km ?: 0.0) >= 30.0) {
                    InlineMetric("能耗", "${d.wh_per_km?.fmtKwh100() ?: "—"} kWh/100km")
                }
                // ⚠️ v0.8.6（长途自驾党复核）：把**到站电量**放进列表行。
                // 他的 18 次长途停靠，到达电量中位数只有 14%、5 次 ≤10% ——
                // 这个数是「这次长途够不够」的答案，却只躺在详情页第三张卡里。
                // end_soc <= 15 用琥珀色提示「到站偏低」。
                //
                // ⚠️ v0.9.2（长途自驾党 N3）：行程行原来只写「到达 70%」，
                // 而充电行写「电量 68 → 100%」—— 同一条时间轴上**两种口径**，
                // 看的人得自己拼。现在统一成「电量 85→70%」（有起点就用起点）。
                if (d.start_soc != null && d.end_soc != null) {
                    InlineMetric(
                        "电量",
                        "${d.start_soc}%→${d.end_soc}%",
                        valueColor = if (d.end_soc <= 15) accents.warnAmber else MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    d.end_soc?.let { soc ->
                        InlineMetric(
                            "到达",
                            "$soc%",
                            valueColor = if (soc <= 15) accents.warnAmber else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                InlineMetric("最高", "${d.speed_max ?: "—"} km/h")
            }
            // ⚠️ v0.22.0（第 13 轮充电专家 P1-5）：**速度档标注** ——
            //
            // 高速趟的能耗天然高于市区（实测近 30 天：
            // 高速（均速 ≥90）17.6 vs 市区（均速 <60）16.6 kWh/100km），
            // 不标出来的话，「我最近变费油了」会被误读成车有问题。
            //
            // 这是**解释性标签**，不是新的统计口径 ——
            // 刻意**不**为高速趟单开一套平均能耗数字，
            // 否则 App 里会多出第三个能耗数，用户对不上账。
            //
            // 只在 `distance >= 30` 时显示（与「能耗」那一行同一门槛）。
            val speed = d.avg_speed_kph
            if ((d.distance_km ?: 0.0) >= 30.0 && speed != null) {
                Text(
                    "均速 $speed km/h（${speedBucketLabel(speed)}档）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // ⚠️ v0.9.2：统一电量带 —— 所有事件画在**同一条 0~100% 标尺**上，
            // 于是「85→70」和「70→55」的位置一致，能看出电在哪儿掉的、在哪儿补的。
            if (d.start_soc != null && d.end_soc != null) {
                SocDeltaBar(d.start_soc, d.end_soc, charging = false)
            }
            // ⚠️ v0.17.0（车队运营评审 P0-1）：**同线路对比的摘要**（列表里的一行灰字）。
            //
            // ⚠️ 必须放在**卡片内部**：原来放在卡片外面（TimelineRow 的 Column 里），
            // 而那�� Row 用 `IntrinsicSize.Min` + 左侧 `fillMaxHeight()`，
            // 右边被裁掉 —— 摘要行**根本渲染不出来**。
            // 放进卡片内部就没有这个问题，而且视觉上更完整（是卡的一部分，不是浮在外面）。
            footnote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
internal fun DriveDetail(
    drive: Drive,
    api: Api?,
    settings: AppSettings,
    /**
     * ⚠️ v0.17.0（车队运营评审 P0-1）：**同线路的历史行程**（含本趟）。
     *
     * 用来回答「这趟比上趟好吗」—— 数据其实早就有了
     * （`buildRouteTemplates` 按起止分组），只是取出来之后丢掉。
     * 空列表 → 不显示对比（没有可比的历史）。
     */
    routeHistory: List<Drive> = emptyList(),
    /**
     * ⚠️ **v0.28.0：这一趟的驾驶平顺分**（来自 `/cars/{id}/driving_profile`）。
     *
     * 车况页的「驾驶画像」给的是**总体**；这里给**这一趟** ——
     * 「这趟开得怎么样」才是看详情时想问的。
     * null = 没算过（离线 / 这一趟太短被服务端过滤掉）。
     */
    driveScore: Double? = null,
    onClose: () -> Unit,
) {
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
    //
    // ⚠️ v0.14.0（口径审计 P0-2）：把**真实里程**传进去，
    // 让分箱中心被重标定到 [0, distance_km] ——
    // 否则横轴的分母是分箱中心（2.28 km → 2.5），
    // 与刻度标签的真实里程（2.28）对不上，首点不在 0、末点越过终点。
    val powerProfile = remember(track, drive.distance_km) {
        track.toPowerProfile(realLengthKm = drive.distance_km)
    }

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
                // ⚠️ v0.13.0（iOS 交互评审 P1-4）：**「在地图里打开」出口**。
                //
                // 那张「地图」其实是**一张 PNG**（高德静态图 API）—— 不能拖、不能缩、不能点。
                // 但车主看完这趟轨迹，下一步最自然的动作是「把这趟路在地图里再走一遍」，
                // 而界面上根本没有这个出口。
                // 现在给一个 geo: 按钮（系统标准的地理 URI，高德/百度/Google 地图都能接）。
                val mapCtx = LocalContext.current
                val endPt = track.lastOrNull()
                if (endPt != null) {
                    val lat = endPt.map_lat ?: endPt.lat
                    val lng = endPt.map_lng ?: endPt.lng
                    // ⚠️ v0.17.0（iOS 动效与材质专项评审 P0-3 / 3D 规格表）：
                    // **按下瞬间的 3D 倾斜**。
                    //
                    // 这是全 App 最适合给几何 3D 的位置 —— 浮在地图上的一个独立小物件，
                    // 按下时轻微前倾（像被手指按下去），抬手回 0，不留残留畸变。
                    //
                    // `cameraDistance` 由 [Tilt3DState] 强制成 **3 × 自身宽（px）** ——
                    // 见 Motion.kt 里的推导：框架默认的 8px 会让近边跑到相机背后，
                    // 而流传很广的「12~16dp」会在 330dp 宽的卡上让近边**放大 82%**。
                    val tilt = remember { Tilt3DState() }
                    var mapPressed by remember { mutableStateOf(false) }
                    Surface(
                        // ⚠️ v0.14.0（苹果 HIG P0-4）：圆角改胶囊、高度提到 **44dp**。
                        // 原来整个可点区域只有约 25dp（图标 16dp + 上下 6dp 内边距），
                        // 远低于 44pt 触控目标下限 —— 开车前单手点根本点不准。
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                            .tilt3DState(
                                state = tilt,
                                rotationX = if (mapPressed) -2f else 0f,
                            ),
                    ) {
                        Row(
                            Modifier
                                .height(44.dp)
                                .pressable(
                                    onClick = {
                                        val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng")
                                        runCatching {
                                            mapCtx.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                        }
                                    },
                                    onPressed = { mapPressed = it },
                                )
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Place,
                                contentDescription = "在地图里打开",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "在地图里打开",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            Text(
                // ⚠️ v0.20.0（车主 P2-3）：**删掉「轨迹采样点 N 个」** ——
                // 「采样点」是开发者语言，车主看不懂它有什么用；
                // 而它占着一行、还带着一个内部实现细节（采样密度）。
                // 真要表达「这条轨迹记录得细不细」，说的是「记录完整」而不是「点」。
                "轨迹 ${track.size} 个点 · 记录完整",
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

        // ⚠️ v0.13.0（iOS 交互评审 P1-3）：**顶部渐隐蒙版**。
        //
        // 原来 LazyColumn 的顶边是**硬裁切线**（clipToBounds），内容滑到那里会被
        // 沿字形中间切断 —— 实测那行「电量（%）· 同一横轴 = 同一公里…」只露出上半截，
        // 读起来像渲染 bug。而它上方 260dp 的地图是不动的。
        //
        // 现在加一层 14dp 的渐隐：内容是「渐隐进入」，不是「被切」。
        Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ⚠️ v0.17.0（苹果 HIG P0-1）：**主角条** —— 一屏一个主角数字。
            //
            // 原来这一屏全是等权的卡片与图表，扫一眼没有落点。
            // 现在地图下面就是这一趟的三个关键数：**多远、多久、多费**。
            item(key = "hero") {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BigStat(
                            drive.distance_km?.fmt(1) ?: "—",
                            "公里",
                            hero = true,
                        )
                        Spacer(Modifier.width(20.dp))
                        Column {
                            Text(
                                buildString {
                                    append(minutesFmt(drive.duration_min))
                                    drive.wh_per_km?.let { append(" · ${it.fmtKwh100()} kWh/100km") }
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                buildString {
                                    append("耗电 ")
                                    append(drive.energy_kwh?.fmt(1) ?: "—")
                                    append(" 度")
                                    // ⚠️ v0.20.0（车主 P0-1）：**给「到站电量」，不是出发电量**。
                                    //
                                    // 原来这里写的是 `start_soc`（出发时的电量），
                                    // 标签却是「电量」—— 于是「耗电 7.2 度 · 电量 96%」
                                    // 被读成「**到站还剩 96%**」。
                                    // 而同一屏下方写着「96% → 85%」，真到 252 公里只剩 16%。
                                    // 这是**会让人误判是否需要在服务区停留**的错误。
                                    //
                                    // 现在：优先给 end_soc 且标签写「到站」；
                                    // 没有 end_soc 才退回 start_soc，并把标签写明「出发」。
                                    val endSoc = drive.end_soc
                                    val startSoc = drive.start_soc
                                    when {
                                        endSoc != null -> append(" · 到站 $endSoc%")
                                        startSoc != null -> append(" · 出发 $startSoc%")
                                    }
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            // ── 功率剖面 ──────────────────────────────────────────
            // 长途自驾党在实测 43 条行程后选它当唯一最有价值的曲线：
            // 一条线同时给出能耗、地形、回收三件事（海拔曲线是它的子集），
            // 而且能解释 Wh/km 的异常 —— 看到某段爬坡功率跳到 48 kW，
            // 下次跑同一条高速就会主动避开。
            if (powerProfile.size >= 2) {
                item {
                    // ⚠️ v0.14.0（口径审计 P0-3）：标题里写清「1 km 分箱平均」。
                    //
                    // 原来标题只写「用电曲线（kW · 对应里程）」，
                    // 而下面「车速与功率」卡里的「峰值功率 92 kW」是**瞬时值** ——
                    // 同一屏出现两个功率数，量纲同名不同源：
                    // 曲线最高只到 9 kW，卡片写 92 kW，差 10 倍。
                    // 读者只能得出「图画错了」或「数据不对」。
                    // 现在标题标明是分箱平均，底部卡片标明是瞬时。
                    // ⚠️ v0.14.0（单位审校 P2-1 / P2-2）：
                    // 「用电曲线」→「**功率曲线**」——
                    // 「用电」在中文里指**电量**（kWh），这里画的是**功率**（kW），
                    // 同一屏两个「用电」指两个量。
                    // 「1 km 分箱平均」→「**每 1 公里平均**」——
                    // 「分箱」是数据工程黑话，用户不知道那是什么意思。
                    SectionCard("功率曲线（kW · 每 1 公里平均 · 对应里程）", Modifier.animateItem()) {
                        val peakUp = powerProfile.maxOf { it.powerKw }
                        val peakDown = powerProfile.minOf { it.powerKw }
                        PowerProfileChart(
                            samples = powerProfile,
                            xStartKm = 0.0,
                            xEndKm = drive.distance_km,
                            contentDescription = "功率随里程变化曲线，" +
                                "全程 ${powerProfile.last().km.roundToInt()} 公里，" +
                                "最高耗电 ${peakUp.roundToInt()} 千瓦，" +
                                "最深回收 ${kotlin.math.abs(peakDown).roundToInt()} 千瓦",
                        )
                        Text(
                            "正值 = 耗电（蓝）· 负值 = 刹车回收（绿）" +
                                (drive.wh_per_km?.let { " · 本趟 ${it.fmtKwh100()} kWh/100km" } ?: "") +
                                // ⚠️ v0.14.0：把「瞬时峰值」和「分箱平均」的差别**写在明面上**，
                                // 免得同屏两个功率数打架时读者以为数据坏了。
                                (drive.power_max_kw?.let {
                                    " · 瞬时峰值 $it kW，图中为每 1 公里平均"
                                } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // ⚠️ v0.9.0（长途自驾党复核）：加**电量下降条**。
                        // 他原话：「图上只有功率没有电量，我看不到还能撑到哪个服务区。
                        // 建议加一条电量曲线，或者在图上标出'到达 20% 时走了多少公里'。」
                        //
                        // 功率图答的是「为什么」，电量条答的是「还能撑多远」——
                        // 共享同一条里程横轴，两块图上下对齐，同一公里是同一列。
                        val milestones = remember(powerProfile) { powerProfile.socMilestones() }
                        // ⚠️ v0.9.2（长途自驾党 N4 + 口径审计 S2-2）：**短途不画电量条**。
                        //
                        // 4.51 km 的行程电量是 99% → 98%，画出来是一条贴在轴顶的平线，
                        // 20%/10% 的参考线毫无意义、交叉点标注永远不会出现 ——
                        // 这张图是纯噪音。规则：距离 < 30 km 或全程跌幅 < 5 个百分点 → 不渲染。
                        val socValues = powerProfile.mapNotNull { it.soc }
                        val socDrop = if (socValues.size >= 2) socValues.first() - socValues.last() else 0
                        val tooShortForSoc = (drive.distance_km ?: 0.0) < 30.0 || socDrop < 5
                        if (socValues.size >= 2 && tooShortForSoc) {
                            Text(
                                "本趟仅掉 ${socDrop} 个百分点（${drive.distance_km?.fmt(1) ?: "—"} 公里），" +
                                    "短途不适用长途规划",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (socValues.size >= 2) {
                            Spacer(Modifier.height(10.dp))
                            SocStrip(
                                samples = powerProfile,
                                milestones = milestones,
                                // ⚠️ v0.14.0：与上方功率图**共用同一组里程轴**，
                                // 否则「同一公里 = 同一列」对不上。
                                xStartKm = 0.0,
                                xEndKm = drive.distance_km,
                                contentDescription = buildString {
                                    append("电量随里程下降曲线")
                                    milestones.kmAt20?.let { append("，约第 ${it.roundToInt()} 公里降到 20%") }
                                    milestones.kmAt10?.let { append("，约第 ${it.roundToInt()} 公里降到 10%") }
                                },
                            )
                            // ⚠️ v0.9.0：图上已经标了「到达 20% @ N km」，
                            // 这里再给一行**纯文字**的结论 ——
                            // 很多长途行程压根掉不到 20%（比如 82% → 70%），
                            // 那时图上不会有标记，但「全程最低多少」依然是个有用的答案。
                            //
                            // ⚠️ v0.9.2（口径审计 S2-2）：措辞改「约」——
                            // SOC 是整数百分比，1 个百分点约等于 3.65 km，
                            // 加上 1 km 分箱的箱中心量化，真实不确定度约 ±2 km。
                            // 写成精确的「第 243 公里」是把 ±2 km 当成整数公里显示。
                            val lowestSoc = socValues.min()
                            Text(
                                buildString {
                                    append("电量 (%) · 同一横轴 = 同一公里")
                                    milestones.kmAt20?.let {
                                        append(" · 约第 ${it.roundToInt()} 公里降到 20%")
                                    } ?: lowestSoc.let {
                                        append(" · 全程最低 $it%（未到 20%）")
                                    }
                                    milestones.kmAt10?.let {
                                        append(" · 约第 ${it.roundToInt()} 公里降到 10%")
                                    }
                                    append(" · 每 1 公里取平均，精度约 ±2 公里")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                SectionCard("时间与电量", Modifier.animateItem()) {
                    KV("开始", drive.start_date.fmtFull())
                    KV("结束", drive.end_date?.fmtFull() ?: "—")
                    KV("时长", minutesFmt(drive.duration_min))
                    // ⚠️ v0.14.0（单位审校 P1-3）：**1 位小数 + 「公里」**。
                    // `distance` 来自 GPS 里程表差，真值分辨率约 10 m ——
                    // 写成「3.90 公里」是把 ±0.005 公里当成事实。
                    KV("里程", drive.distance_km?.fmt(1, " 公里") ?: "—")
                    KV("电量变化", "${drive.start_soc ?: "—"}% → ${drive.end_soc ?: "—"}%")
                    // ⚠️ v0.14.0（单位审校 P0-2）：**删掉「标定续航变化」整行**。
                    //
                    // 三个理由：
                    // ① **同源冗余** —— Δ标定续航 × 0.146 就是下面「行驶耗电」，
                    //    一个「能耗」卡里两行给的是同一个数；
                    // ② **「标定」是黑话** —— 用户不知道它指特斯拉的官方参考续航表；
                    // ③ **观感悖论** —— 「我开了 3.9 公里，续航掉了 6 公里？」，
                    //    用户会以为 App 坏了。
                    // 它的信息已经在「电量变化 82% → 70%」里以更有意义的形式给出。
                }
            }
            item {
                SectionCard("能耗", Modifier.animateItem()) {
                    // ⚠️ v0.14.0（单位审校 P1-1）：「行车用电」→「行驶耗电」，
                    // 与用量页的图表标题同名 —— 同一个量不该有两个名字。
                    // ⚠️ v0.14.0（单位审校 P1-3）：1 位小数（`energy_kwh` 的
                    // 真实分辨率是 0.146 度，2 位是假精度）。
                    KV("行驶耗电", drive.energy_kwh?.fmt(1, " 度") ?: "—")
                    KV("百公里能耗", drive.wh_per_km?.let { "${it.fmtKwh100()} kWh/100km" } ?: "—")
                    // ⚠️ v0.14.0（口径审计 P2-1）：删掉「按里程算能耗」这一行。
                    //
                    // 服务端的 `wh_per_km` **已经**用 `d.distance`（里程表差）做分母
                    // （api/queries.go），所以这一行算出来的是**同一个数**，
                    // 只是换了个说法 —— 而「（odometer 差）」里那个英文词
                    // 在中文界面里还是个雷。
                    //
                    // ⚠️ v0.14.0（单位审校 P0-1）：**短途加脚注**。
                    //
                    // 短途的能耗**天然偏高**：分子是整数公里的标定续航差（±0.5 km
                    // 的量化误差），分母只有行驶里程 —— 1.8 km 的行程能算出 213 Wh/km。
                    // 停车时开空调的耗电也摊在这 1.8 km 上。
                    // 不说清楚，提车半年的车主会以为「App 算错了 / 车有问题」。
                    val dKm = drive.distance_km ?: 0.0
                    if (dKm < 30.0) {
                        Text(
                            "短途（不足 30 公里）的能耗偏高：停车时开空调等耗电也摊在了行驶里程上，" +
                                "这个数字不能和长途比",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // ⚠️ v0.17.0（车队运营评审 P0-1）：**同线路历史对比**。
                    //
                    // 「这趟比上趟好吗」是每周复盘的第一问，
                    // 而这里的数据早就齐了（客户端持有全部 drives 字段，零新增请求）。
                    val cmp = remember(drive.id, routeHistory) {
                        if (routeHistory.size < 2) null else compareWithRouteHistory(drive, routeHistory)
                    }
                    cmp?.let { c ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            c.headline,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (c.hasVerdict) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        c.note?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                SectionCard("车速与功率", Modifier.animateItem()) {
                    KV("平均车速", drive.avg_speed_kph?.let { "$it km/h" } ?: "—")
                    KV("最高车速", drive.speed_max?.let { "$it km/h" } ?: "—")
                    // ⚠️ v0.14.0（口径审计 P0-3）：加「瞬时」二字。
                    // 这个数是 TeslaMate 记的**瞬时**最大功率，
                    // 与上方曲线的「1 km 分箱平均」量纲同名不同源，
                    // 不写清楚读者会以为曲线画错了。
                    KV("瞬时峰值功率", drive.power_max_kw?.let { "$it kW" } ?: "—")
                    drive.power_min_kw?.let { if (it < 0) KV("瞬时回收峰值", "${-it} kW") }
                    // ⚠️ v0.28.0：这一趟的驾驶平顺分。
                    // ⚠️ **必须标出它是合成指标**（权重是行业惯例值，不是从这辆车拟合的）——
                    // 否则用户会以为「84 分」是什么客观认证。
                    driveScore?.let {
                        KV("驾驶平顺分", "${it.roundToInt()} 分（App 自算）")
                    }
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
        // 顶部渐隐蒙版
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.background, Color.Transparent)
                    )
                )
        )
        }
    }
}
