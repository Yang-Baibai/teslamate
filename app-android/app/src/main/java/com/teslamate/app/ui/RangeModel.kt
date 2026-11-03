package com.teslamate.app.ui

import com.teslamate.app.CarStatus
import com.teslamate.app.ConsumptionModel
import com.teslamate.app.RangeEstimate
import com.teslamate.app.fmt
import com.teslamate.app.fmtDate
import com.teslamate.app.fmtKwh100
import kotlin.math.roundToInt

/**
 * ⚠️ **v0.119.0：续航结论区 —— 事实层与渲染层拆开。**
 *
 * ## 为什么拆
 *
 * v0.118.0 起「真实可达续航」的**判定在服务端**
 * （`GET /api/v1/cars/{id}/range_estimate`，ADR #38 / #47）⇒ App 不再自己算这个数。
 *
 * 但界面上要显示的东西分两类：
 *
 * ① **数**（真实可达 / 区间 / 保底 / Wh/km / 样本数 / 温度 / 日期）⇒ **服务端给**；
 * ② **怎么把数写成字**（区间文案、来源自曝、「怎么算的」那段说明）⇒ **只有一份**，
 *    在本文件的渲染层里。
 *
 * ⇒ 两个事实来源各管一段：
 *
 * - [rangeFactsFromServer]：**服务端结论**（首选）；
 * - [rangeFactsFromLocal]：**本地模型**兜底（服务器挂掉时用 —— v0.9.0 的老教训：
 *   不能退化成「乐观 50% 的标称」）。
 *
 * ⚠️ 两条路产出的**事实结构完全一样** ⇒ 渲染层不需要知道自己用的是哪一条
 *    ⇒ **版面与文案一个字都不动**。
 */
internal data class RangeFacts(
    /** 官方标称续航（km） */
    val ratedRange: Double?,
    /** 参考车速（km/h） */
    val refSpeed: Double,
    /** 参考车速是不是**兜底假设值** */
    val speedAssumed: Boolean,
    /** 车外温度（°C） */
    val outsideTemp: Double?,
    /** 采用档位的实测 Wh/km（null = 没有可用档位） */
    val whPerKm: Double?,
    /** 参考车速是否**正落在**这一档 */
    val whExact: Boolean,
    /** 档位名（如「0–60」） */
    val bucketLabel: String,
    /** 真实可达（km）。null = 不敢给结论。 */
    val trueRange: Double?,
    /** 真实可达的区间下界（km） */
    val bandLo: Int?,
    /** 真实可达的区间上界（km） */
    val bandHi: Int?,
    /** 「留 20% 保底还能开多少」（km） */
    val floorKm: Double?,
    /** 保底那一行自己的区间下界（km） */
    val floorBandLo: Int?,
    /** 保底那一行自己的区间上界（km） */
    val floorBandHi: Int?,
    /** 模型样本条数（null = 没有本地模型） */
    val sampleCount: Int?,
    /** 残差样本条数（≥100 公里的长途） */
    val residualSamples: Int,
    /** 样本覆盖的温度下界（°C） */
    val tempMinC: Double?,
    /** 样本覆盖的温度上界（°C） */
    val tempMaxC: Double?,
    /** ≤10°C 的样本条数 */
    val lowTempSamples: Int,
    /** 样本覆盖的起始日期（已按**服务端时区**格式化） */
    val firstDrivenAt: String?,
    /** 样本覆盖的结束日期 */
    val lastDrivenAt: String?,
    /** 温度口径说明（null = 没有要说的） */
    val tempCaveat: String?,
)

/**
 * ⚠️ **v0.119.0：服务端结论 ⇒ 事实。**
 *
 * ⚠️ **字段逐条对应**服务端 `api/range_estimate.go` 的 `RangeEstimate`
 *    （Hermes 闸门 7 钉住两端的常量）。
 *
 * ⚠️ 日期用**同一个**格式化器（[fmtDate]，服务端时区）⇒ 与本地兜底那条路
 *    **显示出来的字一模一样**（本地那条路也是从同一个 `Instant` 格式化来的）。
 */
internal fun rangeFactsFromServer(server: RangeEstimate): RangeFacts = RangeFacts(
    ratedRange = server.rated_range_km,
    refSpeed = server.reference_speed_kph,
    speedAssumed = server.speed_assumed,
    outsideTemp = server.outside_temp_c,
    whPerKm = server.wh_per_km,
    whExact = server.bucket_exact,
    bucketLabel = server.bucket_label,
    trueRange = server.true_range_km,
    bandLo = server.true_range_band_km?.lo_km,
    bandHi = server.true_range_band_km?.hi_km,
    floorKm = server.floor_range_km,
    floorBandLo = server.floor_range_band_km?.lo_km,
    floorBandHi = server.floor_range_band_km?.hi_km,
    sampleCount = server.sample_count,
    residualSamples = server.residual_samples,
    tempMinC = server.temp_min_c,
    tempMaxC = server.temp_max_c,
    lowTempSamples = server.low_temp_samples,
    firstDrivenAt = server.first_driven_at?.let { it.fmtDate() },
    lastDrivenAt = server.last_driven_at?.let { it.fmtDate() },
    tempCaveat = server.temp_caveat.takeIf { it.isNotBlank() },
)

