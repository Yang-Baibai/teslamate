package com.teslamate.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ⚠️ **v0.149.0**：「上次读到多少、什么时候」那一行（**车外 / 车内温度共用**）。
 *
 * ## 盯住什么
 *
 * 1. **当前读数还在 ⇒ 不显示**（没有「上次」可言 ⇒ 说了反而像在补旧账）；
 * 2. **没有上次读数 ⇒ 不显示**（「上次读到 —」是废话）；
 * 3. 有上次读数 ⇒ 「<标签>上次读到 <值> · <日期>」；
 * 4. **车外 / 车内两处必须是同一个说法** ⇒ 只差一个标签。
 */
class LastReadNoteTest {

    @Test
    fun `当前读数还在时不显示上次`() {
        assertNull(lastReadNote("车外温度", current = 22.5, last = 21.0, atText = "2026-11-09"))
    }

    @Test
    fun `没有上次读数时不显示`() {
        assertNull(lastReadNote("车外温度", current = null, last = null, atText = "2026-11-09"))
    }

    @Test
    fun `有上次读数就说出数值与日期`() {
        assertEquals(
            "车外温度上次读到 22.5°C · 2026-11-09",
            lastReadNote("车外温度", current = null, last = 22.5, atText = "2026-11-09"),
        )
    }

    @Test
    fun `没有时刻就不带日期`() {
        assertEquals(
            "车内温度上次读到 25.8°C",
            lastReadNote("车内温度", current = null, last = 25.8, atText = null),
        )
    }

    @Test
    fun `车外与车内只差一个标签`() {
        assertEquals(
            "车外温度上次读到 22.5°C · 2026-11-09",
            lastReadNote("车外温度", current = null, last = 22.5, atText = "2026-11-09"),
        )
        assertEquals(
            "车内温度上次读到 22.5°C · 2026-11-09",
            lastReadNote("车内温度", current = null, last = 22.5, atText = "2026-11-09"),
        )
    }
}
