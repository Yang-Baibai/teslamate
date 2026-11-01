package com.teslamate.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teslamate.app.Charge
import com.teslamate.app.ChargeStats
import com.teslamate.app.ChargeStatsRange
import com.teslamate.app.fmt
import com.teslamate.app.fmtDay
import com.teslamate.app.fmtMonth
import com.teslamate.app.fmtShort
import kotlin.math.roundToInt

/**
 * ⚠️ **v0.22.0（第 13 轮充电数据专家 P1-1）：充电记录列表页**。
 *
 * ## 为什么之前没有这个页面
 *
 * `ChargesScreen.kt` 里的 `ChargesScreen` / `ChargeList` 从 v0.8.5 起就是**死代码**
 * （顶层 Composable 从未被调用）—— 但里面的 `ChargeItem`（列表行）与
 * `ChargeDetail`（详情 + 曲线）都是**活的**（记录页时间轴在用）。
 *
 * 结果是：用户在 App 里**看不到任何充电记录的列表** ——
 * 充电只作为记录页时间轴里的一个节点出现，
 * 而「我一共充过多少次、都在哪充的、这个桩快不快」这些问题无处可答。
 *
 * 数据其实早就在客户端手里（`RecordsScreen` 已经拉了 `api.charges(carId, 300)`），
 * 只是没有页面去展示它。
 *
 * ## 导航设计（避免嵌套 AnimatedContent）
 *
 * 这个页面**不是**在记录页里再嵌一层 `AnimatedContent`，而是作为
 * `RecordSelection` 的一个新变体，由记录页**顶层那一个** `AnimatedContent` 统一调度：
 *
 * ```
 * null                    → 时间轴（根）
 * RecordSelection.ChList  → 充电记录列表
 * RecordSelection.Ch      → 充电详情（曲线）
 * RecordSelection.Dr      → 行程详情
 * ```
 *
 * 所以**永远只有一层** `AnimatedContent` —— 这正是当初把充电页搁置的那个风险点，
 * 现在从架构上绕开了，而不是靠小心避开。
 */
