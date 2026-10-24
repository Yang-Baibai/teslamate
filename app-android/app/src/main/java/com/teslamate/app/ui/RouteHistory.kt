package com.teslamate.app.ui

import com.teslamate.app.Drive
import com.teslamate.app.medianOf
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * **同线路历史对比** —— 「这趟比上趟好吗？」
 *
 * ⚠️ v0.17.0（车队运营评审 P0-1）：
 *
 * 原来行程详情只给**本趟**的绝对值（162 Wh/km / 8.1 度 / 55 分钟），
 * 全项目没有一处「上趟 / 历史中位」用于行程。
 * 而分组信息**其实已经算出来了** —— `buildRouteTemplates` 按起止分组 ——
 * 只是取出来之后就丢掉了。数据齐了，只差翻译成一句话。
 *
 * ## 口径（本项目一贯的「口径诚实」规范）
 *
 * - **基准**：同线路 Wh/km 的**中位数**（样本 ≥ 3）；只有 2 次时降级为「上趟」；
 *   只有 1 次时**不给结论**。
 * - **归一**：按**速度档**比较（本趟与基准必须落在同一档）——
 *   市区 162 和高速 197 没有可比性。
 * - **归一**：总耗时与总电量按**里程折算**（每百公里），否则长的一趟天然「更费」。
 * - **标注不归一**：**室外均温**差 ≥ 5°C 时不给百分比结论（只标注）；
 *   **里程**差 > ±10% 视为改道（只标注）。
 * - **持平阈值（双门槛，取更严者判持平）**：
 *   `|差值| ≤ 3%` **或** `整趟的能耗差 < 0.3 度`。
 *   绝对量门槛专门用来杀掉短途的假进步
 *   （5 km 的一趟差 12%，换算成电量只有 0.08 度 —— 那不是进步）。
 *
 * 为什么是 3% 而不是充电桩用的 8%：桩功率是同桩重复测量、重复性高；
 * Wh/km 受速度 / 温度 / 路况扰动大，噪声底更高，必须取更严。
 */
data class RouteComparison(
    /** 主结论行（照抄可用） */
    val headline: String,
    /** 灰字注记（温度差 / 改道 / 跨档）；没有就 null */
    val note: String? = null,
    /** 是否给出了百分比结论（false = 只给注记） */
    val hasVerdict: Boolean = true,
    /** 这条线路上本趟是第几次（含本趟） */
    val ordinal: Int = 1,
    /** 简称：「省 8%」/「多耗 12%」/「持平」/ null（不可比） */
    val verdictShort: String? = null,
)

/** 列表卡底部的一行灰字摘要：「同线路第 3 次 · 比常跑水平省 8%」。 */
fun RouteComparison.shortLabel(): String = buildString {
    append("同线路第 $ordinal 次")
    verdictShort?.let { append(" · $it") }
}

/** 线路分组的 key：起点键 + 终点键。 */
data class RouteKey(val start: String, val end: String) {
    override fun toString(): String = "$start→$end"
}

/**
 * 取一条行程的线路 key。
 *
 * ⚠️ 围栏名优先（车主自己定义的，天然唯一）；没有围栏名就用**地址首段**，
 * 并**去掉门牌号**（「永丰社区 2 门」与「永丰社区」是同一个地方）。
 *
 * ⚠️ 两端都取不到时返回 null —— **不能**返回 `"?"`：
 * 那样所有「没地址」的行程会聚成同一组，比较出来的结论毫无意义。
 */
fun routeKeyOf(d: Drive): RouteKey? {
    val s = endpointKeyOf(d.start_geofence, d.start_place) ?: return null
    val e = endpointKeyOf(d.end_geofence, d.end_place) ?: return null
    return RouteKey(s, e)
}

private fun endpointKeyOf(geofence: String?, place: String?): String? {
    geofence?.takeIf { it.isNotBlank() }?.let { return "gf:$it" }
    val first = place?.split('，', ',')?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
        ?: return null
    val cleaned = first.replace(Regex("\\d+"), "").trim().ifBlank { first }
    return "nm:$cleaned"
}

