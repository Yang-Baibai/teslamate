package com.teslamate.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * ⚠️ **v0.29.0：服务端时区（全局）。**
 *
 * ## 为什么需要它
 *
 * 服务端按 `TZ_NAME`（默认 Asia/Shanghai）分日/月桶，
 * 而 App 原来按**手机时区**显示与分组。
 * 跨时区时同一件事在时间轴属于 10-06、在图表里却落到 10-07 ——
 * 「跨页跨日错位」，用户对不上账。
 *
 * 项目自己的方向早就写在这里了：
 * > 「要彻底修需要服务端下发 `server_timezone` 并让 App **统一以服务端为准**。」
 *
 * 现在服务端已经在 `/status` 里下发了它，App 收到后就切到这个时区。
 *
 * ## 为什么是全局而不是逐页传
 *
 * 时间格式化发生在**几十个地方**（`fmtFull` / `fmtShort` / `fmtDate` / `fmtMonth` …），
 * 逐个传参既啰嗦又容易漏。
 * 而且口径必须**全 App 统一** —— 一半用手机时区、一半用服务端时区，
 * 比全用手机时区更糟。
 *
 * 所以放在这里：进程级单例，收到服务端时区后**一次性**切换。
 * 切换时 ZoneFormatter 会自己重建 formatter（它本来就按 zone 缓存）。
 */
object ServerZone {
    @Volatile
    private var zone: ZoneId? = null

    /** 服务端下发的时区（非法值会被忽略，保留手机时区）。 */
    fun set(tz: String?) {
        val z = tz?.takeIf { it.isNotBlank() }?.let {
            try {
                ZoneId.of(it)
            } catch (_: Exception) {
                null
            }
        }
        zone = z
    }

    /** 当前用于显示的时区：服务端优先，否则手机时区。 */
    fun current(): ZoneId = zone ?: ZoneId.systemDefault()

    /** 服务端时区与手机时区**不一样**吗（用于在界面上提示）。 */
    fun differsFromDevice(): Boolean {
        val z = zone ?: return false
        return z != ZoneId.systemDefault()
    }
}

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
 * 现在：每次格式化时重新读时区，
 * 只在时区**真的变了**时才重建 formatter（重建本身有开销，不能每次都做）。
 *
 * ⚠️ **v0.29.0**：时区的来源优先是**服务端**（[ServerZone]），
 * 服务端没给才用手机时区 —— 口径与服务端的聚合桶保持一致。
 */
private class ZoneFormatter(private val pattern: String) {
    private var lastZone: ZoneId? = null
    private var fmt: DateTimeFormatter? = null

