package com.teslamate.app

/**
 * 胎压判定（车况页与后台通知共用同一套口径）。
 *
 * ⚠️ 阈值的来历（2026-10-16 改）：原先写死「2.4–2.9 bar」是抄来的通用值，与本车实际
 * 工况严重不符——查 51434 个真实采样点，本车范围 2.50–3.30、平均 2.96 bar，于是
 * **62.4% 的记录都会触发「建议检查」**。一个 62% 时间误报的告警等于没有告警：
 * 用户会把它当噪音忽略，连带把真正该响的漏气信号也一起忽略，比没有这功能更糟。
 * 现按本车实测分布改为 2.5–3.3 bar。真正能识别「单胎漏气」的是按中位数做的
 * 离群检测，保持不变。换车只需改上面两个常量。
 */
const val TPMS_MIN_BAR = 2.5
const val TPMS_MAX_BAR = 3.3

/** 四轮胎压 + 判定结果。null 表示该轮无数据（未上报）。 */
data class TpmsReading(
    val fl: Double? = null,
    val fr: Double? = null,
    val rl: Double? = null,
    val rr: Double? = null,
) {
    /** 有读数的轮胎数量 */
    val values: List<Double> = listOfNotNull(fl, fr, rl, rr)

    /** 四轮中位数，用于「单胎明显偏低」判断 */
    val median: Double? =
        if (values.isEmpty()) null else values.sorted()[values.size / 2]

    /** 超出绝对正常范围 */
    fun outOfRange(p: Double?): Boolean =
        p != null && (p < TPMS_MIN_BAR || p > TPMS_MAX_BAR)

    /** 单胎明显低于其他三轮（差 0.2 bar 以上）——真正能识别漏气的信号 */
    fun lowOutlier(p: Double?): Boolean {
        val m = median ?: return false
        return p != null && (m - p) > 0.2
    }

    /** 某一轮是否需要告警 */
    fun isAlerting(p: Double?): Boolean = outOfRange(p) || lowOutlier(p)

    /** 整体是否有异常 */
    val anyAlert: Boolean =
        isAlerting(fl) || isAlerting(fr) || isAlerting(rl) || isAlerting(rr)

    /** 异常轮的可读描述，用于通知文案 */
    fun alertSummary(): String {
        val bad = buildList {
            if (isAlerting(fl)) add("左前")
            if (isAlerting(fr)) add("右前")
            if (isAlerting(rl)) add("左后")
            if (isAlerting(rr)) add("右后")
        }
        return bad.joinToString("、")
    }

    /** 四轮原始值描述（用于通知里带具体数字） */
    fun detail(): String = buildList {
        fl?.let { add("左前 ${fmt1(it)}") }
        fr?.let { add("右前 ${fmt1(it)}") }
        rl?.let { add("左后 ${fmt1(it)}") }
        rr?.let { add("右后 ${fmt1(it)}") }
    }.joinToString("  ")

    private fun fmt1(v: Double): String = String.format("%.2f", v)
}
