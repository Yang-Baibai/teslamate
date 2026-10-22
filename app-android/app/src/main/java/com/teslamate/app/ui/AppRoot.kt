package com.teslamate.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.teslamate.app.AppLatest
import com.teslamate.app.AppSettings
import com.teslamate.app.BuildConfig
import com.teslamate.app.Notifier
import com.teslamate.app.WatchWorker
import com.teslamate.app.settingsFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class Tab(val label: String, val icon: ImageVector) {
    STATUS("车况", Icons.Filled.DirectionsCar),
    CHARGES("充电", Icons.Filled.BatteryChargingFull),
    DRIVES("行程", Icons.Filled.Route),
    STATS("统计", Icons.Filled.QueryStats),
    SETTINGS("设置", Icons.Filled.Settings),
}

@Composable
fun TeslaMateApp() {
    val ctx = LocalContext.current
    var settings by remember { mutableStateOf<AppSettings?>(null) }
    var updateInfo by remember { mutableStateOf<AppLatest?>(null) }

    LaunchedEffect(Unit) {
        ctx.settingsFlow().collect { settings = it }
    }

    // 启动后查一次更新：仅在 settings 配好后触发；versionCode 严格大于当前才提示
    LaunchedEffect(settings?.configured) {
        val s = settings
        if (s == null || !s.configured) return@LaunchedEffect
        val api = s.api() ?: return@LaunchedEffect
        runCatching {
            withContext(Dispatchers.IO) { api.appLatest() }
        }.onSuccess { latest ->
            val remote = latest.version_code.toIntOrNull() ?: 0
            if (remote > BuildConfig.VERSION_CODE && latest.apk_url.isNotBlank()) {
                updateInfo = latest
            }
        }
        // 静默失败：网络抖动不打扰用户，下次启动再查
    }

    // 通知渠道 + 后台监听调度（随设置变化自动生效）
    // 注意：只在「已配置服务器 + 用户主动开启通知」时才排任务，
    // 否则会白跑轮询也发不出通知。
    LaunchedEffect(settings?.notifyEnabled, settings?.configured) {
        val s = settings ?: return@LaunchedEffect
        Notifier.ensureChannels(ctx)
        WatchWorker.schedule(ctx, s.notifyEnabled && s.configured)
    }

    TeslaMateTheme {
        // MIUI/HyperOS 大字号档会把 fontScale 提到 1.2~1.3，官方适配文档明确指出
        // 大档位会造成"布局错乱、重叠"（dev.mi.com 大字体适配说明）。
        // 仪表盘类界面固定 dp 容器多，这里把 App 内字体缩放封顶到 1.15：
        // 保留可读性增益，同时杜绝文字溢出/截断。
        val baseDensity = LocalDensity.current
        val cappedDensity = remember(baseDensity) {
            Density(baseDensity.density, fontScale = minOf(baseDensity.fontScale, 1.15f))
        }
        CompositionLocalProvider(LocalDensity provides cappedDensity) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                val s = settings
                if (s == null) {
                    CircularProgressIndicator()
                } else {
                    MainScaffold(s)
                    updateInfo?.let { info ->
                        UpdateDialog(
                            info = info,
                            onDismiss = { if (!info.mandatory) updateInfo = null },
                            onUpdate = { url -> ctx.downloadAndInstall(url) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(settings: AppSettings) {
    var tab by remember { mutableStateOf(Tab.STATUS) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Bottom),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        // Play 式 Tab 过渡：淡入 + 按导航方向的轻水平滑
        AnimatedContent(
            targetState = tab,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            transitionSpec = {
                val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                (fadeIn(tween(220)) + slideInHorizontally(tween(240)) { dir * it / 8 }) togetherWith
                    (fadeOut(tween(160)))
            },
            label = "tabSwitch",
        ) { current ->
            when (current) {
                Tab.STATUS -> StatusScreen(settings, Modifier.fillMaxSize())
                Tab.CHARGES -> ChargesScreen(settings, Modifier.fillMaxSize())
                Tab.DRIVES -> DrivesScreen(settings, Modifier.fillMaxSize())
                Tab.STATS -> StatsScreen(settings, Modifier.fillMaxSize())
                Tab.SETTINGS -> SettingsScreen(settings, Modifier.fillMaxSize())
            }
        }
    }
}
