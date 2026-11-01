package com.teslamate.app.ui

import com.teslamate.app.VehicleCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.107.0（用户反馈 #25）**：车控按钮**短标签**与**后果说明**的回归测试。
 *
 * ## 用户要的是什么
 *
 * 真机截图（`media/feedback/25.jpg`）：
 *
 * > 「车辆控制这一块的各个按钮**可以不需要括号内的那些东西**。
 * > 把文字的布局尽量统一。」
 *
 * 「车窗通风（开一条缝）」与「关闭全部车窗」并排 ——
 * 一个**两行**、一个**一行** ⇒ 同排按钮的文字布局参差不齐。
 *
 * ## 这三条测试钉住什么
 *
 * | | 钉住 |
 * |---|---|
 * | ① | 按钮**优先**印服务端的 `label`（短标签），不是带括号的 `description` |
 * | ② | 「有后果说明」⇒ **必须**先弹窗（否则那行后果永远没人看得见 ⇒ 等于删了信息） |
 * | ③ | 后果说明进**弹窗**；老服务端没给 `note` 时有**兜底**（唤醒车辆要花钱，必须说） |
 *
 * ⚠️ **老服务端兼容**：`label` / `note` 为空 ⇒ 行为跟今天一模一样
 * （按钮退回 `description`、不弹额外的窗）。
 */
class CommandLabelsTest {

    /** 新服务端：给了短标签与后果说明。 */
    private fun cmd(
        action: String,
        description: String = "",
        label: String = "",
        note: String = "",
        risk: String = "low",
        params: List<String> = emptyList(),
        wakes: Boolean = false,
    ) = VehicleCommand(
        action = action,
        description = description,
        label = label,
        note = note,
        risk = risk,
        params = params,
        wakes_vehicle = wakes,
    )

    // ── ① 按钮上印什么 ──────────────────────────────────────────

    @Test
    fun `按钮优先印短标签而不是带括号的说明`() {
        val c = cmd(
            action = "window_vent",
            description = "车窗通风（开一条缝）",
            label = "车窗通风",
            note = "开一条缝",
        )
        assertEquals("车窗通风", commandButtonLabel(c))
        // ⚠️ 断言**具体值**：只断言「不等于 description」的话，
        // 哪天返回空串也照样绿。
        assertTrue(commandButtonLabel(c).none { it == '（' || it == '）' })
    }

    @Test
    fun `老服务端没有短标签时退回原说明`() {
        val c = cmd(action = "window_vent", description = "车窗通风（开一条缝）")
        assertEquals("车窗通风（开一条缝）", commandButtonLabel(c))
    }

    @Test
    fun `连说明都没有时退回动作名`() {
        val c = cmd(action = "honk_horn")
        assertEquals("honk_horn", commandButtonLabel(c))
    }

    // ── ② 什么时候弹窗 ──────────────────────────────────────────

    @Test
    fun `高危动作要弹窗`() {
        assertTrue(commandNeedsConfirm(cmd("actuate_trunk", risk = "high")))
    }

    @Test
    fun `有参数的动作要弹窗`() {
        assertTrue(commandNeedsConfirm(cmd("set_temperature", params = listOf("driver_temp"))))
    }

    @Test
    fun `有后果说明的动作也要弹窗`() {
        // ⚠️ 这条是 v0.107.0 的**核心**：唤醒车辆（会耗电、可能计费）
        // 以前是「直接执行」—— 因为后果**写在按钮标签里**。
        // 后果搬进弹窗后还直接执行 ⇒ 那行后果就永远没人看得见。
        val wake = cmd(
            action = "wake_up",
            label = "唤醒车辆",
            note = "会耗电、可能计费",
            risk = "medium",
            wakes = true,
        )
        assertTrue(commandNeedsConfirm(wake))
    }

    @Test
    fun `没风险没参数没后果的动作直接执行`() {
        assertFalse(commandNeedsConfirm(cmd("honk_horn", label = "鸣笛")))
        assertFalse(commandNeedsConfirm(cmd("flash_lights", label = "闪灯")))
    }

    @Test
    fun `老服务端的行为不变`() {
        // 没有 label / note ⇒ 判定只看风险与参数 ⇒ 与 v0.106.0 一致。
        assertFalse(commandNeedsConfirm(cmd("honk_horn", description = "鸣笛")))
        assertFalse(commandNeedsConfirm(cmd("wake_up", description = "唤醒车辆（会耗电、可能计费）")))
        assertTrue(commandNeedsConfirm(cmd("actuate_trunk", description = "打开尾门", risk = "high")))
    }

    // ── ③ 后果说明进弹窗 ────────────────────────────────────────

    @Test
    fun `后果说明优先用服务端给的那句`() {
        val c = cmd("wake_up", label = "唤醒车辆", note = "会耗电、可能计费", wakes = true)
        assertEquals("会耗电、可能计费", commandNoteLine(c))
    }

    @Test
    fun `老服务端没给说明时唤醒车辆仍有兜底`() {
        // ⚠️ 唤醒要花钱 —— 这句话**不能**因为服务端没给 note 就消失。
        val c = cmd("wake_up", description = "唤醒车辆（会耗电、可能计费）", wakes = true)
        assertTrue(commandNoteLine(c).contains("计费"))
    }

    @Test
    fun `没有后果的动作就别印那一行`() {
        assertEquals("", commandNoteLine(cmd("honk_horn", label = "鸣笛")))
    }
}
