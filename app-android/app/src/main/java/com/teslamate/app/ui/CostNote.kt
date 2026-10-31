package com.teslamate.app.ui

import com.teslamate.app.fmtThousands

/**
 * ⚠️ **v0.97.0（第 8 轮 CDO Polish）：用量页「费用」两条 ⓘ 口径说明。**
 *
 * ## 为什么把它们抽成纯函数
 *
 * 原来这两条 ⓘ 是 `StatsScreen.kt` 里两段 `buildString { … }`，
 * 拼出来的文字**分别有 380 和 296 视觉宽度**
 * （闸门 5 的「啰嗦棘轮」只管**常驻可见**的长说明块，
 * 折进 ⓘ 之后就看不见了 ⇒ ⓘ 里的口径**没有长度上限**，
 * 这正是上一轮 CHANGELOG 的头号痛点）。
 *
 * 展开 380 宽度的 ⓘ 得到的是**五句话混三个话题**：
 * 电价估算 / 度数构成 / 交流占比 / 家充占比 / 围栏判定 / 设置提示。
 * 点一下 ⓘ 应当得到**一句话结论 + 关键限定**，不是一堵字墙。
 *
 * 抽成纯函数有两个好处：
 * ① **能单测** —— 宽度上限、分支语义都能用 JVM 单测钉住
 *    （界面代码没法单测，闸门只能做正则级的检查）；
 * ② **算法只有一处** —— 口径改了不会出现两个地方各写一遍。
 *
 * ## 收敛了什么（**没有丢任何事实**）
 *
 * | 原来有 | 现在 | 为什么 |
 * |---|---|---|
 * | 「家充（有围栏归属）0 度」 | 零值段**不出现** | v0.14.0 P2-4 的先例：「慢充为 0 时不显示该分段」 |
 * | 「交流慢充占 0%」 | **去掉** | 占比 = 度数 ÷ 总量，度数已经给了 ⇒ 同一个数说两遍 |
 * | 「其中真正在家充的占 0%」 | **去掉** | 同上；且原文的「其中」指交流，与实际口径（占总度数）不符 |
 * | 「家充按 TeslaMate 的充电围栏判定（这段窗口里有 X 度命中围栏）」 | 「家充按充电围栏判定」 | 度数已在构成里说了；「TeslaMate」是工程词 |
 * | 「这段窗口里没有任何一场充电命中充电围栏 —— App 分不出交流慢充是在家还是在酒店/路边」 | 「交流慢充没有围栏归属……分不出在家还是在外面」 | 合并两句话说同一件事 |
 * | 「『家』围栏是 2026-10-25 才建的，之前的充电都没有归属」 | 「（「家」围栏 2026-10-25 才建）」 | 压成括号里的短注，事实一字不减 |
 * | 「（设置里可改）」 | **去掉** | 操作指引，不是口径；设置页自己会说 |
 * | 「分子是窗口内充入的电、分母是窗口内跑的公里，窗口两端还剩多少电的差没有扣」 | 「分子是窗口内充入的电，两端余电没扣」 | 「分母」那句在开头已经说过 ⇒ 同一个数说两遍 |
 * | 「这 12 个月里 App 记录到 Y 公里行驶，而里程表走了 Z 公里 —— 差的 16% 是 TeslaMate 没在记录的遥测断档」 | 「里程表多走 16%，那段路车真开了却没记行程」 | 绝对公里数是派生量；「遥测断档」是工程词 |
 *
 * ⚠️ **一个阈值也没新增**：缺口 ≥1% 才给区间，沿用 `costPerKm()` 既有口径；
 * 围栏归属沿用 `ChargeCost.homeKwh / acOtherKwh`（服务端既有事实源）。
 */

/**
 * 「充电费用」那行的 ⓘ 口径。
 *
 * @param cost 服务端窗口聚合 + 客户端电价算出来的费用构成
 * @param acUsesHomePrice 车主的设置：「没有围栏归属的交流慢充」按哪个价算
 */
fun noteChargeCost(cost: ChargeCost, acUsesHomePrice: Boolean): String {
    // ── 构成：只列**非零**段（v0.14.0 P2-4 先例）──
    val segs = mutableListOf<String>()
    if (cost.homeKwh > 0.01) segs += "家充 ${cost.homeKwh.fmtThousands()} 度"
    if (cost.acOtherKwh > 0.01) segs += "无围栏交流 ${cost.acOtherKwh.fmtThousands()} 度"
    if (cost.dcKwh > 0.01) segs += "直流快充 ${cost.dcKwh.fmtThousands()} 度"

    val sb = StringBuilder("按你填的电价估算")
    if (segs.isEmpty()) {
        // 开了电、但这段窗口没充过电 ⇒ 费用 ¥0.00，得说清楚为什么是 0。
        sb.append("（这段窗口没有充入的电量）")
    } else {
        sb.append("（").append(segs.joinToString(" / ")).append("）")
    }
    sb.append("。")

    // ── 关键限定：只有「围栏归属」会真正影响这个数能不能信 ──
    val tails = mutableListOf<String>()
    if (cost.homeKwh > 0.01) {
        tails += "家充按充电围栏判定"
    }
    if (cost.acOtherKwh > 0.01) {
        val t = StringBuilder("交流慢充没有围栏归属")
        if (cost.homeKwh <= 0.01) {
            // ⚠️ 实测：本机 4 场交流慢充全在 2026-10-02~10-06，
            // 全无围栏归属（「家」围栏 2026-10-25 才建）⇒ 这一句是**实话**。
            t.append("（「家」围栏 2026-10-25 才建）")
        }
        t.append("，分不出在家还是在外面，按「")
        t.append(if (acUsesHomePrice) "家充电价" else "公共桩价")
        t.append("」计")
        tails += t.toString()
    }
    if (tails.isNotEmpty()) sb.append(tails.joinToString("；")).append("。")
    return sb.toString()
}

/**
 * 「每公里成本」那行的 ⓘ 口径。
 *
 * @param drivingKm 分母（App 记录到的行驶里程 = `sum.km`）
 * @param gapPercent 里程表比行驶里程多走的百分比；null 或 <1% 表示不构成缺口
 */
fun noteCostPerKm(drivingKm: Double, gapPercent: Int?): String {
    val sb = StringBuilder("分母是 App 记录到的行驶里程 ${drivingKm.fmtThousands()} 公里。")
    // ⚠️ 缺口 ≥1% 才值得说 —— 与 `costPerKm()` 给区间的口径**同一个阈值**，不新增。
    if (gapPercent != null && gapPercent >= 1) {
        sb.append("里程表多走 ${gapPercent}%，那段路车真开了却没记行程，所以是区间。")
    }
    sb.append("分子是窗口内充入的电，两端余电没扣，所以是近似值。")
    return sb.toString()
}
