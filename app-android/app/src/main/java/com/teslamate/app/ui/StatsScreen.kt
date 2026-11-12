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
    //
    // ⚠️ **v0.75.0（模拟器真机复核）：默认段是「7 天」，不是「今天」。**
    //
    // v0.29.0 把分段重排成「今天 / 7 天 / 本月 / 1 年」，
    // 但这里的下标**没跟着改** ⇒ 默认落到了**索引 0 =「今天」**。
    // 而上面那段注释（v0.8.5 效率车主复核）写的仍然是「默认…改『近 7 天』」——
    // **注释与代码打架**，最后是代码赢了。
    //
    // 实测（模拟器 1440×3200）：打开「用量」页 = 今天没开车 ⇒
    // 首屏是「今天还没开车」+ **三张灰色实心图表块**
    // （行驶里程 / 充入电量 / 行驶耗电），用户第一眼读作「App 坏了」。
    //
    // ⇒ 默认回「7 天」：一进来就有内容，也正是 v0.8.5 那条判断的意思。
    var periodIndex by remember { mutableIntStateOf(1) }
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
                            // ⚠️ **v0.151.0**：给出**可见标签** ⇒ 不再是一枚没有主体的孤儿徽标。
                            //
                            // InfoNote 自己的文档写着：label 给 null 时「只剩一个 ⓘ 徽标」，
                            // **适合挂在已经有明确标题的地方** —— 而这里**恰恰没有标题**
                            // （上一行是分段控件，下面是覆盖度说明）⇒ 用户看到的是一枚
                            // 不知道在解释什么的圆点（实测它还贴在屏幕最左缘 x=0）。
                            label = "时区口径",
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
            // ⚠️ **v0.75.0（模拟器真机复核）：覆盖度为 0 时**不显示**这一行。**
            //
            // 「数据只覆盖 0/1 天（0%）」—— 这句话**不含任何信息**：
            // 0/1 天就是「这一天没数据」，而下面的空态已经说了「今天还没开车」。
            // 而覆盖度这行**本来是诚实披露工具**（v0.27.0 复审要的就是它）——
            // 一旦变成 0，它就从「披露」退化成「噪音」。
            //
            // ⇒ 只在**有数据但没覆盖满**时说话（那才是需要提醒的情况）。
            if (coverageLow && filledBuckets > 0) {
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
                        // ── **v0.93.0：汇总卡做减法 —— 一个主角 + 一行副标题 + 费用 + 一个折叠入口** ──
                        //
                        // 原来这里是 **5 行等权 KV**（行驶耗电 / 充入电量 / 充电费用 /
                        // 平均能耗（标定法） / 行程·充电次数），
                        // 主角「81.7 公里」被压在最上面一行下面，
                        // 中间还夹着 **4 个没有主题的孤儿 ⓘ** ——
                        // 收起来就是一列裸 ⓘ，谁也说不清哪个解释哪个指标。
                        //
                        // 现在按「一屏一焦点」重排：
                        //   · **表层**：主角 = 里程；副标题 = 平均能耗（带口径 ⓘ）；
                        //     费用块（可操作的入口 + 必须直接可见的告警）；「更多明细」入口。
                        //   · **折叠区**：行驶耗电 / 充入电量 / 行程·充电次数 —— 全是**证据**。
                        //
                        // ⚠️ **数据一点没丢** —— 只是换了位置；
                        // API 与 Hermes（飞书）侧全都还在（字段、精度、口径都没动）。
                        //
                        // ⚠️ **v0.93.0**：本轮**一个阈值也不新增**，全部沿用服务端既有事实源。

                        // ── 主角 / 空态结论 ─────────────────────────────────────────
                        if (sum.drive_count == 0) {
                            // ⚠️ **v0.48.0（第 28 轮通勤车主评审 P1-1）：零行驶日给**专属空态**。**
                            //
                            // 真实数据：87 天里 **42 天零行驶（48.3%）** ——
                            // 「今天」段**有一半概率**是空的，而空的形态是「0.0 公里 · 今天」，
                            // 那看起来像 **App 出故障**，而不是「今天没开车」。
                            //
                            // ⚠️ **v0.93.0**：空态**只留这一句结论** ——
                            // 原来紧跟着的「行驶耗电 —」行是**证据位**，现在折进「更多明细」。
                            Text(
                                emptyWindowText(periodLabel),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        } else {
                            // ⚠️ **v0.17.0（苹果 HIG P0-1）：一屏一个主角数字。**
                            //
                            // 原来 7 行**等权**排布 —— 扫一眼没有落点，主角不成立
                            // （评审原话：「任意一屏截图缩略到 200px 宽，
                            // 灰度化后仍能一眼指出唯一的最深/最大文字块」）。
                            //
                            // ⚠️ **v0.93.0**：副标题由「公里 · 7 天」收敛为「公里」——
                            // 窗口名交给**卡片标题**（「7 天汇总」）承担，
                            // 它在主角正上方、滚动后仍然可见；
                            // 首屏「7 天」由 3 次降为 2 次（段控件 + 卡片标题）。
                            BigStat(
                                sum.km.fmt(1),
                                "公里",
                                hero = true,
                            )
                            // ⚠️ **v0.93.0**：副标题换成**平均能耗**。
                            //
                            // 「平均能耗」= 行驶耗电 ÷ 里程，是**派生结论** ——
                            // 电动车用户真正反复看的那个数；
                            // 原来它埋在第 5 行，和「行程 / 充电次数」这种纯计数**平级**。
                            // 现在结论上移，原始数（行驶耗电）降为证据。
                            //
                            // ⚠️ **v0.27.0**：两个页面各写一个能耗数（这里 vs 车况页「驾驶画像」），
                            // 不标口径用户会以为有一个是错的 ⇒ 口径必须写清楚。
                            sum.wh_per_km?.let { wh ->
                                Spacer(Modifier.height(6.dp))
                                TextWithNote(
                                    text = "平均能耗 ${wh.fmtKwh100()} kWh/100km",
                                    // ⚠️ **v0.93.0**：口径 ⓘ 挂在**它解释的那一行行尾**
                                    // （v0.68.0 R32 真机复核起就是这条规矩）。
                                    //
                                    // 标签里的「（标定法）」是**工程词** ⇒ 去掉
                                    // （同 v0.90.0 去掉「标定」），口径一字不少地写进 ⓘ。
                                    //
                                    // ⚠️ **v0.103.0**：正文搬进 `ui/EnergyNote.kt` 的纯函数
                                    // `noteAvgEnergy()`。
                                    //
                                    // 原来这段 buildString 拼出 **238 视觉宽度**，而且**最后一句是错的**：
                                    // 「而且两个都比实测电池耗电低（车轮功率不含传动损耗，标定法还受车机重算影响）」
                                    // —— 那句是拿**净值**（≈155，比实测 ≈198 低 24%）推出来的，
                                    // 而车况页显示的是**毛耗电**（≈215~227，**高于**实测）。
                                    // ⇒ 详见 `ui/EnergyNote.kt` 的注释（含完整时间线）。
                                    // ⇒ 现在压到 **104**（≤160），且不再断言两个口径的大小关系。
                                    note = noteAvgEnergy(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    a11yLabel = "平均能耗的口径",
                                )
                            }
                        }
                        // ── 费用块（留在表层）─────────────────────────────────────
                        //
                        // ⚠️ **v0.93.0**：费用块**留在表层**，两个理由：
                        // ① 「充电费用 未记录」是**可操作的入口**（ⓘ 告诉用户去设置填电价）；
                        // ② 「没填公共桩服务费」的**琥珀告警必须直接可见**
                        //    （仓库规矩：告警与错误保持直接可见）。
                        //
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
                        // ⚠️ **v0.96.0**：费用**证据行**（每度电 / 电费 / 服务费）要折进
                        // 「更多明细」，而那个折叠区在费用块**外面** ⇒ 费用得先算好提到这里。
                        //
                        // ⚠️ 只在**能算**的时候算（配了电价 + 这段有行驶）——
                        // 没配电价时算出来全是 0，界面上会出现「¥0.00」的假数。
                        val clientCost = if (hasClientEstimate) estimateCost(costSummary!!, rates) else null
                        if (!hasClientEstimate) {
                            if (sum.cost > 0) {
                                KV("充电费用", "${sum.cost.fmt(2)} 元")
                            } else if (hasCostData && cs != null) {
                                // 有费用数据，只是**这段没有行驶** → 给费用，不给每公里成本
                                val charged = cs.dc_used_kwh + cs.ac_used_kwh
                                if (charged > 0.01) {
                                    val c = estimateCost(cs, rates)
                                    KV(
                                        "充电费用",
                                        "≈ ${c.totalYuan.fmtYuan()}",
                                        // ⚠️ **v0.93.0**：解释挂到**它解释的那一行行尾**
                                        // —— 原来是一行裸灰字，夹在两张卡之间像漂着的字。
                                        note = "这段没有行驶记录，所以给不出每公里成本",
                                    )
                                } else {
                                    KV(
                                        "充电费用",
                                        "¥0.00",
                                        note = "这段时间没有充电记录",
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
                                //
                                // ⚠️ **v0.93.0**：ⓘ 挂在**标签旁**（`KV` 的 note 参数）——
                                // 原来它是**一个没有主题的孤儿 ⓘ**，收起来就是一列裸 ⓘ。
                                KV(
                                    "充电费用",
                                    "未记录",
                                    note = "在「设置 → 电价与成本」填上电价后，这里就能估算",
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
                            // ⚠️ v0.96.0：用上面提上来的那一份（同一个窗口、同一份口径）。
                            val cost = clientCost!!
                            // ⚠️ **v0.31.0（第 20 轮费用复审 P0-1）：两个分母。**
                            //
                            // `km_drives` = App 记录到的行驶里程；
                            // `km_odometer` = 里程表跨度，**包含**遥测断档里车真开过的公里
                            // （实测全库 12 次断档合计 1459.62 公里 = 16.71%，且全在 10-01 之前）。
                            //
                            // 所以长窗口的「每公里成本」是**区间**，不是点值。
                            val kmOdo = costSummary?.km_odometer ?: 0.0
                            val perKm = costPerKm(cost, sum.km, kmOdo)
                            KV(
                                "充电费用",
                                "≈ ${cost.totalYuan.fmtYuan()}",
                                // ⚠️ **v0.93.0**：成本口径 + 家充判定**合并**挂到这一行 ——
                                // 原来它们是两个**没有主题的孤儿 ⓘ**，谁也说不清哪个解释哪个。
                                // ⚠️ **v0.97.0**：成本口径抽成纯函数 `noteChargeCost()`（`ui/CostNote.kt`）。
                                //
                                // 原来这段 buildString 拼出 **380 视觉宽度**的说明 ——
                                // 五句话混三个话题（怎么算的 / 度数构成 / 占比 / 围栏判定 / 设置提示），
                                // 点开 ⓘ 得到的是一堵字墙，不是「一句话结论 + 关键限定」。
                                //
                                // 现在压到 ≤160（≈3 行）：只留「按你填的电价 + 度数构成」
                                // 与「围栏归属」这条**真正影响这个数能不能信**的限定。
                                // ⚠️ 一个阈值也没新增；事实一字没丢（丢掉的是占比 —— 那是度数的派生量）。
                                note = noteChargeCost(cost, rates.acUsesHomePrice),
                            )
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
                                    // ⚠️ **v0.93.0**：分母说明挂到**它解释的那一行行尾**
                                    // —— 原来是一个没有主题的孤儿 ⓘ。
                                    // ⚠️ **v0.97.0**：分母口径抽成纯函数 `noteCostPerKm()`（`ui/CostNote.kt`）。
                                    //
                                    // 原来这段 buildString 拼出 **296 视觉宽度**的说明，
                                    // 现在压到 ≤160（≈3 行）：分母是什么 + 缺口为什么是区间 + 为什么是近似值。
                                    // ⚠️ 缺口 ≥1% 才说 —— 与 `costPerKm()` 给区间的口径**同一个阈值**。
                                    note = noteCostPerKm(sum.km, perKm.denominatorGapPercent),
                                )
                            }
                            // ⚠️ P0-3：**直流桩边际成本**必须单独给 ——
                            // 实测本车交流慢充只占 3%，混算会给人「我享受了家充电价」的错觉，
                            // 而真实情况是这车几乎全按公共桩价买的电。
                            // ⚠️ **v0.96.0**：这一行**只在它与「每公里成本」不是同一个数时**才出现。
                            //
                            // 窗口里全是直流桩时（本机今天 / 7 天段实测 100% 直流），
                            // 「直流桩边际成本」与「每公里成本」**恒等** ⇒
                            // 它只是**同一个数的第二次出现**，还顶着一个像新指标一样的标签 ⇒ 更迷惑。
                            //
                            // v0.14.0 的先例：「两行说的是同一件事，比一行更让人烦」。
                            if (!dcMarginalIsRedundant(perKm.allPerKm, perKm.dcPerKm)) {
                                perKm.dcPerKm?.let {
                                    KV(
                                        "直流桩边际成本",
                                        "≈ ${it.fmtYuan()} /公里" +
                                            if (coverageLow) "（按这 $filledBuckets $bucketUnit 算）" else "",
                                    )
                                }
                            }
                            // ⚠️ **v0.96.0**：「实际每度电 / 电费每度 / 服务费每度」**折进「更多明细」** ——
                            // 它们是**证据**（这钱是怎么算出来的），不是结论。
                            //
                            // 表层只留「充电费用（总额）」与「每公里成本（单价）」两个答案。
                            // （拆解的理由跟着这三行一起折进去，见下面折叠区里的注释。）
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
                                // ⚠️ **v0.71.0（减啰嗦深挖）**：文案**提出成局部变量 + 压缩措辞**。
                                //
                                // 原来两个分支直接写进 `Text(...)` ⇒ 闸门把两支合起来算成 **47 字**，
                                // 而界面上**一次只显示一支**。
                                // 而且那一支本身也偏长（26 字）——
                                // 「这类桩普遍收占位费」是**原因**，压成括号里的短注，
                                // 主句留给**结论**（低估多少 %）。
                                val feeNote = if (missingShare != null) {
                                    "⚠ 没填公共桩服务费（普遍收占位费）—— 实测会低估约 $missingShare%"
                                } else {
                                    "⚠ 没填公共桩服务费（普遍收占位费）—— 估算会偏低"
                                }
                                Text(
                                    feeNote,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalAccentColors.current.warnAmber,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
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
                        //
                        // ── 二级披露：证据位折进「更多明细」────────────────────────
                        //
                        // ⚠️ **v0.93.0**：「行驶耗电 / 充入电量 / 行程·充电次数」是**证据**，
                        // 不是结论 —— 主角已经是里程、副标题已经是平均能耗。
                        // 现在它们折进一个折叠入口，常驻版面只剩「结论 + 一行费用」。
                        //
                        // ⚠️ **v0.71.0**：`CollapsibleSection` 的内容 lambda **不是 ColumnScope**
                        // ⇒ 多个 composable 必须显式包一层 `Column { }`，
                        // 否则它们会**叠印在同一位置**（屏幕上只看得见最后一行）。
                        //
                        // ⚠️ 空态（今天没开车）也保留这个入口 ——
                        // 「充入电量」可能非零（只充电没开车），那仍然是用户要看的数。
                        CollapsibleSection("更多明细") {
                            Column {
                                KV(
                                    "行驶耗电",
                                    // ⚠️ **v0.48.0**：真的没有数据时给「—」，不是「0.0 度」。
                                    if (sum.drive_count == 0) "—" else "${sum.kwh_used.fmt(1)} 度",
                                )
                                // ⚠️ v0.14.0（单位审校 P1-1 / P1-3）：
                                // 「行车用电」→「行驶耗电」（与下面图表同名）——
                                // 原来同一个量在汇总卡叫「行车用电」、在图表叫「行驶耗电」，
                                // 用户会以为是两个量。
                                // 「总里程」→「行驶里程」——
                                // 「总里程」在中文里会被读成「这车一辈子跑了多少」。
                                //
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
                                KV("行程 / 充电次数", "${sum.drive_count} 次 / ${sum.charge_count} 次")
                                // ⚠️ **v0.96.0**：费用**证据行**也折在这里 ——
                                // 「实际每度电」是这辆车**充一度电的均价**，
                                // 「电费每度 / 服务费每度」是它的**拆解**（两者相加 = 实际每度电）。
                                //
                                // ⚠️ **v0.30.0（第 19 轮财务评审 P1-1）：「实际每度电」必须拆开。**
                                //
                                // 实测全周期 1.7689 元/度里，**0.5874（33.2%）是公共桩服务费**。
                                // 车主拿「实际每度电」和自己的居民电价 0.53~0.83 一比，
                                // 会以为「我的电费高了三倍」—— 却不知道其中 1/3 是停车/占位费。
                                // 现在拆成两行：电费每度 / 服务费每度。
                                //
                                // ⚠️ 没配电价时**不显示**这三行（算出来是 0 ⇒ 「¥0.00」的假数）。
                                clientCost?.let { c ->
                                    c.yuanPerKwh?.let {
                                        KV("实际每度电", "≈ ${it.fmtYuan()}")
                                    }
                                    c.energyYuanPerKwh?.let {
                                        KV("电费每度", "≈ ${it.fmtYuan()}")
                                    }
                                    c.serviceYuanPerKwh?.let {
                                        KV("服务费每度", "≈ ${it.fmtYuan()}")
                                    }
                                }
                            }
                        }
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
                            // ⚠️ **v0.151.0**：同样给出**可见标签**（实测实测它在 x=16，
                            // 是一枚孤零零的 ⓘ，不知道自己解释的是哪一行）。
                            label = "行驶耗电的口径",
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

                        // ⚠️ **v0.90.0（第 32 轮 CDO UI Polish）：这张卡重排过 —— 一个主角，其余二级披露。**
                        //
                        // ## 原来长什么样
                        //
                        // **7 行等权 KV**：现在还能装 / 历史最好时 / 新电池标称可用容量 /
                        // 电池健康度（相对出厂）/ 近期趋势 / 满电标定续航（现在）/（出厂折算）。
                        // 缩略到 200px 宽、灰度化后**指不出唯一的主角** ——
                        // 这正是使命书 §2.1 点名的反面教材（表显续航 / 额定续航 / 可用容量 /
                        // 压差 / 电池温度 / 循环次数）：工程指标一视同仁地铺开，没有落点。
                        //
                        // ## 现在
                        //
                        // - **表层**：一个主角（电池健康度）+ 一行副标题（还能装多少度）
                        //   + 趋势图 + 一句结论；
                        // - **二层**：「更多明细」折叠区（4 行）+「怎么算的」折叠区（原有）。
                        //
                        // ⚠️ **主角用 26sp 而不是 44sp**：本页的主角是顶部汇总卡的 44sp 里程，
                        // 而本仓库的硬标准是「两个锚点拉开 ≥25% 字号差」——
                        // 44:34 = **1.29×** 正是 v0.35.0 明确判为「缩略后分不出主次」的比例。
                        // 同型先例（RangeSegments / DriveScoreCard / StayPointsCard）都用 26sp。
                        //
                        // ⚠️ **不许自造分级**：原来的注解写「新车即约 95%，低于 90% 才需要关注」——
                        // 那个 95% **全项目找不到任何来源**。本轮一个阈值也不新增，
                        // 只用服务端已经给出的三个事实：健康度%、相对首测的变化量、衰减结论强度。
                        //
                        // ⚠️ **分母口径的老账**（v0.22.0 P0-2 / v0.62.0 第 31 轮出厂态核查）：
                        // `nominal_capacity_kwh` 存的是**出厂可用容量**，
                        // 分子（实测可用容量）分母（出厂可用容量）**同一口径**，
                        // 比值就是**电池健康度（SOH）**，一个标准指标。
                        // 旧名「占铭牌」恰恰把它说成了别的意思，
                        // 还让车主以为自己读到的是「不是衰减」——
                        // 于是 84.9% 会被读成「还有 84.9%」而不是「衰减了 15%」。
                        val strictMax = b.max_capacity_kwh_strict ?: b.max_capacity_kwh
                        val nominalKwh = b.nominal_capacity_kwh
                        // ⚠️ v0.22.0（P1-5）：**历史最好时用严格档** ——
                        // 严格档只取**充到 100%** 的测量点。
                        // 原来取的是全部「≥90%」的点里的最大值，
                        // 而 e_soc 90~98 的点**系统性偏高 0.56%**
                        // （从 94% 线性外推到 100% 会高估 —— 车机的续航-电量曲线顶部是平的）。
                        // 实测那个 66.7 度正是一个 e_soc=96 的点。
                        val baselineDate = b.series.orEmpty()
                            .take(3)
                            .sortedBy { it.capacity_kwh }
                            .getOrNull(1)?.date?.fmtDate()
                        val latestFullPoint = b.series.orEmpty()
                            .filter { it.end_soc != null && it.end_soc!! >= 100 && it.rated_range_km != null }
                            .maxByOrNull { it.date }
                        val nowRatedKm = latestFullPoint?.rated_range_km
                        val factoryKm = b.factory_rated_range_km

                        // ── 表层：一个主角 + 一行副标题 ──────────────────────────
                        val health = b.health_percent
                        if (health != null) {
                            BigStat(
                                "${health.fmt(1)}%",
                                "电池健康度（相对出厂）",
                                mid = true,
                            )
                            Spacer(Modifier.height(2.dp))
                            // ⚠️ **副标题给的是「还能装多少度」** ——
                            // 百分比是**比值**，单看没有锚点；车主真正关心的是「还能装多少电」。
                            // 「现在还能装 65.2 度 · 新电池标称 78.4 度」——
                            // 两个数一摆出来，83.1% 自己就会算了。
                            //
                            // ⚠️ **v0.68.0（R32 真机复核）**：口径 ⓘ 挂在**它解释的那一行行尾**
                            // —— 不再在卡底堆三个一模一样的裸 ⓘ：
                            // 真机上三个角标竖排在一起，谁也说不清哪个解释哪个指标。
                            // 现在 ⓘ 挂在副标题上，而副标题字面就是这条口径的两个操作数。
                            TextWithNote(
                                // ⚠️ **v0.125.0**：副标题带「哪一次充电测的」（时间诚实性）。
                                //    文案由服务端生成，App 只透传（ADR #41）。
                                text = batteryCapacitySubtitle(
                                    b.current_capacity_kwh,
                                    nominalKwh,
                                    b.measurement?.note_short,
                                ),
                                // ⚠️ **v0.99.0**：口径正文搬进 `ui/BatteryNote.kt` 的纯函数。
                                //
                                // 为什么搬：这条 ⓘ 里有**插值的数字**（那个百分比），
                                // 静态扫描看不见它们 ⇒ 只有运行时单测才量得到真宽度。
                                // 详见 `BatteryNote.kt` 的说明与 `BatteryNoteTest.kt`。
                                note = noteBatteryHealth(strictMax, nominalKwh),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                a11yLabel = "电池健康度的口径",
                            )
                        } else {
                            Text(
                                "暂无「电池健康度」（未配置出厂容量，服务端不给分母）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // ⚠️ **v0.90.0**：口径 ⓘ **仍然是贴着它解释的那一行**（v0.68.0 起）：
                        // 健康度口径 → 副标题；趋势口径 → 「更多明细」里的「近期趋势」行；
                        // 续航口径 → 「更多明细」里的「满电续航（现在）」行。
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
                            // 基准值由上面副标题里的「新电池标称」承担。
                            LineChart(
                                values = series,
                                color = MaterialTheme.colorScheme.secondary,
                                yUnit = "度",
                                xLabels = bucketAxisLabels(b.series.orEmpty().map { it.date.fmtDate() }),
                                contentDescription = batteryChartDescription(b),
                            )
                            // ⚠️ **v0.74.0（模拟器实测）：拆两行。**
                            //
                            // 原来一行「可用容量（度）· 纵轴已放大到数据范围 · 仅 83 天 / 9936 公里」
                            // —— 单位、纵轴说明、覆盖范围全挤在一行，折行后读不出层次。
                            // 现在：第 1 行说**画的是什么**，第 2 行说**怎么读 + 数据覆盖到哪**。
                            Text(
                                "可用容量（度）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                // ⚠️ v0.22.0（P0-3）：**说清数据覆盖范围** ——
                                // 电池曲线只覆盖车龄的一小段（实测本机 83 天 / 9,936 公里
                                // = 车龄的 10%），不说清楚的话，
                                // 车主会把那条下滑的线当成「电池的全部历史」。
                                buildString {
                                    append("纵轴已放大到数据范围")
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
                            // ⚠️ **v0.69.0（真机 UI 树实测）**：**结论常驻，其余折进 ⓘ**。
                            //
                            // 原先是**四段直接可见的灰字**：样本量 / 衰减结论 / 那次落差 / 容量口径，
                            // 合计 **101 个汉字** —— 真机滚到电池卡底部时占了半屏，
                            // 而其中三段都是「结论的依据」，不是结论本身。
                            //
                            // 现在只有结论（衰减速率）留在外面，
                            // 依据（样本 / 落差 / 口径）折进 ⓘ，点开在下面就地展开。
                            // 一段文字都没删，只是换了位置。
                            b.decay?.let { d ->
                                TextWithNote(
                                    text = BatteryDecayText.headline(d),
                                    note = BatteryDecayText.note(d),
                                    style = MaterialTheme.typography.labelSmall,
                                    a11yLabel = "电池衰减结论的依据",
                                )
                            }
                        }

                        // ⚠️ **v0.90.0：二级披露 —— 4 行明细折进「更多明细」。**
                        //
                        // 这 4 行是**结论怎么来的**，不是结论本身：
                        // 「历史最好时」是天花板、「近期趋势」是变化量、
                        // 「满电续航」是另一把尺子上的读数。
                        // 数据一点没丢 —— API 与 Hermes（飞书）侧全都还在
                        // （见 mcp/teslamate_mcp.py 的 teslamate_battery 工具）。
                        //
                        // ⚠️ **「近期趋势」为什么也折进来**：
                        // 它是 −1.6%，而服务端对衰减的判定是「与 0 区分不开」——
                        // 也就是说这个数**在统计上和 0 没法区分**。
                        // 放在表层，读者会先看到一个「−1.6%」再读到那句结论，
                        // 顺序正好反了（v0.74.0 的原则：**先给出结论，数字只当证据**）。
                        // 现在结论在表层、数字在折叠区，顺序对了。
                        // ⚠️ **v0.41.0（第 24 轮对抗性审计 ②③④）：完整推导也在这个折叠区里。**
                        //
                        // 服务端的 `caveat` / `method` 从 v0.35.0 起就在下发，
                        // 但客户端**从来没渲染过它们** ——
                        // 于是界面上留着的是客户端自己写的那句
                        // 「残差里大部分是充电时气温」（实测只有 12%）。
                        //
                        // 现在：口径提示与服务端判定**同一份**，
                        // 完整推导（气温系数、留一法、方差解释比例）
                        // 收进「更多明细」—— 数据一点没丢，但不占常驻版面。
                        //
                        // ⚠️ **v0.90.0**：原来它和「更多明细」是**两个**折叠入口，
                        // 两个入口之间会留下一段**说不清的空白**
                        // （收起的折叠区自己还占着高度），整张卡看着像多了一截。
                        // 本仓库的规矩是「默认态只留一个折叠入口」
                        // （见 RangeSegments v0.29.0 P1-9）⇒ 合成一个。
                        val decayDetail = b.decay?.let { BatteryDecayText.detailLines(it) }.orEmpty()
                        val hasDetails = strictMax != null ||
                            b.health_percent_of_baseline != null ||
                            nowRatedKm != null ||
                            decayDetail.isNotEmpty()
                        if (hasDetails) {
                            CollapsibleSection("更多明细") {
                                Column {
                                    strictMax?.let { KV("历史最好时", "${it.fmt(1)} 度") }
                                    // ⚠️ v0.22.0（P1-1）：**相对首测** ——
                                    // 这是**唯一不受** `cars.efficiency` 影响的健康指标
                                    // （分子分母同比例缩放）。
                                    // 它回答的才是车主真正想知道的那句：
                                    // 「电池比刚开始测的时候差了多少」。
                                    //
                                    // ⚠️ **v0.28.0（第 17 轮新手车主 P0-3）：必须带**首测日期**。**
                                    //
                                    // 原来只写「相对首测 98.4%」，用户会读成
                                    // 「新车容量到现在只剩 98.4%」——
                                    // 而首测时车**已经开了 4 个月**，分母**本身已经衰减过**。
                                    b.health_percent_of_baseline?.let {
                                        // ⚠️ **v0.63.0（R31 视觉复审 P1-1）**：**数字的形状本身就是误读入口**。
                                        //
                                        // 「98.4%」这个形状会被读成「电池还有 98.4%」——
                                        // 改名字不够，**必须换形状**：显示**变化量** −1.6%。
                                        val delta = 100.0 - it
                                        KV(
                                            "近期趋势（自 " + (baselineDate ?: "首测") + "）",
                                            (if (delta >= 0) "−" else "+") +
                                                kotlin.math.abs(delta).fmt(1) + "%",
                                            // ⚠️ **v0.68.0（R32 真机复核）**：口径 ⓘ 挂在
                                            // **它解释的那一行行尾**。
                                            //
                                            // ⚠️ **v0.99.0**：正文搬进 `ui/BatteryNote.kt`。
                                            // 原来这里重复了行标题里的日期「（2026-08-02）」，
                                            // 也重复了行本身的意思（「只回答『比那次低了多少』」）——
                                            // **同一个数说两遍 = 噪音**。
                                            note = noteRecentTrend(),
                                        )
                                    }
                                    if (nowRatedKm != null) {
                                        // ⚠️ **v0.62.0（第 31 轮电池出厂态核查）**：**满电续航**。
                                        //
                                        // 车主最关心的单位（「公里」）。
                                        //
                                        // ⚠️ **v0.63.0（R31 视觉复审 P0-2 / P1-5）**：原来把
                                        // 「现在 / 出厂折算 / 少多少」全塞进**一个值**里 ——
                                        // 29 个字符在 15sp 的值列里撑破布局。
                                        // 现在拆成**两行同名**（现在 / 按出厂折算），每个值都很短，
                                        // 「少多少」降级到下面的说明里。
                                        //
                                        // ⚠️ **v0.90.0**：标签去掉「标定」这个术语 ——
                                        // 「标定」是工程词，车主读不出它跟「续航」是什么关系；
                                        // 口径说明里已经写明它是「车机在满电时自己报的读数」。
                                        KV(
                                            "满电续航（现在）",
                                            "${nowRatedKm.toInt()} km",
                                            // ⚠️ **v0.99.0**：正文搬进 `ui/BatteryNote.kt`。
                                            // 原来这里写「按出厂容量折算，新电池应是 538 km」——
                                            // 而**下面那行**就写着「满电续航（按出厂折算）538 km」
                                            // ⇒ **同一个数说两遍 = 噪音**。
                                            note = noteFullChargeRange(
                                                latestFullPoint?.date?.fmtDate(),
                                                // ⚠️ **用「两行的显示值之差」**，不是原始值之差 ——
                                                // 下面那行显示 538 km、这行显示 448 km，
                                                // 用户一眼能验的差是 **90** ⇒ ⓘ 必须写同一个数。
                                                // 原来写 89（`(gapKm / 5.0 * 5.0).toInt()` 其实是 floor，
                                                // 不是「取整到 5」）⇒ 与自己下面两行自相矛盾。
                                                factoryKm?.let { fk ->
                                                    fk.toInt() - nowRatedKm.toInt()
                                                },
                                            ),
                                        )
                                        factoryKm?.let { fk ->
                                            KV("满电续航（按出厂折算）", "${fk.toInt()} km")
                                        }
                                    }
                                    if (decayDetail.isNotEmpty()) {
                                        // ⚠️ **v0.152.0**：这里原来是一行**平铺**的「怎么算的」标题
                                        // ＋ 15 行方法学 ⇒ 展开「更多明细」后是 **16 行**小字
                                        // （实测线上这辆车：caveat 12 段 ＋ 算法 1 行 ＋ 气温模型 1 行
                                        // ＋ 留一法 1 行 ＝ **15 行**）⇒ 而**同一份推导**在续航卡里
                                        // 是**折叠区** ⇒ 这块推导**连折叠入口都没有**：
                                        // 用户只能展开「更多明细」，再一路滚过那 15 行。
                                        //
                                        // ⚠️ **v0.90.0 当初为什么把它摊平**（那个决策是对的，别推翻）：
                                        // 当时「更多明细」与「怎么算的」是**两个并列**的折叠入口
                                        // ⇒ 收起态有两个入口、中间还留着一段**说不清的空白**
                                        // ⇒ 本仓库的规矩是「默认态只留一个折叠入口」⇒ **合成一个**。
                                        //
                                        // ⇒ 本轮用**嵌套**同时满足两边：
                                        // ① 收起态**仍然只有「更多明细」一个入口**
                                        //    （v0.90.0 的验收原话「收起态一个主角 + 一个折叠入口」不破）；
                                        // ② 「怎么算的」在**展开之后**才以**折叠区**出现 ⇒ 它终于有入口了；
                                        // ③ 展开「更多明细」后从 **16 行**变成 **4 行 ＋ 1 个入口**；
                                        // ④ 那 15 行方法学**一点没丢**（点开就在）；
                                        // ⑤ 形态与续航卡的「怎么算的」**一致**（同一个 `CollapsibleSection`）。
                                        //
                                        // ⚠️ 行距跟续航卡对齐（`Spacer(4.dp)` ＋ `CollapsibleSection`）。
                                        Spacer(Modifier.height(4.dp))
                                        CollapsibleSection("怎么算的") {
                                            // ⚠️ **v0.152.0（补）**：这里**必须有 Column** ——
                                            // `CollapsibleSection` 的内容槽是**单个槽位**，
                                            // 直接放若干个 Text 而不给容器 → 它们全部**叠在一起**（实测：展开后一块模糊）。
                                            // 原说法不需要 Column，因为它在**外层** Column 里。
                                            Column {
                                                decayDetail.forEach { line ->
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

