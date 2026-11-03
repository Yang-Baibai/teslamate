package com.teslamate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **v0.120.0**：首屏「电量环 + 续航主角」的**排布规则**。
 *
 * ## 盯住什么
 *
 * 原来这里拍了一个**字号阈值** ⇒ 同一个字号下，**窄屏先竖排、宽屏后竖排**（甚至一样早）
 * ⇒ 白丢「环 + 主角并排」的首屏构图。现在由**宽度预算**决定 ⇒
 * **同一字号下，屏越宽越晚竖排**（这才是物理事实）。
 *
 * ## 钉死的数（口径：`estimateTextWidthDpWithNbsp`，即**乐观口径**）
 *
 * | 元素 | 宽度 |
 * |---|---|
 * | 「204」@44sp | **81.84dp** |
 * | 「现在能开（公里）」@13sp | **104.00dp** |
 *
 * ⇒ **绑定项是标签**（104.00dp），不是数字（81.84dp）。
 */
class RangeArrangementTest {

    /** 360dp 窄屏：卡片内容区 328dp（360 − 32dp 页面内边距）。 */
    private val narrowContentDp = 328f

    /** 393dp 屏：卡片内容区 361dp。 */
    private val typicalContentDp = 361f

    /** 412dp 屏：卡片内容区 380dp。 */
    private val wideContentDp = 380f

    /** 主角数字（真实可达 171 之类 ⇒ 三位）。 */
    private val heroText = "204"

    /** 主角标签（数据不变 ⇒ 排布也不变）。 */
    private val heroLabel = "现在能开（公里）"

    private fun arrange(contentDp: Float, fontScale: Float): HeroArrangement =
        heroArrangement(
            contentDp = contentDp,
            ringDp = socRingDiameterDp(SOC_RING_MIN_DP, fontScale),
            heroText = heroText,
            heroLabel = heroLabel,
            fontScale = fontScale,
        )

    /**
     * 正常字号下**并排** —— 视觉与改之前一模一样。
     */
    @Test
    fun `正常字号下并排`() {
        assertEquals(
            "100%% 下窄屏（328dp）也放得下「204」+「现在能开（公里）」⇒ 必须并排",
            HeroArrangement.SIDE_BY_SIDE,
            arrange(narrowContentDp, 1.0f),
        )
        assertEquals(
            "100%% 下 393dp 屏必须并排",
            HeroArrangement.SIDE_BY_SIDE,
            arrange(typicalContentDp, 1.0f),
        )
    }

    /**
     * ⚠️ **v0.120.0 的真洞**：旧阈值在 **1.35** 就切竖排，
     * 而 393dp 屏的窄栏有 **193dp**，要装的是 81.84dp 与 104.00dp
     * ⇒ **余量 52.6dp** ⇒ 旧阈值**白丢**了「环 + 主角并排」的构图。
     */
    @Test
    fun `135字号下393屏不必竖排`() {
        assertEquals(
            "135%% 下 393dp 屏：窄栏 193dp ≫ 标签 140.40dp ⇒ 不该竖排（旧阈值在这里就竖排了）",
            HeroArrangement.SIDE_BY_SIDE,
            arrange(typicalContentDp, 1.35f),
        )
    }

    /** 160% 下 393dp 屏余量只剩 10.3dp ⇒ **还是并排**（旧阈值早就竖排了）。 */
    @Test
    fun `160字号下393屏仍并排`() {
        assertEquals(
            "160%% 下 393dp 屏：窄栏 180.68dp ≥ 标签 166.40dp + 余量 ⇒ 还是并排",
            HeroArrangement.SIDE_BY_SIDE,
            arrange(typicalContentDp, 1.6f),
        )
    }

    /** 170% 下 393dp 屏**必须竖排**（窄栏 171.16dp < 标签 176.80dp + 余量）。 */
    @Test
    fun `170字号下393屏必须竖排`() {
        assertEquals(
            "170%% 下 393dp 屏：窄栏 171.16dp < 标签 176.80dp ⇒ 必须竖排",
            HeroArrangement.STACKED,
            arrange(typicalContentDp, 1.7f),
        )
    }

