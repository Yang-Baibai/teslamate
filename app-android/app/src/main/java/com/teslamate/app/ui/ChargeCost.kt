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
 *    不是里程表读数。
 *
 *    ⚠️ **v0.31.0（第 20 轮费用复审 P0-1）：这条注释原来把因果写反了。**
 *
 *    原文写的是「后者含停车期累计误差（实测差 16.6%）」——
 *    事实**不是**这样：
 *    - `sum(drives.distance)` 与**逐段里程表差之和完全相等**（ratio 1.0000），
 *      所以 `drives.distance` **没有测距误差**；
 *    - 差的那 1459.62 公里是**两趟行程之间的遥测断档** ——
 *      12 次断档（最大 502.59 公里）里车**真的开了**，
 *      但 TeslaMate **没记行程**（开车全程 positions 只有 75 行，且全在断档末端），
 *      连充电记录也是 0 场。
 *
 *    所以：**里程表是物理真值，`drives.distance` 漏掉的是没被记录的那部分**，
 *    两者都不算「错的那个」。
 *
 *    现在两个分母都下发（`km_drives` / `km_odometer`），
 *    客户端在差 > 1% 时把「每公里成本」显示成**区间**（下界 ÷ 里程表，上界 ÷ 行驶里程）。
 *    实测：今天 / 7 天 / 本月三段两者只差 0.10%~0.12%（区间退化成点值），
 *    「1 年」段差 **16.71%**。
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
     * ⚠️ **v0.30.0（第 19 轮财务评审 P0-3）：围栏命中的「真家充」网侧度数。**
     *
     * 这是 [acOtherKwh] 的**对面** —— 两者的和 = 全部交流慢充。
     *
     * 判定依据是 TeslaMate 的**充电围栏**（`charging_processes.geofence_id`），
     * 不是「交流 ⇒ 家充」这种猜法 ——
     * 实测本机 5 场交流慢充**全部**在 286~1506 公里外，猜法 **5/5 全错**。
     */
    val homeKwh: Double,
    /** 没有围栏归属的交流慢充网侧度数 */
    val acOtherKwh: Double,
    /** 直流快充网侧度数 */
    val dcKwh: Double,
    /** 直流桩总时长（分钟） */
    val publicMinutes: Double,
    /** 电费（元）—— **不含**公共桩服务费 */
    val energyYuan: Double,
    /** 公共桩服务费（元）—— 单独拆出来，界面要能显示它 */
    val serviceYuan: Double,
    /** 直流快充那部分的**电费**（不含服务费） */
    val dcEnergyYuan: Double = 0.0,
) {
    /** 交流慢充合计（家充 + 无围栏归属的交流） */
    val acKwh: Double get() = homeKwh + acOtherKwh

    /** 直流快充那部分的**总**费用（电费 + 服务费） */
    val dcYuan: Double get() = dcEnergyYuan + serviceYuan

    /** 总费用（元，电费 + 服务费） */
    val totalYuan: Double get() = energyYuan + serviceYuan

    /** 总度数 */
    val totalKwh: Double get() = acKwh + dcKwh

    /**
     * 每度**电费**（元/度，**不含服务费**）。
     *
     * ⚠️ v0.30.0：原来只有一个 `yuanPerKwh`（含服务费），
     * 界面上叫「实际每度电」—— 实测全周期 1.7689 元/度里
     * **0.5874（33.2%）是服务费**，车主拿它和自己的居民电价一比会以为贵了三倍。
     * 现在拆成三个数：电费 / 服务费 / 合计。
     */
    val energyYuanPerKwh: Double?
        get() = if (totalKwh > 0.01) energyYuan / totalKwh else null

    /** 每度**服务费**（元/度） */
    val serviceYuanPerKwh: Double?
        get() = if (totalKwh > 0.01) serviceYuan / totalKwh else null

    /** 每度合计（元/度） */
    val yuanPerKwh: Double?
        get() = if (totalKwh > 0.01) totalYuan / totalKwh else null
}

/**
 * ⚠️ **v0.31.0（第 20 轮费用复审 P2-5）：删掉死代码 `estimateChargeCost`。**
 *
 * 它是 0 调用点的**第二份**分桶/计价实现 —— 将来必然与 [buildCost] 分叉。
 * 实际在用的是：
 * - [estimateSingleChargeCost]（充电详情页单场）
 * - [estimateCost]（用量页窗口聚合）
 *
 * ⚠️ 原来那个函数还在的时候，它把「有围栏归属的交流」判成家充 ——
 * 而 [buildCost] 现在也这么做，两边一致才没出事；删掉一份就少一个分叉点。
 */

/**
 * ⚠️ **v0.30.0：把度数换算成金额的唯一入口。**
 *
 * 三个桶各自定价，**不混**：
 * - 家充（有围栏归属的交流）→ 家充电价，**绝不按分钟**（插枪时长含大量闲置）
 * - 无围栏归属的交流 → 按车主的 [ChargeRates.acUsesHomePrice] 设置
 * - 直流 → 公共桩电价 + 公共桩服务费（按分钟或按度）
 */
