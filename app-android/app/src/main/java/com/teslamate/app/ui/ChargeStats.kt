package com.teslamate.app.ui

import com.teslamate.app.Charge
import com.teslamate.app.firstSegment
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
    val capacity = perPercent.sorted()[perPercent.size / 2]

    val grouped = charges
        .filter { !it.place.isNullOrBlank() }
        .groupBy { stationKey(it) }

    return grouped.mapNotNull { (key, list) ->
        if (key.isBlank() || list.size < 2) return@mapNotNull null
        val powers = list.mapNotNull { it.avg_power_kw }.filter { it > 1.0 }
        if (powers.isEmpty()) return@mapNotNull null
        val typicalPower = median(powers.map { it.toDouble() })

        // ⚠️ v0.9.2：直流与交流**分开统计**。
        // AC 典型约 7 kW、DC 典型约 55 kW，混在一起取中位数得到的数两边都不是。
        val dcPowers = list.filter { it.is_dc }.mapNotNull { it.avg_power_kw }.filter { it > 1.0 }
        val typicalPowerDc = if (dcPowers.size >= 2) median(dcPowers.map { it.toDouble() }) else null

        // 从 ≤50% 出发的充电：算充到 80% 需要多久
        val minutes = list.mapNotNull { c ->
            val start = c.start_soc ?: return@mapNotNull null
            val kw = c.avg_power_kw ?: return@mapNotNull null
            if (start > 50 || kw <= 1.0) return@mapNotNull null
            val needKwh = (80 - start) / 100.0 * capacity
            needKwh / kw * 60.0
        }.filter { it in 3.0..600.0 }.map { it.roundToInt() }.sorted()

        key to StationStat(
            sessions = list.size,
            typicalPowerKw = typicalPower,
            typicalPowerDcKw = typicalPowerDc,
            typicalMinutesTo80 = if (minutes.size >= 2) minutes[minutes.size / 2] else null,
            p90MinutesTo80 = if (minutes.size >= 5) minutes[(minutes.size * 0.9).toInt().coerceAtMost(minutes.size - 1)] else null,
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
 * 中位数。
 *
 * ⚠️ v0.9.2（口径审计 S3-1）：偶数样本时这里取的是**上中位数**
 * （index = n/2），而 numpy / pandas / R 的 `median()` 取两个中间值的平均 ——
 * 第三方按教科书算法复算会对不上（Wh/km 差 0.5–2%，耗时差 1 分钟）。
 * 现在改成**两个中间值的平均**，与教科书一致。
 */
private fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
}

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
