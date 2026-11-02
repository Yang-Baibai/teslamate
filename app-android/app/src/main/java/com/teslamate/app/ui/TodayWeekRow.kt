package com.teslamate.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.teslamate.app.Drive
import com.teslamate.app.ServerZone
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * ⚠️ **v0.47.0（第 28 轮通勤车主评审 P0-1）**：车况页的「今天 / 近 7 天」摘要。
 *
 * ## 为什么要有这一行
 *
 * 第 28 轮通勤车主评审的结论：车况页**只有静态车况**
 * （电量 / 续航 / 锁车 / 空调 / 胎压），
 * **没有任何「今天 / 近 7 天」的信息**。
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
 * ⇒ 今天 / 近 7 天的公里数、分钟数、行驶天数**纯客户端现算**。
 *
 * ## 口径
 *
 * 时间窗用**服务端时区**（[ServerZone]）——
 * 与统计页的聚合桶同源（v0.29.0 起全 App 统一）。
 * ⚠️ **v0.50.0**：窗口 = **滚动 7 天**（今天 + 前 6 天），
 * 与服务端 `period=week` 的 7 个自然日桶**完全一致**。
 */
data class UsageSummary(
    val todayKm: Double = 0.0,
    val todayMinutes: Int = 0,
    val todayTrips: Int = 0,
    val weekKm: Double = 0.0,
    val weekMinutes: Int = 0,
    val weekTrips: Int = 0,
    /** 近 7 天里有行程的天数 */
    val weekDays: Int = 0,
) {
    /** 今天开过车吗（决定用「今天 X 公里」还是「今天还没开车」）。 */
    val hasToday: Boolean get() = todayTrips > 0
}

/**
 * ⚠️ **v0.47.0（第 28 轮通勤车主评审 P0-1）**：从行程列表现算「今天 / 近 7 天」。
 *
 * 抽成**纯函数**是为了能被 JVM 单测直接断言（时间窗边界最容易错，
 * 而时间窗错一次就是「今天」显示成「昨天」）。
 *
 * ⚠️ **v0.50.0（第 29 轮对抗性审计 P1-3）**：窗口从「**自然周**」改成「**滚动 7 天**」。
 *
 * 原来按自然周（周一起）切，而统计页那个分段是 `period=week`
 * = **服务端最近 7 个自然日**（滚动，含今天）——
 * 两个是**不同的窗口**。
 *
 * 实测（逐日复算）：10-19（周一）那天，
 * 「本周」是 **9.0 公里**，而统计页的「7 天」是 **781.3 公里** ——
 * 差 **87 倍**。
 *
 * ⚠️ 唯一样机日 10-25 恰好是**周日** ⇒ 两个窗口**重合** ——
 * 作者和两轮评审都没看出来（这是「只在特定日子才显形」的那类 bug）。
 *
 * 现在两处**用同一个窗口**（滚动 7 天，含今天），标签也统一成「近 7 天」。
 */
fun computeUsageSummary(
    drives: List<Drive>,
    zone: ZoneId = ServerZone.current(),
    now: Instant = Instant.now(),
): UsageSummary {
    val today = now.atZone(zone).toLocalDate()
    // ⚠️ 滚动 7 天 = 今天 + 前 6 天（与服务端 `period=week` 的 7 个自然日桶一致）
    val windowStart = today.minusDays(6)
    var out = UsageSummary()
    val weekDays = HashSet<LocalDate>()
    for (d in drives) {
        val date = d.start_date.atZone(zone).toLocalDate()
        if (date.isBefore(windowStart) || date.isAfter(today)) continue
        val km = d.distance_km ?: continue
        val min = d.duration_min ?: 0
        if (date == today) {
            out = out.copy(
                todayKm = out.todayKm + km,
                todayMinutes = out.todayMinutes + min,
                todayTrips = out.todayTrips + 1,
            )
        } else {
            out = out.copy(
                weekKm = out.weekKm + km,
                weekMinutes = out.weekMinutes + min,
                weekTrips = out.weekTrips + 1,
            )
            weekDays += date
        }
    }
    // 「近 7 天」的公里数要**包含今天**（用户看到的是「这一周到现在」）
    return out.copy(
        weekKm = out.weekKm + out.todayKm,
        weekMinutes = out.weekMinutes + out.todayMinutes,
        weekTrips = out.weekTrips + out.todayTrips,
        weekDays = weekDays.size + if (out.hasToday) 1 else 0,
    )
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
 * ⚠️ **v0.47.0（第 28 轮通勤车主评审 P0-1）**：车况页的「今天 / 近 7 天」一行。
 *
 * ⚠️ **v0.112.0**：这两段原来**并排**放着，而它们加起来要 ≈404dp、
 * 车况页内容区只有 328~361dp ⇒ **连 100% 字号都放不下** ⇒ 后一段被折行劈开
 * （「4 天有行程」变成「4 天」+「有行程」）。
 * 现在**每段独占一行**，段内放不下就拆成完整短语（详见 [usageTodayLine]）。
 *
 * 文案（逐字）：
 * - 有行驶：`今天 4.4 公里 · 15 分钟` + `近 7 天 243.3 公里 · 4 天有行程`
 * - 零行驶：`今天还没开车` + `近 7 天 243.3 公里 · 4 天有行程`
 *
 * ⚠️ 刻意给「N 天有行程」而不是只给公里数 ——
 *
 * ⚠️ **v0.50.0（第 29 轮对抗性审计 P1-3）**：标签从「本周」改成「**近 7 天**」——
 * 原来的「本周」指的是**自然周**（周一起），而统计页那个分段是**滚动 7 天**，
 * 两个窗口在周一那天能差 **87 倍**（实测 10-19：9.0 公里 vs 781.3 公里）。
 * 现在两处用**同一个窗口**，标签也不再含糊。
 * 真实数据里近 7 天的 243.3 km 有 **84.3% 来自 10-24 一天**，
 * 单给公里数会被那一天主导；「几天」才是能对照「我今天该出门了吗」的量。
 */
@Composable
fun TodayWeekRow(summary: UsageSummary, modifier: Modifier = Modifier) {
    // ⚠️ 判定要用**生效**的字号（15sp × 字体倍率）⇒ 宽度预算才是真的。
    val fontSizeSp = USAGE_BODY_MEDIUM_SP * LocalDensity.current.fontScale
    Column(
        modifier.fillMaxWidth().padding(vertical = 6.dp),
        // ⚠️ 2dp：段内拆行时用同一个间距 ⇒ 拆出来的几行仍读作一组。
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        UsageSummaryText(usageTodayLine(summary), fontSizeSp, emphasis = true)
        UsageSummaryText(usageWeekLine(summary), fontSizeSp, emphasis = false)
    }
}

/**
 * 一条摘要的渲染：**放得下就整句一行，放不下就拆成完整短语各占一行**。
 *
 * ⚠️ 拆行时**不渲染「 · 」** —— 分隔符只在「同排」时才有意义；
 * 两块各占一行时它会变成一个孤零零的尾巴（比劈开句子轻，但仍然是瑕疵）。
 *
 * ⚠️ 拆开后的每一块都是**完整短语**（独立 Text 节点 ⇒ 独立段落 ⇒ 没有内部断点）
 * ⇒ 「4 天有行程」这种词组永远不会被劈开。
 */
@Composable
private fun UsageSummaryText(line: UsageLine, fontSizeSp: Float, emphasis: Boolean) {
    val parts = if (usageLineNeedsSplit(line, fontSizeSp)) line.parts else listOf(line.joined)
    for (part in parts) {
        Text(
            part,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal,
            color = if (emphasis) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
