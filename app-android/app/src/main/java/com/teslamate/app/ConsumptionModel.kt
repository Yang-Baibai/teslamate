package com.teslamate.app

import kotlinx.serialization.Serializable

/**
 * 实测能耗模型 —— 把 EPA 标定续航换算成**你自己跑出来的真实可达里程**。
 *
 * ## 为什么必须做
 *
 * 车况页原来显示「预估续航」，取 `est_range_km ?: ideal_range_km ?: rated_range_km`。
 * 实测发现这条链在本车**塌缩成了一个值**：
 *
 * | 字段 | 全库 1,596,924 行里的非空率 | 说明 |
 * |---|---|---|
 * | `est_battery_range_km` | **0.085%** | 只在数据采集的头两天出现过，之后再没有；且有值时只比 rated 高 1.6% |
 * | `ideal_battery_range_km` | 3.49% | 与 `rated_battery_range_km` **逐行完全相同**（55,669 行差异数 = 0） |
 * | `rated_battery_range_km` | 3.49% | EPA 标定值 |
 *
 * 也就是说：屏幕上写「预估续航」，实际显示的是**EPA 标定值**。
 * 标定值不是里程表，它是一块**能量表** ——「按 146 Wh/km 还能跑多远」。
 * 用它做长途规划，夏季高速会乐观 **25%~40%**。
 *
 * ## 换算关系
 *
 * ```
 * 真实可达 = 标定续航 × 146 ÷ 实测 Wh/km × 温度修正 × 爬升修正 × 安全余量
 * ```
 *
 * 146 Wh/km 是本车 `cars.efficiency`（0.146 kWh/km）；用充电数据回归出的真实可用容量
 * 是 65.89 kWh，而 `448.30 km × 0.146 = 65.45 kWh`，误差 0.7% —— 关系成立。
 *
 * ## 实测 Wh/km 按车速分桶（服务端 `wh_per_km`，92 条行程对照 SOC 真值平均误差 -0.41%）
 *
 * | 车速 | Wh/km |
 * |---|---|
 * | < 60（市区） | 171.8 |
 * | 60–90 | 177.3 |
 * | 90–110（高速） | 182.7 |
 * | 110–130 | 197.7 |
 *
 * 取**中位数**而不是均值：单条异常行程（逆行、堵车爬坡、忘记关空调）会把均值拖飞，
 * 而中位数对离群值免疫。样本不足 2 条的桶不建。
 */
@Serializable
data class SpeedBucket(
    val minKph: Int,
    val maxKph: Int,
    val whPerKm: Double,
    val samples: Int,
) {
    /** 档位的可读名，如「90–110」。 */
    val label: String get() = if (maxKph == Int.MAX_VALUE) "$minKph+" else "$minKph–$maxKph"
}

/**
 * 取到的 Wh/km 样本 + **它是不是本档的**。
 *
 * ⚠️ v0.9.2（口径审计 S2-4）：`exact = false` 表示这是**借用**邻档的值，
 * 界面必须说清楚 —— 见 [whSample]。
 */
@Serializable
data class WhSample(
    val bucket: SpeedBucket,
    val exact: Boolean,
)

/**
 * ⚠️ v0.9.0：加 @Serializable —— 能耗模型要能**落盘**。
 *
 * 故障注入实测发现的 bug：服务器挂掉时，车况来自落盘缓存（有 rated_range_km），
 * 但「真实可达」要用的能耗模型是从 `drives` 现算的，而 drives 没缓存 →
 * 模型为 null → 界面退化成「官方标称 308 公里」。
 *
 * 那是**比真实可达乐观 50%** 的数字，而且口径无声地变了
 * （「现在能开」变成「官方标称」），正是新手车主最怕的那种误导：
 * 「官方说 312，App 说 206，我到底该信哪个？」
 *
 * 模型本身只有 4~5 个分桶，缓存它比缓存 200 条 drives 划算得多。
 */
