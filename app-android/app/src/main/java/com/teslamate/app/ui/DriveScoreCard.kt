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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teslamate.app.DrivingProfile
import com.teslamate.app.fmtKwh100
import kotlin.math.roundToInt

/**
 * ⚠️ **v0.27.0（第 17 轮官方 App 对标评审 A-1）：驾驶画像卡片。**
 *
 * ## 这是官方 Tesla App 给不了的东西
 *
 * 官方 App 只拿到车端汇总上报的**平均** Wh/km 和标定续航；
 * 而 TeslaMate 记的是**逐秒的轮速与车轮功率** ——
 * 所以「你这一趟开得有多野」只有我们算得出来。
 * （第三方 TeslaFi / ABetterDrive 有这个功能，官方 App 没有。）
 *
 * ## 一屏一个主角数字
 *
 * 主角 = **驾驶平顺分**（0~100）。
 * 下面是 5 个**分项**，每个都给真实数字与口径，不给雷达图
 * —— 雷达图的面积没有可解释的量纲，是装饰不是信息。
 *
 * ## 必须说清的四件事
 *
 * 1. **统计范围**：只算 **≥20 公里**的行程 ——
 *    实测近 30 趟里有 16 趟不到 20 公里（占里程 15%），
 *    而低分那几趟全是 6~9 公里的**挪车**（商场/充电站内）；
 *    不排除的话主角分数会被「你在停车场挪车」主导。
 * 2. **分数怎么来的**：权重是**行业惯例值**，不是从这辆车拟合出来的。
 * 3. **地形混杂**：实测「超速占比」与「下坡高度」的相关系数 **+0.635** ——
 *    山路上限速本来就高，所以超速这一项**掺了地形**，
 *    而「急刹车」与下坡的相关系数只有 **−0.16**（干净）。
 * 4. **能耗口径**：这里是**车轮功率积分**（真实耗电），
 *    用量页那个是**标定续航差法**（高约 19%）—— 两处口径不同，别混。
 */
