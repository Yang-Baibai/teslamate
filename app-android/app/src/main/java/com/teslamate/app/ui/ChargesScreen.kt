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
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    // 地址只取第一段（"龙山九路, 西区街道, …" → "龙山九路"），
                    // 完整邮编地址在车况页位置卡里以灰色小字保留。
                    c.place.firstSegment() ?: "未知地点",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                ChargeTypeChip(c.is_dc)
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
                if (c.cost != null) {
                    InlineMetric(
                        "费用",
                        "¥${c.cost.fmt(2)}",
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
            // 站点历史：至少 2 次才显示
            if (stat != null && stat.sessions >= 2) {
                Text(
                    stat.summary(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 快充/慢充标签：矢量图标 + 文字，状态不只靠颜色 */
@Composable
internal fun ChargeTypeChip(isDc: Boolean) {
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
                if (isDc) "直流快充" else "交流慢充",
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
                                if (charge.is_dc) "直流快充" else "交流慢充",
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
                    if (stat != null && stat.sessions >= 2) {
                        Text(
                            stat.summary(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        stat.compareTo(charge.avg_power_kw?.toDouble())?.let {
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
                        val power = if (charge.is_dc) {
                            stat.typicalPowerDcKw ?: stat.typicalPowerKw
                        } else {
                            stat.typicalPowerKw
                        }
                        val from = charge.start_soc
                        if (from != null && from < 95) {
                            val m80 = stat.minutesTo(from, 80, power)
                            val m90 = stat.minutesTo(from, 90, power)
                            if (m80 != null) {
                                KV("从 $from% 充到 80%", "约 $m80 分钟")
                            }
                            if (m90 != null) {
                                val extra = if (m80 != null) "（多花 ${m90 - m80} 分钟）" else ""
                                KV("从 $from% 充到 90%", "约 $m90 分钟$extra")
                            }
                            // ⚠️ v0.9.2：「最坏情况我等多久」—— 中位数是乐观的一半。
                            // P90 样本不足 5 次时不给（编数据比不给更危险）。
                            stat.p90MinutesTo80?.let {
                                Text(
                                    "该站历史 P90（10 次里最慢的那次）：充到 80% 约 $it 分钟",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
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
