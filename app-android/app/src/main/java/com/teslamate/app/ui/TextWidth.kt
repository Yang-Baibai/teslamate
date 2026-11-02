package com.teslamate.app.ui

/**
 * **按真实宽度**估算一段中英混排文本的宽度（dp）—— 界面里判「**放得下放不下**」时用它。
 *
 * ⚠️ **v0.112.0**：它与 [estimateTextWidthDp] 的**唯一区别**是
 * **不换行空格（U+00A0）按 0.30em 算**（普通空格的口径），而不是 1.0em。
 *
 * ## 为什么要有两个
 *
 * | 函数 | 不换行空格按 | 用途 |
 * |---|---|---|
 * | [estimateTextWidthDp] | **1.0em**（刻意偏保守） | 用来**证明「放不下」**（估大 ⇒ 安全） |
 * | [estimateTextWidthDpWithNbsp] | **0.30em**（≈真实） | 用来判「**放得下**」 |
 *
 * U+00A0 **渲染出来就是一个空格** ⇒ 它的真实宽度与普通空格相同（≈0.25em），
 * 而 [estimateTextWidthDp] 对**普通空格**用的就是 0.30em（同样偏保守 20%）。
 *
 * ⇒ 为什么不能用后者判「放得下」：用它会把「近 7 天 243.3 公里」估成 **328.5dp**
 *   （比 328dp 的预算**多 0.5dp**）⇒ 明明放得下却被拆开 ⇒ 白拆一行。
 *
 * ⚠️ **共享那个函数没动** —— 它那个「宁可估大」的取值在 `SummaryMetrics.kt` 的断言里是**承重**的。
 *
 * ## 用法
 *
 * 界面里判「这一行放不放得下」：
 * ```kotlin
 * if (estimateTextWidthDpWithNbsp(text, fontSizeSp) > availableDp) { /* 拆行 */ }
 * ```
 *
 * ⚠️ 配套的三条结构性规则见 **ADR #42**（放不下就换行：拆成完整短语，既不截断也不压文案）：
 * 拆开后的每一块必须**独立成 Text 节点**（独立段落 ⇒ 没有内部断点），
 * 且**数与单位之间要用不换行空格绑住**。
 */
fun estimateTextWidthDpWithNbsp(text: String, fontSizeSp: Float): Float {
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
