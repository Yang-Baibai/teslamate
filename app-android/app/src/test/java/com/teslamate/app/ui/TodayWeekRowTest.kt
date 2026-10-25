package com.teslamate.app.ui

import com.teslamate.app.Drive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * ⚠️ **v0.47.0（第 28 轮通勤车主评审 P0-1）：车况页「今天 / 本周」这一行。**
 *
 * ## 为什么要有这条测试
 *
 * 这一行是**纯客户端现算**的（`store.drives` 已经在内存里，零新增网络请求），
 * 而**时间窗最容易错** —— 错一次就是「今天」显示成「昨天」，
 * 或者「本周」把上周的行程算进来。
 *
 * ## 盯住什么
 *
 * ① 「今天」按**服务端时区**的自然日切（不是 UTC，也不是手机时区）；
 * ② 「本周」= 自然周（**周一**起），且**包含今天**；
 * ③ **零行驶日**必须给出「今天还没开车」的形态 ——
 *    真实数据 87 天里 **42 天零行驶（48.3%）**，这个分支有一半概率会走到；
 * ④ 本周公里数是「这一周**到现在**」，不是「本周已过的几天」。
 */
class TodayWeekRowTest {

    private val sh = ZoneId.of("Asia/Shanghai")

    private fun drive(id: Int, startIso: String, km: Double, min: Int) = Drive(
        id = id,
        start_date = Instant.parse(startIso),
        end_date = Instant.parse(startIso).plusSeconds(min * 60L),
        duration_min = min,
        distance_km = km,
    )

    // 「现在」固定在 2026-10-25（周日）12:00 +08:00 ⇒ 本周起点 = 10-19（周一）
    private val now = Instant.parse("2026-10-25T04:00:00Z")

    @Test
    fun `今天按服务端时区切自然日`() {
        // 10-25 当天两趟：本地 08:47 与 16:47（UTC 是 00:47 与 08:47）
        val s = computeUsageSummary(
            listOf(
                drive(1, "2026-10-25T00:47:10Z", 4.3258, 14),
                drive(2, "2026-10-25T08:47:00Z", 0.1208, 1),
            ),
            zone = sh, now = now,
        )
        assertEquals("今天的公里数", 4.4466, s.todayKm, 0.001)
        assertEquals("今天的分钟数", 15, s.todayMinutes)
        assertEquals("今天的趟数", 2, s.todayTrips)
        assertTrue(s.hasToday)
    }

    @Test
    fun `本地零点前的那趟仍算今天`() {
        // UTC 2026-10-24T16:30Z = 本地 2026-10-25 00:30 ⇒ 算**今天**
        val s = computeUsageSummary(
            listOf(drive(1, "2026-10-24T16:30:00Z", 3.0, 10)),
            zone = sh, now = now,
        )
        assertEquals("跨 UTC 日界仍应算今天", 3.0, s.todayKm, 0.001)
    }

    @Test
    fun `本地零点后但UTC还没跨日的那趟算昨天`() {
        // UTC 2026-10-24T17:00Z = 本地 2026-10-25 01:00 ⇒ 仍是今天（上面那条）
        // 反过来：UTC 2026-10-24T15:00Z = 本地 2026-10-24 23:00 ⇒ **昨天**
        val s = computeUsageSummary(
            listOf(drive(1, "2026-10-24T15:00:00Z", 3.0, 10)),
            zone = sh, now = now,
        )
        assertEquals("本地 23:00 属于昨天", 0.0, s.todayKm, 0.001)
        assertFalse(s.hasToday)
    }

