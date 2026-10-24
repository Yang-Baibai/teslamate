@file:OptIn(ExperimentalMaterial3Api::class)

package com.teslamate.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.teslamate.app.ActiveCharge
import com.teslamate.app.AppSettings
import com.teslamate.app.CarStatus
import com.teslamate.app.ConsumptionModel
import com.teslamate.app.Haptics
import com.teslamate.app.fmt
import com.teslamate.app.fmtShort
import com.teslamate.app.fmtThousands
import com.teslamate.app.relTime
import java.time.Instant
import kotlin.math.roundToInt
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

    LaunchedEffect(api, settings.carId) {
        if (api != null) {
            runCatching {
                withContext(Dispatchers.IO) { api.drives(settings.carId, 200) }
            }.onSuccess { store.updateDrives(it) }
        }
    }
    val drives = store.drives
    // ⚠️ v0.9.0：能耗模型改从 store 取（App 级 + **已落盘**），
    // 不用页面里的 `remember(drives) { ConsumptionModel.from(drives) }` ——
    // 那样服务器挂掉时模型为 null，续航会退化成「官方标称」（乐观 50%）。
    val consumption = store.consumption
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
    }

    fun refresh() {
        scope.launch {
            refreshing = true
            load()
            refreshing = false
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
                // ⚠️ v0.9.2（iOS 交互评审 P1-7）：刷新按钮原来点了**零反馈** ——
                // 图标不转、按钮不禁用（刷新中再点会并发再发一次请求）、也没有触感。
                // 用户点完刷新，屏幕上什么变化都看不到。
                val spin = rememberInfiniteTransition(label = "refreshSpin")
                val angle by spin.animateFloat(
                    0f, 360f,
                    infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)),
                    label = "refreshAngle",
                )
                IconButton(
                    onClick = {
                        Haptics.tick(ctx)
                        refresh()
                    },
                    enabled = !refreshing,
                ) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "刷新",
                        modifier = Modifier.graphicsLayer {
                            rotationZ = if (refreshing) angle else 0f
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
                    if (err.kind == com.teslamate.app.ApiErrorKind.NO_DATA) {
                        NoticeBanner(
                            err.message ?: "这台车还没有任何数据。可能还没开过，或采集器还没上报。"
                        )
                    } else {
                        ErrorBanner(
                            err.message ?: "出错了",
                            actionLabel = if (err.kind == com.teslamate.app.ApiErrorKind.AUTH) {
                                "前往设置"
                            } else {
                                "重试"
                            },
                            onAction = if (err.kind == com.teslamate.app.ApiErrorKind.AUTH) {
                                onOpenSettings
                            } else {
                                { refresh() }
                            },
                        )
                    }
                }

                val st = status
                when {
                    st == null && error == null -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SkeletonBox(Modifier.fillMaxWidth().height(180.dp))
                        SkeletonBox(Modifier.fillMaxWidth().height(96.dp))
                        SkeletonBox(Modifier.fillMaxWidth().height(96.dp))
                    }

                    st != null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // ⚠️ v0.8.4：状态行左边原来恒是「在线/休眠/离线」胶囊。
                            //
                            // 踩过的坑：**车停着不动时 TeslaMate 就报 state=offline**
                            // （它把「车休眠/没活动」记成 offline，日志里天天
                            //   "Start / :offline"）—— 也就是说「离线」是**常态**，
                            // 不是需要特别提醒的异常。所以不能让它挤掉锁车状态。
                            //
                            // 现在：**有锁车数据就显示锁车**（这是这块屏上唯一一件
                            // 「车停着不动还会变」的信息），并在后面附上车离线标记；
                            // 只有锁车数据未知（没配 Fleet API / 取不到）时，
                            // 才退回显示原来的状态胶囊。
                            // ⚠️ v0.8.5：三态**都显式呈现**（含「锁车状态未知」），
                            // 不再在拿不到数据时悄悄退回「在线/休眠/离线」胶囊 ——
                            // 那样等于把「没取到」伪装成「没这功能」。
                            LockStateChip(st.locked, st.locked_at)
                            Spacer(Modifier.weight(1f))
                            Text(
                                // ⚠️ v0.7.1：绝对时间看不出隔了多久，改相对时间
                                // （「更新于 3 分钟前」），超过一周才回落到绝对时间。
                                //
                                // ⚠️ v0.9.0：显示的这份数据如果是**落盘缓存**里的，
                                // 必须说清楚「这是上次读到的」——
                                // 一个会撒谎的缓存比没有缓存更糟。
                                // ⚠️ v0.9.0（新手车主复核）：两个时间戳挨着会以为是同一个。
                                // 车主原话：「'更新于 3 分钟前'旁边又挂了锁车的时间戳，
                                // 两个时间戳挨着，我以为是同一个」。
                                // 现在右边这句明确是**车况**（TeslaMate 上报）的，
                                // 左边锁车那颗自带的是 **Tesla 云端**的时间 —— 各自有主语。
                                if (store.cachedAt != null) {
                                    "上次读到 · ${store.cachedAt!!.relTime()}"
                                } else {
                                    "车况 · ${st.updated_at.relTime()}"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                        val reminders = remember(st.odometer, settings) {
                            maintenanceReminders(st.odometer, settings)
                        }
                        reminders.firstOrNull { it.needsAction }?.let { r ->
                            // ⚠️ 超期是**该行动**的事（红色），快到了只是提醒（中性）。
                            // 两者都挂横幅，但力度不同 —— 用同一种样式会让「超期」显得像例行公事。
                            //
                            // ⚠️ v0.14.0（新手车主 P0-2）：文案**消歧** ——
                            // 「保养已超期 2455 公里」在中文里会被读成
                            // 「上次保养后开了 2455 公里」，误读方向是「还早」，
                            // 而保养超期只有一个方向：太晚。
                            // 现在把「该在多少做 / 现在多少」一起写出来。
                            if (r.overdue) {
                                ErrorBanner(r.bannerText(st.odometer))
                            } else {
                                NoticeBanner(r.bannerText(st.odometer))
                            }
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
                        if (st.data_stale) {
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
                        val fleetNotice = remember(st.locked, st.fleet_error, st.fleet_last_ok) {
                            buildString {
                                if (st.locked == null) {
                                    append("没读到车机的锁车状态 —— 这不代表车没锁。")
                                }
                                if (st.fleet_error.isNotBlank()) {
                                    if (isNotEmpty()) append(" ")
                                    append(st.fleet_error)
                                }
                                st.fleet_last_ok?.let { ok ->
                                    if (st.locked == null) {
                                        append("（最后一次成功获取：${ok.relTime()}）")
                                    } else {
                                        // 有旧值：说清上面那个值是什么时候读到的
                                        append(" —— 上面显示的是 ${ok.relTime()}读到的旧值。")
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
                        // 阈值 1.5：1.5 倍时左右两栏还挤得下；再大就必须换行。
                        //
                        // ⚠️ v0.14.0（新手车主 P0-3）：**大字号时主角数字排最前**。
                        //
                        // 原来大字号是「环在上、数字在下」，环按 168dp × 1.75 = 294dp
                        // 计算，占了屏幕近一半 —— 爸妈打开 App 想看「还能跑多少」，
                        // 得先滚一屏才看得到那个数，而 69% 恰恰不是他们要的数。
                        //
                        // 现在：**大数字在上、环缩小**（120dp × 1.75 = 210dp ≤ 屏高 25%），
                        // 「204」「现在能开」「69%」三个信息块首屏内全部可见。
                        val fontScale = LocalDensity.current.fontScale
                        if (fontScale >= 1.5f) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                RangeAndOdometer(st, consumption, recentAscent, fallbackSpeed, Modifier.fillMaxWidth())
                                Spacer(Modifier.height(14.dp))
                                SocRing(
                                    st.battery_level,
                                    charging = st.active_charge != null,
                                    baseSizeDp = 120,
                                )
                            }
                        } else {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            ) {
                                SocRing(st.battery_level, charging = st.active_charge != null)
                                Spacer(Modifier.width(28.dp))
                                RangeAndOdometer(st, consumption, recentAscent, fallbackSpeed, Modifier.weight(1f))
                            }
                        }

                        // ⚠️ v0.14.0：中性横幅**下沉到主角数字之后**。
                        // 红色告警在第一（该动手的事最靠前），中性信息让位给主角。
                        fleetNotice?.let { NoticeBanner(it) }

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

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            InfoTile(
                                "空调",
                                // 车没上报时是 null，显示"关闭"会骗人 —— 必须区分「未知」
                                when (st.is_climate_on) {
                                    true -> "开启"
                                    false -> "关闭"
                                    null -> "未知"
                                },
                                Modifier.weight(1f),
                            )
                            InfoTile("车内温度", st.inside_temp?.fmt(1, "°C") ?: "—", Modifier.weight(1f))
                        }

                        // 能量条 —— 特斯拉最易识别的视觉母题，充电中转绿
                        EnergyBar(
                            soc = st.battery_level,
                            charging = st.active_charge != null,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )

                        st.active_charge?.let { ChargeNowCard(it) }
                        st.active_drive?.let { DriveNowCard(it, st) }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            InfoTile("车外温度", st.outside_temp?.fmt(1, "°C") ?: "—", Modifier.weight(1f))
                            InfoTile("功率", st.power?.let { "$it kW" } ?: "—", Modifier.weight(1f))
                        }
                        // ⚠️ v0.8.5（效率车主复核）删掉「海拔 | 可用电量」两块：
                        //  · 海拔一年用一次，且 data-dictionary 自己写着「深圳平原常为 null」
                        //  · 「可用电量」和电量环是同一个数的两半（battery_level vs
                        //    usable_battery_level），本车实测 100/100 恒等 —— 纯重复
                        // 省下 74dp，胎压卡就能完整露出首屏（现在要滑 170dp 才看得到四个胎压值）。
                        TpmsCard(st)

                        // 保养 / 轮胎卡片（配过基准才出现）
                        if (reminders.isNotEmpty()) {
                            MaintenanceCard(reminders)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 续航 + 里程表 + 「怎么算的」折叠区。
 *
 * ⚠️ v0.9.0：从 StatusScreen 里抽出来 —— 因为首屏改成**弹性布局**后，
 * 这一块要能在「左右两栏」与「上下两栏」两种排法里复用。
 */
@Composable
private fun RangeAndOdometer(
    st: CarStatus,
    consumption: ConsumptionModel?,
    recentAscent: Int?,
    /** 最近一次行程的均速（参考车速的最后一档兜底） */
    fallbackSpeedKph: Double? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        // ── 续航：显示**真实可达**，不再显示骗人的「预估」 ──
        //
        // 原来取 est → ideal → rated 三级降级，实测这条链塌缩成一个值：
        // ideal 与 rated 在 55,669 行里逐行相同，est 全库只有 0.085% 有值。
        // 屏幕上写「预估续航」，实际是 EPA 标定值 —— 夏季高速会乐观 25%~40%。
        //
        // 现在用你自己跑出来的 Wh/km 折算：
        //   真实可达 = 标定续航 × 146 ÷ 实测 Wh/km × 温度修正 × 爬升修正 × 0.9 余量
        val ratedRange = st.rated_range_km ?: st.ideal_range_km
        // 参考车速：行驶中用实时车速；停车时用**巡航速度**
        //（近期高速行程的均速中位数 —— 长途党复核指出
        //  整段均速会让人在高速时按市区桶估，偏乐观约 12 km）。
        val refSpeed = st.speed?.takeIf { it > 5 }?.toDouble()
            ?: consumption?.cruiseKph
            ?: fallbackSpeedKph
            ?: 60.0
        // ⚠️ v0.9.2（口径审计 S2-4）：**参考车速的来源要说清楚**。
        // 原来的兜底链最后落到硬编码 60.0 —— 而 60 km/h 恰好落在**最低档**
        // （171.8 Wh/km，是最乐观的一档），却和「实测巡航速度」用同一句话表述。
        val speedAssumed = consumption?.cruiseKph == null && fallbackSpeedKph == null
        val whSample = consumption?.whSample(refSpeed)
        // ⚠️ v0.8.6 修一个真 bug：模型里有爬升修正（≥200m ×1.05），
        // 但调用处**从来没传 ascentM** —— 参数默认 null，修正从未生效。
        // 爬升从最近一次行程取（车况页已经拉了 drives(200) 到 StatusStore）。
        val trueRange = consumption?.trueRangeKm(
            ratedRangeKm = ratedRange,
            speedKph = refSpeed,
            outsideTempC = st.outside_temp,
            ascentM = recentAscent,
        )
        BigStat(
            (trueRange ?: ratedRange)?.fmt(0) ?: "—",
            // ⚠️ v0.9.0（新手车主复核）：「真实可达 (km)」是术语。
            // 车主原话：「'可达'太书面」「我真正要的是一句话：
            // '现在能开 206 公里，高速算的，够你回家'」。
            // 大数字 + 这行小字，读起来就是「现在能开 206 公里」。
            if (trueRange != null) "现在能开（公里）" else "官方标称（公里）",
            // ⚠️ v0.14.0（苹果 HIG P0-1）：**主角级** —— 44sp。
            // 34sp 和页面标题「车况」同级，主角就不成立（每屏要有唯一视觉锚点）。
            hero = true,
        )
        // ⚠️ v0.14.0（长途自驾党 P0-3）：**保底余量**。
        //
        // 「现在能开 204 公里」里已经留了 10% 余量；
        // 但长途规划问的是「下一个服务区在 170 公里外，我到得了吗」——
        // 那要按 **20% 保底**算。
        //
        // 这**不是另一个估计值**（不跟 204 打架），
        // 而是同一个模型换了个阈值 —— 所以用副行呈现，不抢主角。
        val trueRangeFloor = consumption?.trueRangeKm(
            ratedRangeKm = ratedRange,
            speedKph = refSpeed,
            outsideTempC = st.outside_temp,
            ascentM = recentAscent,
            reserveFactor = 0.80,
        )
        if (trueRangeFloor != null && trueRange != null) {
            Text(
                "留 20% 保底还能开 ${trueRangeFloor.fmt(0)} 公里",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        // ⚠️ v0.9.0（新手车主复核）：**假设链收进可展开区**。
        //
        // 原来主数字下面明晃晃挂着三行参数
        // （「标定 312 km 的 66%」+「按 132 km/h 巡航 · 28°C · 实测 183 Wh/km」），
        // 车主的反应是：
        //   「屏幕上同时出现 206 和 312，我脑子里变成'官方说 312，
        //     App 说 206，我到底该信哪个？' 两个数字打架，
        //     比只给一个数字更让我拿不准。」
        //   「多出来的信息不是让我更有信心，是让我更慌。」
        //
        // 现在：主数字**只给答案**，怎么算的收在一行「怎么算的 ▸」里，
        // 点开才展开 —— 规划长途时它是关键，平时它是干扰。
        var showExplain by remember { mutableStateOf(false) }
        val explainCtx = LocalContext.current
        val explainText = buildString {
            if (ratedRange != null && ratedRange > 0) {
                append("官方标称 ${ratedRange.fmt(0)} 公里")
            }
            if (trueRange != null) {
                append(" · 按平时 ${refSpeed.roundToInt()} km/h 估算")
                // ⚠️ v0.9.2：60 km/h 是硬编码兜底，且它落在**最乐观的一档**。
                // 必须标成「假设」，不能和「实测巡航速度」混为一谈。
                if (speedAssumed) append("（假设值）")
                st.outside_temp?.let { append(" · ${it.roundToInt()}°C") }
                // ⚠️ v0.9.2（口径审计 S2-4）：借用邻档时不能说「实测」。
                whSample?.let { s ->
                    append(
                        if (s.exact) {
                            " · 实测 ${s.bucket.whPerKm.fmt(0)} Wh/km（${s.bucket.label} 档）"
                        } else {
                            " · 参考 ${s.bucket.whPerKm.fmt(0)} Wh/km（借用 ${s.bucket.label} 档）"
                        }
                    )
                }
                append(" · 已留 10% 余量")
            }
        }
        Text(
            text = if (showExplain) "怎么算的 ▾" else "怎么算的 ▸",
            style = MaterialTheme.typography.labelSmall,
            // ⚠️ v0.9.0：不用 primary（品牌红）。
            // 本 App 里红色只表示告警/品牌点缀 —— 一个折叠链接是红的，
            // 看起来像出错了。可点性由 ▸/▾ 本身表达（iOS 就是这么做的）。
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable {
                    Haptics.tick(explainCtx)
                    showExplain = !showExplain
                }
                .semantics {
                    contentDescription = if (showExplain) {
                        "收起续航计算方式"
                    } else {
                        "展开续航计算方式"
                    }
                },
        )
        // ⚠️ v0.9.2（iOS 交互评审 P1-11）：展开要有过渡。
        // 原来 `if (showExplain) { Text(...) }` 是硬切 —— 父容器高度瞬间跳变，
        // 把下方所有卡片「顶」下去再弹回来（这是最容易被感知的廉价感来源）。
        AnimatedVisibility(
            visible = showExplain,
            enter = fadeIn(tween(160)) + expandVertically(tween(220, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(180, easing = FastOutSlowInEasing)),
        ) {
            Text(
                explainText.ifBlank { "暂无足够数据估算" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(12.dp))
        // ⚠️ 原来里程表也是 34sp Bold，与续航**同级** —— 一屏两个主角。
        // 降级为一行小字：信息一点没丢，但视觉上只剩续航是主角。
        Text(
            "里程表 ${st.odometer?.fmtThousands() ?: "—"} km",
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
 */
/**
 * 锁车状态标签。
 *
 * 三态，且**不靠颜色单独表达**（WCAG 1.4.1）：
 * - `true`  → 锁形图标 + 「已上锁」+ 中性灰
 * - `false` → 开锁图标 + 「未上锁」+ 琥珀（图标和文字同时变化，色觉障碍用户也读得出）
 * - `null`  → 问号图标 + 「锁车状态未知」+ 中性灰
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
 * 3. **删掉「· 离线」后缀**。车停着不动时 TeslaMate 天天报 offline（这是常态，
 *    不是异常），挂着它只会让人以为锁车状态有问题。
 */
@Composable
private fun LockStateChip(locked: Boolean?, lockedAt: Instant?) {
    val accents = LocalAccentColors.current
    val icon: androidx.compose.ui.graphics.vector.ImageVector
    val text: String
    val tint: androidx.compose.ui.graphics.Color
    when (locked) {
        true -> {
            icon = Icons.Filled.Lock
            text = "已上锁"
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        }
        false -> {
            icon = Icons.Filled.LockOpen
            text = "未上锁"
            tint = accents.warnAmber
        }
        null -> {
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
        // 锁车状态的新鲜度用**它自己的**采集时刻
        if (locked != null && lockedAt != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                lockedAt.relTime(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

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
            Text(
                "暂无位置数据。车辆位置需要 TeslaMate 采集到定位信息后才会出现。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            // 占两行且没有信息增量 —— 主标题只取**第一段**，
            // 完整地址降为一行灰色小字，信息一点不丢。
            val fullPlace = st.location_name ?: "未命名地点"
            val shortPlace = fullPlace
                .substringBefore('，')
                .substringBefore(',')
                .trim()
                .ifBlank { fullPlace }
            Text(
                shortPlace,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (shortPlace != fullPlace) {
                Text(
                    fullPlace,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AnimatedVisibility(
                visible = showMap,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
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
 * 胎压卡：俯视车辆 + 四轮数值。
 *
 * 俯视图是本 App **唯一能超越官方 Tesla App** 的地方（官方不展示四轮胎压）。
 * 异常轮用危险红实心 + 呼吸动画，接近阈值用琥珀，正常用中性灰。
 * 判定完全由服务端算好（api/alerts.go），App 只读结果——阈值也只有服务端一份。
 */
@Composable
private fun TpmsCard(st: CarStatus) {
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
            Text(
                "暂无胎压数据。实时胎压需要配置 Tesla Fleet API；" +
                    "未配置时显示的是 TeslaMate 的稀疏采样（车开动或充电时才记）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

    SectionCard(
        title = if (st.tpms_alert) "轮胎胎压 · 建议检查" else "轮胎胎压",
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            VehicleTopView(reading = reading, modifier = Modifier.weight(0.9f))
            Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TpmsValue("左前", fl, reading.isAlerting(fl, "左前"))
                TpmsValue("右前", fr, reading.isAlerting(fr, "右前"))
                TpmsValue("左后", rl, reading.isAlerting(rl, "左后"))
                TpmsValue("右后", rr, reading.isAlerting(rr, "右后"))
            }
        }
        // 告警不能只靠颜色（WCAG 1.4.1）——服务端已算好这句文案，直接用
        st.tpms_alert_detail?.takeIf { it.isNotBlank() }?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Text(
            // ⚠️ v0.9.0（新手车主复核）：新手「完全不懂 bar」。
            // 补一句「超出会怎样」，让它变成一个**能据此行动**的信息，
            // 而不是一个需要背下来的单位。
            "正常 ${st.tpms_min_bar}–${st.tpms_max_bar} bar（冷胎）· 超出这个范围需要检查",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun MaintenanceCard(reminders: List<MaintenanceReminder>) {
    val accents = LocalAccentColors.current
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
                        contentDescription = "已超期",
                        tint = color,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    r.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(44.dp),
                )
                Text(
                    r.describe(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                )
            }
        }
        Text(
            "基准与间隔可在「设置」页修改",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                    "充电中${if (c.fast_charger_present == true) " · 直流快充" else ""}",
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoTile("功率", "${c.charger_power_kw ?: "—"} kW", Modifier.weight(1f))
                InfoTile("已充入", c.energy_added_kwh?.fmt(1, " 度") ?: "—", Modifier.weight(1f))
                InfoTile("当前电量", "${c.battery_level ?: "—"}%", Modifier.weight(1f))
            }
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoTile("车速", st.speed?.let { "$it km/h" } ?: "—", Modifier.weight(1f))
                InfoTile("功率", st.power?.let { "$it kW" } ?: "—", Modifier.weight(1f))
                InfoTile("电量", "${st.battery_level ?: "—"}%", Modifier.weight(1f))
            }
        }
    }
}
