package com.teslamate.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.teslamate.app.AppLatest
import com.teslamate.app.AppSettings
import com.teslamate.app.BuildConfig
import com.teslamate.app.Haptics
import com.teslamate.app.StatusCache
import com.teslamate.app.settingsFlow
import com.teslamate.app.saveSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    // ⚠️ v0.13.0（iOS 交互评审 P0-1 / P0-2）：记录页的详情选中态也提到 App 级。
    //   - 切 Tab 再回来，详情页**不会凭空消失**（iOS：每个 Tab 持有自己的导航栈）；
    //   - 「点当前 Tab = 回到根」才有地方可复位。
    var recordSelection by remember { mutableStateOf<RecordSelection?>(null) }

    // ⚠️ v0.20.0（iOS IA 评审 P1-1）：**设置页的二级页路由提到 App 级**。
    //
    // 存的是 `SettingsRoute` 的 **name 字符串**而不是枚举本身 ——
    // `rememberSaveable` 只能自动保存基本类型，枚举不行。
    // 用 name 既能保存，又在 `valueOf` 失败时安全回落（见下面的 runCatching）。
    //
    // 放在 App 级的理由与 `recordSelection` 相同：
    // ① 切 Tab 再回来，二级页还在（iOS：每个 Tab 持有自己的导航栈）；
    // ② 「点当前 Tab = 回到根」才有地方可复位；
    // ③ 401 横幅与保养卡的箭头需要**深链到指定二级页**（见 `onOpenSettings`）。
    var settingsRoute by rememberSaveable { mutableStateOf<String?>(null) }

    // ⚠️ v0.16.0（车队运营评审 P0-2）：**车辆列表提到 App 级**。
    //
    // 原来只有设置页会拉车辆列表，其它页面根本不知道「有几台车、
    // 现在看的是哪台」—— 于是「记录」页顶栏只有「记录」两个字，
    // 用户可能以为在看 A 车、实际是 B 车（读错数据的风险）。
    //
    // 现在顶栏放一颗切换芯片（仅多车时出现），列表在这里拉一次、全 App 共用。
    var cars by remember { mutableStateOf<List<com.teslamate.app.Car>>(emptyList()) }
    LaunchedEffect(settings?.configured) {
        val s = settings ?: return@LaunchedEffect
        if (!s.configured) return@LaunchedEffect
        val api = s.api() ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { api.cars() } }
            .onSuccess { cars = it }
            .onFailure { /* 车辆列表拿不到就不显示芯片，不影响主流程 */ }
    }

    // ⚠️ v0.16.0：切车。
    //
    // ① 保存新的 carId（三个页面的 LaunchedEffect(settings) 会自动重新加载数据）；
    // ② **清空记录页的选中态** —— 否则在 A 车的行程详情页切到 B 车，
    //    标题还写着 A 车那条行程，数据张冠李戴。
    val scope = rememberCoroutineScope()
    val onCarChange: (Int) -> Unit = { id ->
        val s = settings
        if (s != null && s.carId != id) {
            recordSelection = null
            scope.launch { ctx.saveSettings(s.copy(carId = id)) }
        }
    }

    LaunchedEffect(Unit) {
        ctx.settingsFlow().collect { settings = it }
    }

    // ⚠️ v0.20.0（iOS IA 评审 P1-3）：**深链的落点在 MainScaffold 里实现** ——
    // 那里才有 tab 的 setter 与 onTabChange。

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
                        recordSelection = recordSelection,
                        onRecordSelectionChange = { recordSelection = it },
                        cars = cars,
                        onCarChange = onCarChange,
                        // ⚠️ v0.20.0：设置页二级页状态**传参**给 MainScaffold ——
                        // 它是独立的 @Composable 函数，拿不到 TeslaMateApp 的局部变量。
                        settingsRoute = settingsRoute,
                        onSettingsRouteChange = { settingsRoute = it },
                        onTabChange = { t ->
                            // ⚠️ v0.13.0（iOS 交互评审 P0-2）：**点当前已选中的 Tab = 回到根**。
                            //
                            // 原来 `if (t != tab) { ... tab = t }` 把「点当前 Tab」整个吞掉了 ——
                            // 零反馈、零动作。而用户看到「记录」是选中态，
                            // 理所当然会去点它想回到列表，
                            // 唯一的出路是左上角那个 20dp 的箭头。
                            //
                            // iOS 惯例（UITabBarController）：再点一次当前 Tab → 该栈 pop 到根。
                            if (t == tab) {
                                when {
                                    tab == Tab.RECORDS && recordSelection != null -> {
                                        Haptics.selection(ctx)
                                        recordSelection = null   // 反向 push 动画由 AnimatedContent 自动播
                                    }
                                    // ⚠️ v0.20.0：设置页的二级页同样要能「回根」。
                                    tab == Tab.SETTINGS && settingsRoute != null -> {
                                        Haptics.selection(ctx)
                                        settingsRoute = null
                                    }
                                }
                            } else {
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
    recordSelection: RecordSelection?,
    onRecordSelectionChange: (RecordSelection?) -> Unit,
    cars: List<com.teslamate.app.Car> = emptyList(),
    onCarChange: (Int) -> Unit = {},
    /** ⚠️ v0.20.0：设置页二级页路由（null = 根页）。 */
    settingsRoute: String? = null,
    onSettingsRouteChange: (String?) -> Unit = {},
    onTabChange: (Tab) -> Unit,
) {
    val ctx = LocalContext.current

    // ⚠️ v0.20.0（iOS IA 评审 P1-3）：**深链到设置页的指定二级页** ——
    // 原来所有「去设置」出口都只是切 Tab —— 用户从 401 横幅点过去，
    // 落在设置页**顶部**，而他真正要改的东西（地址/令牌）在第一组、
    // 保养基准在第三组，都得自己找。现在直接落到指定二级页。
    val openSettingsRoute: (SettingsRoute?) -> Unit = { r ->
        // ⚠️ 已经在设置页时**不要**走 onTabChange ——
        // 它的语义是「切 Tab」，而「点当前 Tab」会被解释成「回到根」，
        // 那样深链刚落地就被自己弹回根页。
        if (tab != Tab.SETTINGS) {
            onTabChange(Tab.SETTINGS)
        }
        onSettingsRouteChange(r?.name)
    }
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
                    // ⚠️ v0.14.0（苹果 HIG P0-3 + 新手车主 P1-4）：
                    // **栏顶渐隐** —— 内容不再被底栏「切」一刀。
                    //
                    // 列表的滚动视口到 Tab 栏顶边为止，被切掉一半的字看起来像
                    // App 坏了（2.0 倍字号下尤其明显：「平均能耗 181 Wh/km」
                    // 被拦腰切开）。iOS 的做法是在栏上方叠一段渐变，
                    // 让内容「淡出」而不是「断掉」。
                    //
                    // 和 v0.13.0 给详情页顶部加的那段渐隐是同一个道理 ——
                    // 滚动容器的两个边缘都必须软化。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, accents.glassTint)
                                )
                            )
                    )
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
                                // ⚠️ v0.20.0（a11y 评审 P0-2）：**合并子节点语义 + 显式名称** ——
                                // uiautomator dump 里 4 个底栏 Tab 全是
                                // 「clickable=true 且 text/content-desc 都为空」的 View
                                // （文字落在不可聚焦的子 TextView 上），
                                // TalkBack 聚焦到底栏读不出是哪个 Tab —— 导航直接不可用。
                                //
                                // ⚠️ 注意：**空 lambda 的 `semantics(mergeDescendants = true)` 无效** ——
                                // 不设置任何属性就不会创建语义节点，合并无处发生。
                                // 必须真的设 contentDescription。
                                modifier = Modifier.semantics(mergeDescendants = true) {
                                    contentDescription = t.label
                                },
                                icon = {
                                    // contentDescription = null：可见的文字标签已提供可访问名称，
                                    // 再给图标一份会导致 TalkBack 念「车况,车况」（WCAG 2.5.3）
                                    //
                                    // ⚠️ v0.17.0（iOS 动效专项评审 P2-3）：**选中图标微动** ——
                                    // scale 1.0 → 1.08，spring(dampingRatio 0.8 / stiffness 900)。
                                    // 只给选中的那个；未选中的一律 1.0。
                                    val iconScale by animateFloatAsState(
                                        targetValue = if (tab == t) 1.08f else 1f,
                                        animationSpec = Motion.IconPop,
                                        label = "tabIconScale",
                                    )
                                    Icon(
                                        t.icon,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(22.dp)
                                            .graphicsLayer {
                                                scaleX = iconScale
                                                scaleY = iconScale
                                            },
                                    )
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
        //
        // ⚠️ v0.13.0（iOS 交互评审 P0-1）：用 [SaveableStateHolder] 保住每个 Tab 的状态。
        //
        // 原来 Tab 切换用 `AnimatedContent(targetState = tab)` ——
        // 切走的页面在淡出结束后**被 dispose**，于是列表的滚动位置全丢：
        // 在记录页滚到 10 月，切到「用量」再切回来，又回到顶部。
        // iOS 惯例：每个 Tab 持有自己的导航栈，切回来时**栈和滚动位置原样保留**。
        val stateHolder = rememberSaveableStateHolder()
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
            stateHolder.SaveableStateProvider(current.name) {
                when (current) {
                    Tab.STATUS -> StatusScreen(
                        settings = settings,
                        modifier = Modifier.fillMaxSize(),
                        store = statusStore,
                        // 令牌失效时给用户一个直达设置的出口（见 ApiErrorKind.AUTH）
                        // ⚠️ v0.20.0：直接落到「服务器」二级页 —— 401 的原因就是地址/令牌，
                        // 落点与原因一致，不用让用户自己找。
                        onOpenSettings = { openSettingsRoute(SettingsRoute.Server) },
                        // ⚠️ v0.20.0：保养卡 → 保养二级页（原来两者共用同一个出口）。
                        onOpenMaintenance = { openSettingsRoute(SettingsRoute.Maintenance) },
                        // ⚠️ v0.16.0：顶栏车切换芯片
                        cars = cars,
                        onCarChange = onCarChange,
                    )
                    Tab.RECORDS -> RecordsScreen(
                        settings = settings,
                        modifier = Modifier.fillMaxSize(),
                        selection = recordSelection,
                        onSelectionChange = { onRecordSelectionChange(it) },
                        // ⚠️ v0.14.0：令牌失效时给「前往设置」的出口
                        // （原来只有车况页有，停在记录页会无限次点「重试」）。
                        onOpenSettings = { openSettingsRoute(SettingsRoute.Server) },
                        // ⚠️ v0.16.0：顶栏车切换芯片
                        cars = cars,
                        onCarChange = onCarChange,
                    )
                    Tab.USAGE -> StatsScreen(
                        settings,
                        Modifier.fillMaxSize(),
                        onOpenSettings = { openSettingsRoute(SettingsRoute.Server) },
                        cars = cars,
                        onCarChange = onCarChange,
                    )
                    Tab.SETTINGS -> SettingsScreen(
                        settings,
                        Modifier.fillMaxSize(),
                        // ⚠️ v0.20.0（iOS IA 评审 P1-4）：车辆列表由 App 级传入，
                        // 设置页不再自己拉一遍（失败时静默、且整组会消失）。
                        cars = cars,
                        route = settingsRoute?.let { name ->
                            runCatching { SettingsRoute.valueOf(name) }.getOrNull()
                        },
                        onRouteChange = { r -> onSettingsRouteChange(r?.name) },
                    )
                }
            }
        }
    }
}