    @Test
    fun `近 7 天是滚动窗口且包含今天`() {
        // ⚠️ **v0.50.0（第 29 轮对抗性审计 P1-3）**：窗口从「自然周」改成「**滚动 7 天**」。
        // 「现在」= 2026-10-25 ⇒ 窗口 = 10-19 ~ 10-25（今天 + 前 6 天）。
        // 10-19、10-21 各一趟，10-25（今天）两趟；10-18 在窗口外。
        val s = computeUsageSummary(
            listOf(
                drive(1, "2026-10-19T02:00:00Z", 100.0, 60),   // 本地 10-19 10:00
                drive(2, "2026-10-21T02:00:00Z", 100.0, 60),   // 本地 10-21 10:00
                drive(3, "2026-10-25T00:47:10Z", 4.3, 14),     // 今天
                drive(4, "2026-10-18T02:00:00Z", 999.0, 60),   // 10-18 —— 窗口外
            ),
            zone = sh, now = now,
        )
        assertEquals("近 7 天公里数应含今天、且不含 10-18", 204.3, s.weekKm, 0.001)
        assertEquals("近 7 天趟数", 3, s.weekTrips)
        assertEquals("近 7 天有行程的天数（含今天）", 3, s.weekDays)
    }

    @Test
    fun `窗口边界是今天加前六天而不是自然周`() {
        // ⚠️ **第 29 轮审计 P1-3 的核心**：
        // 「本周」（自然周，周一起）与统计页的「7 天」（滚动）**是两个窗口**。
        // 实测 10-19（周一）那天：本周 9.0 公里 vs 7 天 781.3 公里 —— **差 87 倍**。
        //
        // 现在固定「现在」在 10-25（周日）：自然周窗口是 10-19~10-25，
        // 滚动窗口也是 10-19~10-25 ⇒ **恰好重合**（唯一样机日如此），
        // 所以必须换一个「现在」才能测出差异。
        //
        // 把「现在」放在 10-21（周三）：自然周窗口 = 10-19~10-21，
        // 滚动 7 天窗口 = 10-15~10-21 ⇒ **10-15~10-18 的行程**
        // 在滚动窗口里、但**不在**自然周窗口里。
        val later = Instant.parse("2026-10-21T04:00:00Z")
        val s = computeUsageSummary(
            listOf(
                drive(1, "2026-10-16T02:00:00Z", 50.0, 30),   // 10-16
                drive(2, "2026-10-19T02:00:00Z", 70.0, 30),   // 10-19
                drive(3, "2026-10-21T02:00:00Z", 90.0, 30),   // 10-21（当天）
            ),
            zone = sh, now = later,
        )
        // 滚动窗口：10-15 ~ 10-21 ⇒ 三趟全算
        assertEquals("滚动 7 天窗口应含 10-16 与 10-19", 210.0, s.weekKm, 0.001)
        assertEquals("滚动 7 天窗口的天数", 3, s.weekDays)
    }

    @Test
    fun `零行驶日给出今天还没开车`() {
        val s = computeUsageSummary(emptyList(), zone = sh, now = now)
        assertFalse("不该说今天开过车", s.hasToday)
        assertEquals(0.0, s.todayKm, 0.001)
        assertEquals(0, s.todayTrips)
        assertEquals(0, s.weekDays)
        assertEquals("近 7 天也没有行程", 0, s.weekTrips)
    }

    @Test
    fun `只有今天的行程时本周天数是一`() {
        val s = computeUsageSummary(
            listOf(drive(1, "2026-10-25T00:47:10Z", 4.3, 14)),
            zone = sh, now = now,
        )
        assertEquals("近 7 天有行程的天数", 1, s.weekDays)
        assertEquals("近 7 天公里数 = 今天的公里数", 4.3, s.weekKm, 0.001)
    }

    @Test
    fun `缺少里程的行程不参与统计`() {
        val s = computeUsageSummary(
            listOf(
                Drive(id = 1, start_date = Instant.parse("2026-10-25T00:47:10Z"),
                    duration_min = 14, distance_km = null),
            ),
            zone = sh, now = now,
        )
        assertEquals("里程为 null 的行程不该被当成 0 公里算进来", 0, s.todayTrips)
        assertFalse(s.hasToday)
    }
}
