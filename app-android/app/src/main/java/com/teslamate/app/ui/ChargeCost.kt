package com.teslamate.app.ui

import com.teslamate.app.Charge
import com.teslamate.app.ChargeRates
import java.util.Locale
import kotlin.math.roundToInt

/**
 * ⚠️ **v0.25.0（第 16 轮充电成本专家 P0-1 / P0-2 / P0-3 / P0-4）**。
 *
 * ## 为什么需要它
 *
 * 官方 Tesla App 有完整的 Charging Stats（总充电量、总支出、分地点类型设价），
 * 但**给不出「每公里成本」** —— 它用「总增加公里数」（充电量 ÷ 型号效率反推）当分母，
 * 官方 FAQ 自己承认与里程表对不上。
 *
 * 我们有 `drives.distance`（**真实行驶里程**），所以能给。
 * 这是**官方 App 给不了**的数。
 *
 * ## 三条口径纪律（每一条都是专家用真实数据算出来的）
 *
 * ① **分子用「网侧送出」`energy_used_kwh`，不是「充入电池」`energy_added_kwh`。**
 *    实测本车：网侧 1863.1 度 vs 充入 1736.5 度 —— **差 126.6 度（6.79%）**。
 *    桩是按**它送出去**的度数收费的，用充入量会**低估 6.79%**。
 *
 * ② **交流慢充与直流快充**分开算，不混。
 *    实测本车 97% 的电来自直流快充，交流慢充只有 3.0%。
 *    混算出来的「每公里 0.23 元」给人「我享受了家充电价」的错觉，
 *    而真实情况是这车几乎全按公共桩价买的电。
 *
 * ③ **分母是「行驶里程」**（`sum(drives.distance)`），
 *    不是里程表读数 —— 后者含停车期累计误差（实测差 16.6%）。
 *
 * ## 服务费必须能按分钟或按度
 *
 * 实测本车 73 场直流（1684.7 度 / 2194 分钟）：
 *
 * | 模型 | 总费用 | 相对纯按度 |
 * |---|---|---|
 * | 纯按充入度 × 1.20 | 2022 元 | 基准 |
 * | 纯按网侧度 × 1.20 | 2167 元 | +7.2% |
 * | 每度含 0.5 元服务费 | 2528 元 | +25.1% |
 * | 电费 0.9/度 + 服务费 0.5 元每分钟 | 2722 元 | **+34.6%** |
 *
 * 单一「每度单价」对直流部分**低估 25%~35%** ——
 * 而服务区快充普遍按分钟收服务费（**占位时间也要钱**）。
 *
 * ⚠️ 反过来：**家充绝不能按分钟算** ——
 * 实测 id=44 插枪 617 分钟只充入 17 度（有效功率仅 1.65 kW），
 * 插枪时长包含大量闲置，按分钟算会严重高估。
 */
data class ChargeCost(
    /**
     * ⚠️ **v0.26.0：这是「交流慢充」，不是「家充」。**
     *
     * 原来的字段叫 `homeKwh`，界面上写「家充 X 度」——
     * 听起来像 App **知道**哪场是在家充的。它并不知道：
     * 本机 `geofences` 表 **0 行**，78/78 场充电的 `geofence_id` 全是 NULL，
     * 而库里全部 4 场「交流」都发生在**外省的酒店和路边**，没有一场在家。
     *
     * 字段名改成 `acKwh` / `dcKwh` 就是为了让**代码里也不许再叫它家充**。
     * 价格侧仍然是「家充电价 / 公共桩电价」（那是车主自己填的意图），
     * 但界面必须说清楚：**交流慢充是按家充电价估的**。
     */
    val acKwh: Double,
    /** 直流快充网侧度数 */
    val dcKwh: Double,
    /** 直流桩总时长（分钟） */
    val publicMinutes: Double,
    /** 交流慢充电费（元，按车主填的家充电价） */
    val acYuan: Double,
    /** 直流桩费用（元） */
    val dcYuan: Double,
) {
    /** 总费用（元） */
    val totalYuan: Double get() = acYuan + dcYuan

    /** 每度实际成本（元/度，**只对有费用的部分**） */
    val yuanPerKwh: Double?
        get() {
            val used = acKwh + dcKwh
            return if (used > 0.01) totalYuan / used else null
        }
}

/**
 * 按车主的电价估算费用。
 *
 * @param charges 充电会话（需要 `energy_used_kwh` / `duration_min` / `is_dc`）。
 * @param rates 车主填的电价。
 */
fun estimateChargeCost(
    charges: List<Charge>,
    rates: ChargeRates,
): ChargeCost {
    var acKwh = 0.0
    var dcKwh = 0.0
    var publicMinutes = 0.0
    charges.forEach { c ->
        // ⚠️ P0-2：用**网侧送出**（桩按这个数收费），不是充入电池。
        val used = c.energy_used_kwh ?: c.energy_added_kwh ?: 0.0
        if (c.is_dc) {
            dcKwh += used
            publicMinutes += (c.duration_min ?: 0).toDouble()
        } else {
            acKwh += used
        }
    }
    val acYuan = acKwh * rates.homeYuanPerKwh
    val publicEnergyYuan = dcKwh * rates.publicYuanPerKwh
    val publicServiceYuan = if (rates.publicServiceMode == ChargeRates.MODE_PER_MINUTE) {
        publicMinutes * rates.publicServiceYuan
    } else {
        dcKwh * rates.publicServiceYuan
    }
    return ChargeCost(
        acKwh = acKwh,
        dcKwh = dcKwh,
        publicMinutes = publicMinutes,
        acYuan = acYuan,
        dcYuan = publicEnergyYuan + publicServiceYuan,
    )
}

