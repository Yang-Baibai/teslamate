package com.teslamate.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.teslamate.app.ApiErrorKind
import com.teslamate.app.CachedRangeContext
import com.teslamate.app.CarStatus
import com.teslamate.app.ConsumptionModel
import com.teslamate.app.Drive
import com.teslamate.app.RangeEstimate
import com.teslamate.app.StatusCache
import com.teslamate.app.UserFacingException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

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
 * - **冷启动**：先从**落盘缓存**里把上次已知填上（v0.9.0 新增），
 *   界面立刻有数字，并标出「这是 X 小时前的」。
 * - **服务器挂掉**：落盘缓存里那份**继续显示**，上面挂一条错误横幅 + 重试按钮。
 *
 * ## 落盘缓存在哪
 *
 * 在 [StatusCache] 里（SharedPreferences）。这里只负责「跨 Tab 复用内存」+ 触发写盘。
 */
class StatusStore(private val cache: StatusCache? = null) {

    /** 最近一次成功取到的车况。切 Tab 时保留，用于 stale-while-revalidate。 */
    var status by mutableStateOf<CarStatus?>(null)
        private set

    /**
     * 当前显示的这份数据是**什么时候缓存下来的**（null = 刚刚从网络取到）。
     *
     * 界面用它显示「上次读到的数据 · 3 小时前」——
     * 一个会撒谎的缓存比没有缓存更糟，所以这个时刻必须一路带到界面上。
     */
    var cachedAt by mutableStateOf<Instant?>(null)
        private set

    /** 最近一次请求的错误。成功刷新后清空。 */
    var error by mutableStateOf<UserFacingException?>(null)
        private set

    /** 行程样本，用于建「车速 → 实测 Wh/km」能耗表。 */
    var drives by mutableStateOf<List<Drive>>(emptyList())
        private set

    /**
     * 实测能耗模型（由 [drives] 推导）。
     *
     * ⚠️ v0.9.0：它是 **App 级状态**而不是页面的 `remember` ——
     * 因为它要**落盘**：服务器挂掉时行程列表拿不到，
     * 模型就为 null → 续航退化成「官方标称」（比真实可达乐观 50%）。
     */
    var consumption by mutableStateOf<ConsumptionModel?>(null)
        private set

    /**
     * ⚠️ **v0.123.0：服务端算的保养 / 轮胎提醒结论。**
     *
     * ⚠️ 它是**首选**（判定与文案都在服务端，ADR #38 / #41）⇒
     * 飞书与 App 必然同一个数、同一句话。
     * 为 null（还没取到 / 取不到）⇒ 退回**本地兜底**。
     */
    var serverMaintenance by mutableStateOf<com.teslamate.app.MaintenanceReport?>(null)
        private set

    /** 最近一次行程的累计爬升（米），用于爬升修正。 */
    var recentAscentM by mutableStateOf<Int?>(null)
        private set


    /**
     * ⚠️ **v0.119.0：服务端算的「真实可达续航」结论。**
     *
     * ⚠️ 它是**首选**（判定只在服务端做，ADR #38）⇒ 飞书与 App 必然同一个数。
     * 为 null（还没取到 / 取不到）⇒ 退回**本地模型**兜底
     * （v0.9.0 的老教训：服务器挂掉不能退化成「乐观 50% 的标称」）。
     */
    var serverRange by mutableStateOf<RangeEstimate?>(null)
        private set

    /**
     * ⚠️ **v0.27.0：驾驶画像**（官方 Tesla App 没有的能力）。
     *
     * 服务端从 `positions` 的 162 万个采样点算 —— 实测 30 趟要 **几百毫秒**，
     * 所以它放在 **App 级 store** 而不是页面里：
     * 否则每次切到「车况」Tab 都要重算一次（而这正是 App 的首屏）。
     *
     * [driveProfileLoadedFor] 记录算过的是哪辆车 —— 切车要重算，
     * 但同一辆车切 Tab 回来不重算。
     */
    var driveProfile by mutableStateOf<com.teslamate.app.DrivingProfile?>(null)
        private set

    /** 驾驶画像正在算（首屏显示「正在算…」而不是空白卡）。 */
    var driveProfileLoading by mutableStateOf(false)
        private set

