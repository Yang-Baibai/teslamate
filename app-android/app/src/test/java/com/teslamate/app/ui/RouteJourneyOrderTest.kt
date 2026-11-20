package com.teslamate.app.ui

import com.teslamate.app.RoutePlanTemplateDto
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * ⚠️ **v0.181.0：线路顺序由**服务端**给，客户端只透传。**
 *
 * ## 为什么改了这条测试
 *
 * 原来这里测的是 `buildJourneys` / `buildRouteTemplates`（客户端切线路）。
 * 那套判据**已经搬到服务端**（api/route_plans.go）⇒ 客户端那两份函数被删掉。
 * ⇒ 切分与排序的判据测试**移到服务端 Go 单测**（api/route_plans_test.go）。
 *
 * 本文件现在盯的是**客户端还剩下的那部分**：
 * ① 客户端**保持服务端给的顺序**（不许自己再排一遍）；
 * ② `pickRouteTemplate(null)` 落到**第一条**（服务端已按「最近一次」倒序）。
 */
class RouteJourneyOrderTest {

    private fun dto(key: String, drivenAt: String) = RoutePlanTemplateDto(
        key = key,
        from_label = "起点",
        to_label = "终点",
        distance_km = 400.0,
        leg_count = 2,
        stop_count = 0,
        last_driven_at = Instant.parse(drivenAt),
        start_odo_km = 0.0,
        last_distance_km = 400.0,
        last_drive_id = 4,
    )

    /** 服务端给的顺序：最近那次出门在最前。 */
    private val dtos = listOf(
        dto("journey-3-4", "2026-10-17T02:00:00Z"),
        dto("journey-1-2", "2026-08-01T02:00:00Z"),
    )

    @Test
    fun `客户端保持服务端给的顺序`() {
        val templates = routeTemplatesFromServer(dtos, emptyList())
        assertEquals(
            "客户端不许自己重排 —— 必须原样保持服务端的顺序",
            listOf("journey-3-4", "journey-1-2"),
            templates.map { it.key },
        )
    }

    @Test
    fun `没手动选线路时 默认落在最近那条`() {
        val templates = routeTemplatesFromServer(dtos, emptyList())
        val picked = pickRouteTemplate(templates, null)
        assertEquals(
            "默认必须选最近走过的那条（不是最老那条）",
            "journey-3-4",
            picked?.key,
        )
    }

    @Test
    fun `手动选了线路就听用户的`() {
        val templates = routeTemplatesFromServer(dtos, emptyList())
        val picked = pickRouteTemplate(templates, "journey-1-2")
        assertEquals("手动选择必须优先于默认", "journey-1-2", picked?.key)
    }
}
