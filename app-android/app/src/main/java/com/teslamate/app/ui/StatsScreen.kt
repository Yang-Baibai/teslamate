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
            //
            // ⚠️ **v0.29.0（复审 P0-3）：第 1 段是「今天」，第 3 段是「本月」。**
            //
            // 上一版把「今天」换成了「本月」，理由是「5 段在 200% 字号下会截断」——
            // 但那个论证针对的是 **「30 天」**（4 字标签，fs=2.0 需 70.5dp，
            // 而 5 段时每槽只有 61.8dp），**跟「今天」（2 个汉字）无关**。
            //
            // 而且「今天的数据没丢，在 7 天段的第一根柱子」这句话**是错的** ——
            // 实测 `period=week` 返回 10-19…10-25，**今天是最后一根（最右）**。
            //
            // 现在按复审给的规格：**今天 / 7 天 / 本月 / 1 年**（4 段，最长 4 字标签，
            // 200% 字号下 79.75dp 槽宽放得下）。
            // 「今天」**零新接口**：`buckets.takeLast(1)` 就是今天（v0.8.5 的注释就是这么写的）。
            // 删掉的是滚动的「30 天」—— 它的语义和「本月」打架（实测多算 25.9%）。
            // ⚠️ **v0.30.0（第 19 轮财务评审 P0-1）：费用端点的窗口必须与里程窗口一致。**
            //
            // 原来「今天」段的两条数据来自**不同的窗口**：
            //   费用 → `cost_summary?period=week`（**7 天**）
            //   里程 → `stats?period=week` 的**最后一个桶**（**今天**）
            // 分子 7 天、分母 1 天 → **每公里成本高估 4.7 倍**
            // （实测 2026-10-24：App 会显示 ¥1.18/公里，正确值 ¥0.25/公里）。
            //
            // 现在服务端给 `cost_summary` 加了 `period=today`，
            // 「今天」段用**今天**的费用窗口 —— 两个分子分母同源。
            //
            // 数据结构：label → (接口参数, 取几个桶, 费用端点的 period)
            "今天" to Triple("week", 1, "today"),
            "7 天" to Triple("week", 7, "week"),
            // 「本月」= 自然月（当月 1 号 → 今天），服务端按 TZ_NAME 分日桶。
            // 桶数给 31 是为了「取最后 N 个」能取到全部（服务端只返回到今天）。
            "本月" to Triple("calendar_month", 31, "calendar_month"),
            // ⚠️ v0.9.2：「12 个月」在 2 倍字号下会被省略号截断（实测「12 个…」）。
            // 改成「1 年」—— 更短、同样准确，中文语境下也更好读。
            "1 年" to Triple("year", 12, "year"),
        )
    }
    // ⚠️ v0.8.5（效率车主复核）：默认从「近 30 天」改「近 7 天」。
    // 30 根柱子密到看不出形状；7 根能看出工作日/周末的节奏。
    var periodIndex by remember { mutableIntStateOf(0) }
    val apiPeriod = periods[periodIndex].second.first
    val bucketCount = periods[periodIndex].second.second
    // ⚠️ v0.30.0：费用窗口（与里程窗口**同源**，见 periods 的注释）
    val costPeriod = periods[periodIndex].second.third
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
                withContext(Dispatchers.IO) { api.costSummary(settings.carId, costPeriod) }
            }.onSuccess {
                costSummary = it
            }.onFailure {
                costSummary = null
            }
        } else {
            costSummary = null
        }
    }

    // ⚠️ v0.30.0：key 里必须带 **costPeriod** ——
