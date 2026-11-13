package com.teslamate.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ⚠️ **v0.158.0：落盘缓存要存**服务端给的原始 JSON**。**
 *
 * ## 这条规矩是什么
 *
 * 缓存原来存的是「把 [CarStatus] **重新序列化**一遍」的 JSON ⇒
 * **服务端给了、客户端还没声明的字段在写盘那一刻就丢了**
 * （2026-11-14 实测：服务端 /status 给 **75** 个键，App 声明 **72** 个 ⇒ 丢 **5** 个：
 * `cabin` / `locked_source` / `locked_stale` / `sentry_at` / `tpms_source`）。
 *
 * ⇒ 后果：服务器挂掉时，App 交出的「上次已知」**比服务器当时给的少** ——
 * 而缓存存在的理由正是「服务器整个挂掉时还能交出上次已知」。
 *
 * ⇒ 修法：存**原文** ⇒ 缓存与服务端给的东西一样多；
 * 将来 App 声明了那些字段，**离线也读得到**（不用等下一次成功请求）。
 *
 * ## 这条单测钉的是什么
 *
 * 钉的是**行为**，不是写法：
 * 「服务端多给的字段，落在缓存里**必须还在**」——
 * 并且用一个「未来版本的模型」证明它**真的读得回来**。
 */
class StatusCacheCodecTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sample() = CarStatus(
        car_id = 1,
        updated_at = Instant.parse("2026-11-14T00:00:00Z"),
        battery_level = 24,
    )

    @Test
    fun `服务端给了客户端还不认识的字段_落盘内容里必须留着它`() {
        val raw = """{"car_id":1,"updated_at":"2026-11-14T00:00:00Z","battery_level":24,"cabin_stale":true}"""
        val stored = StatusCacheCodec.payload(sample(), raw)
        assertTrue(
            "落盘内容里那个还没声明的字段不见了：$stored",
            stored.contains("cabin_stale"),
        )
    }

    @Test
    fun `将来App声明了那个字段_离线也读得回来`() {
        // 「未来版本」的模型：比现在的 CarStatus 多一个字段。
        @Serializable
        data class FutureStatus(
            val car_id: Int,
            val battery_level: Int? = null,
            /** 现在还没声明的字段（服务端已经给了） */
            val cabin_stale: Boolean? = null,
        )

        val raw = """{"car_id":1,"updated_at":"2026-11-14T00:00:00Z","battery_level":24,"cabin_stale":true}"""
        val stored = StatusCacheCodec.payload(sample(), raw)

        // 用「未来版本」的模型去读落盘内容 ⇒ 那个字段**读得到**。
        val future = json.decodeFromString<FutureStatus>(stored)
        assertEquals(1, future.car_id)
        assertEquals(true, future.cabin_stale)
    }

    @Test
    fun `没有原文时退回模型序列化_行为与从前一致`() {
        val stored = StatusCacheCodec.payload(sample(), null)
        val back = StatusCacheCodec.parse(stored)
        assertNotNull("模型序列化那一份必须还能解回来", back)
        assertEquals(1, back!!.car_id)
        assertEquals(24, back.battery_level)
    }

    @Test
    fun `原文坏掉时宁可不显示_也不要半截数据`() {
        assertNull(StatusCacheCodec.parse("{这不是 JSON"))
    }

    @Test
    fun `原文里的字段值坏了_解不出来就当没有`() {
        // 服务端把 battery_level 改成了非数字 ⇒ 当前 App 解不出来 ⇒ 不显示半截数据。
        val raw = """{"car_id":1,"updated_at":"2026-11-14T00:00:00Z","battery_level":"不是数字"}"""
        assertNull(StatusCacheCodec.parse(raw))
    }

    @Test
    fun `带引号的数字也能解出来_这是kotlinx的行为_别把它当成类型校验`() {
        // ⚠️ **实测发现**（2026-11-14）：kotlinx 的 JSON 解码对 `Int` 字段
        // **接受带引号的数字** ⇒ `"24"` 也能解成 24。
        //
        // ⇒ 推论：**「服务端改了字段类型」不一定解不出来**（数字↔带引号的数字能互通），
        // 真正解不出来的是**值本身坏了**（上面的那条）。
        // ⇒ 写这类断言前要按实测来，别凭直觉。
        val raw = """{"car_id":1,"updated_at":"2026-11-14T00:00:00Z","battery_level":"24"}"""
        assertEquals(24, StatusCacheCodec.parse(raw)?.battery_level)
    }
}
