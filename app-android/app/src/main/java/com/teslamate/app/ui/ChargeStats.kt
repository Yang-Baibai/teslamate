package com.teslamate.app.ui

import com.teslamate.app.Charge
import com.teslamate.app.firstSegment
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 充电站历史统计 —— 回答长途自驾党最关心的那个问题：
 * **「这个桩到底快不快？我要等多久？」**
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
 * - **充满到 80% 的耗时**用「该车反推出的电池容量」折算：
 *   容量 = 充入 kWh ÷ ΔSOC% × 100，每次充电各算一个再取中位数。
 *   这是 TeslaMate 没有直接提供、但可以可靠反推的量。
 */
data class StationStat(
    /** 该站历史充电次数 */
    val sessions: Int,
    /** 典型充电功率（kW，中位数） */
    val typicalPowerKw: Double,
    /** 从 ≤50% 充到 80% 的典型耗时（分钟，中位数）；样本不足为 null */
    val typicalMinutesTo80: Int?,
    /** 反推出的电池容量（kWh），用于折算耗时 */
    val batteryCapacityKwh: Double,
)

/**
 * 按地点聚合充电历史。
 *
 * key 用地点的**第一段**（完整邮编地址的第一段，如「G210, 车河镇, 南丹县…」→「G210」），
 * 与列表里显示的地点口径一致。
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
        .groupBy { (it.place ?: "").firstSegment() ?: "" }

    return grouped.mapNotNull { (place, list) ->
        if (place.isBlank() || list.size < 2) return@mapNotNull null
        val powers = list.mapNotNull { it.avg_power_kw }.filter { it > 1.0 }
        if (powers.isEmpty()) return@mapNotNull null
        val typicalPower = powers.sorted()[powers.size / 2].toDouble()

        // 从 ≤50% 出发的充电：算充到 80% 需要多久
        val minutes = list.mapNotNull { c ->
            val start = c.start_soc ?: return@mapNotNull null
            val kw = c.avg_power_kw ?: return@mapNotNull null
            if (start > 50 || kw <= 1.0) return@mapNotNull null
            val needKwh = (80 - start) / 100.0 * capacity
            needKwh / kw * 60.0
        }.filter { it in 3.0..600.0 }.map { it.roundToInt() }

        place to StationStat(
            sessions = list.size,
            typicalPowerKw = typicalPower,
            typicalMinutesTo80 = if (minutes.size >= 2) {
                minutes.sorted()[minutes.size / 2]
            } else null,
            batteryCapacityKwh = capacity,
        )
    }.toMap()
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
