package com.teslamate.app.ui

import com.teslamate.app.Charge
import java.time.Instant

/**
 * ⚠️ **v0.181.0：这段判据已经搬到服务端**（api/route_plans.go）。
 *
 * 原来「哪些算一次长途出门」「沿途有哪些充电站」是在**这个文件**里算的
 * ⇒ ① **飞书里问不出来**（mission §2.1）；② 两端各算一套必然漂（ADR #38）。
 * ⇒ 现在**判定与阈值都在服务端**，本文件只剩**类型**与**映射**。
 * ⚠️ 阈值（60 公里 / 36 小时 / 120 公里 / 6 小时 / 3 公里 / 1 公里 / 3 公里 / 200 公里）
 * 现在**只有服务端那一份**（api/route_plans.go 的 route* 常量）—— 客户端不再留副本。
 *
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
    /**
     * ⚠️ **v0.36.0（第 21 轮一线车主复审 C1）：到这个站时的**真实**电量。**
     *
     * ## 为什么不能用 [socBefore]
     *
     * `socBefore` 是**充电会话的插枪电量**（`charging_processes.start_battery_level`），
     * 而「上次实测到站剩 Y%」要的是**车开到这儿时的电量**。
     *
     * 两者在**多数**场次相同，但有三个地方会错开：
     *
     * ① **合并时取错场次**：`sqlCharges` 是 `ORDER BY cp.start_date DESC`（新的在前），
     *    客户端按里程**稳定排序** ⇒ 同一里程的两场充电里**后一场排在前**；
     *    合并分支保留 `prevStop.socBefore` ⇒ 留的是**后一场**的插枪电量。
     *    实测 35 站里 **6 站取错**，而且错的方向**一致地让人更放心**
     *    （梧硕高速显示 27%、真值 8%；长征大道显示 94%、真值 39%）。
     *
     * ② **0 度空会话被 API 过滤**：`charge_energy_added > 0.01` 会滤掉 9 场空会话，
     *    那一站的插枪电量就整个丢了（修文县差 1 个点）。
     *
     * ③ **插枪前可能已经走过一段**：插枪电量 ≠ 到站电量。
     *
     * ## 正确的真值
     *
     * **以该站里程为终点的那一趟行程的 `end_soc`**
     * （服务端 `drives.end_soc` = `positions.battery_level` @ `end_position_id`）——
     * 它就是车**开到**这个充电点时的电量。
     */
    val arrivalSoc: Int? = null,
)
/**
 * ⚠️ **v0.181.0：把服务端的线路结论映射成界面用的类型。**
 *
 * ## 为什么是「映射」而不是「各算一套」
 *
 * 「哪些算一次长途出门」「沿途有哪些充电站」这套判据有 7 个阈值 ＋ 7 处评审修出来的坑，
 * 原来只写在客户端 ⇒ ① 飞书里问不出来；② 两端各算一套必然漂（ADR #38）。
 * ⇒ 现在**判定在服务端**（api/route_plans.go），App 只透传。
 *
 * ## 沿途站怎么拿到充电记录
 *
 * 服务端只下发 `charge_id`（不下发整条充电记录 ⇒ JSON 不臃肿）。
 * 客户端按 id 从**自己那份**充电列表里找 —— 显示名、起止电量全部本地取，
 * 与原来完全一致。找不到时（服务端比客户端新，刚多了一场充电）⇒ 用服务端附带的
 * 显示字段兜底，**不丢站**（丢站会让余量表少一站 ⇒ 结论直接错）。
 */
fun routeTemplatesFromServer(
    dtos: List<com.teslamate.app.RoutePlanTemplateDto>,
    charges: List<Charge>,
): List<RouteTemplate> {
    val byId = charges.associateBy { it.id }
    return dtos.map { dto ->
        RouteTemplate(
            key = dto.key,
            fromLabel = dto.from_label,
            toLabel = dto.to_label,
            distanceKm = dto.distance_km,
            startSoc = dto.start_soc,
            endSoc = dto.end_soc,
            legCount = dto.leg_count,
            totalLegCount = dto.total_leg_count,
            stopCount = dto.stop_count,
            lastDrivenAt = dto.last_driven_at,
            startOdoKm = dto.start_odo_km,
            lastDistanceKm = dto.last_distance_km,
            avgSpeedKph = dto.avg_speed_kph,
            isRoundTrip = dto.is_round_trip,
            lastStartSoc = dto.last_start_soc,
            lastEndSoc = dto.last_end_soc,
            lastDriveId = dto.last_drive_id,
            legIds = dto.leg_ids,
            legs = dto.legs.map { leg ->
                JourneyLeg(
                    driveId = leg.drive_id,
                    startKm = leg.start_km,
                    endKm = leg.end_km,
                    distanceKm = leg.distance_km,
                    startSoc = leg.start_soc,
                    endSoc = leg.end_soc,
                    startPlace = leg.start_place,
                    endPlace = leg.end_place,
                    startDate = leg.start_date,
                    endDate = leg.end_date,
                    durationMin = leg.duration_min,
                    startPlaceKey = leg.start_place_key,
                    endPlaceKey = leg.end_place_key,
                    ascentM = leg.ascent_m,
                    descentM = leg.descent_m,
                )
            },
            stops = dto.stops.map { stop ->
                val local = byId[stop.charge_id]
                val meta = stop.charge_meta
                JourneyStop(
                    charge = local ?: Charge(
                        id = stop.charge_id,
                        start_date = meta.start_date,
                        place = meta.place,
                        poi_name = meta.poi_name,
                        place_label = meta.place_label,
                        place_key = meta.place_key,
                        geofence_name = meta.geofence_name,
                        map_lat = meta.map_lat,
                        map_lng = meta.map_lng,
                        start_soc = meta.start_soc,
                        end_soc = meta.end_soc,
                        start_odometer_km = meta.start_odometer_km,
                    ),
                    kmAlong = stop.km_along,
                    isEndpoint = stop.is_endpoint,
                    socBefore = stop.soc_before,
                    socAfter = stop.soc_after,
                    arrivalSoc = stop.arrival_soc,
                )
            },
        )
    }
}


