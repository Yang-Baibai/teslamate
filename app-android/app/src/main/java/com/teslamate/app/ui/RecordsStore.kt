package com.teslamate.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.teslamate.app.Api
import com.teslamate.app.ApiErrorKind
import com.teslamate.app.Charge
import com.teslamate.app.ChargeStats
import com.teslamate.app.Drive
import com.teslamate.app.UserFacingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 记录页的数据仓库（**App 级**）。
 *
 * ## 为什么要它
 *
 * ⚠️ **v0.24.0（性能与稳定性评审 P0-2 / P1-1）**。
 *
 * 原来 `drives` / `charges` 是 `RecordsScreen` 里的**页面级** `remember`。
 * 而 `AppRoot` 的 Tab 切换用 `AnimatedContent` ——
 * **切走的页面会被 dispose**，而 `SaveableStateHolder` **只保 `rememberSaveable`，
 * 不保普通 `remember`**。
 *
 * 后果（实测）：
 * - 每次切到「记录」都**重拉 367 KB**（drives 307 KB + charges 60 KB）；
 * - 数据落地那一帧，5 个 `remember` 同时失效并全量重算
 *   （`mergeRecords` / `buildStationStats` / `buildRouteTemplates` /
 *   `buildRoutePlan` / `buildRouteHistory`），
 *   叠加 `AnimatedContent` 淡入淡出时**新旧两页同时组合** ——
 *   实测单帧主线程停顿 **430 ms**，整窗 UI 累计 1.67 s。
 *
 * 也就是说：切「记录」是 App **最常用的导航动作**，而它最卡。
 *
 * ## 现在的做法
 *
 * 状态放在 App 级（`TeslaMateApp` 作用域），页面只读它：
 * - 切 Tab 再回来，**数据还在**（不用重拉、不用重算）；
 * - `AnimatedContent` 换页时旧页被 dispose，但 App 级的状态不受影响。
 *
 * 数据量本身不大（362 drives + 69 charges），
 * 派生计算也都是 O(n) 毫秒级 —— 问题从来不是算法，
 * 而是**它们被频繁失效 + 在主线程跑**。
 */
class RecordsStore {

    /** 行程列表（按时间倒序，服务端已排好）。 */
    var drives by mutableStateOf<List<Drive>>(emptyList())
        private set

    /** 充电会话列表（按时间倒序）。 */
    var charges by mutableStateOf<List<Charge>>(emptyList())
        private set

    /** 行程请求的错误。 */
    var error by mutableStateOf<UserFacingException?>(null)
        private set

    /**
     * ⚠️ 充电请求的**独立错误** —— 它失败不该让整个记录页不可用。
     *
     * 原来两个请求是捆绑的（`drives to charges`），
     * 任一失败 → 两份数据一起被丢弃 → 整页回到骨架屏。
     */
    var chargeError by mutableStateOf<UserFacingException?>(null)
        private set

    /**
     * ⚠️ **v0.108.0（用户反馈 #27）**：充电统计的**三个区间**（服务端算）。
     *
     * ⚠️ 为 null = 没拿到（服务端是旧版本，或这次请求失败）
     * ⇒ 卡片显示「统计没加载出来」+ 重试，
     * **绝不在客户端自己算一套** —— 那会变成第二个口径（同一个数两处说法）。
     */
    var chargeStats by mutableStateOf<ChargeStats?>(null)
        private set

    /** 首次加载中（还没拿到任何数据）。 */
    var loading by mutableStateOf(true)
        private set

    /** 下拉刷新中。 */
    var refreshing by mutableStateOf(false)
        private set

    /** 已经拉过数据的 (carId, api 地址) —— 用来避免重复拉取。 */
    private var loadedFor: Pair<Int, String>? = null

    /**
     * 拉数据。
     *
     * @param force 下拉刷新时传 true（忽略「已经拉过」的判断）。
     */
    suspend fun load(api: Api?, carId: Int, force: Boolean = false) {
        val key = carId to (api?.baseUrl ?: "")
        if (!force && loadedFor == key && drives.isNotEmpty()) {
            loading = false
            return
        }
        if (api == null) {
            // ⚠️ 未配置 / 地址无效 → kind = AUTH，横幅直接给「前往设置」。
            error = UserFacingException(
                "服务器地址或令牌无效，请到「设置」页重新填写",
                ApiErrorKind.AUTH,
            )
            loading = false
            return
        }
        loading = drives.isEmpty()
        refreshing = !loading
        // ① 行程（时间轴的主干）
        runCatching {
            withContext(Dispatchers.IO) { api.drives(carId, 500) }
        }.onSuccess {
            drives = it
            error = null
            loadedFor = key
        }.onFailure {
            error = it as? UserFacingException
                ?: UserFacingException(it.message ?: "出错了")
        }
        // ② 充电（时间轴的次要成分 + 线路余量表的数据源）
        runCatching {
            withContext(Dispatchers.IO) { api.charges(carId, 300) }
        }.onSuccess {
            charges = it
            chargeError = null
        }.onFailure {
            chargeError = it as? UserFacingException
                ?: UserFacingException(it.message ?: "出错了")
        }
        // ③ 充电统计的三个区间（v0.108.0，用户反馈 #27）
        //
        // ⚠️ 失败**不阻塞**记录页（下面的充电列表照常用）⇒ 只把统计清成 null，
        // 卡片会显示「统计没加载出来」+ 重试。
        runCatching {
            withContext(Dispatchers.IO) { api.chargeStats(carId) }
        }.onSuccess {
            chargeStats = it
        }.onFailure {
            chargeStats = null
        }
        loading = false
        refreshing = false
    }

    /** 切车时清掉数据（否则标题换了、数据还是上一台车的）。 */
    fun clear() {
        drives = emptyList()
        charges = emptyList()
        error = null
        chargeError = null
        chargeStats = null
        loading = true
        loadedFor = null
    }
}
