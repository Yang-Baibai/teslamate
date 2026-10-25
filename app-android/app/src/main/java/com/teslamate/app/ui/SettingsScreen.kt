@file:OptIn(ExperimentalMaterial3Api::class)

package com.teslamate.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import com.teslamate.app.fmtThousands
import com.teslamate.app.saveSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页的二级页路由。
 *
 * ⚠️ **v0.20.0（iOS IA 评审 P1-1）新增**。
 *
 * 原来设置页是**一张长表单**：5 张 `SectionCard` 串在一条滚动里，
 * 服务器地址、访问令牌、四个保养字段、帮助条目全铺在根页上。
 *
 * iOS 设置页的铁律是**根页只放行**：
 * 每行 = 一个设置项 + **当前值的摘要**（灰字）+ 右侧 `›`；
 * 点进去才是编辑界面。区别不只是样子 —— 长表单让用户面对一张
 * **没有边界的表单**，不知道哪些已经生效、哪些还没保存。
 */
enum class SettingsRoute {
    /** 服务器地址 / 访问令牌 */
    Server,

    /** 选择当前车辆 */
    Vehicles,

    /** 保养与轮胎基准 */
    Maintenance,

    /**
     * ⚠️ **v0.25.0（第 16 轮充电成本专家 P1-5）：电价与成本。**
     *
     * 官方 Tesla App 能填住宅电价，但它**给不出「每公里成本」** ——
     * 它不知道你的真实行驶里程口径。我们有。
     */
    Rates,

    /** 怎么找到地址和令牌 */
    Help,

    /**
     * ⚠️ v0.22.0：服务器状态。
     *
     * 自托管服务的体验，八成体现在**它坏的时候** ——
     * 而「我的服务器现在是什么状态」原本在 App 里**无处可查**。
     */
    ServerStatus,

