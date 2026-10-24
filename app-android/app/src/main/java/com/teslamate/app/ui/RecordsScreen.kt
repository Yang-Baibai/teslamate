package com.teslamate.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.teslamate.app.AppSettings
import com.teslamate.app.Charge
import com.teslamate.app.Drive
import com.teslamate.app.firstSegment
import com.teslamate.app.fmtMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * 「记录」页 —— 充电与行程**按时间排成一条线**。
 *
 * ## 为什么不再用段控件（充电 | 行程）
 *
 * v0.8.5 把两个 Tab 合并成一页，用段控件切换。IA 复核当时认可这个方向，
 * 但**长途自驾党最反对段控件**，理由是：
 *
 * > 「一次跨零点的长途，行程落在 10 月、充电落在 11 月，
 * > 分成两页就再也看不出'这趟是接着那次充电来的'。
 * > 我要的是一条交替的时间轴：drive → charge → drive → charge。」
 *
 * 他是对的，而且理由不是「个人偏好」，是**数据本身的时间结构**：
 * 充电**永远发生在两次行程之间**。真实的车主故事天然就是交替的：
 *
 * ```
 *   10-31 23:10  开出去
 *   11-01 02:40  到了服务区
 *   11-01 02:55  充上
 *   11-01 03:40  开回来
 * ```
 *
 * 段控件把这条线**剪成两半**：行程在「行程」页、充电在「充电」页，
 * 而「这趟是接着那次充电来的」这个上下文**在界面上根本拼不回来**。
 *
 * 现在：一条**连续的时间轴**，充电与行程交替出现，左侧一根竖线串起来。
 * 跨月也**不断线**（月份标题只是分组，竖线贯穿整列）。
 */
sealed interface RecordItem {
    /** 列表 key */
    val key: String

    /** 发生时刻（用于排序与月份分组） */
    val at: Instant

    data class DriveItem(val drive: Drive) : RecordItem {
        override val key: String = "d-${drive.id}"
        override val at: Instant = drive.start_date
    }

    data class ChargeItem(val charge: Charge) : RecordItem {
        override val key: String = "c-${charge.id}"
        override val at: Instant = charge.start_date
    }
}

/**
 * 把充电与行程合并成一条**按时间倒序**的流水。
 *
 * 充电排在**同一时刻的行程之前**：两者时间戳相同时，
 * 先充电后开车更符合因果（刚充完电就出发）。
 */
fun mergeRecords(drives: List<Drive>, charges: List<Charge>): List<RecordItem> {
    val out = ArrayList<RecordItem>(drives.size + charges.size)
    drives.forEach { out += RecordItem.DriveItem(it) }
    charges.forEach { out += RecordItem.ChargeItem(it) }
    // 同刻时充电在前（先充后开）
    out.sortWith(compareByDescending<RecordItem> { it.at }.thenBy { if (it is RecordItem.ChargeItem) 0 else 1 })
    return out
}

/**
 * 记录页选中项（充电或行程，二选一）。
 *
 * ⚠️ v0.13.0：从 `private` 提到**公开** —— 选中态要提升到 App 级，
 * 这样「点当前 Tab 回到根」（iOS 惯例）才有地方可复位，
 * 而且**切 Tab 时详情页不会凭空消失**。
 */
