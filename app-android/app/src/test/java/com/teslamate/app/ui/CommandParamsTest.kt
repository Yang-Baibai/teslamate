package com.teslamate.app.ui

import com.teslamate.app.CommandParamOption
import com.teslamate.app.CommandParamSpec
import com.teslamate.app.VehicleCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️ **v0.87.0**：车控参数解析的回归测试。
 *
 * ## 为什么要有它
 *
 * 参数弹窗原来**按参数名硬编码**（`needNum` / `needPwd` / `needOn`）——
 * 来了新参数它就不认识 ⇒ 弹窗里**一个控件都没有**，
 * 用户点「确定」只会被服务端拒掉。
 *
 * ⇒ 这三条测试把「照服务端规格渲染」钉死：
 *   ① 服务端给了规格 ⇒ 照它渲染（含取值标签）
 *   ② 服务端没给规格 ⇒ 回落到老的那张表（**既有版面不变**）
 *   ③ 控件顺序固定（数字 → 口令 → 开关）—— 老动作的版面不能变形
 */
class CommandParamsTest {

    /** 老服务端 / 老动作：没有 param_specs，只有参数名。 */
    private fun legacyCmd(action: String, params: List<String>) =
        VehicleCommand(action = action, params = params)

    @Test
    fun `没有规格时回落到老的那张表`() {
        val fields = resolveParamFields(
            legacyCmd("set_temperature", listOf("driver_temp", "passenger_temp"))
        )
        assertEquals(2, fields.size)
        assertEquals(ParamKind.INT, fields[0].kind)
        assertEquals("驾驶位温度 ℃（15~28）", fields[0].label)
        assertEquals(15, fields[0].min)
        assertEquals(28, fields[0].max)
        assertEquals("副驾温度 ℃（15~28）", fields[1].label)
    }

    @Test
    fun `老动作的控件顺序不变`() {
        // 代客模式：PIN 输入框在前，「开启」开关在后 —— 与 v0.76.0 之前的版面一致。
        val fields = resolveParamFields(
            legacyCmd("set_valet_mode", listOf("on", "password"))
        )
        assertEquals(listOf("password", "on"), fields.map { it.key })
        assertEquals(ParamKind.PASSWORD, fields[0].kind)
        assertEquals(ParamKind.BOOL, fields[1].kind)
    }

    @Test
    fun `服务端给了规格就照它渲染`() {
        val cmd = VehicleCommand(
            action = "seat_heater",
            params = listOf("seat_position", "level"),
            param_specs = listOf(
                CommandParamSpec(
                    name = "seat_position",
                    type = "int",
                    required = true,
                    min = 0,
                    max = 8,
                    options = listOf(
                        CommandParamOption("0", "主驾"),
                        CommandParamOption("1", "副驾"),
                    ),
                    label = "座位",
                    description = "官方座位序号",
                ),
                CommandParamSpec(
                    name = "level",
                    type = "int",
                    required = true,
                    min = 0,
                    max = 3,
                    options = listOf(
                        CommandParamOption("0", "关"),
                        CommandParamOption("1", "低档"),
                    ),
                    label = "加热档位",
                    description = "官方 Level 枚举",
                ),
            ),
        )
        val fields = resolveParamFields(cmd)
        assertEquals(2, fields.size)
        // 有取值 ⇒ 渲染成选择器，不渲染成「填数字」
        assertTrue(fields[0].chooser)
        assertTrue(fields[1].chooser)
        assertEquals("座位", fields[0].label)
        assertEquals(0, fields[0].min)
        assertEquals(8, fields[0].max)
        assertEquals(listOf("0", "1"), fields[0].options.map { it.value })
        assertEquals("主驾", fields[0].options[0].label)
    }

    @Test
    fun `选填的参数不填也能过`() {
        // 副驾温度在服务端是选填（不填就跟着主驾）⇒ 不能因此卡住「确定」。
        val cmd = VehicleCommand(
            action = "set_temperature",
            params = listOf("driver_temp", "passenger_temp"),
            param_specs = listOf(
                CommandParamSpec("driver_temp", "int", required = true, min = 15, max = 28, label = "驾驶位温度 ℃（15~28）"),
                CommandParamSpec("passenger_temp", "int", required = false, min = 15, max = 28, label = "副驾温度 ℃（15~28）"),
            ),
        )
        val fields = resolveParamFields(cmd)
        // 只填必填的（副驾温度不填）⇒ 服务端会拿主驾的值兜底 ⇒ 应当能过
        assertTrue(paramFieldsReady(fields, mapOf("driver_temp" to "22")))
        // 必填的没填 ⇒ 不能过
        assertFalse(paramFieldsReady(fields, mapOf("passenger_temp" to "23")))
        assertTrue(paramFieldsReady(fields, mapOf("driver_temp" to "22", "passenger_temp" to "23")))
    }

    @Test
    fun `越界的值不能让确定按钮变亮`() {
        val fields = resolveParamFields(legacyCmd("set_charge_limit", listOf("percent")))
        assertFalse(paramFieldsReady(fields, mapOf("percent" to "49")))
        assertFalse(paramFieldsReady(fields, mapOf("percent" to "101")))
        assertFalse(paramFieldsReady(fields, mapOf("percent" to "abc")))
        assertTrue(paramFieldsReady(fields, mapOf("percent" to "80")))
    }

    @Test
    fun `口令长度不对不能让确定按钮变亮`() {
        val fields = resolveParamFields(legacyCmd("remote_start_drive", listOf("password")))
        assertFalse(paramFieldsReady(fields, mapOf("password" to "123")))
        assertFalse(paramFieldsReady(fields, mapOf("password" to "123456789012345678901")))
        assertTrue(paramFieldsReady(fields, mapOf("password" to "1234")))
    }

    @Test
    fun `选择器没选就不能过 选了就能过`() {
        val cmd = VehicleCommand(
            action = "seat_heater",
            params = listOf("seat_position"),
            param_specs = listOf(
                CommandParamSpec(
                    name = "seat_position", type = "int", required = true, min = 0, max = 8,
                    options = listOf(CommandParamOption("0", "主驾")),
                    label = "座位", description = "",
                )
            ),
        )
        val fields = resolveParamFields(cmd)
        val init = initialParamValues(fields)
        assertEquals("", init["seat_position"])
        assertFalse(paramFieldsReady(fields, init))
        assertTrue(paramFieldsReady(fields, mapOf("seat_position" to "0")))
        // 不在可选值里的数字 ⇒ 不能过
        assertFalse(paramFieldsReady(fields, mapOf("seat_position" to "5")))
    }

    @Test
    fun `开关默认是开的`() {
        val fields = resolveParamFields(legacyCmd("set_sentry_mode", listOf("on")))
        val init = initialParamValues(fields)
        assertEquals("true", init["on"])
        assertTrue(paramFieldsReady(fields, init))
        val params = paramFieldsToParams(fields, init)
        assertEquals(true, params["on"])
    }

    @Test
    fun `送出去的类型要对`() {
        val fields = resolveParamFields(legacyCmd("set_temperature", listOf("driver_temp")))
        val params = paramFieldsToParams(fields, mapOf("driver_temp" to "22"))
        assertEquals(22, params["driver_temp"])
        assertTrue(params["driver_temp"] is Int)
    }
}
