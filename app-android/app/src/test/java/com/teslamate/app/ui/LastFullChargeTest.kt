package com.teslamate.app.ui

import com.teslamate.app.Charge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * ⚠️ **v0.51.0（第 28 轮通勤车主评审 P1-3）**：充电记录页的「上次充满」。
 *
 * ## 盯住什么
 *
 * ① 「充满」的判据是 **`end_soc >= 100`** ——
 *    半途拔枪的场次**不算**（那只是路过补一点，不是充满）；
 * ② 取的是**最近**的那一场，不是第一场；
 * ③ 一场都没充满过 ⇒ 返回 **null** ⇒ 界面**不给数**，不编。
 */
class LastFullChargeTest {

    private fun charge(
        id: Int,
        startIso: String,
        startSoc: Int?,
        endSoc: Int?,
    ) = Charge(
        id = id,
        start_date = Instant.parse(startIso),
        end_date = Instant.parse(startIso).plusSeconds(1800),
        duration_min = 30,
        energy_added_kwh = 20.0,
        start_soc = startSoc,
        end_soc = endSoc,
    )

    @Test
    fun `半途拔枪的不算充满`() {
        val list = listOf(
            charge(1, "2026-10-01T02:00:00Z", 40, 80),   // 只到 80%
            charge(2, "2026-10-05T02:00:00Z", 55, 90),   // 只到 90%
        )
        assertNull("没有一场充满 ⇒ 不该给数", lastFullCharge(list))
    }

    @Test
    fun `取最近的那一场充满`() {
        val list = listOf(
            charge(1, "2026-10-01T02:00:00Z", 40, 100),
            charge(2, "2026-10-10T02:00:00Z", 68, 100),  // 最近
            charge(3, "2026-10-05T02:00:00Z", 55, 90),
        )
        val last = lastFullCharge(list)
        assertEquals("应取最近的那场充满", 2, last!!.id)
        assertEquals(68, last.start_soc)
        assertEquals(100, last.end_soc)
    }

    @Test
    fun `充满的判据是大于等于一百而不是恰好一百`() {
        // 有些场次会充到 101%（车端电量钳位前）⇒ 不能用 `== 100`
        val list = listOf(charge(1, "2026-10-10T02:00:00Z", 60, 101))
        assertEquals(1, lastFullCharge(list)?.id)
    }

    @Test
    fun `列表为空时返回 null`() {
        assertNull("空列表不该给数", lastFullCharge(emptyList()))
    }
}
