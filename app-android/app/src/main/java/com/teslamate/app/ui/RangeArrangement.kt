package com.teslamate.app.ui

import com.teslamate.app.fmt

/**
 * 首屏「电量环 + 续航主角」的**排布规则**（纯逻辑，可单测）——
 * **放得下就并排，放不下就上下叠**。
 *
 * ⚠️ **v0.120.0（本轮手术点）**：这里原来没有规则，只有一个**拍死的字号阈值**
 * （写在 `StatusScreen.kt` 里：字号到某个数就切竖排）⇒ 同 ADR #43 / #44 的老教训：
 * **拍死的阈值迟早过期**。这一轮把它换成**宽度驱动**。
 *
 * ## 为什么那个阈值是错的（不是「不精确」，是**方向错了**）
 *
 * 阈值是个**代理量**：它假装「字号大 ⇒ 放不下」。但真正决定放不放得下的是**宽度**：
 *
 * - 窄栏宽度 = 卡片内容区 − 环直径 − 间距（**随机型与字号变化**）；
 * - 窄栏要装的是**主角数字 + 它的标签**（两个都得装下）；
 * - 环直径本身是**字号反推**的：`socRingDiameterDp` = max(140, 28 × fs × 3.4)。
 *
 * ⇒ 旧阈值的代价（这一轮量出来的，见 `RangeArrangementTest`）：
 *   393dp 屏的内容区 361dp − 环 140dp − 间距 28dp = 窄栏 **193dp**；
 *   而窄栏要装的是「204」@44sp = **81.84dp** 与「现在能开（公里）」@13sp = **104.00dp**
 *   （口径：`estimateTextWidthDpWithNbsp`，**乐观口径** ⇒ 约等于真实宽度）
 *   ⇒ **绑定项是标签**（104.00dp），**不是**数字（81.84dp）；
 *   ⇒ fs=1.35 时窄栏余量 **52.6dp**、fs=1.6 时余量 **14.3dp**（判定要求 ≥ 4dp 安全垫）⇒ **都放得下**；
 *   ⇒ 旧阈值却已经切了竖排 ⇒ **白丢「环 + 主角并排」的首屏构图**
 *   （主角独占一行、环被挤到下面 ⇒ 首屏纵向多占一屏）。
 *
 * ⚠️ **旧推导里的数字是手算的**：当时的注释写着「主角「204」在 44sp 下宽 **73.9sp**」，
 *   而 App 自己的估算器给的是 **81.84dp**（1.86em × 44sp）⇒ 差 8dp，
 *   而且**没有任何单测钉住** ⇒ 它随时会悄悄过期。
 *
 * ## 现在的规则
 *
 * - **宽度是运行时量出来的**（`BoxWithConstraints`）⇒ 每台机器按自己的实际宽度判断
 *   （360dp 窄屏 / 393dp / 412dp / 折叠屏各按各的）；
 * - **主角数字与标签都要放得下**（同 `infoTileColumns` 的规矩：标签也要）；
 * - **留 4dp 余量**（[HERO_FIT_SLACK_DP]）：估算器是**近似**（数字 0.62em、全角 1.0em），
 *   真实字形宽度随系统字体而变 ⇒ 别在刀尖上走；
 * - 放不下 ⇒ **竖排**（主角独占整行 ⇒ 绝不会被裁）—— 与 v0.9.0 起的竖排分支**同一份版面**。
 *
 * ⚠️ **排布几乎不会来回跳**：绑定项是**标签**（「现在能开（公里）」），
 *   它在数据更新时**不变** ⇒ 不会因为续航数字变了就翻来覆去。
 *
 * ⚠️ 本文件只判**排版**（并排 / 上下），不判任何**数值口径** ——
 *   数值口径在服务端（ADR #38 / #47 / #48）⇒ 不违反「判定只在服务端」那条红线。
 */