    /** 版本与更新说明 */
    About,
}

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
 *
 * ⚠️ **v0.20.0（iOS IA 评审 P1-1）**：改成**根页（分组列表）+ 5 个二级页**。
 * 详见 [SettingsRoute]。
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    modifier: Modifier = Modifier,
    /**
     * 车辆列表。
     *
     * ⚠️ v0.20.0（P1-4）：由 `AppRoot` 传入，**不再自己拉一次**。
     * 原来这里声明了一份本地 `cars` 并在 `LaunchedEffect` 里再拉一遍 ——
     * 同一份数据两个来源，且失败时**静默**（没有 onFailure），
     * 于是令牌失效时「选择车辆」整组直接消失 ——
     * 而令牌失效正是用户被引导到设置页的时刻，他到这里却看不到任何车辆信息。
     */
    cars: List<Car> = emptyList(),
    /** 当前二级页；null = 根页。 */
    route: SettingsRoute? = null,
    /** 切换二级页。传 null 回根页。 */
    onRouteChange: (SettingsRoute?) -> Unit = {},
    /** ⚠️ v0.22.0：给「服务器状态」页用（拉 /api/v1/health）。 */
    api: com.teslamate.app.Api? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // ⚠️ 编辑态放在**顶层**：切到二级页再切回来，草稿不丢。
    // （放在子页面里的话，AnimatedContent 换 targetState 会 dispose 旧内容，
    //   草稿随之消失 —— 用户填了一半被「车辆」页吸引走，回来发现白填了。）
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
    var showToken by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testOk by remember { mutableStateOf<Boolean?>(null) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    // ⚠️ v0.20.0（P0-3）：保存反馈**按语义域分开** ——
    // 服务器组保存完，不该让「保养」页也显示「已保存」。
    var serverSaved by remember { mutableStateOf(false) }
    var baselineSaved by remember { mutableStateOf(false) }
    // ⚠️ v0.25.0：电价编辑态 + 独立的保存反馈（与服务器/保养分开）。
    var rates by remember(settings) { mutableStateOf(settings.currentRates) }
    var ratesSaved by remember { mutableStateOf(false) }
    /** 车辆列表兜底：App 级拉取失败时，用户能在这里手动重试。 */
    var reloadTick by remember { mutableStateOf(0) }
    var listError by remember { mutableStateOf<String?>(null) }

    // ⚠️ v0.20.0（iOS IA 评审 P0-2）：**保存动作拆成三个互不越界的函数**。
    //
    // 原来只有一个 `save()`，无条件把本地编辑态 `url`/`token`/`carId` **连同**保养字段
    // 一起写进 `AppSettings`。于是：
    //
    //   用户在「服务器连接」里把地址手滑改成 http://10.0.2.9:8090（**没点测试**）
    //   → 往下滚 → 点「保存保养与轮胎设置」
    //   → 新地址 + 旧令牌一起落盘 → 车况/记录/用量三页同时 401
    //   → 而用户完全不知道是自己刚才那一按导致的。
    //
    // 这等于在 v0.9.0 建立「401 必须有出口」机制之后，**又从背后捅了一刀**。
    //
    // 现在按**语义域**拆开：每个保存动作只写自己那组字段，
    // 其余字段从 `settings`（已落盘的事实源）原样带回。
    //
    // ⚠️ 注意用的是 `settings.copy(...)` 而不是重新 `AppSettings(...)` ——
    // 后者会把没提到的字段重置成默认值（token 变空、基准归零）。
    /** 只写服务器地址 / 令牌。保养基准与 perCar 原样带回。 */
    fun saveServer() {
        scope.launch {
            ctx.saveSettings(
                settings.copy(
                    serverUrl = url,
                    token = token,
                    carId = carId,
                )
            )
        }
        serverSaved = true
        baselineSaved = false
    }

    /** 只写保养 / 轮胎基准。服务器地址与令牌原样带回（**绝不碰**）。 */
    fun saveBaselines() {
        scope.launch {
            // ⚠️ 空串 = 不填 = 不启用提醒（0 表示未设置）
            val b = com.teslamate.app.CarBaselines(
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
                settings.copy(
                    serviceOdoKm = b.serviceOdoKm,
                    serviceIntervalKm = b.serviceIntervalKm,
                    tireOdoKm = b.tireOdoKm,
                    tireIntervalKm = b.tireIntervalKm,
                    perCar = next,
                )
            )
        }
        baselineSaved = true
        serverSaved = false
    }

    /**
     * ⚠️ **v0.25.0（第 16 轮充电成本专家 P1-5）：只写电价。**
     *
     * 与 [saveServer] / [saveBaselines] 同样的纪律 ——
     * **只写自己那组字段**，其余从 `settings` 原样带回，
     * 绝不碰服务器地址、令牌与保养基准。
     */
    fun saveRates(r: com.teslamate.app.ChargeRates) {
        scope.launch {
            val next = settings.ratePerCar.toMutableMap()
            if (r.configured) {
                next[carId] = r
            } else {
                next.remove(carId)
            }
            ctx.saveSettings(settings.copy(ratePerCar = next))
        }
        ratesSaved = true
        serverSaved = false
        baselineSaved = false
    }

    /** 只切当前车。服务器地址与令牌原样带回（**绝不碰**）。 */
    fun saveCarSelection() {        scope.launch {
            ctx.saveSettings(settings.copy(carId = carId))
        }
        serverSaved = false
        baselineSaved = false
    }

    /**
     * 测一次连接。
     *
     * ⚠️ v0.20.0：原来这段逻辑在两个按钮的 onClick 里**各抄了一份**，
     * 改一处漏一处 —— 现在抽成一个函数，两个按钮共用。
     */
    suspend fun runTest() {
        testing = true
        testOk = null
        testMsg = null
        val api = Api.create(url, token)
        when {
            api == null -> {
                testOk = false
                testMsg = "地址格式无效（需要 http:// 或 https:// 开头）"
            }
            else -> runCatching { withContext(Dispatchers.IO) { api.cars() } }
                .onSuccess {
                    testOk = true
                    // ⚠️ v0.24.0（性能与稳定性评审 P0-1）：**空列表不能调 first()**。
                    //
                    // 原来写的是：
                    //     if (it.none { car -> car.id == carId }) { carId = it.first().id }
                    // 而 `none{}` 对**空列表**返回 true → `it.first()` 抛
                    // NoSuchElementException。
                    //
                    // 异常逃出 `runCatching`（它只包住了 withContext 那段，
                    // `.onSuccess` 在覆盖范围之外）→ 未捕获 → **App 崩溃**。
                    //
                    // 触发条件：服务器上没有车（TeslaMate 侧没配车 / 换了服务器 /
                    // 多车环境清空）→ 用户在设置页点「测试连接」→ 崩。
                    //
                    // 现在：空列表给**可读提示**，不崩。
                    val first = it.firstOrNull()
                    if (it.isEmpty()) {
                        testMsg = "连接成功，但服务器上还没有车 —— 请先在 TeslaMate 里添加车辆"
                    } else {
                        testMsg = "连接成功，发现 ${it.size} 台车"
                        if (first != null && it.none { car -> car.id == carId }) {
                            carId = first.id
                        }
                    }
                }
                .onFailure {
                    testOk = false
                    testMsg = it.message ?: "连接失败"
                }
        }
        testing = false
    }

    // ⚠️ v0.20.0（P1-4）：车辆列表的兜底重试 ——
    // App 级拉取失败时（令牌失效 / 服务器离线），这个页给一个「重试」出口。
    LaunchedEffect(reloadTick) {
        if (reloadTick == 0) return@LaunchedEffect
        val api = settings.api() ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { api.cars() } }
            .onSuccess { listError = null }
            .onFailure { listError = it.message ?: "连接失败" }
    }

    // ⚠️ v0.20.0：**唯一的 AnimatedContent** ——
    // 根页与二级页之间的切换全部由这一层负责，
    // 所以**永远不会出现嵌套 AnimatedContent**（那个才是之前担心的风险源）。
    AnimatedContent(
        targetState = route,
        modifier = modifier,
        transitionSpec = { pushDetailTransition(this) },
        label = "settingsRoute",
    ) { r ->
        when (r) {
            null -> SettingsRootPage(
                settings = settings,
                cars = cars,
                onOpen = { onRouteChange(it) },
            )
            SettingsRoute.Server -> {
                BackHandler { onRouteChange(null) }
                SettingsServerPage(
                    url = url,
                    token = token,
                    showToken = showToken,
                    onUrlChange = { url = it; testOk = null; testMsg = null; serverSaved = false },
                    onTokenChange = { token = it; testOk = null; testMsg = null; serverSaved = false },
                    onToggleToken = { showToken = !showToken },
                    testing = testing,
                    testOk = testOk,
                    testMsg = testMsg,
                    saved = serverSaved,
                    onSaveAndTest = {
                        saveServer()
                        scope.launch { runTest() }
                    },
                    onTestOnly = { scope.launch { runTest() } },
                    onBack = { onRouteChange(null) },
                )
            }
            SettingsRoute.Vehicles -> {
                BackHandler { onRouteChange(null) }
                SettingsVehiclesPage(
                    cars = cars,
                    carId = carId,
                    error = listError,
                    onSelect = { id ->
                        carId = id
                        saveCarSelection()
                    },
                    onRetry = { reloadTick++ },
                    onBack = { onRouteChange(null) },
                )
            }
            SettingsRoute.Maintenance -> {
                BackHandler { onRouteChange(null) }
                SettingsMaintenancePage(
                    carName = cars.firstOrNull { it.id == carId }?.name,
                    serviceOdo = serviceOdo,
                    serviceInterval = serviceInterval,
                    tireOdo = tireOdo,
                    tireInterval = tireInterval,
                    onServiceOdo = { serviceOdo = it.trim().toDoubleOrNull() ?: 0.0; baselineSaved = false },
                    onServiceInterval = { serviceInterval = it.filter { c -> c.isDigit() }; baselineSaved = false },
                    onTireOdo = { tireOdo = it.trim().toDoubleOrNull() ?: 0.0; baselineSaved = false },
                    onTireInterval = { tireInterval = it.filter { c -> c.isDigit() }; baselineSaved = false },
                    dirty = settings.currentBaselines.let {
                        serviceOdo != it.serviceOdoKm ||
                            (serviceInterval.trim().toIntOrNull() ?: 20000) != it.serviceIntervalKm ||
                            tireOdo != it.tireOdoKm ||
                            (tireInterval.trim().toIntOrNull() ?: 40000) != it.tireIntervalKm
                    },
                    saved = baselineSaved,
                    onSave = { saveBaselines() },
                    onBack = { onRouteChange(null) },
                )
            }
            SettingsRoute.Help -> {
                BackHandler { onRouteChange(null) }
                SettingsHelpPage(onBack = { onRouteChange(null) })
            }
            SettingsRoute.ServerStatus -> {
                BackHandler { onRouteChange(null) }
                SettingsServerStatusPage(
                    api = api,
                    onBack = { onRouteChange(null) },
                )
            }
            SettingsRoute.Rates -> {
                BackHandler { onRouteChange(null) }
                SettingsRatesPage(
                    rates = rates,
                    carName = cars.firstOrNull { it.id == carId }?.name,
                    onChange = { rates = it; ratesSaved = false },
                    onSave = { saveRates(rates) },
                    saved = ratesSaved,
                    onBack = { onRouteChange(null) },
                )
            }
            SettingsRoute.About -> {
                BackHandler { onRouteChange(null) }
                SettingsAboutPage(onBack = { onRouteChange(null) })
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
// 根页：iOS 分组列表
// ══════════════════════════════════════════════════════════════════

@Composable
private fun SettingsRootPage(
    settings: AppSettings,
    cars: List<Car>,
    onOpen: (SettingsRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        TopAppBar(title = { ScreenTitle("设置") })
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard("连接", Modifier.animateItem()) {
                    SettingRow(
                        title = "服务器",
                        subtitle = serverSummary(settings.serverUrl),
                    ) { onOpen(SettingsRoute.Server) }
                    RowDivider()
                    SettingRow(
                        title = "访问令牌",
                        // ⚠️ **永不回显 token 本身** —— 只说「设了没」。
                        subtitle = if (settings.token.isBlank()) "未设置" else "已设置",
                    ) { onOpen(SettingsRoute.Server) }
                    RowDivider()
                    SettingRow(
                        title = "当前车辆",
                        subtitle = carSummary(cars, settings.carId),
                    ) { onOpen(SettingsRoute.Vehicles) }
                }
            }
            item {
                SectionCard("提醒", Modifier.animateItem()) {
                    SettingRow(
                        title = "保养与轮胎",
                        subtitle = baselineSummary(settings.currentBaselines),
                    ) { onOpen(SettingsRoute.Maintenance) }
                }
            }
            item {
                SectionCard("成本", Modifier.animateItem()) {
                    // ⚠️ v0.25.0：**电价**入口 ——
                    // 填了之后用量页就能给「每公里成本」（官方 App 给不了这个数）。
                    SettingRow(
                        title = "电价与成本",
                        subtitle = if (settings.currentRates.configured) {
                            // ⚠️ v0.26.0：写「交流慢充 / 直流桩」而不是「家充 / 公共桩」——
                            // App 分不出哪场交流是在自家桩上充的（geofences 0 行），
                            // 写「家充」等于**假装它知道**。
                            "交流慢充 ${settings.currentRates.homeYuanPerKwh.fmtRate("元/度")}" +
                                " · 直流桩 ${settings.currentRates.publicYuanPerKwh.fmtRate("元/度")}"
                        } else {
                            "填上就能算每公里成本"
                        },
                    ) { onOpen(SettingsRoute.Rates) }
                }
            }
            item {
                SectionCard("帮助", Modifier.animateItem()) {
                    SettingRow(
                        title = "怎么找到地址和令牌？",
                        subtitle = "局域网 IP · Tailscale",
                    ) { onOpen(SettingsRoute.Help) }
                }
            }
            item {
                SectionCard("状态", Modifier.animateItem()) {
                    // ⚠️ v0.22.0：**服务器状态**入口 ——
                    // 自托管服务坏的时候，车主需要知道「是服务器的问题还是车的问题」。
                    SettingRow(
                        title = "服务器状态",
                        subtitle = "数据库 · 实时状态 · 最后采集时间",
                    ) { onOpen(SettingsRoute.ServerStatus) }
                }
            }
            item {
                SectionCard("关于", Modifier.animateItem()) {
                    // ⚠️ v0.20.0（P2-2）：**不显示 build number** ——
                    // iOS 从不向用户暴露版本号之外的内部编号，
                    // 两个版本号并列反而让人困惑「哪个才是真的」。
                    SettingRow(
                        title = "版本",
                        subtitle = "v${BuildConfig.VERSION_NAME}",
                        onClick = null,
                        showChevron = false,
                    )
                }
            }
        }
    }
}

/** 服务器摘要：去掉协议前缀（用户填的时候才需要协议）。 */
private fun serverSummary(raw: String): String {
    if (raw.isBlank()) return "未设置"
    return raw.removePrefix("http://").removePrefix("https://")
}

/** 当前车辆摘要：取不到就说取不到，不编。 */
private fun carSummary(cars: List<Car>, carId: Int): String {
    val car = cars.firstOrNull { it.id == carId } ?: return "未获取到车辆列表"
    return listOfNotNull(car.name, car.marketing_name).joinToString(" · ")
}

/** 保养基准摘要：没配就明说没配。 */
private fun baselineSummary(b: com.teslamate.app.CarBaselines): String {
    if (b.serviceOdoKm <= 0) return "未设置"
    // ⚠️ 千分位 —— 全 App 统一（v0.14.0 单位审校）。
    // 「79000 公里」和别处的「101,455 公里」并列时，后者有分隔符、
    // 前者没有，用户会以为是两种量级的数。
    return "上次保养 ${b.serviceOdoKm.fmtThousands()} 公里 · 每 ${b.serviceIntervalKm.toDouble().fmtThousands()} 公里"
}

/**
 * 行间细分隔线。
 *
 * iOS 分组列表的行之间有一条极细的线，但**只从文字起始处开始**
 * （左侧留出图标位的缩进），不是通栏。
 */
@Composable
private fun RowDivider() {
    val accents = LocalAccentColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(0.5.dp)
            .background(accents.cardStrokeEnd)
    )
}

