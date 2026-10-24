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
import androidx.compose.material.icons.filled.History
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
import com.teslamate.app.StatusCache
import com.teslamate.app.settingsFlow
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 底部导航的 4 个 Tab。
 *
 * ⚠️ v0.8.5：**5 → 4**。
 *
 * IA 复核找到一处硬伤：「近 30 天」这份数据在**充电页顶、行程页顶、统计页**出现
 * **3 次**，还带着**两个不同的名字**（「最近 30 天」/「月 汇总」）——
 * 同一份数据被抓三次、被叫两个名字，这不是"信息丰富"，是**信息自相矛盾**。
 *
 * 合并「充电 + 行程 → 记录」的理由不是"Tab 太多"（M3 官方支持 5 项），
 * 而是它们**本来就是同一种东西**：按月分组的历史事件列表 + 详情推入。
 * 段控件（充电 | 行程）比两个平级 Tab 更诚实地表达了它们的从属关系。
 *
 * 「用量」改名（统计 → 用量）：它下面装的是里程/能耗/充入量这些**量**，
 * 而「记录」装的是**事件**。原来的「统计」和「记录」在语义上会打架。
 */
enum class Tab(val label: String, val icon: ImageVector) {
    STATUS("车况", Icons.Filled.DirectionsCar),
    RECORDS("记录", Icons.Filled.History),
    USAGE("用量", Icons.Filled.QueryStats),
    SETTINGS("设置", Icons.Filled.Settings),
}

@Composable
fun TeslaMateApp() {
    val ctx = LocalContext.current
    var settings by remember { mutableStateOf<AppSettings?>(null) }
    var updateInfo by remember { mutableStateOf<AppLatest?>(null) }
    // ⚠️ v0.9.0：Tab 状态提到 App 级。
    // 原因：令牌失效时，车况页的错误横幅要给一个「前往设置」按钮 ——
    // 而设置页是第 4 个 Tab，横幅在第 1 个 Tab 里，
    // 不把这个状态提上来就没法跳过去（可靠性复核：「401 无出口」）。
    var tab by remember { mutableStateOf(Tab.STATUS) }

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
        //
        // ⚠️ v0.8.1 封顶 1.15 → v0.9.0 解封到 **完全放开**。
        //
        //   之前封顶的理由是：「车况页是仪表盘布局，电量环 + 续航 + 里程在 360dp 宽屏上，
        //   2.0 倍时纵向会挤爆。**完全满足 1.4.4 需要把仪表盘改成可弹性布局，属遗留项。**」
        //   现在车况页首屏已改成**弹性布局**（大字号时电量环与续航上下排，而不是硬挤成一行），
        //   这条遗留项就解除了 —— 再封顶就等于**明知有解却不给用户**（WCAG 1.4.4）。
        //
        //   ⚠️ 1.15 那次封顶最糟：把绝大多数用户的系统大字体档（1.15/1.2/1.3）**全部截断** ——
        //   用户在系统里把字调到最大，App 里几乎没变化。
        //
        // 这里**不再覆盖 LocalDensity** —— 直接用系统的 fontScale。
        // 需要感知字号的控件（比如电量环）自己读 LocalDensity.current.fontScale。
        CompositionLocalProvider(
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
                    MainScaffold(
                        settings = s,
                        tab = tab,
                        onTabChange = { t ->
                            if (t != tab) {
                                Haptics.selection(ctx)
                                tab = t
                            }
                        },
                    )
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
private fun MainScaffold(
    settings: AppSettings,
    tab: Tab,
    onTabChange: (Tab) -> Unit,
) {
    val ctx = LocalContext.current
    // 车况数据提到 App 级：切 Tab 时页面会被 AnimatedContent 销毁，
    // 数据放在页面里就会连同骨架屏一起重放一次（等于重新开一次 App）。
    //
    // ⚠️ v0.9.0：这里同步把**落盘缓存**也填上（hydrate）——
    // 冷启动第一帧就有数字，不闪骨架屏；服务器挂着的时候也还能看到上次已知。
    val statusStore = remember {
        StatusStore(StatusCache(ctx)).apply { hydrate() }
    }
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
                    NavigationBar(
                        // ⚠️ v0.9.2（苹果视觉评审 P2-9）：**深色下 Tab Bar 需要「栏」的属性**。
                        // 原来两种模式都是 Color.Transparent（靠毛玻璃透出内容），
                        // 但深色页底是 #000000，玻璃叠上去还是 #000000 ——
                        // Tab Bar 与页面**完全融在一起**，失去「这是一条栏」的读感
                        // （浅色下是 #FFFFFF vs #F1F1F6，分离很清楚）。
                        // 现在：深色给一层卡片面色，浅色保持透明（保留毛玻璃质感）。
                        containerColor = if (isDarkTheme()) {
                            MaterialTheme.colorScheme.surfaceContainer
                        } else {
                            Color.Transparent
                        },
                    ) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { onTabChange(t) },
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
                Tab.STATUS -> StatusScreen(
                    settings = settings,
                    modifier = Modifier.fillMaxSize(),
                    store = statusStore,
                    // 令牌失效时给用户一个直达设置的出口（见 ApiErrorKind.AUTH）
                    onOpenSettings = { onTabChange(Tab.SETTINGS) },
                )
                Tab.RECORDS -> RecordsScreen(settings, Modifier.fillMaxSize())
                Tab.USAGE -> StatsScreen(settings, Modifier.fillMaxSize())
                Tab.SETTINGS -> SettingsScreen(settings, Modifier.fillMaxSize())
            }
        }
    }
}
