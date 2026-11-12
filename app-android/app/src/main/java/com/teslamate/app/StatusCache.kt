package com.teslamate.app

import android.content.Context
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * 车况的**落盘缓存**。
 *
 * ## 为什么必须有它（v0.9.0）
 *
 * 可靠性复核（值班工程师视角）发现一处**功能缺失**：
 * [StatusStore] 的注释写着「落盘缓存在 App 的 DataStore 里做」，
 * 但 DataStore 里只有 server_url / token / car_id 三个 key ——
 * **车况从来没有落盘过**。后果：
 *
 * - 进程被杀后重开 → 回到骨架屏 + 空白
 * - 服务器整个挂掉 → App 交出的是「空白页 + 一条红条」，
 *   连「上次已知」都拿不出来
 *
 * 而这个 App 的核心场景恰恰是「每天开门前看一眼」——
 * 最需要它的时候（车在地库、服务器刚重启、App 刚被杀）
 * 正是它最拿不出东西的时候。
 *
 * ## 缓存的诚实性
 *
 * 缓存的数据**永远带着「这是什么时候的」**：
 * 界面会显示「上次读到的数据 · 3 小时前」，绝不假装是刚刚的。
 * 这是整个缓存设计的第一原则 —— 一个会撒谎的缓存比没有缓存更糟。
 *
 * ## 为什么用 SharedPreferences 而不是 DataStore
 *
 * DataStore 是异步的，冷启动时读它会多一帧延迟（骨架屏先闪一下再被替换）。
 * 车况 JSON 只有 2 KB 左右，SharedPreferences 首次 get 会阻塞十几毫秒，
 * 换来的是「冷启动第一帧就有数据」。这个取舍对「每天看一眼」的场景是划算的。
 */
