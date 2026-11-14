package com.teslamate.app.ui

/**
 * ⚠️ **v0.162.0**：「低电量」的判定**只在服务端做一次**（ADR #38 / #41）。
 *
 * ## 为什么要有这一份
 *
 * 原来 App 自己在 ui/Widgets.kt 里按「soc < 20」/「soc < 10」判（电量环、能量条、胶囊三处）
 * ⇒ **阈值写死在客户端**，而**飞书那边一条低电量提醒都没有**
 * ⇒ 两端对「低电量」的定义**不一样**（用户会看到互相矛盾的事实）。
 *
 * 现在：服务端 api/alerts.go 的 EvaluateLowBattery 判一次，
 * /status 给出 low_battery / low_battery_severe，
 * App 与推送脚本（scripts/watch-car.ps1）**都只读结果**。
 *
 * ## 兜底是干什么的
 *
 * 老服务端 / 旧缓存里**没有**这两个结论字段 ⇒ App 不能因此把「低电量」
 * 悄悄说成「正常」（那是**假安全**）⇒ 服务端没给结论时，用本地兜底按阈值算一次。
 * 兜底的两个数与服务端**逐值相等**，由客户端闸门 41 钉住。
 */

/**
 * ⚠️ 客户端兜底阈值 —— **只在服务端没给结论时**才用（老服务端 / 旧缓存）。
 *
 * ⚠️ 这两个数是服务端的**镜像**，不是第二份事实源：
 * 正常路径（服务端已给结论）下它们**根本不参与判定**。
 */
const val LOW_BATTERY_FALLBACK_SOC = 20
const val LOW_BATTERY_SEVERE_FALLBACK_SOC = 10

/**
 * 低电量的结论（**服务端是唯一事实源**）。
 *
 * @param low 现在电量低吗（不在充电 ＋ 低于阈值）。
 * @param severe 现在电量严重偏低吗。
 */
data class LowBatteryUi(
    val low: Boolean,
    val severe: Boolean,
) {
    companion object {
        /** 「什么都不知道」—— 服务端没给结论、也没读到电量时用。 */
        val NONE = LowBatteryUi(low = false, severe = false)
    }
}

/**
 * 把服务端给的结论装配成界面要用的东西（纯函数 ⇒ 单测钉得住）。
 *
 * @param soc 当前电量（%）；null = 没读到。
 * @param charging 此刻是否在充电。
 * @param serverLow 服务端结论（null = 服务端没给 ⇒ 走兜底）。
 * @param serverSevere 服务端结论（null = 服务端没给 ⇒ 走兜底）。
 */
fun resolveLowBattery(
    soc: Int?,
    charging: Boolean,
    serverLow: Boolean?,
    serverSevere: Boolean?,
): LowBatteryUi {
    // ⚠️ 服务端给了结论 ⇒ **直接用**，客户端不重算（ADR #38：判定只在服务端做）。
    if (serverLow != null && serverSevere != null) {
        return LowBatteryUi(low = serverLow, severe = serverSevere)
    }
    // 服务端没给结论（老服务端 / 旧缓存）⇒ 按兜底阈值算一次（口径与服务端一致）。
    val s = soc
    return LowBatteryUi(
        low = s != null && !charging && s < LOW_BATTERY_FALLBACK_SOC,
        severe = s != null && !charging && s < LOW_BATTERY_SEVERE_FALLBACK_SOC,
    )
}
