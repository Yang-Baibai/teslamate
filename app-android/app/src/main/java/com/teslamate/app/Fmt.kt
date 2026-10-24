package com.teslamate.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val zone: ZoneId = ZoneId.systemDefault()

private val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA).withZone(zone)
private val dtfShort = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA).withZone(zone)
private val df = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.CHINA).withZone(zone)
private val mf = DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA).withZone(zone)

fun Instant?.fmtFull(): String = this?.let { dtf.format(it) } ?: "—"
fun Instant?.fmtShort(): String = this?.let { dtfShort.format(it) } ?: "—"
fun Instant?.fmtDate(): String = this?.let { df.format(it) } ?: "—"
fun Instant?.fmtMonth(): String = this?.let { mf.format(it) } ?: "—"

fun Double?.fmt(digits: Int = 1, suffix: String = ""): String =
    this?.let { String.format(Locale.CHINA, "%.${digits}f%s", it, suffix) } ?: "—"

fun Int?.fmtInt(): String = this?.toString() ?: "—"

/** 带千分位（里程表用）：101350 → 101,350。没有分隔符的五位数很难一眼读出量级。 */
fun Double?.fmtThousands(): String =
    this?.let { String.format(Locale.CHINA, "%,.0f", it) } ?: "—"

/**
 * 地址只取第一段。
 *
 * TeslaMate 的 `location_name` / `place` 是**完整邮编地址**：
 * "龙山九路, 西区街道, 惠州大亚湾经济技术开发区, 惠阳区, 惠州市, 广东省, 516083, 中国"
 * —— 占两行、信息密度极低。列表行只显示第一段（"龙山九路"），
 * 完整地址在车况页的位置卡里以灰色小字保留。
 */
fun String?.firstSegment(): String? {
    if (this == null) return null
    val s = substringBefore('，').substringBefore(',').trim()
    return s.ifBlank { this }
}

fun minutesFmt(m: Int?): String {
    if (m == null) return "—"
    val h = m / 60
    val mm = m % 60
    return when {
        h > 0 && mm > 0 -> "${h}小时${mm}分"
        h > 0 -> "${h}小时"
        else -> "${mm}分钟"
    }
}

fun stateCn(s: String?): String = when (s) {
    "online" -> "在线"
    "asleep" -> "休眠"
    "offline" -> "离线"
    else -> s ?: "未知"
}

/**
 * 相对时间：「3 分钟前」而不是「14:30:12」。
 *
 * 效率型车主的原话：只给 HH:mm 看不出隔了多久 —— 要的是「更新于 3 分钟前」。
 * 超过一周再回落到绝对时间（那时"周前"没有信息量）。
 */
fun Instant?.relTime(now: Instant = Instant.now()): String {
    if (this == null) return "—"
    val mins = java.time.Duration.between(this, now).toMinutes()
    return when {
        mins < 0 -> "刚刚"
        mins < 1 -> "刚刚"
        mins < 60 -> "$mins 分钟前"
        mins < 60 * 24 -> "${mins / 60} 小时前"
        mins < 60 * 24 * 7 -> "${mins / (60 * 24)} 天前"
        else -> fmtShort()
    }
}
