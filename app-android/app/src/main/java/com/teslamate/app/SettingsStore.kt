package com.teslamate.app

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

/**
 * App 设置。
 *
 * ⚠️ v0.9.0：新增保养 / 换胎提醒的四项。
 *
 * 为什么需要它：新手车主复核时列了一张「找不到的功能」清单，第一条就是
 * 「上次保养是什么时候 / 什么时候该换胎 —— 全项目 grep 保养/维修/保险 **0 命中**，
 * API 也没这个接口。这是我最想要的」。
 *
 * 而它**本来就不该由服务端提供** —— 保养记录是车主自己的线下行为，
 * 没有任何机器能自动知道。所以这里让车主**手动填一个基准**
 * （上次保养时的里程表读数）+ 一个间隔（多少公里该做一次），
 * App 用里程表一减就得到「还剩多少公里」。
 *
 * 两个值都是 **0 = 未设置**（未设置就不显示卡片，不制造噪音）。
 */
data class AppSettings(
    val serverUrl: String = "",
    val token: String = "",
    val carId: Int = 1,
    /** 上次保养时的里程表读数（km）；0 = 未设置 */
    val serviceOdoKm: Double = 0.0,
    /** 保养间隔（km） */
    val serviceIntervalKm: Int = 20000,
    /** 上次换胎时的里程表读数（km）；0 = 未设置 */
    val tireOdoKm: Double = 0.0,
    /** 轮胎更换间隔（km） */
    val tireIntervalKm: Int = 40000,
) {
    val configured: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()
    fun api(): Api? = Api.create(serverUrl, token)

    /** 保养提醒是否已启用（填过基准里程） */
    val serviceReminderOn: Boolean get() = serviceOdoKm > 0

    /** 换胎提醒是否已启用 */
    val tireReminderOn: Boolean get() = tireOdoKm > 0
}

private object Keys {
    val SERVER = stringPreferencesKey("server_url")
    val TOKEN = stringPreferencesKey("token")
    val CAR = intPreferencesKey("car_id")
    val SERVICE_ODO = doublePreferencesKey("service_odo_km")
    val SERVICE_INTERVAL = intPreferencesKey("service_interval_km")
    val TIRE_ODO = doublePreferencesKey("tire_odo_km")
    val TIRE_INTERVAL = intPreferencesKey("tire_interval_km")
}

fun Context.settingsFlow(): Flow<AppSettings> =
    settingsStore.data.map { p ->
        AppSettings(
            serverUrl = p[Keys.SERVER] ?: "",
            token = p[Keys.TOKEN] ?: "",
            carId = p[Keys.CAR] ?: 1,
            serviceOdoKm = p[Keys.SERVICE_ODO] ?: 0.0,
            serviceIntervalKm = p[Keys.SERVICE_INTERVAL] ?: 20000,
            tireOdoKm = p[Keys.TIRE_ODO] ?: 0.0,
            tireIntervalKm = p[Keys.TIRE_INTERVAL] ?: 40000,
        )
    }

/** 后台脚本用：一次性取当前设置 */
suspend fun Context.currentSettings(): AppSettings = settingsFlow().first()

suspend fun Context.saveSettings(s: AppSettings) {
    settingsStore.edit { p ->
        p[Keys.SERVER] = s.serverUrl.trim()
        p[Keys.TOKEN] = s.token.trim()
        p[Keys.CAR] = s.carId
        p[Keys.SERVICE_ODO] = s.serviceOdoKm
        p[Keys.SERVICE_INTERVAL] = s.serviceIntervalKm
        p[Keys.TIRE_ODO] = s.tireOdoKm
        p[Keys.TIRE_INTERVAL] = s.tireIntervalKm
    }
}
