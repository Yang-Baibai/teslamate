package com.teslamate.app

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val serverUrl: String = "",
    val token: String = "",
    val carId: Int = 1,
    /** 是否开启后台车况监听与本地通知 */
    val notifyEnabled: Boolean = false,
) {
    val configured: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()
    fun api(): Api? = Api.create(serverUrl, token)
}

private object Keys {
    val SERVER = stringPreferencesKey("server_url")
    val TOKEN = stringPreferencesKey("token")
    val CAR = intPreferencesKey("car_id")
    val NOTIFY = booleanPreferencesKey("notify_enabled")
}

fun Context.settingsFlow(): Flow<AppSettings> =
    settingsStore.data.map { p ->
        AppSettings(
            serverUrl = p[Keys.SERVER] ?: "",
            token = p[Keys.TOKEN] ?: "",
            carId = p[Keys.CAR] ?: 1,
            notifyEnabled = p[Keys.NOTIFY] ?: false,
        )
    }

/** 后台任务用：一次性取当前设置 */
suspend fun Context.currentSettings(): AppSettings = settingsFlow().first()

suspend fun Context.saveSettings(s: AppSettings) {
    settingsStore.edit { p ->
        p[Keys.SERVER] = s.serverUrl.trim()
        p[Keys.TOKEN] = s.token.trim()
        p[Keys.CAR] = s.carId
        p[Keys.NOTIFY] = s.notifyEnabled
    }
}
