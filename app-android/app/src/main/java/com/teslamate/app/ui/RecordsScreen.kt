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
import androidx.compose.material3.IconButton
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

    /**
     * ⚠️ v0.22.0（第 13 轮充电专家 P1-1）：**充电记录列表页**。
     *
     * 之前 App 里**看不到任何充电记录的列表** ——
     * 充电只作为时间轴里的一个节点出现，
     * 而「我一共充过多少次、都在哪充的、这个桩快不快」无处可答。
     *
     * 做成 `RecordSelection` 的一个变体（而不是在记录页里再嵌一层
     * `AnimatedContent`）是刻意的：这样全 App **只有一层**转场动画，
     * 从架构上绕开了「嵌套 AnimatedContent」那个坑。
     *
     * 数据不用重新拉 —— 记录页本来就已经拉了 `api.charges(carId, 300)`。
     */
    data object ChList : RecordSelection
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
    /** ⚠️ v0.14.0：令牌失效时错误横幅的「前往设置」出口。 */
    onOpenSettings: () -> Unit = {},
    /** ⚠️ v0.16.0：车辆列表（顶栏车切换芯片，仅多车时出现）。 */
    cars: List<com.teslamate.app.Car> = emptyList(),
    /** ⚠️ v0.16.0：切换当前车辆。 */
    onCarChange: (Int) -> Unit = {},
) {
    val api = remember(settings) { settings.api() }
    val scope = rememberCoroutineScope()
    var drives by remember { mutableStateOf<List<Drive>>(emptyList()) }
    var charges by remember { mutableStateOf<List<Charge>>(emptyList()) }
    // ⚠️ v0.14.0：error 改成存**异常**而不是字符串 ——
    // 只有拿到 kind（AUTH / NO_DATA / …）才能决定横幅样式与按钮。
    var error by remember { mutableStateOf<com.teslamate.app.UserFacingException?>(null) }
    // ⚠️ v0.22.0：充电记录的**独立错误** —— 它失败不该让整个记录页不可用。
    var chargeError by remember { mutableStateOf<com.teslamate.app.UserFacingException?>(null) }
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
    // ⚠️ v0.18.0（长途自驾党 P0-1）：**线路可选择**。
    //
    // 原来只取 `templates.firstOrNull()` —— 卡片永远显示「上次开的长途」，
    // 不是「我这次要开的那条」。出发前 5 分钟，App 替你猜线路是没用的。
    val routeTemplates = remember(drives) { buildRouteTemplates(drives) }
    var selectedRouteKey by rememberSaveable { mutableStateOf<String?>(null) }
    val routePlan = remember(drives, charges, stationStats, selectedRouteKey) {
        val tpl = pickRouteTemplate(routeTemplates, selectedRouteKey)
        tpl?.let { buildRoutePlan(it, charges, stationStats) }
    }
    // ⚠️ v0.17.0（车队运营评审 P0-1）：**按线路分组的历史** ——
    // 「这趟比上趟好吗」的分组信息本来就该有（`buildRouteTemplates` 已经在按起止分组），
    // 这里用**同一份分组**做对比，避免两处口径不一致。
    val routeHistoryMap = remember(drives) { buildRouteHistory(drives) }

    // ⚠️ v0.9.0：一次拉两份数据（原来两个 Tab 各拉各的）
    //
    // ⚠️ **v0.22.0（自托管运维评审 P1-2）：两个请求**拆开**。**
    //
    // 原来是一次 `api.drives(...) to api.charges(...)`：
    // 任一失败 → 两份数据**一起被丢弃** → 整页回到骨架屏。
    // 也就是说 `/charges` 失败 = 整个记录页不可用。
    //
    // 现在各自独立：行程失败 → 只显示行程相关的错误；
    // 充电失败 → 时间轴照常显示（充电节点只是少了几条），
    // 线路余量表与充电列表页会显示「充电记录取不到」。
    suspend fun load() {
        if (api == null) {
            // ⚠️ v0.14.0：未配置 / 地址无效 → kind = AUTH，
            // 横幅直接给「前往设置」（而不是让用户干等重试）。
            error = com.teslamate.app.UserFacingException(
                if (settings.configured) "服务器地址或令牌无效" else "尚未配置服务器，请到「设置」页",
                com.teslamate.app.ApiErrorKind.AUTH,
            )
            loading = false
            return
        }
        // ① 行程（时间轴的主干）
        runCatching {
            withContext(Dispatchers.IO) { api.drives(settings.carId, 500) }
        }.onSuccess {
            drives = it
            error = null
            loading = false
        }.onFailure {
            error = it as? com.teslamate.app.UserFacingException
                ?: com.teslamate.app.UserFacingException(it.message ?: "出错了")
            loading = false
        }
        // ② 充电（时间轴的次要成分 + 线路余量表的数据源）
        runCatching {
            withContext(Dispatchers.IO) { api.charges(settings.carId, 300) }
        }.onSuccess {
            charges = it
            chargeError = null
        }.onFailure {
            chargeError = it as? com.teslamate.app.UserFacingException
                ?: com.teslamate.app.UserFacingException(it.message ?: "出错了")
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
                        // ⚠️ v0.16.0：顶栏车切换芯片（仅多车时出现）。
                        // 原来这里只有「记录」两个字 —— 看不出数据属于哪台车。
                        actions = {
                            CarChip(
                                cars = cars,
                                currentId = settings.carId,
                                onSelect = onCarChange,
                            )
                            // ⚠️ v0.22.0：**充电记录入口**（闪电图标）。
                            //
                            // 放在顶栏而不是时间轴里，是因为：
                            // ① 时间轴是**混合**的（充电与行程交替），
                            //    在里面塞一个「全部充电记录」入口会让人以为充电是另一类记录；
                            // ② 顶栏是 iOS 里「进入另一个维度」的惯例位置，
                            //    不占滚动空间，也不会把时间轴的第一屏挤掉。
                            //
                            // 图标：闪电 = 充电（与记录页时间轴里的充电节点同图标）。
                            IconButton(
                                onClick = { onSelectionChange(RecordSelection.ChList) },
                            ) {
                                Icon(
                                    Icons.Filled.Bolt,
                                    contentDescription = "充电记录",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
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
                                // ⚠️ v0.14.0：改用公共的 [ApiErrorBanner] ——
                                // 原来这里**一律**只给「重试」，令牌失效时用户会无限次点重试，
                                // 永远走不到能修的地方。现在 AUTH 会给「前往设置」。
                                ApiErrorBanner(
                                    error!!,
                                    modifier = Modifier.padding(16.dp),
                                    onOpenSettings = onOpenSettings,
                                    onRetry = {
                                        scope.launch {
                                            refreshing = true
                                            load()
                                            refreshing = false
                                        }
                                    },
                                )
                                RecordTimeline(
                    records, stationStats, routePlan, routeHistoryMap,
                    routeTemplates = routeTemplates,
                    selectedRouteKey = selectedRouteKey,
                    onSelectRouteKey = { selectedRouteKey = it },
                    onSelect = { item ->
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
                            else -> RecordTimeline(
                                records, stationStats, routePlan, routeHistoryMap,
                                routeTemplates = routeTemplates,
                                selectedRouteKey = selectedRouteKey,
                                onSelectRouteKey = { selectedRouteKey = it },
                                onSelect = { item ->
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
                is RecordSelection.Dr -> DriveDetail(
                    sel.drive,
                    api,
                    settings,
                    // ⚠️ v0.17.0：同线路的历史行程（对比用）
                    routeHistory = routeKeyOf(sel.drive)?.let { routeHistoryMap[it] }.orEmpty(),
                ) { onSelectionChange(null) }
                is RecordSelection.Ch -> ChargeDetail(sel.charge, api, stationStats) { onSelectionChange(null) }
                // ⚠️ v0.22.0：**充电记录列表页** ——
                // 数据直接用记录页已经拉好的 `charges`（不重复请求），
                // 站点统计也用同一份 `stationStats` —— 同一份数据、同一套口径。
                is RecordSelection.ChList -> ChargeListPage(
                    charges = charges,
                    stats = stationStats,
                    onSelect = { onSelectionChange(RecordSelection.Ch(it)) },
                    onBack = { onSelectionChange(null) },
                )
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
    routeHistory: Map<RouteKey, List<Drive>> = emptyMap(),
    routeTemplates: List<RouteTemplate> = emptyList(),
    selectedRouteKey: String? = null,
    onSelectRouteKey: (String) -> Unit = {},
    onSelect: (RecordItem) -> Unit,
) {
    TimelineList(
        records = records,
        stationStats = stationStats,
        routePlan = routePlan,
        routeHistory = routeHistory,
        routeTemplates = routeTemplates,
        selectedRouteKey = selectedRouteKey,
        onSelectRouteKey = onSelectRouteKey,
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
    routeHistory: Map<RouteKey, List<Drive>> = emptyMap(),
    routeTemplates: List<RouteTemplate> = emptyList(),
    selectedRouteKey: String? = null,
    onSelectRouteKey: (String) -> Unit = {},
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
                        templates = routeTemplates,
                        selectedKey = selectedRouteKey,
                        onSelectKey = onSelectRouteKey,
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
                // ⚠️ v0.17.0：同线路对比的**摘要**（列表行只给一行灰字）。
                val cmp = (item as? RecordItem.DriveItem)?.let { di ->
                    routeKeyOf(di.drive)
                        ?.let { k -> routeHistory[k] }
                        ?.takeIf { it.size >= 2 }
                        ?.let { compareWithRouteHistory(di.drive, it) }
                }
                TimelineRow(
                    item = item,
                    stationStats = stationStats,
                    isLast = index == itemsInMonth.lastIndex,
                    comparison = cmp,
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
    /**
     * ⚠️ v0.17.0（车队运营评审 P0-1）：**同线路对比的摘要**。
     *
     * 次位展示：详情页给完整结论，这里只给一行灰字
     * 「同线路第 3 次 · 比常跑水平省 8%」——
     * 列表是**扫读位**，一行就够；完整口径留给详情页。
     */
    comparison: RouteComparison? = null,
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

    // ⚠️ v0.17.0（渲染实测）：**竖线改成画在内容后面**，不再用 `IntrinsicSize` 定行高。
    //
    // 原来整行是 `Row(Modifier.height(IntrinsicSize.Min))`：
    //   · `IntrinsicSize.Min` 取的是**最小**内在高度 —— 带 `maxLines = 1` 的
    //     `Text` 最小内在高度只有一行；
    //   · 而卡片内部 `Arrangement.spacedBy(8.dp)` 的间距**根本不计入内在高度**。
    // 两个因素叠加，行高永远比卡片的真实高度矮一截 —— **卡片底部被裁掉**：
    //   · 每张卡的底部内边距被吃掉（电量带紧贴卡片边缘）；
    //   · 加在卡片末尾的摘要行（对比结论）**完全渲染不出来**。
    // 换成 `IntrinsicSize.Max` 也不够 —— 间距照样不算。
    //
    // 现在：外层 `Box` 的高度 = 卡片的高度（自然测量），
    // 竖线用 `matchParentSize()` 画在**后面**，高度自然等于内容高度 ——
    // 既不用猜行高，也不会裁内容。
    Box(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 26.dp)) {
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(nodeBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = nodeTint, modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                when (item) {
                    is RecordItem.DriveItem -> DriveItem(
                        item.drive,
                        // ⚠️ v0.17.0：摘要行放在**卡片内部**（不在外面）。
                        footnote = comparison?.shortLabel(),
                    ) { onClick() }
                    is RecordItem.ChargeItem -> {
                        val stat = stationStats[stationKey(item.charge)]
                        ChargeItem(item.charge, stat) { onClick() }
                    }
                }
            }
        }
        // 竖线：从节点圆圈下方一直连到下一条 —— 高度 = 卡片高度（matchParentSize）
        //
        // ⚠️ `matchParentSize` 必须**套一层 Box**：它把元素尺寸强制成父尺寸，
        // 如果 `background` 画在同一个元素上，背景会铺满整块父区域（把卡片盖成灰块）。
        // 所以外层只负责「占满父尺寸」，线画在内层。
        if (!isLast) {
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier
                        .padding(start = 12.dp, top = 26.dp, end = 12.dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(railColor)
                )
            }
        }
    }
}
