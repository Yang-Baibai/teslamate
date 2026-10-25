package com.teslamate.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teslamate.app.Charge
import com.teslamate.app.Drive
import com.teslamate.app.Haptics
import com.teslamate.app.fmtDate
import java.time.Instant
import kotlin.math.roundToInt

/**
 * ⚠️ **v0.27.0（第 17 轮官方 App 对标评审 A-4）：实测满电续航。**
 *
 * ## 为什么官方 App 给不了
 *
 * 官方 App 只显示**车机上报的标定续航**（EPA 值，本车满充时约 454 公里）。
 * 那个数是「按 146 Wh/km 还能跑多远」的**能量表**，
 * 跟真实路况（市区 / 高速 / 山路 / 气温）完全无关。
 *
 * 我们有 `charging_processes`：每一次充电都记了**充前电量和充后电量**，
 * 加上 `positions` 的里程表 —— 于是可以直接量出
 * **「从 100% 开到 8%，实跑了多少公里」**。
 *
 * 实测样本（本机 87 天）：
 * - 2026-09-12：96% → 6%，实跑 **546.5 公里** → 折合满电 **607 公里**（高速）
 * - 市区工况多数落在 **340~360 公里/满电**
 * - 官方标称 454 公里 → 高速比标称**高 34%**，市区比标称**低 20%**
 *
 * ## 口径
 *
 * 一段续航 = 从**一次充电结束**到**下一次充电开始**。
 * 里程 = 下一次充电的起始里程 − 上一次充电的结束里程
 * （含开到充电桩的绕行 —— 那也是真跑的公里）。
 *
 * 折算成满电：`公里数 ÷ 掉电百分比 × 100`。
 *
 * ⚠️ **必须自曝**：这是**折算值**，不是「某一次满电跑了 X 公里」。
 * 而且市区与高速差 1.7 倍，**中位数不代表任何一次具体出行**。
 */
data class RangeSegment(
    val startOdoKm: Double,
    val endOdoKm: Double,
    val km: Double,
    /** 离开上一个充电点时的电量 */
    val fromSoc: Int,
    /** 到达下一个充电点时的电量 */
    val toSoc: Int,
    /** 掉电百分比 */
    val socUsed: Int,
    val startDate: Instant,
    /** 折合满电里程（km） */
    val fullChargeKm: Double,
)

/**
 * 从充电记录里重建续航段。
 *
 * @param minSocUsed 掉电少于这个百分点的段**不算** ——
 *   差几十公里的短途（掉电 5%~15%）折算出来的「满电里程」噪声极大，
 *   混进来会把中位数拉偏。
 * @param drives **必须传**：用来剔除「里程表跳了但中间没有行程记录」的假段。
 *
 * ## ⚠️ 为什么要 drives
 *
 * 实测有一段**物理上不可能**的数据：
 * 2026-08-15 → 08-22 之间里程表走了 **366 公里**，而电量只掉了 **21%** ——
 * 折算出来是 **1744 公里/满电**（特斯拉做不到，实际是 37 Wh/km）。
 *
 * 原因：那 303 公里**根本没有行程记录**（里程表与行程表之间有空洞）——
 * 所以「掉电 21%」和「跑了 366 公里」根本不是同一段时间里的事。
 *
 * 现在要求：**这一段里的行程里程之和 ≥ 里程表跨度的 90%**，
 * 否则说明中间有没被记录的路段 → **这一段不算**（宁可少一段，也不给一个物理上不可能的数）。
 */
fun buildRangeSegments(
    charges: List<Charge>,
    drives: List<Drive> = emptyList(),
    minSocUsed: Int = 20,
): List<RangeSegment> {
    val sorted = charges
        .filter { it.start_odometer_km != null && it.start_soc != null && it.end_soc != null }
        .sortedBy { it.start_odometer_km }
    // 行程按里程排序，用来核对「这一段到底有没有真的开过」
    val sortedDrives = drives
        .filter { it.odometer_start != null && it.odometer_end != null && (it.distance_km ?: 0.0) > 0 }
        .sortedBy { it.odometer_start!! }
    val out = ArrayList<RangeSegment>()
    for (i in 0 until sorted.size - 1) {
        val a = sorted[i]
        val b = sorted[i + 1]
        val odoA = a.end_odometer_km ?: a.start_odometer_km ?: continue
        val odoB = b.start_odometer_km ?: continue
        val km = odoB - odoA
        if (km <= 5.0) continue
        // ⚠️ v0.27.0：**核对这段是不是真的全程有记录。**
        val recordedKm = sortedDrives
            .filter { d ->
                val s = d.odometer_start!!
                val e = d.odometer_end!!
                e > odoA && s < odoB
            }
            .sumOf { it.distance_km ?: 0.0 }
        // 没有行程数据时（drives 为空）**不做校验** —— 不能因为拿不到行程就把全丢掉。
        if (sortedDrives.isNotEmpty() && recordedKm < km * 0.9) continue
        val fromSoc = a.end_soc ?: continue
        val toSoc = b.start_soc ?: continue
        val socUsed = fromSoc - toSoc
        if (socUsed < minSocUsed) continue
        out += RangeSegment(
            startOdoKm = odoA,
            endOdoKm = odoB,
            km = km,
            fromSoc = fromSoc,
            toSoc = toSoc,
            socUsed = socUsed,
            startDate = a.end_date ?: a.start_date,
            fullChargeKm = km / socUsed * 100.0,
        )
    }
    return out
}

