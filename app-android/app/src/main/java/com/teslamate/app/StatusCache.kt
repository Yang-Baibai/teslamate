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
     * 读不出来（没缓存 / JSON 损坏 / 时间戳非法）一律返回 null ——
     * 宁可没有，也不要半截数据。
     */
    fun load(): CachedStatus? {
        val raw = prefs.getString(KEY_STATUS, null) ?: return null
        val savedAt = runCatching { Instant.parse(prefs.getString(KEY_SAVED_AT, null) ?: return null) }
            .getOrNull() ?: return null
        val status = runCatching { json.decodeFromString(CarStatus.serializer(), raw) }.getOrNull() ?: return null
        return CachedStatus(status, savedAt)
    }

    /**
     * 写盘。
     *
     * 失败**静默** —— 缓存写不进去不该影响主流程（用户还是能正常看实时数据）。
     */
    fun save(status: CarStatus) {
        runCatching {
            prefs.edit()
                .putString(KEY_STATUS, json.encodeToString(CarStatus.serializer(), status))
                .putString(KEY_SAVED_AT, Instant.now().toString())
                .apply()
        }
    }

    private companion object {
        const val PREFS = "tm_status_cache"
        const val KEY_STATUS = "last_status"
        const val KEY_SAVED_AT = "last_saved_at"
    }
}

/** 一次缓存命中：数据 + 它被缓存下来的时刻。 */
data class CachedStatus(
    val status: CarStatus,
    val savedAt: Instant,
)
