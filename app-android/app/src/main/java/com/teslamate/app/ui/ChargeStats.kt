package com.teslamate.app.ui

import com.teslamate.app.Charge
import com.teslamate.app.firstSegment
import com.teslamate.app.medianOf
import com.teslamate.app.p90NearestRank
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ⚠️ **v0.31.0：实测样本的「同起点」带宽（±5 个百分点）。**
 *
 * 实测同站离散度：起始 SOC ≤37% 时 3%~4%（很小），
 * ≥38% 时 20%~29%（慢会话会混进来）。
 * 所以 ±5 是个既能攒到样本、又不至于混太杂的带宽。
 */
const val MEASURED_SOC_BAND = 5

/**
 * 充电站历史统计 —— 回答长途自驾党最关心的那个问题：
 * **「这个桩到底快不快？我要等多久？够不够充到下一站？」**
 *
 * ## 为什么必须有它
 *
 * 现有字段只有**单次**充电的峰值/平均功率。峰值没有决策价值 ——
 * 实测本车 22,850 个采样点里，峰值 >100 kW 只占 4.1%，>50 kW 占 48.6%，
 * 国标直流桩典型就是 50~110 kW，峰值 152 kW 只出现在起步那几分钟。
 * 选桩要看的是**这个站历史实测的典型水平**。
 *
 * 实测：26 场「≤50% 充到 ≥70%」的充电，充到 80% 的中位耗时 **49 分钟**、
 * P90 **86 分钟** —— 这两个数才决定「进不进这个服务区」。
 * 它们现在只躺在数据库里。
 *
 * ## 口径说明
 *
 * - **典型功率**取 `avg_power_kw` 的**中位数**（不是均值）：单次充电受起始电量、
 *   桩故障、排队影响很大，中位数抗离群。
 * - **直流与交流分开统计**（v0.9.2）：AC 的典型功率约 7 kW、DC 约 55 kW，
 *   混在一起取中位数，得到的数**两边都不是**。
 * - **充满到 80% 的耗时**用「该车反推出的电池容量」折算：
 *   容量 = 充入 kWh ÷ ΔSOC% × 100，每次充电各算一个再取中位数。
 *   这是 TeslaMate 没有直接提供、但可以可靠反推的量。
 */
