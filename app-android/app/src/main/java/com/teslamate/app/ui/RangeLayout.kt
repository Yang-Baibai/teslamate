package com.teslamate.app.ui

/**
 * 续航结论区（区间行 / 保底行 / 「怎么算的」）的**宽度预算**。
 *
 * ⚠️ **v0.95.0**：这些常量与估算函数存在的唯一理由，是把
 * 「结论区必须**整宽**」这条**结构约束**变成**可执行的断言**
 * （见 `RangeConclusionWidthTest`），而不是只写在注释里。
 *
 * ## 背景（真机实测）
 *
 * 结论行「约 191 ~ 249 公里 · 88 条实测」原来渲染在电量环右侧的**窄栏**里：
 * - 窄栏可用宽度 ≈ **171dp**（v0.92.0 / v0.94.0 真机实测）；
 * - 结论行文字 ≈ **152dp**，再加 ⓘ 徽标那一行的 **20dp**（16dp 图标 + 4dp 间距）；
 * - ⇒ **放不下** ⇒ 贪心折行把「88 条实测」劈成「88 条实」+「测」。
 *
 * CJK 允许在**任意两个汉字之间**断行 ⇒ 窄栏里**没有任何写法**能保证不被劈开；
 * 而且窄栏宽度**随机型与字号变化**（360dp 屏更窄、放大字号更窄）
 * ⇒ 任何「刚好放得下」的写法都是**运气**，不是结构。
 *
 * ⇒ 结论区只能**整宽**渲染（与 v0.94.0 的里程表同一手法）。
 */

/** 电量环右侧**窄栏**的可用宽度（dp）。真机实测（v0.92.0 / v0.94.0）。 */
const val NARROW_COLUMN_DP = 171f

/** 卡片内容区（**整宽**）的可用宽度（dp）：393dp 屏 − 卡片左右内边距 32dp。 */
const val FULL_WIDTH_DP = 361f

/** ⓘ 徽标那一行的固定开销（dp）：16dp 图标 + 4dp 间距。 */
const val INFO_BADGE_ROW_DP = 20f

/**
 * 结论行用的字号：`labelMedium` = **13sp**（App 自定义排版，见 ui/Theme.kt）。
 *
 * ⚠️ 别拿 M3 的默认值来算 —— 本 App 覆盖了整套 `Typography`。
 * ⚠️ 界面里判「放不放得下」要用**生效**的字号（这个值 × 系统字体倍率）。
 */
const val RANGE_CONCLUSION_SP = 13f

/**
 * 粗估一段中英混排文本的宽度（dp）。
 *
 * ⚠️ 刻意**偏保守**（往大了估）：用途只有一个 —— 证明「放不下」。
 * 真实字形宽度随系统字体而变，估得刚好反而会骗人。
 *
 * 权重：CJK 与全角标点 1em、数字 0.62em、拉丁字母 0.66em、半角标点 0.55em、空格 0.30em。
 */
fun estimateTextWidthDp(text: String, fontSizeSp: Float): Float {
    var em = 0f
    for (ch in text) {
        em += when {
            ch == ' ' -> 0.30f
            ch in '0'..'9' -> 0.62f
            ch in 'a'..'z' || ch in 'A'..'Z' -> 0.66f
            ch.code < 128 -> 0.55f
            else -> 1.00f
        }
    }
    return em * fontSizeSp
}

/**
 * 续航结论行的**文本**（区间 + 来源自曝）。
 *
 * ⚠️ v0.95.0：两个半句都在（**数据一点没丢**），中间用「 · 」隔开。
 * 它必须渲染在**整宽**位置 —— 窄栏里会被折行劈开（见本文件的说明）。
 */
fun rangeConclusionLine(band: String?, scopeLine: String?): String? =
    listOfNotNull(band, scopeLine).takeIf { it.isNotEmpty() }?.joinToString(" · ")

