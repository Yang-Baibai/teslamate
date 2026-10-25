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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
 *    用量页那个是**标定续航差法**（系统性高估约 9%）—— 两处口径不同，别混。
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
        BigStat(
            s.median_score.roundToInt().toString(),
            "驾驶平顺分（中位）",
            hero = true,
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
            "${s.wh_per_km_median.fmtKwh100()} kWh/100km（中位）",
            hint = "车轮功率积分（真实耗电）；用量页那个是标定法，两者差约 9%",
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
            // ⚠️ **复审：柱子不能只靠颜色区分** ——
            // 分数是数字，每根柱子下面必须写出来（WCAG 1.4.1）。
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                recent.forEach { d ->
                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            d.score.roundToInt().toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height((d.score / 100.0 * 34).dp.coerceAtLeast(3.dp))
                                .clip(RoundedCornerShape(3.dp))
                                .background(scoreColor(d.score))
                        )
                    }
                }
            }
            Text(
                "最近 ${recent.size} 趟的分数（右边最新）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))
        // ── 口径披露 ──
        Text(
            "分数 = 100 − 急刹车(≤30) − 急加速(≤20) − ≥${profile.params.over_speed_kph}km/h(≤20)" +
                " − 能耗(≤15) − 夜驾(≤15)。" +
                "硬加/硬刹 = 2 秒内车速变化 ≥${profile.params.hard_event_threshold_kmh_per_2s} km/h" +
                "（噪声底 ±12，有 8 倍余量）；" +
                "夜间 ${profile.params.night_start_hour}:00–${profile.params.night_end_hour}:00、" +
                "≥${profile.params.over_speed_kph} km/h —— " +
                "都是**行业惯例值**，不是从这辆车拟合出来的。",
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
 */
@Composable
private fun scoreColor(score: Double): Color {
    val a = LocalAccentColors.current
    return when {
        score >= 85 -> a.energyBlue
        score >= 60 -> a.warnAmber
        else -> a.chartTrack
    }
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
                modifier = Modifier.width(96.dp),
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
