package com.teslamate.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.teslamate.app.ApiErrorKind
import com.teslamate.app.CachedRangeContext
import com.teslamate.app.CarStatus
import com.teslamate.app.ConsumptionModel
import com.teslamate.app.Drive
import com.teslamate.app.StatusCache
import com.teslamate.app.UserFacingException
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

    /** 最近一次行程的累计爬升（米），用于爬升修正。 */
    var recentAscentM by mutableStateOf<Int?>(null)
        private set

    /** 已有可用数据时为 true —— UI 据此决定是显示骨架屏还是直接显示旧数据。 */
    val hasData: Boolean get() = status != null

    /**
     * 冷启动时先把落盘缓存填进来。
     *
     * 同步执行（SharedPreferences 的首次 get 会阻塞十几毫秒）——
     * 换来的是「冷启动第一帧就有数字」，不闪骨架屏。
     */
    fun hydrate() {
        if (status != null) return
        cache?.load()?.let { hit ->
            status = hit.status
            cachedAt = hit.savedAt
        }
        cache?.loadRangeContext()?.let { ctx ->
            consumption = ctx.consumption
            recentAscentM = ctx.recentAscentM
        }
    }

    internal fun update(s: CarStatus) {
        status = s
        cachedAt = null
        error = null
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

    internal fun updateDrives(list: List<Drive>) {
        drives = list
        // ⚠️ v0.9.0：模型在这里建（而不是页面里 `remember`），
        // 这样才能一起落盘 —— 服务器挂掉时续航口径不变。
        consumption = ConsumptionModel.from(list)
        recentAscentM = list.firstOrNull()?.ascent_m
        consumption?.let {
            cache?.saveRangeContext(CachedRangeContext(it, recentAscentM))
        }
    }
}
