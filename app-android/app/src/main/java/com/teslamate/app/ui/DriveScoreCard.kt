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
import androidx.compose.foundation.layout.size
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
import com.teslamate.app.DrivingProfile
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
 * ## 必须说清的三件事
 *
 * 1. **阈值是拍的**：2 秒窗口 20 km/h、120 km/h、夜间 22:00–06:00
 *    都是行业惯例，不是从这辆车拟合出来的。
 * 2. **地形混杂**：实测「超速占比」与「下坡高度」的相关系数 **+0.635** ——
 *    山路上限速本来就高，所以超速这一项**掺了地形**，
 *    而「急刹车」与下坡的相关系数只有 **−0.16**（干净）。
 * 3. **数据来自 positions**：162 万个采样点，中位采样间隔 0.251 秒。
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
            "近 ${s.drive_count} 趟 · ${s.total_km.roundToInt()} 公里" +
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
            hint = "刹车片磨损的直接来源",
        )
        ScoreRow(
            "急加速",
            "${s.hard_accel_total} 次 · ${fmtPer100(s.hard_accel_total, s.total_km)} 次/百公里",
            hint = "轮胎与悬架的消耗来源",
        )
        ScoreRow(
            "能耗",
            "${s.wh_per_km_median.roundToInt()} kWh/100km（中位）",
            // ⚠️ v0.27.0：**口径必须写清楚** ——
            // 这个数是**车轮功率积分**（真实耗电），
            // 而用量页的「平均能耗」是**标定续航差法**（Δ标定续航 × 效率），
            // 两者实测相差约 9%（标定法系统性高估）。
            // 不写清楚，用户在两页看到两个能耗数会以为有一个是错的。
            hint = "车轮功率积分（真实耗电）；用量页那个是标定法",
        )
        ScoreRow(
            "夜间行驶",
            "${s.night_pct_avg.roundToInt()}% 的时间",
            hint = "本地 ${profile.params.night_start_hour}:00–${profile.params.night_end_hour}:00",
        )
        ScoreRow(
            "超速",
            "${s.over_speed_pct_avg.roundToInt()}% 的时间 ≥${profile.params.over_speed_kph} km/h",
            hint = "这项掺了地形：山路限速本来就高",
        )

        Spacer(Modifier.height(8.dp))
        // ── 最近几趟的分数条 ──
        // ⚠️ 只画最近 12 趟 —— 再多就成了「装饰」，而每个格子都该有量纲。
        val recent = profile.drives.takeLast(12)
        if (recent.size >= 3) {
            Row(
                Modifier.fillMaxWidth().height(46.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                recent.forEach { d ->
                    val h = (d.score / 100.0 * 40).dp.coerceAtLeast(3.dp)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(h)
                            .clip(RoundedCornerShape(3.dp))
                            .background(scoreColor(d.score))
                    )
                }
            }
            Text(
                "最近 ${recent.size} 趟（左边最新）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))
        // ── 口径披露 ──
        Text(
            "口径：硬加/硬刹 = 2 秒内车速变化 ≥${profile.params.hard_event_threshold_kmh_per_2s} km/h" +
                "（噪声底 ±12，有 8 倍余量）；" +
                "超速阈值 ${profile.params.over_speed_kph} km/h、" +
                "夜间 ${profile.params.night_start_hour}:00–${profile.params.night_end_hour}:00 —— " +
                "都是行业惯例值，不是从这辆车拟合的。",
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

/** 分数 → 颜色。⚠️ 颜色只是**辅助**：分数本身是数字，不是颜色。 */
@Composable
private fun scoreColor(score: Double): Color {
    val a = LocalAccentColors.current
    return when {
        score >= 85 -> a.energyBlue
        score >= 60 -> a.warnAmber
        else -> a.danger
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
                modifier = Modifier.width(72.dp),
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
