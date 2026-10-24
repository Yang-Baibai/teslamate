package com.teslamate.app

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * 面向用户的中文错误。
 *
 * 为什么需要它：这个 App 的定位是「一台车 + 家人」（见 docs/adr.md #17），
 * 但 OkHttp 抛出的异常原文是给开发者看的——
 *   "Failed to connect to /192.168.1.7:8090"
 *   "timeout"
 * 直接显示给家人等于让人家看天书。
 *
 * 约定：所有 [get] 抛出的异常都是这个类型，message 一定是可直接展示的中文。
 * 页面侧统一用 `it.message` 展示即可，不需要再做二次翻译。
 */
class UserFacingException(message: String) : IOException(message)

// Instant <-> ISO-8601 字符串（API 统一返回 UTC，如 2026-10-11T13:13:14.590Z）
object InstantSer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
}

@Serializable
data class Car(
    val id: Int,
    val name: String,
    val model: String? = null,
    val trim_badging: String? = null,
    val vin: String? = null,
    val marketing_name: String? = null,
    val efficiency: Double? = null,
    val exterior_color: String? = null,
    val wheel_type: String? = null,
)

@Serializable
data class ActiveCharge(
    val charge_id: Int,
    @Serializable(with = InstantSer::class) val started_at: Instant,
    val energy_added_kwh: Double? = null,
    val charger_power_kw: Int? = null,
    val charger_voltage: Int? = null,
    val charger_current: Int? = null,
    val charger_phases: Int? = null,
    val fast_charger_present: Boolean? = null,
    val battery_level: Int? = null,
)

@Serializable
data class ActiveDrive(
    val drive_id: Int,
    @Serializable(with = InstantSer::class) val started_at: Instant,
)

@Serializable
data class CarStatus(
    val car_id: Int,
    @Serializable(with = InstantSer::class) val updated_at: Instant,
    val state: String? = null,
    @Serializable(with = InstantSer::class) val state_since: Instant? = null,
    val battery_level: Int? = null,
    val usable_battery_level: Int? = null,
    val ideal_range_km: Double? = null,
    val est_range_km: Double? = null,
    val rated_range_km: Double? = null,
    val odometer: Double? = null,
    val speed: Int? = null,
    val power: Int? = null,
    val outside_temp: Double? = null,
    val inside_temp: Double? = null,
    val is_climate_on: Boolean? = null,
    val elevation: Int? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val map_lat: Double? = null,
    val map_lng: Double? = null,
    val location_name: String? = null,
    val tpms_fl: Double? = null,
    val tpms_fr: Double? = null,
    val tpms_rl: Double? = null,
    val tpms_rr: Double? = null,
    // 服务端算好的告警判定（唯一事实源，App 不再自己算阈值）
    val tpms_alert: Boolean = false,
    val tpms_alert_wheels: List<String> = emptyList(),
    val tpms_alert_detail: String = "",
    val tpms_min_bar: Double = 2.5,
    val tpms_max_bar: Double = 3.3,
    /**
     * 锁车状态三态（Tesla Fleet API 提供，TeslaMate 不采集）。
     *
     * - `true` = 已上锁
     * - `false` = 未上锁
     * - **null = 未知**（未配置 Fleet API 凭据、令牌刷新失败、或 API 不可达）
     *
     * ⚠️ null 必须能表达「不知道」，不能默认 false —— 否则 App 会把
     * 「没取到数据」说成「车没锁」，这是危险的假信息。
     */
    val locked: Boolean? = null,
    /** 锁车状态的采集时刻（用于判断新鲜度） */
    @Serializable(with = InstantSer::class) val locked_at: Instant? = null,
    val data_age_hours: Int = 0,
    val data_stale: Boolean = false,
    val active_charge: ActiveCharge? = null,
    val active_drive: ActiveDrive? = null,
)