// 「今天」与「7 天」的 apiPeriod **都是 "week"**（统计桶不用重拉），
// 但费用窗口不同（today vs week），不加这个 key 切段时费用不会重取。
LaunchedEffect(settings, apiPeriod, costPeriod) { load() }

    // ⚠️ **v0.28.0（第 17 轮官方 App 对标评审 A-2）：能耗-车速曲线。**
    //
    // ⚠️ **不进 load()** —— 它跟时间窗口**无关**（是全车曲线），
    // 而 load() 每次切「7 天 / 30 天 / 1 年」都会跑一遍。
    // 服务端这一算要扫 162 万个采样点，**每次切段都重算是不可接受的**。
    // 所以单独一个 LaunchedEffect，**每辆车只算一次**。
    var energyProfile by remember { mutableStateOf<com.teslamate.app.EnergyProfile?>(null) }
    var energyProfileLoading by remember { mutableStateOf(false) }
    var energyProfileLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(api, settings.carId) {
        if (api == null || energyProfileLoaded) return@LaunchedEffect
        energyProfileLoading = true
        runCatching {
            withContext(Dispatchers.IO) { api.energyProfile(settings.carId) }
        }.onSuccess {
            energyProfile = it
        }.onFailure {
            energyProfile = null
        }
        energyProfileLoading = false
        energyProfileLoaded = true
    }


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
            // ⚠️ **v0.29.0**：现在 App 已经**统一以服务端时区**显示
            // （`Fmt.ServerZone`），所以这句话要改成说明「全 App 的时间口径」。
            val serverTz = s.server_timezone
            if (!serverTz.isNullOrBlank() && serverTz != java.time.ZoneId.systemDefault().id) {
                item {
                        // ⚠️ **v0.65.0（第 32 轮「减啰嗦」）**：时区口径折进 ⓘ。
                        //
                        // 「全 App 的时间与分日都按服务端时区」是**口径**⇒ 折。
                        InfoNote(
                            text = "全 App 的时间与分日都按服务端时区 $serverTz",
                            a11yLabel = "时区口径",
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
            // ⚠️ **v0.38.0（第 23 轮数据自洽性审计 P2-3）：覆盖度必须按**切片后**的桶算。**
            //
            // 原来 `totalBuckets` / `filledBuckets` 用的是**整个响应**的 `s.buckets`
            // （week 响应 = 7 个桶），而卡片内容是 `s.buckets.takeLast(bucketCount)`
            // （今天段 = **1** 个桶）⇒ 页面顶部写「数据只覆盖 3/7 天（43%）」，
            // 而下面那张卡标题是「今天 汇总」—— **两个数在讲两件事**。
            //
            // 而且「（按这 3 天 算）」那个后缀挂在「每公里成本」上，
            // 但那些数的分母是 `sum.km` = **今天**的里程 ⇒ 后缀也是错的。
            val buckets = s.buckets.takeLast(bucketCount)
            val totalBuckets = buckets.size
            val filledBuckets = buckets.count { it.km > 0.0 || it.charge_kwh > 0.0 }
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
            // ⚠️ v0.38.0：`buckets` 的切片已上移到覆盖度计算之前（见上面的注释）。
            val sum = summarizeBuckets(buckets)
            val periodLabel = periods[periodIndex].first
            item {
                SectionCard(
                    // ⚠️ **v0.29.0（HIG 评审 P1-10）：「本月」是**残缺周期**，要标出来。**
                    // 「本月」= 当月 1 日至今（25 天），而相邻的「30 天」是完整窗口（30 天）——
                    // 两个数**不可比**（实测 4180 vs 5262.6 公里，差 25.9%）。
                    // 不标的话用户会以为它们是同一口径。
                    (
                        if (apiPeriod == "calendar_month") "$periodLabel 汇总（1 日至今）"
                        else "$periodLabel 汇总"
                        ),
                    Modifier.animateItem(),
                ) {
                    if (sum != null) {
                        // ⚠️ **v0.48.0（第 28 轮通勤车主评审 P1-1）：零行驶日给**专属空态**。**
                        //
                        // 真实数据：87 天里 **42 天零行驶（48.3%）** ——
                        // 「今天」段**有一半概率**是空的，而空的形态是「0.0 公里 · 今天」，
                        // 那看起来像 **App 出故障**，而不是「今天没开车」。
                        //
                        // 现在：主角位换成一句话；「行驶耗电 / 充入电量」在
                        // **真的没有数据**时给「—」，而不是「0.0 度」。
                        //
                        // ⚠️ 费用分支**不动** —— v0.31.0 已经把它修对了：
                        // 「没有里程」≠「没有费用数据」，
                        // 今天没充过电，费用就是 ¥0，那句「这段时间没有充电记录」是**对的**。
                        if (sum.drive_count == 0) {
                            Text(
                                emptyWindowText(periodLabel),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.height(10.dp))
                            KV("行驶耗电", "—")
                        } else {
                            // ⚠️ v0.17.0（苹果 HIG P0-1）：**一屏一个主角数字**。
                            //
                            // 原来 7 行**等权**排布 —— 扫一眼没有落点，主角不成立
                            // （评审原话：「任意一屏截图缩略到 200px 宽，
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
                        }
                        // ⚠️ v0.14.0（单位审校 P2-4）：慢充为 0 时**不显示该分段**。
                        KV(
                            "充入电量",
                            if (sum.charge_count == 0) {
                                // ⚠️ v0.48.0：没有充电记录时给「—」，不是「0.0 度」
                                "—"
                            } else {
                                buildString {
                                    append("${sum.charge_kwh.fmt(1)} 度")
                                    if (sum.dc_kwh > 0.05 || sum.ac_kwh > 0.05) {
                                        append("（快充 ${sum.dc_kwh.fmt(1)}")
                                        if (sum.ac_kwh > 0.05) append(" / 慢充 ${sum.ac_kwh.fmt(1)}")
                                        append("）")
                                    }
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
                        //
                        // ⚠️ **v0.31.0（第 20 轮费用复审 P2-2）：「没有里程」不等于「没有费用数据」。**
                        //
                        // 原来 `sum.km > 0.1` 一把关，「今天」段（今天还没开车，里程 0）
                        // 就掉进「充电费用 **未记录**」分支，还说「需在 TeslaMate 网页端手填单价」——
                        // 而事实是**今天没充过电，费用就是 ¥0**。
                        // 把「没有里程」误报成「没有数据」，还把责任推给用户的配置。
                        val hasCostData = costSummary != null && rates.configured
                        val hasClientEstimate = hasCostData && sum.km > 0.1
                        // ⚠️ costSummary 是 delegated property（var by remember）→ 不能 smart cast，
                        // 先取一个局部 val。
                        val cs = costSummary
                        if (!hasClientEstimate) {
                            if (sum.cost > 0) {
                                KV("充电费用", "${sum.cost.fmt(2)} 元")
                            } else if (hasCostData && cs != null) {
                                // 有费用数据，只是**这段没有行驶** → 给费用，不给每公里成本
                                val charged = cs.dc_used_kwh + cs.ac_used_kwh
                                if (charged > 0.01) {
                                    val c = estimateCost(cs, rates)
                                    KV("充电费用", "≈ ${c.totalYuan.fmtYuan()}")
                                    Text(
                                        "这段没有行驶记录，所以给不出每公里成本",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 4.dp),
                                    )
                                } else {
                                    KV("充电费用", "¥0.00")
                                    Text(
                                        "这段时间没有充电记录",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 4.dp),
                                    )
                                }
                            } else {
                                // ⚠️ v0.17.0：说明文字**拆到下一行** ——
                                // 原来把它塞进值里（「未记录 —— 在 TeslaMate 网页端手填单价后才有」），
                                // 值太长会换行成两行、右对齐还顶到边角，很难读。
                                //
                                // ⚠️ **v0.31.0**：那句「在 TeslaMate 网页端手填单价」**已经过时** ——
                                // 电价是在**本 App 的设置页**填的（v0.25.0 起），
                                // TeslaMate 那边的 `tou_rates` 是另一套东西（且至今 0 行）。
                                KV("充电费用", "未记录")
                        // ⚠️ **v0.65.0（第 32 轮「减啰嗦」）**：操作指引折进 ⓘ。
                        //
                        // 车主第 32 轮拍板：「**操作指引也折**」⇒ 这条「去设置填电价」折。
                        InfoNote(
                            text = "在「设置 → 电价与成本」填上电价后，这里就能估算",
                            a11yLabel = "每公里成本怎么来的",
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
                            // ⚠️ **v0.31.0（第 20 轮费用复审 P0-1）：两个分母。**
                            //
                            // `km_drives` = App 记录到的行驶里程；
                            // `km_odometer` = 里程表跨度，**包含**遥测断档里车真开过的公里
                            // （实测全库 12 次断档合计 1459.62 公里 = 16.71%，且全在 10-01 之前）。
                            //
                            // 所以长窗口的「每公里成本」是**区间**，不是点值。
                            val kmDrives = costSummary?.km_drives ?: 0.0
                            val kmOdo = costSummary?.km_odometer ?: 0.0
                            val perKm = costPerKm(cost, sum.km, kmOdo)
                            KV("充电费用", "≈ ${cost.totalYuan.fmtYuan()}")
                            perKm.allPerKm?.let {
                                // ⚠️ v0.27.0：覆盖度不足时必须限定口径 ——
                                // 「每公里成本」的分母是**这段时间的里程**，
                                // 而「1 年」实际只有 4 个月有数据 → 这个数不是年成本。
                                val range = perKm.allPerKmLow?.let { lo ->
                                    "≈ ${lo.fmtYuan()} ~ ${it.fmtYuan()} /公里"
                                } ?: "≈ ${it.fmtYuan()} /公里"
                                KV(
                                    "每公里成本",
                                    range + if (coverageLow) "（按这 $filledBuckets $bucketUnit 算）" else "",
                                )
                            }
                            // ⚠️ **v0.31.0（P0-1）：分母有缺口时必须说清楚。**
                            //
                            // 不说的话，用户会把上界当成**确定值** ——
                            // 而它其实是个上界（真值低 3.4%~11.9%）。
                            perKm.denominatorGapPercent?.let { gap ->
                                if (gap >= 1) {
                        // ⚠️ **v0.65.0（第 32 轮「减啰嗦」）**：分母/窗口边界的口径说明折进 ⓘ。
                        //
                        // 这段是**整卡最啰嗦的一段**（185 字）：分母为什么不是里程表、
                        // 断档是什么、窗口两端的存量电没扣 ⇒ 全部折起来。
                        InfoNote(
                            text = "分母说明：这 $filledBuckets $bucketUnit 里 App 记录到 " +
                                "${kmDrives.roundToInt()} 公里行驶，" +
                                "而里程表走了 ${kmOdo.roundToInt()} 公里 —— " +
                                "差的 $gap% 是 TeslaMate 没在记录的遥测断档" +
                                "（那些路车真开了，但没记行程），所以每公里成本是区间。" +
                                // ⚠️ **v0.44.0（第 24 轮对抗性审计 ⑫）：还要说清**窗口边界**。**
                                //
                                // 分子 = 窗口内**充入的电**，分母 = 窗口内**跑的公里** ——
                                // 这两者**不是同一段能量**：窗口起点的存量电量、
                                // 窗口终点尚未消耗的电量，都被算进去了。
                                // 长窗口（1 年）这个偏差可以到百分之十几。
                                "另外分子是窗口内充入的电、分母是窗口内跑的公里，" +
                                    "窗口两端还剩多少电的差没有扣，所以这个数是近似值。",
                            a11yLabel = "每公里成本的分母",
                        )
                                }
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
                            // ⚠️ **v0.30.0（第 19 轮财务评审 P1-1）：「实际每度电」必须拆开。**
                            //
                            // 实测全周期 1.7689 元/度里，**0.5874（33.2%）是公共桩服务费**。
                            // 车主拿「实际每度电」和自己的居民电价 0.53~0.83 一比，
                            // 会以为「我的电费高了三倍」—— 却不知道其中 1/3 是停车/占位费。
                            // 现在拆成两行：电费每度 / 服务费每度。
                            cost.energyYuanPerKwh?.let {
                                KV("电费每度", "≈ ${it.fmtYuan()}")
                            }
                            cost.serviceYuanPerKwh?.let {
                                KV("服务费每度", "≈ ${it.fmtYuan()}")
                            }
                        // ⚠️ **v0.65.0（第 32 轮「减啰嗦」）**：成本口径说明折进 ⓘ。
                        //
                        // 「分子分母分别是什么、占比多少」是**口径**⇒ 折。
                        InfoNote(
                            text = buildString {
                                append("按你填的电价估算（家充（有围栏归属）")
                                append(cost.homeKwh.fmt(0))
                                append(" 度 / 其它交流慢充 ")
                                append(cost.acOtherKwh.fmt(0))
                                append(" 度 / 直流快充 ")
                                append(cost.dcKwh.fmt(0))
                                append(" 度）")
                                perKm.acSharePercent?.let { append(" · 交流慢充占 $it%") }
                                perKm.homeSharePercent?.let {
                                    append(" · 其中真正在家充的占 $it%")
                                }
                                append("。分母是行驶里程 ")
                                append(sum.km.fmt(0))
                                append(" 公里。")
                            },
                            a11yLabel = "每公里成本的口径",
                        )
                            // ⚠️ **v0.30.0（P0-2）：服务费没填时必须说「估算偏低」。**
                            //
                            // 实测：不填服务费 → ¥2,201.17；按分钟 0.50 → ¥3,295.67，
                            // **低估 ¥1,094.50（33.2%）** —— 而界面一声不吭。
                            //
                            // ⚠️ **v0.31.0（第 20 轮费用复审 P2-1）**：
                            // ① 「三分之一」是**硬编码**，实测各段占比不同：
                            //    1 年 32.9% / 本月 33.3% / **7 天 47.8%** —— 7 天段是「约一半」。
                            // ② 纯交流窗口（没有直流）时**服务费根本不参与**，提示完全不成立。
                            // 现在：① 用实际占比；② 加直流闸门。
                            if (rates.serviceFeeMissing && cost.dcKwh > 0.01) {
                                val missingShare = cost.serviceYuanPerKwh?.let { svc ->
                                    val total = cost.yuanPerKwh ?: return@let null
                                    if (total > 0.01) (svc / total * 100).roundToInt() else null
                                }
                                Text(
                                    if (missingShare != null) {
                                        "⚠ 没填公共桩服务费 —— 这类桩普遍收占位费，" +
                                            "实测不填会低估约 $missingShare%"
                                    } else {
                                        "⚠ 没填公共桩服务费 —— 这类桩普遍收占位费，估算会偏低"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalAccentColors.current.warnAmber,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                            // ⚠️ **v0.30.0（P0-3）：家充现在有**真凭据**了。**
                            //
                            // 原来的模型是「交流 ⇒ 家充」，靠充电枪类型猜地点 ——
                            // 实测本机 5 场交流慢充**没有一场在家**（最近的 287 公里，
                            // 其余 1435/1435/1481 公里，还有一场 25.6 公里但充入 0 度），
                            // 这个猜法 5/5 全错。
                            // 现在家充由 **TeslaMate 的充电围栏**判定，
                            // 剩下的交流（没有围栏归属）就是「不知道在哪」，
                            // 按你填的「其它交流慢充单价」计。
                        // ⚠️ **v0.65.0（第 32 轮「减啰嗦」）**：围栏判定的口径说明折进 ⓘ。
                        //
                        // 「家充按充电围栏判定」「没有归属的交流慢充按公共桩价计」——
                        // 两条都是**口径**⇒ 折（两态都进 note，正文只留 ⓘ）。
                        InfoNote(
                            text = if (cost.homeKwh > 0.01) {
                                "家充按 TeslaMate 的充电围栏判定（这段窗口里有 ${cost.homeKwh.fmt(0)} 度命中围栏）"
                            } else {
                                "这段窗口里没有任何一场充电命中充电围栏 —— App 分不出交流慢充是在家还是在酒店/路边" +
                                    "（「家」围栏是 2026-10-25 才建的，之前的充电都没有归属）。" +
                                    "没有归属的交流慢充现在按「${if (rates.acUsesHomePrice) "家充电价" else "公共桩价"}」计" +
                                    "（设置里可改）"
                            },
                            a11yLabel = "家充判定的口径",
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
                        // 实测两者相差约 **19%**（标定法 181.9 vs 车轮功率 152.7）。
                        // 两个页面各写一个能耗数，不标口径用户会以为有一个是错的。
                        sum.wh_per_km?.let {                         // ⚠️ **v0.29.0（复审 P1-2）：量级订正。**
                        // 原来注释写「标定法高估约 7%」—— 实测是 **+19%**：
                        // 标定法 181.9 vs 车轮功率 152.7（最近 20 趟 ≥20km 中位：172.7 vs 151.9）。
                        // 而且要说明**两个都比实测电池耗电低**（实测约 198 kWh/100km）——
                        // 车轮功率不含传动损耗，标定法受车机重算影响。
                        KV("平均能耗（标定法）", "${it.fmtKwh100()} kWh/100km") }
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
                        // ⚠️ **v0.65.0（第 32 轮「减啰嗦」）**：指标注释折进 ⓘ。
                        //
                        // 「行驶耗电 = 行车用掉的电，不含充电时充进去的」——
                        // 这是**指标注释**（车主点名要折的那类）⇒ 折。
                        InfoNote(
                            text = "行驶耗电 = 行车用掉的电，不含充电时充进去的",
                            a11yLabel = "行驶耗电的口径",
                        )
                }
            }

            val b = battery
            item {
                // ⚠️ **v0.28.0：能耗-车速曲线**。
                //
                // 放在「行驶耗电」之后、电池健康之前 ——
                // 它是「行驶耗电」那张图的**展开**（同一件事：从平均变成按车速）。
                EnergyProfileCard(
                    profile = energyProfile,
                    loading = energyProfileLoading,
                    modifier = Modifier.animateItem(),
                )
            }
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
                        // ⚠️ **v0.63.0（R31 视觉复审 P1-4）**：「新电池」原来一个词指两个量 ——
                        // 「新电池标称 76.8 度」和下面「新电池同口径 527 km」单位不同、换算系数没给，
                        // 用户自己对不上。现在把容量那行的名字写全。
                        b.nominal_capacity_kwh?.let { KV("新电池标称可用容量", "${it.fmt(1)} 度") }
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
                            // ⚠️ **v0.62.0（第 31 轮电池出厂态核查）**：
                            // 原来叫「占铭牌」，还配了一句「它不是衰减了多少」——
                            // **那句话是错的**。
                            //
                            // `nominal_capacity_kwh` 存的就是**出厂可用容量**，
                            // 分子（实测可用容量）分母（出厂可用容量）**同一口径**，
                            // 比值就是**电池健康度（SOH）**，一个标准指标。
                            // 旧名「占铭牌」恰恰把标准指标说成了别的意思，
                            // 还让车主以为自己读到的是「不是衰减」——
                            // 于是 84.9% 会被读成「还有 84.9%」而不是「衰减了 15%」。
                            KV("电池健康度（相对出厂）", "${b.health_percent!!.fmt(1)}%")
                        } else {
                            Text(
                                "暂无「电池健康度」（未配置出厂容量，服务端不给分母）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // ⚠️ v0.22.0（P1-1）：**相对首测** ——
                        // 这是**唯一不受** `cars.efficiency` 影响的健康指标
                        // （分子分母同比例缩放）。
                        // 它回答的才是车主真正想知道的那句：「电池比刚开始测的时候差了多少」。
                        //
                        // ⚠️ **v0.28.0（第 17 轮新手车主 P0-3）：必须带**首测日期**。**
                        //
                        // 原来只写「相对首测 98.4%」，用户会读成「新车容量到现在只剩 98.4%」——
                        // 而首测是 **2026-08-02**，那台车**已经开了 4 个月**，
                        // 分母**本身已经衰减过**。
                        //
                        // 评审原话：「首测不是新车容量，只回答『比 8 月 2 号那次低了多少』」。
                        val baselineDate = b.series.orEmpty().take(3)
                            .sortedBy { it.capacity_kwh }
                            .getOrNull(1)?.date?.fmtDate()
                        b.health_percent_of_baseline?.let {
                            // ⚠️ **v0.63.0（R31 视觉复审 P1-1）**：**数字的形状本身就是误读入口**。
                            //
                            // 「98.4%」这个形状会被读成「电池还有 98.4%」——
                            // 改名字不够，**必须换形状**：显示**变化量** −1.6%。
                            // 换完之后整张卡**只有 84.9% 一个百分比**，主角唯一。
                            //
                            // 语义不变：它仍然只是「比首测那次低了多少」，
                            // 首测时车**已经开了 4 个月**（见下面说明）。
                            val delta = 100.0 - it
                            KV(
                                "近期趋势（自 " + (baselineDate?.let { d -> d } ?: "首测") + "）",
                                (if (delta >= 0) "−" else "+") + kotlin.math.abs(delta).fmt(1) + "%",
                            )
                        }
                        // ⚠️ **v0.62.0（第 31 轮电池出厂态核查）**：**满电标定续航**。
                        //
                        // 车主最关心的单位（「公里」）。
                        //
                        // ⚠️ **v0.63.0（R31 视觉复审 P0-2 / P1-5）**：原来把
                        // 「现在 / 出厂折算 / 少多少」全塞进**一个值**里 ——
                        // 29 个字符在 15sp 的值列里撑破布局：标签和值只隔 3.5dp
                        // （比值内部的词间距 6.1dp 还小，看着像粘成一个词），
                        // 第二行只剩「81 km）」孤零零右对齐。
                        // 现在拆成**两行同名**（现在 / 出厂折算），每个值都很短，
                        // 「少多少」降级到下面的说明里。
                        val latestFullPoint = b.series.orEmpty()
                            .filter { it.end_soc != null && it.end_soc!! >= 100 && it.rated_range_km != null }
                            .maxByOrNull { it.date }
                        val nowRatedKm = latestFullPoint?.rated_range_km
                        if (nowRatedKm != null) {
                            KV("满电标定续航（现在）", "${nowRatedKm.toInt()} km")
                            b.factory_rated_range_km?.let { factoryKm ->
                                KV("满电标定续航（出厂折算）", "${factoryKm.toInt()} km")
                            }
                        }
                        if (nowRatedKm != null && b.factory_rated_range_km != null) {
                            // ⚠️ **v0.63.0（R31 视觉复审 P1-3）**：「少 81 km」是**精确值假象** ——
                            // 满充读数本身有 445~455 km 的温度波动，同一份数据可能算出 73~82 km。
                            // 所以改成「约 80 km」，并把日期与波动区间一起说清楚。
                            val gapKm = b.factory_rated_range_km!! - nowRatedKm
                        // ⚠️ **v0.65.0（第 32 轮「减啰嗦」）**：口径说明折进 ⓘ。
                        //
                        // 「同一把尺子」「读数有温度波动」「官方标称不能直接相除」——
                        // 三条都是**口径**⇒ 折。
                        InfoNote(
                            text = buildString {
                                append("「满电标定续航」= 车机在满电时自己报的读数")
                                latestFullPoint?.date?.let { append("（${it.fmtDate()} 那次满充）") }
                                append("。")
                                append("按出厂容量折算，同一把尺子下新电池应是 ")
                                append(b.factory_rated_range_km!!.toInt())
                                append(" km —— 大约少 ")
                                append((gapKm / 5.0 * 5.0).toInt())
                                append(" km。")
                                append("满充读数本身有 445~455 km 的温度波动，所以这个差是一个区间，不是一个精确值。")
                                append("官方标称续航是新车认证值，不是这台车这把尺子上的读数，两者不能直接相除。")
                            },
                            a11yLabel = "满电标定续航的算法",
                        )
                        }
                        // ⚠️ **v0.65.0（第 32 轮「减啰嗦」）**：公式与口径说明折进 ⓘ。
                        //
                        // 「电池健康度 = 现在还能装的度 ÷ 新电池标称的度」是**口径**，
                        // 「效率常数可在服务端改」是**依赖披露** ⇒ 两条都折。
                        InfoNote(
                            text = buildString {
                                append("「电池健康度」= 现在还能装的度 ÷ 新电池标称的度 —— ")
                                append("两边都是「可用容量」，同一口径，")
                                append("所以这个比值就是相对出厂衰减了多少。")
                                // ⚠️ **v0.44.0（第 24 轮对抗性审计 ⑪）：分子依赖一个**用户可改的常数**。**
                                //
                                // 分子 = 车机上报的续航估值 × `cars.efficiency`（本机 0.146）。
                                // 那个常数是车主为修 Wh/km 显示而配的 —— **改一次，界面就变一次**
                                // （0.146 → 0.153 会让这个数从 84.9% 跳到 88.9%）。
                                // 不说清楚，读者会以为它是「测出来的」。
                                append("分子里的效率常数（cars.efficiency）可在服务端改，改了这个数就变。")
                                // ⚠️ **v0.63.0（R31 视觉复审 P0-3）**：原来这里写「本车实测上限 86.4%」——
                                // 改名为「（相对出厂）」之后，它和上面的 84.9% **成了同一个口径的两个数**，
                                // 用户会以为有一个是错的。现在把关系写进句子里：
                                // 它是「天花板」，不是第二个健康度。
                                if (b.nominal_capacity_kwh != null && strictMax != null) {
                                    append("这台车有史以来最好的一次，也只到出厂的 ")
                                    append((strictMax * 100 / b.nominal_capacity_kwh).fmt(1))
                                    append("%。")
                                }
                            },
                            a11yLabel = "电池健康度的算法",
                        )
                        if (b.health_percent_of_baseline != null) {
                        // ⚠️ **v0.65.0（第 32 轮「减啰嗦」）**：口径说明折进 ⓘ。
                        //
                        // 「近期趋势 = 当前容量 ÷ 首测容量…和『相对出厂』不是同一段时间」——
                        // 这是**口径**不是数据 ⇒ 折。
                        InfoNote(
                            text = buildString {
                                append("「近期趋势」= 当前容量 ÷ 首测容量（取前 3 次中位数），")
                                append("不受行驶能耗常数影响。")
                                append("它只衡量这段时间里的变化，和上面的「相对出厂」不是同一段时间 —— ")
                                append("要回答「相对出厂衰减了多少」，看的是上面那个健康度。")
                                if (baselineDate != null) {
                                    append("注意：首测（$baselineDate）不是新车容量 —— ")
                                    append("它只回答「比 $baselineDate 那次测的低了多少」。")
                                }
                            },
                            a11yLabel = "近期趋势的算法",
                        )
                        }
                        val series = b.series.orEmpty().map { it.capacity_kwh?.toFloat() }
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
                                xLabels = bucketAxisLabels(b.series.orEmpty().map { it.date.fmtDate() }),
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
                            // ⚠️ **v0.35.0：电池衰减结论由**服务端**判定**（严格档 + t 检验 + 阶跃检测），
                            // 客户端只负责把它说清楚。
                            //
                            // 原来这里有一份客户端侧的线性回归（v0.29.0），
                            // 用的「效应/噪声」比值**不是显著性检验**，
                            // 而且混用了系统性偏高 0.56% 的 90~98% 满充点 ——
                            // 实测把衰减速率**高估了 2.3 倍**。
                            b.decay?.let { d ->
                                BatteryDecayText.lines(d).forEach { line ->
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    BatteryDecayText.caveatLine(d),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                // ⚠️ **v0.41.0（第 24 轮对抗性审计 ②③④）：完整推导进折叠区。**
                                //
                                // 服务端的 `caveat` / `method` 从 v0.35.0 起就在下发，
                                // 但客户端**从来没渲染过它们** ——
                                // 于是界面上留着的是客户端自己写的那句
                                // 「残差里大部分是充电时气温」（实测只有 12%）。
                                //
                                // 现在：口径提示与服务端判定**同一份**，
                                // 完整推导（气温系数、留一法、方差解释比例）
                                // 收进「怎么算的」—— 数据一点没丢，但不占常驻版面。
                                val detail = BatteryDecayText.detailLines(d)
                                if (detail.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    CollapsibleSection("怎么算的") {
                                        Column {
                                            detail.forEach { line ->
                                                Text(
                                                    line,
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
            }
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
/**
 * ⚠️ **v0.48.0（第 28 轮通勤车主评审 P1-1）**：用量页汇总卡的**零行驶日空态**文案。
 *
 * 真实数据：87 天里 **42 天零行驶（48.3%）** ——
 * 「今天」段有一半概率是空的，而「0.0 公里 · 今天」看起来像 App 出故障。
 *
 * ⚠️ 「今天」与更长的窗口**用不同的说法**：
 * 「今天还没开车」是一个**时点**的判断，
 * 而「7 天没有行程记录」是一个**区间**的判断 ——
 * 混用会把「这个区间没开车」说成「现在还没开车」。
 */
fun emptyWindowText(periodLabel: String): String =
    // ⚠️ Kotlin 的字符串模板：**必须**用 `${}` ——
    // `"$periodLabel没有行程记录"` 会被解析成变量名 `periodLabel没有行程记录`
    // （中文字符是合法的标识符字符！）⇒ 编译报「Unresolved reference」。
    if (periodLabel == "今天") "今天还没开车" else "${periodLabel}没有行程记录"


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
                        // ⚠️ **v0.65.0（第 32 轮「减啰嗦」）**：图表脚注折进 ⓘ。
                        //
                        // 车主原话：「细节上的文字直接展示出来的太多了…需要我点击一个小角标才会展示。」
                        // 「有记录 3 天，没开车不产生记录」是**口径**，不是数据 ⇒ 折。
                        InfoNote(
                            text = "$avgLabel ${avg.fmt(0)} $unit（有记录 ${nonZero.size} $avgUnit，没开车不产生记录）",
                            a11yLabel = "日均值的算法",
                        )
    }
}
