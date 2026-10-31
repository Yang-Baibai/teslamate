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
 */
fun rangeConclusionNeededDp(text: String, fontSizeSp: Float = 12f): Float =
    estimateTextWidthDp(text, fontSizeSp) + INFO_BADGE_ROW_DP
