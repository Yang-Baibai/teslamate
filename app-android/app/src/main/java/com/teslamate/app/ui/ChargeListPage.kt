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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teslamate.app.Charge
import com.teslamate.app.fmt
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
) {
    // ⚠️ 分组必须在 LazyColumn **外面**算 ——
    // LazyListScope 的 content lambda **不是** @Composable 作用域，
    // 在里面调 remember 会编译报错。
    //
    // ⚠️ 与记录页的时间轴**同一套分组口径**（按月、倒序）——
    // 同一份数据在两个地方用两种分组方式会让用户对不上。
    val groups = remember(charges) { charges.groupBy { it.start_date.fmtMonth() } }

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
            item(key = "summary") {
                val totalKwh = charges.sumOf { it.energy_added_kwh ?: 0.0 }
                val dcList = charges.filter { it.is_dc }
                val acList = charges.filter { !it.is_dc }
                val dcKwh = dcList.sumOf { it.energy_added_kwh ?: 0.0 }
                val acKwh = totalKwh - dcKwh
                val dcCount = dcList.size
                val acCount = acList.size
                SectionCard(modifier = Modifier.animateItem()) {
                    BigStat(
                        totalKwh.fmt(0),
                        "度 · 共 ${charges.size} 次充电",
                        hero = true,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        InlineMetric("直流", "${dcKwh.fmt(0)} 度 · $dcCount 次")
                        InlineMetric("交流", "${acKwh.fmt(0)} 度 · $acCount 次")
                    }
                    // ⚠️ v0.22.0（第 13 轮充电专家 P1-4）：**补上时长拆分** ——
                    // 场次与度数只说「充了多少」，不说「每次要待多久」；
                    // 而「每次要待多久」才是决定「要不要在这里充」的量。
                    //
                    // 实测本车：直流平均 36 分钟、交流平均 **7 小时** ——
                    // 这两个数放在一起，用户才看得出「我主要在外面快充，
                    // 家里的桩只是过夜补电」。
                    val dcMin = dcList.mapNotNull { it.duration_min }.takeIf { it.isNotEmpty() }
                    val acMin = acList.mapNotNull { it.duration_min }.takeIf { it.isNotEmpty() }
                    if (dcMin != null || acMin != null) {
                        Text(
                            buildString {
                                if (dcMin != null) {
                                    append("直流平均 ")
                                    append(dcMin.average().roundToInt())
                                    append(" 分钟")
                                }
                                if (acMin != null) {
                                    if (isNotEmpty()) append(" · ")
                                    append("交流平均 ")
                                    append(formatHours(acMin.average()))
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
                    // ⚠️ 判据是 `end_soc >= 100`（**真正充满**）——
                    // 半途拔枪的场次不算，那只是路过补一点。
                    // ⚠️ 没有充满过 ⇒ **不给这一行**（不给数），不编。
                    lastFullCharge(charges)?.let { last ->
                        Text(
                            "上次充满：" + last.start_date.fmtShort() + "，" +
                                (last.start_soc?.let { "从 $it% " } ?: "") +
                                "到 ${last.end_soc ?: 100}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (charges.isNotEmpty()) {
                        Text(
                            // 说清数据范围，避免「共 N 次」被误解成全部历史。
                            "${charges.minOf { it.start_date }.fmtMonth()} 至 ${charges.maxOf { it.start_date }.fmtMonth()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
 * ⚠️ **v0.51.0（第 28 轮通勤车主评审 P1-3）**：**上一次真正充满**的那一场。
 *
 * 「充满」的判据是 `end_soc >= 100` —— 不是「充过一次」，
 * 半途拔枪的场次不算（那只是路过补一点）。
 *
 * 真实数据（本机 69 场）：上一次充满是 **10-24 13:53，从 68% 到 100%**。
 *
 * 为什么它有用：通勤者/家充用户打开充电页，问的是
 * 「**上次充满是什么时候**」「**充满一次要多少度**」，
 * 而不是「这个桩最慢一次」—— 后者是**长途用户**在服务区才关心的问题。
 *
 * ⚠️ 没有充满过（`end_soc` 全 < 100）⇒ 返回 null ⇒ 界面**不给数**，
 * 而不是编一个「上次充满」。
 */
fun lastFullCharge(charges: List<Charge>): Charge? =
    charges.filter { (it.end_soc ?: 0) >= 100 }.maxByOrNull { it.start_date }

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
