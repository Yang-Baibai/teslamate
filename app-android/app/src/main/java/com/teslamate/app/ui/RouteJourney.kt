package com.teslamate.app.ui

import com.teslamate.app.Charge
import com.teslamate.app.Drive
import com.teslamate.app.firstSegment
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * ⚠️ **v0.26.0（第 17 轮长途自驾党 P0-1）：「线路」从一趟 drive 抬到一段 journey。**
 *
 * ## 为什么必须抬
 *
 * 原先把「一条线路」当成**一趟 drive**（按起止地名分组，取最近那一趟）。
 * 这在数据上是错的 —— **充电必然打断行程**：
 *
 * 实测 78/78 场充电都落在某趟 drive 的**起点或终点**附近（最近距离 ≤ 0.022 km），
 * 也就是说：**一趟 drive 内部永远不会发生充电**。
 *
 * 后果（复刻 App 的实际输出）：
 * 1. 「沿途充电站」列出来的其实是**上一趟的收尾充电** ——
 *    它落在本趟的起点里程上，里程窗口把它捞进来了。
 * 2. 本趟**真正**的那次充电落在终点里程上，被判成「终点处的充电」而被剔除。
 * 3. 于是「站间距离」「到下一站剩多少电」全建立在**错位的站**上。
 *
 * 「11 条 ≥200 km 的线路」其实是**同一次长途被充电切碎的 11 段**。
 *
 * ## journey 是什么
 *
 * **一次连续出门**：从离开家/常驻点，到下一次回到那里。
 *
 * 用两个可验证的判据切分：
 *
 * 1. **里程连续**：相邻两趟之间里程表缺口 ≤ [MAX_GAP_KM]，
 *    且时间间隔 ≤ [MAX_GAP_HOURS]。
 * 2. **「回家了」**：充电点**重复出现**且相隔 > [HOME_RETURN_KM] ——
 *    这是「出门」的真正终点。实测本机家充点「石化大道西」7 次充电
 *    分布在 08-01~10-24，每次间隔 235~976 公里。
 *
 * 判据 1 单独用会把「一次 19 天的自驾 + 中途在当地开了 4 天」连成一条
 * （实测 09-25~09-28 在丽江一带零散开了 600 公里，里程完全连续）；
 * 判据 2 才能把「回到常驻点」这个语义边界找出来。
 *
 * ## 沿途站怎么归属
 *
 * 充电场次落在**两段之间的缺口**里（实测：充电里程精确等于上一段的 `end_km`，
 * 或落在到下一段 `start_km` 之前的绕行里，最大 31 公里）。
 *
 * 所以：
 * - 缺口里的充电 = **沿途站**（这一趟路上的补电点）
 * - 最后一段之后的充电 = **到达后的那次充电**（不属于「沿途」）
 */
object RouteJourneyLimits {
    /** 相邻两趟之间的里程缺口上限（公里）。实测同一次出门的最大缺口是 31 公里（去充电桩的绕行）。 */
    const val MAX_GAP_KM = 60.0

    /** 相邻两趟之间的时间间隔上限（小时）。超过就是「过了一夜/几天」，算另一段出门。 */
    const val MAX_GAP_HOURS = 36.0

    /**
     * 「回到常驻点」的判据：同一个充电点相隔超过这么多公里就算「回来了」。
     *
     * 实测本机：家充点两次充电相隔 235~976 公里；
     * 而同一趟途中的**连续两次**充电（修文县 4 次、金田服务区 4 次）
     * 相隔都在 **2.5 公里以内** —— 所以 50 公里能干净地把两者分开。
     */
    const val HOME_RETURN_KM = 50.0

    /** 充电与行程边界的匹配容差（公里）。实测充电里程与上一段 `end_km` 的误差 ≤ 0.1 公里。 */
    const val BOUNDARY_TOL_KM = 3.0
}

/** 旅程中的一段（一趟连续行驶）。 */
data class JourneyLeg(
    val driveId: Int,
    val startKm: Double,
    val endKm: Double,
    val distanceKm: Double,
    val startSoc: Int?,
    val endSoc: Int?,
    val startPlace: String?,
    val endPlace: String?,
    val startDate: Instant,
)