// ══════════════════════════════════════════════════════════════════
// 二级页
// ══════════════════════════════════════════════════════════════════

@Composable
private fun SettingsServerPage(
    url: String,
    token: String,
    showToken: Boolean,
    onUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onToggleToken: () -> Unit,
    testing: Boolean,
    testOk: Boolean?,
    testMsg: String?,
    saved: Boolean,
    onSaveAndTest: () -> Unit,
    onTestOnly: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SecondaryTitleBar(title = "服务器", backLabel = "设置", onBack = onBack)
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
                        onValueChange = onUrlChange,
                        placeholder = "http://192.168.1.100:8090",
                    )
                    IosField(
                        label = "访问令牌 (API_TOKEN)",
                        value = token,
                        onValueChange = onTokenChange,
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardType = KeyboardType.Password,
                    )
                    // ⚠️ v0.20.0（iOS IA 评审 P2-3）：**开关移到行尾**。
                    // iOS 的开关恒在行尾（left = 标签，right = 开关），
                    // 原来开关在左、文字在右 —— 与 iOS 设置页的视觉方向相反，
                    // 扫读时「这行是什么」和「这行是什么状态」的顺序是反的。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleToken() }
                            // ⚠️ v0.20.0（a11y 评审 P0-2）：**合并子节点语义** ——
                            // 否则 uiautomator 里这一行是「可点击但没有名字」的 View，
                            // TalkBack 聚焦上去读不出这是「显示令牌」。
                            .semantics(mergeDescendants = true) {
                                contentDescription = "显示令牌"
                            }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            "显示令牌",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IosSwitch(
                            checked = showToken,
                            onCheckedChange = { onToggleToken() },
                            label = "显示令牌",
                        )
                    }
                    // ⚠️ v0.20.0（P0-1）：**两个按钮改成纵向排列**。
                    //
                    // 原来横排（各 `weight(1f)` + 固定 `.height(44.dp)`）：
                    // 屏宽 1080px @2.625x → 卡片内容 347dp → 每按钮 183dp
                    // → 文字区只剩 ~331px；而「保存并测试」5 个全角字在 2.0 倍字号下
                    // 需要 ~397px —— **放不下**。
                    //
                    // 更糟的是 `.height(44.dp)` 是**硬约束**：溢出的末字被**直接裁掉**，
                    // 屏幕上显示成「保存并测」「仅测试连」，**连省略号都没有**。
                    // 这违反 WCAG 1.4.4（文字不得被裁剪）——
                    // 而且本机只有模拟器，真机用户几乎必然遇到。
                    //
                    // 现在：纵向排列，每个按钮占满整行 → 永远放得下；
                    // 高度用 `heightIn(min = 44.dp)` —— 最低 44dp 满足触达，
                    // 需要时（超大字号）**自动长高**而不是裁切。
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !testing && url.isNotBlank() && token.isNotBlank(),
                            onClick = onSaveAndTest,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                // ⚠️ M3 默认的 disabled 文字是 onSurface @38% alpha，
                                // 在浅色底上只有 ~2.2:1 —— 2 倍字号下几乎读不出来。
                                // 显式改成 onSurfaceVariant（≥4.5:1）。
                                disabledContainerColor = MaterialTheme.colorScheme.surface,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                // ⚠️ v0.20.0（a11y 评审 P0-2）：合并子节点语义，
                                // 否则按钮在无障碍树里是「有名字的空按钮」。
                                .semantics(mergeDescendants = true) {
                                    contentDescription = if (testing) "测试中" else "保存并测试"
                                },
                        ) {
                            if (testing) {
                                CircularProgressIndicator(Modifier.width(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(if (testing) "测试中…" else "保存并测试")
                        }
                        OutlinedButton(
                            enabled = !testing && url.isNotBlank() && token.isNotBlank(),
                            onClick = onTestOnly,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSurface,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp)
                                .semantics(mergeDescendants = true) {
                                    contentDescription = "仅测试连接"
                                },
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
                    if (saved) {
                        Text(
                            "已保存 ✓（其他页面会自动刷新）",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsVehiclesPage(
    cars: List<Car>,
    carId: Int,
    error: String?,
    onSelect: (Int) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SecondaryTitleBar(title = "当前车辆", backLabel = "设置", onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard("选择车辆", Modifier.animateItem()) {
                    if (cars.isEmpty()) {
                        // ⚠️ v0.20.0（P1-4）：车辆列表拿不到时**不能静默消失** ——
                        // 令牌失效正是用户被引导到设置页的时刻，
                        // 他到这里却什么都看不到，只能干瞪眼。
                        Text(
                            error ?: "未获取到车辆列表",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            onClick = onRetry,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 44.dp),
                        ) {
                            Text("重试")
                        }
                    } else {
                        // ⚠️ v0.9.2：M3 的 RadioButton（红圈红点）→ **整行可点 + 右侧对勾**。
                        // 这是 iOS 设置页的标准范式，命中区也从一个小圆点变成整行。
                        //
                        // ⚠️ v0.14.0（苹果 HIG P1）：**点选即保存**，删掉「保存车辆选择」按钮。
                        cars.forEach { c ->
                            val selected = carId == c.id
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(c.id) }
                                    // ⚠️ v0.20.0（a11y 评审 P0-2）：合并子节点语义 ——
                                    // 原来这一行在无障碍树里是「可点击但没有名字」。
                                    .semantics(mergeDescendants = true) {
                                        // ⚠️ `this.selected` 显式指向语义属性 ——
                                        // 外层有同名局部 val，直接写 `selected =` 会解析到它。
                                        this.selected = selected
                                    }
                                    .padding(vertical = 12.dp),
                            ) {
                                Text(
                                    listOfNotNull(c.name, c.marketing_name).joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                if (selected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        // ⚠️ 勾是**状态指示**，不是独立信息 ——
                                        // 选中状态已经写在行的 `selected` 语义上，
                                        // 再给图标一个 contentDescription 会念重复。
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                    // ⚠️ v0.16.0：基准按车隔离，得让用户知道。
                    Text(
                        "保养与轮胎基准按车分别保存。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsMaintenancePage(
    carName: String?,
    serviceOdo: Double,
    serviceInterval: String,
    tireOdo: Double,
    tireInterval: String,
    onServiceOdo: (String) -> Unit,
    onServiceInterval: (String) -> Unit,
    onTireOdo: (String) -> Unit,
    onTireInterval: (String) -> Unit,
    dirty: Boolean,
    saved: Boolean,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SecondaryTitleBar(title = "保养与轮胎", backLabel = "设置", onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                    "保养与轮胎提醒" + (carName?.let { " · $it" } ?: ""),
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
                        onValueChange = onServiceOdo,
                        keyboardType = KeyboardType.Number,
                    )
                    IosField(
                        label = "保养间隔（公里）",
                        value = serviceInterval,
                        onValueChange = onServiceInterval,
                        keyboardType = KeyboardType.Number,
                    )
                    IosField(
                        label = "上次换胎时的里程（公里）",
                        value = if (tireOdo > 0) tireOdo.toLong().toString() else "",
                        onValueChange = onTireOdo,
                        keyboardType = KeyboardType.Number,
                    )
                    IosField(
                        label = "轮胎更换间隔（公里）",
                        value = tireInterval,
                        onValueChange = onTireInterval,
                        keyboardType = KeyboardType.Number,
                    )
                    // ⚠️ v0.14.0（苹果 HIG P1）：**实心按钮**，不再是裸文字。
                    //
                    // ⚠️ v0.20.0（P0-3）：**有改动才用主色** ——
                    // 原来无论有没有改动都是中性 `surface` 填充，
                    // 与「保存并测试」权重相同，扫读时无法分辨哪是本组提交动作；
                    // 现在「有未保存改动」= 主色实心，「没有改动」= 中性禁用。
                    Button(
                        onClick = onSave,
                        enabled = dirty,
                        shape = RoundedCornerShape(12.dp),
                        // ⚠️ v0.16.0：**中性**，不是品牌红。
                        // 红色在这个 App 的语义里只表示**告警** ——
                        // 一个「保存提醒」的按钮用红块，是在把例行操作伪装成警报。
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (dirty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            contentColor = if (dirty) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            disabledContainerColor = MaterialTheme.colorScheme.surface,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = "保存保养与轮胎设置"
                            },
                    ) {
                        Text(
                            when {
                                saved -> "已保存 ✓"
                                dirty -> "保存保养与轮胎设置"
                                else -> "没有改动"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (saved && !dirty) {
                        Text(
                            "已保存 ✓（其他页面会自动刷新）",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHelpPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        SecondaryTitleBar(title = "怎么找到地址和令牌？", backLabel = "设置", onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(null, Modifier.animateItem()) {
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
        }
    }
}

/**
 * ⚠️ **v0.22.0（自托管运维评审 P1-1）：服务器状态页**。
 *
 * 自托管服务的体验，八成体现在**它坏的时候**。
 * 车主真正需要回答的问题是「**是服务器的问题，还是车的问题？**」——
 * 而 App 里原本**无处可查**（`/healthz` 只回一个 ok）。
 *
 * 这一页给四件事：
 * ① 一句**人话结论**（正常 / 数据库不可达 / Tesla 授权失效 / 车在休眠）；
 * ② 数据库连通性与延迟；
 * ③ Fleet 实时状态（四态）；
 * ④ 最后一次车况采集时间（TeslaMate 报上来的）。
 */
@Composable
private fun SettingsServerStatusPage(
    api: com.teslamate.app.Api?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var health by remember { mutableStateOf<com.teslamate.app.HealthDetail?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    suspend fun load() {
        loading = true
        err = null
        if (api == null) {
            err = "尚未配置服务器地址与令牌"
            loading = false
            return
        }
        runCatching {
            withContext(Dispatchers.IO) { api.health() }
        }.onSuccess {
            health = it
            loading = false
        }.onFailure {
            err = it.message ?: "连不上服务器"
            loading = false
        }
    }
    LaunchedEffect(Unit) { load() }

    Column(modifier) {
        SecondaryTitleBar(title = "服务器状态", backLabel = "设置", onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(modifier = Modifier.animateItem()) {
                    when {
                        loading -> Text(
                            "正在检查…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        err != null -> {
                            Text(
                                "连不上服务器 —— $err",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Text(
                                "请确认家里那台电脑开着、服务器在跑，以及手机上的 Tailscale 已连接。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = { scope.launch { load() } },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                            ) {
                                Text("重试")
                            }
                        }
                        health != null -> {
                            val h = health!!
                            // ① 一句人话结论
                            val (verdict, verdictColor) = when {
                                !h.db_ok ->
                                    "数据库不可达 —— 服务器连不上数据库" to MaterialTheme.colorScheme.error
                                h.fleet.state == "unauthorized" ->
                                    "Tesla 授权已失效 —— 锁车与实时胎压已停更" to MaterialTheme.colorScheme.error
                                h.fleet.state == "unreachable" ->
                                    "暂时连不上 Tesla 云端 —— 锁车与实时胎压已停更" to
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                h.fleet.state == "asleep" ->
                                    "一切正常（车在休眠，锁车状态等它醒来才更新）" to
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                h.fleet.state == "disabled" ->
                                    "一切正常（未接入 Tesla 实时状态，锁车与实时胎压不可用）" to
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                else ->
                                    "一切正常" to MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Text(
                                verdict,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = verdictColor,
                            )
                            Spacer(Modifier.height(10.dp))
                            // ② 数据库
                            KV("数据库", if (h.db_ok) "正常（${h.db_latency_ms} 毫秒）" else "不可达")
                            // ③ Fleet 实时状态
                            KV(
                                "实时状态",
                                when (h.fleet.state) {
                                    "disabled" -> "未接入"
                                    "asleep" -> "车在休眠"
                                    "unauthorized" -> "授权已失效"
                                    "unreachable" -> "连不上云端"
                                    "ok" -> "正常"
                                    else -> "—"
                                },
                            )
                            // ④ 最后一次车况采集
                            val age = h.teslamate.age_hours
                            KV(
                                "最后车况",
                                if (age != null) {
                                    val hrs = age.toInt()
                                    if (hrs >= 24) "${hrs / 24} 天前" else "$hrs 小时前"
                                } else {
                                    "—"
                                },
                            )
                            h.fleet.error?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            item {
                SectionCard("服务器信息", Modifier.animateItem()) {
                    KV("App 版本", "v${BuildConfig.VERSION_NAME}")
                    health?.server_timezone?.let { KV("服务端时区", it) }
                    Text(
                        "车况数据按服务端时区分日；时间轴上的时间按手机时区显示。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * ⚠️ **v0.25.0（第 16 轮充电成本专家 P0-1 / P1-5）：电价与成本页。**
 *
 * 三档而不是一个单价：
 * - **家充电价**（元/度）—— 家充必须按度算：实测 id=44 插枪 617 分钟只充入 17 度
 *   （有效功率 1.65 kW），插枪时长含大量闲置，按分钟算会严重高估。
 * - **公共桩电费**（元/度）
 * - **公共桩服务费**（按**分钟**或按**度**）——
 *   实测同一台车两种算法差 **34.6%**（2022 元 vs 2722 元），
 *   而服务区快充普遍按分钟收服务费（占位时间也要钱）。
 */
@Composable
private fun SettingsRatesPage(
    rates: com.teslamate.app.ChargeRates,
    carName: String?,
    onChange: (com.teslamate.app.ChargeRates) -> Unit,
    onSave: () -> Unit,
    saved: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var homeText by remember(rates) { mutableStateOf(rates.homeYuanPerKwh.toPlainString()) }
    var publicText by remember(rates) { mutableStateOf(rates.publicYuanPerKwh.toPlainString()) }
    var serviceText by remember(rates) { mutableStateOf(rates.publicServiceYuan.toPlainString()) }
    var perMinute by remember(rates) { mutableStateOf(rates.publicServiceMode == com.teslamate.app.ChargeRates.MODE_PER_MINUTE) }

    Column(modifier) {
        SecondaryTitleBar(title = "电价与成本", backLabel = "设置", onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard("电价" + (carName?.let { " · $it" } ?: ""), Modifier.animateItem()) {
                    Text(
                        "填上你的电价，App 就能给出「每公里成本」的**估算**" +
                            "（分母是 App 记录的行驶里程，可能漏记）—— " +
                            "这个数官方 Tesla App 给不了。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // ⚠️ **v0.30.0（第 19 轮财务评审 P0-3 / P2-1）：口径改了。**
                    //
                    // 原文写「本机 `geofences` 表 0 行，78/78 场充电都没有围栏归属」——
                    // **这句已经过时**：「家」围栏是 2026-10-25 02:36 才建的（1 行），
                    // 而全部 78 场充电都发生在**它之前**，所以历史数据仍然没有归属。
                    //
                    // 原文写「所有交流慢充都按家充电价算」—— 这个默认在本机 **5/5 全错**：
                    // 实测 5 场交流慢充全部在 **286~1506 公里外**（甘孜的酒店、梧州的路边）。
                    //
                    // 现在：**家充有真凭据**（TeslaMate 的充电围栏），
                    // 没有归属的交流由下面的开关决定按哪个价算。
                    Text(
                        "家充按 TeslaMate 的**充电围栏**判定（命中围栏的才算家充）。" +
                            "没有围栏归属的交流慢充，App 不知道它是在家还是在酒店/路边 —— " +
                            "用下面的开关决定按哪个价算。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // ⚠️ v0.30.0：**新开关** —— 没有围栏归属的交流慢充按哪个价算。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "无归属的交流慢充按",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IosSwitch(
                            checked = rates.acUsesHomePrice,
                            onCheckedChange = {
                                onChange(rates.copy(acUsesHomePrice = it))
                            },
                            label = "无归属的交流慢充按家充电价计",
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (rates.acUsesHomePrice) "家充电价" else "公共桩价",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        "默认按**公共桩价**算 —— 因为实测本机 5 场交流慢充全部在 286 公里以外，" +
                            "「交流 = 家充」这个假设 5/5 是错的。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    IosField(
                        label = "家充电价（元/度）",
                        value = homeText,
                        onValueChange = {
                            homeText = it.filter { c -> c.isDigit() || c == '.' }
                            onChange(
                                rates.copy(homeYuanPerKwh = it.toDoubleOrNull() ?: 0.0)
                            )
                        },
                        placeholder = "0.68",
                        keyboardType = KeyboardType.Number,
                    )
                    IosField(
                        label = "直流桩电费（元/度）",
                        value = publicText,
                        onValueChange = {
                            publicText = it.filter { c -> c.isDigit() || c == '.' }
                            onChange(
                                rates.copy(publicYuanPerKwh = it.toDoubleOrNull() ?: 0.0)
                            )
                        },
                        placeholder = "1.20",
                        keyboardType = KeyboardType.Number,
                    )
                    IosField(
                        label = "直流桩服务费（元/" + if (perMinute) "分钟" else "度" + "）",
                        value = serviceText,
                        onValueChange = {
                            serviceText = it.filter { c -> c.isDigit() || c == '.' }
                            onChange(
                                rates.copy(publicServiceYuan = it.toDoubleOrNull() ?: 0.0)
                            )
                        },
                        placeholder = "0.50",
                        keyboardType = KeyboardType.Number,
                    )
                    // 服务费计费方式：**按分钟 / 按度**二选一。
                    // 这是本轮最关键的一个开关 —— 两种算法实测差 34.6%。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "服务费按",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        IosSwitch(
                            checked = perMinute,
                            onCheckedChange = {
                                perMinute = it
                                onChange(
                                    rates.copy(
                                        publicServiceMode = if (it) {
                                            com.teslamate.app.ChargeRates.MODE_PER_MINUTE
                                        } else {
                                            com.teslamate.app.ChargeRates.MODE_PER_KWH
                                        }
                                    )
                                )
                            },
                            label = "服务费按分钟计",
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (perMinute) "分钟" else "度",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        "服务区快充普遍按**分钟**收服务费（占位时间也要钱）。" +
                            "本机实测：按分钟 ≈ ¥1,094.50、按度 ≈ ¥902.76（差 5.8%）；" +
                            "**不填服务费会低估约 33%**。家用交流桩反过来必须按度算。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SectionCard("保存", Modifier.animateItem()) {
                    Button(
                        onClick = onSave,
                        enabled = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = "保存电价设置"
                            },
                    ) {
                        Text(
                            when {
                                saved -> "已保存 ✓"
                                else -> "保存电价"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        "电价按车分别保存。保存后用量页会显示「每公里成本」。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * ⚠️ **v0.25.0（第 16 轮充电成本专家 P1-1 / P1-5）**：把电量输入框里的数字读成可编辑文本。
 *
 * `IosField` 存的是 String，这里只是把 Double 变成「不带多余 0 的文本」。
 */
private fun Double.toPlainString(): String =
    if (this == 0.0) "" else {
        val s = String.format(java.util.Locale.CHINA, "%.3f", this).trimEnd('0').trimEnd('.')
        s
    }

@Composable
private fun SettingsAboutPage(onBack: () -> Unit, modifier: Modifier = Modifier) {    Column(modifier) {
        SecondaryTitleBar(title = "关于", backLabel = "设置", onBack = onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard("关于", Modifier.animateItem()) {
                    KV("当前版本", "v${BuildConfig.VERSION_NAME}")
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
                modifier = Modifier
                    .fillMaxWidth()
                    // ⚠️ v0.20.0（a11y 评审 P0-2）：**输入框要能自报家门** ——
                    // 标签是输入框**外面**的一个独立 TextView，
                    // TalkBack 聚焦到输入框时只能读出值、不知道这是哪个字段。
                    .semantics { contentDescription = label },
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
    /** ⚠️ v0.20.0（a11y 评审 P0-2）：开关的**可访问名称**。 */
    label: String = "开关",
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
    // ⚠️ **v0.26.0（第 17 轮 HIG 评审 P0-1）：开关轨道的开态绿从 #34C759 降到 #2E9E4F。**
    //
    // 白色旋钮 `#FFFFFF` 画在 `#34C759` 上只有 **2.22:1**，
    // 低于 WCAG 1.4.11「非文字图形 ≥ 3:1」的硬线 —— 开关是**图形**（旋钮），不是文字。
    // `#2E9E4F` → 白旋钮 **3.43:1**，达标；色相仍是 iOS 绿，肉眼几乎看不出差别。
    val onColor = Color(0xFF2E9E4F)
    val offColor = accents.chartTrack
    Box(
        modifier
            .width(51.dp)
            .height(31.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                androidx.compose.ui.graphics.lerp(offColor, onColor, trackColor) ?: offColor
            )
            // ⚠️ v0.20.0（a11y 评审 P0-2）：**用 toggleable 而不是 clickable** ——
            // `clickable` 只提供「可点」，不提供「这是一个开关、开/关状态」。
            // TalkBack 读出来会是「按钮」而不是「开关，已关闭」。
            // `toggleable` 同时给出 Role.Switch 与 checked 状态语义。
            //
            // ⚠️ 语义必须放在 toggleable **之前**（外层）——
            // 外层 merging 节点会把内层的 role/checked/onClick 一并收进来，
            // 于是这一个节点同时有：名字 + 角色 + 开/关状态 + 可激活。
            //
            // ⚠️ 之前这里写了 `.clearAndSetSemantics { }` ——
            // 那会把 toggleable 的语义**全清掉**（开关变成完全无名的节点）。
            // 空 clearAndSetSemantics 同样无效（不设任何属性）。
            .semantics(mergeDescendants = true) {
                contentDescription = label
            }
            .toggleable(
                value = checked,
                onValueChange = {
                    Haptics.selection(ctx)
                    onCheckedChange(it)
                },
                role = Role.Switch,
            ),
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
