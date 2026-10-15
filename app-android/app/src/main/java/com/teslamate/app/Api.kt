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
import java.time.Instant
import java.util.concurrent.TimeUnit

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
    val power_max_kw: Int? = null,
    val power_min_kw: Int? = null,
    val start_rated_range_km: Double? = null,
    val end_rated_range_km: Double? = null,
    val start_ideal_range_km: Double? = null,
    val end_ideal_range_km: Double? = null,
    val start_geofence: String? = null,
    val end_geofence: String? = null,
    val has_supercharger: Boolean = false,
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
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { json.decodeFromString<ApiError>(body).error }
                    .getOrNull() ?: "HTTP ${resp.code}"
                throw IOException(msg)
            }
            return json.decodeFromString(body)
        }
    }

    fun cars(): List<Car> = get("/api/v1/cars")
    fun status(carId: Int): CarStatus = get("/api/v1/cars/$carId/status")
    fun drives(carId: Int, limit: Int = 30): List<Drive> =
        get("/api/v1/cars/$carId/drives?limit=$limit")
    fun track(driveId: Int): List<TrackPoint> = get("/api/v1/drives/$driveId/track")
    fun charges(carId: Int, limit: Int = 50): List<Charge> =
        get("/api/v1/cars/$carId/charges?limit=$limit")
    fun curve(chargeId: Int): List<CurvePoint> = get("/api/v1/charges/$chargeId/curve")
    fun stats(carId: Int, period: String): Stats =
        get("/api/v1/cars/$carId/stats?period=$period")
    fun battery(carId: Int): BatteryHealth = get("/api/v1/cars/$carId/battery")
    fun appLatest(): AppLatest = get("/api/v1/app/latest")
}
