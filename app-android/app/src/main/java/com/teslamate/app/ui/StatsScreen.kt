@file:OptIn(ExperimentalMaterial3Api::class)

package com.teslamate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teslamate.app.AppSettings
import com.teslamate.app.BatteryHealth
import com.teslamate.app.Stats
import com.teslamate.app.StatsBucket
import kotlin.math.roundToInt
import com.teslamate.app.fmt
import kotlin.math.roundToInt
import com.teslamate.app.fmtDate
import kotlin.math.roundToInt
import com.teslamate.app.fmtKwh100
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 统计：周/月/年 里程、能耗、充电、电池健康 */
@Composable
fun StatsScreen(
    settings: AppSettings,
    modifier: Modifier,
    /** ⚠️ v0.14.0：令牌失效时错误横幅的「前往设置」出口。 */
    onOpenSettings: () -> Unit = {},
    /** ⚠️ v0.16.0：车辆列表（顶栏车切换芯片，仅多车时出现）。 */
    cars: List<com.teslamate.app.Car> = emptyList(),
    /** ⚠️ v0.16.0：切换当前车辆。 */
    onCarChange: (Int) -> Unit = {},
) {
    val api = remember(settings) { settings.api() }
    val scope = rememberCoroutineScope()
    /**
     * v0.8.5：3 段 → 4 段。
     *
     * 原来只有「周/月/年」，但**周 = 含今天的近 7 天**，而用户真正想问的是
     * 「我今天开了多少」—— 服务端的 `period=week` 第 7 个桶（index 6）**就是今天**
     * （服务端按 `windowStart = 今天 − 6 天` 起算，桶 i = windowStart + i 天）。
     * 所以「今天」不需要新接口，取 `week` 响应的**最后一个桶**即可。
     *
     * 数据结构：label → (接口参数, 取几个桶)
     * 「今天」与「近 7 天」共用 `week` 一次请求 → 切这两段**零网络请求**。
     */
    val periods = remember {
        listOf(
            // ⚠️ v0.9.2：标签缩短（「近 7 天」→「7 天」）。
            // 大字号下 4 段等分槽放不下「近 12 个月」（实测 2 倍时折成两行，
            // 而且与相邻段的选中胶囊贴死）。页面副标题已经写明「里程 · 能耗 · 费用」，
            // 标签不需要再带「近」字。
            "今天" to ("week" to 1),
            "7 天" to ("week" to 7),
            "30 天" to ("month" to 30),
            // ⚠️ v0.9.2：「12 个月」在 2 倍字号下会被省略号截断（实测「12 个…」）。
            // 改成「1 年」—— 更短、同样准确，中文语境下也更好读。
            "1 年" to ("year" to 12),
        )
    }
    // ⚠️ v0.8.5（效率车主复核）：默认从「近 30 天」改「近 7 天」。
    // 30 根柱子密到看不出形状；7 根能看出工作日/周末的节奏。
    var periodIndex by remember { mutableIntStateOf(1) }
    val apiPeriod = periods[periodIndex].second.first
    val bucketCount = periods[periodIndex].second.second
    // ⚠️ v0.9.1：平均值的口径名要跟桶的粒度一致 ——
    // 年视图的桶是**月**粒度（12 个桶），其余是**日**粒度。
    // 原来一律写「日均」，在「近 12 个月」下就成了「日均 80 km」（其实是月均）。
    val avgLabel = if (apiPeriod == "year") "月均" else "日均"
    val avgUnit = if (apiPeriod == "year") "个月" else "天"
    var stats by remember { mutableStateOf<Stats?>(null) }
    var battery by remember { mutableStateOf<BatteryHealth?>(null) }
    // ⚠️ v0.22.0：电池健康**独立的错误** —— 它失败不该让整页不可用。
    var batteryError by remember { mutableStateOf<com.teslamate.app.UserFacingException?>(null) }
    // ⚠️ v0.25.0：充电成本构成（同一个时间窗口的聚合）。
    var costSummary by remember { mutableStateOf<com.teslamate.app.CostSummary?>(null) }
    // ⚠️ v0.25.0：车主填的电价（按车隔离）。
    val rates = settings.currentRates
    // ⚠️ v0.14.0：error 改成存**异常**而不是字符串 ——
    // 只有拿到 kind（AUTH / NO_DATA / …）才能决定横幅样式与按钮。
    var error by remember { mutableStateOf<com.teslamate.app.UserFacingException?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    // ⚠️ v0.9.2（iOS 交互评审 P0-3）：**数据与它所属的时间段绑定**。
    //
    // 原来 `stats` 只是一份数据，而标题用的是**当前** periodLabel ——
    // 切段时请求要跑几百毫秒，这期间标题已经变成「30 天 汇总」，
    // 数据却还是上一段的 7 个桶，而且**没有任何 loading 指示**
    // （骨架屏只在 stats == null 时出现）。
    // 结果是界面在**说谎**：标题说 30 天，数据是 7 天。
    //
    // 现在：loaded 记下「这份数据属于第几段」，渲染前先比对；
    // 对不上就显示骨架屏 —— **绝不显示旧数据配新标题**。
    var loadedPeriod by remember { mutableIntStateOf(-1) }

    // ⚠️ v0.9.0：抽成可复用的 load() —— 原来只有一次性加载，
    // 网络一断就再也不请求，用户必须「切走再切回」才能重试。
    //
    // ⚠️ **v0.22.0（自托管运维评审 P1-2）：两个请求**拆开**。**
    //
    // 原来是一次 `Pair(api.stats(...), api.battery(...))`：
    // `battery` 抛异常 → `stats` 的成功结果**一起被丢弃**，
    // `stats` 保持 null → 整页回到骨架屏 + 错误横幅。
    //
    // 也就是说：**电池健康这一个接口失败，整个用量页就不可用了** ——
    // 降级路径在「其中之一出错」时反而更差。
    //
    // 现在两个请求各自独立：谁失败谁降级，
    // 电池健康卡显示「取不到 · 重试」，其余（里程/能耗/充入量）照常。
    suspend fun load() {
        if (api == null) {
            // ⚠️ v0.14.0：未配置 / 地址无效 → kind = AUTH，横幅直接给「前往设置」。
            error = com.teslamate.app.UserFacingException(
                if (settings.configured) "服务器地址或令牌无效" else "尚未配置服务器，请到「设置」页",
                com.teslamate.app.ApiErrorKind.AUTH,
            )
            return
        }
        // ① 统计（里程/能耗/充入量）—— 主数据
        runCatching {
            withContext(Dispatchers.IO) { api.stats(settings.carId, apiPeriod) }
        }.onSuccess {
            stats = it
            loadedPeriod = periodIndex
            error = null
        }.onFailure {
            error = it as? com.teslamate.app.UserFacingException
                ?: com.teslamate.app.UserFacingException(it.message ?: "出错了")
        }
        // ② 电池健康 —— 独立的次要数据，失败只降级自己
        runCatching {
            withContext(Dispatchers.IO) { api.battery(settings.carId) }
        }.onSuccess {
            battery = it
            batteryError = null
        }.onFailure {
            batteryError = it as? com.teslamate.app.UserFacingException
                ?: com.teslamate.app.UserFacingException(it.message ?: "出错了")
        }
        // ③ 充电成本构成（v0.25.0）——
        // 只在**配了电价**时才拉（没配就没必要算）。
        if (settings.currentRates.configured) {
            runCatching {
                withContext(Dispatchers.IO) { api.costSummary(settings.carId, apiPeriod) }
            }.onSuccess {
                costSummary = it
            }.onFailure {
                costSummary = null
            }
        } else {
            costSummary = null
        }
    }

    LaunchedEffect(settings, apiPeriod) { load() }


    Column(modifier) {
        TopAppBar(
            // v0.8.5：Tab 改名「用量」，页面标题跟着改 —— 这一页装的是**量**
            //（里程/能耗/充入量），而「记录」页装的是**事件**。
            title = { ScreenTitle("用量") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            // ⚠️ v0.16.0：顶栏车切换芯片（仅多车时出现）
            actions = {
                CarChip(
                    cars = cars,
                    currentId = settings.carId,
                    onSelect = onCarChange,
                )
            },
        )
        // ⚠️ v0.9.0（新手车主复核）：加一行副标题说清这一页装什么。
        // 车主原话：「『记录』和『用量』两个 Tab，我第一次点进去不知道哪个是'历史'
        // 哪个是'统计'，试了两遍才明白」。
        // 「记录」页下面就是「充电 | 行程」分段控件，本身已经自解释；
        // 而「用量」这个词本身不直观 —— 补一行说明就够了。
        //
        // ⚠️ v0.14.0（新手车主 P0-4 + 长途自驾党 P2-1）：**不再承诺「费用」**。
        // 原来写「里程 · 能耗 · 费用」，但服务端的 `cp.cost` 全库 NULL，
        // 汇总卡里只能给出「未记录」—— 副标题承诺了却交白卷。
        // 现在只列**一定有**的三项；费用在真有数时自己出现在汇总卡里。
        Text(
            if (rates.configured) "里程 · 能耗 · 充入电量 · 成本" else "里程 · 能耗 · 充入电量",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
        )
        // ⚠️ v0.9.0：加下拉刷新（原来失败后只能切 Tab 重试）。
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
        LazyColumn(
            Modifier.fillMaxSize(),
            // ⚠️ v0.7.1：原来只有 bottom padding，SectionCard 又是 fillMaxWidth
            // → 卡片左右**贴边 0dp**，而同页分段按钮留了 16dp，加载完还会左右跳一下。
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SegmentedTabs(
                    labels = periods.map { it.first },
                    selectedIndex = periodIndex,
                    onSelect = { periodIndex = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ⚠️ v0.9.2（iOS 交互评审 P0-5）：错误**不再清空整页**。
            // 原来 `if (error != null) { ErrorBanner; return@LazyColumn }` ——
            // 一次请求失败就把汇总、三张图、电池健康**全藏起来**，
            // 用户会以为「这 30 天没有任何数据」，其实只是这次没取到。
            // 现在：错误横幅叠在上面，**数据照常显示**。
            if (error != null) {
                item {
                    // ⚠️ v0.14.0：改用公共的 [ApiErrorBanner] ——
                    // 原来这里**一律**只给「重试」，令牌失效时用户会无限次点重试，
                    // 永远走不到能修的地方。现在 AUTH 会给「前往设置」。
                    ApiErrorBanner(
                        error!!,
                        onOpenSettings = onOpenSettings,
                        onRetry = {
                            scope.launch {
                                refreshing = true
                                load()
                                refreshing = false
                            }
                        },
                    )
                }
            }

            // ⚠️ v0.9.2：数据必须属于**当前这一段**才显示，否则显示骨架屏。
            // （原来只看 stats == null，切段期间会拿旧数据配新标题。）
            val s = stats
            if (s == null || loadedPeriod != periodIndex) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SkeletonBox(Modifier.fillMaxWidth().height(140.dp))
                        SkeletonBox(Modifier.fillMaxWidth().height(200.dp))
                        SkeletonBox(Modifier.fillMaxWidth().height(200.dp))
                    }
                }
                return@LazyColumn
            }

            // ⚠️ v0.21.0：**标明分日用的时区** ——
            // 服务端按 `server_timezone` 分日桶，而时间轴上的时间戳
            // 按**手机时区**显示。对非该时区的用户，
            // 同一个事件在时间轴属于 10-06、在图表里却落到 10-07 ——
            // 「跨页跨日错位」，用户对不上账。
            //
            // 客户端没法自己猜服务端用哪个时区，所以服务端显式下发，
            // 这里如实告诉用户（与手机时区一致时就不说了 —— 说了是噪音）。
            val serverTz = s.server_timezone
            if (!serverTz.isNullOrBlank() && serverTz != java.time.ZoneId.systemDefault().id) {
                item {
                    Text(
                        "按服务端时区 $serverTz 分日",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            // ⚠️ **v0.27.0（第 17 轮通勤车主 P0-3）：「1 年」其实只覆盖 23%。**
            //
            // 本机数据只有 2026-07-30 ~ 10-24（87 天），而「1 年」这个分段
            // 会给出「8756 公里 / 1593 度 / 每公里成本」——
            // 数字看起来是**一整年**的量，实际上只有 **4 个月**有数据。
            //
            // 评审原话：「用户看到『1 年』，会以为这是年均值；
            // 而它其实是 4 个月的数据 —— 那不是年，是 1/3。」」
            //
            // 现在**自曝覆盖度**：桶里有数据的比例 < 90% 时明确说清楚，
            // 并把「每公里成本」两行限定到「这 4 个月」。
            val totalBuckets = s.buckets.size
            val filledBuckets = s.buckets.count { it.km > 0.0 || it.charge_kwh > 0.0 }
            val coverageRatio = if (totalBuckets > 0) filledBuckets.toFloat() / totalBuckets else 1f
            val coverageLow = totalBuckets > 0 && coverageRatio < 0.9f
            val bucketUnit = if (s.granularity == "month") "个月" else "天"
            if (coverageLow) {
                item {
                    Text(
                        "数据只覆盖 $filledBuckets/$totalBuckets $bucketUnit" +
                            "（${(coverageRatio * 100).roundToInt()}%）" +
                            if (apiPeriod == "year") " —— 不是完整的一年" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }

            // ⚠️ v0.8.5：服务端的 summary 是**整个窗口**的汇总，切段后不适用。
            // 现在统一用切片后的 buckets 自己汇总 —— 这样「今天」段显示的
            // 确实是今天的数，而不是「把今天塞进 30 天窗口里算的平均」。
            val buckets = s.buckets.takeLast(bucketCount)
            val sum = summarizeBuckets(buckets)
            val periodLabel = periods[periodIndex].first
            item {
                SectionCard("$periodLabel 汇总", Modifier.animateItem()) {
                    if (sum != null) {
                        // ⚠️ v0.17.0（苹果 HIG P0-1）：**一屏一个主角数字**。
                        //
                        // 原来 7 行**等权**排布 —— 扫一眼没有落点，
                        // 主角不成立（评审原话：「任意一屏截图缩略到 200px 宽，
                        // 灰度化后仍能一眼指出唯一的最深/最大文字块」）。
                        //
                        // 现在：里程升为主角（44sp Bold），其余降为次级行。
                        BigStat(
                            sum.km.fmt(1),
                            "公里 · $periodLabel",
                            hero = true,
                        )
                        Spacer(Modifier.height(10.dp))
                        // ⚠️ v0.14.0（单位审校 P1-1 / P1-3）：
                        // 「行车用电」→「行驶耗电」（与下面图表同名）——
                        // 原来同一个量在汇总卡叫「行车用电」、在图表叫「行驶耗电」，
                        // 用户会以为是两个量。
                        // 「总里程」→「行驶里程」——
                        // 「总里程」在中文里会被读成「这车一辈子跑了多少」。
                        KV("行驶耗电", "${sum.kwh_used.fmt(1)} 度")
                        // ⚠️ v0.14.0（单位审校 P2-4）：慢充为 0 时**不显示该分段**。
                        KV(
                            "充入电量",
                            buildString {
                                append("${sum.charge_kwh.fmt(1)} 度")
                                if (sum.dc_kwh > 0.05 || sum.ac_kwh > 0.05) {
                                    append("（快充 ${sum.dc_kwh.fmt(1)}")
                                    if (sum.ac_kwh > 0.05) append(" / 慢充 ${sum.ac_kwh.fmt(1)}")
                                    append("）")
                                }
                            },
                        )
                        // ⚠️ **v0.26.0（第 17 轮通勤车主 P0-1）：「充电费用」只能出现一次。**
                        //
                        // 原来这里是 `if (sum.cost > 0) … else …`，
                        // 而下面还有一段 `if (costSummary != null && rates.configured)` 的**估算**分支
                        // 也渲染了一个 `KV("充电费用", "≈ ¥…")`。
                        // 两者用的**不是同一个数**：
                        //   - `sum.cost` = 服务端 `SUM(cp.cost)`，本机 78/78 场全 NULL → 恒为 0
                        //   - 估算分支 = 用**车主自己填的电价**在客户端算
                        // 所以只要填了电价，同一张卡上就会**同时出现**
                        // 「充电费用 未记录」和「充电费用 ≈ ¥1,837.31」——
                        // **同一个指标给两个相反的答案**。
                        //
                        // 现在改成**互斥三分支**：估算 > 服务端记录 > 都没有。
                        val hasClientEstimate = costSummary != null && rates.configured && sum.km > 0.1
                        if (!hasClientEstimate) {
                            if (sum.cost > 0) {
                                KV("充电费用", "${sum.cost.fmt(2)} 元")
                            } else {
                                // ⚠️ v0.17.0：说明文字**拆到下一行** ——
                                // 原来把它塞进值里（「未记录 —— 在 TeslaMate 网页端手填单价后才有」），
                                // 值太长会换行成两行、右对齐还顶到边角，很难读。
                                KV("充电费用", "未记录")
                                Text(
                                    "费用需在 TeslaMate 网页端手填单价后才有",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                        }
                        // ⚠️ v0.9.0（新手车主复核）：补「每公里花多少钱」。
                        // 车主原话：「每公里花多少钱（充 60 度跑 400 公里 = 0.15 元/公里）
                        // ——没有，我只看到总数和每度电」。
                        //
                        // ⚠️ v0.9.2（口径审计 S1-2）：**必须自曝覆盖度**。
                        // `charging_processes.cost` 在本机全库是 NULL（未配 tou_rates），
                        // 所以这一行现在几乎永远不出现 —— 而页面副标题却写着「费用」，
                        // 属于**承诺了却没给**。
                        //
                        // ⚠️ v0.14.0（新手车主 P0-4 + 口径审计 P2-3）：
                        // 「充电费用」和「每公里成本」两行说的是**同一件事**，
                        // 给两行「没有」比给一行更让人烦；
                        // 而文案「充电费用未接入」也不准确 ——
                        // API **早就接了**（`SUM(cp.cost)`），是库里的 cost 全是 NULL，
                        // 也就是「**没填**」，不是「没接入」。
                        //
                        // ⚠️ **v0.25.0（第 16 轮充电成本专家 P0-1 / P0-2 / P0-3 / P0-4）**：
                        // 现在**不用服务端的 cost**（全库 NULL），而是用**车主自己填的电价**
                        // 在客户端算 —— 而且必须满足三条口径纪律：
                        //
                        // ① 分子用**网侧送出**（`energy_used`），不是充入电池（差 6.79%）；
                        // ② 家充与公共桩**分两个数**，不混算（混算会系统性偏低）；
                        // ③ 分母是**行驶里程** `sum.km`（不是里程表读数，误差 16.6%）。
                        //
                        // 「每公里成本」是**官方 Tesla App 给不了**的数 ——
                        // 它用「总增加公里数」（充电量 ÷ 型号效率反推）当分母，
                        // 官方 FAQ 自己承认与里程表对不上。我们有真实里程。
                        if (hasClientEstimate) {
                            val cost = estimateCost(costSummary!!, rates)
                            val perKm = costPerKm(cost, sum.km)
                            KV("充电费用", "≈ ${cost.totalYuan.fmtYuan()}")
                            perKm.allPerKm?.let {
                                // ⚠️ v0.27.0：覆盖度不足时必须限定口径 ——
                                // 「每公里成本」的分母是**这段时间的里程**，
                                // 而「1 年」实际只有 4 个月有数据 → 这个数不是年成本。
                                KV(
                                    "每公里成本",
                                    "≈ ${it.fmtYuan()} /公里" +
                                        if (coverageLow) "（按这 $filledBuckets $bucketUnit 算）" else "",
                                )
                            }
                            // ⚠️ P0-3：**直流桩边际成本**必须单独给 ——
                            // 实测本车交流慢充只占 3%，混算会给人「我享受了家充电价」的错觉，
                            // 而真实情况是这车几乎全按公共桩价买的电。
                            perKm.dcPerKm?.let {
                                KV(
                                    "直流桩边际成本",
                                    "≈ ${it.fmtYuan()} /公里" +
                                        if (coverageLow) "（按这 $filledBuckets $bucketUnit 算）" else "",
                                )
                            }
                            cost.yuanPerKwh?.let {
                                KV("实际每度电", "≈ ${it.fmtYuan()}")
                            }
                            Text(
                                buildString {
                                    append("按你填的电价估算（交流慢充 ")
                                    append(cost.acKwh.fmt(0))
                                    append(" 度 / 直流快充 ")
                                    append(cost.dcKwh.fmt(0))
                                    append(" 度）")
                                    perKm.acSharePercent?.let { append(" · 交流慢充占 $it%") }
                                    append("。分母是行驶里程 ")
                                    append(sum.km.fmt(0))
                                    append(" 公里。")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                            // ⚠️ **v0.26.0（第 17 轮新手车主 P0-1）：交流 ≠ 家充。**
                            //
                            // 原来这里写「家充 X 度」—— 听起来像 App **知道**哪场是在家充的。
                            // 它并不知道：本机 `geofences` 表 **0 行**、
                            // 78/78 场充电的 `geofence_id` 全是 NULL，
                            // 而库里的 4 场「交流」**全部**发生在
                            // 四川甘孜/阿坝、广西梧州的**酒店和路边**，没有一场在家。
                            //
                            // 所以只能诚实说「交流慢充」，并说明它是**按你填的家充电价**估的。
                            Text(
                                "App 分不出哪场是家充（车端没配充电地点）：交流慢充按你填的「家充电价」估算",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 4.dp),
                            )
                        } else if (sum.cost > 0 && sum.km > 0.1) {
                            // ⚠️ 服务端有 cost（用户配了 TeslaMate 的 tou_rates）时的老路径。
                            KV(
                                "每公里成本",
                                // ⚠️ 分子分母不同源：分子只含「被记录了费用」的充电，
                                // 分母含全部里程（含家充跑的公里）→ 系统性偏低。
                                // 所以必须写明覆盖度，不能当成一个干净的数。
                                "${(sum.cost / sum.km).fmt(2)} 元/公里（仅含已记录电费的充电）",
                            )
                        }
                        // ⚠️ v0.14.0（新手车主 P0-4）：没有电费数据时**不再补一行**
                        // 「每公里成本 / 暂无电费数据」——
                        // 两行「没有」说的是同一件事，比一行更让人烦。
                        // 「充电费用」那一行已经说明了怎么才能有数据。
                        // ⚠️ **v0.27.0：标清口径。**
                        // 这个数是**标定续航差法**（Δ标定续航 × cars.efficiency），
                        // 而车况页「驾驶画像」里的能耗是**车轮功率积分**（真实耗电）——
                        // 实测两者相差约 9%（标定法系统性高估）。
                        // 两个页面各写一个能耗数，不标口径用户会以为有一个是错的。
                        sum.wh_per_km?.let { KV("平均能耗（标定法）", "${it.fmtKwh100()} kWh/100km") }
                        KV("行程 / 充电次数", "${sum.drive_count} 次 / ${sum.charge_count} 次")
                    } else {
                        // ⚠️ v0.9.2（iOS 交互 P0-5）：原来 sum == null 时渲染一张**只有标题的空白卡**，
                        // 用户看到一个空框却不知道为什么。给一句中性说明。
                        Text(
                            "这段时间没有可用数据",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SectionCard("行驶里程（公里）", Modifier.animateItem()) {
                    StackedBarChart(
                        series = listOf(
                            ChartSeries(
                                values = buckets.map { it.km.toFloat() },
                                // ⚠️ v0.7.1：原来用 primary（特斯拉红）—— 「里程」没有红色语义，
                                // 红色在本 App 里只表示告警/品牌点缀。改用能量蓝。
                                color = LocalAccentColors.current.energyBlue,
                                label = "里程",
                            ),
                        ),
                        yUnit = "km",
                        xLabels = bucketAxisLabels(buckets.map { it.start }),
                        contentDescription = statsChartDescription("行驶里程", buckets.map { it.start to it.km }),
                    )
                    ChartHint(buckets.map { it.km to it.start }, "公里", avgLabel, avgUnit)
                }
            }

            item {
                SectionCard("充入电量（度）", Modifier.animateItem()) {
                    ChartLegend(
                        listOf(
                            // ⚠️ v0.9.2（苹果视觉评审 P1-1）：直流柱原来用 primary（品牌红）。
                            // 两个问题：
                            //   1) 红色在本 App 的语义里只该表示**告警与品牌点缀**，
                            //      用它标「直流快充」是语义稀释；
                            //   2) 同一屏里「直流快充」芯片是**绿色**（v0.7.1 改的），
                            //      图表里却是红色 —— **同一个概念两个颜色**。
                            // 现在统一成能量蓝（与「里程」图同色系但不同图），交流用中性深灰。
                            "直流快充" to LocalAccentColors.current.energyBlue,
                            "交流慢充" to MaterialTheme.colorScheme.secondary,
                        ),
                    )
                    StackedBarChart(
                        series = listOf(
                            ChartSeries(
                                values = buckets.map { it.dc_kwh.toFloat() },
                                color = LocalAccentColors.current.energyBlue,
                                label = "直流",
                            ),
                            ChartSeries(
                                values = buckets.map { it.ac_kwh.toFloat() },
                                color = MaterialTheme.colorScheme.secondary,
                                label = "交流",
                            ),
                        ),
                        yUnit = "度",
                        xLabels = bucketAxisLabels(buckets.map { it.start }),
                        contentDescription = statsChartDescription("充入电量", buckets.map { it.start to (it.dc_kwh + it.ac_kwh) }),
                    )
                    ChartHint(buckets.map { (it.dc_kwh + it.ac_kwh) to it.start }, "度", avgLabel, avgUnit)
                }
            }

            item {
                SectionCard("行驶耗电（度）", Modifier.animateItem()) {
                    StackedBarChart(
                        series = listOf(
                            ChartSeries(
                                values = buckets.map { it.kwh_used.toFloat() },
                                // ⚠️ v0.14.0（新手车主 P1-3）：换掉 `colorScheme.secondary`。
                                //
                                // 那个颜色同时是上一张图里「**交流慢充**」的深灰 ——
                                // 于是「行驶耗电」图和「交流慢充」图长得一模一样，
                                // 扫一眼我会以为下面那张也是充电数据。
                                // 「颜色在说假话」。
                                //
                                // ⚠️ v0.20.0（车主 P1-2）：**能量紫现在真的落地了** ——
                                // v0.14.0 的注释就写着「现在用能量紫」，但代码是
                                // `colorScheme.tertiary`，而 tertiary 在 v0.15.0
                                // 已被收敛成中性冷灰蓝 #4A5760 ——
                                // 与交流慢充的 secondary #3A4750 只差一档明度，
                                // **肉眼几乎同色**，那个修复其实没生效。
                                // 现在用真正独立的 `energyViolet` 色相。
                                color = LocalAccentColors.current.energyViolet,
                                label = "用电",
                            ),
                        ),
                        yUnit = "度",
                        xLabels = bucketAxisLabels(buckets.map { it.start }),
                        contentDescription = statsChartDescription("行驶耗电", buckets.map { it.start to it.kwh_used }),
                    )
                    ChartHint(buckets.map { it.kwh_used to it.start }, "度", avgLabel, avgUnit)
                    Text(
                        "行驶耗电 = 行车用掉的电，不含充电时充进去的",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val b = battery
            item {
                SectionCard("电池健康", Modifier.animateItem()) {
                    if (batteryError != null) {
                        // ⚠️ v0.22.0：电池健康取不到时，**只降级这一张卡**，
                        // 页面其余部分（里程/能耗/充入电量）照常显示。
                        Text(
                            "电池健康数据取不到 —— ${batteryError!!.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (b == null || b.current_capacity_kwh == null) {
                        Text("暂无足够数据（需要 ≥90% 的完整充电记录）", style = MaterialTheme.typography.bodySmall)
                    } else {
                        // ⚠️ v0.9.0（新手车主复核）：「铭牌容量」是术语。
                        // 车主原话：「'铭牌'是出厂标称；健康度＝还剩多少」，
                        // 建议写法：「新电池能装 66 度，现在还能装 65.4 度（97%）」。
                        KV("现在还能装", "${b.current_capacity_kwh?.fmt(1)} 度")
                        // ⚠️ v0.22.0（P1-5）：**历史最好时改用严格档** ——
                        // 严格档只取**充到 100%** 的测量点。
                        // 原来取的是全部「≥90%」的点里的最大值，
                        // 而 e_soc 90~98 的点**系统性偏高 0.56%**
                        // （从 94% 线性外推到 100% 会高估 —— 车机的续航-电量曲线顶部是平的）。
                        // 实测那个 66.7 度正是一个 e_soc=96 的点。
                        val strictMax = b.max_capacity_kwh_strict ?: b.max_capacity_kwh
                        KV("历史最好时", "${strictMax?.fmt(1)} 度")
                        b.nominal_capacity_kwh?.let { KV("新电池标称", "${it.fmt(1)} 度") }
                        // ⚠️ v0.22.0（P0-2）：**「健康度」改名为「占铭牌」** ——
                        //
                        // 原来的注解写「新车即约 95%，低于 90% 才需要关注」——
                        // 但那个 95% **全项目找不到任何来源**（ADR #11 只决策了「分母用铭牌」，
                        // 没给这个数），而且与本车实测**自相矛盾**：
                        // 本车历史最好的一次只有 86.9%（= 铭牌的 86.9%），
                        // 若「新车即约 95%」成立，新车应该测得 72.96 度 ——
                        // 比这台车**有史以来最好的一次还多 6.24 度**，而没有任何一次测量支持。
                        //
                        // 后果很实际：用户把「84.9%」读成「衰减了 15.1%」，
                        // 再按注解自己的门槛判定「长期处于告警区」——
                        // 而实测窗口内容量只掉了 1.6%。
                        //
                        // 现在说**可验证**的话：占铭牌多少、本车实测上限是多少。
                        if (b.health_percent != null) {
                            KV("占铭牌", "${b.health_percent!!.fmt(1)}%")
                        } else {
                            Text(
                                "暂无「占铭牌」（未配置铭牌容量，服务端不给分母）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // ⚠️ v0.22.0（P1-1）：**相对首测** ——
                        // 这是**唯一不受** `cars.efficiency` 影响的健康指标
                        // （分子分母同比例缩放）。
                        // 它回答的才是车主真正想知道的那句：「电池比刚开始测的时候差了多少」。
                        b.health_percent_of_baseline?.let {
                            KV("相对首测", "${it.fmt(1)}%")
                        }
                        Text(
                            buildString {
                                append("「占铭牌」= 可用容量 ÷ 铭牌毛容量，两者不是同一口径，")
                                append("它不是「衰减了多少」。")
                                if (b.nominal_capacity_kwh != null && strictMax != null) {
                                    append("本车实测上限 ")
                                    append((strictMax * 100 / b.nominal_capacity_kwh).fmt(1))
                                    append("%。")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (b.health_percent_of_baseline != null) {
                            Text(
                                "「相对首测」= 当前容量 ÷ 首测容量（取前 3 次中位数），" +
                                    "不受行驶能耗常数影响，是看衰减更准的那个数。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val series = b.series.map { it.capacity_kwh?.toFloat() }
                        if (series.count { it != null } >= 2) {
                            // ⚠️ v0.9.2（口径审计 S3-3）：**不再传基准线**。
                            // 原来把「新电池标称 76.8 度」当锚点，Y 轴被撑到 99.8，
                            // 而实际数据只有 65.2–66.7（变化 1.5 度 ≈ 画布高度的 2%）
                            // —— 画出来是一条**几乎不可见的平线**，
                            // 与「锚定后衰减趋势一眼可见」的注释完全相反。
                            //
                            // 现在：不传锚点 → 折线图在**细窄范围**下自动贴住数据
                            // （65–67 度），趋势一眼可见；
                            // 基准值由上面的「新电池标称」那一行承担。
                            LineChart(
                                values = series,
                                color = MaterialTheme.colorScheme.secondary,
                                yUnit = "度",
                                xLabels = bucketAxisLabels(b.series.map { it.date.fmtDate() }),
                                contentDescription = batteryChartDescription(b),
                            )
                            Text(
                                // ⚠️ v0.22.0（P0-3）：**说清数据覆盖范围** ——
                                // 电池曲线只覆盖车龄的一小段（实测本机 83 天 / 9,936 公里
                                // = 车龄的 10%），不说清楚的话，
                                // 车主会把那条下滑的线当成「电池的全部历史」。
                                buildString {
                                    append("可用容量（度）· 纵轴已放大到数据范围")
                                    if (b.series_span_days != null && b.odometer_span_km != null) {
                                        append(" · 仅 ")
                                        append(b.series_span_days)
                                        append(" 天 / ")
                                        append(b.odometer_span_km.roundToInt())
                                        append(" 公里")
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        }
    }
}

/**
 * 自绘分段控件（iOS UISegmentedControl 风格）。
 *
 * ⚠️ v0.7.1：原来用 M3 的 `SingleChoiceSegmentedButtonRow` —— 它自带描边、圆角和
 * 一个选中态填充块，**纯 Material 味**，与整 App 的 iOS 语言冲突。
 * 现在：外层一个低对比容器 + 内部 2dp 间隙 + 选中块纯色（深 #3A3A3C / 浅 #FFFFFF），
 * 文字颜色区分选中与否，没有描边、没有 M3 指示器。
 */
@Composable
private fun PeriodSegmented(
    periods: List<Pair<String, String>>,
    current: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isDarkTheme()
    val trackColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFE9E9EC)
    val selectedBg = if (dark) Color(0xFF3A3A3C) else Color.White
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(trackColor)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        periods.forEach { (key, label) ->
            val selected = current == key
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) selectedBg else Color.Transparent)
                    .clickable { onSelect(key) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 图表下方的摘要行。
 *
 * ⚠️ v0.7.1：原来这里打的是**首末日期**，而图表 X 轴已经打了首/中/末三个日期标签 ——
 * 同一件事说两遍。换成真正有用的**峰值 + 均值**。
 */
/**
 * 按切片后的 buckets 汇总（服务端 summary 只覆盖整窗口，切段后不适用）。
 */
private fun summarizeBuckets(buckets: List<StatsBucket>): StatsBucket? {
    if (buckets.isEmpty()) return null
    val km = buckets.sumOf { it.km }
    val kwhUsed = buckets.sumOf { it.kwh_used }
    return StatsBucket(
        start = buckets.first().start,
        km = km,
        minutes = buckets.sumOf { it.minutes },
        kwh_used = kwhUsed,
        wh_per_km = if (km > 0) kwhUsed * 1000.0 / km else null,
        drive_count = buckets.sumOf { it.drive_count },
        charge_kwh = buckets.sumOf { it.charge_kwh },
        dc_kwh = buckets.sumOf { it.dc_kwh },
        ac_kwh = buckets.sumOf { it.ac_kwh },
        cost = buckets.sumOf { it.cost },
        charge_count = buckets.sumOf { it.charge_count },
    )
}

/**
 * 图表下方的摘要行。
 *
 * ⚠️ v0.9.1 修一个「指标张冠李戴 + 一屏重复」的问题。
 *
 * 原来这个函数叫 `RangeHint`，写死讲的是**里程**（km），而且**每个图表下面都调一次** ——
 * 于是「充入电量（度）」和「行驶耗电（度）」两张图的下面也写着
 * 「峰值 205 km（10-24）· 日均 80 km」：
 *   - 205 km 是**里程**的峰值，不是电量的峰值 —— 指标张冠李戴；
 *   - 同一句话在一屏里出现了 **3 次**。
 *
 * 这正是 v0.8.5 的 IA 复核批过（「同一份数据被抓 3 次、叫 2 个名字」）、
 * 现在又长回来的问题。所以现在它**必须讲这张图自己的指标**。
 *
 * @param values 每个桶的值 + 该桶的起始日期
 * @param unit    单位（km / 度 / …）
 * @param avgLabel 平均值的口径名（「日均」/「月均」—— 年视图的桶是月粒度的）
 */
@Composable
private fun ChartHint(
    values: List<Pair<Double, String>>,
    unit: String,
    avgLabel: String = "日均",
    /** 桶的粒度单位（天 / 个月）—— 年视图的桶是月粒度的 */
    avgUnit: String = "天",
) {
    val nonZero = values.filter { it.first > 0 }
    if (nonZero.isEmpty()) return
    val peak = nonZero.maxBy { it.first }
    val peakDate = peak.second.take(10).drop(5)
    val avg = nonZero.sumOf { it.first } / nonZero.size
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            "峰值 ${peak.first.fmt(0)} $unit（$peakDate）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            // ⚠️ v0.9.2（口径审计 S4-1）：**分母只算有数据的桶**，
            // 所以「日均 80 km」其实是「有记录的 3 天」的平均，不是 7 天日均（34 km）。
            // 不写清楚就是个误导 —— 现在把天数自曝出来。
            //
            // ⚠️ v0.20.0（车主 P1-6）：**补一句「没开车不产生记录」** ——
            // 光说「有记录 3 天」而不解释，主人会以为自己天天通勤、
            // App 漏了 4 天的数据（车主原话：「我怀疑 App 漏了 4 天的数据」）。
            // 补了这半句，「3 天」就从「缺了 4 天」变成「本来就这么填」。
            "$avgLabel ${avg.fmt(0)} $unit（有记录 ${nonZero.size} $avgUnit，没开车不产生记录）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
