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
            // ⚠️ v0.20.0（车主 P2-5）：**不再重复当前里程表** ——
            // 车况页下方那行「里程表 101,455 公里」已经写着同一个数，
            // 一字不差地出现两次，用户会以为是两个不同的数。
            // 这里只保留「超了多少 / 该在多少时做」——这两个数下方没有。
        }
    } else {
        "$label 还有 ${remainingKm.roundToInt()} 公里到期（该在里程表 $due 公里时做）"
    }
}

/**
 * 保养卡底部那一行的文案（**唯一事实源**）——「基准与间隔」＋ 是哪台车。
 *
 * ⚠️ **v0.121.0（本轮手术点）**：原来这一行是
 * 「基准与间隔（爱车）· 点这里修改」⇒ 两个毛病：
 *
 * ① **「· 点这里修改」是教学语** —— 界面里教用户怎么点。
 *    而**可点性已经由右边的箭头图标承担了**（KeyboardArrowRight）
 *    ⇒ 同一件事说了两遍（降噪的第一原则：一句事实只说一次）。
 *    ⚠️ 全 App 扫过一遍：这一行是**最后**一处「点这里 / 点击」类教学语。
 * ② **「（爱车）」的括号**把车名降级成「补充说明」
 *    （用户反馈 #25 的同类诉求：「各个按钮**可以不需要括号内的那些东西**」）。
 *
 * ⇒ 现在这一行只剩**事实**：基准与间隔，是哪台车的。
 */
fun maintenanceBaselineLabel(carName: String?): String =
    if (carName.isNullOrBlank()) "基准与间隔" else "基准与间隔 · $carName"

/** 那一行的字号：labelSmall = **12sp**（见 ui/Theme.kt；⚠️ 别拿 M3 默认值算）。 */
const val BASELINE_ROW_SP = 12f

/**
 * 那一行里**文字**能用的宽度（dp）：卡片内容区（FULL_WIDTH_DP = 361dp）
 * − 间距 3dp − 箭头图标 14dp。
 *
 * ⚠️ 这个数是**承重**的：旧文案「基准与间隔（爱车）· 点这里修改」在 200% 字号下
 * 要 **367.20dp > 344dp** ⇒ 放不下（会被折行 / 把箭头挤出屏幕）
 * ⇒ 这就是本轮手术的理由。
 */
const val BASELINE_ROW_TEXT_DP = FULL_WIDTH_DP - 3f - 14f

/** 那一行在某字号倍率下需要的宽度（dp）—— 一律用**估算器**算，别手算。 */
fun maintenanceBaselineNeededDp(label: String, fontScale: Float): Float =
    estimateTextWidthDpWithNbsp(label, BASELINE_ROW_SP * fontScale)
