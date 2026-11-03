package com.teslamate.app

import kotlinx.serialization.Serializable

/**
 * ⚠️ **v0.123.0：保养 / 轮胎提醒的服务端结论。**
 *
 * ## 为什么服务端说了算
 *
 * 之前保养基准只存在**手机本地** ⇒ 飞书 / Hermes **完全问不出**
 * 「我轮胎还能跑多少」⇒ App 与 Hermes 会各说各话（ADR #38 / #52）。
 *
 * ⇒ 现在基准存在服务端（按车隔离），**判定与文案都在服务端**
 * （api/maintenance.go）⇒ App 只渲染，Hermes 只透传。
 *
 * ## 三态语义
 *
 * · [odometer_km] 为 null ⇒ **车端里程表读不到**（车睡着了 / 没配 Fleet）
 *   ⇒ 服务端**不给任何判定**（宁可不说，也不误报 —— v0.75.0 的教训：
 *   拿 GPS 累计值去套会报「保养超期 38,422 公里」的假告警）。
 * · [service] / [tire] 为 null ⇒ 那项**没设基准**（或里程表读不到）。
 * · [text] / [banner_text] 是**服务端出的中文原文**（ADR #41：文案只有一份）。
 */
@Serializable
data class MaintenanceReport(
    /** 车号。 */
    val car_id: Int = 0,
    /** 当前**车端里程表**（km）；null = 读不到（见上面的三态语义）。 */
    val odometer_km: Double? = null,
    /** 里程表来源（「车端」/「未知」）。 */
    val odometer_source: String = "",
    /** 基准是否已设置过（false = 用户还没填过）。 */
    val configured: Boolean = false,
    /** 保养那项的结论（null = 没设基准 / 里程表读不到）。 */
    val service: MaintenanceItem? = null,
    /** 轮胎那项的结论（null = 没设基准 / 里程表读不到）。 */
    val tire: MaintenanceItem? = null,
    /** 一句话总结（服务端唯一一份文案）。 */
    val summary: String = "",
    /** 需要说明时的一句话（空 = 不需要）。 */
    val note: String = "",
    /** 原始基准（设置页要显示它）。 */
    val baseline: MaintenanceBaseline = MaintenanceBaseline(),
)

/**
 * 一项提醒（保养 / 轮胎）的**服务端结论**。
 */
@Serializable
data class MaintenanceItem(
    /** 「保养」/「轮胎」。 */
    val label: String = "",
    /** 该在哪个**车端里程表**读数时做。 */
    val due_odo_km: Double = 0.0,
    /** 还剩多少公里（**负数 = 已超期**）。 */
    val remaining_km: Double = 0.0,
    /** 已超期。 */
    val overdue: Boolean = false,
    /** 快到了（2000 公里内）—— 该提醒，但还没超期。 */
    val due_soon: Boolean = false,
    /** 这一项的中文原文（服务端唯一一份）。 */
    val text: String = "",
    /** 首页横幅用的那一句（服务端唯一一份）。 */
    val banner_text: String = "",
)

/**
 * 一台车的保养 / 轮胎基准（**用户填的输入**，不是遥测）。
 */
@Serializable
data class MaintenanceBaseline(
    /** 上次保养时的**车端里程表**读数（km）；0 = 没设置。 */
    val service_odo_km: Double = 0.0,
    /** 保养间隔（km）。 */
    val service_interval_km: Int = 20000,
    /** 上次换胎时的车端里程表读数（km）；0 = 没设置。 */
    val tire_odo_km: Double = 0.0,
    /** 换胎间隔（km）。 */
    val tire_interval_km: Int = 40000,
)
