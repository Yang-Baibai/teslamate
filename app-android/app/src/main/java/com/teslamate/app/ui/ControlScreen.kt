@file:OptIn(ExperimentalMaterial3Api::class)

package com.teslamate.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.teslamate.app.AppSettings
import com.teslamate.app.Haptics
import com.teslamate.app.UserFacingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ⚠️ **v0.156.0（用户反馈 #153）：车辆控制独立成页。**
 *
 * ## 为什么有这一页
 *
 * 用户原话（2026-11-13）：
 * 「能否将车辆控制的相关部分独立出来，类似特斯拉APP的首页。
 * 现在集成在车况页面中，会显得车况页面特别啰嗦冗长。」
 *
 * ⇒ 车况页是**仪表盘**（主角数字 + 当下状态 + 回顾），
 *   车控是**动作**（要什么就点什么）—— 两件事混在一页 ⇒ 页面被撑长、
 *   用户要找「开空调」得先滚过电量环、胎压、最近 30 天。
 * ⇒ 现在车控有自己的 Tab（排在「车况」之后），
 *   **底栏直达**（不用先滚车况页），车况页也短了一截。
 *
 * ## 这一页的取舍
 *
 * - **车况数据走共享的 [StatusStore]**（App 级）⇒ 与车况页**同一个数**，
 *   不会出现「车况页说已上锁、车控页说未锁」。
 * - **命令下发后立刻回读车况**（v0.76.0 的老教训）：
 *   否则磁贴会跟用户互相打脸最长两分钟。
 * - **15 秒轮询 + 下拉/↻ 刷新**：锁车状态会变（用户用钥匙解锁了），
 *   磁贴不能说陈话。
 * - **清单另算**：车控能力清单**只在用户主动刷新时重读**
 *   （15 秒重读一次纯属浪费 —— 清单不会自己变）。
 * - **401 有出口**：令牌失效时给「前往设置」，不许用户对着红条干瞪眼。
 */
@Composable
fun ControlScreen(
    settings: AppSettings,
    modifier: Modifier,
    /** 与车况页共用同一份车况数据（App 级 store）。 */
    store: StatusStore,
    /** 全局反馈宿主（命令结果落在屏幕底部，不受卡片位置影响）。 */
    snackbarHostState: SnackbarHostState? = null,
    /** ⚠️ v0.16.0：车辆列表（顶栏车切换芯片，仅多车时出现）。 */
    cars: List<com.teslamate.app.Car> = emptyList(),
    /** ⚠️ v0.16.0：切换当前车辆。 */
    onCarChange: (Int) -> Unit = {},
    /** 令牌失效时给一个直达设置的出口。 */
    onOpenSettings: () -> Unit = {},
    /** ⚠️ v0.157.0：顶栏齿轮 ⇒ 打开设置页（根）。 */
    onOpenSettingsRoot: () -> Unit = {},
) {
    val api = remember(settings) { settings.api() }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status = store.status
    val error = store.error
    var refreshing by remember { mutableStateOf(false) }
    // ⚠️ 车控能力清单的刷新信号：只有**用户主动**刷新（↻ / 下拉）才 +1。
    var catalogRefresh by remember { mutableStateOf(0) }

    /**
     * 只取**车况**（不取续航/保养那些）——
     * 这一页只需要「锁没锁」「车醒没醒」两件事。
     */
    suspend fun loadStatus() {
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
                // ⚠️ v0.9.0：**不清空**已有车况 —— 服务器挂掉时用户至少还看得到上次已知。
                store.fail(
                    e as? UserFacingException
                        ?: UserFacingException(e.message ?: "出错了")
                )
            }
    }

    // 15 秒自动刷新，但必须跟随界面生命周期（后台不跑）。
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, settings) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                loadStatus()
                delay(15_000)
            }
        }
    }

    fun refresh() {
        scope.launch {
            refreshing = true
            loadStatus()
            refreshing = false
            // ⚠️ 清单也重读一次 —— 车控代理可能刚起来（原来它在车况页，↻ 顺手能刷）。
            catalogRefresh++
        }
    }

    Column(modifier) {
        TopAppBar(
            title = { ScreenTitle("车控") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            actions = {
                CarChip(
                    cars = cars,
                    currentId = settings.carId,
                    onSelect = onCarChange,
                )
                Spacer(Modifier.width(4.dp))
                // ⚠️ v0.9.2 / v0.14.0：刷新按钮要有**动效反馈**（转圈 + 触感），
                // 否则点完屏幕上什么变化都看不到。
                val spin = remember { Animatable(0f) }
                LaunchedEffect(refreshing) {
                    if (refreshing) {
                        spin.snapTo(spin.value % 360f)
                        while (true) {
                            spin.animateTo(
                                targetValue = spin.value + 360f,
                                animationSpec = tween(
                                    Motion.RefreshSpin,
                                    easing = androidx.compose.animation.core.LinearEasing,
                                ),
                            )
                        }
                    } else if (spin.value != 0f) {
                        spin.animateTo(
                            targetValue = 360f - (spin.value % 360f),
                            animationSpec = tween(Motion.RefreshSettle, easing = Motion.EaseOut),
                        )
                    }
                }
                IconButton(
                    onClick = {
                        Haptics.tick(ctx)
                        // 并发防护：不禁用按钮，就在这里挡。
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
                // ⚠️ v0.157.0：设置在**顶栏**（和刷新并排）⇒ 底栏只剩 4 项，不挤了。
                SettingsIconButton(onClick = onOpenSettingsRoot)
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
                        // ⚠️ 底部必须留出导航栏高度（导航栏是半透明毛玻璃）。
                        bottom = 16.dp + navBarBottom(),
                    ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ⚠️ v0.9.0 / v0.14.0：错误横幅**带出口**（令牌问题 → 前往设置）。
                error?.let { err ->
                    ApiErrorBanner(
                        err,
                        onOpenSettings = onOpenSettings,
                        onRetry = { refresh() },
                    )
                }

                // ⚠️ 锁车状态**未知**时传 null（车休眠或没连实时状态源）——
                // 磁贴上会写「锁车状态未知」，不会假装车没锁。
                VehicleControlPanel(
                    api = api,
                    carId = settings.carId,
                    locked = status?.locked,
                    carAsleep = status?.fleet_state == "asleep",
                    refreshSignal = catalogRefresh,
                    // ⚠️ v0.76.0：命令之后立刻把车况读回来 ——
                    // 否则磁贴会跟用户互相打脸最长两分钟。
                    onCommandFinished = { scope.launch { loadStatus() } },
                    snackbarHostState = snackbarHostState,
                )
            }
        }
    }
}