/**
 * ⚠️ **v0.119.0：本地模型 ⇒ 事实**（服务端取不到时的**兜底**）。
 *
 * ⚠️ 这条路是 v0.119.0 之前的**原样逻辑**（逐条照搬，只把结果装进 [RangeFacts]）
 *    ⇒ 服务端挂掉时，续航**不会退化成「乐观 50% 的标称」**。
 *
 * ⚠️ 与服务端是**同一套规则**（分档边界 / 样本过滤 / 温度修正 / 保底 / 残差 P90）
 *    ⇒ 两边的数**本就相同**（Hermes 闸门 6 / 7 + 契约自检逐位比对）。
 */
internal fun rangeFactsFromLocal(
    st: CarStatus,
    consumption: ConsumptionModel?,
    recentAscent: Int?,
    fallbackSpeedKph: Double?,
): RangeFacts {
    val ratedRange = st.rated_range_km ?: st.ideal_range_km
    // 参考车速：行驶中用实时车速；停车时用**巡航速度**
    val refSpeed = st.speed?.takeIf { it > 5 }?.toDouble()
        ?: consumption?.cruiseKph
        ?: fallbackSpeedKph
        ?: 60.0
    val speedAssumed = consumption?.cruiseKph == null && fallbackSpeedKph == null
    val sample = consumption?.whSample(refSpeed)
    val trueRange = consumption?.trueRangeKm(
        ratedRangeKm = ratedRange,
        speedKph = refSpeed,
        outsideTempC = st.outside_temp,
    )
    // 「留 20% 保底」：能用掉的比例 = (当前电量 − 20%) ÷ 当前电量（绝对电量口径）。
    val socPct = st.battery_level
    val reserveFactor = if (socPct != null && socPct > 20) {
        (socPct - 20).toDouble() / socPct
    } else {
        null
    }
    val floorKm = reserveFactor?.let { rf ->
        consumption?.trueRangeKm(
            ratedRangeKm = ratedRange,
            speedKph = refSpeed,
            outsideTempC = st.outside_temp,
            ascentM = recentAscent,
            reserveFactor = rf,
        )
    }
    val band = if (trueRange != null) {
        consumption?.trueRangeBandKm(
            ratedRangeKm = ratedRange,
            speedKph = refSpeed,
            outsideTempC = st.outside_temp,
        )
    } else {
        null
    }
    val floorBand = consumption?.trueRangeBandKm(
        ratedRangeKm = ratedRange,
        speedKph = refSpeed,
        outsideTempC = st.outside_temp,
        reserveFactor = reserveFactor ?: 0.90,
    )
    return RangeFacts(
        ratedRange = ratedRange,
        refSpeed = refSpeed,
        speedAssumed = speedAssumed,
        outsideTemp = st.outside_temp,
        whPerKm = sample?.bucket?.whPerKm,
        whExact = sample?.exact ?: false,
        bucketLabel = sample?.bucket?.label ?: "",
        trueRange = trueRange,
        bandLo = band?.first,
        bandHi = band?.second,
        floorKm = floorKm,
        floorBandLo = floorBand?.first,
        floorBandHi = floorBand?.second,
        sampleCount = consumption?.sampleCount,
        residualSamples = consumption?.residualSamples ?: 0,
        tempMinC = consumption?.tempMinC,
        tempMaxC = consumption?.tempMaxC,
        lowTempSamples = consumption?.lowTempSamples ?: 0,
        firstDrivenAt = consumption?.firstDrivenAt,
        lastDrivenAt = consumption?.lastDrivenAt,
        tempCaveat = consumption?.tempCaveat(st.outside_temp),
    )
}

/**
 * ⚠️ **v0.119.0：结论用哪一份。**
 *
 * - **服务端有** ⇒ 用服务端的（飞书与 App 必然同一个数，ADR #38）；
 * - **服务端没有**（还没取到 / 服务器挂掉）⇒ 用本地模型兜底。
 */
internal fun resolveRangeFacts(
    serverRange: RangeEstimate?,
    st: CarStatus,
    consumption: ConsumptionModel?,
    recentAscent: Int?,
    fallbackSpeedKph: Double?,
): RangeFacts =
    if (serverRange != null) rangeFactsFromServer(serverRange)
    else rangeFactsFromLocal(st, consumption, recentAscent, fallbackSpeedKph)

/**
 * 续航结论区的**渲染输入**（与计算分开）。
 *
 * ⚠️ **v0.95.0**：抽成数据类，是因为「主角」与「结论区」现在**渲染在两个地方**
 * （窄栏 / 整宽），两处必须共用**同一份**计算 —— 两份算法迟早会漂移。
 */
