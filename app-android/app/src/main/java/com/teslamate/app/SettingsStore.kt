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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
/**
 * 一台车的保养 / 换胎基准。
 *
 * ⚠️ v0.16.0（车队运营评审 P0-3）：基准必须**按车隔离**。
 *
 * 原来四个基准值是全局单值 —— 家人那台车里程表 30,000 km，
 * 我的 101,455 km，但两边共用一套「79,000 + 20,000」的基准，
 * 切到家人车时那条「保养已超期 2455 公里」是**假告警**。
 * 给错的车报错，比没这个功能更糟。
 */
@Serializable
data class CarBaselines(
    /** 上次保养时的里程表读数（km）；0 = 未设置 */
    val serviceOdoKm: Double = 0.0,
    /** 保养间隔（km） */
    val serviceIntervalKm: Int = 20000,
    /** 上次换胎时的里程表读数（km）；0 = 未设置 */
    val tireOdoKm: Double = 0.0,
    /** 轮胎更换间隔（km） */
    val tireIntervalKm: Int = 40000,
)

/** 按车 id 存的基准表（DataStore 里存成一个 JSON 字符串）。 */
@Serializable
private data class PerCarBaselines(val cars: Map<Int, CarBaselines> = emptyMap())

data class AppSettings(
    val serverUrl: String = "",
    val token: String = "",
    val carId: Int = 1,
    /** 上次保养时的里程表读数（km）；0 = 未设置 —— **旧字段，保留用于迁移** */
    val serviceOdoKm: Double = 0.0,
    /** 保养间隔（km）—— 旧字段 */
    val serviceIntervalKm: Int = 20000,
    /** 上次换胎时的里程表读数（km）—— 旧字段 */
    val tireOdoKm: Double = 0.0,
    /** 轮胎更换间隔（km）—— 旧字段 */
    val tireIntervalKm: Int = 40000,
    /**
     * ⚠️ v0.16.0：**按车隔离**的基准表。
     *
     * 迁移规则：`perCar` 为空而旧字段有值时，旧值归入**当前车**
     * （旧字段保留不删 —— 回滚安全，符合 ADR 的变更规则）。
     */
    val perCar: Map<Int, CarBaselines> = emptyMap(),
) {
    val configured: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()
    fun api(): Api? = Api.create(serverUrl, token)

    /**
     * 取某台车的基准。
     *
     * ⚠️ 旧版本只有一组全局值 —— 那组值属于**当时正在看的那台车**，
     * 所以只有当前车能继承它；别的车一律「未设置」（不显示提醒卡）。
     *
     * ⚠️⚠️ **v0.20.0（iOS IA 评审 P0-4 顺带查出的多车串号 bug）**：
     * 迁移条件原来是「`perCar[id]` 缺失 **且** `id == carId`」——
     * 这意味着**任何一台没配过基准的车，只要它是当前车，就会继承旧字段**。
     *
     * 复现（本机只有 1 台车，**只能靠推理发现，端到端测不出来**）：
     * 1. 车主 A 车配了基准 79,000 / 20,000 → `perCar = {1: …}`，旧字段也写了这组值；
     * 2. 家人切到 B 车（id=2），B 从没配过基准 → `perCar` 里**没有 2**；
     * 3. `baselinesFor(2)` → `perCar[2]` 缺失，`2 == carId` 成立
     *    → **回落旧字段** → B 车拿到 A 车的「保养 79,000 公里」基准；
     * 4. B 车里程表 30,000 → App 报「保养超期 49,000 公里」——
     *    正是 v0.16.0 要消灭的**假告警**，只是换了个形式回来。
     *
     * 修法：迁移是**一次性**的，只在 `perCar` **整体为空**（从未迁移过）时生效。
     * 一旦 `perCar` 非空，它就是唯一事实源 —— 缺就是「未设置」，不再回落。
     */
    fun baselinesFor(id: Int): CarBaselines {
        perCar[id]?.let { return it }
        if (perCar.isEmpty() && id == carId) {
            return CarBaselines(
                serviceOdoKm = serviceOdoKm,
                serviceIntervalKm = serviceIntervalKm,
                tireOdoKm = tireOdoKm,
                tireIntervalKm = tireIntervalKm,
            )
        }
        return CarBaselines()
    }

    /** 当前车的基准。 */
    val currentBaselines: CarBaselines get() = baselinesFor(carId)

    /** 保养提醒是否已启用（填过基准里程） */
    val serviceReminderOn: Boolean get() = currentBaselines.serviceOdoKm > 0

    /** 换胎提醒是否已启用 */
    val tireReminderOn: Boolean get() = currentBaselines.tireOdoKm > 0
}

private object Keys {
    val SERVER = stringPreferencesKey("server_url")
    val TOKEN = stringPreferencesKey("token")
    val CAR = intPreferencesKey("car_id")
    val SERVICE_ODO = doublePreferencesKey("service_odo_km")
    val SERVICE_INTERVAL = intPreferencesKey("service_interval_km")
    val TIRE_ODO = doublePreferencesKey("tire_odo_km")
    val TIRE_INTERVAL = intPreferencesKey("tire_interval_km")
    /** ⚠️ v0.16.0：按车隔离的基准表（JSON 字符串）。 */
    val PER_CAR = stringPreferencesKey("per_car_baselines")
}

private val settingsJson = Json { ignoreUnknownKeys = true }

fun Context.settingsFlow(): Flow<AppSettings> =
    settingsStore.data.map { p ->
        val perCar = p[Keys.PER_CAR]
            ?.let {
                runCatching {
                    settingsJson.decodeFromString(PerCarBaselines.serializer(), it)
                }.getOrNull()
            }
            ?.cars
            ?: emptyMap()
        AppSettings(
            serverUrl = p[Keys.SERVER] ?: "",
            token = p[Keys.TOKEN] ?: "",
            carId = p[Keys.CAR] ?: 1,
            serviceOdoKm = p[Keys.SERVICE_ODO] ?: 0.0,
            serviceIntervalKm = p[Keys.SERVICE_INTERVAL] ?: 20000,
            tireOdoKm = p[Keys.TIRE_ODO] ?: 0.0,
            tireIntervalKm = p[Keys.TIRE_INTERVAL] ?: 40000,
            perCar = perCar,
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
        // ⚠️ v0.16.0：把**当前车**的基准写进 perCar。
        //
        // 旧字段也继续写（保留回滚安全），但 perCar 才是新的事实源。
        val cur = s.currentBaselines
        val next = s.perCar.toMutableMap()
        if (cur.serviceOdoKm > 0 || cur.tireOdoKm > 0) {
            next[s.carId] = cur
        } else {
            next.remove(s.carId)
        }
        p[Keys.PER_CAR] = settingsJson.encodeToString(
            PerCarBaselines.serializer(),
            PerCarBaselines(next),
        )
    }
}