@Serializable
data class Drive(
    val id: Int,
    @Serializable(with = InstantSer::class) val start_date: Instant,
    @Serializable(with = InstantSer::class) val end_date: Instant? = null,
    val duration_min: Int? = null,
    val distance_km: Double? = null,
    val start_place: String? = null,
    val end_place: String? = null,
    val start_soc: Int? = null,
    val end_soc: Int? = null,
    val energy_kwh: Double? = null,
    val wh_per_km: Double? = null,
    val speed_max: Int? = null,
    val outside_temp_avg: Double? = null,
    val odometer_start: Double? = null,
    val odometer_end: Double? = null,
    val avg_speed_kph: Int? = null,
    val ascent_m: Int? = null,
    val descent_m: Int? = null,
    val inside_temp_avg: Double? = null,
    val power_max_kw: Int? = null,    val power_min_kw: Int? = null,
    val start_rated_range_km: Double? = null,
    val end_rated_range_km: Double? = null,
    val start_ideal_range_km: Double? = null,
    val end_ideal_range_km: Double? = null,
    val start_geofence: String? = null,
    val end_geofence: String? = null,
    val has_supercharger: Boolean = false,
    /**
     * 这趟行程结束时附近是否发生过充电（**任意类型**，含交流）。
     *
     * v0.7.1 服务端修复：`has_supercharger` 原先用的是「充电时间落在行程时间窗内」，
     * 而充电永远发生在**行程结束之后**，于是这个字段恒为 false（实测 200 条行程 0 条命中）。
     * 现在服务端改用**里程关联**（|充电起始里程 − 行程结束里程| < 2 km），
     * 命中数从 0 变成 52/200。
     */
    val charge_after_trip: Boolean = false,
)

@Serializable
data class TrackPoint(
    @Serializable(with = InstantSer::class) val date: Instant,
    val lat: Double,
    val lng: Double,
    val map_lat: Double? = null,
    val map_lng: Double? = null,
    val speed: Int? = null,
    val power: Int? = null,
    val battery_level: Int? = null,
    val elevation: Int? = null,
)

@Serializable
data class Charge(
    val id: Int,
    @Serializable(with = InstantSer::class) val start_date: Instant,
    @Serializable(with = InstantSer::class) val end_date: Instant? = null,
    val duration_min: Int? = null,
    val energy_added_kwh: Double? = null,
    val energy_used_kwh: Double? = null,
    val cost: Double? = null,
    val start_soc: Int? = null,
    val end_soc: Int? = null,
    val place: String? = null,
    val map_lat: Double? = null,
    val map_lng: Double? = null,
    val is_dc: Boolean = false,
    val geofence_name: String? = null,
    val start_range_km: Double? = null,
    val end_range_km: Double? = null,
    val start_odometer_km: Double? = null,
    val end_odometer_km: Double? = null,
    val peak_power_kw: Int? = null,
    val avg_power_kw: Int? = null,
    val peak_voltage: Int? = null,
    val avg_voltage: Int? = null,
    val peak_current: Int? = null,
    val avg_current: Int? = null,
    val start_outside_temp: Double? = null,
    val end_outside_temp: Double? = null,
    val start_indoor_temp: Double? = null,
    val end_indoor_temp: Double? = null,
    val charger_phases: Int? = null,
    val charger_voltage: Int? = null,
    val charger_current: Int? = null,
    val outside_temp_avg: Double? = null,
    val cable_type: String? = null,
    val charger_brand: String? = null,
    val loss_percent: Double? = null,
)

@Serializable
data class CurvePoint(
    @Serializable(with = InstantSer::class) val date: Instant,
    val battery_level: Int? = null,
    val charger_power_kw: Int? = null,
    val charger_voltage: Int? = null,
    val charger_current: Int? = null,
    val charger_phases: Int? = null,
)

@Serializable
data class StatsBucket(
    val start: String,
    val km: Double = 0.0,
    val minutes: Double = 0.0,
    val kwh_used: Double = 0.0,
    val wh_per_km: Double? = null,
    val drive_count: Int = 0,
    val charge_kwh: Double = 0.0,
    val dc_kwh: Double = 0.0,
    val ac_kwh: Double = 0.0,
    val cost: Double = 0.0,
    val charge_count: Int = 0,
)

@Serializable
data class Stats(
    val granularity: String,
    val buckets: List<StatsBucket> = emptyList(),
    val summary: StatsBucket? = null,
)

