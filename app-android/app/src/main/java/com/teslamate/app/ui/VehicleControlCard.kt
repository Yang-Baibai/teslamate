package com.teslamate.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.teslamate.app.Api
import com.teslamate.app.CommandCatalog
import com.teslamate.app.VehicleCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ⚠️ **v0.71.0（苹果设计总监复审）：车辆控制卡 —— 重做交互与信息架构。**
 *
 * 车主诉求：「车辆的控制功能可以集成在车况页面中，交互、设计动画等等请参考苹果的风格。」
 *
 * ## 这次改了什么（以及为什么）
 *
 * | 原设计 | 问题 | 现在 |
 * |---|---|---|
 * | 锁车 / 解锁 **两个平级按钮** | 两个平级按钮把「此刻该点哪个」的判断甩给用户 | **状态驱动的单一 CTA** —— 已锁时主按钮是「解锁车门」，未锁时是「锁车」；状态未知才两个并列 |
 * | 分区按**风险档**（常用 / 调节 / 高危动作） | 用户想的是「我要开空调」，不是「这动作属于哪一档」 | 按**功能**分区：空调与温度 / 充电 / 其他；**高危动作仍单列**（安全底线） |
 * | **所有**动作都弹确认窗 | 连「闪灯」都要点两次 —— Apple 从不为可逆动作打断用户 | **确认分级**：高危 → 确认；有参数 → 参数弹窗（弹窗本身即确认）；其余 → **直接执行** |
 * | 「车已经锁好了，需要的话可以再锁一次。」 | 纯废话（按钮已经写明了） | 删 |
 * | 「没读到锁车状态…」+「仍可手动锁 / 解锁。」两行 | 两行说一件事 | 一行短句，解释折进 ⓘ |
 * | 结果是一行常驻小字 | 旧结果永远赖着不走 | **自动消失**的结果条（3 秒） |
 *
 * ## 保持不变的底线
 *
 * - **高危动作必须确认**：开合尾门、远程启动、解锁车门 —— 不可逆，有物理后果；
 * - **锁车状态未知时不能说「已锁」也不能说「未锁」** —— 那是没读到，不是车没锁；
 * - **动作清单由服务端下发**（`GET /api/v1/cars/{id}/commands`），
 *   客户端**不新增动作**、也不自己判断高危 —— 那是安全策略，只该有一处；
 * - **车在休眠时先说清楚**：命令发不出去（Tesla 回 408），这不是失败，是「车没醒」。
 */
