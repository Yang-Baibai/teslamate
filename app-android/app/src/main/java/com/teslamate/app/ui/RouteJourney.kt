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
 * 实测 App 能看到的 **69 场**充电全部落在某趟 drive 的**起点或终点**附近
 * （距离中位 0、最大 0.0599 km；库里另有 9 场是充入 0 度的空会话，App 看不到）。
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
 *    这是「出门」的真正终点。实测本机常驻充电点「石化大道西」7 次充电
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
     * ⚠️ **v0.28.0（复审 P1-7）：50 → 120 公里。**
     *
     * 原来 50 公里会**误判**：实测「印象雅緻酒店」两场相隔 **59.97 公里**
     * 被判成「回到常驻点」，把 3551 公里的长途**从中间切成了两段**。
     *
     * 但那两场其实是**同一个地点、同一段数据异常**：
     * 两场充电的坐标逐位相同（30.01848, 101.53022），里程却差了 59.9 公里 ——
     * 而这 59.9 公里**中间没有任何行程记录**（里程表走了，行程表是空的）。
     * 所以它不是「开出去又开回来」，是**里程表跳了一段**。
     *
     * 本机真实的「回到常驻点」间隔实测是 **231.5 / 376.9 / 760.5 / 820.1 /
     * 1326.5 / 7746.6 公里** —— 120 公里能干净地把它们全部分出来，
     * 又把 59.97 那个异常排除在外。
     */
    const val HOME_RETURN_KM = 120.0

    /**
     * 「回到常驻点」还要求**时间上也隔开了**（至少 6 小时）。
     *
     * 同一个桩在 5 分钟内插拔两次（实测 ids 7/8、16/17、43/44 …）是**一次插枪**
     * 被 TeslaMate 切成两段，不是「回来了」。
     */
    const val HOME_RETURN_MIN_HOURS = 6.0

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
    /**
     * ⚠️ v0.28.0（复审 P0-2）：这一段的**结束时刻** ——
     * 沿途站的归属必须同时看**里程**和**时间**：
     * 只看里程会把 62 小时后的另一场充电误判成「这一段的到达充电」。
     */
    val endDate: Instant,
    /**
     * ⚠️ **v0.30.0（长途自驾党 P0-1）：这一趟的时长（分钟）。**
     *
     * 用来算 journey 的**真实均速** ——
     * 规划链路原来把计划车速**硬编码成 90 km/h**，
     * 于是「到下一站剩 34%」是按一个跟这趟毫无关系的速度算的。
     */
    val durationMin: Int? = null,
    /**
     * ⚠️ **v0.31.0：归一后的地点标识**（形如 `g1` / `a24`）。
     *
     * 用来判断「这一趟的起点与终点是不是**同一个地方**」。
     */
    val startPlaceKey: String? = null,
    /** 同上：终点 */
    val endPlaceKey: String? = null,
    /**
     * ⚠️ **v0.31.0：这一段的爬升（米）。**
     *
     * 口径 = `drives.ascent`（原样，不去噪）——
     * 实测它**逐条**等于「相邻点正高差之和」（439/439，最大差 0 米）。
     *
     * ⚠️ 含 GPS 量化噪声：长途大段偏差只有 0.8%~2.3%（够准），
     * 短途行程中位虚高 17.1% —— 所以**只适合长途场景**。
     */
    val ascentM: Int? = null,
    /** 同上：下降（米） */
    val descentM: Int? = null,
)

