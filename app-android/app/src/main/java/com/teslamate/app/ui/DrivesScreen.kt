@file:OptIn(ExperimentalMaterial3Api::class)

package com.teslamate.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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
internal fun DriveItem(d: Drive, modifier: Modifier = Modifier, onClick: () -> Unit) {
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
                            "${d.distance_km?.fmt(1) ?: "—"} km",
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
                        "${d.distance_km?.fmt(1) ?: "—"} km",
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
                InlineMetric("能耗", "${d.wh_per_km?.fmt(0) ?: "—"} Wh/km")
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
            // ⚠️ v0.9.2：统一电量带 —— 所有事件画在**同一条 0~100% 标尺**上，
            // 于是「85→70」和「70→55」的位置一致，能看出电在哪儿掉的、在哪儿补的。
            if (d.start_soc != null && d.end_soc != null) {
                SocDeltaBar(d.start_soc, d.end_soc, charging = false)
            }
        }
    }
}

@Composable
internal fun DriveDetail(drive: Drive, api: Api?, settings: AppSettings, onClose: () -> Unit) {
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
                "轨迹采样点 ${track.size} 个",
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── 功率剖面 ──────────────────────────────────────────
            // 长途自驾党在实测 43 条行程后选它当唯一最有价值的曲线：
            // 一条线同时给出能耗、地形、回收三件事（海拔曲线是它的子集），
            // 而且能解释 Wh/km 的异常 —— 看到某段爬坡功率跳到 48 kW，
            // 下次跑同一条高速就会主动避开。
            if (powerProfile.size >= 2) {
                item {
                    SectionCard("用电曲线（kW · 对应里程）", Modifier.animateItem()) {
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
                                (drive.wh_per_km?.let { " · 本程 ${it.fmt(0)} Wh/km" } ?: ""),
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
                                "本趟仅掉 ${socDrop} 个百分点（${drive.distance_km?.fmt(1) ?: "—"} km），" +
                                    "短途不适用长途规划",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (socValues.size >= 2) {
                            Spacer(Modifier.height(10.dp))
                            SocStrip(
                                samples = powerProfile,
                                milestones = milestones,
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
                                    append(" · 1 km 分箱，精度约 ±2 km")
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
                    KV("里程", drive.distance_km?.fmt(2, " km") ?: "—")
                    KV("电量变化", "${drive.start_soc ?: "—"}% → ${drive.end_soc ?: "—"}%")
                    KV("标定续航变化", rangeDelta(drive.start_rated_range_km, drive.end_rated_range_km))
                    // ⚠️ v0.9.2（口径审计 S4-2）：删掉「预估续航变化」这一行。
                    // 它取的是 ideal_range_km，而 ideal 与 rated 在 55,669 行里
                    // **逐行完全相同**（差异数 = 0）—— 两行显示的是同一个数，
                    // 一个叫「标定」一个叫「预估」，看起来像两种不同的续航。
                    // 车况页早就改叫「现在能开 / 官方标称」了，这里是残留。
                }
            }
            item {
                SectionCard("能耗", Modifier.animateItem()) {
                    KV("行车用电", drive.energy_kwh?.fmt(2, " 度") ?: "—")
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
