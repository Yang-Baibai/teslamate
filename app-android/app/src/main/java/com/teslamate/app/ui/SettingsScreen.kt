@file:OptIn(ExperimentalMaterial3Api::class)

package com.teslamate.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.teslamate.app.Api
import com.teslamate.app.AppSettings
import com.teslamate.app.BuildConfig
import com.teslamate.app.Car
import com.teslamate.app.Haptics
import com.teslamate.app.saveSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页。
 *
 * ⚠️ **v0.9.2（苹果视觉评审 P0-6 + iOS 交互评审 P1-9）：整页去 Material 味**。
 *
 * 原来这里直接用了 M3 的 `OutlinedTextField` / `Checkbox` / `RadioButton` ——
 * 1dp 描边 + 标签在边框上开缺口的轮廓框、方框 Checkbox、红圈 RadioButton，
 * 是全 App 里与 iOS 语言**唯一**的断层（其余页面全是自绘材质）。
 *
 * 现在：
 * - 输入框 → [IosField]（无描边填充式：标签在上、值在下，圆角 12dp）
 * - 「显示令牌」→ [IosSwitch]（51×31dp 胶囊，iOS 开关）
 * - 「选择车辆」→ **整行可点 + 右侧对勾**（不是 radio 按钮）
 * - 次要按钮 → 中性描边（原来「保存」是**红字描边**，和主按钮的实心红撞色）
 */