/**
 * 续航结论行需要的宽度（dp）：文字 + ⓘ 徽标那一行的固定开销。
 *
 * ⚠️ 默认 12sp 是**旧值**（结论行当初按 `labelSmall` 估的）；
 * 现在它渲染在 `labelMedium` = **13sp**（见 ui/Theme.kt）⇒
 * 真实字号要由调用方**显式**传进来，别用这个默认值。
 */
fun rangeConclusionNeededDp(text: String, fontSizeSp: Float = 12f): Float =
    estimateTextWidthDp(text, fontSizeSp) + INFO_BADGE_ROW_DP

/**
 * 区间文案：「约 $lo ~ $hi 公里」。
 *
 * ⚠️ **v0.114.0**：数与单位之间用**不换行空格**（U+00A0）绑住
 * ⇒ 折行只可能落在「约 | 191」这种地方，不会把「249」和「公里」拆开
 * （ADR #42 的第三条规则）。
 *
 * ⚠️ 可见文字**逐字不变**（U+00A0 渲染出来就是一个空格）。
 */
fun rangeBandText(lo: Int, hi: Int): String = "约 $lo ~ $hi${NBSP}公里"

/**
 * 来源自曝：「$sampleCount 条实测」。
 *
 * ⚠️ 同上：数与「条」用不换行空格绑住（ADR #42）。
 */
fun rangeScopeText(sampleCount: Int): String = "$sampleCount${NBSP}条实测"

/**
 * 续航结论行的**文本**与**拆分方案**。
 *
 * ⚠️ **v0.114.0（本轮手术点）**：整宽解决了「窄栏放不下」（v0.95.0），
 * 但**大字号下整宽也放不下** —— 200% 时这一行要 ≈400dp（13sp × 2 倍），
 * 而整宽只有 361dp ⇒ 同一个 Text 节点里两个半句被折开
 * ⇒ 「88 条实测」劈成「88 条实」+「测」
 * ⇒ **v0.94.0 那个坑在整宽布局里又回来了**。
 *
 * ⇒ 按 **ADR #42**：放得下就**整句一行**；放不下就**拆成完整短语各占一行**
 * （每块独立成 Text 节点 ⇒ 独立段落 ⇒ **没有内部断点**）。
 */
data class RangeConclusionText(
    /** 放得下时整句渲染（一个 Text 节点）。 */
    val joined: String,
    /** 放不下时各占一行 —— **每一项都必须是完整短语**。 */
    val parts: List<String>,
)

/**
 * 续航结论行：区间 + 来源自曝（两个半句，**数据一点没丢**）。
 */
fun rangeConclusionText(band: String?, scopeLine: String?): RangeConclusionText? {
    val parts = listOfNotNull(band, scopeLine).filter { it.isNotBlank() }
    val joined = rangeConclusionLine(band, scopeLine) ?: return null
    if (parts.isEmpty()) return null
    return RangeConclusionText(joined, parts)
}

/**
 * 这一行在给定字号下**要不要拆成两行**。
 *
 * ⚠️ 判「放得下」要用 [estimateTextWidthDpWithNbsp]（按**真实**宽度）——
 * 保守的那个 [estimateTextWidthDp] 是用来**证明「放不下」**的，
 * 拿它判「放得下」会把明明放得下的行也拆开（见 ui/TextWidth.kt 的说明）。
 *
 * @param fontSizeSp **生效**的字号（= `labelMedium` 的字号 × 字体倍率）。
 */
fun rangeConclusionNeedsSplit(text: RangeConclusionText, fontSizeSp: Float): Boolean =
    text.parts.size > 1 &&
        estimateTextWidthDpWithNbsp(text.joined, fontSizeSp) + INFO_BADGE_ROW_DP > FULL_WIDTH_DP

/**
 * 拆开后**每一块**需要的宽度（dp）—— 用来证明「拆开之后不会再折行」。
 */
fun rangeConclusionPartNeededDp(part: String, fontSizeSp: Float): Float =
    estimateTextWidthDpWithNbsp(part, fontSizeSp) + INFO_BADGE_ROW_DP
