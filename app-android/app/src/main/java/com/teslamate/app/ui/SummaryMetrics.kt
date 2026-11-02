package com.teslamate.app.ui

import com.teslamate.app.StatsBucket
import com.teslamate.app.fmt
import com.teslamate.app.fmtKwh100

/**
 * 「最近 30 天」卡的**文案与宽度预算**（唯一事实源）：
 *   - 上面那 **2×2 指标网格**（行驶里程 / 充入 / 充电次数 / 行驶天数）—— v0.113.0 加；
 *   - 下面那两条指标（平均能耗 / 直流快充占比）。
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

// ─────────────────────────────────────────────────────────────────────────────
// 上面那 **2×2 指标网格**（v0.113.0）
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 「最近 30 天」卡上面那四格指标的**文案**与**宽度预算**。
 *
 * ⚠️ **v0.113.0**：这四格原来各自写在界面里（`RecentSummaryCard.kt`），
 * 而且**列数写死成两列** ⇒ 200% 字号下「3834.6 公里」要 **213dp**，
 * 而两列时每格只有 **148dp**（296dp ÷ 2）⇒ 被折成「3834.6」+「公里」
 * （**数与单位分离** —— ADR #42 的老毛病）。
 *
 * ⇒ 现在：**文案收归这里**（唯一事实源）+ **列数由宽度预算决定**
 * （[summaryMetricColumns]）⇒ 放得下两列、放不下单列
 * （**换布局，不换文案**，ADR #39）。
 */

/** 指标值的字号：**`titleLarge` = 20sp**（`ui/Theme.kt`；⚠️ 别拿 M3 默认值算）。 */
const val SUMMARY_METRIC_VALUE_SP = 20f

/** 指标标签的字号：**`labelSmall` = 12sp**（同上）。 */
const val SUMMARY_METRIC_LABEL_SP = 12f

/**
 * 两列时**每格**的可用宽度（dp）：卡片内容区 ÷ 2（两格之间没有额外间距）。
 *
 * ⚠️ 296 ÷ 2 = **148dp** —— 这就是「3834.6 公里」在 200% 下放不下的那个数。
 */
const val SUMMARY_METRIC_CELL_DP_TWO_COLUMNS = SUMMARY_CARD_CONTENT_DP / 2f

/** 单列时每格的可用宽度（dp）：整宽（296dp）。 */
const val SUMMARY_METRIC_CELL_DP_ONE_COLUMN = SUMMARY_CARD_CONTENT_DP

/** 一格指标：标签（这是什么）+ 值（数字与单位）。 */
data class SummaryMetric(val label: String, val value: String)

/**
 * 「最近 30 天」那四格指标的**文案**。
 *
 * ⚠️ **v0.14.0（单位审校 P1-3）**：「里程」→「行驶里程」（与用量页图表同名，
 * 且「总里程」会被读成「这车一辈子跑了多少」）；保留一位小数 ——
 * 抹成整数那是**假精度**。
 *
 * ⚠️ **v0.47.0（第 28 轮通勤车主评审 P0-3）**：「行程次数 214 次」换成
 * 「**行驶天数 22 天**」（TeslaMate 的「一趟」= 一次连续移动；真实数据里
 * 2026-10-06 跑 **1,002.5 公里只有 10 趟** ⇒ 214 趟 ÷ 22 天 = **9.7 段/天**
 * ⇒ 通勤者读到只会困惑）；长途用户的信息也不丢（趟数在「用量」页仍能看到）。
 *
 * @param driveDays 最近 30 天里有行程的**天数**；为 null 时退回「行程次数」
 *   （**不给数**总比给一个编的数好）。
 */
fun summaryMetrics(s: StatsBucket, driveDays: Int?): List<SummaryMetric> = listOf(
    SummaryMetric("行驶里程", summaryKmValue(s.km)),
    SummaryMetric("充入", summaryKwhValue(s.charge_kwh)),
    SummaryMetric("充电次数", summaryCountValue(s.charge_count, "次")),
    if (driveDays != null) SummaryMetric("行驶天数", summaryCountValue(driveDays, "天"))
    else SummaryMetric("行程次数", summaryCountValue(s.drive_count, "次")),
)

/**
 * 「3834.6 公里」—— **数与单位之间是不换行空格**（U+00A0，[NBSP]）。
 *
 * ⚠️ 普通空格是**断行机会** ⇒ 放不下时贪心折行会把「3834.6」和「公里」拆开
 * （数与单位分离 ⇒ 半个句子，ADR #42）。
 */
fun summaryKmValue(km: Double): String = "${km.fmt(1)}${NBSP}公里"

/** 「674 度」—— 同上：数与单位之间是不换行空格。 */
fun summaryKwhValue(kwh: Double): String = "${kwh.fmt(0)}${NBSP}度"

/** 「31 次」/「22 天」—— 同上：数与单位之间是不换行空格。 */
fun summaryCountValue(count: Int, unit: String): String = "$count${NBSP}$unit"

/**
 * 那四格指标在某字号倍率下用几列。
 *
 * ⚠️ **1 = 单列（每格独占整宽）**，**2 = 两列**。
 *
 * 判定：**最宽的那一格的值**放不进**两列时每格的 148dp** ⇒ 换单列。
 * ⚠️ 这是**宽度驱动**的，不许写死（写死 ⇒ 200% 下又折行，ADR #39 的老教训）。
 */
fun summaryMetricColumns(values: List<String>, fontScale: Float): Int {
    val widest = values.maxOfOrNull { summaryMetricValueNeededDp(it, fontScale) } ?: return 2
    return if (widest <= SUMMARY_METRIC_CELL_DP_TWO_COLUMNS) 2 else 1
}

/**
 * 某个指标值在某字号倍率下需要的宽度（dp）。
 *
 * ⚠️ 用 [estimateTextWidthDpWithNbsp]（不换行空格按 0.30em 算）——
 * 那是**判「放得下」**的那一个（`ui/TextWidth.kt`）；
 * 刻意偏保守的另一个（U+00A0 按 1.0em）是用来**证明「放不下」**的
 * ⇒ 用在这里会**过度触发**（把明明放得下的两列也换成单列 ⇒ 白白多占一屏）。
 */
fun summaryMetricValueNeededDp(value: String, fontScale: Float): Float =
    estimateTextWidthDpWithNbsp(value, SUMMARY_METRIC_VALUE_SP) * fontScale