    fun format(instant: Instant): String {
        val z = ServerZone.current()
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
// ⚠️ v0.108.0：区间边界用（只要月-日 —— 窗口是按天算的，不该带时分秒）。
private val dfDay = ZoneFormatter("MM-dd")

fun Instant?.fmtFull(): String = this?.let { dtf.format(it) } ?: "—"
fun Instant?.fmtShort(): String = this?.let { dtfShort.format(it) } ?: "—"
fun Instant?.fmtDate(): String = this?.let { df.format(it) } ?: "—"
fun Instant?.fmtMonth(): String = this?.let { mf.format(it) } ?: "—"
/**
 * 只要「月-日」。
 *
 * ⚠️ v0.108.0（用户反馈 #27）：统计区间的边界用它 ——
 * 「近 7 天」的窗口是**按天**算的，带上时分秒反而是噪声。
 */
fun Instant?.fmtDay(): String = this?.let { dfDay.format(it) } ?: "—"
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
 * ⚠️ **v0.127.0 订正：「车端里程表」的单位 —— 官方给的是英里，本项目当成了公里。**
 *
 * ## 事实（本机 car 1 实测，2026-11-04 复核）
 *
 * | 量 | 值 |
 * |---|---|
 * | 官方 `vehicle_state.odometer` / 遥测 `Odometer` | **63,250.31（英里）** |
 * | 换算成公里（× 1.609344） | **101,791.508834 公里** |
 * | TeslaMate 的 `positions.odometer` | **101,791.508834 公里** |
 * | 差 | **0.0003 公里** |
 *
 * ## 为什么不能并列两个数
 *
 * v0.21.0 当时的判断是「两个数都不是错的，只是来源不同，说清楚来源更让人放心」——
 * **那个判断在实测数据上不成立**（本机 car 1，`psql` 直查）：
 *
 * | 事实 | 值 |
 * |---|---|
 * | `positions.odometer` 的**日跨度**最大 | **1,143 公里**（一天 109,457 条采样） |
 * | `drives` 实际里程之和 | 8,782.4 公里 |
 * | `positions.odometer` **绝对值** | 101,477.41 公里 |
 * | **车端里程表（官方原值：英里）** | **63,055.1 英里** = **101,477.35 公里** |
 * | 差 | **0.06 公里**（⚠️ v0.127.0 订正：当年这里写的是「38,422 公里」，那是**单位错误**） |
 *
 * ⇒ 那两个数其实是**同一个数**、只是单位不同。
 * ⚠️ **v0.127.0 订正**：v0.75.0 当年写的「偏 38,422 公里」是**单位错误**（把英里当公里）——
 * 那个「漂移」从来没发生过。
 * 规则本身仍然成立（那份**带采样噪声**：单日跨度到过 1,143 公里），
 * 但**理由**换成了「带噪声」，不是「偏了三万多公里」。
 *
 * 而 v0.21.0 原本要解决的正是「我拿 App 的里程表和特斯拉 App 对，
 * 于是我怀疑车被人动了」。**并列两个数 = 制造那种怀疑。**
 *
 * ## 规则
 *
 * - **车端有值** ⇒ 只显示车端（那才是仪表盘上的里程表）；
 * - **车端读不到**（车休眠 / 没配 Fleet 凭据）⇒ 显示 GPS 估算，并**明说它是估算**。
 *
 * ⚠️ **限定语由服务端给**（`odometer_note`，ADR #41）——
 * 客户端不自己拼「（车端）」，也不自己写日期。
 */
/**
 * ⚠️ **v0.94.0**：里程表那一行拆成**数字部分**与**限定语**两半。
 *
 * 原来它是一个字符串、渲染在电量环右侧**窄栏**里 ——
 * 而「里程表 101,537 公里（GPS 估算）」需要约 168dp、窄栏只有约 171dp，
 * **差 2dp** ⇒ 贪心折行把限定语劈成「（GPS 估」+「算）」（真机实测）。
 *
 * ## 为什么限定语必须**独立成节点**
 *
 * CJK 的折行规则允许在**任意两个汉字之间**断行 ⇒ 只要限定语和数字在**同一个段落**里，
 * 它就可能被劈开（「估算」→「估」+「算」）。
 * 加空格、加不换行空格都**挡不住**（汉字之间本来就没有空格可断，
 * 而不换行空格插在汉字之间会**多出一个空隙**）。
 * 唯一的结构性解法：**限定语单独一个 Text 节点**（独立段落 ⇒ 只有整体换行，没有内部断点）。
 *
 * @return (数字部分, 限定语)
 */
fun odometerParts(gpsKm: Double?, vehicleKm: Double?, note: String? = null): Pair<String, String> {
    val km = vehicleKm ?: gpsKm
    return "里程表 ${km.fmtThousands()} 公里" to (note?.trim().orEmpty())
}

/**
 * 里程表那一行（完整一句话）。
 *
 * ⚠️ 单测盯的是它（`OdometerLineTest`）；界面用的是 [odometerParts] 的两半。
 * 两者由**同一个函数**派生 ⇒ 不会各写各的、口径漂移。
 */
fun odometerLine(gpsKm: Double?, vehicleKm: Double?, note: String? = null): String =
    odometerParts(gpsKm, vehicleKm, note).let { (num, qualifier) -> num + qualifier }

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
