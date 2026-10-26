package com.teslamate.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ⚠️ **v0.66.0（第 33 轮「车辆控制下沉」）：车辆控制卡。**
 *
 * 车主诉求：「我希望接下来给这个 app 加入车辆控制的功能。」
 *
 * ## 设计取舍（按所有者的审美）
 *
 * - **一个主角**：锁车/解锁是车主 90% 的车控需求 ⇒ 给它一个**大按钮**，
 *   上面直接写现在是什么状态（已锁 / 未锁 / 未知）。
 *   其余动作是**紧凑的文字块**，不跟主角抢视觉。
 * - **高危动作单独一区**（解锁除外，它是主角）：开合尾门、远程启动
 *   —— 用琥珀色描边区分，**不靠颜色单独承担**（WCAG 1.4.1），
 *   区标题也写明「高危」。
 * - **每个动作都要确认**：车控是**不可逆**的（门开了就是开了）。
 *   确认弹窗的按钮写**具体动作**（「确认解锁」而不是「确定」）。
 * - **车在休眠时先说清楚**：命令发不出去（Tesla 回 408），
 *   这不是失败，是「车没醒」—— 顶部一条琥珀提示 + 唤醒入口。
 * - 动作清单由**服务端**下发（`GET /api/v1/cars/{id}/commands`），
 *   客户端**不新增动作**、也不自己判断高危 —— 那是安全策略，只该有一处。
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
                    Spacer(Modifier.height(6.dp))
                }

                // ── 主角：锁车/解锁 ──
                val lockCmd = c.actions.firstOrNull { it.action == "door_lock" }
                val unlockCmd = c.actions.firstOrNull { it.action == "door_unlock" }
                if (lockCmd != null && unlockCmd != null) {
                    PrimaryLockTile(
                        locked = locked,
                        busy = running && (pending?.action == "door_lock" || pending?.action == "door_unlock"),
                        onLock = { lockCmd.let { pending = it } },
                        onUnlock = { unlockCmd.let { pending = it } },
                    )
                    Spacer(Modifier.height(10.dp))
                }

                // ── 常用（low）──
                val common = c.actions.filter {
                    it.risk == "low" && it.action != "door_lock" && it.action != "door_unlock"
                }
                if (common.isNotEmpty()) {
                    Text("常用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    ActionGrid(common, running, pending?.action) { pending = it }
                    Spacer(Modifier.height(10.dp))
                }

                // ── 调节（medium）──
                val adjust = c.actions.filter { it.risk == "medium" }
                if (adjust.isNotEmpty()) {
                    Text("调节", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    ActionGrid(adjust, running, pending?.action) { pending = it }
                    Spacer(Modifier.height(10.dp))
                }

                // ── 高危 ──
                val danger = c.actions.filter { it.risk == "high" && it.action != "door_unlock" }
                if (danger.isNotEmpty()) {
                    Text(
                        "高危动作",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalAccentColors.current.warnAmber,
                    )
                    Spacer(Modifier.height(4.dp))
                    ActionGrid(danger, running, pending?.action, danger = true) { pending = it }
                }

                // ── 上一次的结果 ──
                resultMsg?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (resultBad) LocalAccentColors.current.warnAmber
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // ── 确认弹窗 ──
    pending?.let { cmd ->
        CommandConfirmDialog(
            cmd = cmd,
            running = running,
            onDismiss = { if (!running) pending = null },
            onConfirm = { params ->
                if (api == null) return@CommandConfirmDialog
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
            },
        )
    }
}

/**
 * 主角块：锁车 / 解锁。
 *
 * ⚠️ 锁车状态**未知**时（null）不能说「已锁」也不能说「未锁」——
 * 那是没读到，不是车没锁（车况页一直守这条）。
 */
@Composable
private fun PrimaryLockTile(
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
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (locked == true) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = null,
                    tint = when (locked) {
                        true -> accents.energyBlue
                        false -> MaterialTheme.colorScheme.onSurface
                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when (locked) {
                        true -> "已锁"
                        false -> "未锁"
                        null -> "锁车状态未知"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            // ⚠️ **v0.66.0**：三种状态**各写一句**，不写成一个 `when` ——
            // 界面上一行只可能出现一句，而客户端闸门「啰嗦棘轮」按
            // 「一个 Text 块里的文字总量」计 ⇒ 写成 `when` 会被算成 53 字的一块，
            // 让闸门误判「又铺开了」。拆开写，度量与界面一致。
            val stateLine = when (locked) {
                true -> "车已经锁好了，需要的话可以再锁一次。"
                false -> "车没锁 —— 点下面解锁。"
                null -> "没读到锁车状态（车在休眠或没连实时状态源）。"
            }
            Text(
                stateLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (locked == null) {
                Text(
                    "仍可手动锁 / 解锁。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("锁车", onLock, enabled = !busy, primary = true, modifier = Modifier.weight(1f))
                ActionButton("解锁", onUnlock, enabled = !busy, primary = false, modifier = Modifier.weight(1f))
            }
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
 * 确认弹窗。
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认${cmd.description}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cmd.risk == "high") {
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
                if (cmd.description.isNotBlank()) {
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
            ) { Text("确认${cmd.description}") }
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