@Composable
fun ChargeListPage(
    charges: List<Charge>,
    stats: Map<String, StationStat>,
    onSelect: (Charge) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** ⚠️ v0.25.0：车主填的电价 —— 有就显示估算费用。 */
    rates: com.teslamate.app.ChargeRates? = null,
    /**
     * ⚠️ **v0.108.0（用户反馈 #27）**：充电统计的**三个区间**（服务端算）。
     *
     * 为 null = 没拿到（服务端旧版本 / 这次请求失败）⇒ 卡片**明说**没加载出来，
     * **不在客户端自己算一套顶替**（那会变成第二个口径）。
     */
    chargeStats: ChargeStats? = null,
    /** 统计没拿到时的重试（重新拉一次记录页数据）。 */
    onRetryStats: () -> Unit = {},
) {
    // 分组必须在 LazyColumn **外面**算 ——
    // LazyListScope 的 content lambda **不是** @Composable 作用域，
    // 在里面调 remember 会编译报错。
    //
    // ⚠️ 与记录页的时间轴**同一套分组口径**（按月、倒序）——
    // 同一份数据在两个地方用两种分组方式会让用户对不上。
    val groups = remember(charges) { charges.groupBy { it.start_date.fmtMonth() } }

    // ⚠️ **v0.108.0（用户反馈 #27）**：统计区间的选择状态放在**页面级**，
    // 不放在列表项里 —— 列表项滚出屏幕就会被 dispose ⇒ 状态会丢
    // （用户切到「近 7 天」再往下滚一屏，回来发现又变回「全部」）。
    var rangeKey by rememberSaveable { mutableStateOf(chargeRangeKeys.last()) }

    Column(modifier) {
        SecondaryTitleBar(title = "充电记录", backLabel = "记录", onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── 汇总卡 ───────────────────────────────────────
            // ⚠️ 充电专家 P1-1：主角数字用**度数**（与用量页「充入电量」同口径同色），
            // 副行**拆两栏**直流/交流 —— 两者**不混算**：
            // 本车 97% 的充入电量来自直流，混在一起说「充了 1,447 度」没有信息量。
            //
            // ⚠️ **v0.108.0（用户反馈 #27）**：这张卡能**切区间**
            // （近 7 天 / 近 30 天 / 全部）——
            // 用户原话：「充电记录顶上这个统计，需要可以手动切换范围」。
            //
            // 数字**全部来自服务端**（`/charge_stats`），客户端一个都不自己算：
            //  ① **区间口径只有一份**（与用量页 `/stats` 同源：今天 00:00 往前 N−1 天）；
            //  ② **「全部」是真全表** —— 不是客户端那份有上限的列表（那是静默截断的历史）；
            //  ③ **与下面的列表同源**（同表、同过滤、同会话级直交流判据）
            //     ⇒「共 N 次」与列表里的行对得上。
            //
            // ⚠️ 原来这里是**客户端算**的（`charges.sumOf { ... }`）—— 现在删掉：
            // 同一件事只能有一处算法，否则两处迟早漂。
            item(key = "summary") {
                val statsData = chargeStats
                val range = statsData?.ranges?.firstOrNull { it.key == rangeKey }
                    ?: statsData?.ranges?.firstOrNull { it.key == chargeRangeKeys.last() }
                if (statsData == null || range == null) {
                    // 拿不到就**明说**（不编，也不退回客户端自己算）。
                    SectionCard(modifier = Modifier.animateItem()) {
                        Text(
                            "充电统计没加载出来",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        TextButton(onClick = onRetryStats) { Text("重试") }
                    }
                } else {
                    SectionCard(modifier = Modifier.animateItem()) {
                        // ⚠️ 区间切换控件放在**卡里**（而不是页面顶栏）：
                        // 它的作用域就是这张卡的统计 ⇒ 位置本身就说清了
                        // 「下面那张列表仍然是全部历史」。
                        SegmentedTabs(
                            labels = chargeRangeLabels,
                            selectedIndex = chargeRangeIndex(rangeKey),
                            onSelect = { rangeKey = chargeRangeKeys[it] },
                        )
                        Spacer(Modifier.height(12.dp))
                        BigStat(
                            range.total_kwh.fmt(0),
                            "度 · 共 ${range.count} 次充电",
                            hero = true,
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            InlineMetric("直流", "${range.dc_kwh.fmt(0)} 度 · ${range.dc_count} 次")
                            InlineMetric("交流", "${range.ac_kwh.fmt(0)} 度 · ${range.ac_count} 次")
                        }
                        // ⚠️ v0.22.0（第 13 轮充电专家 P1-4）：**补上时长拆分** ——
                        // 场次与度数只说「充了多少」，不说「每次要待多久」；
                        // 而「每次要待多久」才是决定「要不要在这里充」的量。
                        //
                        // 实测本车：直流平均 36 分钟、交流平均 **7 小时** ——
                        // 这两个数放在一起，用户才看得出「我主要在外面快充，
                        // 家里的桩只是过夜补电」。
                        //
                        // ⚠️ v0.108.0：平均值由**服务端**算（区间内同一批会话），
                        // 没有样本时是 null ⇒ **不给这一行**（不拿 0 充数）。
                        val dcAvg = range.dc_avg_min
                        val acAvg = range.ac_avg_min
                        if (dcAvg != null || acAvg != null) {
                            Text(
                                buildString {
                                    if (dcAvg != null) {
                                        append("直流平均 ")
                                        append(dcAvg)
                                        append(" 分钟")
                                    }
                                    if (acAvg != null) {
                                        if (isNotEmpty()) append(" · ")
                                        append("交流平均 ")
                                        append(formatHours(acAvg.toDouble()))
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // ⚠️ **v0.51.0（第 28 轮通勤车主评审 P1-3）**：「上次充满」。
                        //
                        // 家充为主的用户打开充电页，问的是「上次充满是什么时候」，
                        // 而不是「这个桩最慢一次」（那是长途用户在服务区才问的）。
                        //
                        // ⚠️ v0.108.0：判据（**真的充到 100%**）与取哪一场，
                        // 都由服务端算（区间内最近的那场充满）——
                        // 客户端原来那份实现（`lastFullCharge`）已删，
                        // 免得同一个事实有两处算法。
                        range.last_full?.let { last ->
                            Text(
                                "上次充满：" + last.date.fmtShort() + "，" +
                                    (last.from_soc?.let { "从 $it% " } ?: "") +
                                    "到 ${last.to_soc ?: 100}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 区间边界：说清「这组数说的是哪段时间」。
                        chargeStatsWindowLine(range)?.let { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            // ⚠️ **v0.110.0**：列表**只加载最近 N 条**（上限 300），
            // 而卡片上的「全部」是**服务端全表** ⇒ 两者可能对不上（车充得多了就会）。
            //
            // ⇒ 诚实地把上限说出来（**不另算一套口径**）：
            // 比服务端的总场次少时才显示这一行。
            // （并行会话的规格：「别让客户端再数一遍」。）
            val totalCharges = chargeStats?.ranges?.firstOrNull { it.key == chargeRangeKeys.last() }?.count
            if (totalCharges != null && charges.size < totalCharges) {
                item(key = "list-cap") {
                    Text(
                        "只列最近 ${charges.size} 条（共 $totalCharges 次）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ── 按月分组 ─────────────────────────────────────
            groups.forEach { (month, list) ->
                item(key = "h-$month") {
                    Text(
                        month,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp).animateItem(),
                    )
                }
                items(list, key = { it.id }) { c ->
                    val stat = stats[stationKey(c)]
                    ChargeItem(c, stat, Modifier.animateItem(), rates = rates) { onSelect(c) }
                }
            }
        }
    }
}

/**
 * 统计区间的三个键（与服务端 `/charge_stats` 一致）。
 *
 * ⚠️ **v0.108.0**：顺序 = 分段控件从左到右的顺序。
 */
val chargeRangeKeys = listOf("7d", "30d", "all")

/**
 * 分段控件的标签。
 *
 * ⚠️ **为什么是「7 天 / 30 天 / 全部」而不是「近 7 天 / 近 30 天 / 全部」**
 * （用户原话是「近7天，近30天，全部」）——
 *
 * 200% 字号下每段只有 ≈97dp（360dp 屏 − 页面内边距 32dp − 卡片内边距 28dp
 * − 控件内边距 4dp − 段间间隙 4dp）÷ 3：
 *
 *  · 「30 天」= 2.54em × 28sp ≈ **71dp** ⇒ 放得下；
 *  · 「近 30 天」= 3.84em × 28sp ≈ **108dp** ⇒ **放不下**，会被省略号截成「近 30…」。
 *
 * 而用量页的档位本来就是这套短标签（「今天 / 7 天 / 本月 / 1 年」）
 * ⇒ 全 App 一套词，用户不用记两套。
 */
val chargeRangeLabels = listOf("7 天", "30 天", "全部")

/**
 * 当前选中的区间在分段控件里的下标。
 *
 * ⚠️ 认不出来时落在「全部」（最保守：不会凭空少报数据）。
 */
fun chargeRangeIndex(key: String): Int {
    val i = chargeRangeKeys.indexOf(key)
    return if (i < 0) chargeRangeKeys.indexOf(chargeRangeKeys.last()) else i
}

/**
 * 区间边界那一行（说清「这组数说的是哪段时间」）。
 *
 * ⚠️ **v0.108.0**：边界由**服务端**给（`window_start` / `window_end`），
 * 客户端不自己算窗口 —— 两处各算一遍，边界迟早漂。
 *
 * 两种区间说的事不一样，但都是「这段数覆盖哪段时间」：
 *
 *  · 近 N 天 ⇒ **窗口**的起止（按天，不带时分秒）；
 *  · 全部 ⇒ **数据自身**的首尾（与旧卡片那行「2026年7月 至 2026年10月」一致）。
 */
fun chargeStatsWindowLine(range: ChargeStatsRange): String? {
    val s = range.window_start
    val e = range.window_end
    if (s == null || e == null) return null
    return if (range.days > 0) {
        s.fmtDay() + " 至 " + e.fmtDay()
    } else {
        s.fmtMonth() + " 至 " + e.fmtMonth()
    }
}

/**
 * 分钟数 → 人话（「36 分钟」/「7 小时」/「7 小时 12 分」）。
 *
 * ⚠️ 交流慢充动辄 7 小时 —— 写成「436 分钟」没人读得出来。
 */
private fun formatHours(minutes: Double): String {
    val h = (minutes / 60.0).toInt()
    val m = minutes.roundToInt() % 60
    return if (h <= 0) "$m 分钟" else if (m == 0) "$h 小时" else "$h 小时 $m 分"
}

/**
 * 充电行里的「该站历史」一行：**只充过 1 次也要显示**。
 *
 * ⚠️ v0.22.0（第 13 轮充电专家 P1-1）：
 * 原来门槛是 `sessions >= 2` —— 而「只充过 1 次」恰恰是**长途新服务区的常态**。
 * 出发前第一次去一个新服务区，最需要提示的就是「这个桩大概多快」，
 * 结果界面上一个字都没有。
 *
 * 代价是「典型」这个词在没有统计支撑时不成立 ——
 * 所以 1 次的时候改说「实测」，并标「仅 1 次」。
 */
internal fun stationSummaryLine(stat: StationStat, fromSoc: Int? = null): String {
    if (stat.sessions >= 2) return stat.summary(fromSoc)
    return "该站实测 ${stat.typicalPowerKw.roundToInt()} kW（仅 1 次）"
}
