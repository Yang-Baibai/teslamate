@file:OptIn(ExperimentalMaterial3Api::class)

package com.teslamate.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import com.teslamate.app.Notifier
import com.teslamate.app.WatchWorker
import com.teslamate.app.saveSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(settings: AppSettings, modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember(settings) { mutableStateOf(settings.serverUrl) }
    var token by remember(settings) { mutableStateOf(settings.token) }
    var carId by remember(settings) { mutableStateOf(settings.carId) }
    var notifyOn by remember(settings) { mutableStateOf(settings.notifyEnabled) }
    var cars by remember { mutableStateOf<List<Car>>(emptyList()) }
    var showToken by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testOk by remember { mutableStateOf<Boolean?>(null) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    var savedHint by remember { mutableStateOf(false) }
    var notifyHint by remember { mutableStateOf<String?>(null) }

    fun save() {
        scope.launch { ctx.saveSettings(AppSettings(url, token, carId, notifyOn)) }
        savedHint = true
    }

    // 通知权限必须在「用户主动开启」时申请——系统不允许在启动时静默弹窗
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            notifyOn = true
            notifyHint = "通知已开启，正在安排后台检查…"
            save()
            WatchWorker.runNow(ctx)
        } else {
            notifyOn = false
            notifyHint = "未授予通知权限，后台监听可以开启但不会弹出通知。"
        }
    }

    LaunchedEffect(settings) {
        val api = settings.api() ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { api.cars() } }
            .onSuccess { if (it.isNotEmpty()) cars = it }
    }


    Column(modifier) {
        TopAppBar(
            title = { Text("设置", fontWeight = FontWeight.Bold) },
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard("服务器连接", Modifier.animateItem()) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it; testOk = null; testMsg = null; savedHint = false },
                        label = { Text("服务器地址") },
                        placeholder = { Text("http://192.168.1.100:8090") },
                        singleLine = true,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it; testOk = null; testMsg = null; savedHint = false },
                        label = { Text("访问令牌 (API_TOKEN)") },
                        singleLine = true,
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = showToken, onCheckedChange = { showToken = it })
                        Text("显示令牌", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
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
                        ) {
                            if (testing) {
                                CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("测试连接")
                        }
                        OutlinedButton(
                            enabled = url.isNotBlank() && token.isNotBlank(),
                            onClick = { save() },
                        ) {
                            Text("保存")
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
                        cars.forEach { c ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = carId == c.id,
                                    onClick = { carId = c.id; savedHint = false },
                                )
                                Text("${c.name}${c.marketing_name?.let { " · $it" } ?: ""}")
                            }
                        }
                        TextButton(onClick = { save() }) { Text("保存车辆选择") }
                    }
                }
            }

            item {
                SectionCard("车况通知", Modifier.animateItem()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("后台监听与通知", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "每 15 分钟检查一次车况。以下情况推送通知：开始充电、充电完成、" +
                                    "胎压异常、数据超过 24 小时未更新。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = notifyOn,
                            onCheckedChange = { on ->
                                if (!on) {
                                    notifyOn = false
                                    notifyHint = null
                                    save()
                                } else if (Notifier.canNotify(ctx)) {
                                    notifyOn = true
                                    notifyHint = "正在安排后台检查…"
                                    save()
                                    WatchWorker.runNow(ctx)
                                } else {
                                    permLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                        )
                    }
                    notifyHint?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (notifyOn) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { WatchWorker.runNow(ctx) }) {
                                Text("立即检查")
                            }
                        }
                        Text(
                            "提示：国产系统（小米/华为等）会限制后台任务。若很久才收到通知，" +
                                "请到「设置 → 应用设置 → 应用管理 → 爱车车况 → 电池」" +
                                "把「省电策略」改为「无限制」，否则后台检查可能被推迟很久。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                SectionCard("怎么找到地址和令牌？", Modifier.animateItem()) {
                    Text(
                        "• 家里 WiFi：地址填 http://<电脑局域网IP>:8090（电脑上 ipconfig 可查 IP）\n" +
                            "• 外出使用：电脑和手机都装 Tailscale 并登录同一账号后，地址改填 http://<电脑的Tailscale名称或IP>:8090\n" +
                            "• 令牌在电脑项目目录的 .env 文件中 API_TOKEN= 一行",
                        style = MaterialTheme.typography.bodySmall,
                    )
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