data class StationStat(
    /** 该站历史充电次数 */
    val sessions: Int,
    /** 典型充电功率（kW，中位数，全部类型混算） */
    val typicalPowerKw: Double,
    /** 仅**直流**场次的中位功率（kW）；该站没有直流场次时为 null */
    val typicalPowerDcKw: Double?,
    /** 从 ≤50% 充到 80% 的典型耗时（分钟，中位数）；样本不足为 null */
    val typicalMinutesTo80: Int?,
    /** 从 ≤50% 充到 80% 的 **P90** 耗时（分钟）——「最坏情况我要等多久」；样本不足 5 次为 null */
    val p90MinutesTo80: Int?,
    /** 反推出的电池容量（kWh），用于折算耗时 */
    val batteryCapacityKwh: Double,
    /**
     * ⚠️ **v0.31.0（第 20 轮充电系统数据专家 P0-1）：该站的**实测**样本。**
     *
     * 每项 = 「从该 SOC 起充到 80% 实测用了多少分钟」。
     *
     * ⚠️ 为什么是「起点区间」而不是固定「≤50%」：
     * 固定口径会把起始 SOC 差 40 个百分点、功率差 5 倍的场次混在一起 ——
     * 折算值互相没有可比性，这正是 v0.30.0 那个「同屏两个数差 50%」的结构性原因。
     *
     * 实测同站同区间的离散度很小：起始 SOC 13/14/15 的三场实测是 37 / 37 / 36 分钟
     * （离散 3%）；但起始 SOC ≥38% 时离散会跳到 20%~29%（因为慢会话也进来了）。
     */
    val measuredSamples: List<MeasuredSample> = emptyList(),
    /**
     * ⚠️ **v0.42.0（第 24 轮对抗性审计 ⑧）：该站直流场次里**没充到 80%**的次数。**
     *
     * ## 为什么要有这个数
     *
     * 「该站同起点最慢一次」取的是**成功充到 80%** 的场次里的最大值。
     * 而**没充到 80%** 的场次（桩慢、桩坏、排队、提前离场）**恰恰是最该知道的那批** ——
     * 它们被整体排除，于是「最慢一次」只是**成功子集里的最慢**，
     * **不是这个桩的最坏情况**。
     *
     * 实测本机：直流 65 场里 **31 场 `never_reached_80`**（47.7%）、
     * 6 场起点已 ≥80% ⇒ 有效的只有 **28 场**。
     *
     * ⚠️ 所以界面**必须**说清这个「最慢」是**哪个总体**里的最慢。
     */
    val dcNeverReached80: Int = 0,
    /**
     * ⚠️ **v0.43.0（第 26 轮口径审计 S6）：其中**停留很短**的场次数（≤20 分钟）。**
     *
     * ## 为什么要有这个数
     *
     * 界面原来写「最坏情况可能比这个数差」—— 那是**没有数据支撑的因果推测**。
     * 第 26 轮独立复算发现：被排除的 31 场里
     * **24 场停留 ≤20 分钟、0 场 >40 分钟、有效功率中位 89 kW**
     * ⇒ 它们主要是「**人提前走了**」，**不是**「桩慢/桩坏/排队」。
     *
     * 所以归因要**用实测数据**，不能猜。
     */
    val dcNeverReached80Short: Int = 0,
) {
    /**
     * ⚠️ **v0.42.0**：被排除在「最慢一次」统计之外的直流场次数。
     *
     * 只算「没充到 80%」这一类 —— 那是**速度问题**；
     * 「起点已 ≥80%」「采样点太少」那些是**口径问题**，不是慢。
     */
    val excludedFromSlowest: Int get() = dcNeverReached80

    /**
     * ⚠️ v0.31.0：一笔**实测**样本（起点 SOC + 分钟数）。
     */
    data class MeasuredSample(val fromSoc: Int, val minutes: Int)

    /** 实测样本里落在「起点 ± [MEASURED_SOC_BAND]%」区间的数量 */
    fun measuredCountNear(soc: Int?): Int {
        if (soc == null) return 0
        return measuredSamples.count { kotlin.math.abs(it.fromSoc - soc) <= MEASURED_SOC_BAND }
    }

    /**
     * 同起点区间的**实测中位**（分钟）。
     *
     * 样本 < 2 时返回 null —— n=1 的「中位数」不是中位数。
     */
    fun measuredMedianNear(soc: Int?): Int? {
        if (soc == null) return null
        val vals = measuredSamples
            .filter { kotlin.math.abs(it.fromSoc - soc) <= MEASURED_SOC_BAND }
            .map { it.minutes }
        if (vals.size < 2) return null
        return medianOf(vals.map { it.toDouble() })?.roundToInt()
    }

    /** 同起点区间里**最慢的一次**（分钟）—— 代替原来那个从不显示的 P90 */
    fun measuredSlowestNear(soc: Int?): Int? {
        if (soc == null) return null
        val vals = measuredSamples
            .filter { kotlin.math.abs(it.fromSoc - soc) <= MEASURED_SOC_BAND }
            .map { it.minutes }
        return if (vals.isEmpty()) null else vals.max()
    }

    /**
     * 从 [socFrom] 充到 [socTo] 需要多久（分钟）。
     *
     * ⚠️ v0.9.2（长途自驾党 N2）：原来只有「从 ≤50% 充到 80%」一个写死的口径，
     * 我到站是 31%、想充到 90%，界面就什么都不显示。
     * 现在参数化 —— 任何起止电量都能算。
     *
     * @param powerKw 该站的典型功率（kW）；直流桩请传 [typicalPowerDcKw]
     */
    fun minutesTo(socFrom: Int, socTo: Int, powerKw: Double): Int? {
        if (socTo <= socFrom) return null
        if (powerKw <= 1.0) return null
        val needKwh = (socTo - socFrom) / 100.0 * batteryCapacityKwh
        val minutes = needKwh / powerKw * 60.0
        if (minutes < 0.5 || minutes > 600.0) return null
        return minutes.roundToInt()
    }
}

/**
 * ⚠️ **v0.182.0：按站画像改由**服务端**给**（App 只透传）。
 *
 * ## 为什么搬
 *
 * 原来「这个桩快不快 / 我要等多久」是**客户端**按地点聚合充电历史算的
 * ⇒ ① **飞书里问不出来**（mission §2.1）；
 *   ② **两端各算一套** —— 那套口径有 9 个阈值 ＋ 5 处评审修出来的坑（ADR #38）。
 *
 * ## 分组键
 *
 * 服务端按 `place_key`（按真实距离聚类 500 m）分组 ⇒ 客户端按 `charge.place_key` 查表。
 * ⚠️ **实测分组数一致**（本机 84 场充电：坐标 2 位小数分 **58** 组，place_key 也是 **58** 组）
 * ⇒ 同一批站、同一批样本 ⇒ **数值不变**。
 */
