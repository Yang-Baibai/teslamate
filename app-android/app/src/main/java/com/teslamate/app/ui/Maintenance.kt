package com.teslamate.app.ui

import com.teslamate.app.AppSettings
import com.teslamate.app.fmtThousands
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ⚠️ **v0.123.0：「快到了」的阈值（公里）。**
 *
 * ⚠️ **正常路径上这个阈值不在这里生效** —— 判定与文案都在服务端
 * （api/maintenance.go 的 maintenanceDueSoonKm，ADR #38）。
 *
 * ⇒ 它只用于**兜底路径**（服务器挂掉、拿不到服务端结论时）。
 * ⚠️ 因此它**必须与服务端那个常量相等** ⇒ Hermes 闸门 13 逐条核对。
 * （两侧各写一份只靠注释维持 ⇒ 迟早漂 —— 这正是闸门 6/7 存在的理由。）
 */
const val MAINTENANCE_DUE_SOON_KM = 2000.0

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
    /**
     * ⚠️ **v0.123.0：服务端的「已超期」判定**（null = 本地兜底路径）。
     *
     * ⚠️ **判定只在服务端**（ADR #38）⇒ 正常路径上这个值来自服务端，
     * 客户端**不再自己**比 ${remainingKm < 0}（那会是第二份判定 ⇒ 迟早漂）。
     */
    val serverOverdue: Boolean? = null,
    /**
     * ⚠️ **v0.123.0：服务端的「快到了」判定**（null = 本地兜底路径）。
     *
     * ⚠️ **2000 公里那个阈值只在服务端有一份**
     * （api/maintenance.go 的 maintenanceDueSoonKm）⇒
     * Hermes 闸门 13 核对两侧一致。
     */
    val serverDueSoon: Boolean? = null,
    /**
     * ⚠️ **v0.123.0：服务端出的这一项文案**（空 = 本地兜底路径）。
     *
     * ⚠️ 服务端有 ⇒ **原样印**（ADR #41：文案只有服务端一份）。
     */
    val serverText: String = "",
    /**
     * ⚠️ **v0.123.0：服务端出的横幅文案**（空 = 本地兜底路径）。
     */
    val serverBannerText: String = "",
) {
    /** 已超期 */
    val overdue: Boolean get() = serverOverdue ?: (remainingKm < 0)

    /** 快到了（2000 km 内）—— 该提醒，但还没超期 */
    val dueSoon: Boolean get() = serverDueSoon ?: (!overdue && remainingKm <= MAINTENANCE_DUE_SOON_KM)

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
 * ⚠️ **v0.123.0：服务端结论 → 界面模型。**
 *
 * ## 为什么要这一层
 *
 * v0.123.0 起，保养 / 轮胎的**判定与文案都在服务端**（api/maintenance.go）——
 * 因为基准原先只在手机本地 ⇒ 飞书根本问不出「我轮胎还能跑多少」
 * （ADR #38 / #52：App 能看到的，Hermes 也要能取）。
 *
 * ⇒ 这一层只做**一件事**：把服务端的结构化结论，翻译成界面要渲染的形状。
 * **不重新判定**（不自己减、不自己比阈值）——
 * 那正是 ADR #38 要消灭的「各算一套」。
 *
 * ## 本地那份是**兜底**，不是第二个事实源
 *
 * [maintenanceReminders] 仍留着：服务器挂掉时（取不到服务端结论）
 * 它用**本地基准 + 车端里程表**算一份，好过整个卡片消失。
 * ⚠️ 但它**只在服务端结论缺失时**才被调用（见 [resolveMaintenanceItems]）——
 * 正常路径上永远以服务端为准 ⇒ 飞书与 App 必然同一个数。
 */
fun resolveMaintenanceItems(
    /** 服务端结论（null = 没取到 / 老服务端没这个端点）。 */
    server: com.teslamate.app.MaintenanceReport?,
    /** 车端里程表（兜底路径用；null ⇒ 不报）。 */
    vehicleOdometerKm: Double?,
    /** 本地基准（兜底路径用）。 */
    settings: AppSettings,
): List<MaintenanceReminder> {
    // ⚠️ 服务端有结论 ⇒ **一律以它为准**（不掺任何本地计算）。
    if (server != null) {
        val out = ArrayList<MaintenanceReminder>(2)
        server.service?.let { out += it.toReminder() }
        server.tire?.let { out += it.toReminder() }
        return out
    }
    // ⚠️ 取不到服务端结论 ⇒ 退回本地兜底（服务器挂掉时至少还能看到提醒）。
    return maintenanceReminders(vehicleOdometerKm, settings)
}

/**
 * 服务端的一项结论 → 界面模型。
 *
 * ⚠️ [text] 直接用服务端的原文（ADR #41：文案只有服务端一份）——
 * 这里**不重新拼句子**，否则同一个事实会有两种说法。
 */
private fun com.teslamate.app.MaintenanceItem.toReminder(): MaintenanceReminder =
    MaintenanceReminder(
        label = label,
        remainingKm = remaining_km,
        dueOdoKm = due_odo_km,
        // ⚠️ 判定标志也用服务端的（overdue / due_soon）——
        // 客户端不再自己比 2000 公里那个阈值。
        serverOverdue = overdue,
        serverDueSoon = due_soon,
        serverText = text,
        serverBannerText = banner_text,
    )

/**
 * 提醒的一句话描述（「还有 3,200 公里」/「已超期 500 公里」）。
 *
 * ⚠️ v0.14.0（新手车主 P0-2）：单位统一成**「公里」**。
 * 原来这里写「km」、车况页横幅写「公里」，同一屏两种单位。
 */
fun MaintenanceReminder.describe(): String {
    // ⚠️ **v0.123.0**：服务端给了原文 ⇒ 原样印（ADR #41：文案只有服务端一份）。
    if (serverText.isNotBlank()) return serverText
    return if (overdue) {
        "已超期 ${abs(remainingKm).roundToInt()} 公里"
    } else {
        "还有 ${remainingKm.roundToInt()} 公里"
    }
}

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
    // ⚠️ **v0.123.0**：服务端给了原文 ⇒ 原样印（ADR #41：文案只有服务端一份）。
    if (serverBannerText.isNotBlank()) return serverBannerText
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
