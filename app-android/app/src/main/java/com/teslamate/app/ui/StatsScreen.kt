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
import com.teslamate.app.fmt
import com.teslamate.app.fmtDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 统计：周/月/年 里程、能耗、充电、电池健康 */
@Composable
fun StatsScreen(settings: AppSettings, modifier: Modifier) {
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
    var error by remember { mutableStateOf<String?>(null) }
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
    suspend fun load() {
        if (api == null) {
            error = if (settings.configured) "服务器地址或令牌无效" else "尚未配置服务器，请到「设置」页"
            return
        }
        runCatching {
            withContext(Dispatchers.IO) {
                Pair(api.stats(settings.carId, apiPeriod), api.battery(settings.carId))
            }
        }.onSuccess {
            stats = it.first
            battery = it.second
            loadedPeriod = periodIndex
            error = null
        }.onFailure { error = it.message }
    }

    LaunchedEffect(settings, apiPeriod) { load() }


    Column(modifier) {
        TopAppBar(
            // v0.8.5：Tab 改名「用量」，页面标题跟着改 —— 这一页装的是**量**
            //（里程/能耗/充入量），而「记录」页装的是**事件**。
            title = { ScreenTitle("用量") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
            "里程 · 能耗 · 充入电量",
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
                    ErrorBanner(
                        error!!,
                        actionLabel = "重试",
                        onAction = {
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

            // ⚠️ v0.8.5：服务端的 summary 是**整个窗口**的汇总，切段后不适用。
            // 现在统一用切片后的 buckets 自己汇总 —— 这样「今天」段显示的
            // 确实是今天的数，而不是「把今天塞进 30 天窗口里算的平均」。
            val buckets = s.buckets.takeLast(bucketCount)
            val sum = summarizeBuckets(buckets)
            val periodLabel = periods[periodIndex].first
            item {
                SectionCard("$periodLabel 汇总", Modifier.animateItem()) {
                    if (sum != null) {
                        KV("总里程", "${sum.km.fmt(1)} km")
                        KV("行车用电", "${sum.kwh_used.fmt(1)} 度")
                        KV("充入电量", "${sum.charge_kwh.fmt(1)} 度（快充 ${sum.dc_kwh.fmt(1)} / 慢充 ${sum.ac_kwh.fmt(1)}）")
                        KV("充电费用", if (sum.cost > 0) "${sum.cost.fmt(2)} 元" else "未记录")
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
                        // 现在合成一行：说明没数据 + 说清怎么才能有数据。
                        if (sum.cost > 0 && sum.km > 0.1) {
                            KV(
                                "每公里成本",
                                // ⚠️ 分子分母不同源：分子只含「被记录了费用」的充电，
                                // 分母含全部里程（含家充跑的公里）→ 系统性偏低。
                                // 所以必须写明覆盖度，不能当成一个干净的数。
                                "${(sum.cost / sum.km).fmt(2)} 元（仅含已记录电费的充电）",
                            )
                        } else {
                            // ⚠️ v0.14.0（新手车主 P0-4）：**只留一行**。
                            // 两行「没有」比一行更让人烦，而且说的是同一件事。
                            // 「每公里成本」这一行在有电费数据时才会出现。
                            KV("充电费用", "未记录 —— 在 TeslaMate 网页端手填单价后才有")
                        }
                        sum.wh_per_km?.let { KV("平均能耗", "${it.fmt(0)} Wh/km") }
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
                SectionCard("行驶里程（km）", Modifier.animateItem()) {
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
                    ChartHint(buckets.map { it.km to it.start }, "km", avgLabel, avgUnit)
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
                                // 现在用**能量紫**（与蓝、灰都不同相），
                                // 语义上也对：耗电是能量流出，直流快充是能量蓝，
                                // 交流慢充是中性灰 —— 三张图三个色相。
                                color = MaterialTheme.colorScheme.tertiary,
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
                    if (b == null || b.current_capacity_kwh == null) {
                        Text("暂无足够数据（需要 ≥90% 的完整充电记录）", style = MaterialTheme.typography.bodySmall)
                    } else {
                        // ⚠️ v0.9.0（新手车主复核）：「铭牌容量」是术语。
                        // 车主原话：「'铭牌'是出厂标称；健康度＝还剩多少」，
                        // 建议写法：「新电池能装 66 度，现在还能装 65.4 度（97%）」。
                        KV("现在还能装", "${b.current_capacity_kwh?.fmt(1)} 度")
                        KV("历史最好时", "${b.max_capacity_kwh?.fmt(1)} 度")
                        b.nominal_capacity_kwh?.let { KV("新电池标称", "${it.fmt(1)} 度") }
                        // ⚠️ v0.9.2（口径审计 S1-1）：服务端删掉了「没配铭牌容量就
                        // 回退到历史最大值」的兜底 —— 那会让健康度静默从 85% 跳到 ~100%
                        // （「当前/历史最大」这个比值永远接近 100%）。
                        // 现在健康度是 null，界面必须**说清为什么没有这个数**，
                        // 而不是让两行一起消失、用户以为是 App 的 bug。
                        if (b.health_percent != null) {
                            KV("健康度", "${b.health_percent!!.fmt(1)}%")
                        } else {
                            Text(
                                "暂无健康度（未配置铭牌容量，服务端不给分母）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // ⚠️ v0.9.2（口径审计 S3-2）：这个百分比的分子是**可用容量**、
                        // 分母是**总（铭牌）容量** —— 一台全新车跑同一套算法会显示约 95%，
                        // 不是 100%。所以它不是「衰减了多少」，而是「可用容量占铭牌多少」。
                        // 不写清楚会被读成「电池衰减了 15%」。
                        Text(
                            "「健康度」= 可用容量 ÷ 铭牌容量；新车即约 95%，低于 90% 才需要关注",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                                "可用容量（度）· 纵轴已放大到数据范围",
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
            "$avgLabel ${avg.fmt(0)} $unit（有记录 ${nonZero.size} $avgUnit）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
