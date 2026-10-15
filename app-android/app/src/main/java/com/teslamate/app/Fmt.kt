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
