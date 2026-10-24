package com.teslamate.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 时区**动态**取。
 *
 * ⚠️ v0.9.0：原来 `Fmt.kt` 顶部是
 * ```kotlin
 * private val zone: ZoneId = ZoneId.systemDefault()
 * private val dtf = DateTimeFormatter.ofPattern(...).withZone(zone)
 * ```
 * —— 顶层 `val` 在**进程启动时求值一次**。用户改系统时区（出国出差、
 * 自动换区、跨时区出差回来）之后，App 仍然按**旧时区**显示所有绝对时间，
 * 直到进程被杀。这在「经常出国的车主」身上是实打实的显示错误。
 *
 * 现在：每次格式化时重新读 `ZoneId.systemDefault()`，
 * 只在时区**真的变了**时才重建 formatter（重建本身有开销，不能每次都做）。
 *
 * ⚠️ 另一个**已知未修**的差异：服务端的日/月聚合用 `TZ_NAME=Asia/Shanghai`，
 * 而 App 的「按月分组」用**手机**时区。跨时区时两者可能对不上
 * （例如手机在纽约时差出 12 小时，月初/月末会被分到不同月份）。
 * 要彻底修需要服务端下发 `server_timezone` 并让 App 统一以服务端为准 ——
 * 那是比这一条更大的改动，本轮先记在这里，不静默留下。
 */
private class ZoneFormatter(private val pattern: String) {
    private var lastZone: ZoneId? = null
    private var fmt: DateTimeFormatter? = null

    fun format(instant: Instant): String {
        val z = ZoneId.systemDefault()
        var f = fmt
        if (f == null || lastZone != z) {
            f = DateTimeFormatter.ofPattern(pattern, Locale.CHINA).withZone(z)
            fmt = f
            lastZone = z
        }
        return f.format(instant)
    }
}

private val dtf = ZoneFormatter("yyyy-MM-dd HH:mm")
private val dtfShort = ZoneFormatter("MM-dd HH:mm")
private val df = ZoneFormatter("yyyy-MM-dd")
private val mf = ZoneFormatter("yyyy年M月")

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

/**
 * 从完整邮编地址里压出「区 + 市」这一层。
 *
 * ⚠️ v0.14.0（新手车主 P2-2）：位置卡的副行原来写的是**整条邮寄地址**：
 * 「新湖路, 永丰社区, 西乡街道, 宝安区, 深圳市, 广东省, 518100, 中国」——
 * 占满一行、信息增量接近零（主标题已经是「新湖路」）。
 *
 * 现在只留行政区层级：「宝安区 深圳市」。
 * 完整地址在展开的地图详情里仍然可见，信息一点不丢。
 */
fun String?.districtCity(): String? {
    if (this == null) return null
    val parts = split('，', ',').map { it.trim() }.filter { it.isNotBlank() }
    if (parts.size < 2) return null
    val district = parts.lastOrNull { it.endsWith("区") || it.endsWith("县") }
    val city = parts.lastOrNull { it.endsWith("市") }
    val picked = listOfNotNull(district, city).distinct()
    return picked.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

/**
 * 把 **Wh/km** 换算成 **kWh/100km** 展示。
 *
 * ⚠️ v0.14.0（新手车主 P1-1 + 中文本地化专项）：
 * 中国车主脑子里的能耗单位是 **kWh/100km** —— 汽车之家、懂车帝、
 * 特斯拉中国官网全是这个口径。Wh/km 在中文语境里像外星语，
 * 「162 Wh/km」这个数对目标用户**等于不存在**。
 *
 * 换算：**÷10**（100 km 的 kWh 数 = Wh/km 数 ÷ 10）。
 * 单位串用 **kWh/100km**（行业通用写法）。
 *
 * ⚠️ **内部计算一律仍用 Wh/km** —— 只在**显示层**换算，
 * 这样能耗模型、续航折算、保底余量全都不受影响，改一个显示层就能回滚。
 */
fun Double?.fmtKwh100(decimals: Int = 1): String =
    this?.let { String.format(Locale.CHINA, "%.${decimals}f", it / 10.0) } ?: "—"

/** kWh/100km 的完整写法（含单位）。 */
fun Double?.fmtKwh100WithUnit(decimals: Int = 1): String =
    this?.let { String.format(Locale.CHINA, "%.${decimals}f kWh/100km", it / 10.0) } ?: "—"

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