/** 旅程中的一个充电停靠。 */
data class JourneyStop(
    val charge: Charge,
    /** 相对旅程起点的公里数 */
    val kmAlong: Double,
    /** 这一段是「到达之后的充电」吗（不属于沿途） */
    val isEndpoint: Boolean,
    /**
     * ⚠️ v0.28.0（复审 P0-3）：到站时的电量（%）。
     *
     * TeslaMate 有时把**一次插枪**切成两个 process，
     * 合并后 socBefore 取最早那场、socAfter 取最晚那场。
     */
    val socBefore: Int? = null,
    /** 充完后的电量（%） */
    val socAfter: Int? = null,
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
    /** ⚠️ v0.31.0：归一后的起点地点标识 */
    val fromKey: String? = null,
    /** ⚠️ v0.31.0：归一后的终点地点标识 */
    val toKey: String? = null,
) {
    /**
     * ⚠️ **v0.31.0：这一趟是不是「回到了同一个地方」。**
     *
     * 实测：6227 公里那条的起点与终点是**同一个小区**
     * （「永丰社区」与「新湖路」相距 **300 米**，被地理编码拆成了两个名字），
     * 原来 App 把它显示成「永丰社区 → 新湖路」的**单程**线路 ——
     * 读起来像「从 A 开到 B」，而实际上是**往返**。
     */
    val isRoundTrip: Boolean
        get() = fromKey != null && toKey != null && fromKey == toKey
    val distanceKm: Double get() = endKm - startKm
    val legCount: Int get() = legs.size

    /**
     * ⚠️ **v0.28.0（复审 P1-10）：「N 段」要排除挪车微行程。**
     *
     * 实测：一次 118 段的旅程里有 **38 段**不到 2 公里 ——
     * 那是**充电站内挪车**（0.12~0.4 公里），不是「路被切成 118 段」。
     * 不排除的话，「118 段 · 40 次沿途充电」读起来像「这趟路特别碎」。
     */
    val meaningfulLegCount: Int get() = legs.count { it.distanceKm >= 2.0 }
    /** 沿途站数量（不含到达后的那次） */
    val midStopCount: Int get() = stops.count { !it.isEndpoint }

    /**
     * ⚠️ **v0.30.0（长途自驾党 P0-1）：这段旅程的**真实均速**（km/h）。**
     *
     * 规划链路原来把计划车速**硬编码成 90 km/h** ——
     * 于是「到下一站剩 34%」是按一个**跟这趟毫无关系**的速度算的。
     *
     * 实测两段长途的真实均速差很多：
     * - 6227 公里那条（川藏滇）**64 km/h**
     * - 616 公里那条 **42 km/h**
     *
     * 用各自的真实均速算，能耗档位才对得上（90 km/h 档 183 Wh/km vs 110–130 档 197.4，
     * 差 **7.9%**，在 128 公里的站距上就是 **2.8 个百分点**的到达电量差）。
     *
     * 只统计**有里程**的段（挪车微行程的时长是噪声）。
     */
    val avgSpeedKph: Double?
        get() {
            val legsWithTime = legs.filter { (it.durationMin ?: 0) > 0 && it.distanceKm >= 2.0 }
            if (legsWithTime.isEmpty()) return null
            val km = legsWithTime.sumOf { it.distanceKm }
            val min = legsWithTime.sumOf { it.durationMin!! }
            if (km <= 0.1 || min <= 0) return null
            return km / (min / 60.0)
        }
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
            // ⚠️ v0.28.0（复审 P0-2）：结束时刻用于沿途站的**时间**归属。
            // 库里 end_date 可能为 NULL（车还在开），退回开始时刻。
            endDate = d.end_date ?: d.start_date,
            // ⚠️ v0.30.0：带上时长，用于算 journey 的真实均速
            durationMin = d.duration_min,
            // ⚠️ v0.31.0：带上归一后的地点标识（判断是不是往返）
            startPlaceKey = d.start_place_key,
            endPlaceKey = d.end_place_key,
            // ⚠️ v0.31.0：带上爬升/下降（官方 App 给不了的数）
            ascentM = d.ascent_m,
            descentM = d.descent_m,
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
    val lastSeenAt = HashMap<String, Pair<Double, Instant>>()
    val homeReturn = HashSet<Int>()
    for (c in chargesByOdo) {
        val odo = c.start_odometer_km ?: continue
        val key = placeKey(c)
        val prev = lastSeenAt[key]
        if (prev != null) {
            val kmApart = odo - prev.first
            val hoursApart = ChronoUnit.HOURS.between(prev.second, c.start_date).toDouble()
            // ⚠️ v0.28.0（复审 P1-8）：同时看**时间** ——
            // 同一个桩在几分钟内插拔两次（一次插枪被切成两个 process）不是「回来了」。
            if (kmApart > RouteJourneyLimits.HOME_RETURN_KM &&
                hoursApart >= RouteJourneyLimits.HOME_RETURN_MIN_HOURS
            ) {
                homeReturn += c.id
            }
        }
        lastSeenAt[key] = odo to c.start_date
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
        // ⚠️ **v0.28.0（复审 P0-②）：必须维护「已处理」集合。**
        //
        // 原来只用 `stops.any { it.charge.id == c.id }` 判断「这一场是不是已经算过了」，
        // 而**被合并掉**的那一场（同一个插枪的第二个 process）的 id **失忆**了 ——
        // 于是它在下一段窗口里被**再处理一次**，代表记录会回退成
        // 「0.1 kWh 的尾段」→ 显示成「94% → 94%」（等于没充，实际充了 36.6 kWh）。
        val seenIds = HashSet<Int>()
        // 每一段**之后**的缺口里的充电 = 这一段的补电点。
        //
        // ⚠️ **v0.28.0（复审 P0-2）：归属必须同时看里程和**时间**。**
        //
        // 原来只按里程：`odo ∈ [本段终点 − 3, 下一段起点 + 3]`。
        // 实测 **charge#66**（odo 100506.55，**10-17 00:07**）落在 drive 455
        // （odo 100502.53，**10-14 09:37** 结束）之后 4.0 km ——
        // 里程上像「这一段的到达充电」，**时间上晚了 62.5 小时**，
        // 它其实是**下一段旅程的出发充电**。
        //
        // 后果：同一场充电被**两条 journey 各算一次**
        // （一次是 J5 的终点站，一次是 J6 的第 4 公里沿途站），
        // 而 J5 的终点站里程（206.9 km）**超过了它自己的总里程**（202.9 km）。
        //
        // 现在：缺口里的充电必须**时间上也落在这一段结束到下一段开始之间**；
        // 末段（到达充电）允许宽到 [段末, 段末 + 36 小时]。
        for (i in chain.indices) {
            val leg = chain[i]
            val next = chain.getOrNull(i + 1)
            val nextStart = next?.startKm ?: (leg.endKm + RouteJourneyLimits.BOUNDARY_TOL_KM)
            val notBefore = leg.endDate
            val notAfter = next?.startDate
                ?: leg.endDate.plusSeconds(3600L * RouteJourneyLimits.MAX_GAP_HOURS.toLong())
            for (c in chargesByOdo) {
                // ⚠️ **v0.29.0（复审 P0-1）：查重必须在判据**之后**。**
                //
                // 这里原来写的是 `if (!seenIds.add(c.id)) continue` ——
                // **无条件消费**：第 0 段扫一遍就把全库充电都标成「已处理」，
                // 后面 191 段每场都在第一行被跳过。
                //
                // 实测后果（两位评审独立复刻出同一个数）：
                //   6227 公里那条大环线真实沿途充电 **45 场**，App 显示 **0 个**；
                //   616 公里那条真实 13 场，显示 1 个 —— 而且那 1 个是**出发前**在家充的。
                //   决策句于是写「需充 1 次」—— **会让人少安排 22 次补电**。
                //
                // 正确做法：先判「这一场是不是已经算过了」，判据全过之后**才**落标记。
                if (c.id in seenIds) continue
                val odo = c.start_odometer_km ?: continue
                if (odo < leg.endKm - RouteJourneyLimits.BOUNDARY_TOL_KM) continue
                if (odo > nextStart + RouteJourneyLimits.BOUNDARY_TOL_KM) continue
                // ⚠️ 时间约束（上面解释的那条）
                if (c.start_date.isBefore(notBefore.minusSeconds(3600L))) continue
                if (c.start_date.isAfter(notAfter)) continue
                // ✅ 判据全过，现在才落「已处理」标记。
                seenIds.add(c.id)
                // ⚠️ **v0.28.0（复审 P0-3）：同一场充电被 TeslaMate 拆成两个 process 时要合并。**
                //
                // 实测全库有 **10 对**「同地点 + 同里程（或 ≤1 公里）」的相邻 process
                // （7/8、16/17、28/29、30/31、39/40、43/44、53/54、57/59、69/71、72/73）——
                // 它们是**一次插枪被切成两段**（比如插上没立刻开始充电）。
                //
                // 不合并的话：沿途站列表会**同一站列两行**，
                // 而 `verdict()` 的「需充 N 次」会**虚高**。
                //
                // 合并判据：**同一地点键 + 里程差 ≤ 1 公里**。
                // socBefore 取**首场**的 start_soc，socAfter 取**末场**的 end_soc。
                val merged = stops.indexOfFirst {
                    placeKey(it.charge) == placeKey(c) &&
                        kotlin.math.abs(it.charge.start_odometer_km!! - odo) <= 1.0
                }
                if (merged >= 0) {
                    val prevStop = stops[merged]
                    val later = if ((c.end_soc ?: 0) >= (prevStop.charge.end_soc ?: 0)) c else prevStop.charge
                    stops[merged] = prevStop.copy(
                        charge = later,
                        // ⚠️ 复审 P0-①：到站电量必须用**合并后**的值，
                        // 否则显示的是代表记录那一小段（如「94% → 94%」等于没充）。
                        socBefore = prevStop.socBefore,
                        socAfter = later.end_soc,
                    )
                    // 合并掉的那一场也要标记为已处理（否则它会被再处理一次）
                    seenIds.add(prevStop.charge.id)
                    continue
                }
                stops += JourneyStop(
                    charge = c,
                    kmAlong = (odo - startKm).coerceAtLeast(0.0),
                    isEndpoint = i == chain.lastIndex,
                    socBefore = c.start_soc,
                    socAfter = c.end_soc,
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
            // ⚠️ v0.31.0：归一后的起止地点标识（判断「往返」）
            fromKey = first.startPlaceKey,
            toKey = last.endPlaceKey,
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
 * 也不能只用坐标：**坐标会抖**。实测同一个常驻充电点的 7 次充电，
 * 经度分别是 114.4884 / 114.4883 / 114.4886 —— 四舍五入到 3 位（≈111 米）
 * 时最后一次会变成 114.489，于是「回到家」就漏判了。
 *
 * 所以用**组合键**：地点名 + 坐标四舍五入到 **2 位（≈1.1 公里）**。
 *
 * 实测验证：
 * - 常驻充电点「石化大道西」7 次 → 全部同一个键 ✓（22.74, 114.49）
 * - 常驻充电点「宝田一路」2 次 → 同一个键 ✓（22.60, 113.88）
 * - 途中的「修文县」4 次 → 两个键（26.85 / 26.83，差 1.8 公里是不同车位），
 *   但它们相隔不到 50 公里，**本来也不会**触发「回来了」
 * - 「中国石化」3 个城市 → 3 个键 ✓
 *
 * ⚠️ **v0.31.0：服务端已经按**真实距离**把 500 米内的地址聚类了，
 * 现在直接用它下发的 `place_key` —— 上面这套组合键有两个真问题：**
 *
 * 1. **舍入边界**：坐标四舍五入到 2 位（≈1.1 km）会把**相距 300 米**的两个点
 *    劈到两边 —— 实测「永丰社区」(22.5771) → 「22.58」而「新湖路」(22.5749) → 「22.57」，
 *    于是**同一个小区**被算成两个地方。
 * 2. **名字本身会变**：地理编码给同一个小区两个名字，
 *    组合键把名字也算进去 → 归一了坐标却没归一名字。
 *
 * 服务端的新做法：按真实球面距离聚类（500 m），
 * 取**被引用次数最多**的 display_name 作为该地点的统一名字，
 * 并下发 `place_key`（形如 `g1` / `a24`）。
 */
private fun placeKey(c: Charge): String {
    c.place_key?.takeIf { it.isNotBlank() }?.let { return it }
    // 服务端还没给（旧版本 API）时的兜底：老逻辑。
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
    // ⚠️ **v0.31.0（数据口径审计 P1-2）：段数与 `RoutePlan.conclusion()` 保持一致。**
    // 原来这里给**全部**段数（191），而结论句给 **≥2 公里**的段数（131）——
    // 同一个 journey 在两个地方显示不同段数，用户会以为有一个是错的。
    // 现在两个都给出，并说清差别（差的是充电站内挪车微行程）。
    parts += if (meaningfulLegCount < legCount) {
        "$legCount 段行程（其中 $meaningfulLegCount 段超过 2 公里）"
    } else {
        "$legCount 段"
    }
    if (midStopCount > 0) parts += "$midStopCount 次沿途充电"
    return parts.joinToString(" · ")
}
