package com.teslamate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.112.0**：车况页「今天 / 近 7 天」摘要的**文案与宽度预算**。
 *
 * ## 为什么要有它
 *
 * 那一行原来把两段**并排**放着，而两段加起来要 ≈404dp、车况页内容区只有 328~361dp
 * ⇒ **连 100% 字号都放不下** ⇒ 「4 天有行程」被折行劈成半句话。
 *
 * ⇒ 修法是**换布局**（每段独占一行 + 放不下就拆成完整短语），
 * 而这条规则**靠数字成立** ⇒ 必须有运行时断言，不能只写在注释里。
 *
 * ## 七条断言
 *
 * ① **文案逐字不变**（只把内部普通空格换成不换行空格）⇒ Hermes 侧零影响；
 * ② **零行驶**那两种文案也不变；
 * ③ **并排放不下**（否则就不必改布局 ⇒ 这次修复没有依据）；
 * ④ **正常字号不拆行**（拆了就是白拆一行）；
 * ⑤ **两倍字号要拆行**（不拆就又会被折行劈开）；
 * ⑥ **拆开后每一块都放得下**（放不下就说明拆了也救不了）；
 * ⑦ **数与单位之间是不换行空格**（普通空格 ⇒ 200% 下「4.4」和「公里」会被拆开）。
 *
 * ⚠️ 宽度用**真实 dp** 算（`estimateTextWidthDp`），字号取**主题里实际覆盖**的
 * `bodyMedium = 15.sp`（**不是**框架默认的 16sp —— 本 App 覆盖过整套排版）。
 */
class UsageMetricsWidthTest {

    /** `ui/Theme.kt` 里**实际覆盖**的 `bodyMedium`（不是框架默认 16sp）。 */
    private val bodyMediumSp = USAGE_BODY_MEDIUM_SP

    /** 200% 字号（Android 无障碍的最大档）。 */
    private val at200 = bodyMediumSp * 2f

    /** 有行驶的一天（真实数据长这样：今天开了 4.4 公里、15 分钟）。 */
    private val summary = UsageSummary(
        todayKm = 4.4,
        todayMinutes = 15,
        todayTrips = 1,
        weekKm = 243.3,
        weekMinutes = 312,
        weekTrips = 12,
        weekDays = 4,
    )

    @Test
    fun `文案逐字不变 只把空格换成不换行空格`() {
        val today = usageTodayLine(summary).joined.replace(NBSP, " ")
        val week = usageWeekLine(summary).joined.replace(NBSP, " ")
        assertEquals("今天 4.4 公里 · 15 分钟", today)
        assertEquals("近 7 天 243.3 公里 · 4 天有行程", week)
    }

    @Test
    fun `零行驶的文案也不变`() {
        val idle = summary.copy(todayKm = 0.0, todayMinutes = 0, todayTrips = 0)
        assertEquals("今天还没开车", usageTodayLine(idle).joined)
        val noTrips = summary.copy(weekTrips = 0, weekKm = 0.0, weekDays = 0)
        assertEquals("近 7 天还没有行程", usageWeekLine(noTrips).joined.replace(NBSP, " "))
    }

    @Test
    fun `两段并排放不下 这就是这次修复的依据`() {
        val today = usageTodayLine(summary)
        val week = usageWeekLine(summary)
        val sideBySide =
            estimateTextWidthDpWithNbsp(today.joined, bodyMediumSp) +
                estimateTextWidthDpWithNbsp(week.joined, bodyMediumSp)
        assertTrue(
            "两段并排要 ${sideBySide}dp，必须放不下 ${USAGE_ROW_CONTENT_DP}dp —— " +
                "放得下就不必换布局，这次修复就没有依据",
            sideBySide > USAGE_ROW_CONTENT_DP
        )
    }

    @Test
    fun `正常字号不拆行`() {
        assertFalse(usageLineNeedsSplit(usageTodayLine(summary), bodyMediumSp))
        assertFalse(usageLineNeedsSplit(usageWeekLine(summary), bodyMediumSp))
    }

    @Test
    fun `两倍字号要拆行`() {
        assertTrue(usageLineNeedsSplit(usageTodayLine(summary), at200))
        assertTrue(usageLineNeedsSplit(usageWeekLine(summary), at200))
    }

    @Test
    fun `拆开后每一块都放得下`() {
        val lines = listOf(usageTodayLine(summary), usageWeekLine(summary))
        for (line in lines) {
            for (part in line.parts) {
                val need = usagePartNeededDp(part, at200)
                assertTrue(
                    "「${part.replace(NBSP, " ")}」在 200% 下要 ${need}dp，" +
                        "必须放得下 ${USAGE_ROW_CONTENT_DP}dp —— 放不下就说明拆行也救不了",
                    need <= USAGE_ROW_CONTENT_DP
                )
            }
        }
    }

    @Test
    fun `数与单位之间必须是不换行空格`() {
        val today = usageTodayLine(summary)
        val week = usageWeekLine(summary)
        // ⚠️ 普通空格 ⇒ 200% 下贪心折行会把「4.4」和「公里」拆到两行（半个句子）。
        assertTrue(today.joined.contains("4.4${NBSP}公里"))
        assertFalse(today.joined.contains("4.4 公里"))
        assertTrue(today.joined.contains("15${NBSP}分钟"))
        assertTrue(week.joined.contains("243.3${NBSP}公里"))
        assertTrue(week.joined.contains("4${NBSP}天有行程"))
        // ⚠️ 「近 7 天」是一个整体，不能被劈成「近」+「7 天」。
        assertTrue(week.joined.contains("近${NBSP}7${NBSP}天"))
    }

    @Test
    fun `拆开的那一块是完整短语`() {
        // ⚠️ CJK 允许在任意两个汉字之间断行 ⇒ 词组必须独立成节点。
        assertEquals("15${NBSP}分钟", usageTodayLine(summary).parts.last())
        assertEquals("4${NBSP}天有行程", usageWeekLine(summary).parts.last())
    }
}