/** 旅程中的一个充电停靠。 */
data class JourneyStop(
    val charge: Charge,
    /** 相对旅程起点的公里数 */
    val kmAlong: Double,
    /** 这一段是「到达之后的充电」吗（不属于沿途） */
    val isEndpoint: Boolean,
)

/** 一次连续出门（一段 journey）。 */
data class Journey(
    val key: String,
    val fromLabel: String,
    val toLabel: String,
    val legs: List<JourneyLeg>,
    /** 沿途充电站（按里程升序） */
    val stops: List<JourneyStop>,
    val startKm: Double,
    val endKm: Double,
    val startDate: Instant,
    val endDate: Instant,
) {
    val distanceKm: Double get() = endKm - startKm
    val legCount: Int get() = legs.size
    /** 沿途站数量（不含到达后的那次） */
    val midStopCount: Int get() = stops.count { !it.isEndpoint }
}

/**
 * 构建 journey 列表。
 *
 * @param minDistanceKm 多远算「长途」（默认 [RouteJourneyLimits] 之外的 200 公里）
 */
fun buildJourneys(
    drives: List<Drive>,
    charges: List<Charge>,
    minDistanceKm: Double = 200.0,
): List<Journey> {
    val legs = drives.mapNotNull { d ->
        val s = d.odometer_start ?: return@mapNotNull null
        val e = d.odometer_end ?: return@mapNotNull null
        if (e <= s) return@mapNotNull null
        JourneyLeg(
            driveId = d.id,
            startKm = s,
            endKm = e,
            distanceKm = d.distance_km ?: (e - s),
            startSoc = d.start_soc,
            endSoc = d.end_soc,
            startPlace = d.start_geofence ?: d.start_place?.firstSegment(),
            endPlace = d.end_geofence ?: d.end_place?.firstSegment(),
            startDate = d.start_date,
        )
    }.sortedBy { it.startKm }

    if (legs.isEmpty()) return emptyList()

    // ── 充电点「是否回家」的判定 ──────────────────────────────
    //
    // 按里程顺序走一遍充电记录，记下每个地点上次出现的里程。
    // 再次出现且相隔 > HOME_RETURN_KM → 这一次是「回到常驻点」。
    val chargesByOdo = charges
        .filter { it.start_odometer_km != null }
        .sortedBy { it.start_odometer_km }
    val lastSeenAt = HashMap<String, Double>()
    val homeReturn = HashSet<Int>()
    for (c in chargesByOdo) {
        val odo = c.start_odometer_km ?: continue
        val key = placeKey(c)
        val prev = lastSeenAt[key]
        if (prev != null && odo - prev > RouteJourneyLimits.HOME_RETURN_KM) {
            homeReturn += c.id
        }
        lastSeenAt[key] = odo
    }

    // ── 按连续性切 journey ────────────────────────────────────
    val chains = mutableListOf<MutableList<JourneyLeg>>()
    var cur = mutableListOf<JourneyLeg>()
    for (leg in legs) {
        if (cur.isNotEmpty()) {
            val prev = cur.last()
            val gapKm = leg.startKm - prev.endKm
            val gapH = ChronoUnit.HOURS.between(prev.startDate, leg.startDate).toDouble()
            val brokeByGap = gapKm > RouteJourneyLimits.MAX_GAP_KM || gapH > RouteJourneyLimits.MAX_GAP_HOURS
            // 「回家」也要断：上一段结束时车回到了常驻点并充了电。
            val homeBack = chargesByOdo.any { c ->
                val odo = c.start_odometer_km ?: return@any false
                odo >= prev.endKm - RouteJourneyLimits.BOUNDARY_TOL_KM &&
                    odo <= leg.startKm + RouteJourneyLimits.BOUNDARY_TOL_KM &&
                    c.id in homeReturn
            }
            if (brokeByGap || homeBack) {
                chains += cur
                cur = mutableListOf()
            }
        }
        cur += leg
    }
    if (cur.isNotEmpty()) chains += cur

    // ── 给每条 journey 分配沿途站 ────────────────────────────
    return chains.mapNotNull { chain ->
        if (chain.size < 2) return@mapNotNull null
        val startKm = chain.first().startKm
        val endKm = chain.last().endKm
        if (endKm - startKm < minDistanceKm) return@mapNotNull null

        val stops = mutableListOf<JourneyStop>()
        // 每一段**之后**的缺口里的充电 = 这一段的补电点。
        for (i in chain.indices) {
            val leg = chain[i]
            val nextStart = chain.getOrNull(i + 1)?.startKm ?: (leg.endKm + RouteJourneyLimits.BOUNDARY_TOL_KM)
            for (c in chargesByOdo) {
                val odo = c.start_odometer_km ?: continue
                if (odo < leg.endKm - RouteJourneyLimits.BOUNDARY_TOL_KM) continue
                if (odo > nextStart + RouteJourneyLimits.BOUNDARY_TOL_KM) continue
                // 同一段之后的多个充电（如「先充 30 度再往前走 13 公里再充一次」）都算沿途站。
                if (stops.any { it.charge.id == c.id }) continue
                stops += JourneyStop(
                    charge = c,
                    kmAlong = (odo - startKm).coerceAtLeast(0.0),
                    isEndpoint = i == chain.lastIndex,
                )
            }
        }
        stops.sortBy { it.kmAlong }

        val first = chain.first()
        val last = chain.last()
        Journey(
            key = "journey-${first.driveId}-${last.driveId}",
            fromLabel = first.startPlace ?: "起点",
            toLabel = last.endPlace ?: "终点",
            legs = chain.toList(),
            stops = stops,
            startKm = startKm,
            endKm = endKm,
            startDate = first.startDate,
            endDate = last.startDate,
        )
    }.sortedByDescending { it.startDate }
}

