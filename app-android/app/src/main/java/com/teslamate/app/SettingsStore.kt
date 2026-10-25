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
/**
 * 电价与成本设置（**按车隔离**）。
 *
 * ⚠️ **v0.25.0（第 16 轮充电成本专家 P0-1 / P1-5）**。
 *
 * ## 为什么是**车主自己填**
 *
 * 库里 `geofences` 表**本来就有** TeslaMate 的定价字段
 * （`cost_per_unit` / `session_fee` / `billing_type(per_kwh|per_minute)`），
 * 但那张表**是空的**（0 行），而 `charging_processes.geofence_id` 78 场全为 NULL ——
 * 「这次充的是哪个桩、那个桩多少钱」在数据层**根本不存在**。
 *
 * 所以必须车主自己填 —— 而这恰恰是**官方 App 做不到**的事：
 * 它只认得 Tesla 超充（账单能从 Tesla 账户抓），
 * 公共直流桩的账单它拿不到；家充电价它虽然能填，
 * 但它**给不出「每公里成本」** —— 因为它不知道你的真实行驶里程口径。
 *
 * ## 为什么服务费要能按分钟或按度
 *
 * 实测本车 73 场直流：纯按度算 2022 元，按分钟算 2722 元 —— **差 34.6%**。
 * 服务区快充普遍按分钟收服务费（**占位时间也要钱**），
 * 而本车有 41 场没充到 100% 就拔枪，这类场次按分钟计最吃亏。
 *
 * 单一「每度单价」会**系统性低估 25%~35%**。
 *
 * ⚠️ 反过来：**家充绝不能按分钟算** ——
 * 实测 id=44 插枪 617 分钟只充入 17 度（有效功率仅 1.65 kW），
 * 插枪时长包含大量闲置，按分钟算会严重高估。
 */
@Serializable
data class ChargeRates(
    /** 家充电价（元/度）；0 = 没配 */
    val homeYuanPerKwh: Double = 0.0,
    /** 公共桩电费（元/度）；0 = 没配 */
    val publicYuanPerKwh: Double = 0.0,
    /**
     * 公共桩服务费的计费方式：
     * - `"per_kwh"`：服务费按度（元/度）
     * - `"per_minute"`：服务费按分钟（元/分钟）
     */
    val publicServiceMode: String = MODE_PER_KWH,
    /** 公共桩服务费（元/度 或 元/分钟，取决于 [publicServiceMode]） */
    val publicServiceYuan: Double = 0.0,
) {
    /** 有没有配过价（没配就不显示任何费用数字）。 */
    val configured: Boolean get() = homeYuanPerKwh > 0 || publicYuanPerKwh > 0

    companion object {
        const val MODE_PER_KWH = "per_kwh"
        const val MODE_PER_MINUTE = "per_minute"
    }
}

/**
 * 一台车的保养 / 换胎基准。
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
    /**
     * ⚠️ **v0.25.0：按车隔离的电价表**。
     *
     * 与 [perCar] 同范式 —— 家充电价、公共桩电价各按车存
     * （家人那台车跑的路不一样，电价也可能不一样）。
     *
     * 没有迁移需求（电价是新功能，没有旧字段）。
     */
    val ratePerCar: Map<Int, ChargeRates> = emptyMap(),
) {
    val configured: Boolean get() = serverUrl.isNotBlank() && token.isNotBlank()
    fun api(): Api? = Api.create(serverUrl, token)

    /**
     * 取某台车的电价。
     *
     * ⚠️ 与 [baselinesFor] **不同**：电价**不做迁移回落** ——
     * 没配就是没配（旧字段里根本没有电价，回落会凭空造出一个 0）。
     */
    fun ratesFor(id: Int): ChargeRates = ratePerCar[id] ?: ChargeRates()

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

    /** 当前车的电价 */
    val currentRates: ChargeRates get() = ratesFor(carId)
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
    /** ⚠️ v0.25.0：按车隔离的电价表（JSON 字符串）。 */
    val RATE_PER_CAR = stringPreferencesKey("rate_per_car")
}

/** 按车 id 存的电价表。 */
@Serializable
private data class PerCarRates(val cars: Map<Int, ChargeRates> = emptyMap())

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
        val ratePerCar = p[Keys.RATE_PER_CAR]
            ?.let {
                runCatching {
                    settingsJson.decodeFromString(PerCarRates.serializer(), it)
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
            ratePerCar = ratePerCar,
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
        // ⚠️ v0.25.0：电价表同样**按车隔离**。
        val rates = s.currentRates
        val ratesNext = s.ratePerCar.toMutableMap()
        if (rates.configured) {
            ratesNext[s.carId] = rates
        } else {
            ratesNext.remove(s.carId)
        }
        p[Keys.RATE_PER_CAR] = settingsJson.encodeToString(
            PerCarRates.serializer(),
            PerCarRates(ratesNext),
        )
    }
}
