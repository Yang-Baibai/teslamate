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
import androidx.compose.ui.text.style.TextOverflow
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
 * 实测本车（**毛耗电**口径，即不含下坡回充抵扣）：
 * - 最低点 **100–110 km/h（18.4 kWh/100km）**
 * - 高速 120 km/h 以后 19.2~18.7（130+ 档 18.7）
 * - 市区 0–20 km/h 高达 32.2（低速反而更费）
 *
 * ⚠️ **v0.30.0（第 19 轮数据口径审计 P2-A）：这一段原来是**旧净值**数字**
 * （「最低点 40–50 km/h（11.9）」「高速 80–110 也只要 12.4~14.7」），
 * 与 v0.29.0 改成的毛耗电**自相矛盾** —— 注释会持续误导后续改动，已订正。
 *
 * ## 口径
 *
 * **净值** = 车轮功率**梯形积分**（`(power+pp)/2 × Δt`，回充会抵扣）；
 * **毛耗电** = `max(power,0) × Δt`（右端矩形，不抵扣回充）。
 * 两者按 **1 km 分箱**、用**箱内里程表跨度**算距离
 * （不能用相邻点的里程差 —— 里程表比采样稀疏，
 * 逐点差分会漏掉大量里程，算出「市区 0.8 Wh/km」的荒谬值）。
 *
 * ⚠️ v0.30.0（P2-C）：上面这句「梯形积分」**只对净值成立** ——
 * 毛耗电用的是**右端矩形**（`max(P,0)×Δt`）。实测两者差 <0.1%
 * （100–110 档：矩形 184.2 vs 梯形 184.1），但口径描述必须准确。
 *
 * 与「标定续航差 × cars.efficiency」法实测相关系数 **0.99686**，
 * 但标定法**高约 19%**（标定 181.9 vs 车轮功率净值 155.2）——
 * 而且**两者都比实测电池耗电（约 198 kWh/100km）低**：
 * 车轮功率不含传动损耗，标定法受车机重算影响。
 * 所以两处能耗数字的口径必须分别标明。
 *
 * ⚠️ **v0.30.0（数据口径审计 P1-A）：这张卡的主角与柱高用**毛耗电**，
 * 明细行原来用**净值** —— 同一张卡两个数、且没有任何标注区分。**
 * 现在明细行也用毛耗电（并在行尾标出净值），两个口径各归各位。
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
            // ⚠️ **v0.29.0（复审 P0-1）：用**毛耗电**，不用净值。**
            //
            // 评审复算：净值口径下「40–50 km/h = 118.8 Wh/km」看着最省电，
            // 但那是**一次下坡 + 一次长途**的产物 —— 这台 AWD Performance 物理上做不到
            // （扣掉回充后那一档是 215.3）。
            // 改成毛耗电（不含回充抵扣）后，最低档变成 **100–110 km/h**。
            //
            // ⚠️ **v0.46.0（第 27 轮复审）订正一处**错误的注解**：
            // 原文写「正好是日常通勤的速度带，结论从『误导』变成『有用』」——
            // **那句是错的**，而且它已经**误导过一次复审**（把人引向「App 把通勤说错了」）。
            //
            // 实测本机短途（<20 km，264 趟）的**车速中位数只有 18 km/h**、
            // **76.5% 的趟低于 30 km/h** —— 真实通勤速度带离 100–110 km/h 差得远。
            // 而按**里程**看，本机 100–140 km/h 那几档又占了最多公里
            // （因为长途趟数少但很长）⇒ 100–110 对这台车其实是**合理**的。
            //
            // ⇒ 结论：**这一档是「理论最省电」，不是「你的日常」**。
            // 所以下面补一行「你跑得最多的一档」，让读者能拿自己的实际驾驶去比。
            Text(
                best.wh_per_km_gross.fmtKwh100(),
                fontSize = 28.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "最省电速度档的能耗（kWh/100km，不含回充抵扣）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${best.label} km/h" +
                    if (best.km > 0) " · 跑了 ${best.km.roundToInt()} 公里" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // ⚠️ **v0.46.0（第 27 轮复审）：补「你实际跑得最多的一档」。**
            //
            // 最省电的那一档是**理论最优**，不是**你的日常** ——
            // 读者真正想知道的是「我平时开的那一档，耗电是多少」。
            //
            // 数据现成：每个档都带 `km`（该档跑过的公里），取最大的那个。
            // 本机实测：按**里程**看是 130–140 km/h（1156 公里），
            // 与理论最省电的 100–110 相差不远；但按**趟数**看
            // 48.9% 的趟只有 0–5 公里 —— 所以两个口径都给，用户自己对。
            val mostDriven = bands.maxByOrNull { it.km }?.takeIf { it.km > 0 }
            mostDriven?.let { mb ->
                if (!isBest(mb)) {
                    Text(
                        "你跑得最多的一档是 ${mb.label} km/h（${mb.km.roundToInt()} 公里），" +
                            "这一档 ${mb.wh_per_km_gross.fmtKwh100()} kWh/100km",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        // ── 柱状图：每个速度档一根柱子 ──
        // 柱高 ∝ **毛耗电**（越省电越矮），**数值放柱下方的带里**。
        //
        // ⚠️ **v0.29.0（HIG 评审 P0-1 / P1-1）：只标两个数。**
        // 原来 14 根柱**每根**都标数值（9sp）：
        // 柱槽 = (329 − 13×2)/14 = **21.64dp**，而「28.4」在 fs=2.0（em 18dp）下宽 **33.62dp**，
        // **超出 11.98dp** → 相邻柱的数值互相压字；
        // 而且 Row 高度写死 84dp，fs=2.0 时「数值行 30 + 2 + 柱 52 = 84dp」**零余量**。
        //
        // 现在：① 只标**最省电**与**最费电**两档，其余留白；
        // ② 数值在柱**下方**的带里 → 共用一条基线，能横向扫读；
        // ③ 字号 9sp → 11sp（iOS footnote 最小值）。
        val maxWh = bands.maxOf { it.wh_per_km_gross }.takeIf { it > 0 } ?: 1.0
        val worstBand = bands.maxByOrNull { it.wh_per_km_gross }
        // ⚠️ **v0.30.0（第 19 轮 HIG 评审 P0-2）：相邻时只标一个，避免两个数字挤在一起。**
        //
        // 原来两个标签可能落在**相邻**槽位（每槽只有 21.64dp），
        // 而「28.4」在 200% 字号下宽 43.12dp → 两个数字会**压在一起**。
        // 现在：相邻时只标**最省电**那一个，另一个数字写进下面的说明文字。
        //
        // ⚠️ **v0.32.0（第 20 轮规划复审 P2-7）：护栏从 |Δ|≤1 放宽到 |Δ|≤2。**
        // 实测几何：14 档的槽宽 21.64dp、节距 23.64dp；
        // 标签 11sp 四字符在 fs=2.0 时宽 46.1dp（每侧溢出 12.2dp）。
        // |Δ|=2 时两个标签的中心距 47.29dp vs 半宽和 46.1dp → **只剩 1.2dp 余量**，
        // 字号再大一点就贴字。宁少标一个，也不压字。
        //
        // ⚠️ 被护栏丢掉的数值**不会**出现在下面的明细里（只有最省/最费档在窗口内才有），
        // 所以这里同时把丢掉的那个数补进说明文字 —— 不能让它凭空消失。
        val bestIdx = bands.indexOfFirst { isBest(it) }
        val worstIdx = worstBand?.let { bands.indexOf(it) } ?: -1
        val labelIdxs = if (bestIdx < 0) emptySet() else {
            if (worstIdx >= 0 && kotlin.math.abs(bestIdx - worstIdx) <= 2) setOf(bestIdx)
            else setOf(bestIdx, worstIdx)
        }
        /** 被护栏丢掉的另一档（要写进说明文字，不能凭空消失） */
        val droppedLabel = if (labelIdxs.size == 1 && worstIdx >= 0 && worstIdx != bestIdx) {
            bands.getOrNull(worstIdx)?.let { "${it.label} km/h ${it.wh_per_km_gross.fmtKwh100()}" }
        } else {
            null
        }
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().height(56.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                bands.forEach { b ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height((b.wh_per_km_gross / maxWh * 52).dp.coerceAtLeast(3.dp))
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (isBest(b)) accents.energyBlue else accents.chartTrack)
                    )
                }
            }
            // 数值带：在柱**下方**，共用一条基线
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                bands.forEachIndexed { i, b ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (i in labelIdxs) {
                            Text(
                                b.wh_per_km_gross.fmtKwh100(),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                // ⚠️ v0.30.0：**不许折行**，也不许被裁 ——
                                // 槽宽只有 21.64dp，标签在 fs=1.15 起就会折成
                                // 「28.」/「4」两行，把整卡向下撑开。
                                // 允许**溢出**到邻位的空白里（只有 2 个标签，其余槽是空的）。
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Visible,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "左：${bands.first().label} km/h → 右：${bands.last().label} km/h" +
                "（蓝色是最省电那档；柱越矮越省电，柱下是数值）",
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
                    // ⚠️ v0.30.0（P1-A）：明细行改用**毛耗电** ——
                    // 主角与柱高都是毛耗电，明细却给净值 = 同一张卡两个数。
                    // 净值（扣回充）在行尾标出，两个口径各归各位。
                    "${b.wh_per_km_gross.fmtKwh100()} kWh/100km",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isBest(b)) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.width(118.dp),
                )
                Text(
                    // ⚠️ v0.30.0：净耗电（扣回充抵扣）—— 明确标成「净」
                    "净 ${b.wh_per_km.fmtKwh100()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(74.dp),
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
            buildString {
                append("口径：车轮功率毛耗电（`max(P,0)×Δt`，不含下坡回充抵扣；")
                append("净值是梯形积分，两者在明细行分开标），")
                append("1 km 分箱、按箱内里程表跨度算距离。")
                // ⚠️ v0.30.0（P0-2）：分母用**剖面覆盖里程**（与分子同源），
                // 全车总里程只作为背景给出。
                append("剖面覆盖 ${profile?.covered_km?.roundToInt() ?: 0} 公里")
                append("（全车 ${profile?.total_km?.roundToInt() ?: 0} 公里），")
                append("毛耗电 ${profile?.overall_wh_per_km_gross?.fmtKwh100() ?: "—"} kWh/100km。")
                append("官方 App 只有平均 Wh/km，这条曲线它给不了。")
                append("⚠️ 每一档是「平均速度落在该区间的 1 公里箱」，")
                append("箱内含加速与减速（实测 40–50 那一档有 518/522 个箱越界），")
                append("不是「以 45 km/h 匀速跑」的能耗。")
                // ⚠️ v0.32.0：被护栏丢掉的档位不能凭空消失
                droppedLabel?.let {
                    append("（最费电那档 $it kWh/100km 与最省电那档太近，柱上只标了一个）")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