class StatusCache(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 读出上次成功取到的车况。
     *
     * ⚠️ **v0.22.0（自托管运维评审 P0-2）：缓存**按车分区**。**
     *
     * 原来只有一个全局键 `last_status` —— 于是：
     *   ① 在 A 车看到车况 → 切到 B 车 → 此时服务器不可达
     *      → App 在 B 车的标题下**显示 A 车的**电量、位置、锁车状态、里程表。
     *
     * ADR #23（多车上下文）自己写着「读错数据的风险，不是体验问题」——
     * 这正是同一类风险的未覆盖分支。
     *
     * 现在：键名带 carId，每台车各存一份。
     * 另外还有一道**兜底**：解出来的 `car_id` 与请求的不一致就丢弃
     * （防止历史版本留下的旧键被误读）。
     *
     * 读不出来（没缓存 / JSON 损坏 / 时间戳非法）一律返回 null ——
     * 宁可没有，也不要半截数据。
     */
    fun load(carId: Int): CachedStatus? {
        // ⚠️ **v0.146.0：结构版本对不上 ⇒ 整份作废。**
        // 本轮给 CarStatus 补了 outside_temp_last / outside_temp_at
        // ⇒ 旧缓存里**没有**这两个字段 ⇒ 界面会拿到一份「缺字段的半截数据」
        // ⇒ 与本文件开头那条「宁可没有，也不要半截数据」相悖 ⇒ 宁可显示空状态。
        if (prefs.getInt(schemaKey, 0) != cacheSchema) return null
        val raw = prefs.getString(statusKey(carId), null) ?: return null
        val savedAt = runCatching { Instant.parse(prefs.getString(savedAtKey(carId), null) ?: return null) }
            .getOrNull() ?: return null
        val status = runCatching { json.decodeFromString(CarStatus.serializer(), raw) }.getOrNull() ?: return null
        // 兜底：缓存里的车必须是这台车。
        if (status.car_id != carId) return null
        return CachedStatus(status, savedAt)
    }

    /**
     * 写盘。
     *
     * 按 `status.car_id` 分区 —— 调用方不用管分区，是数据自己说的。
     *
     * 失败**静默** —— 缓存写不进去不该影响主流程（用户还是能正常看实时数据）。
     */
    fun save(status: CarStatus) {
        val carId = status.car_id
        runCatching {
            prefs.edit()
                .putInt(schemaKey, cacheSchema)
                .putString(statusKey(carId), json.encodeToString(CarStatus.serializer(), status))
                .putString(savedAtKey(carId), Instant.now().toString())
                .apply()
        }
    }

    /**
     * 缓存续航推算上下文（实测能耗模型 + 最近一次爬升）。
     *
     * 与 [save] 分开写：它更新得**更频繁**（每次拉到行程就更新），
     * 而且体积小得多。
     *
     * ⚠️ v0.22.0：同样**按车分区** —— 它来自行程列表，也是按车的。
     */
    fun saveRangeContext(carId: Int, ctx: CachedRangeContext) {
        runCatching {
            prefs.edit()
                .putString(rangeKey(carId), json.encodeToString(CachedRangeContext.serializer(), ctx))
                .apply()
        }
    }

    /** 读出续航推算上下文；没有或已损坏返回 null。 */
    fun loadRangeContext(carId: Int): CachedRangeContext? {
        val raw = prefs.getString(rangeKey(carId), null) ?: return null
        return runCatching { json.decodeFromString(CachedRangeContext.serializer(), raw) }.getOrNull()
    }

    /**
     * ⚠️ **v0.22.0（自托管运维评审 P2-5）：换服务器时清掉全部缓存。**
     *
     * 原来换服务器地址后，**旧服务器的车况缓存还留在盘上** ——
     * 于是新地址连不上时，App 会在新服务器名下显示**旧服务器**的数据。
     *
     * 与 P0-2（缓存按车分区）是同一类错误的另一半：
     * 分区解决了「换车」，这个解决「换服务器」。
     */
    fun clearAll() {
        runCatching {
            prefs.edit().clear().apply()
        }
    }

    private companion object {
        const val PREFS = "tm_status_cache"
        /**
         * ⚠️ **v0.146.0：缓存的结构版本**（model 变了就 +1 ⇒ 旧缓存整份作废）。
         *
         * 比「App 升级就清一次」更准：只有**缓存结构真的变了**才作废，
         * 平常升级不丢用户那份「上次已知」。
         *
         * ⚠️ **v0.149.0 就地订正这条判据**（上面那两句保留原样，作为错误痕迹）：
         * 真正要作废的是**语义变了**，不是「model 变了」。
         *
         * - **语义变了**（同一个字段名的含义变了 —— v0.142.0 把 `outside_temp`
         *   从「上次已知」收窄成「当前读数」）⇒ **必须** bump：旧缓存里的值会被
         *   当成「当前」印出来 ⇒ 那是**假新鲜度** ⇒ 比没有缓存更糟；
         * - **新增可空字段 / 改类型 / 改名** ⇒ **不必** bump：解出来只是
         *   「没有这条信息」或默认值 ⇒ **不是半截数据** ⇒ 不该作废。
         *
         * ⇒ 本轮给 CarStatus 补 `inside_temp_last` / `inside_temp_at`
         *   （**新增可空字段**）⇒ **不作废**（仍是 2）⇒ 用户的「上次已知」
         *   在 App 升级后**仍然留着** —— 而缓存存在的理由正是
         *   「服务器整个挂掉时还能交出上次已知」。
         */
        const val cacheSchema = 2
        const val schemaKey = "cache_schema"
        // ⚠️ v0.22.0：键名带 carId —— 缓存**按车分区**（见 load 的说明）。
        fun statusKey(carId: Int) = "last_status_car_$carId"
        fun savedAtKey(carId: Int) = "last_saved_at_car_$carId"
        fun rangeKey(carId: Int) = "last_range_ctx_car_$carId"
    }
}

/**
 * 一次缓存命中：数据 + 它被缓存下来的时刻。
 */
data class CachedStatus(
    val status: CarStatus,
    val savedAt: Instant,
)

/**
 * 缓存的「续航推算上下文」。
 *
 * ⚠️ v0.9.0（故障注入实测）：只缓存 `status` 是不够的 ——
 * 「真实可达」要用的实测能耗模型是从行程列表现算的，
 * 服务器挂掉时行程列表拿不到 → 模型为 null → 界面退化成「官方标称」，
 * **比真实可达乐观 50%**，而且口径无声地变了。
 *
 * 所以这里把**模型本身**（只有 4~5 个分桶）连同最近一次行程的爬升一起缓存。
 */
@kotlinx.serialization.Serializable
data class CachedRangeContext(
    val consumption: ConsumptionModel,
    /** 最近一次行程的累计爬升（米），用于爬升修正 */
    val recentAscentM: Int? = null,
)
