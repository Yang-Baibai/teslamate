package com.teslamate.app.ui

import com.teslamate.app.CommandParamSpec
import com.teslamate.app.VehicleCommand

/**
 * ⚠️ **v0.87.0**：车控参数的**解析结果**（纯逻辑，可单测）。
 *
 * ## 为什么要有这一层
 *
 * 参数弹窗原来**按参数名硬编码**：
 * `needNum = params.filter { it == "driver_temp" || it == "percent" || … }`。
 * ⇒ 来了新参数（座椅加热的座位号、档位），那张表里没有 ⇒
 * **弹窗里一个输入框都没有**，用户点「确定」只会被服务端拒掉。
 *
 * ⇒ 现在改成**照服务端的规格渲染**：服务端给什么控件，App 就渲染什么控件。
 *
 * ## 顺序为什么是「数字 → 口令 → 开关 → 文本」
 *
 * 保持既有版面**逐像素不变** —— 老动作不能因为这次重构变形。
 * （Kotlin 的 `sortedBy` 是**稳定**排序 ⇒ 同类字段之间的相对顺序不变。）
 */
enum class ParamKind { INT, BOOL, PASSWORD, TEXT }

/** 一个取值及其人话标签。 */
data class ParamOption(val value: String, val label: String)

/** 一个待填参数。 */
data class ParamField(
    val key: String,
    val kind: ParamKind,
    /** 输入框 / 开关 / 选择器旁的短标签。 */
    val label: String,
    /** INT 的取值范围。 */
    val min: Int? = null,
    val max: Int? = null,
    /** PASSWORD 的长度范围。 */
    val minLength: Int? = null,
    val maxLength: Int? = null,
    /** INT 的可选值（服务端给了就渲染成「选一个」，不让用户去猜数字）。 */
    val options: List<ParamOption> = emptyList(),
    /** 必填吗？（服务端说了算；没有规格时按必填处理） */
    val required: Boolean = true,
) {
    /** 有可选值 ⇒ 渲染成选择器，不渲染成「填数字」。 */
    val chooser: Boolean get() = kind == ParamKind.INT && options.isNotEmpty()
}

/**
 * 服务端没给规格时的**兜底表**。
 *
 * ⚠️ 只在「服务端是旧版」或「该动作没写规格」时才用。
 * 新版服务端每个带参数的动作都会给规格 ⇒ 这张表仅用于兼容。
 */
private fun legacyParamField(key: String): ParamField? = when (key) {
    "driver_temp" -> ParamField(key, ParamKind.INT, "驾驶位温度 ℃（15~28）", min = 15, max = 28)
    "passenger_temp" -> ParamField(key, ParamKind.INT, "副驾温度 ℃（15~28）", min = 15, max = 28)
    "percent" -> ParamField(key, ParamKind.INT, "充电上限 %（50~100）", min = 50, max = 100)
    "charging_amps" -> ParamField(key, ParamKind.INT, "充电电流 A（5~48）", min = 5, max = 48)
    "password" -> ParamField(key, ParamKind.PASSWORD, "PIN（4~20 位）", minLength = 4, maxLength = 20)
    "on" -> ParamField(key, ParamKind.BOOL, "开启")
    else -> null
}

private fun paramKindOf(spec: CommandParamSpec): ParamKind = when (spec.type) {
    "int" -> ParamKind.INT
    "bool" -> ParamKind.BOOL
    "password" -> ParamKind.PASSWORD
    else -> ParamKind.TEXT
}

/**
 * 把一个动作的参数解析成「要渲染哪些控件」。
 *
 * ⚠️ **服务端规格优先**：服务端给了规格就照它（类型 / 范围 / 标签 / 可选值）；
 * 没给才回落到 [legacyParamField]。
 */
fun resolveParamFields(cmd: VehicleCommand): List<ParamField> {
    val specs = cmd.param_specs.associateBy { it.name }
    val fields = cmd.params.map { key ->
        val spec = specs[key]
        if (spec == null) {
            legacyParamField(key) ?: ParamField(key, ParamKind.TEXT, key)
        } else {
            ParamField(
                key = key,
                kind = paramKindOf(spec),
                label = spec.label.ifBlank { legacyParamField(key)?.label ?: key },
                min = spec.min,
                max = spec.max,
                minLength = spec.min_length,
                maxLength = spec.max_length,
                options = spec.options.map { ParamOption(it.value, it.label) },
                required = spec.required,
            )
        }
    }
    val order = listOf(ParamKind.INT, ParamKind.PASSWORD, ParamKind.BOOL, ParamKind.TEXT)
    return fields.sortedBy { order.indexOf(it.kind) }
}

/** 弹窗的初始值：开关默认「开」，其余留空（让人自己选）。 */
fun initialParamValues(fields: List<ParamField>): Map<String, String> =
    fields.associate { f -> f.key to if (f.kind == ParamKind.BOOL) "true" else "" }

/**
 * 参数齐了吗？
 *
 * ⚠️ **服务端还会再校验一遍**（那是安全边界）；这里只是**体验** ——
 * 别让用户填完、点下「确定」，才被告知填错了。
 */
fun paramFieldsReady(fields: List<ParamField>, values: Map<String, String>): Boolean {
    for (f in fields) {
        val v = values[f.key]
        if (f.kind == ParamKind.BOOL) {
            if (v != "true" && v != "false") return false
            continue
        }
        if (v.isNullOrEmpty()) {
            // 没填：必填 ⇒ 不让过；选填 ⇒ 跳过（服务端会自己兜底）。
            if (f.required) return false
            continue
        }
        when (f.kind) {
            ParamKind.INT -> {
                val n = v.toIntOrNull() ?: return false
                if (f.min != null && n < f.min) return false
                if (f.max != null && n > f.max) return false
                if (f.chooser && f.options.none { it.value == v }) return false
            }
            ParamKind.PASSWORD -> {
                if (f.minLength != null && v.length < f.minLength) return false
                if (f.maxLength != null && v.length > f.maxLength) return false
            }
            // BOOL 在上面已经 continue 掉了；这里只是让 when 穷尽。
            ParamKind.BOOL -> Unit
            ParamKind.TEXT -> Unit
        }
    }
    return true
}

/** 把界面上的值变成发给服务端的参数表。 */
fun paramFieldsToParams(fields: List<ParamField>, values: Map<String, String>): Map<String, Any> {
    val out = mutableMapOf<String, Any>()
    for (f in fields) {
        val v = values[f.key] ?: continue
        when (f.kind) {
            ParamKind.INT -> v.toIntOrNull()?.let { out[f.key] = it }
            ParamKind.BOOL -> out[f.key] = (v == "true")
            else -> out[f.key] = v
        }
    }
    return out
}
