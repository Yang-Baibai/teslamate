@file:OptIn(ExperimentalMaterial3Api::class)

package com.teslamate.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.teslamate.app.ActiveCharge
import com.teslamate.app.AppSettings
import com.teslamate.app.CarStatus
import com.teslamate.app.ConsumptionModel
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
fun StatusScreen(settings: AppSettings, modifier: Modifier, store: StatusStore = remember { StatusStore() }) {
    val api = remember(settings) { settings.api() }
    val scope = rememberCoroutineScope()
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
    val consumption = remember(drives) { ConsumptionModel.from(drives) }

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
            .onFailure { store.fail(it.message ?: "网络错误") }
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
                IconButton(onClick = { refresh() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
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
                error?.let { ErrorBanner(it) }

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
                                "更新于 ${st.updated_at.relTime()}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        // 数据新鲜度：让「车睡着了（正常）」和「连不上服务器」不再长得一样。
                        // 连不上服务器的情况由上面的 ErrorBanner 负责（那是请求失败）；
                        // 这里说明的是「请求成功了，但车本身很久没上报」——多半是车停着不动。
                        // ⚠️ 措辞用「数据未更新」而不是「离线」——「离线」在状态胶囊里指的是
                        //    车辆离线，两个意思混用会让人张冠李戴。
                        val staleHours = st.data_age_hours
                        if (staleHours >= 24) {
                            NoticeBanner(
                                "数据已 $staleHours 小时未更新。车停着不动属正常；" +
                                    "若你人在外地，也可能是网络不通，请留意上方是否有红色提示。"
                            )
                        } else if (staleHours >= 6) {
                            NoticeBanner("数据已 $staleHours 小时未更新，可能不是最新的。")
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) {
                            SocRing(st.battery_level, charging = st.active_charge != null)
                            Spacer(Modifier.width(28.dp))
                            Column {
                                // ── 续航：显示**真实可达**，不再显示骗人的「预估」 ──
                                //
                                // 原来取 est → ideal → rated 三级降级，实测这条链塌缩成一个值：
                                // ideal 与 rated 在 55,669 行里逐行相同，est 全库只有 0.085% 有值。
                                // 屏幕上写「预估续航」，实际是 EPA 标定值 —— 夏季高速会乐观 25%~40%。
                                //
                                // 现在用你自己跑出来的 Wh/km 折算：
                                //   真实可达 = 标定续航 × 146 ÷ 实测 Wh/km × 温度修正 × 爬升修正 × 0.9 余量
                                val ratedRange = st.rated_range_km ?: st.ideal_range_km
                                // 参考车速：行驶中用实时车速；停车时用最近一次行程的均速
                                //（长途规划关心的是「你通常怎么开」，不是「车现在静止时的 0」）。
                                val refSpeed = st.speed?.takeIf { it > 5 }?.toDouble()
                                    ?: drives.firstOrNull()?.avg_speed_kph?.toDouble()
                                    ?: 60.0
                                val whBucket = consumption?.whPerKm(refSpeed)
                                // ⚠️ v0.8.6 修一个真 bug：模型里有爬升修正（≥200m ×1.05），
                                // 但调用处**从来没传 ascentM** —— 参数默认 null，修正从未生效。
                                // 爬升从最近一次行程取（车况页已经拉了 drives(200) 到 StatusStore）。
                                val recentAscent = drives.firstOrNull()?.ascent_m
                                val trueRange = consumption?.trueRangeKm(
                                    ratedRangeKm = ratedRange,
                                    speedKph = refSpeed,
                                    outsideTempC = st.outside_temp,
                                    ascentM = recentAscent,
                                )
                                BigStat(
                                    (trueRange ?: ratedRange)?.fmt(0) ?: "—",
                                    if (trueRange != null) "真实可达 (km)" else "标定续航 (km)",
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    // ⚠️ v0.8.5（效率车主复核）：原来副行是三段话
                                    // 「标定 446 km · 按你的实测 187 Wh/km 折算 · 已留 10% 余量」，
                                    // 在 165dp 宽的列里折三四行，而且逼着用户「在 308 和 446 里挑一个」。
                                    // 现在：**主数字只给答案**（308 = 够不够开），
                                    // 副行只说比例（标定的 69%），怎么折算的收进下一行小字。
                                    if (trueRange != null && ratedRange != null && ratedRange > 0) {
                                        "标定 ${ratedRange.fmt(0)} km 的 " +
                                            "${(trueRange / ratedRange * 100).roundToInt()}%"
                                    } else {
                                        "EPA 标定值（非动态预估）"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (trueRange != null) {
                                    Text(
                                        (whBucket?.let { "按你最近 ${consumption?.sampleCount ?: 0} 次行程实测 ${it.whPerKm.fmt(0)} Wh/km 折算" } ?: "")
                                            .ifBlank { "按实测能耗折算" },
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
                    }
                }
            }
        }
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
            modifier = Modifier.size(15.dp),
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
    if (lat == null || lng == null) return
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
    if (fl == null && fr == null && rl == null && rr == null) return // 一个都没有就不占版面

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
            "正常范围 ${st.tpms_min_bar}–${st.tpms_max_bar} bar（冷胎）",
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
            modifier = Modifier.width(34.dp),
        )
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
                InfoTile("已充入", c.energy_added_kwh?.fmt(1, " kWh") ?: "—", Modifier.weight(1f))
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
