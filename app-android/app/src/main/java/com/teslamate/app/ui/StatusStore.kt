package com.teslamate.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.teslamate.app.CarStatus
import com.teslamate.app.Drive

/**
 * 车况数据的 **App 级共享存储**。
 *
 * ## 为什么需要它
 *
 * 原来 `StatusScreen` 里的 `status` 是 `remember { mutableStateOf(...) }`，
 * 而底部 Tab 用 `AnimatedContent(targetState = tab)` 切换 —— **切走的页面会被销毁**。
 * 于是每次从「统计」切回「车况」，都是：
 *
 *   1. `remember` 丢失 → `status = null`
 *   2. 页面渲染三块骨架屏
 *   3. 重新发一次网络请求
 *   4. 数据回来后数字才出现
 *
 * 效率型车主的原话：*「我去看一眼统计再回来，等于重新开一次 App。」*
 * 服务端实测 status 冷请求 38ms、热请求 4ms —— **慢的不是服务器，是这个首屏策略**。
 *
 * ## 现在的做法
 *
 * 数据提到 App 级持有（`remember` 在 AppRoot 里，生命周期跟随整个 App）：
 *
 * - **切 Tab 回来**：旧数据立刻显示（stale-while-revalidate），**不闪骨架屏**，
 *   同时后台静默刷新，刷新完数字自己更新。
 * - **冷启动**：确实没有缓存，该闪还是会闪 —— 但这时 App 会把上一次的结果
 *   落到本地缓存（见下），下次就能直接出数字。
 *
 * 这里只负责「跨 Tab 复用内存」，不负责落盘；落盘缓存在 App 的 DataStore 里做。
 */
class StatusStore {
    /** 最近一次成功取到的车况。切 Tab 时保留，用于 stale-while-revalidate。 */
    var status by mutableStateOf<CarStatus?>(null)
        private set

    /** 最近一次请求的错误。成功刷新后清空。 */
    var error by mutableStateOf<String?>(null)
        private set

    /** 行程样本，用于建「车速 → 实测 Wh/km」能耗表。 */
    var drives by mutableStateOf<List<Drive>>(emptyList())
        private set

    /** 已有可用数据时为 true —— UI 据此决定是显示骨架屏还是直接显示旧数据。 */
    val hasData: Boolean get() = status != null

    internal fun update(s: CarStatus) {
        status = s
        error = null
    }

    internal fun fail(message: String?) {
        error = message
    }

    internal fun updateDrives(list: List<Drive>) {
        drives = list
    }
}