/** 主角数字的字号：**44sp**（`BigStat(hero = true)`，见 ui/Widgets.kt）。 */
const val HERO_VALUE_SP = 44f

/** 主角标签的字号：**labelMedium = 13sp**（见 ui/Theme.kt；⚠️ 别拿 M3 默认值算）。 */
const val HERO_LABEL_SP = 13f

/** 环与主角之间的间距（dp）：与界面里那个 `Spacer` 同一个数。 */
const val HERO_RING_GAP_DP = 28f

/**
 * 放得下的**余量**（dp）。
 *
 * ⚠️ 估算器是**近似**：数字按 0.62em、全角按 1.0em 估，真实字形宽度随系统字体而变
 * ⇒ 判「放得下」时留一点余量（4dp ≈ 13sp 下 0.3 个汉字）。
 */
const val HERO_FIT_SLACK_DP = 4f

/**
 * 电量环的**最小直径**（dp）—— 与 `SocRing(baseSizeDp = …)` **同一个数**。
 *
 * ⚠️ 宽度预算里算的环宽必须等于**画出来的**环宽 ⇒ 客户端闸门 27 会核对这两个数。
 */
const val SOC_RING_MIN_DP = 140

/** 首屏排布：环与主角**并排**，还是上下**叠**。 */
internal enum class HeroArrangement {
    /** 并排：环在左，主角在右的窄栏里。 */
    SIDE_BY_SIDE,

    /** 上下叠：主角独占整行，环在下面。 */
    STACKED,
}

/**
 * 主角的**数字**（唯一事实源：排布判定与渲染都从这里取 ⇒ 两边不会漂）。
 *
 * ⚠️ 数据不足时是「—」⇒ 宽度很窄 ⇒ 那时并排更稳（不会裁）。
 */
internal fun rangeHeroValue(model: RangeModel): String =
    (model.trueRange ?: model.ratedRange)?.fmt(0) ?: "—"

/**
 * 主角的**标签**（唯一事实源，同 [rangeHeroValue]）。
 *
 * ⚠️ v0.9.0：不用「真实可达 (km)」这种术语 ⇒ 「现在能开（公里）」。
 * ⚠️ v0.26.0：低温样本不足退回标称时要说清为什么。
 */
internal fun rangeHeroLabel(model: RangeModel): String = when {
    model.trueRange != null -> "现在能开（公里）"
    model.tempCaveat != null -> "官方标称（公里）· 低温未修正"
    else -> "官方标称（公里）"
}

/** 主角数字在某字号倍率下需要的宽度（dp）。 */
internal fun heroValueNeededDp(text: String, fontScale: Float): Float =
    estimateTextWidthDpWithNbsp(text, HERO_VALUE_SP * fontScale)

/** 主角标签在某字号倍率下需要的宽度（dp）。 */
internal fun heroLabelNeededDp(text: String, fontScale: Float): Float =
    estimateTextWidthDpWithNbsp(text, HERO_LABEL_SP * fontScale)

/**
 * 首屏排布：**放得下就并排，放不下就上下叠**。
 *
 * @param contentDp 卡片内容区的可用宽度（dp；运行时量出来）
 * @param ringDp 电量环直径（dp；= [socRingDiameterDp]）
 * @param heroText 主角数字
 * @param heroLabel 主角标签
 * @param fontScale 当前字号倍率
 */
internal fun heroArrangement(
    contentDp: Float,
    ringDp: Float,
    heroText: String,
    heroLabel: String,
    fontScale: Float,
): HeroArrangement {
    val columnDp = contentDp - ringDp - HERO_RING_GAP_DP
    val neededDp = maxOf(
        heroValueNeededDp(heroText, fontScale),
        heroLabelNeededDp(heroLabel, fontScale),
    )
    return if (neededDp + HERO_FIT_SLACK_DP <= columnDp) {
        HeroArrangement.SIDE_BY_SIDE
    } else {
        HeroArrangement.STACKED
    }
}
