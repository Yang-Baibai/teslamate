package com.teslamate.app.ui

import com.teslamate.app.fmt

/**
 * 车况页「今天 / 近 7 天」摘要的**文案与宽度预算**（唯一事实源）。
 *
 * ⚠️ **v0.112.0（本轮手术点）**
 *
 * ## 这一行原来**永远放不下**
 *
 * 原来两段是**并排**的（`Row` + `Spacer(12.dp)` + `Row`）：
 *
 *   「今天 4.4 公里 · 15 分钟」 ＋ 「近 7 天 243.3 公里 · 4 天有行程」
 *
 * | | 宽度（15sp，按 [estimateTextWidthDp] 的口径） |
 * |---|---|
 * | 今天那一段 | ≈ **166dp** |
 * | 近 7 天那一段 | ≈ **226dp** |
 * | 两段 + 12dp 间距 | ≈ **404dp** |
 * | 车况页内容区（360dp 窄屏） | **328dp** |
 * | 车况页内容区（393dp 屏） | **361dp** |
 *
 * ⇒ **连 100% 字号都放不下** ⇒ 后一段必然被折行 / 挤窄
 * ⇒ 「4 天有行程」被劈成「4 天」＋「有行程」（半句话），
 *    200% 字号下那一段要 **460dp+**（屏幕只有 328~361dp）⇒ 更糟。
 *
 * ⚠️ 「刚好放得下」是运气，不是结构 —— 同一个坑本仓库已经踩过两次
 * （v0.94.0 里程表差 2dp、v0.104.0 「最近 30 天」卡差 7dp）。
 *
 * ## 修法：**换布局**，不压文案（与 ADR #39 同一手法）
 *
 * ① 两段**各自独占一行**（`Column`）⇒ 每段都拿到整宽，不再互相挤；
 * ② 每段内部**放不下就拆行** —— 拆开后的每一块都是**完整短语**，
 *    各自独立成 Text 节点 ⇒ 独立段落**没有内部断点**
 *    ⇒ 「4 天有行程」永远不会被劈开（v0.94.0 的结构性结论）；
 * ③ **数与单位之间用不换行空格**（U+00A0，[NBSP]）绑住
 *    ⇒ 折行只落在「标签 | 数值」之间，不会把「4.4」和「公里」拆开。
 *
 * ## 为什么「放不下就拆行」是安全的
 *
 * 判定用的是 [estimateTextWidthDp] —— 它**刻意偏保守**
 * （不换行空格按 1.0em 算，真实约 0.25em；全角标点也按 1.0em）。
 * ⇒ 「估得下」⇒ 一定放得下；「估不下」⇒ 就拆行。
 * ⇒ **两种情况都安全**：不会因为估得准而漏判，也不会因为估得保守而误拆成两行。
 *
 * ⚠️ 文案**逐字不变**（只把内部的普通空格换成不换行空格）
 * ⇒ Hermes（飞书）侧零影响，用户看到的词一个都没变。
 */

/**
 * 车况页内容区在**最窄**的常见屏（360dp）上的可用宽度（dp）。
 *
 * 360dp 屏 − 页面左右内边距 32dp。
 *
 * ⚠️ 取**最窄**而不是本机那台：版面必须对**所有**机型成立
 * （同 `SummaryMetrics.kt` 的 `SUMMARY_CARD_CONTENT_DP` 的取法）。
 *
 * ⚠️ 这一行**不在卡片里**（它直接排在车况页的内容列上）⇒ 只有页面那 32dp 内边距，
 * 不要照抄卡片那张 296dp。
 */
const val USAGE_ROW_CONTENT_DP = 328f

/**
 * 这一行用的字号：**`bodyMedium` = 15sp**（App 自定义排版，`ui/Theme.kt`）。
 *
 * ⚠️ 别拿 M3 的默认值（bodyMedium = 16sp）来算 —— 本 App 覆盖了整套 `Typography`。
 */
const val USAGE_BODY_MEDIUM_SP = 15f

/**
 * 一条摘要：**整句** ＋ 拆行时用的**分段**。
 *
 * @param joined 整句（放得下时整句渲染成**一个** Text 节点 ⇒ 没有内部断点）。
 * @param parts 拆行时的分段 —— **每一项都必须是完整短语**
 *   （独立成 Text 节点 ⇒ 独立段落 ⇒ 只有整体换行，没有内部断点）。
 */