private fun buildCost(
    homeKwh: Double,
    acOtherKwh: Double,
    dcKwh: Double,
    publicMinutes: Double,
    rates: ChargeRates,
): ChargeCost {
    val acOtherPrice = if (rates.acUsesHomePrice) rates.homeYuanPerKwh else rates.publicYuanPerKwh
    val energyYuan = homeKwh * rates.homeYuanPerKwh +
        acOtherKwh * acOtherPrice +
        dcKwh * rates.publicYuanPerKwh
    val dcEnergyYuan = dcKwh * rates.publicYuanPerKwh
    val serviceYuan = if (rates.publicServiceMode == ChargeRates.MODE_PER_MINUTE) {
        publicMinutes * rates.publicServiceYuan
    } else {
        dcKwh * rates.publicServiceYuan
    }
    return ChargeCost(
        homeKwh = homeKwh,
        acOtherKwh = acOtherKwh,
        dcKwh = dcKwh,
        publicMinutes = publicMinutes,
        energyYuan = energyYuan,
        serviceYuan = serviceYuan,
        dcEnergyYuan = dcEnergyYuan,
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
    } else if (!c.geofence_name.isNullOrBlank()) {
        // ⚠️ v0.30.0：有围栏归属 = 真家充 → 家充电价（绝不按分钟）
        used * rates.homeYuanPerKwh
    } else {
        // 没有围栏归属 → 按车主的设置（默认公共桩价）
        val price = if (rates.acUsesHomePrice) rates.homeYuanPerKwh else rates.publicYuanPerKwh
        used * price
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
    // ⚠️ v0.30.0：家充桶是交流的**子集** —— 没有围栏归属的交流 = ac − home
    val homeKwh = summary.home_used_kwh
    val acOtherKwh = (summary.ac_used_kwh - homeKwh).coerceAtLeast(0.0)
    return buildCost(homeKwh, acOtherKwh, summary.dc_used_kwh, summary.dc_minutes, rates)
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
    /** 上界：总费用 ÷ **App 记录到的**行驶里程（`sum(drives.distance)`） */
    val allPerKm: Double?,
    val dcPerKm: Double?,
    /** 交流慢充占总度数的百分比 */
    val acSharePercent: Int?,
    /**
     * ⚠️ **v0.30.0：其中「有围栏归属」（真家充）的百分比。**
     *
     * 之前 App 只能说「交流慢充占 X%」，而交流慢充**不等于**家充。
     * 现在围栏能给出真凭据，这个数才是真的「家充占比」。
     */
    val homeSharePercent: Int?,
    /**
     * ⚠️ **v0.31.0（第 20 轮费用复审 P0-1）：下界** —— 总费用 ÷ **里程表跨度**。
     *
     * 里程表跨度**包含**「两趟行程之间的遥测断档」里车真开过的公里 ——
     * 实测全库 12 次断档合计 **1459.62 公里（16.71%）**，
     * 最大一次 502.59 公里（开车全程 positions 只有 75 行，全在断档末端）。
     *
     * 所以长窗口的「每公里成本」是**区间**：
     * 下界 = 费用 ÷ 里程表，上界 = 费用 ÷ App 记录的行驶里程。
     */
    val allPerKmLow: Double? = null,
    /**
     * ⚠️ v0.31.0：两个分母的差异百分比。
     *
     * ≥1% 才值得显示区间；短窗口（今天 / 7 天 / 本月）实测只有 0.10%~0.12%，
     * 那时区间退化成点值，不给界面加噪音。
     */
    val denominatorGapPercent: Int? = null,
)

fun costPerKm(
    cost: ChargeCost,
    drivingKm: Double,
    /** 里程表在窗口内的跨度（km）—— 可为 null（拿不到就不给下界） */
    odometerKm: Double? = null,
): CostPerKm {
    if (drivingKm <= 0.1) return CostPerKm(null, null, null, null)
    val totalUsed = cost.totalKwh
    val acShare = if (totalUsed > 0.01) {
        (cost.acKwh / totalUsed * 100).roundToInt()
    } else {
        null
    }
    val homeShare = if (totalUsed > 0.01) {
        (cost.homeKwh / totalUsed * 100).roundToInt()
    } else {
        null
    }
    // ⚠️ v0.31.0：区间只在两个分母**差得够多**时才给。
    val gapPct = if (odometerKm != null && odometerKm > drivingKm) {
        ((odometerKm - drivingKm) / drivingKm * 100).roundToInt()
    } else {
        null
    }
    val low = if (odometerKm != null && odometerKm > 0.1 && (gapPct ?: 0) >= 1) {
        cost.totalYuan / odometerKm
    } else {
        null
    }
    return CostPerKm(
        allPerKm = cost.totalYuan / drivingKm,
        dcPerKm = cost.dcYuan / drivingKm,
        acSharePercent = acShare,
        homeSharePercent = homeShare,
        allPerKmLow = low,
        denominatorGapPercent = gapPct,
    )
}

/** 金额格式（¥ + 2 位小数 + 千分位）。 */
fun Double.fmtYuan(): String = String.format(Locale.CHINA, "¥%,.2f", this)

/** 单价格式（元/度 或 元/分钟，3 位小数去掉尾零）。 */
fun Double.fmtRate(unit: String): String {
    val s = String.format(Locale.CHINA, "%.3f", this).trimEnd('0').trimEnd('.')
    return "$s $unit"
}
