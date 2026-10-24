package com.teslamate.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.teslamate.app.AppLatest
import com.teslamate.app.AppSettings
import com.teslamate.app.BuildConfig
import com.teslamate.app.Haptics
import com.teslamate.app.settingsFlow
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
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

    TeslaMateTheme {
        // MIUI/HyperOS 大字号档会把 fontScale 提到 1.2~1.3，官方适配文档明确指出
        // 大档位会造成"布局错乱、重叠"（dev.mi.com 大字体适配说明）。
        // 仪表盘类界面固定 dp 容器多，这里把 App 内字体缩放封顶到 1.15：
        // 保留可读性增益，同时杜绝文字溢出/截断。
        val baseDensity = LocalDensity.current
        val cappedDensity = remember(baseDensity) {
            Density(baseDensity.density, fontScale = minOf(baseDensity.fontScale, 1.15f))
        }
        CompositionLocalProvider(
            LocalDensity provides cappedDensity,
            LocalHazeState provides remember { HazeState() },
        ) {
            Box(Modifier.fillMaxSize()) {
                val s = settings
                if (s == null) {
                    // ⚠️ 修正：原来这个转圈出现在左上角（Surface 默认 contentAlignment=TopStart）。
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    MainScaffold(s)
                    updateInfo?.let { info ->
                        UpdateDialog(
                            info = info,
                            onDismiss = { if (!info.mandatory) updateInfo = null },
                            onUpdate = { url -> updateInfo = null; ctx.downloadAndInstall(url) },
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
    val ctx = LocalContext.current
    // 车况数据提到 App 级：切 Tab 时页面会被 AnimatedContent 销毁，
    // 数据放在页面里就会连同骨架屏一起重放一次（等于重新开一次 App）。
    val statusStore = remember { StatusStore() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Bottom),
        bottomBar = {
            // 毛玻璃导航栏（iOS bar material）
            val accents = LocalAccentColors.current
            Box(
                Modifier
                    .fillMaxWidth()
                    .glassSurface(GlassLevel.Bar, RoundedCornerShape(0.dp), realBlur = true)
            ) {
                Column {
                    // ⚠️ v0.7.1：栏顶原来**没有分割线**，页面内容直接溶进栏里，
                    // 边界不清（iOS Tab Bar 有一条 0.5px 顶部分割线）。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(accents.cardStroke)
                    )
                    NavigationBar(containerColor = Color.Transparent) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = {
                                    if (tab != t) {
                                        Haptics.selection(ctx)
                                        tab = t
                                    }
                                },
                                icon = {
                                    // contentDescription = null：可见的文字标签已提供可访问名称，
                                    // 再给图标一份会导致 TalkBack 念「车况,车况」（WCAG 2.5.3）
                                    Icon(t.icon, contentDescription = null, modifier = Modifier.size(22.dp))
                                },
                                label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                                colors = NavigationBarItemDefaults.colors(
                                    // ⚠️ M3 默认会画一个胶囊指示器（浅色 #DCE3E8 / 深色 #2A353D），
                                    // 是纯 Material 味，与整 App 的 iOS 语言冲突。
                                    // iOS 的选中态是**图标与文字同色**，没有胶囊。
                                    indicatorColor = Color.Transparent,
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        // ⚠️ iOS UITabBar 切换是**无位移**的（瞬时或极轻 cross-dissolve）。
        // v0.7.0 把 12.5% 改成了固定 16dp 位移，注释说"无位移"、实现却是有位移 ——
        // 注释与实现相反，比注释错更糟。v0.7.1 彻底去掉位移，只留交叉淡入淡出。
        AnimatedContent(
            targetState = tab,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 页面内容 = 毛玻璃的「模糊源」；底部导航栏是它的兄弟节点，作模糊效果。
                // ⚠️ haze 与 hazeChild 不可互为父子，所以源必须挂在内容区而不是根容器。
                .haze(LocalHazeState.current),
            transitionSpec = {
                fadeIn(tween(140)) togetherWith fadeOut(tween(120))
            },
            label = "tabSwitch",
        ) { current ->
            when (current) {
                Tab.STATUS -> StatusScreen(settings, Modifier.fillMaxSize(), statusStore)
                Tab.CHARGES -> ChargesScreen(settings, Modifier.fillMaxSize())
                Tab.DRIVES -> DrivesScreen(settings, Modifier.fillMaxSize())
                Tab.STATS -> StatsScreen(settings, Modifier.fillMaxSize())
                Tab.SETTINGS -> SettingsScreen(settings, Modifier.fillMaxSize())
            }
        }
    }
}
