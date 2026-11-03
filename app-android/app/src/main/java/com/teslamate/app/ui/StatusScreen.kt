@file:OptIn(ExperimentalMaterial3Api::class)

package com.teslamate.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.teslamate.app.ActiveCharge
import com.teslamate.app.AppSettings
import com.teslamate.app.CarStatus
import com.teslamate.app.ConsumptionModel
import com.teslamate.app.Haptics
import com.teslamate.app.TelemetryAlert
import com.teslamate.app.TelemetryStatus
import com.teslamate.app.districtCity
import com.teslamate.app.fmt
import com.teslamate.app.fmtKwh100
import com.teslamate.app.fmtShort
import com.teslamate.app.fmtThousands
import com.teslamate.app.odometerParts
import com.teslamate.app.relTime
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 车况首页：大标题折叠栏 + 下拉刷新 + 15 秒自动刷新 */
@Composable
fun StatusScreen(
    settings: AppSettings,
    modifier: Modifier,
    store: StatusStore = remember { StatusStore() },
    /**
     * 「前往设置」的出口（v0.9.0）。
     *
     * 令牌失效时错误横幅上会挂一个「前往设置」按钮 —— 「设置」在第 4 个 Tab，
     * 光在文案里写「请到「设置」页重新填写」等于没给路。
     */
    onOpenSettings: () -> Unit = {},
    /**
     * ⚠️ v0.20.0（iOS IA 评审 P1-3）：**保养卡的出口要落到具体二级页**。
     *
     * 原来保养卡与 401 横幅共用同一个 `onOpenSettings`，都只是跳到设置页**顶部** ——
     * 而保养基准在设置页的**最下面**，用户跳过去还要滚一屏半才看到。
     * 箭头承诺的落点与实际落点不符，等于没有落点。
     */
    onOpenMaintenance: () -> Unit = {},
    /** ⚠️ v0.16.0：车辆列表（顶栏车切换芯片，仅多车时出现）。 */
    cars: List<com.teslamate.app.Car> = emptyList(),
    /** ⚠️ v0.16.0：切换当前车辆。 */
    onCarChange: (Int) -> Unit = {},
    /**
     * ⚠️ **v0.76.0**：全局反馈用的 Snackbar 宿主。
     *
     * 车控命令的**结果提示必须落在屏幕底部**，而不是卡片的最下面 ——
     * 卡片很长，用户点「闪灯」时卡片底部在**屏幕外**，
     * 结果提示跟着一起看不见 = 「点了没反应」，
     * 用户会以为车没听指挥，**然后再点一次**。
     */
    snackbarHostState: SnackbarHostState? = null,
) {
    val api = remember(settings) { settings.api() }
    val scope = rememberCoroutineScope()
    // ⚠️ v0.9.2：LocalContext.current 要在 @Composable 作用域取一次，
    // 不能写在 onClick lambda 里（那不是 composable 上下文）。
    val ctx = LocalContext.current
    // 数据存在 App 级 store 里：切 Tab 回来时旧数据还在，
    // 直接显示并后台刷新 —— 不再闪一次骨架屏。
    // store 内部用 mutableStateOf 持有，所以在 @Composable 里直接读就会订阅变化。
    val status = store.status
    val error = store.error
    var refreshing by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(false) }
    // ⚠️ **v0.74.0**：车控清单的刷新信号。
    // 只有**用户主动**刷新（↻ / 下拉刷新）才 +1；
    // 15 秒自动刷新走的是 load()，不碰它 —— 动作清单不会自己变，
    // 每 15 秒重读一次纯属浪费。
    var controlRefresh by remember { mutableStateOf(0) }

    LaunchedEffect(api, settings.carId) {
        if (api != null) {
            // ⚠️ v0.24.0（性能评审 P1-2）：**能耗模型在后台线程建** ——
            // 原来它在主线程（`onSuccess` 已切回 Main），
            // 而这正好落在「数据落地那一帧」（实测已是全 App 最卡的一帧）。
            runCatching {
                withContext(Dispatchers.IO) {
                    val list = api.drives(settings.carId, 500)
                    list to ConsumptionModel.from(list)
                }
            }.onSuccess { (list, model) ->
                store.updateDrives(list, settings.carId, model)
            }
        }
    }
    // ⚠️ **v0.27.0：驾驶画像**（官方 Tesla App 没有的能力）。
    //
    // 服务端要从 `positions` 的 162 万个采样点算（实测 30 趟几百毫秒），
    // 所以**每辆车只算一次**，结果放 App 级 store —— 切 Tab 回来不重算。
    LaunchedEffect(api, settings.carId) {
        if (api == null) return@LaunchedEffect
        if (store.driveProfileLoadedFor == settings.carId) return@LaunchedEffect
        store.startDriveProfile()
        runCatching {
            withContext(Dispatchers.IO) { api.drivingProfile(settings.carId, 30) }
        }.onSuccess { store.updateDriveProfile(it, settings.carId) }
            .onFailure { store.failDriveProfile() }
    }
    // ⚠️ **v0.28.0：常驻点**（官方 App 只能告诉你「车现在在哪」）。
    //
    // 服务端从**相邻行程的时间间隙**推断停留时长 ——
    // 车睡着后 TeslaMate 不记位置，所以这是「推断停留」不是实时停车时长。
    // 每辆车只算一次，结果放 App 级 store。
    LaunchedEffect(api, settings.carId) {
        if (api == null) return@LaunchedEffect
        if (store.stayPointsLoadedFor == settings.carId) return@LaunchedEffect
        store.startStayPoints()
        runCatching {
            withContext(Dispatchers.IO) { api.stayPoints(settings.carId, 3.0) }
        }.onSuccess { store.updateStayPoints(it, settings.carId) }
            .onFailure { store.failStayPoints() }
    }
    val drives = store.drives
    // ⚠️ v0.9.0：能耗模型改从 store 取（App 级 + **已落盘**），
    // 不用页面里的 `remember(drives) { ConsumptionModel.from(drives) }` ——
    // 那样服务器挂掉时模型为 null，续航会退化成「官方标称」（乐观 50%）。
    val consumption = store.consumption
    // ⚠️ **v0.119.0**：服务端算的「真实可达续航」结论（null = 还没取到 / 取不到）。
    val serverRange = store.serverRange
    // ⚠️ **v0.123.0**：服务端的保养 / 轮胎结论（null = 还没取到 / 取不到）。
    val serverMaintenance = store.serverMaintenance
    val recentAscent = store.recentAscentM
    val fallbackSpeed = store.drives.firstOrNull()?.avg_speed_kph?.toDouble()

    // ⚠️ 修正：地图展开时按返回键原本会直接退出 App（没有 BackHandler）。
    BackHandler(enabled = showMap) { showMap = false }

    suspend fun load() {
        if (api == null) {
            store.fail(
                if (settings.configured) "服务器地址或令牌无效，请到「设置」页检查"
                else "尚未配置服务器，请到「设置」页填写地址与令牌"
            )
            return
        }
        runCatching { withContext(Dispatchers.IO) { api.status(settings.carId) } }
            .onSuccess { store.update(it) }
            .onFailure { e ->
                // ⚠️ v0.9.0：保留**分级**，界面才能给出不同的反应
                // （令牌问题 → 跳设置；网络问题 → 重试）。
                // 注意这里**不清空** store.status —— 服务器挂掉时用户还能看到上次已知。
                store.fail(
                    e as? com.teslamate.app.UserFacingException
                        ?: com.teslamate.app.UserFacingException(e.message ?: "出错了")
                )
            }

        // ⚠️ **v0.119.0**：服务端算的「真实可达续航」结论。
        //
        // ⚠️ 与 status **一起**刷：结论依赖**当次**的电量 / 标定续航 / 车外温度 / 车速
        // ⇒ 必须跟着 15 秒刷新走，否则行驶中换档（市区 ↔ 高速）时主角数字不跟着变。
        // 服务端把「模型」缓存住（5 分钟）⇒ 这条请求很便宜。
        runCatching { withContext(Dispatchers.IO) { api.rangeEstimate(settings.carId) } }
            .onSuccess { store.updateServerRange(it) }
            // ⚠️ 取不到**不报错**：界面要退回本地模型兜底，
            //    而不是弹一条「续航算不出来」（服务器挂掉时那不是车的问题）。
            .onFailure { /* 保留旧值 / 用本地模型兜底 */ }

        // ⚠️ **v0.123.0**：服务端的保养 / 轮胎结论（基准与判定都在服务端）。
        //
        // ⚠️ 与 status 一起刷：结论依赖**当次**的车端里程表
        // ⇒ 必须跟着 15 秒刷新走，否则里程一变提醒就不跟着变。
        // ⚠️ **v0.123.0：服务端的保养 / 轮胎结论 ＋ 一次性基准迁移。**
        //
        // ⚠️ 与 status 一起刷：结论依赖**当次**的车端里程表
        // ⇒ 必须跟着 15 秒刷新走，否则里程一变提醒就不跟着变。
        //
        // ⚠️ **迁移**：升级前基准只存在手机本地 ⇒ 服务端一开始是空的
        // ⇒ 若不迁移，用户**不手动去设置页点一次保存**，飞书就一直问不出来
        // （这一轮的目标就落空了）。
        // ⇒ 服务端**还没配置**、而本机**有**基准 ⇒ 把本机那份推上去。
        //    推成功之后服务端 configured=true ⇒ **不会重复推**（幂等）。
        val mreport = runCatching {
            withContext(Dispatchers.IO) { api.maintenance(settings.carId) }
        }.getOrNull()
        if (mreport != null) {
            store.updateServerMaintenance(mreport)
            if (!mreport.configured) {
                val b = settings.currentBaselines
                if (b.serviceOdoKm > 0 || b.tireOdoKm > 0) {
                    val synced = runCatching {
                        withContext(Dispatchers.IO) {
                            api.saveMaintenanceBaseline(
                                carId = settings.carId,
                                serviceOdoKm = b.serviceOdoKm,
                                serviceIntervalKm = b.serviceIntervalKm,
                                tireOdoKm = b.tireOdoKm,
                                tireIntervalKm = b.tireIntervalKm,
                            )
                        }
                    }.getOrNull()
                    if (synced != null) store.updateServerMaintenance(synced)
                    // ⚠️ 推失败**不报错**：本机那份还在（App 照旧用本地兜底显示），
                    // 下次刷新会再试 ⇒ 静默失败不该变成一条红条。
                }
            }
        }
        // ⚠️ 取不到**不报错**：退回本地兜底（服务器挂掉时保养提醒不该整块消失）。
    }

    fun refresh() {
        scope.launch {
            refreshing = true
            load()
            refreshing = false
            // ⚠️ v0.74.0：车况页的刷新必须刷新**屏幕上的一切**。
            // 车控清单原来只在首次组合时读一次 ⇒ 清单读失败后，
            // 用户点 ↻ 也换不来任何变化，错误状态一直赖着。
            controlRefresh++
        }
    }

    // 15 秒自动刷新，但必须跟随界面生命周期：
    // 原写法 `while (isActive)` 在 Activity onStop 时并不会被取消（Compose 树仍存活），
    // 于是 App 一旦退到后台就继续每 15 秒打一次 API，白耗电白耗流量。
    // repeatOnLifecycle(STARTED) 保证：前台跑、后台停、回来再跑，且首次进入立刻刷一次。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, settings) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                load()
                delay(15_000)
            }
        }
    }


    Column(modifier) {
        TopAppBar(
            title = { ScreenTitle("车况") },
            // ⚠️ v0.7.1：顶部栏原来用 M3 默认容器色（深色下是一块不透明 #1C1C1E），
            // 底部导航栏却是真毛玻璃 —— 同一个 App 里两套材质语言。
            // 现在顶部栏透明，与底部玻璃统一为「内容在上、材质在下」的一套语言。
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            actions = {
                // ⚠️ v0.16.0：顶栏车切换芯片（仅多车时出现）
                CarChip(
                    cars = cars,
                    currentId = settings.carId,
                    onSelect = onCarChange,
                )
                Spacer(Modifier.width(4.dp))
                // ⚠️ v0.9.2（iOS 交互评审 P1-7）：刷新按钮原来点了**零反馈** ——
                // 图标不转、按钮不禁用（刷新中再点会并发再发一次请求）、也没有触感。
                // 用户点完刷新，屏幕上什么变化都看不到。
                //
                // ⚠️ v0.14.0（iOS 动效专项评审 P0-2）：修三个缺陷。
                //
                // ① **角度跳变**：原来 `angle` 由**常驻** infinite transition 驱动，
                //    点刷新的瞬间 `rotationZ` 从 0 **跳到当前角度**（可能是 347°）——
                //    肉眼可见地「咔」一下。现在进入刷新时先 `snapTo` 当前角度。
                // ② **结束硬切**：`rotationZ` 直接回到 0，像被拽回去。
                //    现在结束时长 200ms 平滑转回 0°。
                // ③ **禁用变灰**：刷新中 `enabled = false` 让 M3 把图标染成 38% 透明灰，
                //    图标在「变灰」和「在转」两个通道里打架。
                //    现在**不禁用**（并发防护靠函数内的 `if (refreshing) return`），
                //    只把 tint 换成 onSurfaceVariant。
                val spin = remember { Animatable(0f) }
                LaunchedEffect(refreshing) {
                    if (refreshing) {
                        // 先对齐到当前角度，消除跳变
                        spin.snapTo(spin.value % 360f)
                        // 每圈 700ms 匀速续跑
                        while (true) {
                            spin.animateTo(
                                targetValue = spin.value + 360f,
                                animationSpec = tween(Motion.RefreshSpin, easing = androidx.compose.animation.core.LinearEasing),
                            )
                        }
                    } else if (spin.value != 0f) {
                        // 平滑回正，而不是瞬移
                        spin.animateTo(
                            targetValue = 360f - (spin.value % 360f),
                            animationSpec = tween(Motion.RefreshSettle, easing = Motion.EaseOut),
                        )
                    }
                }
                IconButton(
                    onClick = {
                        Haptics.tick(ctx)
                        // 并发防护：不禁用按钮，就在这里挡
                        if (!refreshing) refresh()
                    },
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "刷新",
                        tint = if (refreshing) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.graphicsLayer {
                            rotationZ = spin.value
                        },
                    )
                }
            },
        )
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        // ⚠️ 底部必须留出导航栏高度：导航栏是半透明毛玻璃，
                        // 内容会滚到它下面；不留 padding 的话最后一项（胎压卡）
                        // 永远被盖住且滚不到。
                        bottom = 16.dp + navBarBottom(),
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                error?.let { err ->
                    // ⚠️ v0.9.0：错误横幅**带出口**。
                    //   令牌问题 → 「前往设置」（直达第 4 个 Tab）
                    //   其它     → 「重试」（不用干等 15 秒自动轮询）
                    // 而「这台车还没数据」是**正常状态**，不该用红条报错 ——
                    // 走一个中性的引导块。
                    //
                    // ⚠️ v0.14.0：抽成公共的 [ApiErrorBanner]，
                    // 四个页面共用同一套分级逻辑（原来只有这一页有出口）。
                    ApiErrorBanner(
                        err,
                        onOpenSettings = onOpenSettings,
                        onRetry = { refresh() },
                    )
                }

                val st = status
                when {
                    st == null && error == null -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SkeletonBox(Modifier.fillMaxWidth().height(180.dp))
                        SkeletonBox(Modifier.fillMaxWidth().height(96.dp))
                        SkeletonBox(Modifier.fillMaxWidth().height(96.dp))
                    }

                    st != null -> {
                        // ⚠️ **v0.92.0**：页首状态区**收敛成一行**。
                        //
                        // 「锁没锁」「窗关没关」「门关没关」「哨兵开着没」是**同一个问题簇** ——
                        // 用户每天第一个念头就是这一簇。
                        // 它们**全是状态、不是数字** ⇒ 不跟电量环抢视觉锚点
                        // （「一屏一个主角数字」仍然成立）。
                        //
                        // 为什么不用卡片：卡片会把主角数字环往下推 130dp，
                        // 而这些**零滚动**就该看见 ⇒ 放在页首。
                        //
                        // ⚠️ **v0.92.0**：原来这里是**两行**（外加一行固件更新）：
                        //   ① `🔒 已上锁 11 小时前`              `车况 · 11 小时前`
                        //   ② `车窗 全关 · 车门 全关 · 哨兵 未开启 · 11 小时前`
                        // ⇒ 同一个「11 小时前」出现**三次**，
                        //    而 ② 报的三项**全是「没事」**（全关 / 未开启）。
                        // 现在：**一行**、**一个**时间戳、**只报值得报的事**。
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TopStatusLine(st, store.cachedAt)
                            // ⚠️ **v0.84.0**：有可安装的新固件时**多一行**。
                            //
                            // 「装饰宁删勿留」⇒ **没有更新就不出现这一行**。
                            //
                            // ⚠️ 它不是紧急情况 ⇒ **不上红**（红色留给哨兵警报触发）。
                            //   但它是一件「可做的事」⇒ 用正文次级色，不用灰到看不见。
                            //
                            // ⚠️ 只有 `update_pending` 为真才显示 —— 服务端的判据是
                            //   「两个版本号都拿到且不相等」，不会因为字段存在就误报。
                            if (st.update_pending && st.software_update_version.isNotBlank()) {
                                Text(
                                    "有 ${st.software_update_version} 待安装",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        // ⚠️ v0.13.0（iOS 交互评审 P1-12）：**告警排在中性提示之前**。
                        //
                        // 原来顺序是「锁车未知（中性横幅）」→「车没上报（中性横幅）」→「保养超期（红色告警）」——
                        // 唯一的红色告警排第三，用户第一眼扫到的是两条中性信息，
                        // 主角数字（电量环）被推到第三屏。
                        //
                        // iOS 惯例：告警类信息最靠近拇指、排在最前；中性信息后置或降级为行内文字。
                        //
                        // ⚠️ v0.9.0：保养 / 换胎提醒。
                        // 只有**需要行动**时（超期或 2000 km 内到期）才挂横幅 ——
                        // 信号优先，平时不制造噪音。完整数字在页面底部的卡片里。
                        val reminders = remember(st.odometer, st.odometer_vehicle, settings, serverMaintenance) {
                            // ⚠️ **v0.123.0：判定与文案都在服务端**（ADR #38 / #41）——
                            // 服务端有结论就用它（飞书与 App 必然同一个数、同一句话）；
                            // 取不到才退回本地兜底。
                            //
                            // ⚠️ v0.21.0：**优先用车端里程表** ——
                            // 车主填的保养基准是他**仪表盘上看到的数**（车端），
                            // 拿 TeslaMate 按 GPS 点累计的数去套，会偏几百公里。
                            //
                            // ⚠️ **v0.75.0（查库实测）：那个回落**不只偏几百公里，是偏 38,422 公里** ——
                            // `positions.odometer` 的绝对值被停车时的 GPS 抖动污染（实测日漂移可达 1,143 公里）。
                            // 拿它当基准算出来的会是「保养 **超期 38,422 公里**」——
                            // **误报的方向最危险**（让人以为车立刻要保养，白花钱）。
                            //
                            // ⇒ **车端读不到就不报**（宁可不报，也不误报）。
                            // 车醒着时 Fleet API 会给出仪表里程表，提醒照常出现。
                            resolveMaintenanceItems(
                                server = serverMaintenance,
                                vehicleOdometerKm = st.odometer_vehicle,
                                settings = settings,
                            )
                        }
                        reminders.firstOrNull { it.needsAction }?.let { r ->
                            // ⚠️ v0.20.0（车主 P1-1）：**保养超期不再用红色** ——
                            //
                            // 车主原话：「每天早上打开 App，先撞上一条红色
                            // 「保养 超期 2455 公里」，而这红条在我做完保养之前
                            // 会**连着亮几个月**，真正每天变的电量被它压在下面。」
                            //
                            // 告警的排序依据应该是「**多久会变**」，而不是「有多红」：
                            // 保养/换胎是**数月不变**的事，胎压异常、电量 <10%
                            // 才是**今天就会变**的事。红色只留给后者，
                            // 「红色 = 立刻要处理」的信噪比才立得住。
                            //
                            // 现在：中性横幅 + **可点**（直达保养基准页）——
                            // 该做的事一件没少，但不再长期霸占告警位。
                            //
                            // ⚠️ v0.20.0（P2-5）：横幅里删掉「现在 101,455 公里」——
                            // 同一个数在同一屏下方「里程表 101,455 公里」已经有了，
                            // 一字不差地出现两次只会让人以为是两个数。
                            NoticeBanner(
                                r.bannerText(st.odometer),
                                onClick = onOpenMaintenance,
                            )
                        }

                        // 数据新鲜度：让「车睡着了（正常）」和「连不上服务器」不再长得一样。
                        // 连不上服务器的情况由上面的 ErrorBanner 负责（那是请求失败）；
                        // 这里说明的是「请求成功了，但车本身很久没上报」——多半是车停着不动。
                        //
                        // ⚠️ v0.9.0：阈值改用**服务端**的 data_warn / data_stale。
                        // 原来 App 自己写 6 小时 / 24 小时两档，而服务端 DataStaleHours=24
                        // 才是唯一事实源 —— 两边各写一份，改一边就打架。
                        //
                        // ⚠️ v0.9.0（新手车主复核）：措辞改成「车没上报」而不是「数据未更新」。
                        // 车主原话：「车停着不动是常态，这句话像系统在报警。」
                        val staleHours = st.data_age_hours
                        // ⚠️ v0.22.0（自托管运维评审 P0-1）：
                        // **「车没上报」只在本次请求成功时才说。**
                        //
                        // 原来这条横幅**无条件**渲染 —— 只要缓存里的
                        // `data_warn` 为真就出现。于是服务器连不上时，
                        // 屏幕上同时挂着：
                        //   红条：「连不上服务器…」
                        //   灰条：「车 6 小时没上报数据了 —— 停着不动时属正常。」
                        //
                        // 用户读到第二条会以为**车有问题**、车被拖走了、
                        // 或者 TeslaMate 挂了 —— 实际是 App 自己够不着服务器。
                        //
                        // 这是**最坏的一种误导**：把「看不见」说成「车不动了」。
                        // （与 v0.9.0「正常状态被渲染成错误」是同一类错误的镜像。）
                        //
                        // 现在：缓存态（`cachedAt != null`，说明这次没连上）
                        // 改说「这是上次读到的数据，服务器现在连不上」，
                        // **不再断言车没动**。
                        if (store.cachedAt != null) {
                            NoticeBanner(
                                "这是上次读到的数据（${store.cachedAt!!.relTime()}）—— " +
                                    "服务器现在连不上，无法确认车况是否已更新。",
                            )
                        } else if (st.data_stale) {
                            val days = staleHours / 24
                            NoticeBanner(
                                if (days >= 1) {
                                    "车已经 $days 天没上报数据了 —— 停着不动时属正常。" +
                                        "若你人在外地，也可能是网络不通。"
                                } else {
                                    "车已经 $staleHours 小时没上报数据了 —— 停着不动时属正常。"
                                }
                            )
                        } else if (st.data_warn) {
                            NoticeBanner("车 $staleHours 小时没上报数据了 —— 停着不动时属正常。")
                        }

                        // ⚠️ v0.9.0：Fleet API 出问题时**说清原因**。
                        // 服务端现在**保留**上次已知的锁车状态（不再清成 null），
                        // 所以这里显示的可能是一个**旧值** —— 必须告诉用户它有多旧、为什么旧。
                        //
                        // 而「锁车状态未知」本身会让人焦虑（新手车主原话：
                        // 「到底锁没锁？？能锁吗？」），所以要先给一句宽心话：
                        // **没取到，不等于没锁。**
                        //
                        // ⚠️ v0.14.0（新手车主评审 + 苹果 HIG P0-1）：
                        // ① **横幅不再重复状态本身**；
                        // ② **这条中性横幅下沉到主角数字之后**。
                        //
                        // 原来横幅写「暂时没取到锁车状态 —— 不代表车没锁。」，
                        // 而页面顶部那行小字已经写着「锁车状态未知」——
                        // 同一个事实说了两遍，两块灰横幅把主角数字（电量环）挤到第三屏。
                        //
                        // 现在分工明确：
                        //   · 顶部那行 = **状态标签**（三态之一，和「已上锁/未上锁」同一套语言）
                        //   · 横幅     = **宽心话 + 原因 + 这个值有多旧**（顶部那行没有的信息）
                        //
                        // 而它的**位置**也按 iOS 惯例调了：
                        // 红色告警排第一（要动手的事最靠前），**中性信息后置** ——
                        // HIG「Deference」：每屏唯一视觉锚点，其余信息让位。
                        // ⚠️ v0.21.0：**区分「没装这个功能」与「这会儿读不到」**。
                        //
                        // 车主原话：「每天早上第一眼看到「锁车状态未知」，
                        // 下面一条灰横幅说「没读到车机的锁车状态」，
                        // 于是我以为是车机或网络出了故障，刷新两次、怀疑车没锁；
                        // 其实这台服务器压根没配 Fleet API 凭据，
                        // 这个状态**永远**不会变。」
                        //
                        // 服务端现在显式下发 `fleet_enabled`：
                        //   false → 「这台车没连实时状态源」—— 原因说准，且它**不会变**
                        //   true  → 「这会儿没取到」—— 原因另说（fleet_error）
                        val fleetNotice = remember(st.locked, st.fleet_error, st.fleet_last_ok, st.fleet_enabled, st.fleet_state) {
                            buildString {
                                if (st.locked == null) {
                                    when (st.fleet_state) {
                                        "disabled" ->
                                            append("这台车没连实时状态源，锁车与实时胎压拿不到 —— 不是车的问题。")
                                        // ⚠️ **v0.75.0（模拟器真机复核）：不再重复「车在休眠」。**
                                        //
                                        // 页面顶部那行状态芯片已经写着
                                        // 「车在休眠 · 锁车状态等它醒来才更新」——
                                        // 横幅又说一遍「车在休眠，锁车…要等它醒来才更新」，
                                        // **同一个事实说了两遍**，两块灰横幅把电量环挤到第三屏。
                                        //
                                        // 上面那段注释写的分工是「**横幅 = 顶部那行没有的信息**」——
                                        // 那就照它做：顶部说了「车在休眠 + 锁车状态」，
                                        // 横幅只补**实时胎压**这一条。
                                        "asleep" ->
                                            append("实时胎压要等它醒来才更新 —— 不是车的问题。")
                                        else ->
                                            append("没读到车机的锁车状态 —— 这不代表车没锁。")
                                    }
                                }
                                if (st.fleet_error.isNotBlank()) {
                                    if (isNotEmpty()) append(" ")
                                    append(st.fleet_error)
                                }
                                st.fleet_last_ok?.let { ok ->
                                    if (st.locked == null) {
                                        append("（最后一次成功获取：${ok.relTime()}）")
                                    } else {
                                        // ⚠️ **v0.75.0（模拟器真机复核）：只有**真的旧**才说「旧值」。**
                                        //
                                        // 原来无条件写「上面显示的是 X 读到的旧值」——
                                        // 而 X 可能是「刚刚」⇒ 屏幕上出现
                                        // 「上面显示的是 刚刚读到的旧值」⇒ **既刚刚又旧，自相矛盾**。
                                        //
                                        // 现在按**值自己的采集时间**（`locked_at`）判断新鲜度：
                                        // 5 分钟内 = 新鲜 ⇒ 不加这句（没脏就别提「脏」）。
                                        val ageMin = st.locked_at?.let {
                                            java.time.Duration.between(it, java.time.Instant.now()).toMinutes()
                                        } ?: Long.MAX_VALUE
                                        if (ageMin >= 5) {
                                            append(" —— 上面显示的是 ${ok.relTime()}读到的旧值。")
                                        }
                                    }
                                }
                            }.takeIf { it.isNotBlank() }
                        }

                        // ⚠️ v0.9.0：**弹性首屏** —— 大字号时电量环与续航**上下排**，
                        // 而不是硬挤在一行。
                        //
                        // 之前 AppRoot 把 fontScale 封在 1.30，理由是
                        // 「车况页是仪表盘布局，2.0 倍时纵向会挤爆」——
                        // 现在解封了 fontScale（WCAG 1.4.4 要求 200% 可用），
                        // 这条遗留项必须一起解掉，否则等于「解封了一半」。
                        //
                        // ⚠️ **v0.120.0（本轮手术点）**：**排布不再由「拍一个字号阈值」决定，
                        // 改由「实际可用宽度」决定**（`heroArrangement`，`ui/RangeArrangement.kt`）。
                        //
                        // 原来这里写死的是 1.35（v0.30.0 从 1.5 改下来的），推导是**手算**的：
                        // 「主角「204」在 44sp 下宽 73.9sp」—— 那个数**不是** App 估算器给的
                        // （估算器给 **81.84dp**），而且**没有任何单测钉住**它
                        // ⇒ 同 ADR #43 / #44 的老教训：**拍死的阈值迟早过期**。
                        //
                        // 实际代价（这一轮量出来的）：
                        // 393dp 屏的内容区 361dp，扣掉环（140dp）与 28dp 间距 ⇒ 窄栏 193dp；
                        // 而窄栏要装的是**主角数字 + 它的标签**：
                        //   「204」@44sp = **81.84dp**、「现在能开（公里）」@13sp = **104.00dp**
                        //   （口径：`estimateTextWidthDpWithNbsp`，乐观口径）
                        // ⇒ 真正的**绑定项是标签**，不是数字；
                        // ⇒ fs=1.35 时窄栏余量 **52.6dp**、fs=1.6 时余量 **14.3dp**
                        //   （判定要求 ≥ 4dp 安全垫）⇒ **都放得下**
                        // ⇒ 旧阈值却已经切了竖排 ⇒ **白丢「环 + 主角并排」的首屏构图**
                        // （主角独占一行、环被挤到下面 ⇒ 首屏纵向多占一屏）。
                        //
                        // ⇒ 现在：**量出宽度再决定**（`BoxWithConstraints` ⇒ 每台机器按自己的
                        // 实际宽度判断），放得下就并排，放不下就竖排（主角独占整行，绝不会被裁）。
                        val fontScale = LocalDensity.current.fontScale
                        // ⚠️ **v0.95.0（本轮手术点）**：续航的**主角**与**结论区**分开渲染。
                        //
                        // 原来两者都在 `RangeBlock` 里，而它整个渲染在电量环右侧的**窄栏**
                        // （真机实测约 171dp）⇒ 结论行放不下 ⇒ 被折成「88 条实」+「测」。
                        // 现在：**窄栏只放主角**（一屏一焦点），**结论区整宽**。
                        // ⚠️ **v0.119.0**：结论区的**判定在服务端**（ADR #38 / #47）
                        // ⇒ 有服务端结论就用它（飞书与 App 必然同一个数）；
                        //   服务端取不到才退回本地模型兜底（服务器挂掉不退化成乐观标称）。
                        val rangeModel = buildRangeModel(
                            resolveRangeFacts(
                                serverRange = serverRange,
                                st = st,
                                consumption = consumption,
                                recentAscent = recentAscent,
                                fallbackSpeedKph = fallbackSpeed,
                            )
                        )
                        // ⚠️ **v0.120.0**：排布由**宽度预算**决定（不是拍一个字号阈值）。
                        //
                        // `maxWidth` 是**运行时量出来的** ⇒ 360dp 窄屏 / 393dp / 412dp /
                        // 折叠屏各按各的实际宽度判断（同 ADR #43 / #44 的做法）。
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val arrangement = heroArrangement(
                                contentDp = maxWidth.value,
                                // ⚠️ 预算里的环宽必须**等于画出来的**环宽
                                // ⇒ 两者用同一个常量（客户端闸门 27 核对）。
                                ringDp = socRingDiameterDp(SOC_RING_MIN_DP, fontScale),
                                heroText = rangeHeroValue(rangeModel),
                                heroLabel = rangeHeroLabel(rangeModel),
                                fontScale = fontScale,
                            )
                            when (arrangement) {
                                HeroArrangement.STACKED -> Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    RangeHero(rangeModel, Modifier.fillMaxWidth())
                                    RangeConclusions(rangeModel)
                                    Spacer(Modifier.height(14.dp))
                                    // ⚠️ v0.30.0：两种布局用**同一个**环尺寸，
                                    // 否则切布局时环会「跳」一下。
                                    SocRing(
                                        st.battery_level,
                                        charging = st.active_charge != null,
                                        baseSizeDp = SOC_RING_MIN_DP,
                                    )
                                }
                                HeroArrangement.SIDE_BY_SIDE -> Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        SocRing(
                                            st.battery_level,
                                            charging = st.active_charge != null,
                                            baseSizeDp = SOC_RING_MIN_DP,
                                        )
                                        Spacer(Modifier.width(HERO_RING_GAP_DP.dp))
                                        RangeHero(rangeModel, Modifier.weight(1f))
                                    }
                                    // ⚠️ **v0.95.0**：结论区**整宽**（原来在上面的窄栏里）。
                                    RangeConclusions(rangeModel)
                                }
                            }
                        }
                        // ⚠️ **v0.94.0**：里程表那一行**整宽**渲染（原来在右侧窄栏里）。
                        //
                        // 真机实测（v0.92.0 记下、v0.93.0 顺延到本轮）：横排时右栏只有约 171dp，
                        // 而「里程表 101,537 公里（GPS 估算）」需要约 168dp ——
                        // **差 2dp** ⇒ 贪心折行把限定语劈成「（GPS 估」+「算）」，
                        // 看起来像渲染坏了（v0.85.0 的老规矩：宁可不说，也不说半句）。
                        //
                        // 现在它**整宽**（约 320dp），且限定语是**独立 Text 节点**
                        // ⇒ 任何字号下都**不可能**再被劈开。
                        OdometerLine(st)

                        // ⚠️ v0.14.0：中性横幅**下沉到主角数字之后**。
                        // 红色告警在第一（该动手的事最靠前），中性信息让位给主角。
                        fleetNotice?.let { NoticeBanner(it) }

                        // ⚠️ **v0.47.0（第 28 轮通勤车主评审 P0-1）**：「今天 / 本周」这一行。
                        //
                        // 车况页原来**只有静态车况**（电量 / 续航 / 锁车 / 空调 / 胎压），
                        // 没有任何「今天 / 本周」的信息 ——
                        // 而通勤者每天早上的第二个问题永远是
                        // 「这车今天开没开、这周怎么用的」。
                        //
                        // **零新增网络请求**：`drives` 已经在内存里（`store.drives`），
                        // 今天 / 本周的公里数、分钟数、行驶天数**纯客户端现算**。
                        //
                        // 放在主角正下方、`LocationCard` 之前 ——
                        // 主角回答「电够不够到公司」，这一行回答「今天开没开」。
                        val usageSummary = remember(drives) { computeUsageSummary(drives) }
                        TodayWeekRow(usageSummary)

                        // ═══ 首屏重排 ═══
                        // ⚠️ v0.8.1：原来顺序是「电量 → 能量条 → 充电卡 → 位置 → 6 个信息块」，
                        // 按 393×873dp 的机型算，**胎压和第三行信息块都在折叠线以下**，
                        // 而折叠线以下的东西 = 用户看不到 = 等于没有。
                        //
                        // 效率型车主的实测诉求：首屏要能同时看到
                        // 「电量 + 续航 + 位置 + 空调 + 车内温度」，**零滚动**。
                        // 现在把「位置」「空调 / 车内温度」提到电量环之后，
                        // 能量条与状态卡（充电中 / 行驶中）留在它们之后 ——
                        // **信息一点没删，只是把高频答案提前了**。
                        LocationCard(st, settings, showMap) { showMap = !showMap }

                        // ⚠️ **v0.115.0**：这两格原来**并排写死**（两个 `weight(1f)`）
                        // ⇒ 200% 下「31.5°C」要 **138.38dp**，而每格文字区只有 **130dp**
                        // ⇒ 放不下 ⇒ `maxLines=2` + `Ellipsis` 把**读数裁掉**。
                        // ⇒ 现在**列数由宽度预算决定**（`InfoTileRow`）：放得下并排，放不下单列。
                        val climateText = when (st.is_climate_on) {
                            // 车没上报时是 null，显示"关闭"会骗人 —— 必须区分「未知」
                            true -> "开启"
                            false -> "关闭"
                            null -> "未知"
                        }
                        InfoTileRow(
                            listOf(
                                "空调" to climateText,
                                "车内温度" to (st.inside_temp?.fmt(1, "°C") ?: "—"),
                            ),
                        )

                        // 能量条 —— 特斯拉最易识别的视觉母题，充电中转绿
                        EnergyBar(
                            soc = st.battery_level,
                            charging = st.active_charge != null,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )

                        // ⚠️ **v0.79.0：故障码**（Fleet Telemetry）。
                        //
                        // 位置在能量条之后、状态卡之前 —— **活跃故障比「正在充电」更该先看到**。
                        // 平时（遥测正常、无故障）这张卡**不出现**，首屏布局一点不变。
                        TelemetrySection(st.telemetry)

                        st.active_charge?.let { ChargeNowCard(it) }
                        // ⚠️ **v0.78.0**：不充电时，把「上次那次到底怎么了」说出来 ——
                        //    位置与「充电中」卡同一个格（状态卡的槽位），不额外占屏。
                        //    只在有话可说时出现（见 worthShowing）。
                        st.charge_outcome?.takeIf { it.worthShowing() }?.let { ChargeOutcomeCard(it) }
                        st.active_drive?.let { DriveNowCard(it, st) }

                        // ⚠️ v0.14.0（新手车主 P2-2）：**功率为 0 时整格隐藏**。
                        //
                        // 车停着的时候功率恒为 0，这一格没有任何信息量，
                        // 却占掉半个信息块的位置。
                        //
                        // ⚠️ 判据用「功率值就是 0」而不是「速度为空」——
                        // TeslaMate 停着时记的是 `speed = 0`（不是 null），
                        // 所以按 speed 判会漏掉（实测「功率 0 kW」照样显示）。
                        // 但「功率未知（null）」要显示「—」，不能一起藏掉。
                        val powerIdle = st.power != null && st.power == 0
                        // ⚠️ **v0.115.0**：同上面那一行 —— 列数由宽度预算决定（`InfoTileRow`）。
                        val outdoorTiles = mutableListOf(
                            "车外温度" to (st.outside_temp?.fmt(1, "°C") ?: "—"),
                        )
                        if (!powerIdle) {
                            outdoorTiles += "功率" to infoTileKwValue(st.power)
                        }
                        InfoTileRow(outdoorTiles)
                        // ⚠️ v0.8.5（效率车主复核）删掉「海拔 | 可用电量」两块：
                        //  · 海拔一年用一次，且 data-dictionary 自己写着「深圳平原常为 null」
                        //  · 「可用电量」和电量环是同一个数的两半（battery_level vs
                        //    usable_battery_level），本车实测 100/100 恒等 —— 纯重复
                        // 省下 74dp，胎压卡就能完整露出首屏（现在要滑 170dp 才看得到四个胎压值）。
                        TpmsCard(st)

                        // ⚠️ **v0.66.0（第 33 轮）：车辆控制**（车控下沉到本项目）。
                        //
                        // 位置：胎压之后 —— 胎压与车控都是「**当下**这辆车的状态与动作」，
                        // 而保养/最近 30 天是「提醒与回顾」。
                        //
                        // ⚠️ 锁车状态**未知**时（车休眠或没连实时状态源）传 null ——
                        // 卡上会写「锁车状态未知」，不会假装车没锁。
                        VehicleControlCard(
                            api = api,
                            carId = settings.carId,
                            locked = st.locked,
                            carAsleep = st.fleet_state == "asleep",
                            refreshSignal = controlRefresh,
                            // ⚠️ **v0.76.0**：命令之后立刻把车况读回来。
                            //
                            // 服务端在命令后已经回读过一次车况（缓存是新的），
                            // 这里再读一次 —— 锁车磁贴/空调状态会**当场**变，
                            // 而不是让用户对着「空调已开启」和「空调关闭」互相打脸两分钟。
                            //
                            // ⚠️ 走 `load()` 而不是 `refresh()`：后者会亮起整页的刷新指示器，
                            // 那是在说「页面在加载」，而这里只是背景里更新一次。
                            onCommandFinished = { scope.launch { load() } },
                            snackbarHostState = snackbarHostState,
                        )

                        // 保养 / 轮胎卡片（配过基准才出现）
                        if (reminders.isNotEmpty()) {
                            MaintenanceCard(
                                reminders = reminders,
                                carName = cars.firstOrNull { it.id == settings.carId }?.name,
                                onOpenSettings = onOpenMaintenance,
                            )
                        }

                        // ⚠️ **v0.46.0（第 27 轮复审）：把「最近 30 天」接回车况页。**
                        //
                        // ⚠️ **这张卡从 v0.8.5 起就没在任何人眼前出现过** ——
                        // `RecentSummaryCard` 只被 `ChargesScreen` / `DrivesScreen`
                        // 这两个**死屏**调用（顶层 Composable 从未被调用），
                        // 而 `ChargeListPage`（活的）又没接它。
                        //
                        // 它给的六个数（行驶里程 / 充入 / 充电次数 / 行程次数 /
                        // 平均能耗 / 快充占比）恰恰是「我最近到底怎么用车」的答案 ——
                        // 第 27 轮通勤车主复审把它点名为**通勤者最想看的那张卡**，
                        // 而它「写好了却没人调用」。
                        //
                        // 位置：胎压/保养之后、驾驶画像之前 ——
                        // 胎压与保养是「**当下**的车况」，
                        // 「最近 30 天」是「**回顾**」，驾驶画像/常驻点也是回顾。
                        // 回顾里最有用的那张排最前。
                        RecentSummaryCard(
                            settings = settings,
                            fallback = { },
                            // ⚠️ **v0.47.0（第 28 轮通勤车主评审 P0-3）**：
                            // 「行程次数 214 次」对通勤者读不懂（一天 9.7 段），
                            // 换成「行驶天数 22 天」。详见 [driveDaysInWindow]。
                            driveDays = remember(drives) { driveDaysInWindow(drives) },
                        )

                        // ⚠️ **v0.27.0：驾驶画像卡片**。
                        //
                        // 放在胎压/保养**之后** —— 它是「回顾」，
                        // 不该跟「现在能不能开」「胎压正不正常」这些**当下**的答案抢首屏。
                        DriveScoreCard(
                            profile = store.driveProfile,
                            loading = store.driveProfileLoading,
                        )

                        // ⚠️ **v0.28.0：常驻点卡片**（这台车平时停哪）。
                        StayPointsCard(
                            points = store.stayPoints,
                            loading = store.stayPointsLoading,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 续航**主角**（电量环右侧窄栏里只有它）。
 *
 * ⚠️ **v0.95.0**：这一块原来叫 `RangeBlock`，它同时渲染主角**和**结论区 ——
 * 于是结论区被挤进电量环右侧的**窄栏**（真机实测约 171dp），
 * 而结论行需要约 152dp、加上 ⓘ 徽标那一行的 20dp ⇒ **放不下** ⇒
 * 贪心折行把「88 条实测」劈成「88 条实」+「测」（真机实测）。
 *
 * 现在窄栏只负责**主角**（一屏一焦点），结论区由 [RangeConclusions] 整宽渲染。
 */
@Composable
private fun RangeHero(model: RangeModel, modifier: Modifier = Modifier) {
    Column(modifier) {
        BigStat(
            // ⚠️ **v0.120.0**：数字与标签都从**唯一事实源**取
            // （`ui/RangeArrangement.kt`）⇒ 排布判定用的宽度
            // 与**真正渲染出来**的字永远是同一份 ⇒ 两边不会漂。
            rangeHeroValue(model),
            // ⚠️ v0.9.0（新手车主复核）：「真实可达 (km)」是术语。
            // 车主原话：「'可达'太书面」「我真正要的是一句话：
            // '现在能开 206 公里，高速算的，够你回家'」。
            // 大数字 + 这行小字，读起来就是「现在能开 206 公里」。
            // ⚠️ **v0.26.0（第 17 轮通勤车主 P0-2）：低温样本不足时要说清为什么退回标称。**
            // 原来只写「官方标称（公里）」，用户会以为 App 出故障了。
            rangeHeroLabel(model),
            // ⚠️ **v0.14.0（苹果 HIG P0-1）：**主角级** —— 44sp。
            // 34sp 和页面标题「车况」同级，主角就不成立（每屏要有唯一视觉锚点）。
            hero = true,
        )
    }
}

/**
 * 续航**结论区**：区间 / 保底 / 「怎么算的」折叠入口。
 *
 * ⚠️ **v0.95.0（本轮手术点）**：它原来渲染在电量环右侧的**窄栏**里
 * （真机实测约 171dp），而结论行「约 191 ~ 249 公里 · 88 条实测」需要约 152dp，
 * 加上 ⓘ 徽标那一行的 20dp ⇒ **放不下** ⇒ 贪心折行把「88 条实测」劈成
 * 「88 条实」+「测」（v0.92.0 真机实测记下，v0.93.0 / v0.94.0 顺延到本轮修掉）。
 *
 * ## 为什么不能靠「改文案」救
 *
 * CJK 允许在**任意两个汉字之间**断行 ⇒ 只要结论行和 ⓘ 徽标还在同一个窄栏里，
 * 它就随时可能被劈开；而且窄栏宽度**随机型与字号变化**（360dp 屏更窄、
 * 放大字号更窄）⇒ 任何「刚好放得下」的写法都是**运气**，不是结构。
 *
 * ⇒ 唯一的结构性解法：**结论区整宽渲染**（与 v0.94.0 的里程表同一手法）。
 *
 * ## 窄栏的职责只有一个：主角
 *
 * 主角 + 3 行灰字挤在 171dp 里 = v0.86.0 抱怨过的「同权重的灰字墙」。
 * 整宽之后这三行各自**一行就够**（区间 ≈152dp、保底 ≈240dp，整宽 361dp 放得下）
 * ⇒ 视觉行数从 5 行降到 3 行。
 *
 * ⚠️ 这个函数**故意不接 modifier** —— 结构上就不可能再把结论区塞回窄栏。
 */
@Composable
private fun RangeConclusions(model: RangeModel) {
    val accents = LocalAccentColors.current
    Column(Modifier.fillMaxWidth()) {
        // ⚠️ **v0.86.0（真机复核 · 第二次迭代）**：**恢复 v0.43.0 的「区间紧跟主角」**。
        //
        // 第一版把「保底结论」提到区间前面（想强调「我能到哪」），
        // 但那**违反了 v0.43.0 的防误读约束**：区间是**主角 217 的**区间，
        // 排在保底行下面 ⇒ 读者会把「约 192 ~ 251 公里」读成 **167 公里**的区间，
        // 而 192 > 167 ⇒ 看起来像 App 自相矛盾。
        //
        // ⇒ **顺序不动**（主角 → 区间 → 保底），要减的是**行数**不是**顺序**。
        //
        // ⚠️ 现在这一块一共 **3 行**小字（原 5 行）：
        //   ①「约 192 ~ 251 公里 · 88 条实测」= 主角的区间 + 来源自曝（**1 行**）
        //   ②「留 20% 保底还能开 167 公里（约 148 ~ 193 公里）」= 保底结论（**1 行**，自带区间）
        //   ③「怎么算的 ⌄」= 入口
        if (model.trueRange != null) {
            // ⚠️ **v0.86.0（真机复核 · 用户原话「小字太多」）**：主角下面原来有 **4~5 行**小字
            // （区间 / 保底 / 来源 / 怎么算的）⇒ 视觉上是一堵**同权重的灰字墙**。
            //
            // 现在：**「区间」与「来源」并成一行**（日期区间已折进 ⓘ），
            // 整个区从 **5 行 → 3 行**。
            //
            // ⚠️ 这两样**都不能删** ——
            //    区间是 v0.65.0（车主点名要「一眼看得见」）、
            //    来源是 v0.26.0（新手分不清「实测」与「标称」）——
            //    但它们可以**并成一行**，**数据一点没丢**。
            model.band?.let { (txt, note) ->
                rangeConclusionText(txt, model.scopeLine)?.let { conclusion ->
                    // ⚠️ **v0.114.0**：判定要用**生效**的字号
                    //（`labelMedium` 的字号 × 系统字体倍率）⇒ 宽度预算才是真的。
                    val fontSizeSp = MaterialTheme.typography.labelMedium.fontSize.value *
                        LocalDensity.current.fontScale
                    if (rangeConclusionNeedsSplit(conclusion, fontSizeSp)) {
                        // 放不下 ⇒ **拆成完整短语各占一行**（ADR #42）：
                        // 每块独立成 Text 节点 ⇒ 独立段落 ⇒ **没有内部断点**
                        // ⇒ 「88 条实测」永远不会被劈成「88 条实」+「测」。
                        TextWithNote(
                            text = conclusion.parts[0],
                            note = listOfNotNull(note, model.scopeNote).joinToString("；"),
                            a11yLabel = "续航区间与实测样本的说明",
                        )
                        Text(
                            conclusion.parts[1],
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        TextWithNote(
                            text = conclusion.joined,
                            note = listOfNotNull(note, model.scopeNote).joinToString("；"),
                            a11yLabel = "续航区间与实测样本的说明",
                        )
                    }
                }
            }
        }
        if (model.floorKm != null && model.trueRange != null) {
            Text(
                "留 20% 保底还能开 ${model.floorKm.fmt(0)} 公里" +
                    (model.floorBand?.let { (lo, hi) -> "（约 $lo ~ $hi 公里）" } ?: ""),
                style = MaterialTheme.typography.labelMedium,
                // ⚠️ **v0.86.0（真机复核）**：这行是**「我能到哪」的另一个答案** ⇒
                // 提为**半粗 + 正文色**。原来和其它小字一样是灰的 ⇒
                // 主角 217 下面没有一句「结论」，只有一排同权重的说明文字。
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        // ⚠️ **v0.26.0（第 17 轮新手车主 P0-2）：来源与温度可信度常驻显示。**
        // ⚠️ v0.86.0：「来源」已并进上面的区间脚注 ⇒ 这里只在**没有区间**时单独出现。
        if (model.scopeLine != null && model.band == null) {
            if (model.scopeNote != null) {
                // ⚠️ **v0.65.0（第 32 轮「减啰嗦」）**：温度口径折进 ⓘ，
                // 「N 条实测」这半句仍然常驻（来源自曝是 v0.26.0 P0-2 的硬要求）。
                TextWithNote(
                    text = model.scopeLine,
                    note = model.scopeNote,
                    style = MaterialTheme.typography.labelSmall,
                    a11yLabel = "续航实测样本的说明",
                )
            } else {
                Text(
                    model.scopeLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        model.tempCaveat?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = accents.warnAmberText,
            )
        }
        Spacer(Modifier.height(4.dp))
        CollapsibleSection("怎么算的") {
            Text(
                model.explainText.ifBlank { "暂无足够数据估算" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 里程表那一行（**整宽**）。
 *
 * ⚠️ **v0.94.0**：它原来渲染在 [RangeBlock] 里 ⇒ 落在电量环右侧**窄栏**，
 * 那儿只够放下「里程表 101,537 公里（GPS 估」——
 * **限定语被折行劈成两半**（v0.92.0 真机实测记下，v0.93.0 顺延到本轮）。
 *
 * ## 为什么限定语必须**独立成节点**
 *
 * CJK 的折行规则允许在**任意两个汉字之间**断行 ⇒ 只要限定语和数字在**同一个段落**里，
 * 它就可能被劈开（「估算」→「估」+「算」）。
 * 唯一的结构性解法：**限定语单独一个 Text**（独立段落 ⇒ 只有整体换行，没有内部断点）。
 *
 * ⚠️ 数字那半用 `weight(1f, fill = false)`：
 * 放得下就贴着限定语（视觉上与原来一模一样），
 * 放不下时**数字**自己换行，限定语仍然整体不动。
 */
@Composable
private fun OdometerLine(st: CarStatus) {
    val (numText, qualifier) = odometerParts(st.odometer, st.odometer_vehicle)
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            numText,
            // ⚠️ v0.14.0（单位审校 P0-3）：**「km」→「公里」**。
            // 同一屏里保养横幅写「99000 公里」「101455 公里」，
            // 这行却写「101,455 km」—— 两种单位 + 两种数字格式，
            // 用户会以为是两个数。
            //
            // ⚠️ v0.21.0：**把 GPS 里程表与车端里程表分开说** ——
            // 车主原话：「我拿 App 的里程表和特斯拉 App 对，两个数差着几百公里，
            // 于是我怀疑车被人动了、或者 App 在瞎报。」
            //
            // 两个数都不是错的，只是**来源不同**：
            //   st.odometer          = TeslaMate 按 GPS 点累计（估算）
            //   st.odometer_vehicle  = 车辆自己记的（真值）
            // 说清楚来源，比只给一个数更让人放心。
            //
            // ⚠️ **v0.75.0（查库实测）：「里程表」= 仪表盘上那个数 = 车端。**
            // 口径与理由见 [odometerParts]（Fmt.kt）：
            // `positions.odometer` 的**绝对值**被停车时的 GPS 抖动污染
            // （本机实测日漂移可达 1,143 公里，全窗口偏 **38,422 公里**），
            // 而它的窗口**跨度**是准的 —— 所以绝对值不能叫「里程表」。
            //
            // 原来是把两个数并列（GPS 估算 + 车端 + 差 N 公里），
            // 那恰恰会制造 v0.21.0 想消灭的怀疑：「我拿 App 的里程表和特斯拉 App 对，
            // 于是我怀疑车被人动了」。
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            qualifier,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 锁车状态标签。
 *
 * 三态，且**不靠颜色单独表达**（WCAG 1.4.1）：
 * - `true`  → 锁形图标 + 「已上锁」+ 中性灰
 * - `false` → 开锁图标 + 「未上锁」+ 琥珀（图标和文字同时变化，色觉障碍用户也读得出）
 * - `null`  → 问号图标 + 「锁车状态未知」+ 中性灰
 *
 * ⚠️ 未知**绝不能显示成「已上锁」或「未上锁」** —— 那是在编造一个安全相关的状态。
 * 未取到数据就说没取到。
 *
 * ⚠️ v0.8.5 三处修正（效率型车主复核）：
 *
 * 1. **`null` 不再退回「在线/休眠/离线」胶囊**。
 *    车主原话：「锁是我第一诉求，null 就该直说『锁：未知』」。
 *    之前把 null 悄悄换成状态胶囊，等于**把「没取到」伪装成「没这功能」**。
 *    现在三态都显式呈现。
 * 2. **锁车状态带自己的时间戳**。
 *    右边那个「更新于 3 分钟前」是 **TeslaMate 车端上报**的时间，
 *    锁车来自 **Fleet API 自己的 `locked_at`** —— 两个数据源、两个时钟。
 *    并排放会让人以为锁车也是 3 分钟前的。所以锁车后面跟**它自己**的时间。
 *    ⚠️ **v0.92.0**：这条**取消了** —— 整行现在**只留一个时间戳**
 *    （见 [TopStatusLine]），v0.8.5 要避开的「两个时间戳并排被当成同一个」
 *    这个冲突**已经不存在**了 ⇒ 再挂一个只会重新制造「已上锁 11 小时前」
 *    （读成「锁车发生在 11 小时前」）这种歧义。
 * 3. **删掉「· 离线」后缀**。车停着不动时 TeslaMate 天天报 offline（这是常态，
 *    不是异常），挂着它只会让人以为锁车状态有问题。
 *
 * ⚠️ **v0.92.0**：四种「未知」里的分隔符从「·」改成「，」——
 * 这一行上「·」是**词条之间**的分隔符，句子内部再用「·」会分不清层级
 * （「锁车状态 · 授权已失效 · 车窗车门已关」）。
 */
@Composable
private fun LockStateChip(locked: Boolean?, fleetState: String? = null) {
    val accents = LocalAccentColors.current
    val icon: androidx.compose.ui.graphics.vector.ImageVector
    val text: String
    val tint: androidx.compose.ui.graphics.Color
    // ⚠️ v0.22.0（自托管运维评审 P1-4）：**「未知」拆成四种**。
    //
    // 「没取到」其实是四种完全不同的情况，不分开就会误导：
    //   disabled     这台服务器**没配 Fleet 凭据** → 永远不会有值（不是故障）
    //   asleep       车在休眠 → **正常状态**，等它醒来就更新（不是故障）
    //   unauthorized 授权失效 → 一次性 token 过期，**不会自愈**，要人工重新授权
    //   unreachable  云端故障 → 会自愈（退避重试中）
    //
    // 原来一律说「锁车状态未知」—— 车主读到「未知」会焦虑
    // （原话：「到底锁没锁？？能锁吗？」），
    // 而现在能直接告诉他**这是哪一种**，以及**要不要动手**。
    when {
        locked == true -> {
            icon = Icons.Filled.Lock
            text = "已上锁"
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        }
        locked == false -> {
            icon = Icons.Filled.LockOpen
            text = "未上锁"
            tint = accents.warnAmber
        }
        fleetState == "disabled" -> {
            icon = Icons.Filled.HelpOutline
            text = "锁车状态，未接入"
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        }
        fleetState == "asleep" -> {
            icon = Icons.Filled.HelpOutline
            text = "车在休眠，锁车状态等它醒来才更新"
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        }
        fleetState == "unauthorized" -> {
            icon = Icons.Filled.ErrorOutline
            text = "锁车状态，授权已失效"
            tint = accents.danger
        }
        fleetState == "unreachable" -> {
            icon = Icons.Filled.ErrorOutline
            text = "锁车状态，暂时连不上"
            tint = accents.warnAmber
        }
        else -> {
            icon = Icons.Filled.HelpOutline
            text = "锁车状态未知"
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = tint,
            // ⚠️ v0.13.0：15dp → 18dp。
            // 「锁车状态未知」那行的 HelpOutline 是个**细线圆圈**，
            // 15dp 下画出来只有 ~10dp，和 App 内其他 18~20dp 的实心图标不同类，
            // 看起来像一个小小的问号噪点。
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}

/**
 * 页首状态区 —— **一行**。
 *
 * ⚠️ **v0.92.0**。
 *
 * ## 原来是什么
 *
 * 页首原本是**两行**（外加一行固件更新）：
 *
 *   ① `🔒 已上锁 11 小时前`                      `车况 · 11 小时前`
 *   ② `车窗 全关 · 车门 全关 · 哨兵 未开启 · 11 小时前`
 *
 * 三个问题：
 *
 * 1. **同一个「11 小时前」出现了三次** —— 车主原话（v0.9.0）：
 *    「两个时间戳挨着，我以为是同一个」。
 * 2. **第二行报的三项全是「没事」** ——「全关」「未开启」不是事件，
 *    却和「已上锁」挤在同一个视觉层级里。
 * 3. **「已上锁 11 小时前」有歧义** —— 读成「锁车发生在 11 小时前」。
 *
 * ## 现在是什么
 *
 * * **一行**：锁车 + 窗门结论 + 哨兵结论 + **一个**时间戳。
 * * **只报值得报的**（详见 [topStatusFacts]）。
 * * **时间戳只留一个**，取这行上**最旧**那份数据的时刻 ——
 *   宁可显得更旧，不可显得更新。
 *
 * ## 「已关」这句话只在真知道时才能说
 *
 * 车窗 / 车门各是三态（`null`=没读到 / `[]`=全关 / 列表=开着这些）。
 * **两扇都读到且都关着**才说「车窗车门已关」；
 * 只读到一项就说那**一项**（「车窗已关」）；
 * 「没读到」的那项**不声称已关** —— 沉默可以，冒充不行。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun TopStatusLine(st: CarStatus, cachedAt: Instant?) {
    // ⚠️ **时间诚实**：这行上**最旧**的那份数据有多旧？
    //
    // 只取 `locked_at`（锁车结论的时刻）与 `vehicle_safety_at`
    // （车窗 / 车门 / 哨兵三项里最旧的那个）—— 这行上就这两类数据。
    //
    // ⚠️ **不取 `updated_at`**：那是「整页车况」的新鲜度，不在这一行上；
    // 混进来会让一句**新鲜**的话旁边挂一个很旧的时间
    // （车醒着、锁车是新的，但车况整体很旧 ⇒ 平白显得这条状态很旧）。
    val lineAge = remember(st.locked_at, st.vehicle_safety_at) {
        listOfNotNull(st.locked_at, st.vehicle_safety_at).minOrNull()
    }

    val facts = remember(
        st.open_windows, st.open_windows_source,
        st.open_doors, st.doors_source,
        st.sentry_state, st.sentry_text,
    ) {
        topStatusFacts(st)
    }

    // ⚠️ **v0.9.0**：显示的这份数据如果是**落盘缓存**里的，必须说清楚
    // 「这是上次读到的」—— 一个会撒谎的缓存比没有缓存更糟。
    val timeText = when {
        cachedAt != null -> "上次读到 · ${cachedAt.relTime()}"
        lineAge != null -> "车况 · ${lineAge.relTime()}"
        else -> null
    }

    // ⚠️ **顶对齐**：左边的词条多到要换行时，时间戳要跟**第一行**齐 ——
    // 居中的话它会浮在两行中间，两行都对不齐（实测看着像飘着的）。
    Row(verticalAlignment = Alignment.Top) {
        // ⚠️ **FlowRow**：放不下就**换行**，绝不省略号截断
        // （v0.85.0 在 ChargesScreen / DrivesScreen / 这里连踩三处的坑）。
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // 锁车词条自带**后置**分隔符 —— 这样换行时不会在行首凭空多一个点。
            Row(verticalAlignment = Alignment.CenterVertically) {
                LockStateChip(st.locked, st.fleet_state)
                if (facts.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "·",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            facts.forEachIndexed { idx, f ->
                StatusFactRow(
                    text = f.text,
                    tone = f.tone,
                    withSeparator = idx < facts.size - 1,
                )
            }
        }
        if (timeText != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                timeText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 页首状态区的一个**词条**（文案 + 语气）。
 *
 * ⚠️ 分隔符「·」**后置**且**属于这一条** ——
 * 换行时它跟着上一条走，不会让第二行开头凭空多一个点。
 */
@Composable
private fun StatusFactRow(text: String, tone: SafetyTone, withSeparator: Boolean) {
    val accents = LocalAccentColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = when (tone) {
                SafetyTone.WARN -> accents.warnAmber
                SafetyTone.DANGER -> accents.danger
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (withSeparator) {
            Spacer(Modifier.width(6.dp))
            Text(
                "·",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 页首状态区要说的**安全结论**。
 *
 * ⚠️ **v0.92.0**：只报值得报的。
 *
 * - **开着** ⇒ 点名（琥珀）—— 这是要动手的事。
 * - **都没开** ⇒ 才说一句「已关」，而且**只在真知道全关时**。
 * - **哨兵**：只有「警报触发」（红）与「在守护」才出现；
 *   待命 / 静默 / 未开启 / 不支持 / 不知道 **一律不出现** ——
 *   常态不是事件，报了就是噪音。
 *
 * ⚠️ 哨兵的中文**只从服务端拿**（`sentry_text`），
 * App 不自己翻译状态词 —— 两边各写一份，迟早漂移。
 */
private fun topStatusFacts(st: CarStatus): List<StatusFact> {
    val facts = mutableListOf<StatusFact>()
    val win = openState(st.open_windows, st.open_windows_source)
    val door = openState(st.open_doors, st.doors_source)
    val anyOpen = win == OpenState.OPEN || door == OpenState.OPEN

    if (win == OpenState.OPEN) {
        facts += StatusFact(openPhrase(st.open_windows!!, "扇", "窗"), SafetyTone.WARN)
    }
    if (door == OpenState.OPEN) {
        facts += StatusFact(openPhrase(st.open_doors!!, "个", "门"), SafetyTone.WARN)
    }
    if (!anyOpen) {
        when {
            win == OpenState.CLOSED && door == OpenState.CLOSED ->
                facts += StatusFact("车窗车门已关", SafetyTone.NORMAL)
            win == OpenState.CLOSED ->
                facts += StatusFact("车窗已关", SafetyTone.NORMAL)
            door == OpenState.CLOSED ->
                facts += StatusFact("车门已关", SafetyTone.NORMAL)
        }
    }
    // ⚠️ 哨兵：只看**归一化状态词**（服务端给的），不猜。
    when (st.sentry_state) {
        "panic" -> facts += StatusFact("哨兵" + st.sentry_text.ifBlank { "警报触发" }, SafetyTone.DANGER)
        "armed", "aware", "on" ->
            st.sentry_text.takeIf { it.isNotBlank() }?.let { facts += StatusFact("哨兵$it", SafetyTone.NORMAL) }
    }
    return facts
}

/**
 * 页首状态区里的一句结论（文案 + 语气）。
 */
private class StatusFact(val text: String, val tone: SafetyTone)

/**
 * 状态的语气 —— 决定颜色。
 */
private enum class SafetyTone { NORMAL, WARN, DANGER }

/**
 * 窗 / 门 的三态。
 */
private enum class OpenState { UNKNOWN, CLOSED, OPEN }

/**
 * 「开着没」的三态判定 —— **服务端给什么就是什么**，App 不自己猜。
 *
 * - 来源为空 / 值为 null ⇒ **没读到**（不是「都关着」）
 * - 空数组 ⇒ **确定全关**
 * - 有内容 ⇒ 开着这些
 */
private fun openState(open: List<String>?, source: String): OpenState = when {
    source.isBlank() -> OpenState.UNKNOWN
    open == null -> OpenState.UNKNOWN
    open.isEmpty() -> OpenState.CLOSED
    else -> OpenState.OPEN
}

/**
 * 点名开着的那几个 —— 「左前窗开着」比「车窗有开着的」有用得多
 * （你要关的就是它）。
 *
 * ⚠️ **v0.92.0**：模板里的 `$noun` **必须带花括号** ——
 * Kotlin 的标识符**允许 CJK 字符**，「开」「着」都能进标识符，
 * 所以 `$noun开着` 会被解析成一个叫「noun开着」的标识符 ⇒ 编译报错。
 */
private fun openPhrase(names: List<String>, unit: String, noun: String): String =
    if (names.size == 1) "${names[0]}开着" else "${names.size} $unit${noun}开着"

/**
 * 位置卡：地点名 + 点击展开地图。
 *
 * v0.8.1 从 StatusScreen 里抽出来 —— 首屏重排时它被提到电量环之后，
 * 内联在页面里会让那个 Composable 长到 500 行，读不动。
 */
@Composable
private fun LocationCard(
    st: CarStatus,
    settings: AppSettings,
    showMap: Boolean,
    onToggle: () -> Unit,
) {
    val lat = st.map_lat ?: st.lat
    val lng = st.map_lng ?: st.lng
    if (lat == null || lng == null) {
        // ⚠️ v0.9.0：没有位置时**不再整卡消失**（原来直接 return）。
        // 「少了位置」和「车在地下车库」看起来一样，用户无从判断。
        // 给一行灰字说明，比凭空消失诚实。
        SectionCard(title = "位置") {
            // ⚠️ **v0.71.0（减啰嗦深挖）**：**主句常驻 + 解释折进 ⓘ**。
            //
            // 原来是一句 24 字的长句：「暂无位置数据。车辆位置需要 TeslaMate 采集到定位信息后才会出现。」
            // —— 空态的**主句**用户要马上知道，**原因**是补充说明。
            Text(
                "暂无位置数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InfoNote(
                text = "车辆位置需要 TeslaMate 采集到定位信息后才会出现。",
                label = "为什么没有？",
                a11yLabel = "为什么没有位置数据",
            )
        }
        return
    }
    val accents = LocalAccentColors.current
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = accents.cardSurface,
        modifier = Modifier
            .fillMaxWidth()
            .cardDepth()
            .pressable(onClick = onToggle),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "位置",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // ⚠️ v0.7.1：箭头原来孤零零吊在地点名**下方左对齐**，
                // 看着像掉下来的图标。移进标题行右端，与标题垂直居中。
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = if (showMap) "收起地图" else "展开地图",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            // 地点名是完整邮编地址（"龙山九路, 西区街道, 惠州大亚湾…, 516083, 中国"），
            // 占两行且没有信息增量 —— 主标题只取**第一段**。
            //
            // ⚠️ v0.14.0（新手车主 P2-2）：副行也**不再写整条地址** ——
            // 原来写「新湖路, 永丰社区, 西乡街道, 宝安区, 深圳市, 广东省, 518100, 中国」，
            // 一整行都是废话。现在只留行政区层级「宝安区 深圳市」。
            val fullPlace = st.location_name ?: "未命名地点"
            val shortPlace = fullPlace
                .substringBefore('，')
                .substringBefore(',')
                .trim()
                .ifBlank { fullPlace }
            val districtLine = fullPlace.districtCity()
            Text(
                shortPlace,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (districtLine != null) {
                Text(
                    districtLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AnimatedVisibility(
                visible = showMap,
                // ⚠️ **v0.49.0（第 28 轮 HIG 评审 P1-2）**：补上规格 ——
                // 原来**完全没传 animationSpec** ⇒ 高度走框架默认 spring(400)、
                // 淡入走默认 tween(150) —— 与别处的「展开」不是同一个动作。
                // 现在三处「展开」**逐字相同**。
                enter = fadeIn(Motion.FadeIn) + expandVertically(Motion.ExpandSize),
                exit = fadeOut(tween(Motion.ExpandFadeOut)) + shrinkVertically(Motion.ExpandSize),
            ) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    TrackMap(
                        points = listOf(GeoPt(lat, lng)),
                        drawLine = false,
                        pointTitle = "车辆位置：$shortPlace",
                        settings = settings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                    )
                }
            }
        }
    }
}

/**
 * 胎压卡：俯视车辆 + 一个结论。
 *
 * 俯视图是本 App **唯一能超越官方 Tesla App** 的地方（官方不展示四轮胎压）——
 * 它留着；但**四个读数不再与它并排铺开**。
 *
 * ⚠️ **v0.91.0（Apple CDO 轮）**：原来「胎压正常时」也把四个数一字排开，
 * 还常驻一句阈值科普 —— 正是使命书 §2.1 点名的反面教材
 * （胎压正常时不需要四个轮胎飙绿字刷存在感）。
 * 现在：
 * - **正常** ⇒ 表层只给一个结论「胎压正常」+ 一行实测区间；
 *   逐轮数值与阈值说明折进「更多明细」。
 * - **异常 / 接近阈值** ⇒ 结论升级成告警行（红 / 琥珀），
 *   逐轮数值**留在表层** —— 车主要靠它分清「一胎漏气」还是「四胎都低」。
 *
 * ⚠️ **本轮修的口径不一致（真问题）**：标题、告警文案、轮子颜色以前各读各的字段 ——
 * 标题读旧源 `tpms_alert`、告警文案读旧源 `tpms_alert_detail`
 * （实时源的 `tpms_live_alert_detail` **从来没被读过**）、颜色读「实时 + 旧」的合并结果。
 * ⇒ 实时源报警而 TeslaMate 恰好没采样时，
 * **轮子会变红、标题却还是「轮胎胎压」、告警文案一片空白**。
 * 现在三者统一走 `reading.isAlerting` / `reading.isNear` ——
 * 与服务端 `api/alerts.go` 同一套阈值、同一套口径。
 */
@Composable
private fun TpmsCard(st: CarStatus) {
    val accents = LocalAccentColors.current
    // ⚠️ v0.8.5：优先用 **Fleet API 的实时胎压**（车醒着时每 2 分钟一条，明显比
    // TeslaMate 的稀疏采样新鲜），没有才回退 TeslaMate 的值。
    val fl = st.tpms_live_fl ?: st.tpms_fl
    val fr = st.tpms_live_fr ?: st.tpms_fr
    val rl = st.tpms_live_rl ?: st.tpms_rl
    val rr = st.tpms_live_rr ?: st.tpms_rr

    // ⚠️ v0.9.0：四轮全空时**不再整卡消失**。
    // 原来直接 `return`，用户看到的是「少了一块」—— 不知道是坏了、没配、
    // 还是本来就没数据。改成显示一行灰字说明，数据照常占位。
    if (fl == null && fr == null && rl == null && rr == null) {
        SectionCard(title = "轮胎胎压") {
            // ⚠️ **v0.71.0（减啰嗦深挖）**：**主句常驻 + 解释折进 ⓘ**。
            //
            // 原来是一句 36 字的长句 —— 空态的**主句**用户要马上知道，
            // 「实时胎压要配 Fleet API / 未配置时是稀疏采样」是**补充说明**。
            Text(
                "暂无胎压数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InfoNote(
                text = "实时胎压需要配置 Tesla Fleet API；" +
                    "未配置时显示的是 TeslaMate 的稀疏采样（车开动或充电时才记）。",
                label = "为什么没有？",
                a11yLabel = "为什么没有胎压数据",
            )
        }
        return
    }

    // 告警轮次：实时源的判定（Tesla 自己的 hard/soft warning）优先
    val alertWheels = (st.tpms_live_alert_wheels + st.tpms_alert_wheels).toSet()
    val warnWheels = st.tpms_live_warn_wheels.toSet()
    val reading = TpmsReadingUi(
        fl, fr, rl, rr, st.tpms_min_bar, st.tpms_max_bar,
        alertWheels = alertWheels,
        warnWheels = warnWheels,
    )

    // ⚠️ **v0.91.0：标题 / 告警文案 / 轮子颜色必须是同一个口径。**
    // 三者统一问 `reading`：这个轮到底算不算异常 / 算不算接近阈值 ——
    // 与服务端 `api/alerts.go` 用的是同一套阈值。
    val wheels = listOf("左前" to fl, "右前" to fr, "左后" to rl, "右后" to rr)
    val badWheels = wheels.filter { (label, v) -> reading.isAlerting(v, label) }
    val nearWheels = wheels.filter { (label, v) -> reading.isNear(v, label) }
    val alerting = badWheels.isNotEmpty()
    val warning = !alerting && nearWheels.isNotEmpty()

    // 告警文案：**实时源优先**（跟「实时数值优先」同一条规则），旧源兜底，
    // 最后本地拼 —— 取第一个非空。
    val alertText = st.tpms_live_alert_detail.takeIf { it.isNotBlank() }
        ?: st.tpms_alert_detail.takeIf { it.isNotBlank() }
        ?: badWheels.joinToString("  ") { (label, v) -> "$label ${v.fmt(2)} bar" }
    // 接近阈值：服务端只给轮名，数值从**同一批读数**里取（不另算）。
    val warnText = nearWheels.joinToString("  ") { (label, v) -> "$label ${v.fmt(2)} bar" }

    // 实测区间：只统计**真的有数**的轮 —— 说「四轮」就得真的有四轮。
    val measured = wheels.mapNotNull { (_, v) -> v }
    val loS = measured.minOrNull()?.fmt(2).orEmpty()
    val hiS = measured.maxOrNull()?.fmt(2).orEmpty()
    // ⚠️ 比较**格式化之后**的字符串 —— 2.900000043 与 2.9 会显示成同一个「2.90」，
    // 按原值比会印出「2.90–2.90 bar」这种一眼假的区间。
    val rangeText = if (loS == hiS) "$loS bar" else "$loS–$hiS bar"
    val rangeLabel = if (measured.size == 4) {
        "四轮 $rangeText"
    } else {
        "仅测到 ${measured.size} 轮 · $rangeText"
    }

    // ⚠️ **v0.91.0**：阈值说明在**正常时**是「规则」而不是「当下」⇒ 折进二级；
    // 有异常时它就是**行动依据** ⇒ 留在表层。
    val rangeHint = "冷胎标准 ${st.tpms_min_bar}–${st.tpms_max_bar} bar · 超出这个范围需要检查"

    SectionCard(
        title = when {
            alerting -> "轮胎胎压 · 建议检查"
            warning -> "轮胎胎压 · 接近阈值"
            else -> "轮胎胎压"
        },
    ) {
        // ⚠️ **v0.105.0（用户反馈 #24「胎压这一块不需要显示这个图标」）**：
        // **俯视图只在「有事」时才画。**
        //
        // 那张俯视图在**四轮都正常**时，四个轮子全是灰的 ⇒ 它只说明「这是一辆车」，
        // 纯装饰，却占掉卡片近一半的宽度、把「胎压正常」挤到右边
        // （用户原话：「很难理解」「不需要显示这个图标」）。
        // ⇒ 正常时**整行不画** ⇒ 结论独占整宽、左对齐（卡片的左边线）。
        //
        // ⚠️ 告警/接近阈值时**仍然画** —— 那时它是**信息**：
        // 它标出「哪个轮子」偏了（红/琥珀），比四行文字更快被看见。
        if (alerting || warning) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                VehicleTopView(reading = reading, modifier = Modifier.weight(0.9f))
                Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // ⚠️ **v0.91.0**：异常时**必须更醒目** ——
                    // 结论升成告警行（红 / 琥珀），轮子颜色之外还有文字（WCAG 1.4.1）。
                    val noticeColor =
                        if (alerting) MaterialTheme.colorScheme.error else accents.warnAmber
                    val noticeText = if (alerting) alertText else warnText
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.WarningAmber,
                            contentDescription = null,
                            tint = noticeColor,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            noticeText,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = noticeColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        rangeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // ⚠️ **v0.91.0**：没事就安静 —— 一个结论 + 一行实测区间。
            // 四个读数与阈值说明折进下面的「更多明细」。
            // ⚠️ **v0.105.0**：这里**不再**与俯视图并排 ⇒ 独占整宽。
            BigStat("胎压正常", rangeLabel, mid = true)
        }
        if (alerting || warning) {
            // 有事时明细**留在表层**（不折）—— 车主要靠它分清「一胎漏气」还是「四胎都低」。
            TpmsDetailGrid(fl, fr, rl, rr, reading)
            Text(
                rangeHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CollapsibleSection(label = "更多明细") {
                // ⚠️ **AnimatedVisibility 的内容 lambda 不是 ColumnScope** ——
                // 多个 composable 直接并列会**全部叠在同一位置**（只看得见最后一行）。
                // 必须自己包一层 Column。
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TpmsDetailGrid(fl, fr, rl, rr, reading)
                    Text(
                        rangeHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 四轮读数（**两列**排布：左轮一列、右轮一列 —— 与俯视图的左右一致，
 * 不用读者再把「左前」对到图上的左上角）。
 */
@Composable
private fun TpmsDetailGrid(
    fl: Double?,
    fr: Double?,
    rl: Double?,
    rr: Double?,
    reading: TpmsReadingUi,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TpmsValue("左前", fl, reading.isAlerting(fl, "左前"))
            TpmsValue("左后", rl, reading.isAlerting(rl, "左后"))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TpmsValue("右前", fr, reading.isAlerting(fr, "右前"))
            TpmsValue("右后", rr, reading.isAlerting(rr, "右后"))
        }
    }
}

/**
 * 保养与轮胎卡片。
 *
 * ⚠️ v0.9.0（新手车主复核）：「上次保养是什么时候 / 什么时候该换胎」——
 * 之前全项目 0 命中，是车主清单里的第一条。
 *
 * 放在页面**末尾**（胎压卡下面）而不是顶部：
 * 它是**参考信息**而不是「现在要动手」的信息 ——
 * 真的要动手时，顶部会另挂一条横幅（见 StatusScreen）。
 */
@Composable
private fun MaintenanceCard(
    reminders: List<MaintenanceReminder>,
    /** ⚠️ v0.16.0：当前车名（基准是按车隔离的，得说清是哪台车）。 */
    carName: String?,
    /** ⚠️ v0.16.0：点这行去「设置」页改基准。 */
    onOpenSettings: () -> Unit,
) {
    val accents = LocalAccentColors.current
    val ctx = LocalContext.current
    SectionCard(title = "保养与轮胎") {
        reminders.forEach { r ->
            val color = when {
                r.overdue -> MaterialTheme.colorScheme.error
                r.dueSoon -> accents.warnAmber
                else -> MaterialTheme.colorScheme.onSurface
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.overdue) {
                    Icon(
                        Icons.Filled.WarningAmber,
                        // ⚠️ **v0.47.0（第 28 轮 a11y P1-1）**：`null` ——
                        // 文字（`r.describe()` = 「已超期 2455 公里」）已经说了同一件事，
                        // 图标再念一遍就是**重复朗读**；
                        // 而且这一行**不是** clickable、也没开 mergeDescendants ⇒
                        // 图标 / 标签 / 描述是**三个独立焦点停靠点**，
                        // 线性滑动会听到「已超期」→「保养」→「已超期 2455 公里」。
                        // 状态由文字承担（WCAG 1.3.1 已满足），图标退化为纯装饰。
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    r.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // ⚠️ **v0.47.0（第 28 轮 a11y P1-2）**：**不要固定宽** ——
                    // 「保养」两个汉字在 fs=2.0 时是 52dp > 44dp
                    // ⇒ Compose 在汉字间断行，折成「保」/「养」两行，整行高度突变。
                    // 临界 fontScale = 44 / (2 × 13) = **1.69**。
                    // 同文件 `TpmsValue` 早就为同一个理由改成了 `widthIn(min = 34.dp)`，
                    // 这里照抄那个写法。
                    modifier = Modifier.widthIn(min = 44.dp),
                )
                Text(
                    r.describe(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                )
            }
        }
        // ⚠️ v0.16.0（车队运营评审 P0-3 + IA 评审 P1-3）：
        // **整行可点**，并说清是哪台车的基准。
        //
        // 原来这是一行纯文字 —— 用户看不出它可点，
        // 而且多车之后「基准」是**按车隔离**的，不说是哪台车就会张冠李戴。
        Row(
            Modifier
                .fillMaxWidth()
                .clickable {
                    Haptics.selection(ctx)
                    onOpenSettings()
                }
                .padding(vertical = 8.dp),
            // ⚠️ **v0.30.0（第 19 轮 HIG 评审 P1-5）：触控目标补到 24dp 以上。**
            // 原来 `padding(vertical = 4.dp)` → 命中区 = 行高 15dp + 8dp = **23dp**，
            // 差 1dp 不达 WCAG 2.5.8 的 24dp。现在 15 + 16 = **31dp**。
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // ⚠️ **v0.121.0（本轮手术点）**：文案由 maintenanceBaselineLabel 唯一给出 ——
                // 去掉「· 点这里修改」这个**教学语**（可点性由右边的箭头图标承担）
                // 与「（车名）」的括号 ⇒ 这一行只剩**事实**。
                maintenanceBaselineLabel(carName),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // ⚠️ **不许把右边的箭头挤出屏幕**：车名再长也不许溢出
                // （宽度预算见 ui/Maintenance.kt 的 BASELINE_ROW_TEXT_DP）。
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(3.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun TpmsValue(label: String, value: Double?, alert: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // ⚠️ v0.9.0：用 widthIn 而不是固定 width ——
            // 固定 34dp 在 fontScale 2.0 下会把「左前」挤成竖排（「左」/「前」两行）。
            // 四个标签都是两个字，宽度自然一致，数值列照样对齐。
            modifier = Modifier.widthIn(min = 34.dp),
        )
        // ⚠️ v0.9.0：宽度改成自适应后，标签与数值之间必须自己留间距
        // （原来靠 34dp 的固定宽度顺带提供了间隔）。
        Spacer(Modifier.width(10.dp))
        Text(
            value?.let { "${it.fmt(2)} bar" } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (alert) {
            Spacer(Modifier.width(4.dp))
            // ⚠️ v0.7.1：emoji ⚠ → 矢量图标。emoji 在 Android 是彩色字形，
            // 与矢量图标混排时基线和字重都对不齐，TalkBack 朗读也不稳定。
            Icon(
                Icons.Filled.WarningAmber,
                contentDescription = "异常",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun TpmsCell(label: String, value: Double?, alert: Boolean, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (alert) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (alert) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                value?.let { "${it.fmt(2)} bar" } ?: "—",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (alert) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/**
 * ⚠️ **v0.78.0：上次充电的结局卡**。
 *
 * ## 为什么只在「有话可说」的时候出现
 *
 * 充到上限（`complete`）是**常态**，说一声等于没说话 ——
 * 按「装饰宁删勿留」，那种情况下整张卡**不出现**。
 *
 * 只有下面三种「用户需要知道」的情况才出现：
 * - `ended_early` 没到上限就结束了（多半是拔枪走了）
 * - `interrupted` 停了但枪还插着（桩可能有问题）
 * - `no_power` 桩没供电
 *
 * ## 为什么过了 24 小时就不出现
 *
 * 「上次充电没充满」三天后是**噪音**（信息过时，还让人困惑）。
 * 它的价值窗口就是「刚充完、还没开远」。
 */
@Composable
private fun ChargeOutcomeCard(oc: com.teslamate.app.ChargeOutcome) {
    val accents = LocalAccentColors.current
    Surface(
        // 同 ChargeNowCard：状态卡与其余卡片同材质，只用顶部色条区分状态。
        // ⚠️ 这里用**琥珀**而不是充电绿 —— 充电绿只表示「正在充电」，
        //    这张卡说的恰恰是「**没在充**」。
        color = accents.cardSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().cardDepth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(accents.warnAmber)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = accents.warnAmberText,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "上次充电",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    oc.ended_at.relTime(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // ⚠️ 结论词是**服务端给的**（`verdict`）—— App 不自己写，
            //    否则三处各写一份、迟早漂移（通知 / Hermes / App 口径必须一致）。
            Text(
                oc.verdict.ifBlank { oc.detail },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = accents.warnAmberText,
            )
            // ⚠️ **v0.118.0**：这一行原来**三格并排**（充到 / 当时上限 / 充入），
            // 每格文字区只有 **64dp**（卡片内容区 296dp ÷ 3 − 内边距 28dp）
            // ⇒ 而「充入」的值是**度数**（「12.9 度」= 63.07dp /「100.0 度」= **73.61dp**）
            // ⇒ 一次充得多（≥ 100 度）就**放不下** ⇒ 整行塌成三行单列
            // ⇒ 那张卡比预期高 3 倍（与「充电中」那一行同源 ⇒ ADR #45 的模式）。
            //
            // ⇒ 现在：「充到」与「当时上限」（有则）并排一行（每格 **115dp**）
            //   ＋「充入」独占一行整宽（**268dp**）
            // ⇒ 100% 与 200% 都放得下（充入在 200% 下要 147.22dp ≤ 268dp）
            // ⇒ **卡片高度不再随数据跳**（充入 12.9 度还是 100.0 度都是两行）。
            val outcomeTiles = mutableListOf(
                "充到" to (oc.end_soc?.let { "$it%" } ?: "—"),
            )
            // ⚠️ **v0.79.0**：这里读的是**那场会话当时**的上限
            //    （`session_limit_soc`），**不是**现在的上限（`limit_soc`）。
            //
            // 拿现在的上限跟那次的结束电量并排显示，等于在暗示
            // 「当时差了多少」—— 而我们并不知道「当时的上限是多少」
            // （上限会变，方向还不确定）。读不到就整格不出现，
            // 按「宁删勿留」：两个有数的格子比三个里有一个是空的更好看。
            oc.session_limit_soc?.let { lim ->
                outcomeTiles += "当时上限" to "$lim%"
            }
            // 「充到」与「当时上限」并排：两个都是百分比（值 ≤ 41dp、标签 ≤ 52dp）
            // ⇒ 115dp 绰绰有余 ⇒ 这一行**永远**并排（200% 下标签要 104dp，仍放得下）。
            InfoTileRow(outcomeTiles, gapDp = 10.dp)
            Spacer(Modifier.height(8.dp))
            // ⚠️ **v0.118.0**：「充入」**独占一行整宽** —— 它是这一行最宽的一格（度数）
            // ⇒ 整宽（268dp）才装得下「100.0 度」⇒ 数字永远不会被折行/裁切。
            InfoTileRow(listOf("充入" to infoTileKwhValue(oc.energy_added_kwh)))
        }
    }
}

/**
 * ⚠️ **v0.78.0 / v0.79.0**：这次充电结局**值不值得占一格屏幕**。
 *
 * - `complete`（充到上限了）与 `tapered`（充到上限附近）是**常态** ⇒ 不出现
 * - `ended_early` / `interrupted` / `no_power` ⇒ 要说（有问题或需要留意）⇒ 出现
 * - `unknown`（结束方式判断不了）⇒ **只要有事实就出现** ——
 *   用户至少该知道「上次充到多少、充了多少度」，
 *   只是「怎么结束的」还说不准。v0.79.0：v0.78.0 这里直接不显示，
 *   等于把一堆已知事实一起藏起来了。
 * - 且只在这场充电结束后的 24 小时内出现（更早 = 过期信息 = 噪音）
 */
private fun com.teslamate.app.ChargeOutcome.worthShowing(): Boolean {
    val interesting = when (outcome) {
        "ended_early", "interrupted", "no_power" -> true
        "unknown" -> end_soc != null || energy_added_kwh != null || duration_min != null
        else -> false
    }
    if (!interesting) return false
    val ended = ended_at ?: return false
    val hours = java.time.Duration.between(ended, java.time.Instant.now()).toHours()
    return hours < 24
}

/**
 * ⚠️ **v0.79.0：故障码（Fleet Telemetry）**。
 *
 * ## 故障码只有一条路能拿到
 *
 * `vehicle_data` 端点里**没有**故障码（`BMS_*` / `DI_*` / `VCFRONT_*` / `CP_*`）——
 * 只有 Fleet Telemetry（车主动把数据推给我们）才有。
 *
 * ## 三种状态，只有两种会出现在屏幕上
 *
 * - **有正在持续的故障码** ⇒ 显示故障码卡（红色）
 * - **遥测正常、确实没有故障** ⇒ **不显示**（装饰宁删勿留）
 * - **遥测从来没接上 / 读不到** ⇒ 显示一张安静的提示卡（琥珀，不是红）
 *
 * ⚠️ 第三种**绝不能**渲染成第二种 —— 那是**假安全**：
 * 用户以为车没事，真出事时根本不会去看。
 *
 * ## 措辞
 *
 * 码的含义由**服务端**给（`summary`），App 一个字都不多编 ——
 * 没收录的码服务端会明说「含义未收录」，并让车机 Service Mode 去查。
 */
@Composable
private fun TelemetrySection(tel: TelemetryStatus?) {
    if (tel == null) return
    val active = tel.alerts.filter { it.active }
    when {
        // 有正在持续的故障码 ⇒ 说清楚有几个、是哪几个
        active.isNotEmpty() -> TelemetryAlertCard(tel, active)
        // ⚠️ 遥测没接上 / 读不到 ⇒ **明说没数据**，不能装作「一切正常」
        !tel.ever_connected || tel.error.isNotBlank() -> TelemetryNoDataNotice(tel)
        else -> Unit
    }
}

/**
 * ⚠️ **v0.79.0：故障码卡**（有正在持续的故障码时才出现）。
 *
 * ⚠️ 只列**仍在持续**的 —— 已经结束的故障码是历史，
 *    常驻在车况页只是噪音（要看历史走 `teslamate_alerts` 工具）。
 */
@Composable
private fun TelemetryAlertCard(tel: TelemetryStatus, active: List<TelemetryAlert>) {
    val accents = LocalAccentColors.current
    Surface(
        // 与 ChargeNowCard / ChargeOutcomeCard 同材质：
        // 状态卡只靠**顶部一条色条**区分，层级才正确。
        color = accents.cardSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().cardDepth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(accents.danger)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = accents.danger,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "故障码",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "${active.size} 条在持续",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            active.forEach { a -> TelemetryAlertRow(a) }
            // ⚠️ 服务端只在**有没收录的码**时才给指引；
            //    这里原样转述，不自己补解释。
            if (tel.hint.isNotBlank()) {
                Text(
                    tel.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 一条故障码：码本身 + 服务端给的一句话摘要。 */
@Composable
private fun TelemetryAlertRow(a: TelemetryAlert) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            a.code,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            // ⚠️ `summary` 是服务端算好的三档摘要
            //    （「子系统 · 含义」/「子系统 · 含义未收录」/「未收录的子系统 · 含义未收录」）。
            //    App 不用 meaning/subsystem 自己拼 —— 拼出来必然跟服务端漂移。
            a.summary.ifBlank { a.code },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * ⚠️ **v0.79.0：故障码「暂无数据」提示**。
 *
 * ⚠️ 用**琥珀**而不是红 —— 这不是车的故障，是我们**没拿到数据**。
 *    红是给「车出问题」用的；这里用红会让用户以为车坏了。
 */
@Composable
private fun TelemetryNoDataNotice(tel: TelemetryStatus) {
    val accents = LocalAccentColors.current
    Surface(
        color = accents.cardSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().cardDepth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(accents.warnAmber)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.HelpOutline,
                    contentDescription = null,
                    tint = accents.warnAmber,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "故障码",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            // ⚠️ **v0.79.0**：两种「拿不到」是**两回事**，分开说 ——
            //    用户需要知道是「还没接上」还是「服务端读挂了」。
            //    两句各自都在啰嗦棘轮的阈值下（视觉宽度 < 44），
            //    所以是两条独立说明，不是「一段长文字」。
            if (tel.error.isNotBlank()) {
                Text(
                    "服务端读取遥测数据出错，故障码暂时拿不到。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "遥测还没接上，暂时拿不到故障码。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChargeNowCard(c: ActiveCharge) {
    val accents = LocalAccentColors.current
    Surface(
        // ⚠️ v0.7.1：原来整块用 `tertiaryContainer`（深色下是一整片浅蓝灰 #243039）。
        // 那样一来，里面的 InfoTile（cardInset = #0B0B0D 深色块）就成了
        // **深色块嵌在浅色块里** —— 层级倒置，正好是 v0.7.0 想消灭的那个问题。
        // 现在状态卡与其余卡片同材质（cardSurface），只用**顶部一条状态色**区分，
        // 内嵌块的层级关系就正确了。
        color = accents.cardSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().cardDepth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(accents.chargeGreen)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // ⚠️ v0.7.1：emoji ⚡ → 矢量图标（Android 把 emoji 渲染成彩色字形，
                // 与矢量图标混排时基线/字重都对不齐，TalkBack 朗读也不稳定）。
                Icon(
                    Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = accents.chargeGreen,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    // ⚠️ **v0.47.0（第 28 轮 a11y P1-3）**：「 · 」换成「，」——
                    // TTS 会把「点」逐字念出来。
                    "充电中${if (c.fast_charger_present == true) "，直流快充" else ""}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "自 ${c.started_at.fmtShort()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // ⚠️ **v0.117.0**：这一行原来**三格并排**（每格文字区只有 **64dp**），
            // 而 `charger_power_kw` 是 **Double** ⇒ 值**恒带小数**（「120.0 kW」）
            // ⇒ 按估算器要 **4.65em × 17sp = 79.05dp** > 64dp
            // ⇒ `infoTileColumns` **永远**判「放不下」⇒ **整行永远塌成三行单列**
            // ⇒ 那张卡比预期高 3 倍（v0.115.0 改列数判定时引进的副作用）。
            //
            // ⇒ 现在：「功率 | 已充入」两格并排（每格 **115dp**）+「当前电量」独占一行整宽
            //   （同 ADR #45 的模式）⇒ 100% 与 200% 都放得下，
            //   而且**版面不再随数据跳**（功率是 120 还是 79.5 都是两格）。
            InfoTileRow(
                listOf(
                    "功率" to infoTileKwValue(c.charger_power_kw),
                    "已充入" to infoTileKwhValue(c.energy_added_kwh),
                ),
                gapDp = 10.dp,
            )
            Spacer(Modifier.height(8.dp))
            InfoTileRow(listOf("当前电量" to "${c.battery_level ?: "—"}%"))
        }
    }
}

@Composable
private fun DriveNowCard(d: com.teslamate.app.ActiveDrive, st: CarStatus) {
    val accents = LocalAccentColors.current
    Surface(
        // 同 ChargeNowCard：状态卡与其余卡片同材质，只用顶部色条区分状态。
        color = accents.cardSurface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().cardDepth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .background(accents.energyBlue)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.DirectionsCar,
                    contentDescription = null,
                    tint = accents.energyBlue,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("行驶中", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    "自 ${d.started_at.fmtShort()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // ⚠️ **v0.116.0：这一行改成「车速 | 功率」两格并排 + 「电量」另起一行整宽。**
            //
            // **上一轮记错了数**：当时写「42 km/h 真实宽度 ≈ 63.6dp，每格 64dp ⇒ 只剩 0.4dp」
            // —— 那是**手算**（把拉丁字母按 0.55em 算）；App 自己的估算器
            // （`estimateTextWidthDpWithNbsp`）给的是 **4.07em × 17sp = 69.19dp**
            // ⇒ **100% 就已经放不下 64dp** ⇒ 值会被折成「42」/「km/h」（**数与单位分离**，ADR #42）
            // ⇒ 那不是「运气」，是**活的 bug**。
            //
            // 算术：三格并排时每格文字区 = (296 − 2×10) ÷ 3 − 28 = **64dp**；
            // 「42 km/h」= 0.62+0.62+0.30+0.66+0.66+0.55+0.66 = 4.07em × 17sp = **69.19dp**。
            //
            // ⇒ 现在「车速 / 功率」两格并排：每格文字区 = (296 − 10) ÷ 2 − 28 = **115dp**
            // ⇒ 余量 **45.8dp**（结构性放得下）；「电量」另起一行**整宽**（268dp）
            // ⇒ 电量**不是重复信息**：页面下方的续航区虽然也写着电量，
            //   但滚到这张卡时它已经出屏幕了。
            // ⇒ 大字号下两格**自动单列**（`InfoTileRow`）⇒ 200% 也放得下。
            // ⇒ 布局先改、**再上 NBSP**（顺序不能反：布局没改就上 NBSP ⇒ 放不下时整段被裁）。
            InfoTileRow(
                listOf(
                    "车速" to infoTileSpeedValue(st.speed),
                    "功率" to infoTileKwValue(st.power),
                ),
                gapDp = 10.dp,
            )
            Spacer(Modifier.height(8.dp))
            InfoTileRow(listOf("电量" to "${st.battery_level ?: "—"}%"))
        }
    }
}