@Serializable
class ConsumptionModel(
    val buckets: List<SpeedBucket>,
    val cruiseKph: Double,
    /**
     * ⚠️ **v0.26.0（第 17 轮新手车主 P0-2 / 通勤车主 P0-2）：模型的**覆盖范围**。**
     *
     * 原来模型只带「分桶 + 巡航速度」，界面上显示「28°C · 实测 183 Wh/km」——
     * 让人以为**已经按气温修正过**。其实温度修正**只做热的**（>22°C），
     * 而本机样本覆盖 8~37°C，**10°C 以下只有 1 条** ——
     * 也就是说：**冬天那个数是编的**，而界面零提示。
     *
     * 现在模型自己带着覆盖范围，界面必须把它说出来。
     */
    val tempMinC: Double? = null,
    val tempMaxC: Double? = null,
    /** ≤10°C 的样本条数（低温修正是否可信的判据） */
    val lowTempSamples: Int = 0,
    /** 样本覆盖的起止时刻（用于「按 X~Y 的 N 条实测」） */
    val firstDrivenAt: String? = null,
    val lastDrivenAt: String? = null,
) {

    /** 该模型由多少条行程构成（用于在 UI 上标注置信度） */
    val sampleCount: Int = buckets.sumOf { it.samples }

    /** 低温样本是否足够支撑一个低温修正（实测 5 条是最低门槛） */
    val lowTempTrustworthy: Boolean get() = lowTempSamples >= LOW_TEMP_MIN_SAMPLES

    /**
     * ⚠️ **v0.26.0：温度可信度说明。**
     *
     * - 温度高于 22°C → 修正生效（高温会掉续航，模型会往上修）
     * - 温度在 10~22°C → **不做修正**（不冷不热，修正量本来就小）
     * - 温度 ≤10°C 且低温样本 < 5 → **这个数不可信**，界面必须说清楚
     */
    fun tempCaveat(outsideTempC: Double?): String? {
        val t = outsideTempC ?: return null
        return when {
            t <= 10.0 && !lowTempTrustworthy ->
                "低温未修正：10°C 以下只有 $lowTempSamples 条样本，这个数不可信"
            t <= 22.0 ->
                "未做低温修正（10°C 以下样本 $lowTempSamples 条）"
            else -> null
        }
    }

    /**
     * 按当前车速取实测 Wh/km；本档没样本时退到最近的档。
     *
     * ⚠️ v0.9.2（口径审计 S2-4）：**借用邻档时必须知道自己在借**。
     *
     * 原来的失败模式不是「少说一点」，而是「悄悄换口径」：
     * 车速落在所有桶外（比如 145 km/h，而桶只到 130）时借用最近的一档，
     * 界面却标成「实测 183 Wh/km」—— 那个 183 是**别的速度档**的实测值。
     *
     * 现在返回 [WhSample]，带 `exact` 标记；界面据此改成「参考 …（借用 90–110 档）」。
     */
    fun whSample(speedKph: Double): WhSample? {
        if (buckets.isEmpty()) return null
        val exact = buckets.firstOrNull { speedKph >= it.minKph && speedKph < it.maxKph }
        if (exact != null) return WhSample(exact, exact = true)
        // 车速落在桶外（<30 或 >150）：取最近的一档
        val nearest = buckets.minByOrNull { bucket ->
            when {
                speedKph < bucket.minKph -> (bucket.minKph - speedKph).toDouble()
                speedKph >= bucket.maxKph -> (speedKph - bucket.maxKph + 1).toDouble()
                else -> 0.0
            }
        } ?: return null
        return WhSample(nearest, exact = false)
    }

    /** 兼容旧调用点：只要数值，不要来源标记。 */
    fun whPerKm(speedKph: Double): SpeedBucket? = whSample(speedKph)?.bucket

    /**
     * 真实可达里程（km）。
     *
     * @param ratedRangeKm  标定续航（`rated_range_km`，回退 `ideal_range_km`）
     * @param speedKph      参考车速：行驶中用实时车速，停车时用最近一次行程均速
     * @param outsideTempC  车外温度，用于高温修正
     * @param ascentM       爬升（长途用常数修正即可）
     * @return null 表示样本不足，不敢给结论（宁可显示标定值，也不编一个数）
     */
    fun trueRangeKm(
        ratedRangeKm: Double?,
        speedKph: Double,
        outsideTempC: Double?,
        ascentM: Int? = null,
        /**
         * 到站**保留电量**比例（默认 0.90 = 留 10%）。
         *
         * ⚠️ v0.14.0（长途自驾党 P0-3）：长途规划按 **20% 保底**算 ——
         * 「下一个服务区在 170 公里外，我到得了吗」问的是这个数，不是「现在能开多少」。
         * 两个数来自**同一个模型**、只是阈值不同，所以不构成「两个数字打架」。
         */
        reserveFactor: Double = 0.90,
    ): Double? {
        if (ratedRangeKm == null || ratedRangeKm <= 0) return null
        val bucket = whPerKm(speedKph) ?: return null
        if (bucket.samples < 2) return null

        // ⚠️ **v0.26.0（第 17 轮通勤车主 P0-2）：低温且低温样本不足 → **不给数**。**
        //
        // 原来的温度修正只做热的（>22°C），低于 22°C 一律不修正 ——
        // 而本机 10°C 以下只有 1 条样本。于是冬天算出来的「现在能开 XXX 公里」
        // 是**系统性地乐观 15%~25%** 的数，界面上却零提示。
        //
        // 现在：低温（≤10°C）且低温样本不足 5 条 → **返回 null**，
        // 界面退回「官方标称」并写明「低温未修正」。
        // 宁可给一个口径明确的数，也不给一个**看起来精确但其实编的**数。
        if (outsideTempC != null && outsideTempC <= 10.0 && !lowTempTrustworthy) {
            return null
        }

        // 温度修正：只做热的，不做冷的（库里 <10°C 样本不足，做了是编数据）。
        // 实测 85–135 km/h：<22°C 180.2 / 22–28°C 189.8 / ≥28°C 191.0 Wh/km。
        val tempFactor = outsideTempC
            ?.let { 1.0 + minOf(0.10, 0.006 * maxOf(0.0, it - 22.0)) }
            ?: 1.0

        // 爬升修正：高速段实测 <200m 179.8 / 200–600m 184.4 / 600m+ 188.2 Wh/km（差 5%）。
        val climbFactor = if ((ascentM ?: 0) >= 200) 1.05 else 1.0

        // 安全余量：长途规划惯例到站留 ≥15–20%。
        val safety = reserveFactor

        val whEff = bucket.whPerKm * tempFactor * climbFactor
        return ratedRangeKm * EPA_WH_PER_KM / whEff * safety
    }

    companion object {
        /** 本车标称能耗（cars.efficiency = 0.146 kWh/km）。换车需同步。 */
        const val EPA_WH_PER_KM = 146.0

        /** 车速分桶边界 */
        private val EDGES = listOf(0, 60, 90, 110, 130, Int.MAX_VALUE)

        /**
         * 从行程列表建模型。
         *
         * 用服务端算好的 `wh_per_km`（不是自己用 ΔSOC 重算）：服务端那个是用
         * Δrated_range 折算的，对照 SOC 真值平均误差 -0.41%，已经是可用的能量表。
         */
        fun from(drives: List<Drive>): ConsumptionModel? {
            val usable = drives.filter {
                val wh = it.wh_per_km
                val km = it.distance_km
                val sp = it.avg_speed_kph
                wh != null && km != null && km >= 20.0 && sp != null && sp > 0 && wh in 50.0..400.0
            }
            if (usable.size < 4) return null

            val buckets = mutableListOf<SpeedBucket>()
            for (i in 0 until EDGES.size - 1) {
                val lo = EDGES[i]
                val hi = EDGES[i + 1]
                val inBucket = usable.filter {
                    val sp = it.avg_speed_kph!!
                    if (hi == Int.MAX_VALUE) sp >= lo else sp in lo until hi
                }
                if (inBucket.size < 2) continue
                // ⚠️ v0.14.0（口径审计 P1-4）：原来取的是**上中位数**
                // （sorted()[n/2]），与 ChargeStats 里的教科书口径不一致 ——
                // 而这个数**直接决定界面上「实测 XXX Wh/km」的大小**，
                // 第三方按 numpy 复算会系统性对不上。
                // 现在全项目共用 `com.teslamate.app.medianOf` 一个实现。
                val median = medianOf(inBucket.map { it.wh_per_km!! }) ?: continue
                buckets += SpeedBucket(lo, hi, median, inBucket.size)
            }
            if (buckets.isEmpty()) return null

            // ── 参考车速（停车时用） ──────────────────────────
            // ⚠️ **v0.26.0（第 17 轮通勤车主 P0-2）：改成「最近 5 趟 ≥30 km 行程的均速」。**
            //
            // 原来取的是「近期高速行程的**峰值速度**中位数」= **130 km/h**，
            // 于是停车时借了 **110–130 档 197.36 Wh/km**。
            // 但车主日常通勤实测只有 **161.8 Wh/km**（`drives.id=499`）—— 差 16.7%。
            //
            // 后果正好落在**每天的决策点**上：
            //   20% 电量时 App 说「58 公里 → 到不了公司」，
            //   而按市区档应该是「67 公里 → 到得了」——
            //   **在通勤者最需要它的时候给错了边**。
            //
            // 峰值速度本来的用途是「行驶中的巡航速度」（那条路你开多快），
            // 但停车时你并不在高速上 —— 停车时该问的是「**我平时开多快**」。
            // 取最近 5 趟 ≥30 km 行程的**均速中位数**：既贴近日常，
            // 又用中位数躲开单趟堵车/暴雨这种离群值。
            val recentTrips = usable
                .filter { (it.distance_km ?: 0.0) >= 30.0 && (it.avg_speed_kph ?: 0) > 0 }
                .sortedByDescending { it.start_date }
                .take(5)
            val cruise = medianOf(recentTrips.map { it.avg_speed_kph!!.toDouble() })
                ?: medianOf(usable.mapNotNull { it.avg_speed_kph?.toDouble() })
                ?: 60.0

            // ⚠️ v0.26.0：把**覆盖范围**带进模型 ——
            // 界面上「28°C · 实测 183 Wh/km」让人以为已按气温修正，
            // 而修正其实只做热的（>22°C），10°C 以下只有 1 条样本。
            val temps = usable.mapNotNull { it.outside_temp_avg }
            val lowTemp = temps.count { it <= 10.0 }
            val firstAt = usable.minByOrNull { it.start_date }?.start_date?.fmtDate()
            val lastAt = usable.maxByOrNull { it.start_date }?.start_date?.fmtDate()

            return ConsumptionModel(
                buckets = buckets,
                cruiseKph = cruise,
                tempMinC = temps.minOrNull(),
                tempMaxC = temps.maxOrNull(),
                lowTempSamples = lowTemp,
                firstDrivenAt = firstAt,
                lastDrivenAt = lastAt,
            )
        }

        /** 低温样本条数门槛（低于这个数就不做低温结论） */
        const val LOW_TEMP_MIN_SAMPLES = 5
    }
}
