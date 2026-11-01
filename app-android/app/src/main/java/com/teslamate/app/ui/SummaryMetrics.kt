package com.teslamate.app.ui

import com.teslamate.app.fmtKwh100

/**
 * 「最近 30 天」卡那两条指标（平均能耗 / 直流快充占比）的**文案与宽度预算**。
 *
 * ⚠️ **v0.104.0（真机实测的折行缺陷）**
 *
 * ## 出了什么事
 *
 * 那两条指标原来**并排**放在同一个 `Row` 里：
 * 左「平均能耗 17.4 kWh/100km」、右「直流快充 93%（按充入电量）」，
 * 中间一个 `weight(1f)` 的 Spacer。
 *
 * 两条加起来需要约 **353dp**（13sp；按 [estimateTextWidthDp] 算 —— 它把不换行空格
 * 按 **1.0em** 计（真实约 0.25em）⇒ **偏保守**，预算安全），
 * 而卡片内容区在**最窄**的常见屏（360dp）上只有 **296dp**
 * ⇒ **放不下** ⇒ 右边那串被贪心折行劈成「…（按充入电」+「量）」，
 * Spacer 同时被挤成 0 ⇒ 两串**贴死**，读起来像
 * 「平均能耗 17.4 kWh/100km直流快充 93%（…）」。
 *
 * ## 三条结构性结论（别再用「刚好放得下」解决）
 *
 * ① **两条指标不许并排** —— 并排需要 353dp > 296dp；
 *    ⇒ 改成**上下堆叠**，每条独占整宽。
 * ② **口径限定语必须独立成 Text 节点** ——
 *    CJK 允许在**任意两个汉字之间**断行 ⇒ 只要「（按充入电量）」与数字在
 *    **同一个段落**里，它就可能被劈开。
 *    两个节点用 **FlowRow** 排（`docs/adr.md` 第 28 条点名的机制：
 *    **放不下就换行，绝不截断**）：
 *    - 1× 放得下 ⇒ 同一行，视觉与原来一模一样；
 *    - 200% 放不下 ⇒ **限定语整体换到下一行**，两个整块**都不劈开**。
 *
 *    ⚠️ 为什么不能只靠 `Row` + `weight(1f, fill = false)`：
 *    200% 时限定语要 182dp，数字那半只剩 **114dp**，而「直流快充 93%」要 158dp
 *    ⇒ 数字那半自己换行 ⇒ 「直流快充」这个**整词**被劈成「直流」+「快充 93%」
 *    （`docs/adr.md` 第 28 条：短短语不许砍成半截话）。
 * ③ **值与单位之间要绑住** ——
 *    「17.4 kWh/100km」里那个空格是**断行机会**：
 *    200% 时整串要 349dp > 296dp ⇒ 必然折行，
 *    而贪心折行取的是**最后一个放得下的断点**
 *    ⇒ 断在「平均能耗 17.4」/「kWh/100km」上 ⇒ **数与单位分离**（半个句子）。
 *    把那个空格换成 **不换行空格** ⇒ 断点只剩「平均能耗」后面那一个
 *    ⇒ 永远折成「平均能耗」/「17.4 kWh/100km」，值与单位不拆。
 *
 *    ⚠️ 不换行空格**只可以替代本来就有的空格**：
 *    插在**汉字之间**会多出一个空隙（v0.94.0 的教训），
 *    而这里替代的正是那个空格 ⇒ **视觉宽度不变**。
 *
 * ## 这里是唯一事实源
 *
 * 界面（`RecentSummaryCard.kt`）只负责**摆版**，不负责算文案、也不负责判宽度。
 * 宽度预算由 `SummaryMetricsWidthTest`（运行时单测）钉住，
 * 结构不变式由 `scripts/client-gates.sh` 的闸门 16 钉住。
 */

/**
 * 卡片内容区在**最窄**的常见屏（360dp）上的可用宽度（dp）。
 *
 * 360dp 屏 − 页面左右内边距 32dp − 卡片左右内边距 32dp。
 * ⚠️ 取**最窄**而不是本机那台：版面必须对**所有**机型成立，
 * 「刚好在 384dp 屏上放得下」是运气，不是结构。
 */