@Composable
fun VehicleControlCard(
    api: Api?,
    carId: Int,
    /** 锁车状态；null = 未知（车没醒或没连实时状态源） */
    locked: Boolean?,
    /** 车是否在休眠 */
    carAsleep: Boolean,
    modifier: Modifier = Modifier,
) {
    var catalog by remember { mutableStateOf<CommandCatalog?>(null) }
    var loadFailed by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<VehicleCommand?>(null) }
    var running by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf<String?>(null) }
    var resultBad by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(api, carId) {
        if (api == null) return@LaunchedEffect
        catalog = withContext(Dispatchers.IO) {
            runCatching { api.commands(carId) }.getOrNull()
        }
        if (catalog == null) {
            loadFailed = "读不到车控能力清单"
        }
    }

    // ⚠️ v0.71.0：结果条**自动消失** —— 旧结果赖在卡片里不走，
    // 用户看到的是「半分钟前那次操作的结果」，而不是刚才那次。
    LaunchedEffect(resultMsg) {
        if (resultMsg != null) {
            delay(3000)
            resultMsg = null
        }
    }

    fun execute(cmd: VehicleCommand, params: Map<String, Any>) {
        if (api == null) return
        running = true
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching { api.sendCommand(carId, cmd.action, params) }
            }
            running = false
            pending = null
            res.onSuccess { r ->
                resultBad = !r.sent
                resultMsg = when {
                    r.sent -> "已下发：${cmd.description}"
                    !r.error.isNullOrBlank() -> r.error
                    else -> "命令没发出去"
                }
            }.onFailure { e ->
                resultBad = true
                resultMsg = e.message ?: "命令没发出去"
            }
        }
    }

    /**
     * ⚠️ **v0.71.0：确认分级** —— Apple 的原则是「只在不可逆时打断」。
     *
     * - **高危**（解锁车门 / 开合尾门 / 远程启动）→ 确认弹窗；
     * - **需要参数**（温度 / 充电上限 / 电流 / PIN / 开关）→ 参数弹窗，
     *   弹窗本身就是一次确认，不再叠第二层；
     * - **其余**（锁车、空调开关、充电开始/停止、鸣笛、闪灯…）→ **直接执行**。
     *
     * 唤醒车辆也是「直接执行」—— 它的描述里已经写着「（会耗电、可能计费）」，
     * **后果写在标签里就不必再打断一次**。
     */
    fun request(cmd: VehicleCommand) {
        when {
            cmd.risk == "high" || cmd.params.isNotEmpty() -> pending = cmd
            else -> execute(cmd, emptyMap())
        }
    }

    SectionCard(title = "车辆控制", modifier = modifier) {
        val c = catalog
        when {
            c == null && loadFailed != null -> {
                Text(
                    loadFailed!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            c == null -> {
                Text(
                    "正在读车控能力…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            !c.proxy -> {
                // 车控代理没起来 —— 这是**部署状态**，用户要知道原因。
                Text(
                    "车控还没启用（服务器上的 command-proxy 没跑）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InfoNote(
                    text = "车控走 Tesla 官方的 vehicle-command sidecar 做签名命令" +
                        "（2021 年后的 Model 3 必须用它注册在 partner account 上的私钥签名）。" +
                        "在电脑项目目录执行：docker compose --profile control up -d command-proxy。" +
                        "没起它的时候，这一整块功能不可用，其余功能不受影响。",
                    label = "怎么启用？",
                    a11yLabel = "怎么启用车辆控制",
                )
            }
            else -> {
                // ── 车在休眠：命令发不出去，先说清楚 ──
                if (carAsleep) {
                    Text(
                        "车在休眠 —— 命令要等它醒着才发得出去",
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalAccentColors.current.warnAmber,
                    )
                    Spacer(Modifier.height(10.dp))
                }

                // ── 主角：锁车状态 ──
                val lockCmd = c.actions.firstOrNull { it.action == "door_lock" }
                val unlockCmd = c.actions.firstOrNull { it.action == "door_unlock" }
                if (lockCmd != null && unlockCmd != null) {
                    LockHeroTile(
                        locked = locked,
                        busy = running && (pending?.action == "door_lock" || pending?.action == "door_unlock"),
                        onLock = { request(lockCmd) },
                        onUnlock = { request(unlockCmd) },
                    )
                    Spacer(Modifier.height(14.dp))
                }

                // ── 功能分区（用户想的是「我要开空调」，不是「这动作算哪一档」）──
                val rest = c.actions.filter {
                    it.action != "door_lock" && it.action != "door_unlock" && it.risk != "high"
                }
                fun groupOf(name: String, actions: List<String>): List<VehicleCommand> =
                    rest.filter { it.action in actions }
                        .sortedBy { actions.indexOf(it.action) }

                val climate = groupOf("空调与温度", listOf("set_climate_on", "set_climate_off", "set_temperature"))
                val charging = groupOf("充电", listOf("charge_start", "charge_stop", "set_charge_limit", "set_charging_amps"))
                val known = climate + charging
                val others = rest.filter { it !in known }

                if (climate.isNotEmpty()) {
                    ActionSection("空调与温度", climate, running, pending?.action, ::request)
                }
                if (charging.isNotEmpty()) {
                    ActionSection("充电", charging, running, pending?.action, ::request)
                }
                if (others.isNotEmpty()) {
                    ActionSection("其他", others, running, pending?.action, ::request)
                }

                // ── 高危动作：单独一区，安全底线不因为「简洁」而让步 ──
                val danger = c.actions.filter { it.risk == "high" && it.action != "door_unlock" }
                if (danger.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    ActionSection("高危动作", danger, running, pending?.action, ::request, danger = true)
                }

                // ── 结果条（自动消失）──
                AnimatedVisibility(
                    visible = resultMsg != null,
                    enter = fadeIn(tween(Motion.ExpandFadeIn)),
                    exit = fadeOut(tween(Motion.ExpandFadeOut)),
                ) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            resultMsg.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (resultBad) LocalAccentColors.current.warnAmber
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    // ── 确认 / 参数弹窗 ──
    pending?.let { cmd ->
        CommandConfirmDialog(
            cmd = cmd,
            running = running,
            onDismiss = { if (!running) pending = null },
            onConfirm = { params -> execute(cmd, params) },
        )
    }
}

/**
 * 一个分区：标题 + 两列动作块。
 *
 * ⚠️ **v0.71.0**：标题是**功能名**（「空调与温度」）而不是风险档（「调节」）——
 * 用户想的是「我要开空调」，风险档是实现细节。
 */
@Composable
private fun ActionSection(
    title: String,
    actions: List<VehicleCommand>,
    running: Boolean,
    runningAction: String?,
    onPick: (VehicleCommand) -> Unit,
    danger: Boolean = false,
) {
    Text(
        title,
        style = MaterialTheme.typography.labelSmall,
        color = if (danger) LocalAccentColors.current.warnAmber
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    ActionGrid(actions, running, runningAction, danger, onPick)
    Spacer(Modifier.height(12.dp))
}

/**
 * 主角块：车辆锁定状态 + **状态驱动的单一主按钮**。
 *
 * ⚠️ **v0.71.0（苹果设计总监复审）**：原来是「锁车 / 解锁两个平级按钮」——
 * 那把「此刻该点哪个」的判断甩给了用户。
 *
 * 现在：
 * - **已锁** → 主按钮是「**解锁车门**」（琥珀色 —— 解锁是高危动作）；
 * - **未锁** → 主按钮是「**锁车**」（品牌主色）；
 * - **状态未知** → 两个按钮并列（都不能撒谎说状态，所以不偏袒任何一个）。
 *
 * ⚠️ 锁车状态**未知**时（null）不能说「已锁」也不能说「未锁」——
 * 那是没读到，不是车没锁（车况页一直守这条）。
 */
@Composable
private fun LockHeroTile(
    locked: Boolean?,
    busy: Boolean,
    onLock: () -> Unit,
    onUnlock: () -> Unit,
) {
    val accents = LocalAccentColors.current
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    // ⚠️ 未知状态用**空锁**图标 + 灰色 —— 不暗示「未锁」。
                    // （Material 里没有「未知锁」图标，硬凑一个问号反而更费解。）
                    imageVector = if (locked == true) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = null,
                    tint = when (locked) {
                        true -> accents.energyBlue
                        false -> MaterialTheme.colorScheme.onSurface
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        when (locked) {
                            true -> "已锁"
                            false -> "未锁"
                            null -> "锁车状态未知"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // ⚠️ **v0.71.0**：删掉「车已经锁好了，需要的话可以再锁一次。」
                    // 那是一句**废话** —— 按钮上已经写着动作名了。
                    Text(
                        when (locked) {
                            true -> "车辆已锁定"
                            false -> "点下面锁车"
                            null -> "没读到锁车状态"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (locked == null) {
                // ⚠️ v0.71.0：解释**折进 ⓘ**，不占常驻版面。
                InfoNote(
                    text = "没读到锁车状态 —— 车在休眠或没连实时状态源。" +
                        "这不代表车没锁；下面仍然可以手动锁 / 解锁。",
                    label = "为什么读不到？",
                    a11yLabel = "为什么读不到锁车状态",
                )
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(12.dp))
            when (locked) {
                // 已锁 → 突出「解锁」（高危 ⇒ 琥珀）
                true -> HeroButton(
                    label = "解锁车门",
                    onClick = onUnlock,
                    enabled = !busy,
                    container = accents.warnAmber,
                    content = Color.Black,
                )
                // 未锁 → 突出「锁车」
                false -> HeroButton(
                    label = "锁车",
                    onClick = onLock,
                    enabled = !busy,
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                )
                // 未知 → 两个并列，谁也不冒充主角
                null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(
                        label = "锁车",
                        onClick = onLock,
                        enabled = !busy,
                        primary = false,
                        modifier = Modifier.weight(1f),
                    )
                    ActionButton(
                        label = "解锁",
                        onClick = onUnlock,
                        enabled = !busy,
                        primary = false,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * 主角按钮 —— 满宽、填充、48dp 高。
 *
 * iOS 的主 CTA 特征：满宽 + 圆角 + 填充色 + 明显的文字对比。
 */
@Composable
private fun HeroButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    container: Color,
    content: Color,
) {
    Surface(
        color = container,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .semantics { contentDescription = label }
            .pressable(onClick = onClick, enabled = enabled, scaleDown = 0.97f),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = content,
                maxLines = 1,
            )
        }
    }
}

/**
 * 动作网格（2 列）。
 *
 * @param danger 高危区：琥珀色描边 + 「高危」小标（不靠颜色单独承担语义）。
 */
@Composable
private fun ActionGrid(
    actions: List<VehicleCommand>,
    running: Boolean,
    runningAction: String?,
    danger: Boolean = false,
    onPick: (VehicleCommand) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { cmd ->
                    ActionButton(
                        label = cmd.description.ifBlank { cmd.action },
                        onClick = { onPick(cmd) },
                        enabled = !running,
                        primary = false,
                        danger = danger,
                        busy = running && runningAction == cmd.action,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 一个动作按钮。 */
@Composable
private fun ActionButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    primary: Boolean,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    busy: Boolean = false,
) {
    val accents = LocalAccentColors.current
    val container = if (primary) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val content = if (primary) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurface
    Surface(
        color = container,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .semantics { contentDescription = label }
            .pressable(onClick = onClick, enabled = enabled, scaleDown = 0.96f),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = if (primary) MaterialTheme.colorScheme.onPrimary else accents.energyBlue,
                )
            } else {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = content,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 确认 / 参数弹窗。
 *
 * ⚠️ 确认按钮写**具体动作**（「确认解锁」），不写「确定」——
 * 车控不可逆，用户要知道自己刚点了什么。
 */
@Composable
private fun CommandConfirmDialog(
    cmd: VehicleCommand,
    running: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Map<String, Any>) -> Unit,
) {
    val accents = LocalAccentColors.current
    // 参数：数字 / 密码 / 开关
    var numValues = remember { mutableStateOf(mapOf<String, String>()) }
    var password = remember { mutableStateOf("") }
    var onOff = remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }

    val needNum = cmd.params.filter { it == "driver_temp" || it == "passenger_temp" || it == "percent" || it == "charging_amps" }
    val needPwd = cmd.params.contains("password")
    val needOn = cmd.params.contains("on")
    val isHigh = cmd.risk == "high"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            // ⚠️ **v0.71.0**：高危动作的标题带「确认」，纯参数动作直接用动作名
            // —— 参数弹窗**本身就是**一次确认，再叠一层「确认确认」是多余的打断。
            Text(if (isHigh) "确认${cmd.description}" else cmd.description)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isHigh) {
                    Text(
                        "这是「高危动作」，执行后不能自动撤销。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = accents.warnAmber,
                    )
                }
                if (cmd.wakes_vehicle) {
                    Text(
                        "这会「唤醒车辆」—— 会耗一点电，也可能被 Tesla 计费。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = accents.warnAmber,
                    )
                }
                if (!isHigh && cmd.description.isNotBlank()) {
                    Text(
                        cmd.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                needNum.forEach { key ->
                    OutlinedTextField(
                        value = numValues.value[key] ?: "",
                        onValueChange = { v ->
                            numValues.value = numValues.value + (key to v.filter { it.isDigit() })
                        },
                        label = { Text(paramLabel(key)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                if (needPwd) {
                    OutlinedTextField(
                        value = password.value,
                        onValueChange = { password.value = it },
                        label = { Text("PIN（4~20 位）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                if (needOn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("开启", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        Switch(
                            checked = onOff.value["on"] ?: true,
                            onCheckedChange = { v -> onOff.value = onOff.value + ("on" to v) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !running && paramsReady(cmd, needNum, numValues.value, needPwd, password.value),
                onClick = {
                    val p = mutableMapOf<String, Any>()
                    needNum.forEach { key ->
                        numValues.value[key]?.toIntOrNull()?.let { p[key] = it }
                    }
                    if (needPwd && password.value.isNotBlank()) p["password"] = password.value
                    if (needOn) p["on"] = onOff.value["on"] ?: true
                    onConfirm(p)
                },
            ) { Text(if (isHigh) "确认${cmd.description}" else "确定${cmd.description}") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !running) { Text("取消") }
        },
    )
}

/** 参数齐了吗（缺必填参数就别让用户点确认）。 */
private fun paramsReady(
    cmd: VehicleCommand,
    needNum: List<String>,
    numValues: Map<String, String>,
    needPwd: Boolean,
    pwd: String,
): Boolean {
    needNum.forEach { key ->
        val v = numValues[key]?.toIntOrNull() ?: return false
        val range = when (key) {
            "driver_temp", "passenger_temp" -> 15..28
            "percent" -> 50..100
            "charging_amps" -> 5..48
            else -> null
        }
        if (range != null && v !in range) return false
    }
    if (needPwd && (pwd.length < 4 || pwd.length > 20)) return false
    return true
}

private fun paramLabel(key: String): String = when (key) {
    "driver_temp" -> "驾驶位温度 ℃（15~28）"
    "passenger_temp" -> "副驾温度 ℃（15~28）"
    "percent" -> "充电上限 %（50~100）"
    "charging_amps" -> "充电电流 A（5~48）"
    else -> key
}
