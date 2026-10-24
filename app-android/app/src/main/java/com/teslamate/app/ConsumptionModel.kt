package com.teslamate.app

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
data class SpeedBucket(
    val minKph: Int,
    val maxKph: Int,
    val whPerKm: Double,
    val samples: Int,
)

class ConsumptionModel(val buckets: List<SpeedBucket>) {

    /** 该模型由多少条行程构成（用于在 UI 上标注置信度） */
    val sampleCount: Int = buckets.sumOf { it.samples }

    /** 按当前车速取实测 Wh/km；本档没样本时退到最近的档 */
    fun whPerKm(speedKph: Double): SpeedBucket? {
        if (buckets.isEmpty()) return null
        val exact = buckets.firstOrNull { speedKph >= it.minKph && speedKph < it.maxKph }
        if (exact != null) return exact
        // 车速落在桶外（<30 或 >150）：取最近的一档
        return buckets.minByOrNull { bucket ->
            when {
                speedKph < bucket.minKph -> (bucket.minKph - speedKph).toDouble()
                speedKph >= bucket.maxKph -> (speedKph - bucket.maxKph + 1).toDouble()
                else -> 0.0
            }
        }
    }

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
    ): Double? {
        if (ratedRangeKm == null || ratedRangeKm <= 0) return null
        val bucket = whPerKm(speedKph) ?: return null
        if (bucket.samples < 2) return null

        // 温度修正：只做热的，不做冷的（库里 <10°C 样本不足，做了是编数据）。
        // 实测 85–135 km/h：<22°C 180.2 / 22–28°C 189.8 / ≥28°C 191.0 Wh/km。
        val tempFactor = outsideTempC
            ?.let { 1.0 + minOf(0.10, 0.006 * maxOf(0.0, it - 22.0)) }
            ?: 1.0

        // 爬升修正：高速段实测 <200m 179.8 / 200–600m 184.4 / 600m+ 188.2 Wh/km（差 5%）。
        val climbFactor = if ((ascentM ?: 0) >= 200) 1.05 else 1.0

        // 安全余量：长途规划惯例到站留 ≥15–20%。
        val safety = 0.90

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
                val median = inBucket.map { it.wh_per_km!! }.sorted()[inBucket.size / 2]
                buckets += SpeedBucket(lo, hi, median, inBucket.size)
            }
            if (buckets.isEmpty()) return null
            return ConsumptionModel(buckets)
        }
    }
}
