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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Power
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.teslamate.app.firstSegment
import com.teslamate.app.fmtShort
import com.teslamate.app.minutesFmt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChargesScreen(
    settings: AppSettings,
    modifier: Modifier,
    /**
     * v0.8.5：false = 嵌在「记录」页里（RecordsScreen 已提供顶栏与段控件，
     * 且「近 30 天」汇总数据统一收敛到「用量」页 —— 同一份 stats 不该被抓两次）。
     */
    showHeader: Boolean = true,
) {
    val api = remember(settings) { settings.api() }
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Charge>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Charge?>(null) }
    // 该站历史统计（按地点聚合）：回答「这个桩快不快、我要等多久」。
    // 这是决定「进不进这个服务区」的唯一信息，单次充电的峰值/平均功率给不了。
    val stats = remember(list) { buildStationStats(list) }

    // ⚠️ v0.9.0：抽成可复用的 load() ——
    // 原来只有 LaunchedEffect 里的**一次性**加载，网络一断就再也不请求了，
    // 用户必须「切走再切回」才能重试（可靠性复核：列表页没有下拉刷新）。
    // 现在：初次加载 + 下拉刷新都走这一条路径。
    suspend fun load() {
        if (api == null) {
            error = if (settings.configured) "服务器地址或令牌无效" else "尚未配置服务器，请到「设置」页"
            loading = false
            return
        }
        runCatching { withContext(Dispatchers.IO) { api.charges(settings.carId, 200) } }
            .onSuccess { list = it; error = null; loading = false }
            .onFailure { error = it.message; loading = false }
    }

    LaunchedEffect(settings) { load() }

    // 列表 ⇄ 详情：iOS 式 push（新页推入，旧页 30% 视差退让 + 左缘投影）
    AnimatedContent(
        targetState = selected,
        modifier = modifier,
        transitionSpec = { pushDetailTransition(this) },
        label = "chargeDetail",
    ) { sel ->
        Box(Modifier.fillMaxSize()) {
            if (sel == null) ChargeList(
                list = list,
                stats = stats,
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
            else ChargeDetail(sel, api, stats) { selected = null }
            if (sel != null) PushEdgeShadow(Modifier.align(Alignment.CenterStart))
        }
    }
}

