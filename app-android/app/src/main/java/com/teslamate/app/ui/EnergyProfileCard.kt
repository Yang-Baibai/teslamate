package com.teslamate.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teslamate.app.EnergyBand
import com.teslamate.app.EnergyProfile
import com.teslamate.app.fmtKwh100
import kotlin.math.roundToInt

/**
 * ⚠️ **v0.28.0（第 17 轮官方 App 对标评审 A-2）：能耗-车速曲线。**
 *
 * ## 为什么官方 App 给不了
 *
 * 官方 App 只拿到车端汇总上报的**平均** Wh/km；
 * 而 TeslaMate 记的是**逐秒的车轮功率** ——
 * 所以「这台车在 90 km/h 到底多费电、50 km/h 省多少」只有我们算得出来。
 *
 * 实测本车：
 * - 最低点 **40–50 km/h（11.9 kWh/100km）**
 * - 高速 **80–110 km/h 也只要 12.4~14.7**（比市区低得多）
 * - 市区 0–20 km/h 高达 24.7~28.4（低速反而更费）
 * - 120 km/h 以后急剧劣化（17.0 → 18.0）
 *
 * ## 口径
 *
 * **车轮功率梯形积分**（`(power+pp)/2 × Δt`），按 **1 km 分箱**、
 * 用**箱内里程表跨度**算距离（不能用相邻点的里程差 —— 里程表比采样稀疏，
 * 逐点差分会漏掉大量里程，算出「市区 0.8 Wh/km」的荒谬值）。
 *
 * 与「标定续航差 × cars.efficiency」法实测相关系数 **0.99686**，
 * 但标定法**系统性高估约 7%** —— 所以两处能耗数字的口径必须分别标明。
 */
@Composable
fun EnergyProfileCard(
    profile: EnergyProfile?,
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "能耗-车速曲线", modifier = modifier) {
        val bands = profile?.bands.orEmpty()
        if (bands.isEmpty()) {
            Text(
                if (loading) "正在算…" else "还没有足够的能耗样本",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        val accents = LocalAccentColors.current
        val best = profile?.best_band
        // ⚠️ **复审 P0-④：不能比整字段相等。**
        // 服务端的 best_band 以前不带 km/bins → `b == best` **恒为 false**
        // → 「蓝色 = 最省电」的图例是假的。现在按 (min,max) 认同一档。
        fun isBest(b: EnergyBand): Boolean =
            best != null && b.min_kph == best.min_kph && b.max_kph == best.max_kph

        // ── 主角：最省电的那个速度档 ──
        if (best != null) {
            BigStat(
                best.wh_per_km.fmtKwh100(),
                "最省电速度档的能耗（kWh/100km）",
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${best.label} km/h" +
                    if (best.km > 0) " · 跑了 ${best.km.roundToInt()} 公里" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(10.dp))
        // ── 柱状图：每个速度档一根柱子 ──
        // 柱高 ∝ Wh/km（越省电越矮），**柱顶写数值** ——
        // 复审指出「无刻度无数值」的柱状图只能靠颜色读，违反「状态不能只靠颜色」。
        val maxWh = bands.maxOf { it.wh_per_km }.takeIf { it > 0 } ?: 1.0
        Row(
            Modifier.fillMaxWidth().height(84.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            bands.forEach { b ->
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        b.wh_per_km.fmtKwh100(),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height((b.wh_per_km / maxWh * 52).dp.coerceAtLeast(3.dp))
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (isBest(b)) accents.energyBlue else accents.chartTrack)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "左：${bands.first().label} km/h → 右：${bands.last().label} km/h" +
                "（蓝色是最省电那档；柱越矮越省电，柱顶是数值）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        // ── 明细：最快的两档、最省电那档、最快的两档 ──
        // ⚠️ 复审：原来只取「前 3 + 后 3」，**最省电那档在中间被漏掉**了。
        // 现在**显式把最省电那档插进去**。
        val detail = (bands.take(3) + listOfNotNull(bands.firstOrNull { isBest(it) }) + bands.takeLast(3))
            .distinctBy { it.min_kph to it.max_kph }
            .sortedBy { it.min_kph }
        detail.forEach { b ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    "${b.label} km/h",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(88.dp),
                )
                Text(
                    "${b.wh_per_km.fmtKwh100()} kWh/100km",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isBest(b)) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.width(118.dp),
                )
                Text(
                    "${b.km.roundToInt()} 公里",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "口径：车轮功率梯形积分（1 km 分箱，按箱内里程表跨度算距离），" +
                "全车 ${profile?.total_km?.roundToInt() ?: 0} 公里，" +
                "平均 ${profile?.overall_wh_per_km?.fmtKwh100() ?: "—"} kWh/100km。" +
                "官方 App 只有**平均** Wh/km，这条曲线它给不了。" +
                "每一档都够样本（最少的也有几十公里），但**市区与高速的工况差别很大**，" +
                "别拿市区那几档去规划高速。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