@Composable
fun SettingsScreen(settings: AppSettings, modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember(settings) { mutableStateOf(settings.serverUrl) }
    var token by remember(settings) { mutableStateOf(settings.token) }
    var carId by remember(settings) { mutableStateOf(settings.carId) }
    // ⚠️ v0.9.0：保养 / 换胎提醒的基准值
    //
    // ⚠️ v0.16.0（车队运营评审 P0-3）：基准**按车隔离** ——
    // 这里读写的是**当前车**那套（`settings.currentBaselines`），
    // 不再是全局单值。家人那台车 30,000 公里的表，
    // 不能用我的 79,000 基准去套。
    val curBase = settings.currentBaselines
    var serviceOdo by remember(settings) { mutableStateOf(curBase.serviceOdoKm) }
    var serviceInterval by remember(settings) { mutableStateOf(curBase.serviceIntervalKm.toString()) }
    var tireOdo by remember(settings) { mutableStateOf(curBase.tireOdoKm) }
    var tireInterval by remember(settings) { mutableStateOf(curBase.tireIntervalKm.toString()) }
    var cars by remember { mutableStateOf<List<Car>>(emptyList()) }
    var showToken by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testOk by remember { mutableStateOf<Boolean?>(null) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    var savedHint by remember { mutableStateOf(false) }

    fun save() {
        scope.launch {
            // ⚠️ v0.16.0：写进**当前车**的那一套基准。
            //
            // 旧字段也一起写（保留回滚安全），但 `perCar` 才是新的事实源 ——
            // `baselinesFor()` 只在「该车没配过」时才回落到旧字段。
            val b = com.teslamate.app.CarBaselines(
                // ⚠️ 空串 = 不填 = 不启用提醒（0 表示未设置）
                serviceOdoKm = serviceOdo,
                serviceIntervalKm = serviceInterval.trim().toIntOrNull() ?: 20000,
                tireOdoKm = tireOdo,
                tireIntervalKm = tireInterval.trim().toIntOrNull() ?: 40000,
            )
            val next = settings.perCar.toMutableMap()
            if (b.serviceOdoKm > 0 || b.tireOdoKm > 0) {
                next[carId] = b
            } else {
                next.remove(carId)
            }
            ctx.saveSettings(
                AppSettings(
                    serverUrl = url,
                    token = token,
                    carId = carId,
                    serviceOdoKm = b.serviceOdoKm,
                    serviceIntervalKm = b.serviceIntervalKm,
                    tireOdoKm = b.tireOdoKm,
                    tireIntervalKm = b.tireIntervalKm,
                    perCar = next,
                )
            )
        }
        savedHint = true
    }

    LaunchedEffect(settings) {
        val api = settings.api() ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { api.cars() } }
            .onSuccess { if (it.isNotEmpty()) cars = it }
    }


    Column(modifier) {
        TopAppBar(
            title = { ScreenTitle("设置") },
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard("服务器连接", Modifier.animateItem()) {
                    IosField(
                        label = "服务器地址",
                        value = url,
                        onValueChange = { url = it; testOk = null; testMsg = null; savedHint = false },
                        placeholder = "http://192.168.1.100:8090",
                    )
                    IosField(
                        label = "访问令牌 (API_TOKEN)",
                        value = token,
                        onValueChange = { token = it; testOk = null; testMsg = null; savedHint = false },
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardType = KeyboardType.Password,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                Haptics.selection(ctx)
                                showToken = !showToken
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        IosSwitch(checked = showToken, onCheckedChange = { showToken = it })
                        Text("显示令牌", style = MaterialTheme.typography.bodyMedium)
                    }
                    // ⚠️ v0.14.0（iOS 动效专项评审 P2-4 + IA 评审 P0-2）：
                    // **实心品牌红按钮 = 全 App 最大的材质违规** ——
                    // 它是全 App 唯一的饱和红块，与车况页顶部的告警红条撞色：
                    // 红在这个 App 的语义里只表示**告警**，
                    // 而这里是在引导主人去点一个「红按钮」。
                    //
                    // 层级也反了：**「保存」才是离开前必做项**，「测试连接」是可选诊断。
                    // 现在主次颠倒：
                    //   · 主按钮「保存并测试」—— 一次点击完成保存 + 校验（中性填充）
                    //   · 次按钮「仅测试连接」—— 描边
                    // 两个按钮都**不是红色**。
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            enabled = !testing && url.isNotBlank() && token.isNotBlank(),
                            onClick = {
                                // 先落盘，再校验 —— 一次点击完成两件事
                                save()
                                testing = true
                                testOk = null
                                testMsg = null
                                scope.launch {
                                    val api = Api.create(url, token)
                                    when {
                                        api == null -> {
                                            testOk = false
                                            testMsg = "地址格式无效（需要 http:// 或 https:// 开头）"
                                        }
                                        else -> runCatching { withContext(Dispatchers.IO) { api.cars() } }
                                            .onSuccess {
                                                cars = it
                                                testOk = true
                                                testMsg = "连接成功，发现 ${it.size} 台车"
                                                if (it.none { car -> car.id == carId }) {
                                                    carId = it.first().id
                                                }
                                            }
                                            .onFailure {
                                                testOk = false
                                                testMsg = it.message ?: "连接失败"
                                            }
                                    }
                                    testing = false
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                        ) {
                            if (testing) {
                                CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("保存并测试")
                        }
                        OutlinedButton(
                            enabled = !testing && url.isNotBlank() && token.isNotBlank(),
                            onClick = {
                                testing = true
                                testOk = null
                                testMsg = null
                                scope.launch {
                                    val api = Api.create(url, token)
                                    when {
                                        api == null -> {
                                            testOk = false
                                            testMsg = "地址格式无效（需要 http:// 或 https:// 开头）"
                                        }
                                        else -> runCatching { withContext(Dispatchers.IO) { api.cars() } }
                                            .onSuccess {
                                                cars = it
                                                testOk = true
                                                testMsg = "连接成功，发现 ${it.size} 台车"
                                                if (it.none { car -> car.id == carId }) {
                                                    carId = it.first().id
                                                }
                                            }
                                            .onFailure {
                                                testOk = false
                                                testMsg = it.message ?: "连接失败"
                                            }
                                    }
                                    testing = false
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                        ) {
                            Text("仅测试连接")
                        }
                    }
                    testMsg?.let {
                        Text(
                            it,
                            color = if (testOk == true) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (savedHint) {
                        Text(
                            "已保存 ✓（其他页面会自动刷新）",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (cars.isNotEmpty()) {
                item {
                    SectionCard("选择车辆", Modifier.animateItem()) {
                        // ⚠️ v0.9.2：M3 的 RadioButton（红圈红点）→ **整行可点 + 右侧对勾**。
                        // 这是 iOS 设置页的标准范式，命中区也从一个小圆点变成整行。
                        //
                        // ⚠️ v0.14.0（苹果 HIG P1）：**点选即保存**，删掉「保存车辆选择」按钮。
                        //
                        // 原来选完车还要点一个**裸文字**按钮才生效 ——
                        // 而那行文字和上面的车名同形、同样式，用户根本看不出它可点
                        // （评审原话：「用户无法识别可点」）。
                        // iOS 的做法是：改单选 → **立即生效**，右侧对勾就是结果。
                        cars.forEach { c ->
                            val selected = carId == c.id
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        Haptics.selection(ctx)
                                        carId = c.id
                                        savedHint = false
                                        // 点选即保存（iOS 范式）
                                        save()
                                    }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(
                                    "${c.name}${c.marketing_name?.let { " · $it" } ?: ""}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "已选中",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                        Text(
                            "点一下就切换过去，不用再按保存。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                // ⚠️ v0.9.0（新手车主复核）：
                // 「上次保养是什么时候 / 什么时候该换胎 —— 全项目 0 命中。这是我最想要的」。
                //
                // 为什么放在**设置**里而不是服务端：
                // 保养是车主的线下行为，没有任何机器能自动知道。
                // 让车主填一个基准（上次保养时的里程表）+ 一个间隔，
                // App 用里程表一减就得到「还剩多少公里」——
                // 这正是车主真正想知道的那句话。
                SectionCard(
                    // ⚠️ v0.16.0：标清是**哪台车**的基准（基准已按车隔离）。
                    "保养与轮胎提醒" +
                        (cars.firstOrNull { it.id == carId }?.name?.let { " · $it" } ?: ""),
                    Modifier.animateItem(),
                ) {
                    Text(
                        "填「上次做的时候里程表是多少」和一个间隔，" +
                            "App 用现在的里程表算出还剩多少公里。留空 = 不提醒。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IosField(
                        label = "上次保养时的里程（公里）",
                        value = if (serviceOdo > 0) serviceOdo.toLong().toString() else "",
                        onValueChange = {
                            serviceOdo = it.trim().toDoubleOrNull() ?: 0.0
                            savedHint = false
                        },
                        keyboardType = KeyboardType.Number,
                    )
                    IosField(
                        label = "保养间隔（公里）",
                        value = serviceInterval,
                        onValueChange = { serviceInterval = it.filter { c -> c.isDigit() }; savedHint = false },
                        keyboardType = KeyboardType.Number,
                    )
                    IosField(
                        label = "上次换胎时的里程（公里）",
                        value = if (tireOdo > 0) tireOdo.toLong().toString() else "",
                        onValueChange = {
                            tireOdo = it.trim().toDoubleOrNull() ?: 0.0
                            savedHint = false
                        },
                        keyboardType = KeyboardType.Number,
                    )
                    IosField(
                        label = "轮胎更换间隔（公里）",
                        value = tireInterval,
                        onValueChange = { tireInterval = it.filter { c -> c.isDigit() }; savedHint = false },
                        keyboardType = KeyboardType.Number,
                    )
                    // ⚠️ v0.14.0（苹果 HIG P1）：**实心按钮**，不再是裸文字。
                    //
                    // 「保存提醒设置」原来是一个 TextButton —— 与上面的字段标签同形、同样式，
                    // 用户识别不出它可点。改成 filled button（高 44dp、圆角 12dp、
                    // 文字 SemiBold），层级立刻明确。
                    Button(
                        onClick = { save() },
                        shape = RoundedCornerShape(12.dp),
                        // ⚠️ v0.16.0：**中性**，不是品牌红。
                        // 红色在这个 App 的语义里只表示**告警** ——
                        // 一个「保存提醒」的按钮用红块，是在把例行操作伪装成警报。
                        // （v0.15.0 把「测试连接」改中性时漏了这一个。）
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                    ) {
                        Text("保存保养与轮胎设置", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            item {
                SectionCard("怎么找到地址和令牌？", Modifier.animateItem()) {
                    // ⚠️ v0.16.0（苹果 HIG P2）：**悬挂缩进** ——
                    // 原来三条挤在一个 Text 里，项目符号后面的换行顶格对齐卡片内边距，
                    // 读起来像三段各说各话，而不是一个列表。
                    //
                    // 现在每条一行 Row：项目符号占固定宽度，文字在右边自己换行 ——
                    // 续行自然与文字起始处对齐（真正的悬挂缩进）。
                    listOf(
                        "家里 WiFi：地址填 http://<电脑局域网IP>:8090（电脑上 ipconfig 可查 IP）",
                        "外出使用：电脑和手机都装 Tailscale 并登录同一账号后，地址改填 http://<电脑的Tailscale名称或IP>:8090",
                        "令牌在电脑项目目录的 .env 文件中 API_TOKEN= 一行",
                    ).forEach { line ->
                        Row(Modifier.padding(bottom = 6.dp)) {
                            Text(
                                "•",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(14.dp),
                            )
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            item {
                SectionCard("关于", Modifier.animateItem()) {
                    KV("当前版本", "v${BuildConfig.VERSION_NAME}")
                    KV("版本号", BuildConfig.VERSION_CODE.toString())
                    Text(
                        "启动时自动检查更新；有新版本会弹窗提示，点「立即更新」即可在线升级。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * iOS 风格的输入框：**无描边填充式**，标签在上、值在下。
 *
 * ⚠️ v0.9.2（苹果视觉评审 P0-6）：原来用 M3 的 `OutlinedTextField` ——
 * 1dp 描边 + 标签在边框上开缺口，是最典型的「Material 味」符号。
 *
 * 现在：容器用 `cardInset`（与卡片内凹块同一层级）、圆角 12dp、**0dp 描边**，
 * 标签是框外上方一行 13sp 灰字，值 17sp 亮字 —— 与 iOS 设置页一致。
 */
@Composable
fun IosField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val accents = LocalAccentColors.current
    Column(modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(accents.cardInset)
                .padding(horizontal = 14.dp, vertical = 13.dp),
        ) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = visualTransformation,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * iOS 开关（51×31dp 胶囊 + 27dp 滑块）。
 *
 * ⚠️ v0.9.2：原来用 M3 的 `Checkbox` —— 方框 + 灰描边，
 * 是全 App 里 Material 味最重的一个元素。
 */
@Composable
fun IosSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAccentColors.current
    // ⚠️ `LocalContext.current` 只能在 @Composable 作用域取，
    // 所以先取出来，不能写在 .clickable 的 lambda 里。
    val ctx = LocalContext.current
    val knobOffset by animateDpAsState(
        // ⚠️ v0.9.2：iOS 开关的**轨道宽度恒定**（51×31），只有滑块左右移动。
        // 原来我把轨道也做成 51↔31 动画 —— 关掉时轨道变成 31×31 的**圆**，
        // 看起来像一个空心圆圈，不是开关。
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = tween(180),
        label = "switchKnob",
    )
    val trackColor by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(180),
        label = "switchColor",
    )
    val onColor = Color(0xFF34C759)
    val offColor = accents.chartTrack
    Box(
        modifier
            .width(51.dp)
            .height(31.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                androidx.compose.ui.graphics.lerp(offColor, onColor, trackColor) ?: offColor
            )
            .clickable {
                Haptics.selection(ctx)
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(start = knobOffset)
                .size(27.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .border(0.5.dp, Color(0x1A000000), RoundedCornerShape(14.dp)),
        )
    }
}