internal data class RangeModel(
    /** 官方标称续航（km） */
    val ratedRange: Double?,
    /** 参考车速（km/h） */
    val refSpeed: Double,
    /** 参考车速是不是兜底假设值（要写进「怎么算的」） */
    val speedAssumed: Boolean,
    /** 真实可达（km）；null = 数据不足，退回标称 */
    val trueRange: Double?,
    /** 区间文案 + 区间口径（ⓘ） */
    val band: Pair<String, String>?,
    /** 「留 20% 保底还能开」多少公里 */
    val floorKm: Double?,
    /** 保底那一行自己的区间 */
    val floorBand: Pair<Int, Int>?,
    /** 来源自曝（如「88 条实测」）；区间存在时并进区间那一行 */
    val scopeLine: String?,
    /** 来源口径（ⓘ） */
    val scopeNote: String?,
    /** 温度可信度警告 */
    val tempCaveat: String?,
    /** 「怎么算的」的正文 */
    val explainText: String,
)

/**
 * 续航结论区的全部渲染（纯函数，不碰界面）。
 *
 * ⚠️ **v0.95.0**：从旧的 `RangeBlock` 里抽出来的 —— 主角与结论区现在分两处渲染，
 *    两边共用这一份计算。
 *
 * ⚠️ **v0.119.0**：输入从「现算」改成 [RangeFacts] ⇒ 判定在服务端，
 *    而**文案只有这一份**（App 与服务端不会各说各话）。
 */
internal fun buildRangeModel(f: RangeFacts): RangeModel {
    // ⚠️ **v0.43.0**：区间行必须**紧跟主角**，且保底行也要有区间。
    val band = if (f.trueRange != null && f.bandLo != null && f.bandHi != null) {
        rangeBandText(f.bandLo, f.bandHi) to
            (if (f.residualSamples > 0) {
                "（模型残差 90 分位，样本为 ${f.residualSamples} 条 ≥100 公里行程）"
            } else {
                "（模型残差 90 分位）"
            })
    } else {
        null
    }
    val floorBand = if (f.floorBandLo != null && f.floorBandHi != null) {
        f.floorBandLo to f.floorBandHi
    } else {
        null
    }
    val scopeLine = f.sampleCount?.let { rangeScopeText(it) }
    val scopeNote = if (f.tempMinC != null && f.tempMaxC != null) {
        buildString {
            append("（${f.tempMinC.roundToInt()}~${f.tempMaxC.roundToInt()}°C")
            // ⚠️ v0.86.0：样本**日期区间**折进 ⓘ（常驻位只留「N 条实测」）。
            append("；样本区间 ")
            append(f.firstDrivenAt ?: "—")
            append("~")
            append(f.lastDrivenAt ?: "—")
            if (f.lowTempSamples >= 1) {
                append("，≤10°C 只有 ${f.lowTempSamples} 条")
            } else {
                append("，无 ≤10°C 样本")
            }
            append("）")
        }
    } else {
        null
    }
    // ⚠️ **v0.9.0（新手车主复核）**：主数字只给答案，怎么算的收在一行「怎么算的 ▸」里。
    val explainText = buildString {
        if (f.ratedRange != null && f.ratedRange > 0) {
            append("官方标称 ${f.ratedRange.fmt(0)} 公里")
        }
        if (f.trueRange != null) {
            // ⚠️ **v0.30.0（第 19 轮通勤车主评审 P0-6）**：说清楚它是**哪几趟**算出来的。
            append(" · 按最近 5 趟 ≥30 公里行程的均速 ${f.refSpeed.roundToInt()} km/h 估算")
            // ⚠️ v0.9.2：兜底车速必须标成「假设」，不能和「实测巡航速度」混为一谈。
            if (f.speedAssumed) append("（假设值）")
            f.outsideTemp?.let { append(" · ${it.roundToInt()}°C") }
            // ⚠️ v0.9.2（口径审计 S2-4）：借用邻档时不能说「实测」。
            f.whPerKm?.let { w ->
                append(
                    if (f.whExact) {
                        " · 实测 ${w.fmtKwh100()} kWh/100km（${f.bucketLabel} 档）"
                    } else {
                        " · 参考 ${w.fmtKwh100()} kWh/100km（借用 ${f.bucketLabel} 档）"
                    }
                )
            }
            append(" · 已留 10% 余量")
        }
        // ⚠️ **v0.33.0**：爬升的影响在**线路余量表**里算，不在这里。
        append(" · 不含爬升修正（具体路线的爬升影响见「记录 → 线路余量表」）")
        // ⚠️ **v0.26.0**：温度可信度必须进「怎么算的」。
        f.tempCaveat?.let { append(" · $it") }
        // ⚠️ v0.14.0（单位审校 P0-4）：换算说明 —— 「怎么算的」是全 App **唯一**该出现单位口径说明的地方。
        if (f.trueRange != null) {
            append(" · 能耗单位 kWh/100km 即每 100 公里的耗电量（旧版写的 Wh/km 是同一个数）")
        }
    }
    return RangeModel(
        ratedRange = f.ratedRange,
        refSpeed = f.refSpeed,
        speedAssumed = f.speedAssumed,
        trueRange = f.trueRange,
        band = band,
        floorKm = f.floorKm,
        floorBand = floorBand,
        scopeLine = scopeLine,
        scopeNote = scopeNote,
        tempCaveat = f.tempCaveat,
        explainText = explainText,
    )
}
