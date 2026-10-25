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
 * ## 官方 App 给不了
 *
 * 官方 App 只显示**车机上报的标定续航**（EPA 值）。
 * 那个数是「按 146 Wh/km 还能跑多远」的**能量表**，
 * 跟真实路况（市区 / 高速 / 山路 / 气温）完全无关。
 *
 * 我们有 `charging_processes`：每一次充电都记了**充前电量和充后电量**，
 * 加上 `positions` 的里程表 —— 于是可以直接量出
 * **「从 100% 开到 8%，实跑了多少公里」**。
 *
 * 实测样本（本机 87 天，**剔除里程表跳段之后**）：
 * - 最好：10-02 那趟高速，**596 公里/满电**（⚠️ 见下面的「山地下坡」提醒）
 * - 市区工况多数落在 **340~360 公里/满电**
 * - **中位 351 公里**，官方标称满电 **448 公里**（17 场满充的中位数）
 *
 * ## ⚠️ 「最好」那个数要打折看
 *
 * 596 公里那趟是**山地下坡**的产物：海拔 1389~4327 m（均 3228 m），
 * 回充让等效能耗降到 110 Wh/km —— **平路跑不出这个数**。
 * 所以卡片上把「最好」标成「最好（含下坡）」，不拿它当规划依据。
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
        // ⚠️ **v0.28.0（复审 P0-⑤）：同里程的充电必须按**时间**升序排。**
        //
        // 实测 ids 43/44 的里程**完全相同**（97726.898642）——
        // 它们是**一次插枪被切成的两段**（43: 68%→75%，44: 74%→100%）。
        // 而稳定排序会保持 API 返回的**日期倒序**，于是「下一场充电」变成了 44，
        // 续航段配成 (41, 44) → 掉电算成 26% → 折算满电 **734 公里**（物理上不可能）。
        // 真实应该是 (41, 43) → 掉电 32% → **596 公里**。
        .sortedWith(compareBy({ it.start_odometer_km }, { it.start_date }))
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
        //
        // ⚠️ **v0.28.0（复审 P2-12）：要按里程**裁剪**后累加，不能整条计入。**
        // 原来只要「行程的终点 > 段起点 且 行程的起点 < 段终点」就把**整条**行程的里程算进来 ——
        // 实测 11 个保留段的比值 > 1（最极端 3.996），判据形同虚设。
        // 现在按**重叠长度**等比折算。
        val recordedKm = sortedDrives.sumOf { d ->
            val s = d.odometer_start!!
            val e = d.odometer_end!!
            val overlap = minOf(e, odoB) - maxOf(s, odoA)
            if (overlap <= 0.0) {
                0.0
            } else {
                val len = e - s
                val dist = d.distance_km ?: 0.0
                if (len <= 0.0) 0.0 else dist * (overlap / len)
            }
        }
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
        // ⚠️ **v0.28.0（复审）：必须说清这是**折算值**、且含「没充满就走的段」。**
        //
        // 复审原话：「『实测满电续航 351 公里』——它含『没充满就走』的段
        // （如 50%→29%），是折算不是某次满电；
        // 与『官方标称 448』并列像同一件事的两种测法。」
        //
        // 而「官方标称」是**车机在满电时报的那个数**，
        // 两者的关系是「折算 vs 实测」，不是「两种测法」。
        Text(
            "这是**折算值**：把「从 X% 开到 Y% 跑了多少公里」按掉电比例折算到满电，" +
                "所以**含没充满就走的段**（比如 50%→29%）。" +
                "官方标称 448 公里是**车机满电时报的数**，两者是「折算 vs 实测」的关系。",
            style = MaterialTheme.typography.labelSmall,
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
        // ⚠️ **v0.28.0（复审 P2-13）：「最好」那个数必须打折看。**
        // 实测最好那趟是**山地下坡**的产物（海拔 1389~4327 m），
        // 回充让等效能耗降到 110 Wh/km —— **平路跑不出这个数**。
        // 不说清楚，用户会拿它当「这车满电能跑 597 公里」去规划长途。
        Text(
            "「最好」多半含下坡回充（长下坡几乎不耗电），别拿它当平路规划依据；" +
                "「最差」才是市区工况更接近的那个数。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
