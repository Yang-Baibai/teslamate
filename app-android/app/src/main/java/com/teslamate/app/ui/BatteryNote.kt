package com.teslamate.app.ui

import com.teslamate.app.fmt

/**
 * ⚠️ **v0.99.0（第 9 轮 CDO Polish）：电池健康卡的 ⓘ 口径说明（纯函数）。**
 *
 * ## 为什么把它们从 `StatsScreen.kt` 里搬出来
 *
 * v0.97.0 给 ⓘ 立了长度上限（`NOTE_WIDTH_LIMIT = 160` 视觉宽度），
 * 但闸门 13 只能扫**调用点字面量**（`note = buildString {…}`）——
 * 折进提供器函数的 ⓘ 静态扫不到 ⇒ 那一层由**运行时单测**兜底
 * （`NoteBudgetTest.kt` 定的分工：调用点归静态闸门，提供器归单测）。
 *
 * 这三条 ⓘ 里**插值的内容比字面量还多**：
 * 「满电续航」那条要塞**日期**和**公里数**、「电池健康度」那条要塞**百分比**——
 * 这些字符在静态扫描里**看不见**（模板占位只按 1 个字符算）。
 * ⇒ 静态扫描在这三条上**先天偏松**，只有运行时单测才量得到真宽度。
 * ⇒ 于是它们搬进纯函数，由 `BatteryNoteTest.kt` 钉住。
 *
 * ## 这一轮压掉的是什么
 *
 * **不是信息，是「同一个数说两遍」**：
 *
 * - ② 的「（2026-08-02）」与行标题「近期趋势（自 2026-08-02）」重复 ⇒ 删；
 * - ② 的「只回答「比那次低了多少」」与行本身重复 ⇒ 删；
 * - ③ 的「新电池应是 538 km」与下面那行「满电续航（按出厂折算）538 km」重复 ⇒ 删；
 * - ③ 的「车机满电时**自己**报的读数」⇒ 删「自己」（那是强调，不是口径）；
 * - ④ 的第 2、3 段是**服务端 `caveat` 的副本** ⇒ 只留与结论直接相关的一句，
 *   口径原文仍由服务端的 `caveat` 在同卡「怎么算的」里**完整**呈现。
 *
 * ⚠️ ④ 那一条原本是**客户端自己维护的第二份口径副本** ——
 * v0.41.0 的注释早就写过「口径提示与服务端判定**必须是同一份**，否则必然漂移」，
 * 而 `note()` 恰恰就是那份副本。删掉它不是丢信息，是**去掉一个漂移源**。
 */

/**
 * ① 「电池健康度（相对出厂）」的口径。
 *
 * @param strictMax 历史最好时的容量（严格档：只取充到 100% 的点）
 * @param nominalKwh 新电池标称的可用容量
 */
fun noteBatteryHealth(strictMax: Double?, nominalKwh: Double?): String = buildString {
    append("「电池健康度」= 现在还能装的度 ÷ 新电池标称的度（同一口径）—— ")
    append("就是相对出厂衰减了多少。")
    // ⚠️ v0.44.0（第 24 轮对抗性审计 ⑪）：分子依赖一个**用户可改的常数**
    // （`cars.efficiency`）—— 不说清楚，读者会以为它是「测出来的」。
    append("分子里的效率常数服务端可改，改了就变。")
    // ⚠️ v0.63.0（R31 视觉复审 P0-3）：它是「天花板」，不是第二个健康度。
    if (nominalKwh != null && strictMax != null) {
        append("最好的一次也只到出厂的 ")
        append((strictMax * 100 / nominalKwh).fmt(1))
        append("%。")
    }
}

/**
 * ② 「近期趋势」的口径。
 *
 * ⚠️ **行标题已经写着「自 2026-08-02」** ⇒ ⓘ 里不再重复那个日期
 * （同一个数说两遍 = 噪音，v0.96.0 先例）。
 *
 * ⚠️ 「只回答『比那次低了多少』」也删了 —— 行标题的「自 …」和数值本身
 * 已经把这件事说完了，再写一遍是同一句话的第二遍。
 */
fun noteRecentTrend(): String =
    "当前容量 ÷ 首测容量（前 3 次中位数），不受能耗常数影响。" +
        "它只算这段时间的变化，和「相对出厂」不是同一段 —— 后者看上面的健康度。" +
        "首测不是新车容量。"

/**
 * ③ 「满电续航（现在）」的口径。
 *
 * @param dateText 最近一次满充的日期（可空 ⇒ 不提）
 * @param gapKm 与「满电续航（按出厂折算）」那一行**显示值之差**
 */
fun noteFullChargeRange(dateText: String?, gapKm: Int?): String = buildString {
    append("车机满电时报的读数")
    if (!dateText.isNullOrBlank()) {
        append("（").append(dateText).append(" 那次）")
    }
    append("。")
    if (gapKm != null) {
        append("比出厂折算少约 ").append(gapKm).append(" km；")
    }
    // ⚠️ 满充读数本身随温度浮动 ⇒ 这个差是**区间**，不是精确值。
    append("读数有 445~455 km 温度波动，差是区间不是精确值。")
    append("官方标称续航是新车认证值，不能跟这把尺子相除。")
}
/**
 * ⚠️ **v0.125.0**：电池健康卡的**副标题**（纯函数）。
 *
 * ## 为什么它必须带「哪一次充电测的」
 *
 * 「现在还能装 X 度」这个数来自**最近一次充到 90% 以上**的那场充电
 * （服务端 sqlBattery 的最后一个有容量的点），**不是此刻的读数**。
 * ⇒ 只印「现在还能装」＝把**旧值当现在讲**（项目红线第 2 条）。
 *
 * ## 为什么 `noteShort` 是参数而不是这里算的
 *
 * 「10-31 实测」「数据偏旧」这些措辞由**服务端**生成（ADR #41：文案只有一份），
 * 新鲜期阈值也只在服务端一处 ⇒ App **只透传**，不许自己格式化日期。
 *
 * @param capacityKwh 最近一次充电实测的可用容量（度）；null ⇒ 不提
 * @param nominalKwh 新电池标称容量（度）；null ⇒ 不提
 * @param noteShort 服务端给的测量口径短文案（可空 ⇒ 不提）
 */
fun batteryCapacitySubtitle(capacityKwh: Double?, nominalKwh: Double?, noteShort: String?): String =
    buildString {
        if (capacityKwh != null) {
            append("现在还能装 ").append(capacityKwh.fmt(1)).append(" 度")
        }
        if (nominalKwh != null) {
            if (isNotEmpty()) append(" · ")
            append("新电池标称 ").append(nominalKwh.fmt(1)).append(" 度")
        }
        // ⚠️ 时间诚实性：把「哪一次充电测的」接在末尾。
        if (!noteShort.isNullOrBlank()) {
            if (isNotEmpty()) append(" · ")
            append(noteShort)
        }
    }