    /** 已经算过驾驶画像的车 id。 */
    var driveProfileLoadedFor by mutableStateOf<Int?>(null)
        private set

    /**
     * ⚠️ **v0.28.0：常驻点**（官方 App 只能告诉你「车现在在哪」）。
     *
     * 服务端算（相邻行程间隙 → 推断停留），按车缓存 —— 同理不重复算。
     */
    var stayPoints by mutableStateOf<com.teslamate.app.StayPoints?>(null)
        private set

    /** 已经算过常驻点的车 id。 */
    var stayPointsLoadedFor by mutableStateOf<Int?>(null)
        private set

    /** 常驻点正在算。 */
    var stayPointsLoading by mutableStateOf(false)
        private set

    /** 已有可用数据时为 true —— UI 据此决定是显示骨架屏还是直接显示旧数据。 */
    val hasData: Boolean get() = status != null

    /**
     * 冷启动时先把落盘缓存填进来。
     *
     * 同步执行（SharedPreferences 的首次 get 会阻塞十几毫秒）——
     * 换来的是「冷启动第一帧就有数字」，不闪骨架屏。
     *
     * ⚠️ **v0.22.0（自托管运维评审 P0-2）：按车加载** ——
     * 缓存键带 carId，且解出来的 `car_id` 必须与传入的一致。
     * 否则切车 + 断网时，App 会在 B 车的标题下**显示 A 车的数据**。
     */
    fun hydrate(carId: Int) {
        if (status != null) return
        cache?.load(carId)?.let { hit ->
            status = hit.status
            cachedAt = hit.savedAt
            // ⚠️ v0.29.0：冷启动时也先把**服务端时区**设上 ——
            // 否则在网络响应之前，记录页的按月分组会用手机时区，
            // 与服务端的聚合桶对不上（而且刷新后会突然换一批月份）。
            com.teslamate.app.ServerZone.set(hit.status.server_timezone)
        }
        cache?.loadRangeContext(carId)?.let { ctx ->
            consumption = ctx.consumption
            recentAscentM = ctx.recentAscentM
        }
    }

    /**
     * ⚠️ **v0.22.0（自托管运维评审 P0-2）：切车** ——
     *
     * 切车必须把内存里的车况**清掉**，否则：
     *   ① 标题已经换成 B 车，数据还是 A 车的；
     *   ② 断网时更糟 —— 落盘缓存按车分区后读不到 B 车，
     *      但内存里还留着 A 车的数据照样显示。
     *
     * 清完立刻 hydrate 新车的缓存（有的话立刻就有数字）。
     */
    fun switchCar(carId: Int) {
        status = null
        cachedAt = null
        consumption = null
        recentAscentM = null
        serverRange = null
        serverMaintenance = null
        drives = emptyList()
        error = null
        // ⚠️ v0.27.0：驾驶画像是**按车**的，换车必须作废（否则 B 车标题下显示 A 车的驾驶习惯）。
        driveProfile = null
        driveProfileLoadedFor = null
        driveProfileLoading = false
        stayPoints = null
        stayPointsLoadedFor = null
        stayPointsLoading = false
        hydrate(carId)
    }

    /**
     * ⚠️ **v0.22.0（自托管运维评审 P2-5）：换服务器 —— 清掉全部缓存。**
     *
     * 原来换服务器地址后，**旧服务器的车况缓存还留在盘上** ——
     * 新地址连不上时，App 会在新服务器名下显示**旧服务器**的数据。
     */
    fun clearCache() {
        status = null
        cachedAt = null
        consumption = null
        recentAscentM = null
        serverRange = null
        serverMaintenance = null
        drives = emptyList()
        error = null
        // ⚠️ v0.27.0：换服务器 = 全部重来，驾驶画像也要清。
        driveProfile = null
        driveProfileLoadedFor = null
        driveProfileLoading = false
        stayPoints = null
        stayPointsLoadedFor = null
        stayPointsLoading = false
        cache?.clearAll()
    }

    internal fun update(s: CarStatus) {
        status = s
        cachedAt = null
        error = null
        // ⚠️ **v0.29.0：把服务端时区设为全 App 的显示时区。**
        //
        // 服务端按 `TZ_NAME` 分日/月桶，App 原来按**手机时区**显示与分组 ——
        // 跨时区时同一件事在时间轴属于 10-06、在图表里却落到 10-07。
        // 现在统一以服务端为准（`Fmt.ServerZone`）。
        com.teslamate.app.ServerZone.set(s.server_timezone)
        cache?.save(s)
    }

