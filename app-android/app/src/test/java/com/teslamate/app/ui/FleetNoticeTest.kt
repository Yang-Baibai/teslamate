package com.teslamate.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * ⚠️ **v0.171.0**：车况页那条灰横幅不许再出现**自相矛盾**的话。
 *
 * 屏幕上真的出现过「—— 上面显示的是 **刚刚**读到的**旧值**。」
 * ⇒ 既「刚刚」又「旧」⇒ 根因是**判据与措辞用了两个不同的时钟**：
 *   判据看 @BT@locked_at@BT@（这个值的采集时间），措辞印 @BT@fleet_last_ok@BT@（另一次成功获取的时间）。
 *
 * ⇒ 现在判据与措辞**同源** ⇒ 这条不变量在结构上就成立。
 */
class FleetNoticeTest {

    private val now: Instant = Instant.parse("2026-11-18T02:00:00Z")

    private fun ago(minutes: Long): Instant = now.minus(Duration.ofMinutes(minutes))

    /**
     * ⚠️ **形态无关**的不变量：横幅里**永远不出现**「刚刚」和「旧值」同句。
     *
     * ⇒ 不是「不许出现某个词」，而是「两个互斥的说法不许同时出现」
     * ⇒ 换个措辞照样被抓住。
     */
    @Test
    fun `横幅里不许同时出现刚刚和旧值`() {
        // 值很旧（判据说「旧」），但服务端刚成功拉过一次（措辞会说「刚刚」）
        val t = fleetNoticeText(
            locked = true,
            lockedAt = ago(30),
            fleetError = "",
            fleetLastOk = now,
            fleetState = "ok",
            now = now,
        )
        assertNotNull("这个组合应该有横幅", t)
        // 值确实旧（30 分钟）⇒ 「旧值」是**该说的**；
        // 不许的是「刚刚」与它**同句**（⇒ 两句话不许互相打架）。
        assertTrue("该说「旧值」：$t", t!!.contains("旧值"))
        assertFalse("既「刚刚」又「旧值」：$t", t.contains("刚刚"))
    }

    @Test
    fun `值新鲜时不提旧`() {
        val t = fleetNoticeText(
            locked = true,
            lockedAt = ago(1),
            fleetError = "",
            fleetLastOk = ago(1),
            fleetState = "ok",
            now = now,
        )
        assertTrue("新鲜时不该有横幅：$t", t.isNullOrBlank())
    }

    @Test
    fun `值旧时说的是它自己的采集时间`() {
        val t = fleetNoticeText(
            locked = true,
            lockedAt = ago(30),
            fleetError = "",
            fleetLastOk = now,
            fleetState = "ok",
            now = now,
        )
        assertNotNull(t)
        assertTrue("该说「30 分钟前」：$t", t!!.contains("30 分钟前"))
        assertTrue("该说「旧值」：$t", t.contains("旧值"))
        assertTrue("要有指代锚点（说清是顶部那行的锁车状态）：$t", t.contains("顶部那行的锁车状态"))
    }

    @Test
    fun `采集时间读不到就整句不说`() {
        val t = fleetNoticeText(
            locked = true,
            lockedAt = null,
            fleetError = "",
            fleetLastOk = now,
            fleetState = "ok",
            now = now,
        )
        assertTrue("不知道采集时间就不该说「旧值」：$t", t.isNullOrBlank())
    }

    @Test
    fun `没读到锁车状态时说清楚是哪种情况`() {
        val disabled = fleetNoticeText(null, null, "", null, "disabled", now)
        assertTrue("要说是「没连实时状态源」：$disabled", disabled!!.contains("没连实时状态源"))

        val asleep = fleetNoticeText(null, null, "", null, "asleep", now)
        assertTrue("要说是「等它醒来」：$asleep", asleep!!.contains("实时胎压要等它醒来"))

        val other = fleetNoticeText(null, null, "", null, "whatever", now)
        assertTrue("要说是「没读到」：$other", other!!.contains("没读到车机的锁车状态"))
    }

    @Test
    fun `没读到锁车状态时才说最后一次成功获取`() {
        val unknown = fleetNoticeText(null, null, "", ago(3), "whatever", now)
        assertTrue("要说最后一次成功获取：$unknown", unknown!!.contains("最后一次成功获取：3 分钟前"))

        val known = fleetNoticeText(true, ago(30), "", ago(3), "ok", now)
        assertFalse("读到锁车状态时不该说「最后一次成功获取」：$known", known!!.contains("最后一次成功获取"))
    }
}
