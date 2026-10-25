package com.teslamate.app.ui

import com.teslamate.app.Drive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ⚠️ **v0.40.0（第 24 轮客户复审 N1 的**证伪**）：线路默认选的是不是「最近那条」。**
 *
 * ## 为什么有这条测试
 *
 * 第 24 轮客户复审报了 P0「默认线路不是最近那条 —— 而且是最老那条」，
 * 还给了整改规格「`buildRouteTemplates` 返回前加 `.sortedByDescending { it.lastDrivenAt }`」。
 *
 * 但那条复审的证据是**一份 Python 复刻** —— 而**那份复刻自己漏了一行排序**：
 * `RouteJourney.kt:511` 的 `}.sortedByDescending { it.startDate }`。
 * 漏掉之后，journey 列表保持**里程链顺序**（≈时间正序），
 * 于是复刻里 `templates[0]` 恰好是最老那条 —— **复刻的缺陷被当成了 App 的缺陷**。
 *
 * 这类「用复刻当证据」的坑必须用**跑真代码**的测试钉死，
 * 否则下一次改动会照着假结论去改本来正确的代码。
 *
 * ## 盯住什么
 *
 * ① `buildJourneys` 的结果**按开始时刻倒序**（最近的一次出门在最前）；
 * ② `buildRouteTemplates` **保持**这个顺序（它只是 `map`）；
 * ③ `pickRouteTemplate(null)`（没手动选线路时）落到**最近那条**；
 * ④ 整条链路上**没有任何地方**把顺序改回去。
 */
class RouteJourneyOrderTest {

    private fun drive(
        id: Int,
        odoStart: Double,
        odoEnd: Double,
        start: String,
        end: String,
    ) = Drive(
        id = id,
        start_date = Instant.parse(start),
        end_date = Instant.parse(end),
        duration_min = 240,
        distance_km = odoEnd - odoStart,
        start_place = "永丰社区",
        end_place = "石化大道西",
        start_place_key = "g1",
        end_place_key = "g2",
        start_soc = 100,
        end_soc = 60,
        odometer_start = odoStart,
        odometer_end = odoEnd,
    )

    /**
     * 两次「出门」：
     * · 旧的那次 —— 2026-07-31，里程 90000 → 90400（400 公里）
     * · 新的那次 —— 2026-10-16，里程 100000 → 100400（400 公里）
     *
     * 两次之间里程缺口 9600 公里 ≫ `MAX_GAP_KM`(60) ⇒ 必然切成两条独立 journey。
     * 每次出门内部两趟的间隔 12 小时 < `MAX_GAP_HOURS`(36) ⇒ 不会切碎。
     */
    private val drives = listOf(
        drive(1, 90000.0, 90200.0, "2026-07-31T08:00:00Z", "2026-07-31T12:00:00Z"),
        drive(2, 90200.0, 90400.0, "2026-07-31T20:00:00Z", "2026-08-01T02:00:00Z"),
        drive(3, 100000.0, 100200.0, "2026-10-16T08:00:00Z", "2026-10-16T12:00:00Z"),
        drive(4, 100200.0, 100400.0, "2026-10-16T20:00:00Z", "2026-10-17T02:00:00Z"),
    )

    @Test
    fun `两次出门被切成两条 journey`() {
        val journeys = buildJourneys(drives, emptyList())
        assertEquals("应当切成两条 journey", 2, journeys.size)
    }

    @Test
    fun `journey 按开始时刻倒序 —— 最近的那次在最前`() {
        val journeys = buildJourneys(drives, emptyList())
        val dates = journeys.map { it.startDate }
        assertEquals(
            "journey 必须按 startDate 倒序（最近 → 最久）",
            dates.sortedDescending(),
            dates,
        )
        assertEquals(
            "最近的那次出门必须排在第 0 位",
            "journey-3-4",
            journeys.first().key,
        )
    }

    @Test
    fun `buildRouteTemplates 保持倒序 —— 不被重新排序`() {
        val templates = buildRouteTemplates(drives, emptyList())
        assertEquals("应当有两条线路模板", 2, templates.size)
        assertEquals(
            "模板必须保持 journey 的倒序（最近 → 最久）",
            "journey-3-4",
            templates.first().key,
        )
        assertEquals(
            "模板 1 应当是最老那次",
            "journey-1-2",
            templates[1].key,
        )
        assertTrue(
            "lastDrivenAt 也必须倒序",
            (templates[0].lastDrivenAt ?: Instant.EPOCH) >=
                (templates[1].lastDrivenAt ?: Instant.EPOCH),
        )
    }

    @Test
    fun `没手动选线路时 默认落在最近那条`() {
        val templates = buildRouteTemplates(drives, emptyList())
        val picked = pickRouteTemplate(templates, null)
        assertEquals(
            "默认必须选最近走过的那条（不是最老那条）",
            "journey-3-4",
            picked?.key,
        )
    }

    @Test
    fun `手动选了线路就听用户的`() {
        val templates = buildRouteTemplates(drives, emptyList())
        val picked = pickRouteTemplate(templates, "journey-1-2")
        assertEquals("手动选择必须优先于默认", "journey-1-2", picked?.key)
    }
}