/**
 * 官方标称的**满电**续航（公里）。
 *
 * 取「充到 ≥99% 的那些场次」的车机额定续航中位数 ——
 * 那就是车机在满电时显示的那个数，正是官方 App 给用户的那个数。
 */
fun officialFullChargeRangeKm(charges: List<Charge>): Double? {
    val full = charges.filter { (it.end_soc ?: 0) >= 99 && it.end_range_km != null }
    if (full.isEmpty()) return null
    return com.teslamate.app.medianOf(full.map { it.end_range_km!! })
}

/**
 * 「实测满电续航」卡片。
 *
 * ⚠️ 默认**折叠** —— 它是「参考知识」而不是「现在能开多少」，
 * 抢首屏会挤压记录页的历史列表（v0.14.0 同样的教训）。
 */
@Composable
fun RangeSegmentsCard(
    charges: List<Charge>,
    /** ⚠️ v0.27.0：用来核对「这一段是不是真的全程有行程记录」。 */
    drives: List<Drive> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val segments = remember(charges, drives) { buildRangeSegments(charges, drives) }
    val official = remember(charges) { officialFullChargeRangeKm(charges) }
    if (segments.isEmpty()) return

    val fullValues = segments.map { it.fullChargeKm }
    val median = com.teslamate.app.medianOf(fullValues) ?: return
    val best = segments.maxByOrNull { it.fullChargeKm }
    val worst = segments.minByOrNull { it.fullChargeKm }

    var expanded by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current

    SectionCard(title = "实测满电续航", modifier = modifier) {
        BigStat(
            "${median.roundToInt()}",
            "实测满电续航（公里，中位）",
        )
        Spacer(Modifier.height(2.dp))
        Text(
            buildString {
                append("基于 ${segments.size} 段实测（每次充完到下次充上）")
                official?.let {
                    val diff = (median / it * 100).roundToInt()
                    append(" · 官方标称 ${it.roundToInt()} 公里")
                    append(if (diff >= 0) "，实测高 $diff%" else "，实测低 ${-diff}%")
                }
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        best?.let {
            Text(
                "最好：${it.fullChargeKm.roundToInt()} 公里" +
                    "（${it.startDate.fmtDate()} 从 ${it.fromSoc}% 开到 ${it.toSoc}%，实跑 ${it.km.roundToInt()} 公里）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        worst?.let {
            Text(
                "最差：${it.fullChargeKm.roundToInt()} 公里" +
                    "（${it.startDate.fmtDate()}，跑了 ${it.km.roundToInt()} 公里）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (expanded) "收起明细 ▾" else "看 ${segments.size} 段明细 ▸",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable {
                    Haptics.tick(ctx)
                    expanded = !expanded
                }
                .padding(vertical = 2.dp),
        )
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(160)) + expandVertically(tween(220)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(180)),
        ) {
            Column(Modifier.padding(top = 4.dp)) {
                segments.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 28.dp).padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            s.startDate.fmtDate(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(78.dp),
                        )
                        Text(
                            "${s.fromSoc}% → ${s.toSoc}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(72.dp),
                        )
                        Text(
                            "${s.km.roundToInt()} 公里",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(72.dp),
                        )
                        Text(
                            "≈ ${s.fullChargeKm.roundToInt()} 公里/满电",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "口径：一次充电结束到下一次充电开始，按掉电百分比折算到满电。" +
                        "这是**折算值**，不是某一次满电实跑；" +
                        "市区与高速差 1.7 倍，中位数不代表任何一次具体出行。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