@Serializable
data class BatteryPoint(
    @Serializable(with = InstantSer::class) val date: Instant,
    val capacity_kwh: Double? = null,
    val end_soc: Int? = null,
)

@Serializable
data class BatteryHealth(
    val current_capacity_kwh: Double? = null,
    val max_capacity_kwh: Double? = null,
    /** 铭牌（出厂标称）容量 —— 健康度的分母。缺它就无法验算「85%」这个数。 */
    val nominal_capacity_kwh: Double? = null,
    val health_percent: Double? = null,
    val series: List<BatteryPoint> = emptyList(),
)

@Serializable
data class AppLatest(
    val version_code: String = "0",
    val version_name: String = "0.0.0",
    val apk_url: String = "",
    val sha256: String? = null,
    val release_notes: String? = null,
    val mandatory: Boolean = false,
)

@Serializable
data class ApiError(val error: String? = null)

class Api private constructor(private val base: String, private val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        fun create(url: String, token: String): Api? {
            val u = url.trim()
            if (!u.startsWith("http://") && !u.startsWith("https://")) return null
            if (token.isBlank()) return null
            return Api(u.trimEnd('/'), token.trim())
        }
    }

    private inline fun <reified T> get(path: String): T {
        val req = Request.Builder()
            .url(base + path)
            .header("Authorization", "Bearer $token")
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    // 服务端返回的业务错误（Go 那边全是中文）优先沿用；
                    // 只有服务端没给出可读文案时才按状态码兜底。
                    val serverMsg = runCatching { json.decodeFromString<ApiError>(body).error }
                        .getOrNull()?.takeIf { it.isNotBlank() }
                    throw UserFacingException(
                        when (resp.code) {
                            401 -> "访问令牌无效，请到「设置」页重新填写"
                            403 -> "没有访问权限"
                            404 -> serverMsg ?: "找不到该数据"
                            in 500..599 -> serverMsg ?: "服务器暂时不可用，请稍后再试"
                            else -> serverMsg ?: "请求失败（HTTP ${resp.code}）"
                        }
                    )
                }
                return json.decodeFromString(body)
            }
        } catch (e: UserFacingException) {
            throw e // 已经是中文文案，原样上抛
        } catch (e: UnknownHostException) {
            throw UserFacingException("找不到服务器，请检查「设置」里的地址是否正确")
        } catch (e: SocketTimeoutException) {
            throw UserFacingException("连接超时，请确认手机和服务器在同一个网络")
        } catch (e: ConnectException) {
            throw UserFacingException("连不上服务器，请确认服务已启动、手机与它在同一网络")
        } catch (e: IOException) {
            throw UserFacingException("网络异常，请稍后重试")
        }
    }

    fun cars(): List<Car> = get("/api/v1/cars")
    fun status(carId: Int): CarStatus = get("/api/v1/cars/$carId/status")

    /**
     * 行程列表。
     *
     * ⚠️ **limit 默认从 30 提到 200**（服务端 clampLimit 上限就是 200）：
     * 原来的 50 条窗口里 **一条高速行程都没有**（avg_speed ≥ 90 km/h = 0 条），
     * 于是「按车速分桶的实测能耗表」根本建不出来 —— 真实可达续航也就无从谈起。
     * 200 条窗口里有 6 条高速样本，够画第一版表。
     */
    fun drives(carId: Int, limit: Int = 200, offset: Int = 0): List<Drive> =
        get("/api/v1/cars/$carId/drives?limit=$limit&offset=$offset")
    fun track(driveId: Int): List<TrackPoint> = get("/api/v1/drives/$driveId/track")
    fun charges(carId: Int, limit: Int = 200): List<Charge> =
        get("/api/v1/cars/$carId/charges?limit=$limit")
    fun curve(chargeId: Int): List<CurvePoint> = get("/api/v1/charges/$chargeId/curve")
    fun stats(carId: Int, period: String): Stats =
        get("/api/v1/cars/$carId/stats?period=$period")
    fun battery(carId: Int): BatteryHealth = get("/api/v1/cars/$carId/battery")
    fun appLatest(): AppLatest = get("/api/v1/app/latest")
}