fun stationStatsFromServer(resp: com.teslamate.app.StationStatsResponse): Map<String, StationStat> =
    resp.stations.associate { dto ->
        dto.place_key to StationStat(
            sessions = dto.sessions,
            typicalPowerKw = dto.typical_power_kw,
            typicalPowerDcKw = dto.typical_power_dc_kw,
            typicalMinutesTo80 = dto.typical_minutes_to_80,
            p90MinutesTo80 = dto.p90_minutes_to_80,
            batteryCapacityKwh = resp.capacity_kwh,
            measuredSamples = dto.measured_samples.map {
                StationStat.MeasuredSample(it.from_soc, it.minutes)
            },
            dcNeverReached80 = dto.dc_never_reached_80,
            dcNeverReached80Short = dto.dc_never_reached_80_short,
        )
    }

/**
 * ⚠️ v0.21.0：**有效平均功率** —— 优先用时间加权值。
 *
 * `avg_power_kw` 是**按采样点数**算的算术平均，而采样间隔不均匀
 * （高功率段 5 秒一个点、掉功率段几十秒一个点），所以它**系统性高估**
 * （实测 18%~50%）。
 *
 * 而这个数是「从 X% 充到 80% 约 N 分钟」的**唯一来源** ——
 * 功率高估 45% 就意味着**等待时间低估 31%**。
 * 「服务区还要等多久」乐观了近三分之一。
 *
 * 旧服务端没有 `avg_power_kw_true` 时回落到采样均值（行为退化但不崩）。
 *
 * ⚠️ **v0.182.0：它留在客户端是有意的** —— 它是「**这一场**充电显示哪个功率字段」，
 * 是**单场**的显示口径，不是按站聚合；按站聚合已经在服务端（api/station_stats.go）。
 */
internal fun Charge.effectiveAvgPowerKw(): Int? = avg_power_kw_true ?: avg_power_kw

/**
 * 中位数。
 *
 * ⚠️ v0.9.2（口径审计 S3-1）：偶数样本时这里取的是**上中位数**
 * （index = n/2），而 numpy / pandas / R 的 `median()` 取两个中间值的平均 ——
 * 第三方按教科书算法复算会对不上（Wh/km 差 0.5–2%，耗时差 1 分钟）。
 * 现在改成**两个中间值的平均**，与教科书一致。
 *
 * ⚠️ v0.14.0（口径审计 P1-4）：实现**搬到 `com.teslamate.app.medianOf`** ——
 * 因为 `ConsumptionModel`（另一个包）里还有两处上中位数没改，
 * 而那两处直接决定界面上「实测 Wh/km」的大小。
 * 全项目现在只有一份实现。
 */
private fun median(values: List<Double>): Double = medianOf(values) ?: 0.0

/**
 * 一句话摘要：这个桩的历史表现。
 *
 * 例：「该站历史 59 kW · 同起点实测充到 80% 中位 37 分钟（3 次）」
 *
 * ⚠️ **v0.31.0：优先给**实测**（同起点区间），折算只作兜底。**
 *
 * 原来只有折算值「充到 80% 约 54 分钟」——
 * 而实测同区间中位是 **37.5 分钟**（**+44%**），最大偏差 **+185%**。
 * 两个数曾**同屏并列**却不加解释。
 */
fun StationStat.summary(fromSoc: Int? = null): String {
    val sb = StringBuilder()
    sb.append("该站历史 ").append(typicalPowerKw.roundToInt()).append(" kW")
    sb.append("（").append(sessions).append(" 次）")
    // ⚠️ **v0.74.0（模拟器实测）：拆两行**。
    //
    // 原来一行写完「该站历史 29 kW · 充到 80% 约 61 分钟（按典型功率折算）（3 次）」，
    // 手机上折行正好把**末尾的括号拆开** —— 屏幕上是「…（按典型功率折算）（3 / 次）」，
    // 两个括号各自悬空，读起来像坏掉的句子。
    //
    // 现在：第一行 = 这个站的典型功率 + 样本数；第二行 = 等待时间 + 它是怎么来的。
    // **一个字都没删**，但括号不再被折断。
    val wait = buildString {
        val measured = measuredMedianNear(fromSoc)
        if (measured != null) {
            append("同起点实测充到 80% 中位 ").append(measured).append(" 分钟")
        } else {
            typicalMinutesTo80?.let {
                append("充到 80% 约 ").append(it).append(" 分钟（按典型功率折算）")
            }
        }
    }
    if (wait.isNotEmpty()) sb.append("\n").append(wait)
    return sb.toString()
}

/**
 * 与当前这次充电的对比：比这个站的中位数快还是慢。
 */
fun StationStat.compareTo(powerKw: Double?): String? {
    val p = powerKw ?: return null
    if (typicalPowerKw <= 1.0) return null
    val diff = ((p - typicalPowerKw) / typicalPowerKw * 100).roundToInt()
    return when {
        abs(diff) <= 8 -> "与该站中位水平持平"
        diff > 0 -> "比该站中位快 $diff%"
        else -> "比该站中位慢 ${-diff}%"
    }
}
