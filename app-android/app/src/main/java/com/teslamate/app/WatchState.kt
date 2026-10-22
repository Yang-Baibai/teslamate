package com.teslamate.app

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.watchStore by preferencesDataStore(name = "watch_state")

/**
 * 后台监听的「上一次状态」，用来判断事件是否发生了变化。
 *
 * 为什么要记状态：后台任务每隔一段时间看一眼车况，只有在**状态跳变**时才发通知——
 * 否则每 15 分钟都会收到一条「电量 33%」的噪音，几天后用户就会把通知全关掉。
 */
data class WatchState(
    /** 上一次检查时是否在充电 */
    val lastCharging: Boolean = false,
    /** 上一次的电量（充电结束时用它报最终电量） */
    val lastBattery: Int? = null,
    /** 上一次充电会话充入的电量（充电完成通知用） */
    val lastAddedKwh: Double? = null,
    /** 上一次是否已就胎压发过告警（用于去重，恢复正常后重置） */
    val lastTpmsAlert: Boolean = false,
    /** 上一次是否已就「数据过期」发过告警（用于去重） */
    val lastStaleAlert: Boolean = false,
    /** 车辆名字（缓存，避免每次都请求 /cars） */
    val carName: String? = null,
    /** 上次成功检查的时间戳（毫秒） */
    val lastCheckAt: Long = 0L,
)

private object WatchKeys {
    val CHARGING = booleanPreferencesKey("last_charging")
    val BATTERY = intPreferencesKey("last_battery")
    val ADDED_KWH = stringPreferencesKey("last_added_kwh")
    val TPMS_ALERT = booleanPreferencesKey("last_tpms_alert")
    val STALE_ALERT = booleanPreferencesKey("last_stale_alert")
    val CAR_NAME = stringPreferencesKey("car_name")
    val CHECK_AT = longPreferencesKey("last_check_at")
}

fun Context.watchStateFlow(): Flow<WatchState> =
    watchStore.data.map { p ->
        WatchState(
            lastCharging = p[WatchKeys.CHARGING] ?: false,
            lastBattery = p[WatchKeys.BATTERY],
            lastAddedKwh = p[WatchKeys.ADDED_KWH]?.toDoubleOrNull(),
            lastTpmsAlert = p[WatchKeys.TPMS_ALERT] ?: false,
            lastStaleAlert = p[WatchKeys.STALE_ALERT] ?: false,
            carName = p[WatchKeys.CAR_NAME],
            lastCheckAt = p[WatchKeys.CHECK_AT] ?: 0L,
        )
    }

suspend fun Context.currentWatchState(): WatchState = watchStateFlow().first()

suspend fun Context.saveWatchState(s: WatchState) {
    watchStore.edit { p ->
        p[WatchKeys.CHARGING] = s.lastCharging
        s.lastBattery?.let { p[WatchKeys.BATTERY] = it } ?: p.remove(WatchKeys.BATTERY)
        s.lastAddedKwh?.let { p[WatchKeys.ADDED_KWH] = it.toString() }
            ?: p.remove(WatchKeys.ADDED_KWH)
        p[WatchKeys.TPMS_ALERT] = s.lastTpmsAlert
        p[WatchKeys.STALE_ALERT] = s.lastStaleAlert
        s.carName?.let { p[WatchKeys.CAR_NAME] = it }
        p[WatchKeys.CHECK_AT] = s.lastCheckAt
    }
}