sealed interface RecordSelection {
    data class Dr(val drive: Drive) : RecordSelection
    data class Ch(val charge: Charge) : RecordSelection
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(
    settings: AppSettings,
    modifier: Modifier,
    /**
     * 当前选中的记录（详情页）。
     *
     * ⚠️ v0.13.0（iOS 交互评审 P0-1 / P0-2）：**由 App 级持有**，不放在页面里。
     *
     * 原来 `selected` 是页面局部状态，而 Tab 切换用 `AnimatedContent(targetState = tab)` ——
     * 切走的页面在淡出结束后**被 dispose**。于是：
     *   1. 在行程详情页点「用量」→ 再点回「记录」→ **详情页凭空消失**，
     *      没有任何反向 push 动画；
     *   2. 列表滚动位置也一起丢了。
     *
     * iOS 惯例：每个 Tab 持有自己的 `UINavigationController` 栈，
     * 切 Tab 再回来，**栈和滚动位置必须原样保留**。
     */
    selection: RecordSelection? = null,
    onSelectionChange: (RecordSelection?) -> Unit = {},
) {
    val api = remember(settings) { settings.api() }
    val scope = rememberCoroutineScope()
    var drives by remember { mutableStateOf<List<Drive>>(emptyList()) }
    var charges by remember { mutableStateOf<List<Charge>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refreshing by remember { mutableStateOf(false) }
    val selected = selection

    val records = remember(drives, charges) { mergeRecords(drives, charges) }
    // 该站历史统计（充电页原来用它）
    val stationStats = remember(charges) { buildStationStats(charges) }
    // ⚠️ v0.11.0（长途自驾党 P0-1）：**线路余量表** ——
    // 出发前 5 分钟真正要看的那张表：这条线路历史上真实发生过哪些充电、
    // 每次到站时剩多少、充到多少、在第几公里。
    // 数据全部来自**已经发生的**记录（行程 + 充电），不编。
    val routePlan = remember(drives, charges, stationStats) {
        val tpl = pickRouteTemplate(buildRouteTemplates(drives))
        tpl?.let { buildRoutePlan(it, charges, stationStats) }
    }

    // ⚠️ v0.9.0：一次拉两份数据（原来两个 Tab 各拉各的）
    suspend fun load() {
        if (api == null) {
            error = if (settings.configured) "服务器地址或令牌无效" else "尚未配置服务器，请到「设置」页"
            loading = false
            return
        }
        runCatching {
            withContext(Dispatchers.IO) {
                api.drives(settings.carId, 200) to api.charges(settings.carId, 200)
            }
        }.onSuccess { (d, c) ->
            drives = d
            charges = c
            error = null
            loading = false
        }.onFailure {
            error = it.message
            loading = false
        }
    }

    LaunchedEffect(settings) { load() }

    // 列表 ⇄ 详情：iOS 式 push（新页推入，旧页 30% 视差退让 + 左缘投影）
    //
    // ⚠️ 列表与详情在**同一个** AnimatedContent 里 ——
    // 之前把 AnimatedContent 放在外面的话，详情层的透明 Box 会盖住列表并吃掉触摸事件。
    AnimatedContent(
        targetState = selected,
        modifier = modifier,
        transitionSpec = { pushDetailTransition(this) },
        label = "recordDetail",
    ) { sel ->
        Box(Modifier.fillMaxSize()) {
            when (sel) {
                null -> Column(Modifier.fillMaxSize()) {
                    TopAppBar(
                        title = { ScreenTitle("记录") },
                    )
                    // ⚠️ v0.9.0：加下拉刷新（原来只有一次性加载，网络一断就再也不请求）
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = {
                            scope.launch {
                                refreshing = true
                                load()
                                refreshing = false
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        when {
                            // ⚠️ v0.9.2（iOS 交互评审 P0-4）：错误**不再替换整列列表**。
                            // 原来 `error != null -> ErrorBanner` 把 200 条历史记录全换成了
                            // 一条横幅 —— 用户原本有数据，断网后一条都看不到，
                            // 而且横幅**没有任何出口**（车况页有「重试」，这里没有）。
                            // 现在：横幅叠在列表**上面**，列表照常显示。
                            error != null -> Column(Modifier.fillMaxSize()) {
                                ErrorBanner(
                                    error!!,
                                    modifier = Modifier.padding(16.dp),
                                    actionLabel = "重试",
                                    onAction = {
                                        scope.launch {
                                            refreshing = true
                                            load()
                                            refreshing = false
                                        }
                                    },
                                )
                                RecordTimeline(records, stationStats, routePlan, onSelect = { item ->
                                    onSelectionChange(
                                        when (item) {
                                            is RecordItem.DriveItem -> RecordSelection.Dr(item.drive)
                                            is RecordItem.ChargeItem -> RecordSelection.Ch(item.charge)
                                        }
                                    )
                                })
                            }
                            loading -> ListSkeleton(items = 7)
                            records.isEmpty() -> Column(Modifier.padding(16.dp)) {
                                SectionCard { Text("暂无充电或行程记录") }
                            }
                            else -> RecordTimeline(records, stationStats, routePlan, onSelect = { item ->
                                onSelectionChange(
                                    when (item) {
                                        is RecordItem.DriveItem -> RecordSelection.Dr(item.drive)
                                        is RecordItem.ChargeItem -> RecordSelection.Ch(item.charge)
                                    }
                                )
                            })
                        }
                    }
                }
                is RecordSelection.Dr -> DriveDetail(sel.drive, api, settings) { onSelectionChange(null) }
                is RecordSelection.Ch -> ChargeDetail(sel.charge, api, stationStats) { onSelectionChange(null) }
            }
            if (sel != null) PushEdgeShadow(Modifier.align(Alignment.CenterStart))
        }
    }
}

@Composable
private fun RecordTimeline(
    records: List<RecordItem>,
    stationStats: Map<String, StationStat>,
    routePlan: RoutePlan? = null,
    onSelect: (RecordItem) -> Unit,
) {
    TimelineList(
        records = records,
        stationStats = stationStats,
        routePlan = routePlan,
        onSelect = onSelect,
    )
}

/**
 * 时间轴列表。
 *
 * 竖线**贯穿整列**（包括月份标题），不随分组断开 ——
 * 一断就又变成「两段历史」，正是这个页面要解决的问题。
 */
@Composable
private fun TimelineList(
    records: List<RecordItem>,
    stationStats: Map<String, StationStat>,
    routePlan: RoutePlan? = null,
    onSelect: (RecordItem) -> Unit,
) {
    // 按月份分组，但保持整体的时间倒序
    val groups = remember(records) { records.groupBy { it.at.fmtMonth() } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ⚠️ v0.11.0：路线余量表放在时间轴**上方** ——
        // 它回答的是「下一次跑这条线会怎样」，是出发前 5 分钟要看的东西；
        // 时间轴回答的是「我过去干了什么」。
        //
        // ⚠️ v0.14.0（苹果 HIG P1 + 新手车主 P1-2）：**默认折叠成一行**。
        //
        // 原来整张卡展开放在首屏 —— 252 公里那张卡连图带站点行吃掉整整一屏，
        // 2.0 倍字号下更是一张卡就顶满全屏，「记录」页第一条真实行程
        // 根本看不到（车主原话：「我今天开的是龙翔大道回家，不是 252 公里的长途」）。
        //
        // 现在：默认只显示**一行摘要 + 结论句**（「广昆高速 → 城东路 · 到达最低 16% ▸」），
        // 点一下才展开。既有「出发前要用的工具」的入口，又不抢历史记录的位置。
        if (routePlan != null) {
            item(key = "route-plan") {
                var expanded by rememberSaveable { mutableStateOf(false) }
                if (expanded) {
                    RoutePlanCard(
                        routePlan,
                        Modifier.animateItem(),
                        onCollapse = { expanded = false },
                    )
                } else {
                    RoutePlanSummaryRow(
                        plan = routePlan,
                        onClick = { expanded = true },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
        groups.forEach { (month, itemsInMonth) ->
            item(key = "h-$month") {
                Text(
                    month,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
            }
            itemsIndexed(
                items = itemsInMonth,
                key = { _, it -> it.key },
            ) { index, item ->
                TimelineRow(
                    item = item,
                    stationStats = stationStats,
                    isLast = index == itemsInMonth.lastIndex,
                    // animateItem() 是 LazyItemScope 的扩展，必须在列表作用域里取
                    modifier = Modifier.animateItem(),
                    onClick = { onSelect(item) },
                )
            }
        }
    }
}

/**
 * 时间轴上的一行：左侧竖线 + 节点图标，右侧是内容卡。
 */
@Composable
private fun TimelineRow(
    item: RecordItem,
    stationStats: Map<String, StationStat>,
    isLast: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accents = LocalAccentColors.current
    val railColor = MaterialTheme.colorScheme.outlineVariant
    val icon: ImageVector
    val nodeBg: androidx.compose.ui.graphics.Color
    val nodeTint: androidx.compose.ui.graphics.Color
    when (item) {
        is RecordItem.DriveItem -> {
            icon = Icons.Filled.DirectionsCar
            nodeBg = accents.energyBlue.copy(alpha = 0.16f)
            nodeTint = accents.energyBlue
        }
        is RecordItem.ChargeItem -> {
            icon = Icons.Filled.Bolt
            nodeBg = accents.chargeGreen.copy(alpha = 0.18f)
            nodeTint = accents.chargeGreen
        }
    }

    Row(
        modifier
            .fillMaxWidth()
            // IntrinsicSize.Min：让左侧竖线的高度**跟着右边内容走**，
            // 否则竖线只有图标那么高，串不起来。
            .height(IntrinsicSize.Min)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(26.dp)
                .fillMaxHeight(),
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(nodeBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = nodeTint, modifier = Modifier.size(13.dp))
            }
            if (!isLast) {
                Box(
                    Modifier
                        .width(2.dp)
                        .weight(1f)
                        .background(railColor)
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            when (item) {
                is RecordItem.DriveItem -> DriveItem(item.drive) { onClick() }
                is RecordItem.ChargeItem -> {
                    val stat = stationStats[stationKey(item.charge)]
                    ChargeItem(item.charge, stat) { onClick() }
                }
            }
        }
    }
}
