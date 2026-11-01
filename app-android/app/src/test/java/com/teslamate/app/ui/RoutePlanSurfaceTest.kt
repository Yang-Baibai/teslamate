package com.teslamate.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.106.0（用户反馈 #26）**：「记录」页顶部的**折叠态**那一张卡。
 *
 * ## 用户原话
 *
 * > 「记录最顶上的这一个小块，**很难理解他想表达什么意思**。需要进一步优化。」
 *
 * 真机截图（v0.104.0）上，表层并排着 **22% / 30% / 20% / 13% / 15%** 五个百分数：
 * 「上次剩 22%」（哪一站？）、「估算剩 30%」、「低于 20% 保底」、
 * 「最险 … 估 13%」、「全程最低 15%」—— 读者分不清
 * **哪个是结论、哪个是证据、哪个是这条线的底线**。
 *
 * ⇒ 本轮把表层**收敛成 4 行**（标题 / 主角 / 结论句 / 告警），
 *   百分数从 5 个减到 3 个；证据（「全程最低」与「最险」细节）折进**展开卡**。
 *
 * ## 这份单测盯住三件事
 *
 * ① **决策句必须带指代锚点**「这一跳到站：」——
 *    不带的话，读者不知道「上次剩 22%」说的是**哪一站**；
 * ② **告警短句**（表层）不许带「最险：…」细节 —— 那是证据；
 * ③ **完整告警**（展开卡）必须仍然带「最险：…」—— 数据一点没丢。
 *
 * ⚠️ 界面层那条「表层不许再出现 `verdictDetail(`（全程最低）」由
 *   `scripts/client-gates.sh` 的**闸门 17** 守住（结构 ⇒ 静态闸门；
 *   文案 ⇒ 运行时单测）。
 */
class RoutePlanSurfaceTest {

    private fun stop(
        name: String,
        km: Double,
        gapKm: Double? = null,
        need: Int? = null,
        required: Int? = null,
        realSoc: Int? = null,
        estSoc: Int? = null,
    ) = RouteStop(
        name = name,
        kmAlongRoute = km,
        socBefore = null,
        socAfter = null,
        typicalPowerKw = null,
        minutesTo80 = null,
        sessions = 1,
        gapToNextKm = gapKm,
        gapNeedPct = need,
        requiredSocHere = required,
        nextStopRealSoc = realSoc,
        socAtNextStop = estSoc,
        nextStopName = null,
    )

    /**
     * 真实数据那条 616 公里线的形状：
     * 最长跳 351 公里（上次剩 13%）、另外两跳**到站**低于 20% 保底。
     */
    private fun plan(): RoutePlan = RoutePlan(
        RouteTemplate(
            key = "j1", fromLabel = "永丰社区", toLabel = "石化大道西",
            distanceKm = 6227.0, startSoc = 100, endSoc = 50,
            legCount = 191, stopCount = 36,
            lastDrivenAt = java.time.Instant.parse("2026-10-07T00:00:00Z"),
            startOdoKm = 100000.0,
        ),
        emptyList(),
        listOf(
            stop("修文县", 4929.0, gapKm = 351.0, need = 88, required = 108, realSoc = 13, estSoc = 8),
            stop("建设南路", 4262.0, gapKm = 328.0, need = 83, required = 103, realSoc = 8, estSoc = 17),
            stop("G210 车河镇", 5280.0, gapKm = 120.0, need = 45, required = 65, realSoc = 30),
        ),
    )

    /** ① 决策句必须说清「这两个数是那一跳的终点余量」。 */
    @Test
    fun `决策句带指代锚点`() {
        val v = plan().verdictFrom(-1)
        assertTrue("决策句必须带「这一跳到站：」这个锚点: $v", v.startsWith("这一跳到站："))
        assertTrue("真值还得在: $v", v.contains("上次剩 13%"))
        assertTrue("估算还得在: $v", v.contains("估算剩 8%"))
    }

    /** 兜底句自带指代，**不该**被加上「这一跳到站：」。 */
    @Test
    fun `兜底句不加指代锚点`() {
        val p = RoutePlan(
            RouteTemplate(
                key = "j1", fromLabel = "A", toLabel = "B",
                distanceKm = 500.0, startSoc = 100, endSoc = 50,
                legCount = 2, stopCount = 1,
                lastDrivenAt = java.time.Instant.parse("2026-10-07T00:00:00Z"),
                startOdoKm = 100000.0,
            ),
            emptyList(),
            listOf(
                stop("A", 0.0, gapKm = 200.0, need = 60, required = 80, realSoc = 20, estSoc = 25),
                // ⚠️ 最后一站：**没有下一站**
                stop("B", 200.0, gapKm = null, need = null, required = null, realSoc = 30, estSoc = null),
            ),
        )
        val v = p.verdictFrom(1)
        assertTrue("应说清是「到最后一站」: $v", v.contains("最后一站"))
        assertFalse("兜底句不该带「这一跳到站：」: $v", v.startsWith("这一跳到站："))
    }

    /** ② 表层短句：只留「N 跳到站低于 20% 保底」，**不带**「最险：…」细节。 */
    @Test
    fun `表层告警是短句 不带最险细节`() {
        val n = plan().belowReserveNoticeFrom(-1, withDetail = false)!!
        assertTrue("必须说清有几跳、低于多少保底: $n", n.contains("另有 2 跳到站低于 20% 保底"))
        assertFalse("表层不许带「最险：」细节（那是证据）: $n", n.contains("最险："))
    }

    /** ③ 展开卡那份完整告警**必须仍然带最险细节** ⇒ 数据一点没丢。 */
    @Test
    fun `展开卡的完整告警仍然带最险细节`() {
        val n = plan().belowReserveNoticeFrom(-1, withDetail = true)!!
        assertTrue("展开卡必须点名最险那一跳: $n", n.contains("最险："))
        assertTrue("最险那跳的估算余量还得在: $n", n.contains("估 8%"))
    }

    /** 两种口径的**区别**只有那半句细节 ⇒ 短句是完整句的真子串（信息不丢）。 */
    @Test
    fun `短句是完整句的前缀`() {
        val p = plan()
        val short = p.belowReserveNoticeFrom(-1, withDetail = false)!!
        val full = p.belowReserveNoticeFrom(-1, withDetail = true)!!
        assertTrue("短句应是完整句打头: $full", full.startsWith(short))
    }
}