@Composable
private fun ChargeList(
    list: List<Charge>,
    stats: Map<String, StationStat>,
    loading: Boolean,
    error: String?,
    settings: AppSettings,
    showHeader: Boolean = true,
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onSelect: (Charge) -> Unit,
) {

    Column(Modifier.fillMaxSize()) {
        if (showHeader) {
            TopAppBar(
                title = { ScreenTitle("充电") },
            )
        }
        // ⚠️ v0.9.0：加下拉刷新。
        // 原来这个页只有一次性加载 —— 网络抖一下失败后，只能「切走再切回」才重试。
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                error != null -> Column(Modifier.fillMaxSize().padding(16.dp)) { ErrorBanner(error!!) }
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
                        if (showHeader) {
                            item(key = "summary") {
                                RecentSummaryCard(settings = settings, fallback = {
                                    Text(
                                        "共 ${list.size} 次 · 充入 ${totalKwh.fmt(1)} 度",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                })
                            }
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
                                val stat = stats[stationKey(c)]
                                ChargeItem(c, stat, Modifier.animateItem()) { onSelect(c) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 充电列表行。
 *
 * ⚠️ **v0.7.1 修正**：原来把所有字段挤进一条 `maxLines=1` 的 Text
 * （"10-12 14:30 · 47分钟 · 充入 12.3 kWh · 45%→88% · ¥18.40"），
 * 在 360dp 宽的真机上永远只能看到前半截，末尾的**费用**和**SOC 变化**最容易被吃掉 ——
 * 而这恰恰是车主最想看的两件事。
 * 现在改成三层结构：**地点（可两行）→ 时间 → 三个并排指标**，
 * 信息一条不丢，且每条都有独立视觉权重。
 *
 * ⚠️ **v0.8.2 新增**：第四行是该站**历史**表现（典型 kW + 充到 80% 耗时）——
 * 这是决定「进不进这个服务区」的唯一信息，只存在于单次充电的字段里是不够的。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChargeItem(
    c: Charge,
    stat: StationStat?,
    modifier: Modifier = Modifier,
    /** ⚠️ v0.25.0：车主填的电价 —— 有就显示估算费用。 */
    rates: com.teslamate.app.ChargeRates? = null,
    onClick: () -> Unit,
) {
    val accents = LocalAccentColors.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .cardDepth()
            // ⚠️ v0.22.0（a11y）：**整卡可点但没有可访问名称** ——
            // uiautomator dump 里充电行的 clickable 节点 content-desc 为空，
            // TalkBack 聚焦上去读不出这是哪一次充电。
            // 合并子节点语义并显式给名称（标题 + 类型 + 时间 + 充入量）。
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(c.place.firstSegment() ?: "未知地点")
                    append("，")
                    append(if (c.is_dc && c.is_tesla_sc) "特斯拉超充" else if (c.is_dc) "国标直流" else "交流慢充")
                    append("，")
                    append(c.start_date.fmtShort())
                    append("，充入 ")
                    append(c.energy_added_kwh?.fmt(1) ?: "—")
                    append(" 度")
                }
            }
            .pressable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = accents.cardSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    // ⚠️ v0.24.0：**优先显示真实 POI 名**（「金田服务区」），
                    // 而不是反向地理编码的长串
                    //（「汕昆高速公路, 北庄, 宜州区, 河池市, 广西, 中国」）。
                    // 用户要找的是「哪个服务区」，不是一段地址。
                    //
                    // 原来取的是 `place.firstSegment()`（地址第一段）——
                    // 那是个**地名**，而 OSM 的 POI 名才是**场所名**。
                    // ⚠️ **v0.36.0（第 21 轮一线车主复审 P1-2）：优先用服务端的完整标签。**
                    //
                    // 原来取的是 `poi_name` → `place.firstSegment()`，
                    // 而这两者在 OSM 把**路名**写进 name 时都是路名（「G210」「沪聂线」）。
                    // 服务端的 `place_label` 会在是路名时带上地名（「G210 车河镇」）。
                    c.place_label?.takeIf { it.isNotBlank() }
                        ?: c.poi_name?.takeIf { it.isNotBlank() }
                        ?: c.place.firstSegment()
                        ?: "未知地点",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                ChargeTypeChip(c.is_dc, c.is_tesla_sc)
                // ⚠️ v0.9.2（iOS 交互评审 P1-8）：**可推入的卡片要有 disclosure indicator**。
                // 这张卡会 push 出充电详情，但外观与纯展示卡完全一致 ——
                // 唯一的线索是「按下去会缩放」，而按下之前用户根本不知道能点。
                // iOS 惯例：会 push 的列表行右侧必须有 `›`。
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                "${c.start_date.fmtShort()} · ${minutesFmt(c.duration_min)}",
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
                InlineMetric("充入", "${c.energy_added_kwh?.fmt(1) ?: "—"} 度")
                InlineMetric("电量", "${c.start_soc ?: "—"}→${c.end_soc ?: "—"}%")
                // ⚠️ v0.25.0（第 16 轮充电成本专家 P1-6）：
                // 费用优先用服务端的 `cost`；没有就按**车主填的电价**本地估算，
                // 并用 **≈** 前缀标明「这是估算」——
                // 服务端 cost 全库 NULL（未配 tou_rates），所以这一行**永远**不显示，
                // 而「每公里成本」恰恰是车主最想要的那个数。
                val estCost = c.cost ?: rates?.let { estimateSingleChargeCost(c, it) }
                if (estCost != null) {
                    InlineMetric(
                        "费用",
                        if (c.cost != null) "¥${c.cost.fmt(2)}" else "≈${estCost.fmtYuan()}",
                        // ⚠️ v0.8.3：原来用 primary（红）。红色在本 App 只表示告警，
                        // 费用不是告警 —— 与里程数字同理，去掉这层廉价感。
                        valueColor = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            // ⚠️ v0.9.2（长途自驾党 N3）：统一电量带 ——
            // 充电行与行程行画在**同一条 0~100% 标尺**上，
            // 于是「充到 100%」与「掉到 70%」在视觉上是对应的。
            if (c.start_soc != null && c.end_soc != null) {
                SocDeltaBar(c.start_soc, c.end_soc, charging = true)
            }
            // 站点历史：⚠️ v0.22.0：**只充过 1 次也显示** ——
            // 原来门槛是 2 次，而「只充过 1 次」恰恰是**长途新服务区的常态**。
            // 出发前第一次去一个新服务区，最需要知道的就是「这个桩大概多快」，
            // 结果界面上一个字都没有。1 次时改说「实测」并标「仅 1 次」。
            if (stat != null && stat.sessions >= 1) {
                Text(
                    stationSummaryLine(stat, c.start_soc),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 快充/慢充标签：矢量图标 + 文字，状态不只靠颜色 */
@Composable
internal fun ChargeTypeChip(isDc: Boolean, isTeslaSc: Boolean = false) {
    val accents = LocalAccentColors.current
    // ⚠️ v0.7.1：原来用 `primaryContainer`（深色下是一块 **深红** #93000A）——
    // 一条普通记录的快充标签，红色权重远大于信息量，而且和「告警红」撞语义。
    // 现在直流用充电绿（与能量条、充电态统一），交流用中性灰。
    //
    // ⚠️ v0.9.2（苹果视觉评审 P0-1）：这里要用 **chargeGreenText** 而不是 chargeGreen ——
    // chargeGreen #1E9E62 压在自己的 16% 淡绿底上只有 2.85:1，
    // 文字要 4.5:1、图形要 3:1，两边都不达标。
    // ⚠️ v0.17.0（苹果 HIG P0-2 + 口径审计）：直流快充芯片改用**能量蓝**。
    //
    // 原来用 `chargeGreenText`（绿）—— 但绿色在这个 App 的语义里只表示
    //「**正在充电**」，而这里是**历史**充电记录；
    // 更要紧的是：用量页图表里「直流快充」的柱子也是能量蓝，
    // 同一个概念两个颜色（评审原话：「颜色在说假话」）。
    // 现在两处统一用能量蓝，交流慢充用中性灰。
    val tint = if (isDc) accents.energyBlue else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = tint.copy(alpha = 0.16f),
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isDc) Icons.Filled.Bolt else Icons.Filled.Power,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                // ⚠️ v0.24.0（长途充电规划专家 P1-5）：**三档**，不只两档。
                //
                // 原来只有「直流快充 / 交流慢充」——
                // 但直流桩里「特斯拉超充」和「国标直流」的功率差了好几倍
                // （实测本车：超充典型 55 kW，国标直流 45 kW，而交流只有 2.6 kW），
                // 用户规划长途时最想知道的就是「**这是不是超充**」。
                //
                // 官方 App 的核心区分就是这一条。
                when {
                    isDc && isTeslaSc -> "特斯拉超充"
                    isDc -> "国标直流"
                    else -> "交流慢充"
                },
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
        }
    }
}

@Composable
internal fun ChargeDetail(
    charge: Charge,
    api: Api?,
    stats: Map<String, StationStat>,
    onClose: () -> Unit,
) {
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
            title = { ScreenTitle("充电详情") },
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

            // ⚠️ v0.22.0（第 13 轮充电专家 P1-2）：**结论卡前置** ——
            // 打开充电详情，**第一眼**要看到的是「这次充了多少、用了多久、这个桩多快」，
            // 而不是地点、日期、场次明细 —— 那些是**证据**，不是结论。
            //
            // 官方 Tesla App 给三个数（充入电量 / 峰值功率 / 时长），
            // 但少两个**决策必需量**：
            //   ① **SOC 增益** —— 同样 20.6 度，从 68% 充到 100% 与
            //      从 15% 充到 58% 完全是两回事，官方只给 kWh 不给百分比；
            //   ② **平均功率**（时间加权）—— 峰值功率只有起步那几分钟有意义
            //      （实测 22,850 个采样点里 >100 kW 只占 4.1%），
            //      而「这个桩平均能跑多少 kW」才决定要等多久。
            item {
                SectionCard(modifier = Modifier.animateItem()) {
                    BigStat(
                        charge.energy_added_kwh?.fmt(1) ?: "—",
                        "度 · 充入电池",
                        hero = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        InlineMetric("时长", minutesFmt(charge.duration_min))
                        InlineMetric(
                            "平均功率",
                            charge.effectiveAvgPowerKw()?.let { "$it kW" } ?: "—",
                        )
                        InlineMetric(
                            "峰值功率",
                            charge.peak_power_kw?.let { "$it kW" } ?: "—",
                        )
                    }
                    // SOC 增益：官方 App 不给，但它是「充了多少」的另一半。
                    val s0 = charge.start_soc
                    val s1 = charge.end_soc
                    if (s0 != null && s1 != null) {
                        Text(
                            // ⚠️ 必须用 `${...}` —— `$` 后直接跟 `(` 不是合法的字符串模板
                            // （Kotlin 只认 `$标识符` 与 `${表达式}`），
                            // 那样写会把 `$((s1 - s0).coerceAtLeast(0))` **原样印在屏幕上**。
                            "电量 $s0% → $s1%（+${(s1 - s0).coerceAtLeast(0)}%）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    // ⚠️ v0.22.0（充电专家 P2-4）：**补回了多少公里续航** ——
                    // kWh 对多数用户无感，**公里**有感。
                    // 用车机自己的标定续航差来算（不是用 kWh 除以一个 kWh/km 常数）——
                    // 后者会因常数的取值而虚高。
                    val r0 = charge.start_range_km
                    val r1 = charge.end_range_km
                    if (r0 != null && r1 != null) {
                        val delta = r1 - r0
                        if (kotlin.math.abs(delta) >= 1.0) {
                            Text(
                                "补回约 ${kotlin.math.abs(delta).toLong()} 公里续航" +
                                    if (delta < 0) "（车机标定续航反而降了，可能与温度有关）" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (charge.is_dc) Icons.Filled.Bolt else Icons.Filled.Power,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (charge.is_dc && charge.is_tesla_sc) "特斯拉超充" else if (charge.is_dc) "国标直流" else "交流慢充",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            charge.geofence_name?.takeIf { it != charge.place }?.let {
                                Spacer(Modifier.width(8.dp))
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
                    // ⚠️ v0.9.2（口径审计 S2-1）：
                    //   1)「桩上计的账」是**伪称谓** —— `charge_energy_used` 是 TeslaMate
                    //      的**会话级网侧电量**（车端上报），**不是独立物理电表读数**。
                    //      叫「计的账」会让人以为现场有块电表在计。改成中性描述。
                    //   2) 损耗那一行要**带类型** —— AC 的差额 = 车载充电机效率（~7–10%），
                    //      DC 的差额 = 桩 + 车路径（~5–8%），两条不同的物理路径，
                    //      不带类型的话用户会拿 10% 和 5% 比。
                    // ⚠️ v0.14.0（单位审校 P1-2）：两个名字改成**量名** ——
                    // 「充进电池的电」把量词塞进了名字里；
                    // 「充电桩送出」没有量词，读起来像**事件**不是**量**。
                    KV("充入电池电量", charge.energy_added_kwh?.fmt(1, " 度") ?: "—")
                    KV("充电桩输出电量", charge.energy_used_kwh?.fmt(1, " 度") ?: "—")
                    charge.loss_percent?.let {
                        // ⚠️ v0.14.0：损耗行**带上绝对量** ——
                        // 只给百分比，用户不知道那是 0.1 度还是 3 度。
                        val lossKwh = charge.energy_used_kwh?.let { used ->
                            charge.energy_added_kwh?.let { added -> used - added }
                        }
                        KV(
                            "充电损耗",
                            buildString {
                                append("${it.fmt(1)}%")
                                lossKwh?.let { l -> append("（${l.fmt(2)} 度）") }
                                append("（${if (charge.is_dc) "直流" else "交流"}）")
                            },
                        )
                    }
                    if (charge.loss_percent != null) {
                        Text(
                            "交流约 10%、直流约 5%，属正常范围",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SectionCard("充电路况", Modifier.animateItem()) {
                    // 该站历史：峰值功率没有决策价值（国标直流桩典型 50~110 kW，
                    // 峰值只出现在起步那几分钟）。真正决定「值不值得进」的是
                    // 这个站**历史实测**的典型水平，以及你这次比中位快还是慢。
                    val stat = stats[stationKey(charge)]
                    val fromSoc = charge.start_soc
                    if (stat != null && stat.sessions >= 2) {
                        Text(
                            stat.summary(fromSoc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // ⚠️ **v0.31.0（P0-2）：把「实测」放在「折算」**前面**，并说清差多少。**
                        //
                        // 原来这块只有折算值「从 31% 充到 80% 约 43 分钟」，
                        // 而曲线实测是 36 分钟 —— 差 20%，却没有任何解释。
                        // 实测口径下折算**系统性高估**（30 场实测：中位 +33%、最大 +185%）。
                        val measuredMed = stat.measuredMedianNear(fromSoc)
                        val measuredSlow = stat.measuredSlowestNear(fromSoc)
                        if (measuredMed != null) {
                            KV(
                                "该站同起点实测中位",
                                "$measuredMed 分钟（${stat.measuredCountNear(fromSoc)} 次实测）",
                            )
                        }
                        if (measuredSlow != null) {
                            KV("该站同起点最慢一次", "$measuredSlow 分钟")
                        }
                        stat.compareTo(charge.effectiveAvgPowerKw()?.toDouble())?.let {
                            Text(
                                "本次：$it",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // ⚠️ v0.9.2（长途自驾党 N2）：**「要等多久」要按本次的起始电量算**。
                        //
                        // 原来只有一个写死的「从 ≤50% 充到 80% 约 48 分钟」——
                        // 我到站是 31%、想充到 90%，这个数对不上，界面就什么都不显示。
                        // 现在用该站的典型功率与本车容量，现场折算：
                        //   从 31% 充到 80%：约 43 分钟
                        //   从 31% 充到 90%：约 62 分钟（多花 19 分钟）
                        // 这是站在服务区最需要的两个数。
                        //
                        // ⚠️ **v0.31.0：折算值**降级为次要信息**并自曝偏差方向** ——
                        // 直流桩在 80% 附近会降功率，所以折算通常**偏大**。
                        val power = if (charge.is_dc) {
                            stat.typicalPowerDcKw ?: stat.typicalPowerKw
                        } else {
                            stat.typicalPowerKw
                        }
                        if (fromSoc != null && fromSoc < 95) {
                            val m80 = stat.minutesTo(fromSoc, 80, power)
                            val m90 = stat.minutesTo(fromSoc, 90, power)
                            if (m80 != null) {
                                val bias = measuredMed?.let { m80 - it }
                                KV(
                                    "从 $fromSoc% 充到 80%（折算）",
                                    // ⚠️ **v0.34.0（第 21 轮视觉评审 P2-5）：基准必须写清楚。**
                                    //
                                    // 原来写「（比实测多 N 分钟）」—— 但同屏还有
                                    // 「本次实测 36 分钟」和「该站同起点实测中位 37 分钟」，
                                    // **两个「实测」**。代码比的是**同站同起点中位**，
                                    // 文案却说「实测」→ 读者不知道在跟哪个比。
                                    "约 $m80 分钟" +
                                        (bias?.let { "（比同站同起点中位 $measuredMed 分钟多 $it 分钟）" } ?: ""),
                                )
                            }
                            if (m90 != null) {
                                val extra = if (m80 != null) "（多花 ${m90 - m80} 分钟）" else ""
                                KV("从 $fromSoc% 充到 90%（折算）", "约 $m90 分钟$extra")
                            }
                            if (measuredMed != null || m80 != null) {
                                Text(
                                    // ⚠️ **v0.34.0（第 21 轮视觉评审 P0-3）：脚注原来与 App 自己的实测**矛盾**。**
                                    //
                                    // 原来写「直流桩在 80% 附近会降功率，所以折算通常**偏大**」——
                                    // 而 App 自己在 `RoutePlan.kt` 的 v0.32.0 实测结论是
                                    // 「折算与实测的偏差**中位是 −9%（略偏小）**，
                                    // 区间 **−58%（严重低估）~ +80%（高估）**」。
                                    //
                                    // **两屏自相矛盾**，而且原来那句把读者引向**安全的方向**
                                    // （以为折算偏大 = 实际更快到），
                                    // 而实测的最大风险是**低估 58%**
                                    // （黄金坪那个站说「约 257 分钟」，实测 **617 分钟**）。
                                    "折算 = 用该站典型功率线性外推。本机 65 场实测：" +
                                        "折算中位比实测小 9%，但最坏一次小 58%（低估）。" +
                                        "直流桩在 80% 附近降功率，所以折算可能偏大也可能偏小 —— " +
                                        "想按最坏情况，请用「同起点最慢一次」。" +
                                        "「实测」读自充电曲线的真实时间戳。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    KV("峰值功率", charge.peak_power_kw?.let { "$it kW" } ?: "—")
                    // ⚠️ v0.21.0：用**时间加权**平均功率（不是采样均值）。
                    //
                    // 采样均值按**采样点数**算，而采样间隔不均匀
                    // （高功率段 5 秒一个点、掉功率段几十秒一个点），
                    // 所以它系统性**高估**（实测 18%~50%）。
                    // 而这个数是「从 X% 充到 80% 约 N 分钟」的唯一来源 ——
                    // 功率高估 45% 就意味着等待时间**低估 31%**。
                    KV(
                        "平均功率",
                        charge.effectiveAvgPowerKw()?.let { "$it kW" } ?: "—",
                    )
                    // 说明口径：不然「平均功率」又是黑箱。
                    if (charge.effectiveAvgPowerKw() != null) {
                        Text(
                            "平均功率按采样时长加权（不是按采样点数），这是「还要等多久」的依据。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                    // ⚠️ v0.9.0（新手车主复核）：电压/电流/相数是纯 jargon，
                    // 新手「完全不懂，纯 jargon」—— 但老手进阶后真的想看。
                    // 折进「电气细节」：新手不被干扰，老手数据一点没丢。
                    CollapsibleSection("电气细节") {
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
                    // 真实时间轴：充电过程的采样间隔极不均匀 —— 怠速时几分钟才一个点，
                    // 一冲电时几十秒一个点。等距画会把功率曲线压成一团锯齿，
                    // 看起来像"功率剧烈抖动"，其实是采样密度问题。
                    val timeWeights = curve.map { it.date.toEpochMilli().toFloat() }
                    val xLabels = listOfNotNull(
                        curve.firstOrNull()?.date?.fmtShort(),
                        curve.getOrNull(curve.size / 2)?.date?.fmtShort(),
                        curve.lastOrNull()?.date?.fmtShort(),
                    ).distinct()
                    val peakKw = powers.max()
                    item {
                        SectionCard("充电功率曲线 (kW)", Modifier.animateItem()) {
                            LineChart(
                                values = curve.map { it.charger_power_kw?.toFloat() },
                                color = LocalAccentColors.current.energyBlue,
                                yUnit = "kW",
                                xLabels = xLabels,
                                xWeights = timeWeights,
                                contentDescription = "充电功率随时间变化曲线，峰值 $peakKw 千瓦，共 ${curve.size} 个采样点",
                            )
                        }
                    }
                    val socCurve = curve.mapNotNull { it.battery_level }
                    item {
                        SectionCard("电量曲线 (%)", Modifier.animateItem()) {
                            LineChart(
                                values = curve.map { it.battery_level?.toFloat() },
                                color = MaterialTheme.colorScheme.secondary,
                                yUnit = "%",
                                xLabels = xLabels,
                                xWeights = timeWeights,
                                contentDescription = "充电过程中电量变化曲线，从 ${socCurve.minOrNull()}% 升到 ${socCurve.maxOrNull()}%",
                            )
                            // ⚠️ v0.22.0（第 13 轮充电专家 P1-2）：
                            // **实测的「充到 80% 用了多久」** ——
                            //
                            // 这是**从曲线直接读出来的**，不是模型估的：
                            // 取 SOC 首次达到 80% 的时间戳，减去**曲线起点**。
                            //
                            // 为什么重要：App 其它地方给的「充到 80% 约 N 分钟」
                            // 是**用典型功率反推**的（而且过去用的是高估 45% 的采样均值），
                            // 而这个是**这次真的花了多久**。官方 App 不给这个数。
                            //
                            // ⚠️ 起点必须用**实际起始 SOC**，不能写死「从 10%」——
                            // 曲线常常从 30% 甚至 68% 才开始（中途才接上监控），
                            // 写死 10% 会让「从 68% 到 80%」被说成「从 10% 到 80%」。
                            // （实测踩过：一次 51 分钟的充电被算成「9 分钟」。）
                            // ⚠️ **v0.31.0（第 20 轮充电系统数据专家 P0-5）：实测值由**服务端**算，客户端不再自己算。**
                            //
                            // 原来客户端从曲线自己算一遍、服务端将来再算一遍 → 两套算法必然漂移，
                            // 就会出现「列表页 36 分钟、详情页 38 分钟」。
                            // 现在口径**只有一处**（服务端 `measured_minutes_to_80`）。
                            val mServer = charge.measured_minutes_to_80
                            val fromServer = charge.measured_from_soc
                            if (mServer != null && fromServer != null) {
                                Text(
                                    "本次实测：从 $fromServer% 充到 80% 用了 $mServer 分钟（读自充电曲线）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            } else {
                                // 兜底：老服务端没有这个字段时，用客户端的曲线算法。
                                val startPt = curve.firstOrNull()
                                val t80 = curve.firstOrNull { (it.battery_level ?: -1) >= 80 }?.date
                                val startSoc = startPt?.battery_level
                                if (startPt != null && t80 != null && startSoc != null &&
                                    t80.isAfter(startPt.date) && startSoc < 80
                                ) {
                                    val mins = java.time.Duration.between(startPt.date, t80).toMinutes()
                                    if (mins in 1..600) {
                                        Text(
                                            "实测从 $startSoc% 充到 80% 用了 $mins 分钟（从曲线直接读）",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                            // 不可用时说明**为什么** —— 不能一律显示「—」。
                            charge.measured_unavailable_reason?.let { why ->
                                val whyCn = when (why) {
                                    "start_soc_ge_80" -> "本次到站已经 80% 以上，不用充到 80%"
                                    "never_reached_80" -> "本次没充到 80% 就离开了，算不出这段耗时"
                                    "too_few_points" -> "本次充电采样点太少，实测耗时不可用"
                                    "soc_regressed" -> "本次电量曲线有回落，实测耗时不可用"
                                    "too_fast" -> "本次充电不足 30 秒，实测耗时不可用"
                                    "too_long" -> "本次充电超过 10 小时，不是「一次充电」"
                                    else -> null
                                }
                                if (whyCn != null) {
                                    Text(
                                        "实测耗时不可用：$whyCn",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            // ⚠️ 充电专家建议的「**怎么读这条曲线**」——
                            // 一条没有解读的曲线，用户只会得出「桩坏了」的结论。
                            //
                            // ⚠️ Compose 的 Text **不解析 Markdown** ——
                            // 写 **高压段限功率** 会在界面上直接出现两个星号。
                            val peakIdx = powers.indices.maxByOrNull { powers[it] }
                            if (peakIdx != null && powers.size > 3) {
                                val peakKw = powers[peakIdx]
                                val tailKw = powers.takeLast(powers.size / 5).average()
                                if (peakKw > 0 && tailKw < peakKw * 0.35 && charge.is_dc) {
                                    Text(
                                        "峰值 $peakKw kW 在 " +
                                            (curve.getOrNull(peakIdx)?.date?.fmtShort() ?: "") +
                                            "，之后一路降到约 ${tailKw.toInt()} kW —— " +
                                            "这是直流桩的高压段限功率，不是桩坏了。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
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