/** 按线路分组（每组按时间倒序，最新在前）。 */
fun buildRouteHistory(drives: List<Drive>): Map<RouteKey, List<Drive>> =
    drives.mapNotNull { d -> routeKeyOf(d)?.let { it to d } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, v) -> v.sortedByDescending { it.start_date } }

/**
 * 把一趟行程与**同线路的历史**比一比。
 *
 * @param drive 本趟
 * @param history 同线路的全部行程（**含本趟**）
 * @return null = 没有可比的历史（只跑过 1 次）
 */
fun compareWithRouteHistory(
    drive: Drive,
    history: List<Drive>,
): RouteComparison? {
    val others = history.filter { it.id != drive.id }
    if (others.isEmpty()) return null

    val curWh = drive.wh_per_km ?: return null
    val curKm = drive.distance_km ?: return null
    if (curKm <= 0.0) return null

    // ⚠️ v0.20.0（充电数据专家 P0-1）：**当前趟也要过值域护栏**。
    //
    // 原来基准样本做了 `wh in 50.0..400.0` 过滤，**当前趟却没有** ——
    // 规则不对称，于是：
    //   本趟 wh_per_km = −55（标定续航不降反升，物理上不可能）
    //   基准 = 150 Wh/km
    //   → 结论「比上趟省 137%」—— **胡说**。
    //
    // 实测：本机 440 条行程里有 14 条 wh_per_km ≤ 0，
    // 其中 12 条常跑线路含至少一条非正能耗行程。
    //
    // 现在当前趟与基准用**同一套**护栏；不合格就不给结论，
    // 而不是给一个看着有依据、实则错误的百分比。
    if (curWh !in 50.0..400.0) return null

    // 基准样本：同线路、Wh/km 有值、里程 ≥ 20 km（太短的那趟本身噪声大）
    val usable = others.filter {
        val wh = it.wh_per_km ?: return@filter false
        val km = it.distance_km ?: return@filter false
        km >= 20.0 && wh in 50.0..400.0
    }
    if (usable.isEmpty()) return null

    // 样本 ≥ 3 → 用中位数（常跑水平）；只有 2 次 → 降级为「上趟」
    val useMedian = usable.size >= 3
    val baseWh = if (useMedian) {
        medianOf(usable.map { it.wh_per_km!! }) ?: return null
    } else {
        usable.first().wh_per_km!!
    }
    val baseLabel = if (useMedian) "这条线路的常跑水平" else "上趟"

    // ── 归一 1：速度档 ──────────────────────────────────
    // 本趟与基准必须落在同一档，否则市区和高速的 Wh/km 没有可比性。
    val curSpeed = drive.avg_speed_kph
    val baseSpeeds = usable.mapNotNull { it.avg_speed_kph }
    val baseSpeed = medianOf(baseSpeeds.map { it.toDouble() })
    var note: String? = null
    var comparable = true
    if (curSpeed != null && baseSpeed != null) {
        val curBucket = speedBucketLabel(curSpeed)
        val baseBucket = speedBucketLabel(baseSpeed.roundToInt())
        if (curBucket != baseBucket) {
            comparable = false
            note = "这趟均速 $curSpeed km/h（${curBucket}档），历史中位均速 " +
                "${baseSpeed.roundToInt()} km/h（${baseBucket}档）· 两档能耗不可直接比"
        }
    }

    // ── 标注（不阻断）：里程差 ───────────────────────────────
    //
    // ⚠️ 这里**不改**评审给的「>±10% 判改道、不给百分比」的方案 ——
    // 但把它从**阻断项**降为**标注项**，理由是：
    //
    // Wh/km **本身就是按距离归一**的量。同一对起终点，33 公里和 42 公里
    // 两次跑的每公里能耗仍然可比 —— 里程差影响的是**总量**对比
    // （「这趟多用了 3 度电」），不是**每公里**对比。
    //
    // 真正会让每公里能耗不可比的是**路况档次变了**（市区 vs 高速），
    // 那个由上面的**速度档**检查负责。
    //
    // 实测：本机「上水径路 → 新湖路」三次分别是 48.4 / 36.0 / 33.4 公里，
    // 按原来的方案三次全部被判「路线有变化，不宜直接比」—— 功能等于没有。
    val baseKm = medianOf(usable.mapNotNull { it.distance_km }) ?: curKm
    if (baseKm > 0) {
        val kmDiff = (curKm - baseKm) / baseKm
        if (abs(kmDiff) > 0.10) {
            note = (note?.plus(" · ") ?: "") +
                "上趟 ${baseKm.roundToInt()} 公里，本趟 ${curKm.roundToInt()} 公里" +
                "（${if (kmDiff > 0) "+" else ""}${(kmDiff * 100).roundToInt()}%）· 路线略有变化"
        }
    }

    // ── 标注（不归一）：室外均温 ─────────────────────────────
    // 温度差 ≥ 5°C 时不给百分比结论 —— 空调的影响大到能盖过 3% 的门槛。
    val curTemp = drive.outside_temp_avg
    val baseTemp = medianOf(usable.mapNotNull { it.outside_temp_avg })
    if (curTemp != null && baseTemp != null && abs(curTemp - baseTemp) >= 5.0) {
        comparable = false
        note = (note?.plus(" · ") ?: "") +
            "室外均温差 ${abs(curTemp - baseTemp).roundToInt()}°C" +
            "（这趟 ${curTemp.roundToInt()}°C，基准 ${baseTemp.roundToInt()}°C）· " +
            "空调可能影响能耗，暂不比较"
    }

    val curKwh100 = curWh / 10.0
    val baseKwh100 = baseWh / 10.0

    // ── 差值与持平判定 ──────────────────────────────────
    val diff = (curWh - baseWh) / baseWh
    val diffPct = (diff * 100).roundToInt()
    // 绝对量门槛：整趟的能耗差 < 0.3 度 → 算持平
    val absKwhDiff = abs(curWh - baseWh) / 1000.0 * curKm
    val flat = abs(diff) <= 0.03 || absKwhDiff < 0.3

    val headline = when {
        !comparable ->
            "这趟 ${fmtKwh(curKwh100)} kWh/100km（基准 ${fmtKwh(baseKwh100)}）· 先看看上面的说明"
        flat ->
            "与${baseLabel}持平（${fmtKwh(curKwh100)} kWh/100km，基准 ${fmtKwh(baseKwh100)}）"
        diff < 0 ->
            "比${baseLabel}省 ${-diffPct}%" +
                "（${fmtKwh(curKwh100)} kWh/100km，基准 ${fmtKwh(baseKwh100)}" +
                (if (useMedian) " · ${usable.size} 次" else "") + "）"
        else ->
            "比${baseLabel}多耗 ${diffPct}%" +
                "（${fmtKwh(curKwh100)} kWh/100km，基准 ${fmtKwh(baseKwh100)}" +
                (if (useMedian) " · ${usable.size} 次" else "") + "）"
    }

    // 列表卡用的一行简称
    val verdictShort = when {
        !comparable -> null
        flat -> "与${baseLabel}持平"
        diff < 0 -> "省 ${-diffPct}%"
        else -> "多耗 ${diffPct}%"
    }

    return RouteComparison(
        headline = headline,
        note = note,
        hasVerdict = comparable,
        ordinal = history.size,
        verdictShort = verdictShort,
    )
}

/** 速度档名（与 [ConsumptionModel] 的分桶边界一致）。 */
fun speedBucketLabel(kph: Int): String = when {
    kph < 60 -> "市区"
    kph < 90 -> "中速"
    kph < 110 -> "高速"
    kph < 130 -> "快速"
    else -> "超高速"
}

private fun fmtKwh(v: Double): String {
    val s = String.format(java.util.Locale.CHINA, "%.1f", v)
    return s
}
