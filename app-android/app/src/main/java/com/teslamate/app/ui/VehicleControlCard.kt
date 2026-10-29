package com.teslamate.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.teslamate.app.Api
import com.teslamate.app.CommandCatalog
import com.teslamate.app.CommandResult
import com.teslamate.app.VehicleCommand
import kotlinx.coroutines.Dispatchers
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
    /**
     * ⚠️ **v0.74.0**：外部刷新信号。
     *
     * 车况页的「↻ / 下拉刷新」必须刷新**屏幕上的一切** ——
     * 原来车控清单只在首次组合时读一次，于是：
     * 用户看到「读不到车控能力清单」⇒ 点 ↻ ⇒ **什么也没变**，
     * 那个错误状态会一直赖着，直到用户离开这一页再回来。
     *
     * 页面刷新时把计数器 +1 ⇒ 清单重新读一次。
     * （只有用户**主动**刷新才传；15 秒自动刷新不传 —— 动作清单不会自己变，
     *   每 15 秒重读一次纯属浪费。）
     */
    refreshSignal: Int = 0,
    /**
     * ⚠️ **v0.76.0**：命令下发**之后**回调 —— 让上层把车况重新读一遍。
     *
     * ## 为什么必须有
     *
     * 轮询间隔是 2 分钟，所以「我刚开了空调」之后，
     * 屏幕上的「空调关闭」会**赖两分钟** —— 车真的开了，App 却像是「点了没反应」。
     * 用户会以为车没听指挥，**然后再点一次**。
     *
     * 服务端在命令后已经**立即回读**过一次车况（缓存已是新的），
     * 这里再读一次，磁贴就会**当场**变成新状态。
     */
    onCommandFinished: () -> Unit = {},
    /**
     * ⚠️ **v0.76.0**：全局反馈宿主（Snackbar）。
     *
     * ## 为什么结果提示不能留在卡片里
     *
     * 卡片很长（空调 / 充电 / 其他 / 高危动作 四区），
     * 用户点「闪灯」时**卡片底部在屏幕外** ——
     * 结果条跟着一起看不见 = **「点了没反应」**，
     * 用户会以为车没听指挥，**然后再点一次**。
     *
     * ⇒ 结果改走屏幕底部的 Snackbar：**无论滚到哪儿都看得见**。
     */
    snackbarHostState: SnackbarHostState? = null,
    modifier: Modifier = Modifier,
) {
    var catalog by remember { mutableStateOf<CommandCatalog?>(null) }
    var loadFailed by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<VehicleCommand?>(null) }
    var running by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf<String?>(null) }
    var resultBad by remember { mutableStateOf(false) }
    /** 卡片自己的「重试」按钮用：+1 就重新读一次清单。 */
    var reloadKey by remember { mutableStateOf(0) }
    /** ⚠️ v0.76.0：上一次失败的动作 —— 给 Snackbar 的「重试」用。 */
    val scope = rememberCoroutineScope()

    LaunchedEffect(api, carId, refreshSignal, reloadKey) {
        if (api == null) return@LaunchedEffect
        // ⚠️ 重读前先清掉失败态 ⇒ 卡片回到「正在读车控能力…」，
        // 而不是把上一轮的失败文字留在屏幕上。
        loadFailed = null
        catalog = withContext(Dispatchers.IO) {
            runCatching { api.commands(carId) }.getOrNull()
        }
        if (catalog == null) {
            loadFailed = "读不到车控能力清单"
        }
    }

    /**
     * ⚠️ **v0.76.0**：把结果**说给用户听**。
     *
     * - 成功 ⇒ 4 秒后自动消失（Material3 的 Short）。
     * - 失败 ⇒ **不自动消失**（Indefinite）+ 一个真的有用的「重试」——
     *   「车端拒绝：充电枪没插」⇒ 用户插上枪，点「重试」就行；
     *   给一个「知道了」那种没用的按钮等于没说。
     */
    fun announce(text: String, bad: Boolean, retry: (() -> Unit)? = null) {
        resultMsg = text
        resultBad = bad
        val host = snackbarHostState ?: return
        scope.launch {
            val r = host.showSnackbar(
                message = text,
                actionLabel = if (bad && retry != null) "重试" else null,
                duration = if (bad) SnackbarDuration.Indefinite else SnackbarDuration.Short,
            )
            // ⚠️ 用户点了「重试」⇒ 原样再发一次（参数不变）。
            if (r == SnackbarResult.ActionPerformed && retry != null) {
                retry()
            }
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
                // ⚠️ **v0.76.0**：三态文案 —— 「已执行 / 被拒绝 / 已下发待确认」。
                // 原来只有一句「已下发」，于是**车端拒绝也会被报成成功**。
                val outcome = commandOutcome(r, cmd.done)
                if (outcome.bad) {
                    announce(outcome.text, true) { execute(cmd, params) }
                } else {
                    announce(outcome.text, false)
                }
            }.onFailure { e ->
                val msg = e.message ?: "命令没发出去"
                announce(msg, true) { execute(cmd, params) }
            }
            // ⚠️ **v0.76.0**：命令之后**立刻回读车况** ——
            // 否则「空调已开启」和上面的「空调关闭」磁贴会互相打脸（最多两分钟）。
            onCommandFinished()
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
                // ⚠️ **v0.74.0**：失败必须给一条**出路**。
                //
                // 原来这里只有一句死文字 —— 用户既不能重试、也不知道要去哪。
                // 现在给一个明确的重试按钮（不是「知道了」那种没有用的确认）。
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { reloadKey++ },
                    // ⚠️ 无障碍名**必须包含可见文字**（「重试」）——
                    // WCAG 2.5.3 Label in Name：可见标签要出现在可访问名里。
                    modifier = Modifier.semantics { contentDescription = "重试读取车控能力清单" },
                ) {
                    Text("重试", style = MaterialTheme.typography.labelLarge)
                }
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

                // ⚠️ **v0.87.0**：座椅加热 / 方向盘加热归「空调与温度」——
                // 用户想的是「我要把座位弄暖和」，不是「这动作算哪一档」。
                val climate = groupOf(
                    "空调与温度",
                    listOf("set_climate_on", "set_climate_off", "set_temperature",
                        "seat_heater", "steering_wheel_heater"),
                )
                val charging = groupOf("充电", listOf("charge_start", "charge_stop", "set_charge_limit", "set_charging_amps"))
                // ⚠️ 车窗单独一组 —— 「开窗」和「开空调」在用户脑子里是两件事。
                val windows = groupOf("车窗", listOf("window_vent", "window_close"))
                val known = climate + charging + windows
                val others = rest.filter { it !in known }

                if (climate.isNotEmpty()) {
                    ActionSection("空调与温度", climate, running, pending?.action, ::request)
                }
                if (charging.isNotEmpty()) {
                    ActionSection("充电", charging, running, pending?.action, ::request)
                }
                if (windows.isNotEmpty()) {
                    ActionSection("车窗", windows, running, pending?.action, ::request)
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

                // ── 结果条 ──
                //
                // ⚠️ **v0.76.0**：结果**优先走屏幕底部的 Snackbar** ——
                // 卡片很长，用户点上面的按钮时卡片底部在屏幕外，
                // 结果条跟着看不见 = 「点了没反应」。
                //
                // 这里保留它只是**没有全局宿主时的兜底**（别处单独用这张卡时）。
                if (snackbarHostState == null) {
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
 * ⚠️ **v0.76.0**：一次命令的**结果陈述**。
 *
 * ## 为什么要有它
 *
 * 原来只有一句「已下发：{动作}」—— 只要 HTTP 2xx 就这么写。
 * 而车端会**明确拒绝**（`result:false`，比如 `already_charging` / `not_charging` /
 * `disconnected`）：车没照做，App 却说搞定了。
 * ⇒ 这是**假成功** —— 用户会以为车没听指挥，**然后再点一次**
 * （而「开始充电」点两次是有副作用的）。
 *
 * ⇒ 现在必须分清三件事：
 *
 * | 车端说的      | 该怎么说              | 是坏消息吗 |
 * |-------------|---------------------|-----------|
 * | 已执行       | 「已锁车」            | 否         |
 * | 明确拒绝     | 「车端拒绝：…」      | **是**     |
 * | 发出去了但没确认 | 「已下发，等车端确认」 | 否     |
 *
 * ⚠️ **坏消息不自动消失**（成功才自动消失）——
 * 「车端拒绝：充电枪没插」三秒后自己没了，等于没说。
 */
data class CommandOutcome(val text: String, val bad: Boolean)

/**
 * 把服务端返回的命令结果翻译成一句人话。
 *
 * ⚠️ **老服务端兼容**：`state` 为空（服务端是旧版）⇒ 退回老口径
 * （`sent` ⇒ 「已下发：{动作}」），别让旧服务端一升级就显示不出东西。
 */
fun commandOutcome(result: CommandResult, fallbackDone: String = ""): CommandOutcome {
    val done = result.done?.takeIf { it.isNotBlank() }
        ?: fallbackDone.takeIf { it.isNotBlank() }
        ?: result.description.takeIf { it.isNotBlank() }
        ?: "命令"
    val reasonText = result.reason_text?.takeIf { it.isNotBlank() }
        ?: result.reason?.takeIf { it.isNotBlank() }

    return when (result.state) {
        "executed" -> CommandOutcome(done, false)
        "rejected" -> CommandOutcome(
            "车端拒绝" + (reasonText?.let { "：$it" } ?: ""),
            true,
        )
        "dispatched" -> CommandOutcome("已下发，等车端确认", false)
        "failed" -> CommandOutcome(
            result.error?.takeIf { it.isNotBlank() } ?: "命令没发出去",
            true,
        )
        else -> when {
            // 老服务端：只有 sent / error
            !result.error.isNullOrBlank() -> CommandOutcome(result.error!!, true)
            result.sent -> CommandOutcome("已下发：$done", false)
            else -> CommandOutcome("命令没发出去", true)
        }
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
            // ⚠️ **v0.76.0**：一行里的两个按钮**等高**。
            //
            // 「唤醒车辆（会耗电、可能计费）」这种长标签要能换成两行，
            // 而同排的另一个按钮只有一行 —— 不等高就会一高一低，像没对齐。
            // `height(IntrinsicSize.Min)` + 按钮 `fillMaxHeight()` 是 Compose 的标准做法。
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(IntrinsicSize.Min),
            ) {
                pair.forEach { cmd ->
                    ActionButton(
                        label = cmd.description.ifBlank { cmd.action },
                        onClick = { onPick(cmd) },
                        enabled = !running,
                        primary = false,
                        danger = danger,
                        busy = running && runningAction == cmd.action,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
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
            .heightIn(min = 48.dp)
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
                    // ⚠️ **v0.76.0**：**允许换成两行**。
                    //
                    // 原来 maxLines=1 且不设 overflow ⇒ Compose 默认 **Clip**，
                    // 于是「唤醒车辆（会耗电、可能计费）」被**拦腰砍断**成
                    // 「唤醒车辆（会耗电、」—— 看起来像渲染坏了，
                    // 而且**代价提示整个不见了**。
                    //
                    // 而 v0.71.0 的设计决定正是「后果写在标签里就不必再打断一次」
                    // ⇒ 标签必须完整可见。
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
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
    // ⚠️ **v0.87.0**：参数不再按**名字**硬编码，而是照**服务端给的规格**渲染。
    //
    // 原来那张表（needNum / needPwd / needOn）只认 4 个参数名 ——
    // 来了新参数（座椅加热的座位号、档位）它就不认识了 ⇒
    // 弹窗里一个控件都没有，用户点「确定」只会被服务端拒掉。
    //
    // ⚠️ `remember(cmd.action)`：按动作名重置 ——
    // 否则关掉 A 的弹窗再开 B 的，上一个动作的输入会**残留**。
    val fields = remember(cmd.action) { resolveParamFields(cmd) }
    var values by remember(cmd.action) { mutableStateOf(initialParamValues(fields)) }
    val isHigh = cmd.risk == "high"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            // ⚠️ **v0.71.0**：高危动作的标题带「确认」，纯参数动作直接用动作名
            // —— 参数弹窗**本身就是**一次确认，再叠一层「确认确认」是多余的打断。
            Text(if (isHigh) "确认${cmd.description}" else cmd.description)
        },
        text = {
            // ⚠️ **v0.87.0**：内容可滚动。
            // 座椅加热的座位有 9 个（选择器），加上档位 4 个 ——
            // 不给滚动容器，200% 字号下会直接**顶出弹窗**。
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                // ⚠️ **v0.87.0**：正文**不再重复**动作说明。
                //
                // 标题已经写了 `cmd.description`（高危动作是「确认X」），
                // 正文再来一遍 ⇒ 同一屏出现两次同样的字
                //（「设定座舱温度（℃）」占两行，却只多了一个信息量）。
                // ⇒ 标题说过的，正文不再说。
                fields.forEach { f ->
                    ParamFieldRow(
                        field = f,
                        value = values[f.key] ?: "",
                        onValueChange = { v -> values = values + (f.key to v) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !running && paramFieldsReady(fields, values),
                onClick = { onConfirm(paramFieldsToParams(fields, values)) },
            ) { Text(if (isHigh) "确认${cmd.description}" else "确定${cmd.description}") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !running) { Text("取消") }
        },
    )
}

/**
 * 一个参数控件。
 *
 * ⚠️ **v0.87.0**：控件**照服务端给的规格**渲染，三种形态：
 *
 * | 参数形态        | 控件 | 为什么 |
 * |-----------------|------|--------|
 * | 有可选值（int） | **选择器**（一排小标签） | 「座位号 0~8」让人自己填数字是折磨；给名字才能选对 |
 * | 数字（无取值）  | 数字输入框 | 温度、电流这类连续量 |
 * | 布尔            | 开关 | 开 / 关本来就该是开关 |
 * | 口令            | 掩码输入框 | PIN 不该明文显示 |
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParamFieldRow(
    field: ParamField,
    value: String,
    onValueChange: (String) -> Unit,
) {
    when {
        field.kind == ParamKind.BOOL -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(field.label, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = value != "false",
                    onCheckedChange = { onValueChange(if (it) "true" else "false") },
                )
            }
        }
        field.chooser -> {
            Column {
                Text(field.label, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    field.options.forEach { opt ->
                        FilterChip(
                            selected = value == opt.value,
                            onClick = { onValueChange(opt.value) },
                            label = { Text(opt.label) },
                            // ⚠️ **v0.87.0**：选中态用 `primaryContainer`，
                            // 别用 M3 默认的浅灰 —— 在白底弹窗上那一层灰
                            // 跟背景几乎同色，**看不出选了哪个**。
                            // primaryContainer #FFDAD6 / onPrimaryContainer #410001
                            // ⇒ 浅色下对比度 ≈ 15:1 ✓（深色下 #FFDAD6 on #93000A 同理）。
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        )
                    }
                }
            }
        }
        else -> {
            OutlinedTextField(
                value = value,
                onValueChange = { v ->
                    onValueChange(if (field.kind == ParamKind.INT) v.filter { it.isDigit() } else v)
                },
                label = { Text(field.label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (field.kind == ParamKind.INT) KeyboardType.Number else KeyboardType.Text,
                ),
                visualTransformation = if (field.kind == ParamKind.PASSWORD) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
            )
        }
    }
}