/**
 * 单场充电的**估算费用**（元）。
 *
 * ⚠️ v0.25.0（第 16 轮充电成本专家 P1-6）。
 *
 * 服务端的 `cost` 全库 NULL（未配 `tou_rates`），所以费用只能**本地算** ——
 * 而这恰恰是**官方 App 做不到**的一半：它只认得 Tesla 超充（账单能抓），
 * 公共直流桩的账单它拿不到。
 *
 * ⚠️ 家充按**度**算、绝不能按分钟：实测 id=44 插枪 617 分钟只充入 17 度
 * （有效功率 1.65 kW），插枪时长含大量闲置。
 */
fun estimateSingleChargeCost(c: Charge, rates: ChargeRates): Double? {
    if (!rates.configured) return null
    // ⚠️ P0-2：分子用**网侧送出**（桩按这个数收费），不是充入电池。
    val used = c.energy_used_kwh ?: c.energy_added_kwh ?: return null
    if (used <= 0.0) return null
    return if (c.is_dc) {
        val energy = used * rates.publicYuanPerKwh
        val service = if (rates.publicServiceMode == ChargeRates.MODE_PER_MINUTE) {
            (c.duration_min ?: 0).toDouble() * rates.publicServiceYuan
        } else {
            used * rates.publicServiceYuan
        }
        energy + service
    } else {
        used * rates.homeYuanPerKwh
    }
}

/**
 * 按服务端聚合的窗口数据算费用。
 *
 * ⚠️ v0.25.0：用量页的「7 天 / 30 天 / 1 年」是**服务端时区**的窗口，
 * 所以必须用**同一窗口**的聚合结果（`/cost_summary?period=…`），
 * 不能拿「最近 N 场充电」去配「30 天的公里数」—— 那会算错。
 */
fun estimateCost(summary: com.teslamate.app.CostSummary, rates: ChargeRates): ChargeCost {
    val acYuan = summary.ac_used_kwh * rates.homeYuanPerKwh
    val publicEnergyYuan = summary.dc_used_kwh * rates.publicYuanPerKwh
    val publicServiceYuan = if (rates.publicServiceMode == ChargeRates.MODE_PER_MINUTE) {
        summary.dc_minutes * rates.publicServiceYuan
    } else {
        summary.dc_used_kwh * rates.publicServiceYuan
    }
    return ChargeCost(
        acKwh = summary.ac_used_kwh,
        dcKwh = summary.dc_used_kwh,
        publicMinutes = summary.dc_minutes,
        acYuan = acYuan,
        dcYuan = publicEnergyYuan + publicServiceYuan,
    )
}

/**
 * 每公里成本的两个口径（**不许只给一个**）。
 *
 * ⚠️ P0-3：混算交流慢充与直流快充会**系统性偏低** ——
 * 实测本车交流慢充只占 3%，却把全车 8760 公里的成本都摊薄了，
 * 让人以为「我享受了家充电价」。
 *
 * - [allPerKm]：总费用 ÷ 行驶里程 —— 「这辆车每公里花多少钱」
 * - [dcPerKm]：**只算直流桩**的费用 ÷ 行驶里程 ——
 *   「如果我今天全是公共桩充的电，每公里多花多少」= **长途的边际成本**
 */
data class CostPerKm(
    val allPerKm: Double?,
    val dcPerKm: Double?,
    /** 交流慢充占总度数的百分比（⚠️ 不等于「家充占比」—— App 分不出哪场在家） */
    val acSharePercent: Int?,
)

fun costPerKm(cost: ChargeCost, drivingKm: Double): CostPerKm {
    if (drivingKm <= 0.1) return CostPerKm(null, null, null)
    val totalUsed = cost.acKwh + cost.dcKwh
    val acShare = if (totalUsed > 0.01) {
        (cost.acKwh / totalUsed * 100).roundToInt()
    } else {
        null
    }
    return CostPerKm(
        allPerKm = cost.totalYuan / drivingKm,
        dcPerKm = cost.dcYuan / drivingKm,
        acSharePercent = acShare,
    )
}

/** 金额格式（¥ + 2 位小数 + 千分位）。 */
fun Double.fmtYuan(): String = String.format(Locale.CHINA, "¥%,.2f", this)

/** 单价格式（元/度 或 元/分钟，3 位小数去掉尾零）。 */
fun Double.fmtRate(unit: String): String {
    val s = String.format(Locale.CHINA, "%.3f", this).trimEnd('0').trimEnd('.')
    return "$s $unit"
}
