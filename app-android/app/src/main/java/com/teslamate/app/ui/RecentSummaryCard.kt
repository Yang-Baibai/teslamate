package com.teslamate.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teslamate.app.AppSettings
import com.teslamate.app.Stats
import com.teslamate.app.StatsBucket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 顶部 30 天汇总条的加载态（骨架条） */
@Composable
private fun SummarySkeleton() {
    SectionCard {
        SkeletonBox(
            Modifier
                .fillMaxWidth()
                .height(20.dp),
        )
        SkeletonBox(
            Modifier
                .fillMaxWidth()
                .height(20.dp),
        )
    }
}

/** 顶部汇总条里的一格 */
@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 列表页顶部的「最近 30 天」统计框。
 *
 * 口径：调用 /cars/{id}/stats?period=month（服务端聚合最近 30 个自然日，
 * 按 Asia/Shanghai 本地日界切桶），取其 summary。
 * 这与「统计」页的"月"是同一份数据，保证两处数字一致。
 *
 * fallback：stats 还没加载出来/失败时，先显示调用方给的旧汇总，不让顶部空着。
 */
@Composable
fun RecentSummaryCard(
    settings: AppSettings,
    fallback: @Composable () -> Unit,
    /**
     * ⚠️ **v0.47.0（第 28 轮通勤车主评审 P0-3）**：最近 30 天里有行程的**天数**。
     *
     * 为 null 时退回「行程次数」那一格（拿不到行程列表就不给数）。
     */
    driveDays: Int? = null,
) {
    var stats by remember { mutableStateOf<Stats?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(settings.serverUrl, settings.carId) {
        val api = settings.api() ?: return@LaunchedEffect
        stats = runCatching {
            withContext(Dispatchers.IO) { api.stats(settings.carId, "month") }
        }.getOrElse {
            failed = true
            null
        }
    }

    val s = stats?.summary
    when {
        s == null && !failed -> SummarySkeleton()
        s == null -> fallback()
        else -> SummaryContent(s, driveDays)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SummaryContent(s: StatsBucket, driveDays: Int?) {
    SectionCard("最近 30 天") {
        // ⚠️ **v0.113.0**：这四格原来**列数写死成两列**（两个 Row + 四个 `weight(1f)`）
        // ⇒ 200% 字号下「3834.6 公里」要 **213dp**，而每格只有 **148dp**（296dp ÷ 2）
        // ⇒ 被折成「3834.6」+「公里」（**数与单位分离**，ADR #42 的老毛病）。
        //
        // ⇒ 现在**列数由宽度预算决定**（`summaryMetricColumns`）：
        //    放得下 ⇒ 两列（**视觉与原来一模一样**）；放不下 ⇒ 单列（每格独占整宽 296dp）
        //    ⇒ **换布局，不换文案**（ADR #39）。
        //
        // ⚠️ 文案与宽度预算在 `ui/SummaryMetrics.kt`（唯一事实源），这里只负责摆版。
        val metrics = summaryMetrics(s, driveDays)
        val columns = summaryMetricColumns(metrics.map { it.value }, LocalDensity.current.fontScale)
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (columns >= 2) {
                metrics.chunked(columns).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        pair.forEach { m -> Stat(m.label, m.value, Modifier.weight(1f)) }
                    }
                }
            } else {
                metrics.forEach { m -> Stat(m.label, m.value) }
            }
        }
        // ⚠️ **v0.104.0（真机实测的折行缺陷）**：这两条指标原来**并排**在一个 `Row` 里
        // （左「平均能耗 …」+ 右「直流快充 …」+ 中间 weight(1f) 的 Spacer），
        // 两条加起来需要约 **353dp**，而卡片内容区在最窄的常见屏（360dp）上只有 **296dp**
        // ⇒ 放不下 ⇒ 右边那串被折行劈成「…（按充入电」+「量）」，
        // Spacer 同时被挤成 0 ⇒ 两串贴死，读起来像「平均能耗 17.4 kWh/100km直流快充 93%（…）」。
        //
        // ⇒ **版面修法**（三条结构性结论，别再用「刚好放得下」解决）：
        //   ① 两条指标**上下堆叠**，每条独占整宽；
        //   ② 口径限定语「（按充入电量）」**独立成 Text 节点** ——
        //      CJK 允许在任意两个汉字之间断行，同一段落里就会被劈开；
        //   ③ 这两段用 **FlowRow** 排（`docs/adr.md` 第 28 条点名的机制：
        //      **放不下就换行，绝不截断**）：
        //      1× 放得下 ⇒ 同一行，视觉与原来一模一样；
        //      200% 放不下 ⇒ 限定语**整体**换到下一行 ⇒ 两个整块都不劈开。
        //
        //      ⚠️ 为什么不能只靠 `Row` + `weight(1f, fill = false)`：
        //      200% 时限定语要 182dp ⇒ 数字那半只剩 114dp，
        //      而「直流快充 93%」要 158dp ⇒ 数字那半自己换行
        //      ⇒ 「直流快充」这个**整词**被劈成「直流」+「快充 93%」。
        //
        // ⚠️ 文案与宽度预算在 `ui/SummaryMetrics.kt`（唯一事实源），
        //    这里只负责摆版。
        val avgLine = avgEnergyLine(s.wh_per_km)
        if (avgLine != null) {
            Spacer(Modifier.padding(top = 10.dp))
            Text(
                // ⚠️ v0.14.0：Wh/km → **kWh/100km**（中国车主的口径）。
                //
                // ⚠️ 值与单位之间是**不换行空格**（U+00A0，见 `avgEnergyLine`）：
                //    200% 字号下整行要 349dp > 296dp ⇒ 必然折行，
                //    而贪心折行取**最后一个放得下的断点**
                //    ⇒ 断在普通空格上就会把「17.4」和「kWh/100km」拆到两行
                //    ⇒ 数与单位分离。绑住之后只折在「平均能耗」后面。
                avgLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val dcPct =
                if (s.charge_kwh > 0) (s.dc_kwh / s.charge_kwh * 100).toInt() else 0
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                // ⚠️ 0 间距 = 括号紧贴数字（视觉与原来那个整串一模一样）。
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    // ⚠️ **v0.47.0（第 28 轮通勤车主评审 P0-3）**：补**分母**。
                    //
                    // 原来写「快充占比 94%」—— 不说是按**电量**还是按**次数**算的，
                    // 而这两个口径可以差很远。
                    // 补上「按充入电量」后，读者能自己判断这个数指的是什么。
                    //
                    // ⚠️ 刻意**不**写「这车天天在外面快充」之类的解读 ——
                    // 本机 `geofence_name` 全为 null（没配充电围栏），
                    // **家充占比根本算不出来**，而「自动识别家」是明确不做的范围。
                    // 没有数据支撑的解读一律不给。
                    dcShareLine(dcPct),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    // ⚠️ 限定语**独立成节点** ⇒ 它永远不会被折行劈开。
                    DC_SHARE_CALIBER,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.padding(top = 6.dp))
            // 快充占比条：一眼看出这 30 天的补能结构
            // ⚠️ v0.7.1：原来用 primary（特斯拉红）—— 红色在本 App 的语义里
            // 只表示「告警 / 品牌点缀」，用来画一条普通占比条属于语义误用。
            // 改用能量蓝（快充本身就是「直流快充」，用品牌红还会和告警撞色）。
            LinearProgressIndicator(
                progress = { if (s.charge_kwh > 0) (s.dc_kwh / s.charge_kwh).toFloat() else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = LocalAccentColors.current.energyBlue,
                trackColor = LocalAccentColors.current.chartTrack,
            )
        }
    }
}
