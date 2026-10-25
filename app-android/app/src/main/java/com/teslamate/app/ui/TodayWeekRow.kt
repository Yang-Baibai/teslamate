package com.teslamate.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teslamate.app.Drive
import com.teslamate.app.ServerZone
import com.teslamate.app.fmt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * ⚠️ **v0.47.0（第 28 轮通勤车主评审 P0-1）**：车况页的「今天 / 本周」摘要。
 *
 * ## 为什么要有这一行
 *
 * 第 28 轮通勤车主评审的结论：车况页**只有静态车况**
 * （电量 / 续航 / 锁车 / 空调 / 胎压），
 * **没有任何「今天 / 本周」的信息**。
 * 而通勤者每天早上的第二个问题永远是
 * 「这车今天开没开、这周怎么用的」——
 * 数据**是有的**（`StatusScreen` 已经把最近 500 趟留在内存里），只是**没给人看**。
 *
 * 真实数据（87 天）：**42 天零行驶（48.3%）** ——
 * 所以**零行驶的形态必须存在**，不能只在有数据时才显示这一行。
 *
 * ## 零新增网络请求
 *
 * `StatusScreen.kt:177` 的 `val drives = store.drives` 已经把这 500 趟留在内存里
 * ⇒ 今天 / 本周的公里数、分钟数、行驶天数**纯客户端现算**。
 *
 * ## 口径
 *
 * 时间窗用**服务端时区**（[ServerZone]）——
 * 与统计页的聚合桶同源（v0.29.0 起全 App 统一）。
 * 「本周」= 自然周（周一起）。
 */
data class UsageSummary(
    val todayKm: Double = 0.0,
    val todayMinutes: Int = 0,
    val todayTrips: Int = 0,
    val weekKm: Double = 0.0,
    val weekMinutes: Int = 0,
    val weekTrips: Int = 0,
    /** 本周有行程的天数 */
    val weekDays: Int = 0,
) {
    /** 今天开过车吗（决定用「今天 X 公里」还是「今天还没开车」）。 */
    val hasToday: Boolean get() = todayTrips > 0
}

/**
 * ⚠️ **v0.47.0（第 28 轮通勤车主评审 P0-1）**：从行程列表现算「今天 / 本周」。
 *
 * 抽成**纯函数**是为了能被 JVM 单测直接断言（时间窗边界最容易错，
 * 而时间窗错一次就是「今天」显示成「昨天」）。
 */
fun computeUsageSummary(
    drives: List<Drive>,
    zone: ZoneId = ServerZone.current(),
    now: Instant = Instant.now(),
): UsageSummary {
    val today = now.atZone(zone).toLocalDate()
    // 自然周：周一为起点（与统计页的「本周」口径一致）
    val weekStart = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
    var out = UsageSummary()
    val weekDays = HashSet<LocalDate>()
    for (d in drives) {
        val date = d.start_date.atZone(zone).toLocalDate()
        val km = d.distance_km ?: continue
        val min = d.duration_min ?: 0
        when {
            date == today -> {
                out = out.copy(
                    todayKm = out.todayKm + km,
                    todayMinutes = out.todayMinutes + min,
                    todayTrips = out.todayTrips + 1,
                )
            }
            !date.isBefore(weekStart) && date.isBefore(today) -> {
                out = out.copy(
                    weekKm = out.weekKm + km,
                    weekMinutes = out.weekMinutes + min,
                    weekTrips = out.weekTrips + 1,
                )
                weekDays += date
            }
        }
    }
    // 本周的公里数要**包含今天**（用户看到的是「这一周到现在」）
    return out.copy(weekKm = out.weekKm + out.todayKm, weekMinutes = out.weekMinutes + out.todayMinutes,
        weekTrips = out.weekTrips + out.todayTrips, weekDays = weekDays.size + if (out.hasToday) 1 else 0)
}

/**
 * ⚠️ **v0.47.0（第 28 轮通勤车主评审 P0-3）**：最近 30 天里有行程的**天数**。
 *
 * ## 为什么要有这个数
 *
 * 第 28 轮通勤车主评审的批评很准：
 * 「行程次数 214 次」对通勤者**读不懂** ——
 * TeslaMate 的「一趟」= 一次连续移动，
 * 而真实数据里 2026-10-06 跑 **1,002.5 公里只有 10 趟**（≈100 公里/趟），
 * 2026-10-24 跑 205.1 公里也是 **10 趟**。
 * 214 趟 ÷ 22 天 = **9.7 段/天** ⇒ 通勤者读到只会困惑「我一天上下班 9.7 次？」
 *
 * 换成「行驶天数 22 天」后，长途用户的信息**不丢**（趟数在「用量」页仍能看到），
 * 而通勤者能读懂「这车这周开了几天」。
 *
 * ## 口径
 *
 * 与服务端 `period=month`（最近 **30 个自然日**）**同一个窗口**；
 * 时区用服务端时区（[ServerZone]），与服务端分桶同源。
 */
fun driveDaysInWindow(
    drives: List<Drive>,
    days: Int = 30,
    zone: ZoneId = ServerZone.current(),
    now: Instant = Instant.now(),
): Int {
    val today = now.atZone(zone).toLocalDate()
    val from = today.minusDays((days - 1).toLong())
    return drives.mapNotNull { d ->
        val date = d.start_date.atZone(zone).toLocalDate()
        if (!date.isBefore(from) && !date.isAfter(today)) date else null
    }.distinct().size
}

/**
 * ⚠️ **v0.47.0（第 28 轮通勤车主评审 P0-1）**：车况页的「今天 / 本周」一行。
 *
 * 文案（逐字）：
 * - 有行驶：`今天 4.4 公里 · 15 分钟` + `本周 243.3 公里 · 4 天有行程`
 * - 零行驶：`今天还没开车` + `本周 243.3 公里 · 4 天有行程`
 *
 * ⚠️ 刻意给「N 天有行程」而不是只给公里数 ——
 * 真实数据里本周 243.3 km 有 **84.3% 来自 10-24 一天**，
 * 单给公里数会被那一天主导；「几天」才是能对照「我今天该出门了吗」的量。
 */
@Composable
fun TodayWeekRow(summary: UsageSummary, modifier: Modifier = Modifier) {
    val todayTxt = if (summary.hasToday) {
        "今天 " + summary.todayKm.fmt(1) + " 公里 · " + summary.todayMinutes + " 分钟"
    } else {
        "今天还没开车"
    }
    val weekTxt = if (summary.weekTrips > 0) {
        "本周 " + summary.weekKm.fmt(1) + " 公里 · " + summary.weekDays + " 天有行程"
    } else {
        "本周还没有行程"
    }
    Column(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                todayTxt,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                weekTxt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
