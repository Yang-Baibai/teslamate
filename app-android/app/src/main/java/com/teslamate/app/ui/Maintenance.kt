package com.teslamate.app.ui

import com.teslamate.app.AppSettings
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 保养 / 换胎提醒。
 *
 * ⚠️ v0.9.0（新手车主复核）：「上次保养是什么时候 / 什么时候该换胎 ——
 * 全项目 grep 保养/维修/保险 **0 命中**，API 也没这个接口。**这是我最想要的**。」
 *
 * ## 为什么不是服务端算的
 *
 * 保养是**车主的线下行为**：换了机油、开了保养单，只有车主自己知道，
 * 没有任何机器能自动知道。所以：
 *
 *   - 车主在「设置」里填两个数：**上次做时的里程表读数** + **间隔**
 *   - App 用**当前里程表**一减，就是「还剩多少公里」
 *
 * 这是本 App 里唯一一个「输入一次 → 之后自动更新」的小工具：
 * 输入是车主的知识，结论由 App 算。
 */
data class MaintenanceReminder(
    /** 「保养」/「轮胎」 */
    val label: String,
    /** 还剩多少公里（**负数表示已超期**） */
    val remainingKm: Double,
    /** 该在哪个里程表读数时做 */
    val dueOdoKm: Double,
) {
    /** 已超期 */
    val overdue: Boolean get() = remainingKm < 0

    /** 快到了（2000 km 内）—— 该提醒，但还没超期 */
    val dueSoon: Boolean get() = !overdue && remainingKm <= 2000

    /** 需要行动（超期或快到了）—— 只有这时才值得在首页顶部挂横幅 */
    val needsAction: Boolean get() = overdue || dueSoon
}

/**
 * 由当前里程表与设置算出提醒列表。
 *
 * 没填基准（= 0）或拿不到里程表时返回空列表 —— 不制造噪音。
 */
fun maintenanceReminders(odometerKm: Double?, settings: AppSettings): List<MaintenanceReminder> {
    if (odometerKm == null || odometerKm <= 0) return emptyList()
    val out = ArrayList<MaintenanceReminder>(2)
    if (settings.serviceReminderOn) {
        val due = settings.serviceOdoKm + settings.serviceIntervalKm
        out += MaintenanceReminder("保养", due - odometerKm, due)
    }
    if (settings.tireReminderOn) {
        val due = settings.tireOdoKm + settings.tireIntervalKm
        out += MaintenanceReminder("轮胎", due - odometerKm, due)
    }
    return out
}

/** 提醒的一句话描述（「还有 3,200 km」/「已超期 500 km」）。 */
fun MaintenanceReminder.describe(): String =
    if (overdue) "已超期 ${abs(remainingKm).roundToInt()} km" else "还有 ${remainingKm.roundToInt()} km"