@Composable
fun DriveScoreCard(
    profile: DrivingProfile?,
    /** 正在算（服务端要从 162 万个采样点里算） */
    loading: Boolean,
    modifier: Modifier = Modifier,
) {
    SectionCard(title = "驾驶画像", modifier = modifier) {
        if (profile == null) {
            Text(
                if (loading) "正在算…" else "还没算过驾驶画像",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        val s = profile.summary
        if (s.drive_count == 0) {
            Text(
                "最近没有 ≥${profile.params.min_distance_km} 公里的行程，够不着评分",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }

        // ── 主角 ──
        // ⚠️ **v0.29.0（HIG 评审 P1-3）：**不跟车况页的续航主角同级。**
        // 续航「现在能开 XXX 公里」是车况页**唯一**的 44sp 锚点；
        // 驾驶画像再给一个 44sp 就成了两个平级主角（一屏一个主角数字）。
        //
        // ⚠️ **v0.35.0（第 21 轮视觉评审 P1-5）：从 34sp 降到 26sp。**
        // 原来它和「常驻点」都是 34sp，与 44sp 主角的层级差只有 **1.294×** ——
        // 缩略后这两个数字块**分不出主次**。现在层级比是 44 : 26 = **1.69×**。
        BigStat(
            s.median_score.roundToInt().toString(),
            "驾驶平顺分（中位）",
            mid = true,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "按 ≥${profile.params.min_distance_km} 公里的 ${s.drive_count} 趟算" +
                " · 共 ${s.total_km.roundToInt()} 公里" +
                " · 平均 ${s.avg_score.roundToInt()} 分",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        // ── 分项 ──
        ScoreRow(
            "急刹车",
            "${s.hard_brake_total} 次 · ${fmtPer100(s.hard_brake_total, s.total_km)} 次/百公里",
            // 实测 corr(急刹车/百公里, 下坡) = −0.16 → 这项**不掺地形**
            hint = "刹车片磨损的直接来源；与下坡几乎无关（相关系数 −0.16）",
        )
        ScoreRow(
            "急加速",
            "${s.hard_accel_total} 次 · ${fmtPer100(s.hard_accel_total, s.total_km)} 次/百公里",
            hint = "轮胎与悬架的消耗来源",
        )
        ScoreRow(
            "能耗",
            "${s.wh_per_km_median_gross.fmtKwh100()} kWh/100km（中位）",
            // ⚠️ **v0.29.0（复审 P0-2）：口径必须写清楚。**
            //
            // 评审原话：「『能耗 … 车轮功率积分（真实耗电）』——
            // 实测真实耗电是 198.2 Wh/km，车轮功率是 152.7，**低 24%**；
            // 且下坡得白送分。」
            //
            // 所以：① 它是**车轮功率**（不含传动损耗、不含回充抵扣），
            //      不能叫「真实耗电」；
            //      ② 显示用**毛耗电**（不含回充抵扣），
            //      否则下坡那两趟的白分会显示成「16.2 kWh/100km」这种不可能的数。
            hint = "车轮功率积分，不含传动损耗与回充抵扣；不是电池实耗",
        )
        ScoreRow(
            "夜间行驶",
            "${s.night_pct_avg.roundToInt()}% 的时间",
            hint = "本地 ${profile.params.night_start_hour}:00–${profile.params.night_end_hour}:00",
        )
        ScoreRow(
            "≥${profile.params.over_speed_kph} km/h",
            "${s.over_speed_pct_avg.roundToInt()}% 的时间",
            // ⚠️ 复审：原来叫「超速」，而 120 km/h 是**国内高速的合法限速** ——
            // 叫「超速」等于把合法速度说成违法。改成中性描述。
            hint = "这项掺了地形：山路限速本来就高（与下坡相关系数 +0.635）",
        )

        Spacer(Modifier.height(10.dp))
        // ── 最近几趟的分数 ──
        // ⚠️ 只画最近 12 趟 —— 再多就成了「装饰」，而每个格子都该有量纲。
        //
        // ⚠️ **v0.28.0（复审 P0-1）：必须用 `take(12)` 而不是 `takeLast(12)`。**
        // 服务端是 `ORDER BY d.start_date DESC` —— `drives[0]` 是**最新**那趟。
        // 用 `takeLast` 拿到的是 30 趟里**最旧**的 12 趟（10-17~10-11），
        // 而标题写着「最近 12 趟」—— 说的是**过去**，图上画的是**更早**。
        val recent = profile.drives.take(12).reversed()
        if (recent.size >= 3) {
            // ⚠️ **v0.29.0（HIG 评审 P0-2 / P1-1）：只标关键值，数值放柱下方的带里。**
            //
            // 原来 12 根柱**每根**都标数值（10sp）—— 200% 字号下：
            // 柱槽 = (329 − 11×3)/12 = **24.67dp**，而「100」在 fs=2.0（em 20dp）下宽 **33dp**，
            // **超出 8.33dp** → 相邻柱的数值互相压字。
            // 而且每个数字坐在各自柱顶 → 12 个数字**锯齿状错位**，无法横向扫读。
            //
            // 现在：
            // ① 数值放到**柱下方的带**里 → 共用一条基线，能横向扫读（P1-1）；
            // ② 只标**最高**与**最低**两个 —— 其余留白，数字可以溢出到邻位的空白里，
            //    不会压到别的数字（P0-2）；
            // ③ 最高与最低相邻时只标最高（避免两个数字挤在一起）。
            val maxScore = recent.maxOf { it.score }
            val minScore = recent.minOf { it.score }
            val maxIdx = recent.indexOfFirst { it.score == maxScore }
            val minIdx = recent.indexOfFirst { it.score == minScore }
            val labelIdxs = if (maxIdx == minIdx || kotlin.math.abs(maxIdx - minIdx) <= 1) {
                setOf(maxIdx)
            } else {
                setOf(maxIdx, minIdx)
            }
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().height(38.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    recent.forEach { d ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height((d.score / 100.0 * 34).dp.coerceAtLeast(3.dp))
                                .clip(RoundedCornerShape(3.dp))
                                .background(scoreColor(d.score))
                        )
                    }
                }
                // 数值带：在柱**下方**，共用一条基线
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    recent.forEachIndexed { i, d ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (i in labelIdxs) {
                                Text(
                                    d.score.roundToInt().toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    // ⚠️ **v0.30.0（第 19 轮 HIG 评审 P0-3）：不许折行。**
                                    // 槽宽 24.67dp，而「100」在 fs=1.5 起就宽 25.20dp →
                                    // 折行会把整卡向下撑开、行高突变。
                                    // 允许**溢出**到邻位空白（只标 2 个，其余槽是空的）。
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Visible,
                                )
                            }
                        }
                    }
                }
            }
            Text(
                "最近 ${recent.size} 趟的分数（右边最新）· 柱越高分越高",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))
        // ── 口径披露 ──
        //
        // ⚠️ **v0.30.0（第 19 轮数据口径审计 P0-1）：两条免赔线的文案原来都写错了。**
        //
        // ① 「能耗 150 kWh/100km 以下不扣」—— 代码阈值是 `WhPerKmGross − 150`，
        //    而 `WhPerKmGross` 的单位是 **Wh/km**（= 15 kWh/100km）——
        //    **差 10 倍**。用户以为门槛是 150 kWh/100km（比实测 198 还高 7.6 倍），
        //    实际是 15 kWh/100km（本车实测毛耗电最低 166 Wh/km），
        //    所以这条免赔线在默认 20 趟里**一次都不触发**。
        //    现在按代码的真实单位写：150 Wh/km（即 15 kWh/100km）。
        //
        // ② 「≥120 km/h 的前 3% 不扣」—— 代码是 `over_pct − 3`，
        //    即「**超过 3 个百分点**的部分每 1 个百分点扣 1 分」，
        //    不是「最快的 3% 样本豁免」。实测 20 趟的超速占比中位 **19.1%**，
        //    「前 3% 免赔」在实践中几乎从不生效。
        Text(
            "分数 = 100 − 急刹车(≤30) − 急加速(≤20) − ≥${profile.params.over_speed_kph}km/h(≤20)" +
                " − 能耗(≤15) − 夜驾(≤15)。" +
                "两条免赔线：≥${profile.params.over_speed_kph} km/h 的占比超过 3 个百分点的部分" +
                "每 1 个百分点扣 1 分；能耗 150 Wh/km（即 15 kWh/100km）以下不扣。" +
                "硬加/硬刹 = 2 秒内车速变化 ≥${profile.params.hard_event_threshold_kmh_per_2s} km/h" +
                "（噪声底 ±12，有 8 倍余量）；" +
                "夜间 ${profile.params.night_start_hour}:00–${profile.params.night_end_hour}:00、" +
                "≥${profile.params.over_speed_kph} km/h —— " +
                "都是行业惯例值，不是从这辆车拟合出来的。" +
                "⚠️ 120 km/h 是国内高速的合法限速，这一项扣的是「开得比限速快」，不是「违法」。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 每公里 / 每百公里的次数。 */
private fun fmtPer100(count: Int, km: Double): String {
    if (km <= 0.1) return "—"
    return (count / km * 100).let { String.format(java.util.Locale.CHINA, "%.1f", it) }
}

/**
 * 分数 → 颜色。
 *
 * ⚠️ 复审：原来 `<60` 用 **danger 红** —— 太重。
 * 低分不是「告警」（车主没做错什么，只是开得野），
 * 用中性灰 + 旁边的数字表达就够了。
 *
 * ⚠️ **v0.29.0（HIG 评审 P1-7）：灰只保留「轨道」语义。**
 * 原来这里是三档（蓝 / 琥珀 / **灰**），而同一屏里：
 * 能耗曲线用灰表示「普通档」、常驻点用灰表示「轨道」——
 * 同一个灰在三个地方三种含义，直接冲突。
 * 现在改成**两档**（蓝 = 好，琥珀 = 需要留意），灰只用于轨道。
 */
@Composable
private fun scoreColor(score: Double): Color {
    val a = LocalAccentColors.current
    return if (score >= 85) a.energyBlue else a.warnAmber
}

/** 一个分项行：标题 + 数值 + 一句解释。 */
@Composable
private fun ScoreRow(
    label: String,
    value: String,
    hint: String,
) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                // ⚠️ **v0.29.0（HIG 评审 P1-5）：不要固定宽。**
                // 原来 `width(96.dp)`：「≥120 km/h」在 fs=1.0 宽 72.3dp ✓，
                // fs=2.0 宽 **144.6dp** → 超出 48.6dp，**必折行**且与数值错位。
                // 改成 weight + 最小宽：空间够就并排，不够就换行，不会挤到数值。
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 96.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
