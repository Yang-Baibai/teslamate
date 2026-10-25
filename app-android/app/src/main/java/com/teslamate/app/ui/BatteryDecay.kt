package com.teslamate.app.ui

import com.teslamate.app.BatteryPoint
import com.teslamate.app.fmt
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ⚠️ **v0.29.0（第 17 轮官方 App 对标评审 A-5）：电池衰减的「效应 vs 噪声」判定。**
 *
 * ## 为什么不能直接画一条趋势线
 *
 * 专家实测本机 29 个满充点：
 * - 端点差 **66.20 → 65.17 kWh = −1.03 度（−1.6%）**，跨 9936 公里；
 * - 而**单点测量噪声 σ ≈ 0.5 度（0.77%）**。
 *
 * 也就是说：**效应只有噪声的 2 倍**。
 * 在这种情况下画一条拟合趋势线，等于**把噪声画成趋势**——
 * 用户会读成「电池在明显衰减」，而数据根本支撑不了这个结论。
 *
 * 专家的建议是「只画散点 + 稳健回归 + 置信带，样本 <50 个满充点时不给趋势线」。
 * 这里做的是它的可落地版本：**把「效应 / 噪声」这个比值算出来给用户看**，
 * 并在比值不够时明说「还不能当趋势看」。
 *
 * ## 口径
 *
 * - **自变量用里程**（`odometer_km`）而不是日期 ——
 *   电池衰减是**按公里**发生的，按日期会把「这几个月开得多」误读成「衰减快」。
 * - **趋势用最小二乘**（对里程），残差 σ 就是单点测量波动。
 * - 样本 < [MIN_POINTS_FOR_TREND] 个点 → **不给结论**。
 * - 效应/噪声 < [MIN_EFFECT_TO_NOISE] → **不给趋势结论**（只给区间）。
 */
object BatteryDecay {
    /** 少于这么多个满充点，不谈趋势。 */
    const val MIN_POINTS_FOR_TREND = 50

    /** 效应/噪声 至少要到这个倍数才敢叫「趋势」。 */
    const val MIN_EFFECT_TO_NOISE = 3.0

    /** 一个测量点。 */
    data class Point(val km: Double, val kwh: Double)

    /** 判定结果。 */
    data class Verdict(
        /** 满充点个数 */
        val n: Int,
        /** 每 1000 公里的线性变化（度，负数 = 在衰减） */
        val per1000Km: Double?,
        /** 整段跨度的端到端变化（度） */
        val totalChange: Double?,
        /** 残差标准差（度）= **单点测量波动** */
        val noiseSigma: Double?,
        /** 原始标准差（度）—— **含趋势本身**，所以比残差 σ 大 */
        val rawSigma: Double?,
        /** 效应 / 噪声（用残差 σ） */
        val effectToNoise: Double?,
        /** 能不能下「趋势」结论 */
        val trendVerdict: Boolean,
        /** 覆盖的里程跨度（公里） */
        val spanKm: Double,
    ) {
        /**
         * 一句话结论（给界面直接用）。
         *
         * ⚠️ **v0.29.0：噪声有两个口径，两个都说清楚。**
         *
         * 专家给的数是「单点测量噪声 σ ≈ 0.5 度」，那是**原始标准差** ——
         * 它**把趋势本身也算进噪声里了**（因为序列整体在下降）。
         * 真正回答「这条线是衰减还是抖动」的分母是**残差标准差**
         * （扣掉线性趋势后的残余波动），实测 **0.31 度**。
         *
         * 两个口径的差别很大：
         * - 原始 σ 0.55 → 效应/噪声 = 1.03/0.55 ≈ **1.9 倍**（看不出趋势）
         * - 残差 σ 0.31 → 效应/噪声 = 1.65/0.31 ≈ **5.3 倍**（能分辨）
         *
         * 所以界面既不给「这只是噪声」的结论，也不给「这就是趋势」的结论 ——
         * 它把两个数都摆出来，并指出**真正的短板是样本量**（29 < 50）。
         */
        fun summary(): String {
            val sb = StringBuilder()
            sb.append("$n 个满充点")
            if (!trendVerdict) {
                sb.append("（要 ≥").append(MIN_POINTS_FOR_TREND).append(" 个才够下趋势结论）")
            } else {
                sb.append("（样本够了）")
            }
            per1000Km?.let { sb.append(" · 每 1000 公里 ").append("%.3f".format(it)).append(" 度") }
            return sb.toString()
        }

        /** 结论的第二行（效应 vs 噪声）。 */
        fun detail(): String? {
            val e = effectToNoise ?: return null
            return buildString {
                append("整段变化 ")
                totalChange?.let { append(it.fmt(2)).append(" 度") }
                noiseSigma?.let { append("，单点测量波动 σ=").append(it.fmt(2)).append(" 度") }
                append(" —— 效应是波动的 ")
                append("%.1f".format(e))
                append(" 倍")
                if (!trendVerdict) {
                    append("。样本量不够，现在这条线**先当区间看**")
                } else {
                    append("。可以当趋势看")
                }
            }
        }
    }

    /**
     * 判定电池衰减是不是「已经能看出趋势」。
     *
     * @param series 服务端给的满充点序列（已按时间升序）
     */
    fun analyze(series: List<BatteryPoint>): Verdict? {
        val pts = series.mapNotNull { p ->
            val kwh = p.capacity_kwh ?: return@mapNotNull null
            val km = p.odometer_km ?: return@mapNotNull null
            Point(km, kwh)
        }
        if (pts.size < 2) return null
        val n = pts.size
        val meanX = pts.sumOf { it.km } / n
        val meanY = pts.sumOf { it.kwh } / n
        val sxx = pts.sumOf { (it.km - meanX) * (it.km - meanX) }
        if (sxx <= 0.0) {
            return Verdict(n, null, null, null, null, null, false, 0.0)
        }
        val sxy = pts.sumOf { (it.km - meanX) * (it.kwh - meanY) }
        val slope = sxy / sxx
        val intercept = meanY - slope * meanX
        // 残差 σ（自由度 n-2）
        val sse = pts.sumOf {
            val r = it.kwh - (intercept + slope * it.km)
            r * r
        }
        val sigma = if (n > 2) sqrt(sse / (n - 2)) else null
        // 原始标准差（含趋势本身）—— 用来和专家给的数对上
        val rawSigma = sqrt(pts.sumOf { (it.kwh - meanY) * (it.kwh - meanY) } / (n - 1))
        val spanKm = pts.last().km - pts.first().km
        val totalChange = if (spanKm != 0.0) slope * spanKm else null
        val effectToNoise = if (sigma != null && sigma > 1e-9) {
            abs(totalChange ?: 0.0) / sigma
        } else {
            null
        }
        val trendVerdict = n >= MIN_POINTS_FOR_TREND &&
            effectToNoise != null && effectToNoise >= MIN_EFFECT_TO_NOISE
        return Verdict(
            n = n,
            per1000Km = slope * 1000.0,
            totalChange = totalChange,
            noiseSigma = sigma,
            rawSigma = rawSigma,
            effectToNoise = effectToNoise,
            trendVerdict = trendVerdict,
            spanKm = spanKm,
        )
    }
}