/**
 * 充电地点的**稳定键**。
 *
 * ⚠️ 不能只用展示名：`place` 是「石化大道西, 西区街道, 惠州大亚湾…」这种长串；
 * 而 `poi_name` 也**会撞名** —— 实测「中国石化」在 3 个不同城市都叫这个名字
 * （26.8761/100.2446、28.2549/99.275、23.7339/112.9761）。
 *
 * 也不能只用坐标：**坐标会抖**。实测同一个家充点的 7 次充电，
 * 经度分别是 114.4884 / 114.4883 / 114.4886 —— 四舍五入到 3 位（≈111 米）
 * 时最后一次会变成 114.489，于是「回到家」就漏判了。
 *
 * 所以用**组合键**：地点名 + 坐标四舍五入到 **2 位（≈1.1 公里）**。
 *
 * 实测验证：
 * - 家充点「石化大道西」7 次 → 全部同一个键 ✓（22.74, 114.49）
 * - 家充点「宝田一路」2 次 → 同一个键 ✓（22.60, 113.88）
 * - 途中的「修文县」4 次 → 两个键（26.85 / 26.83，差 1.8 公里是不同车位），
 *   但它们相隔不到 50 公里，**本来也不会**触发「回来了」
 * - 「中国石化」3 个城市 → 3 个键 ✓
 */
private fun placeKey(c: Charge): String {
    val name = c.poi_name?.takeIf { it.isNotBlank() }
        ?: c.geofence_name?.takeIf { it.isNotBlank() }
        ?: c.place?.firstSegment()
        ?: "?"
    val lat = if (c.map_lat != null) Math.round(c.map_lat * 100.0) / 100.0 else "x"
    val lng = if (c.map_lng != null) Math.round(c.map_lng * 100.0) / 100.0 else "x"
    return "$name@$lat,$lng"
}

/** 旅程的「一句话摘要」（卡片副标题）。 */
fun Journey.summary(): String {
    val parts = mutableListOf<String>()
    parts += "${distanceKm.roundToInt()} 公里"
    parts += "$legCount 段"
    if (midStopCount > 0) parts += "$midStopCount 次沿途充电"
    return parts.joinToString(" · ")
}