    /**
     * ⚠️ **同一字号下，屏越宽越晚竖排** —— 这才是物理事实。
     *
     * 旧阈值**完全不看屏宽** ⇒ 393dp 与 412dp 在同一个字号上一起竖排
     * ⇒ 宽屏白等了一场。
     */
    @Test
    fun `同一字号下宽屏比窄屏晚竖排`() {
        assertEquals(
            "170%% 下 393dp 屏必须竖排",
            HeroArrangement.STACKED,
            arrange(typicalContentDp, 1.7f),
        )
        assertEquals(
            "170%% 下 412dp 屏：窄栏 190.16dp ≥ 标签 176.80dp + 余量 ⇒ 不该竖排",
            HeroArrangement.SIDE_BY_SIDE,
            arrange(wideContentDp, 1.7f),
        )
    }

    /** 200% 下窄屏**必须竖排** —— 竖排时主角独占整行 ⇒ 绝不会被裁。 */
    @Test
    fun `200字号下窄屏必须竖排`() {
        assertEquals(
            "200%% 下 360dp 屏：环 190.40dp ⇒ 窄栏只剩 109.60dp ⇒ 必须竖排",
            HeroArrangement.STACKED,
            arrange(narrowContentDp, 2.0f),
        )
        assertEquals(
            "200%% 下 393dp 屏也必须竖排（窄栏 142.60dp < 标签 208.00dp）",
            HeroArrangement.STACKED,
            arrange(typicalContentDp, 2.0f),
        )
    }

    /**
     * **单调性**：字号越大 ⇒ 越可能竖排 ⇒ 一旦竖排就**不该再变回并排**。
     *
     * ⚠️ 这条能抓住「环宽忘了随字号长」这类改坏（环不变 ⇒ 窄栏不变宽）。
     */
    @Test
    fun `竖排之后不再变回并排`() {
        val scales = listOf(1.0f, 1.15f, 1.3f, 1.45f, 1.6f, 1.75f, 1.9f, 2.0f)
        listOf(narrowContentDp, typicalContentDp, wideContentDp).forEach { content ->
            var seenStacked = false
            scales.forEach { fs ->
                val a = arrange(content, fs)
                if (a == HeroArrangement.STACKED) seenStacked = true
                assertTrue(
                    "%.0fdp 屏在 fs=%.2f 处竖排之后又变回并排 ⇒ 排布不再单调".format(content, fs),
                    !seenStacked || a == HeroArrangement.STACKED,
                )
            }
        }
    }

    /**
     * **宽度数字钉死**（口径：乐观 `estimateTextWidthDpWithNbsp`）。
     *
     * ⚠️ 这两个数会写进 CHANGELOG ⇒ 客户端闸门 26 要求它们与
     *   `WidthBudgetPinTest` 里的断言**是同一个数**。
     */
    @Test
    fun `主角与标签的宽度钉死`() {
        assertEquals("「204」@44sp（乐观口径）", 81.84f, heroValueNeededDp(heroText, 1.0f), 0.01f)
        assertEquals("「现在能开（公里）」@13sp（乐观口径）", 104.00f, heroLabelNeededDp(heroLabel, 1.0f), 0.01f)
        // 保守口径（NBSP 按 1.0em）对这两个串**同值**（都没有不换行空格）
        // ⇒ 这里说「哪个口径」很重要，但这两个数不因口径而变。
        assertEquals("「204」@44sp（保守口径同值）", 81.84f, estimateTextWidthDp(heroText, 44f), 0.01f)
        assertEquals("「现在能开（公里）」@13sp（保守口径同值）", 104.00f, estimateTextWidthDp(heroLabel, 13f), 0.01f)
    }

    /**
     * **文案逐字不变** —— 排布换了，文案一个字都不能动。
     */
    @Test
    fun `文案逐字不变`() {
        val model = RangeModel(
            ratedRange = 430.0,
            refSpeed = 80.0,
            speedAssumed = false,
            trueRange = 204.0,
            band = null,
            floorKm = null,
            floorBand = null,
            scopeLine = null,
            scopeNote = null,
            tempCaveat = null,
            explainText = "",
        )
        assertEquals("204", rangeHeroValue(model))
        assertEquals("现在能开（公里）", rangeHeroLabel(model))
        // 退回标称（低温未修正）时标签要说明白
        val cold = model.copy(trueRange = null, tempCaveat = "≤10°C 只有 1 条样本")
        assertEquals("430", rangeHeroValue(cold))
        assertEquals("官方标称（公里）· 低温未修正", rangeHeroLabel(cold))
        // 读不到数 ⇒ 「—」（不是「0」）
        assertEquals("—", rangeHeroValue(model.copy(trueRange = null, ratedRange = null)))
    }
}
