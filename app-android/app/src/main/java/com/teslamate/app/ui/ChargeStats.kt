package com.teslamate.app.ui

import com.teslamate.app.Charge
import com.teslamate.app.firstSegment
import com.teslamate.app.medianOf
import com.teslamate.app.p90NearestRank
import kotlin.math.abs
import kotlin.math.roundToInt

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
) {
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
 * 按地点聚合充电历史。
 *
 * ⚠️ **v0.9.2（口径审计 S2-3）：分组 key 改成坐标，而不是地址第一段。**
 *
 * 原来 key 用 `place.firstSegment()`（「石化大道西」「人民路」「G210」）——
 * **同名地点跨城市会被混算**（实测「石化大道西」的 7 次其实横跨多地）。
 *
 * 现在：优先用**围栏名**（`geofence_name`，由车主自己定义，天然唯一）；
 * 没有围栏名就用**坐标聚类**（保留 2 位小数 ≈ 1.1 km，
 * 既能区分不同城市，又能容忍同一桩的 GPS 抖动）；
 * 两者都没有才退回地址第一段。地点名只用于**显示**。
 */
fun buildStationStats(charges: List<Charge>): Map<String, StationStat> {
    if (charges.size < 2) return emptyMap()

    // 先反推电池容量：每次充电算一个 kWh/%，取中位数
    // ⚠️ v0.8.2 修正：这里算出来的是**整包容量**（kWh / ΔSOC% × 100），
    // 不是「每 1% SOC 的 kWh」。所以合理区间是 30~120 kWh，
    // 原来误写成 0.3~1.2 —— 那一档会把**全部样本**滤掉，
    // 于是 perPercent 为空、函数直接返回空 Map，站点历史永远不显示。
    val perPercent = charges.mapNotNull { c ->
        val kwh = c.energy_added_kwh ?: return@mapNotNull null
        val dsoc = (c.end_soc ?: return@mapNotNull null) - (c.start_soc ?: return@mapNotNull null)
        if (dsoc <= 5) return@mapNotNull null      // ΔSOC 太小，量化误差过大
        kwh / dsoc * 100.0
    }.filter { it in 30.0..120.0 }
    if (perPercent.isEmpty()) return emptyMap()
    // ⚠️ v0.14.0（口径审计 P1-4）：原来这里取的是**上中位数**（sorted()[n/2]），
    // 与同一文件里的 `median()` 不一致 —— 第三方按 numpy 复算会系统性对不上。
    val capacity = medianOf(perPercent) ?: return emptyMap()

    val grouped = charges
        .filter { !it.place.isNullOrBlank() }
        .groupBy { stationKey(it) }

    return grouped.mapNotNull { (key, list) ->
        if (key.isBlank()) return@mapNotNull null
        // ⚠️ v0.14.0（长途自驾党 P0-2）：原来 `list.size < 2` 的门槛
        // 把**只充过 1 次的站整条丢掉** —— 而「只充过 1 次」恰恰是
        // 长途新服务区的常态。结果：出发前最想知道的那个站，
        // 界面上只有「到站 16%」三个字，没有 kW、没有分钟。
        // 现在 1 次也保留，界面负责标注「仅 1 次」。
        val powers = list.mapNotNull { it.effectiveAvgPowerKw() }.filter { it > 1.0 }
        if (powers.isEmpty()) return@mapNotNull null
        val typicalPower = medianOf(powers.map { it.toDouble() }) ?: return@mapNotNull null

        // ⚠️ v0.9.2：直流与交流**分开统计**。
        // AC 典型约 7 kW、DC 典型约 55 kW，混在一起取中位数得到的数两边都不是。
        val dcPowers = list.filter { it.is_dc }.mapNotNull { it.effectiveAvgPowerKw() }.filter { it > 1.0 }
        val typicalPowerDc = if (dcPowers.size >= 2) medianOf(dcPowers.map { it.toDouble() }) else null

        // 从 ≤50% 出发的充电：算充到 80% 需要多久
        //
        // ⚠️ v0.21.0：**必须用时间加权功率** ——
        // 采样均值系统性高估（实测 18%~50%），
        // 而这个数是「我要等多久」的直接来源：高估 45% → 耗时低估 31%。
        // 例：会话 76（15%→100%）真实 72 分钟，采样均值算法只给 48 分钟。
        val minutes = list.mapNotNull { c ->
            val start = c.start_soc ?: return@mapNotNull null
            val kw = c.effectiveAvgPowerKw() ?: return@mapNotNull null
            if (start > 50 || kw <= 1.0) return@mapNotNull null
            val needKwh = (80 - start) / 100.0 * capacity
            needKwh / kw * 60.0
        }.filter { it in 3.0..600.0 }.map { it.roundToInt() }.sorted()

        key to StationStat(
            sessions = list.size,
            typicalPowerKw = typicalPower,
            typicalPowerDcKw = typicalPowerDc,
            typicalMinutesTo80 = if (minutes.size >= 2) {
                // ⚠️ v0.14.0（口径审计 P1-4）：原来取上中位数（minutes[n/2]）。
                medianOf(minutes.map { it.toDouble() })?.roundToInt()
            } else {
                null
            },
            // ⚠️ v0.14.0（口径审计 P1-5）：改用**最近秩法**的 P90，
            // 样本 < 10 时不给（此时 P90 与最大值没有区别，标 P90 是误导）。
            p90MinutesTo80 = p90NearestRank(minutes),
            batteryCapacityKwh = capacity,
        )
    }.toMap()
}

/** 分组 key：围栏名优先 → 坐标聚类 → 地址第一段。 */
fun stationKey(c: Charge): String {
    c.geofence_name?.takeIf { it.isNotBlank() }?.let { return "gf:$it" }
    val lat = c.map_lat
    val lng = c.map_lng
    if (lat != null && lng != null) {
        return String.format("xy:%.2f,%.2f", lat, lng)
    }
    return "nm:${c.place.firstSegment() ?: ""}"
}

/**
 * ⚠️ v0.21.0：**有效平均功率** —— 优先用时间加权值。
 *
 * `avg_power_kw` 是**按采样点数**的算术平均，而采样间隔不均匀
 * （高功率段 5 秒一个点、掉功率段几十秒一个点），所以它**系统性高估**
 * （实测 18%~50%）。
 *
 * 而这个数是「从 X% 充到 80% 约 N 分钟」的**唯一来源** ——
 * 功率高估 45% 就意味着**等待时间低估 31%**。
 * 「服务区还要等多久」乐观了近三分之一。
 *
 * 旧服务端没有 `avg_power_kw_true` 时回落到采样均值（行为退化但不崩）。
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
 * 例：「该站历史 59 kW · 充到 80% 约 32 分钟（3 次）」
 */
fun StationStat.summary(): String {
    val sb = StringBuilder()
    sb.append("该站历史 ").append(typicalPowerKw.roundToInt()).append(" kW")
    typicalMinutesTo80?.let { sb.append(" · 充到 80% 约 ").append(it).append(" 分钟") }
    sb.append("（").append(sessions).append(" 次）")
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
