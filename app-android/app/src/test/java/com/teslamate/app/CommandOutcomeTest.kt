package com.teslamate.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.teslamate.app.CommandResult
import com.teslamate.app.ui.commandOutcome

/**
 * ⚠️ **v0.76.0**：命令结果的三态文案。
 *
 * ## 盯住什么
 *
 * 车端会**明确拒绝**命令（`result:false`，比如 `already_charging` /
 * `not_charging` / `disconnected`）。
 * 原来 App 只有一句「已下发：{动作}」—— 只要 HTTP 2xx 就这么写。
 *
 * ⇒ 车**没照做**，App 却说搞定了 = **假成功**。
 * 用户会以为车没听指挥，**然后再点一次**
 * （而「开始充电」点两次是有副作用的）。
 *
 * ⇒ 官方响应形状（developer.tesla.cn → Fleet API → Vehicle Commands）：
 * `{"response": {"result": true|false, "reason": "..."}}`
 *
 * ⇒ 三态必须分清：
 * - `executed` ⇒ 车真的做了（「已锁车」）
 * - `rejected` ⇒ 车明确拒绝（「车端拒绝：充电枪没插」），**是坏消息**
 * - `dispatched` ⇒ 发出去了但没拿到确认（「已下发，等车端确认」）
 */
class CommandOutcomeTest {

    @Test
    fun `车端已执行 —— 说的是现在的样子`() {
        val r = CommandResult(
            action = "door_lock",
            description = "锁车",
            done = "已锁车",
            sent = true,
            executed = true,
            state = "executed",
        )
        val o = commandOutcome(r)
        assertEquals("已锁车", o.text)
        assertFalse("成功不是坏消息", o.bad)
    }

    @Test
    fun `车端拒绝 —— 必须是坏消息且带上原因`() {
        val r = CommandResult(
            action = "charge_start",
            description = "开始充电",
            sent = true,
            executed = false,
            state = "rejected",
            reason = "already_charging",
            reason_text = "车端拒绝（already_charging）",
        )
        val o = commandOutcome(r)
        assertEquals("车端拒绝：车端拒绝（already_charging）", o.text)
        assertTrue("拒绝是坏消息", o.bad)
    }

    @Test
    fun `车端拒绝但没有原因文案 —— 也不能报成功`() {
        val r = CommandResult(
            action = "charge_start",
            description = "开始充电",
            sent = true,
            state = "rejected",
            reason = "disconnected",
        )
        val o = commandOutcome(r)
        assertEquals("车端拒绝：disconnected", o.text)
        assertTrue(o.bad)
    }

    @Test
    fun `已下发但没确认 —— 不能说成已完成`() {
        val r = CommandResult(
            action = "flash_lights",
            description = "闪灯",
            done = "已闪灯",
            sent = true,
            state = "dispatched",
        )
        val o = commandOutcome(r)
        assertEquals("已下发，等车端确认", o.text)
        assertFalse(o.bad)
    }

    @Test
    fun `没发出去 —— 是坏消息`() {
        val r = CommandResult(
            action = "door_lock",
            description = "锁车",
            sent = false,
            state = "failed",
            error = "车在休眠或离线 —— 先唤醒再试",
        )
        val o = commandOutcome(r)
        assertEquals("车在休眠或离线 —— 先唤醒再试", o.text)
        assertTrue(o.bad)
    }

    @Test
    fun `老服务端没有 state 字段 —— 退回老口径且不能崩`() {
        val r = CommandResult(
            action = "flash_lights",
            description = "闪灯",
            sent = true,
        )
        val o = commandOutcome(r)
        assertEquals("已下发：闪灯", o.text)
        assertFalse(o.bad)
    }

    @Test
    fun `老服务端报错误 —— 仍然要显示错误`() {
        val r = CommandResult(
            action = "door_lock",
            description = "锁车",
            sent = false,
            error = "命令没发出去",
        )
        val o = commandOutcome(r)
        assertEquals("命令没发出去", o.text)
        assertTrue(o.bad)
    }

    @Test
    fun `服务端没给 done 时用清单里的兜底`() {
        val r = CommandResult(
            action = "door_lock",
            description = "锁车",
            sent = true,
            executed = true,
            state = "executed",
        )
        val o = commandOutcome(r, fallbackDone = "已锁车")
        assertEquals("已锁车", o.text)
    }
}