const val SUMMARY_CARD_CONTENT_DP = 296f

/**
 * 这两行用的字号：**`bodySmall` = 13sp**（App 自定义排版，`ui/Theme.kt`）。
 *
 * ⚠️ 别拿 M3 的默认值（bodySmall = 14sp）来算 —— 本 App 覆盖了整套 `Typography`。
 */
const val SUMMARY_BODY_SMALL_SP = 13f

/**
 * 200% 字号（Android 无障碍的最大档）下的字号（sp）。
 *
 * ⚠️ 仓库规矩：布局问题必须在 **200%** 下也成立（`docs/adr.md` 第 28 条），
 * 而 200% 才是**真正会把整块撑破**的那一档。
 */
const val SUMMARY_BODY_SMALL_200_SP = SUMMARY_BODY_SMALL_SP * 2f

/**
 * **不换行空格**（U+00A0）。
 *
 * ⚠️ 它的唯一用途：把「值」与「单位」绑在一起，
 * 让 200% 字号下的折行只落在**标签**后面，不落在数与单位之间。
 *
 * ⚠️ 用 `String(charArrayOf(...))` 而不是 `"\u00A0"` 字面量 ——
 * 源码里放一个**看不见**的字符，下一个改这段代码的人根本不知道它在哪；
 * 而 `0xA0` 是**看得见**的意图（U+00A0 = 不换行空格）。
 */
val NBSP: String = String(charArrayOf(0xA0.toChar()))

/**
 * 「直流快充占比」的口径限定语 —— **独立成 Text 节点**。
 *
 * ⚠️ 不许与数字拼成同一段（同一段落 ⇒ 会被折行劈开）；
 *   也不许塞进 [dcShareLine] 的返回值里。
 */
const val DC_SHARE_CALIBER = "（按充入电量）"

/** kWh/100km —— 平均能耗的单位（与 [avgEnergyLine] 配套）。 */
const val ENERGY_UNIT = "kWh/100km"

/**
 * 平均能耗那一行。
 *
 * ⚠️ 内部计算仍用 Wh/km，只在**显示层**换算成中国车主的口径 kWh/100km
 * （与 v0.14.0 一致）；能耗模型、续航折算、保底余量全都不受影响。
 *
 * ⚠️ **值与单位之间用不换行空格**（[NBSP]）—— 见本文件的 ③。
 *
 * @return null 表示**没有能耗数据** ⇒ 整块不渲染（不给「—」占位）。
 */
fun avgEnergyLine(whPerKm: Double?): String? =
    whPerKm?.takeIf { it > 0.1 }?.let { "平均能耗 ${it.fmtKwh100()}$NBSP$ENERGY_UNIT" }

/**
 * 直流快充占比那一行（**不含**口径限定语 —— 那是 [DC_SHARE_CALIBER]，独立成段）。
 *
 * @param dcPct 按**充入电量**算的直流占比（不是按次数）。
 */
fun dcShareLine(dcPct: Int): String = "直流快充 $dcPct%"

/** 一行需要的宽度（dp）。口径与 [estimateTextWidthDp] 相同（刻意偏保守）。 */
fun summaryLineNeededDp(text: String, fontSizeSp: Float = SUMMARY_BODY_SMALL_SP): Float =
    estimateTextWidthDp(text, fontSizeSp)

/**
 * 「直流快充」那一**行**（数字 + 口径限定语，同一行渲染）需要的宽度（dp）。
 *
 * ⚠️ 这两个 Text 是**同一行**上的两段：
 * 数字整块 158dp、限定语 182dp（200% 口径）—— 两者都 < 296dp
 * ⇒ **各自都放得下** ⇒ 放得下时同排一行，放不下时**整体换行**
 * ⇒ 两个整块**都不会被劈开**。
 */
fun dcShareRowNeededDp(dcPct: Int, fontSizeSp: Float = SUMMARY_BODY_SMALL_SP): Float =
    summaryLineNeededDp(dcShareLine(dcPct), fontSizeSp) +
        summaryLineNeededDp(DC_SHARE_CALIBER, fontSizeSp)
