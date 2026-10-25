package com.teslamate.app

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

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
    /**
     * ⚠️ **v0.36.0（第 21 轮一线车主复审 C8）：模型残差的 90 分位（Wh/km）。**
     *
     * 复审原话：「这个 App 要么**给区间**，要么把上次实际值并排放。
     * 否则它比官方更不可信 —— 因为它看起来更精确。」
     *
     * 这是模型**自己的**不确定度：每条可用行程的
     * |实测 Wh/km − 它所属档的中位数| 的 90 分位。
     * 样本不足 10 条时为 null（不给数）。
     */
    val residualP90WhPerKm: Double? = null,
    /**
     * ⚠️ **v0.43.0（第 26 轮口径审计 R2/S1）：算 P90 用了**多少条样本**。**
     *
     * ## 为什么必须带出来
     *
     * 界面写「模型残差 90 分位」时，读者会以为它描述的是**自己的日常驾驶**。
     * 而这个 P90 **只用 km ≥ 100 的长途样本**算
     * （实测 20 条，档位分布 90–110×9 / 60–90×5 / 110–130×5 / **0–60×1**）——
     * 而基数用的是 **0–60 市区档**。
     *
     * ⇒ **基数与误差带来自两个不同总体**，不说清楚就是**口径漂移**。
     * 现在界面会把样本数与口径一起写出来。
     */
    val residualSamples: Int = 0,
) {
    /**
     * 把 [residualP90WhPerKm] 换算成**电量百分点**（一段路程上的误差）。
     *
     * @param km 这一段的里程
     * @param capacityKwh 本车可用电池容量（度）
     */
    fun socErrorPoints(km: Double, capacityKwh: Double?): Int? {
        val p90 = residualP90WhPerKm ?: return null
        val cap = capacityKwh ?: return null
        if (cap <= 0 || km <= 0) return null
        return ((p90 * km / 1000.0) / cap * 100.0).roundToInt()
    }

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
                "低温未修正：≤10°C 只有 $lowTempSamples 条样本，这个数不可信"
            t <= 22.0 ->
                "未做低温修正（≤10°C 样本 $lowTempSamples 条）"
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

        // ⚠️ **v0.33.0（第 20 轮爬升能耗建模评审）：这里原来的爬升修正被**移除**了。**
        //
        // 原来：`if ((ascentM ?: 0) >= 200) 1.05 else 1.0` —— 二值饱和（200 m 与 2,571 m 同价）
        // 且是**乘性**；实测本车的爬升能耗是**加性**（m·g·h 是绝对量，与车速无关）。
        //
        // 但更根本的问题是**口径错**：这里算的是「**现在**能开多远」，
        // 而传进来的 `ascentM` 是「**最近一趟**行程」的爬升 ——
        // 用**已经走过的**山路去修**未来**的续航，概念就不对
        // （实测那 5 趟「最近行程」是川藏滇线，爬升 2,290 米，
        //  按新模型会算成 +219 Wh/km —— 荒谬）。
        //
        // 现在：**车况页的续航是「平路 + 车速档 + 气温」的估算**，
        // 不含爬升修正；**爬升影响在「记录 → 线路余量表」里逐段算**
        // （那里有真实的逐段爬升，见 `RoutePlan.kt`）。
        // 界面上「怎么算的」也照实说。
        val whEff = bucket.whPerKm * tempFactor

        // 安全余量：长途规划惯例到站留 ≥15–20%。
        val safety = reserveFactor

        return ratedRangeKm * EPA_WH_PER_KM / whEff * safety
    }

    /**
     * ⚠️ **v0.42.0（第 24 轮对抗性审计 ⑤）：「现在能开」的**区间**（公里）。**
     *
     * ## 为什么要有它
     *
     * 同一个模型、同一个残差分布：
     * - 线路余量表给「±N 个点」（[socErrorPoints]）；
     * - 车况页却给一个**裸整数**（229 公里）。
     *
     * 同一个不确定度，一个页面给区间一个不给 ⇒ **口径漂移**。
     * 第 24 轮对抗性审计的原话：「两个页面，同一个模型，一个给误差带，一个给裸整数」。
     *
     * ## 为什么区间是**非对称**的
     *
     * 不确定度在 **Wh/km** 上，而续航与 Wh/km 成**反比**：
     * 实际比模型省电 ⇒ 跑得更远（**上行被放大**）；
     * 实际比模型费电 ⇒ 跑得更近（**下行被放大**）。
     *
     * 所以不能写「±N 公里」—— 那会把**放大的那一侧**说小了。
     *
     * ## 区间是**非对称**的，而且**不是**「对称 ± 某个百分比」
     *
     * 设 `x = p90 / whEff`（本机约 0.10）：
     * - 乐观侧 = 基数 ÷ (1 − x) ⇒ 比基数高 **x/(1−x)** ≈ **+11.5%**；
     * - 悲观侧 = 基数 ÷ (1 + x) ⇒ 比基数低 **x/(1+x)** ≈ **−9.3%**。
     *
     * 而「对称 ±x」是 **±10.3%** ⇒ 它**高估**了悲观侧、**低估**了乐观侧。
     * 所以不能写成「±N 公里」。
     *
     * ## 口径与 [trueRangeKm] **完全同源**
     *
     * 同样的档位规则、同样的气温系数、同样的低温不可信判定 ——
     * 否则这个区间和那个点就对不上。
     *
     * @return (悲观, 乐观) 两个公里数，**升序**；样本不足时返回 null（不给区间）。
     */
    fun trueRangeBandKm(
        ratedRangeKm: Double?,
        speedKph: Double,
        outsideTempC: Double?,
        reserveFactor: Double = 0.90,
    ): Pair<Int, Int>? {
        if (ratedRangeKm == null || ratedRangeKm <= 0) return null
        val p90 = residualP90WhPerKm ?: return null
        val bucket = whPerKm(speedKph) ?: return null
        if (bucket.samples < 2) return null
        // ⚠️ 低温不可信 ⇒ 连区间也不给（与 [trueRangeKm] 同一个判据）
        if (outsideTempC != null && outsideTempC <= 10.0 && !lowTempTrustworthy) return null
        val tempFactor = outsideTempC
            ?.let { 1.0 + minOf(0.10, 0.006 * maxOf(0.0, it - 22.0)) }
            ?: 1.0
        val whEff = bucket.whPerKm * tempFactor
        // Wh/km 减到零以下 ⇒ 这个模型在那个速度档根本不可信 ⇒ 不给区间
        if (whEff <= p90) return null
        val base = ratedRangeKm * EPA_WH_PER_KM / whEff * reserveFactor
        val optimistic = base * whEff / (whEff - p90)
        val pessimistic = base * whEff / (whEff + p90)
        // ⚠️ **升序返回** —— 调用方直接当 (下界, 上界) 用。
        return pessimistic.roundToInt() to optimistic.roundToInt()
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
            // 于是停车时借了 **110–130 档 197.36 Wh/km** —— 偏乐观 21%。
            //
            // ⚠️ **v0.30.0（第 19 轮通勤车主评审 P0-2）：修正「通勤」这个说法。**
            //
            // 原文写的是「车主日常通勤实测只有 161.8 Wh/km（`drives.id=499`）」——
            // **那是错的**。实测这 5 趟是：
            //
            // | id | 时间 | 里程 | 时长 | 峰值 | 均速 |
            // |---|---|---|---|---|---|
            // | 499 | 10-24 **周六** 13:05 | 49.8 km | 55 min | 130 | 54.4 |
            // | 495 | 10-24 **周六** 11:58 | 37.4 km | 38 min | 150 | 59.1 |
            // | 491 | 10-24 **周六** 03:41 | 85.4 km | 67 min | 151 | 76.5 |
            // | 479 | 10-18 **周日** 11:13 | 77.1 km | 101 min | 187 | 45.8 |
            // | 471 | 10-18 **周日** 03:15 | 75.7 km | 67 min | 122 | 67.8 |
            //
            // 全部是**周末长途自驾**，峰值 122~187 km/h —— **没有一趟是通勤**。
            // 这台车 87 天只出门 15 次，工作日 17~21 点回到家围栏的次数是 **0**，
            // 严格定义下（工作日 + 家出发 + 20~60km + 05~10 点）**只有 1 趟**。
            //
            // 所以这个参考车速**不是**「日常通勤速度」，而是
            // 「**最近 5 趟长途的均速**」—— 界面文案已同步改成实话
            // （见 `StatusScreen.kt` 的「按最近 5 趟 ≥30 公里行程的均速 X km/h 估算」）。
            //
            // 峰值速度本来的用途是「行驶中的巡航速度」（那条路你开多快），
            // 但停车时你并不在高速上 —— 停车时该问的是「**我最近开多快**」。
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

            // ⚠️ **v0.36.0（第 21 轮一线车主复审 C8）：模型要自曝它的**误差带**。**
            //
            // 复审原话：「这个 App 要么**给区间**，要么把上次实际值并排放。
            // 否则它比官方更不可信 —— 因为它看起来更精确。」
            //
            // 现在用**模型自己的残差**算：对每条可用行程，
            // |实测 Wh/km − 它所属档的中位数| 的 **90 分位**（不用均值 —— 离群值会拉飞）。
            //
            // ⚠️ **只用 km ≥ 100 的长途样本** —— 这是**口径必须同源**的地方：
            // 线路余量表只处理 ≥200 公里的旅程，而**短途**的残差噪声极大
            // （标定续航变化很小、里程很短 ⇒ Wh/km 残差被放大）：
            // 实测全样本 P90 = **97 Wh/km**（351 公里上 ±52 点，毫无意义），
            // 而 km ≥ 100 的 P90 = **23.2 Wh/km**（351 公里上 **±12.4 个点**）——
            // 与复审实测的「单跳误差 -11 ~ +12 个点」**精确吻合**。
            val residuals = usable
                .filter { (it.distance_km ?: 0.0) >= 100.0 }
                .mapNotNull { d ->
                    val wh = d.wh_per_km ?: return@mapNotNull null
                    val sp = d.avg_speed_kph ?: return@mapNotNull null
                    val b = bucketForSpeed(sp.toDouble(), buckets) ?: return@mapNotNull null
                    kotlin.math.abs(wh - b.whPerKm)
                }
            val residualP90 = if (residuals.size >= 10) {
                val sorted = residuals.sorted()
                // 最近秩法 P90（与 `p90NearestRank` 同一口径）
                // 用整数算 ceil(size × 0.9)：(size×9 + 9) / 10
                val rank = ((sorted.size * 9 + 9) / 10).coerceIn(1, sorted.size)
                sorted[rank - 1]
            } else {
                null
            }
            // ⚠️ **v0.43.0**：把样本数带出来 —— 界面要能说清这个 P90 是**多少条**样本算的。
            val residualN = if (residualP90 != null) residuals.size else 0

            return ConsumptionModel(
                buckets = buckets,
                cruiseKph = cruise,
                tempMinC = temps.minOrNull(),
                tempMaxC = temps.maxOrNull(),
                lowTempSamples = lowTemp,
                firstDrivenAt = firstAt,
                lastDrivenAt = lastAt,
                residualP90WhPerKm = residualP90,
                residualSamples = residualN,
            )
        }

        /** 低温样本条数门槛（低于这个数就不做低温结论） */
        const val LOW_TEMP_MIN_SAMPLES = 5

        /**
         * 某个车速落在**哪个档**（含借用邻档的规则，与 [whSample] 一致）。
         *
         * ⚠️ v0.36.0：算模型残差时要用**同一个**分档规则，
         * 否则残差会被「借用邻档」污染。
         */
        private fun bucketForSpeed(
            speedKph: Double,
            buckets: List<SpeedBucket>,
        ): SpeedBucket? {
            if (buckets.isEmpty()) return null
            buckets.firstOrNull { b ->
                if (b.maxKph == Int.MAX_VALUE) speedKph >= b.minKph
                else speedKph >= b.minKph && speedKph < b.maxKph
            }?.let { return it }
            // 借用最近的一档（速度落在所有桶外时）
            return buckets.minByOrNull { b ->
                val d = if (speedKph < b.minKph) b.minKph - speedKph else speedKph - b.maxKph
                kotlin.math.abs(d)
            }
        }
    }
}