data class UsageLine(
    val joined: String,
    val parts: List<String>,
)

/**
 * 「今天」那一段。
 *
 * ⚠️ 文案与改造前**逐字相同**（只把「数 + 单位」之间的普通空格换成不换行空格）：
 * 「今天 4.4 公里 · 15 分钟」。
 */
fun usageTodayLine(summary: UsageSummary): UsageLine {
    if (!summary.hasToday) {
        val text = "今天还没开车"
        return UsageLine(text, listOf(text))
    }
    val km = "${summary.todayKm.fmt(1)}${NBSP}公里"
    val minutes = "${summary.todayMinutes}${NBSP}分钟"
    return UsageLine(
        joined = "今天 $km · $minutes",
        parts = listOf("今天 $km", minutes),
    )
}

/**
 * 「近 7 天」那一段。
 *
 * ⚠️ 文案与改造前**逐字相同**：「近 7 天 243.3 公里 · 4 天有行程」。
 *
 * ⚠️ 「近 7 天」三个字用不换行空格连在一起 ⇒ 它是**一个**整体，
 * 折行时不会被劈成「近」＋「7 天」。
 */
fun usageWeekLine(summary: UsageSummary): UsageLine {
    if (summary.weekTrips <= 0) {
        val text = "近${NBSP}7${NBSP}天还没有行程"
        return UsageLine(text, listOf(text))
    }
    val km = "${summary.weekKm.fmt(1)}${NBSP}公里"
    val days = "${summary.weekDays}${NBSP}天有行程"
    return UsageLine(
        joined = "近${NBSP}7${NBSP}天 $km · $days",
        parts = listOf("近${NBSP}7${NBSP}天 $km", days),
    )
}

/**
 * 这一行的宽度估算 —— **与 [estimateTextWidthDp] 的唯一区别**：
 * **不换行空格按 0.30em**（普通空格的口径）算，而不是 1.0em。
 *
 * ## 为什么要另起一个
 *
 * 仓库那个估算函数把不换行空格按 **1.0em** 算（`SummaryMetrics.kt` 当时的**刻意**选择：
 * 「宁可估大」⇒ 用来**证明「放不下」**）。
 *
 * 但 U+00A0 **渲染出来就是一个空格** ⇒ 它的真实宽度与普通空格相同（≈0.25em），
 * 而估算函数对**普通空格**用的就是 **0.30em**（同样偏保守 20%）。
 *
 * ⇒ 这里要判的是「**放得下放不下**」⇒ 需要**接近真实**的宽度；
 *   沿用 1.0em 会把「近 7 天 243.3 公里」估成 328.5dp（比 328dp 的预算**多 0.5dp**）
 *   ⇒ 明明放得下却被拆开 ⇒ 白拆一行。
 *
 * ⚠️ 本函数**只影响这一行的拆行判定**，不动共享估算函数
 * （它那个「宁可估大」的取值在别处是**承重**的）。
 */
fun estimateUsageWidthDp(text: String, fontSizeSp: Float): Float {
    var em = 0f
    for (ch in text) {
        em += when {
            ch == ' ' -> 0.30f
            // ⚠️ U+00A0：不换行空格 —— 渲染出来就是个空格（宽度同普通空格）。
            ch.code == 0xA0 -> 0.30f
            ch in '0'..'9' -> 0.62f
            ch in 'a'..'z' || ch in 'A'..'Z' -> 0.66f
            ch.code < 128 -> 0.55f
            else -> 1.00f
        }
    }
    return em * fontSizeSp
}

/**
 * 这一句在给定字号下**要不要拆成两行**。
 *
 * @param fontSizeSp **生效**的字号（= [USAGE_BODY_MEDIUM_SP] × 字体倍率）。
 */
fun usageLineNeedsSplit(line: UsageLine, fontSizeSp: Float): Boolean =
    line.parts.size > 1 &&
        estimateUsageWidthDp(line.joined, fontSizeSp) > USAGE_ROW_CONTENT_DP

/**
 * 拆行后**每一块**需要的宽度（dp）—— 用来证明「拆开之后不会再折行」。
 */
fun usagePartNeededDp(part: String, fontSizeSp: Float): Float =
    estimateUsageWidthDp(part, fontSizeSp)
