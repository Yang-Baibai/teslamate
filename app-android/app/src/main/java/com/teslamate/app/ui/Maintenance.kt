package com.teslamate.app.ui

import com.teslamate.app.AppSettings
import com.teslamate.app.fmtThousands
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
    // ⚠️ v0.16.0（车队运营评审 P0-3）：基准**按车隔离** ——
    // 家人那台车 30,000 km 的表，用我的 79,000 基准去套是**假告警**。
    val b = settings.currentBaselines
    val out = ArrayList<MaintenanceReminder>(2)
    if (b.serviceOdoKm > 0) {
        val due = b.serviceOdoKm + b.serviceIntervalKm
        out += MaintenanceReminder("保养", due - odometerKm, due)
    }
    if (b.tireOdoKm > 0) {
        val due = b.tireOdoKm + b.tireIntervalKm
        out += MaintenanceReminder("轮胎", due - odometerKm, due)
    }
    return out
}

/**
 * 提醒的一句话描述（「还有 3,200 公里」/「已超期 500 公里」）。
 *
 * ⚠️ v0.14.0（新手车主 P0-2）：单位统一成**「公里」**。
 * 原来这里写「km」、车况页横幅写「公里」，同一屏两种单位。
 */
fun MaintenanceReminder.describe(): String =
    if (overdue) "已超期 ${abs(remainingKm).roundToInt()} 公里" else "还有 ${remainingKm.roundToInt()} 公里"

/**
 * ⚠️ v0.14.0（新手车主 P0-2）：横幅文案**消歧**。
 *
 * 原来写「保养已超期 2455 公里」—— 这句话在中文里有**两种读法**：
 *   1. 已经**超出保养基准** 2455 公里（正确）
 *   2. 上次保养之后**才开了** 2455 公里（差了两万公里）
 *
 * 车主原话：「我第一反应是第二种 —— 那我会以为离保养还早。」
 * 误读方向只有一个：**太晚**。而保养超期是花钱和伤车的事。
 *
 * 现在把三个数一起写出来：超了多少、该在哪个里程表做、现在多少。
 * 单看这一句就知道是「超期多少」，而不是「开了多少」。
 */
fun MaintenanceReminder.bannerText(currentOdoKm: Double?): String {
    val due = dueOdoKm.fmtThousands()
    return if (overdue) {
        buildString {
            append("$label 超期 ${kotlin.math.abs(remainingKm).roundToInt()} 公里")
            append(" · 该在里程表 $due 公里时做")
            // ⚠️ v0.14.0（单位审校 P0-3）：当前里程表也用**千分位** ——
            // 同一个数在横幅里没千分位、在车况页那行有千分位，
            // 用户会以为是两个数。
            currentOdoKm?.let { append("，现在 ${it.fmtThousands()} 公里") }
        }
    } else {
        "$label 还有 ${remainingKm.roundToInt()} 公里到期（该在里程表 $due 公里时做）"
    }
}