    /**
     * 请求失败。
     *
     * ⚠️ v0.9.0：**不清空**已有的 status ——
     * 服务器挂掉时，用户至少还能看到上一次知道的值（并带着「3 小时前」的标注）。
     * 原来把 status 留在原地是对的，但错误只存了 message，
     * 界面没法区分「令牌错了（要跳设置）」和「网断了（重试就行）」。
     */
    internal fun fail(e: UserFacingException) {
        error = e
    }

    internal fun fail(message: String) {
        error = UserFacingException(message, ApiErrorKind.OTHER)
    }

    /**
     * 更新行程样本与能耗模型。
     *
     * ⚠️ **v0.24.0（性能评审 P1-2）：能耗模型由调用方在**后台线程**建好后传进来。**
     *
     * 原来 `ConsumptionModel.from(list)` 在**主线程**执行 ——
     * `withContext(Dispatchers.IO) { api.drives(...) }` 返回后
     * `.onSuccess { }` 已经切回 Main。
     * 它要对 362 条行程做 8 趟遍历 + 5 次分桶中位数。
     *
     * 当前数据量下约 1–3 ms（**未构成 ANR**），但架构上是错的 ——
     * 数据涨到 500 条 + 服务端补齐字段后会线性放大，
     * 而这正好落在「数据落地那一帧」（实测已是全 App 最卡的一帧）。
     */
    internal fun updateDrives(
        list: List<Drive>,
        carId: Int,
        model: ConsumptionModel?,
    ) {
        drives = list
        // ⚠️ v0.9.0：模型在这里存（而不是页面里 `remember`），
        // 这样才能一起落盘 —— 服务器挂掉时续航口径不变。
        consumption = model
        recentAscentM = list.firstOrNull()?.ascent_m
        model?.let {
            cache?.saveRangeContext(carId, CachedRangeContext(it, recentAscentM))
        }
    }



    /**
     * ⚠️ **v0.119.0：服务端的续航结论到了。**
     *
     * ⚠️ 取不到时**不清空**旧值（与 [fail] 同一个原则）：
     * 服务器临时算不动不该让首屏的主角数字变成空白。
     */
    internal fun updateServerRange(r: RangeEstimate) {
        serverRange = r
    }

    /**
     * ⚠️ **v0.123.0：服务端的保养 / 轮胎结论到了。**
     *
     * ⚠️ 取不到时**不清空**旧值（与 [fail] 同一个原则）：
     * 服务器临时算不动不该让保养提醒整块消失。
     */
    internal fun updateServerMaintenance(r: com.teslamate.app.MaintenanceReport) {
        serverMaintenance = r
    }
    /**
     * ⚠️ **v0.27.0：驾驶画像算完了。**
     *
     * 失败时**保留旧值**（和 [fail] 同一个原则）：
     * 服务器临时算不动不该让卡片变成空白 —— 但要标出「这是旧的」。
     */
    internal fun updateDriveProfile(
        profile: com.teslamate.app.DrivingProfile,
        carId: Int,
    ) {
        driveProfile = profile
        driveProfileLoadedFor = carId
        driveProfileLoading = false
    }

    /** 驾驶画像算失败了（保留旧值，只停掉 loading）。 */
    internal fun failDriveProfile() {
        driveProfileLoading = false
    }

    /** 开始算驾驶画像（显示「正在算…」）。 */
    internal fun startDriveProfile() {
        driveProfileLoading = true
    }

    /** ⚠️ v0.28.0：常驻点算完了。 */
    internal fun updateStayPoints(points: com.teslamate.app.StayPoints, carId: Int) {
        stayPoints = points
        stayPointsLoadedFor = carId
        stayPointsLoading = false
    }

    /** 开始算常驻点。 */
    internal fun startStayPoints() {
        stayPointsLoading = true
    }

    /** 常驻点算失败了（保留旧值，只停掉 loading）。 */
    internal fun failStayPoints() {
        stayPointsLoading = false
    }
}
