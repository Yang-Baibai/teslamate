package com.teslamate.app

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
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
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

/**
 * 错误分类 —— 让界面能做出**不同的反应**，而不只是显示一句话。
 *
 * ⚠️ v0.9.0（可靠性复核，值班工程师视角）：
 * 原来所有错误都是一个 `UserFacingException(message)`，
 * 界面只能把 message 显示出来。于是：
 *   - 令牌失效 → 文案说「请到「设置」页重新填写」，但「设置」在第 4 个 Tab，
 *     横幅上**没有任何可点的东西** —— 说了等于没说。
 *   - 服务器慢 / 连不上 → 一律说「连接超时，请确认手机和服务器在同一个网络」，
 *     而自用场景最常见的原因是「家里服务器关机了」—— 方向完全反了。
 *
 * 分类之后，界面能：
 *   - AUTH   → 给一个「前往设置」按钮（横幅本身就是出口）
 *   - NETWORK→ 给「重试」按钮
 *   - NO_DATA→ 走空状态引导（这是**正常状态**，不是错误）
 */
enum class ApiErrorKind {
    /** 令牌无效 / 权限不足 —— 界面必须给一个「去设置」的出口 */
    AUTH,

    /** 连不上服务器（DNS 失败、连接被拒、连接超时） */
    NETWORK,

    /** 服务器连上了，但响应超时或报 5xx */
    SERVER,

    /** 返回的数据解不开 —— 多半是服务端版本旧了 */
    PARSE,

    /** 服务端明确说「这台车还没数据」—— 这是**正常状态**，不是故障 */
    NO_DATA,

    OTHER,
}

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
 * 页面侧统一用 `it.message` 展示即可，不需要再做二次翻译；
 * 需要**不同反应**时看 [kind]。
 */
class UserFacingException(
    message: String,
    val kind: ApiErrorKind = ApiErrorKind.OTHER,
) : IOException(message)

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
    /**
     * ⚠️ v0.21.0：**车端里程表**（km，来源 Tesla Fleet API 的 `vehicle_state.odometer`）。
     *
     * 与 [odometer]（TeslaMate 按 GPS 点累计）不同：
     * 车端那个数是**车辆自己**记的，保养基准应该对齐它 ——
     * 基准是车主的知识（他记的是仪表盘上的数），
     * 拿一个偏了几百公里的数去套，告警会偏同样多公里。
     */
    val odometer_vehicle: Double? = null,
    /**
     * ⚠️ v0.21.0：**服务端是否配置了 Fleet API 凭据**。
     *
     * false = 这台服务器**没有实时状态源** → 锁车与实时胎压恒为「未知」。
     * 这与「这会儿读不到」是两件事：
     * 前者是「没装这个功能」，后者是「暂时读不到」。
     * （原来 App 一律说「没读到」，车主会以为是车机或网络故障。）
     */
    val fleet_enabled: Boolean? = null,
    /**
     * ⚠️ v0.22.0：**Fleet 实时状态**（disabled / asleep / unauthorized / unreachable / ok）。
     *
     * 「没值」其实是四种完全不同的情况：
     * `disabled` 没配凭据（永远不会变）· `asleep` 车在休眠（正常）·
     * `unauthorized` 授权失效（**不会自愈**，要人工重新授权）·
     * `unreachable` 云端故障（会自愈）。
     */
    val fleet_state: String? = null,
    /**
     * **实时胎压**（bar，来源 Tesla Fleet API 的 `vehicle_state`）。
     *
     * ⚠️ 与上面的 [tpms_fl] 等是**两个来源，不要互相覆盖**：
     * - [tpms_fl] 等 = **TeslaMate 的稀疏采样**：车开动/充电时才记，数小时才有一条，还可能是几小时前的旧值
     * - [tpms_live_fl] 等 = **Fleet API 的实时值**：车醒着时每 2 分钟一条，明显更鲜
     *
     * **优先级：有 live 就用 live，没有才回退 [tpms_fl] 等。**
     *
     * - `null` = 没读到（未配置 Fleet API 凭据 / 车休眠 / 还没拉到）
     *
     * ⚠️ null 不能当 0 —— 0 bar 是不可能值。
     */
    val tpms_live_fl: Double? = null,
    val tpms_live_fr: Double? = null,
    val tpms_live_rl: Double? = null,
    val tpms_live_rr: Double? = null,
    /** 实时胎压这批数据的采集时刻（用于判断新鲜度；null = 从来没拿到过数据） */
    @Serializable(with = InstantSer::class) val tpms_live_at: Instant? = null,
    /**
     * 实时胎压的告警判定（服务端算好，App 不自己算阈值）。
     *
     * - `true` = 有异常（Tesla 硬告警，或超出正常范围）
     * - `false` = 已知正常
     * - **null = 未知**（没读到实时数据）
     *
     * ⚠️ Kotlin 里用 `== true` 判断，别写成 `if (tpms_live_alert)`。
     */
    val tpms_live_alert: Boolean? = null,
    /** 异常轮次，如 `["左后"]` */
    val tpms_live_alert_wheels: List<String> = emptyList(),
    /** 异常轮次读数描述，如 `"左后 2.80 bar"` */
    val tpms_live_alert_detail: String = "",
    /** 接近阈值的轮次（Tesla 的软告警），如 `["右前"]` */
    val tpms_live_warn_wheels: List<String> = emptyList(),
    val data_age_hours: Int = 0,
    val data_stale: Boolean = false,
    /**
     * 数据已超过「注意」阈值但还没过期（服务端 6 小时）。
     *
     * ⚠️ v0.9.0：原来 App 自己写 6 小时 / 24 小时两档阈值，
     * 而服务端 `DataStaleHours=24` 才是唯一事实源 —— 两边各写一份，改一边就打架。
     * 现在两档都在服务端算（`DataWarnHours` / `DataStaleHours`），App 只读结果。
     */
    val data_warn: Boolean = false,
    /**
     * Tesla Fleet API 后台轮询失败的原因（空 = 正常）。
     *
     * ⚠️ v0.9.0：**失败时服务端保留上次已知的锁车状态**，
     * 不会把 locked 清成 null —— 「锁没锁」是车主第一诉求，
     * 把它降级成「未知」比显示一个带时间戳的旧值伤害大得多。
     * 这个字段告诉 App「为什么现在读不到 / 显示的是旧数据」。
     */
    val fleet_error: String = "",
    /** Fleet API 最近一次成功拉取的时刻（null = 从来没成功过） */
    @Serializable(with = InstantSer::class) val fleet_last_ok: Instant? = null,
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
    /**
     * ⚠️ v0.21.0：**采样均值**平均功率（按采样点数算术平均）。
     *
     * 采样间隔不均匀（高功率段 5 秒一个点、掉功率段几十秒一个点），
     * 所以它**系统性高估**（实测 18%~50%）。保留只是为了兼容旧服务端，
     * 新代码请用 [avg_power_kw_true]。
     */
    val avg_power_kw: Int? = null,
    /**
     * ⚠️ v0.21.0：**时间加权**平均功率 —— 真正的「这个桩平均能跑多少 kW」。
     *
     * 它是「从 X% 充到 80% 约 N 分钟」的唯一来源，
     * 采样均值高估 45% 就意味着**等待时间低估 31%**。
     */
    val avg_power_kw_true: Int? = null,
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
    /**
     * ⚠️ v0.21.0：**服务端分日用的时区**（如 "Asia/Shanghai"）。
     *
     * 服务端按这个时区分桶，App 的时间戳却按**手机时区**显示 ——
     * 对非该时区的用户，同一件事在时间轴与图表里会落到不同的日子。
     * 有了它，App 才能把话说准（「按北京时间分日」）。
     */
    val server_timezone: String? = null,
)

@Serializable
data class BatteryPoint(
    @Serializable(with = InstantSer::class) val date: Instant,
    val capacity_kwh: Double? = null,
    val end_soc: Int? = null,
    /**
     * ⚠️ v0.22.0：这个点用的是**哪个 kWh/km 常数**。
     *
     * 它是「可用容量」这个数的来源，也是「健康度」会漂移的原因
     * —— 车主为修 Wh/km 显示改一次配置，容量就变一次。
     * 随点下发至少**可核对**。
     */
    val efficiency_used: Double? = null,
    /** 该次测量时的里程表（km） */
    val odometer_km: Double? = null,
    /** 能量反推口径的容量（充入 kWh ÷ ΔSOC% × 100，不需要 kWh/km 常数） */
    val capacity_kwh_energy: Double? = null,
)

@Serializable
data class BatteryHealth(
    val current_capacity_kwh: Double? = null,
    val max_capacity_kwh: Double? = null,
    /** 铭牌（出厂标称）容量 —— 「占铭牌」的分母。缺它就无法验算这个数。 */
    val nominal_capacity_kwh: Double? = null,
    val health_percent: Double? = null,
    val series: List<BatteryPoint> = emptyList(),
    // ⚠️ v0.22.0（电池健康专家 P0-1 / P0-2 / P0-3 / P1-1 / P1-5 / P1-6）
    /**
     * **相对首测**（百分比）—— 唯一**不受 `cars.efficiency` 影响**的健康指标。
     *
     * 分子分母同比例缩放，所以车主改那个常数，这个数一点都不变。
     * 它回答的才是「电池比刚开始测的时候差了多少」。
     */
    val health_percent_of_baseline: Double? = null,
    /** 首测容量（服务端取前 3 次的中位数） */
    val baseline_capacity_kwh: Double? = null,
    /** 严格档上限：只取**充到 100%** 的测量点 */
    val max_capacity_kwh_strict: Double? = null,
    /** 能量反推口径的容量中位数（与主口径互相印证） */
    val capacity_kwh_energy: Double? = null,
    /** 序列覆盖的天数 —— 不说清楚，车主会把那条线当成「电池的全部历史」 */
    val series_span_days: Int? = null,
    /** 序列覆盖的里程 */
    val odometer_span_km: Double? = null,
)

/**
 * ⚠️ v0.22.0：服务器健康详情（`GET /api/v1/health`，**需鉴权**）。
 *
 * 自托管服务的用户体验，八成体现在**它坏的时候** ——
 * 而「我的服务器现在是什么状态」在 App 里原本**无处可查**。
 */
@Serializable
data class HealthDetail(
    val db_ok: Boolean = false,
    val db_latency_ms: Long = 0,
    val server_timezone: String? = null,
    val server_time: String? = null,
    val fleet: FleetHealth = FleetHealth(),
    val teslamate: TeslamateHealth = TeslamateHealth(),
)

@Serializable
data class FleetHealth(
    val enabled: Boolean = false,
    /** disabled / asleep / unauthorized / unreachable / ok */
    val state: String? = null,
    val last_ok: String? = null,
    val error: String? = null,
)

@Serializable
data class TeslamateHealth(
    val last_position_at: String? = null,
    val age_hours: Double? = null,
)

@Serializable
data class AppLatest(    val version_code: String = "0",
    val version_name: String = "0.0.0",
    val apk_url: String = "",
    val sha256: String? = null,
    val release_notes: String? = null,
    val mandatory: Boolean = false,
)

@Serializable
data class ApiError(
    val error: String? = null,
    /**
     * 机器可读错误码（v0.9.0 新增）。
     *
     * 目前只有两个值：
     * - `no_data`：这台车还没有任何数据 —— **正常状态**，不是故障
     * - `no_car` ：根本没有这台车 —— 配置错了
     *
     * ⚠️ 不要退回「中文文案字符串匹配」那种做法：改一个字就失效，
     * 而且对服务端换成英文就完全失效。
     */
    val error_code: String? = null,
)

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
                    val parsed = runCatching { json.decodeFromString<ApiError>(body) }.getOrNull()
                    val serverMsg = parsed?.error?.takeIf { it.isNotBlank() }
                    val errCode = parsed?.error_code
                    // ⚠️ v0.9.0：错误**分级**。
                    //   401/403 → 令牌问题（界面要给出「去设置」的出口）
                    //   404 + no_data → 这台车还没数据（**正常状态**，走空状态引导）
                    //   5xx → 服务端问题（可重试）
                    // ⚠️ v0.22.0（自托管运维评审 P0-4 + P1-3）：
                    // ① `no_car` 也要给「前往设置」——
                    //    服务端对「这台车在库里不存在」返回 404 + error_code=no_car，
                    //    但客户端只认 `no_data`，于是落到 OTHER → 给的是**「重试」**。
                    //    而重试永远不会成功（车号不对，重试一万次也没用）——
                    //    用户按提示去设置页，按钮却不给他路。
                    // ② 5xx **不用**服务端的原始错误 ——
                    //    `writeErr(w, 500, err.Error())` 会把
                    //    `ERROR: relation "…" does not exist (SQLSTATE 42P01)`
                    //    这种数据库内部信息原样回给客户端。
                    val kind = when {
                        resp.code == 401 || resp.code == 403 -> ApiErrorKind.AUTH
                        resp.code == 404 && errCode == "no_car" -> ApiErrorKind.AUTH
                        resp.code == 404 && errCode == "no_data" -> ApiErrorKind.NO_DATA
                        resp.code in 500..599 -> ApiErrorKind.SERVER
                        else -> ApiErrorKind.OTHER
                    }
                    throw UserFacingException(
                        when (resp.code) {
                            401 -> "访问令牌无效，请到「设置」页重新填写"
                            403 -> "没有访问权限，请到「设置」页检查令牌"
                            404 -> when (errCode) {
                                "no_car" ->
                                    "这台车在服务器上不存在，请到「设置」页重新选择车辆"
                                else -> serverMsg ?: "找不到该数据"
                            }
                            // ⚠️ v0.22.0：5xx 一律用本地文案，不回显数据库错误。
                            in 500..599 -> "服务器内部错误，请稍后再试"
                            else -> serverMsg ?: "请求失败（HTTP ${resp.code}）"
                        },
                        kind,
                    )
                }
                return json.decodeFromString(body)
            }
        } catch (e: UserFacingException) {
            throw e // 已经是中文文案，原样上抛
        } catch (e: UnknownHostException) {
            // ⚠️ v0.22.0（自托管运维评审 P1-3）：**别把 Tailscale 掉线说成「地址写错了」**。
            //
            // 原来写「请检查「设置」里的地址是否正确」——
            // 但本部署走 Tailscale（ADR #9），地址**从没变过**，
            // 失败最常见的原因是 **Tailscale 没连上**。
            // 照着原来的文案，用户会把**好地址改坏**。
            throw UserFacingException(
                "连不上服务器（地址解析失败）。若服务器地址没改过，多半是 Tailscale 没连上。",
                ApiErrorKind.NETWORK,
            )
        } catch (e: ConnectException) {
            // ⚠️ v0.22.0：**删掉「手机与它在同一网络」** ——
            // 本部署走 Tailscale，**从不需要同一网络**，这句是纯误导。
            throw UserFacingException(
                "连不上服务器。请确认家里那台电脑开着、服务器在跑，" +
                    "以及手机上的 Tailscale 已连接。",
                ApiErrorKind.NETWORK,
            )
        } catch (e: SocketTimeoutException) {
            // ⚠️ v0.9.0：连接超时与读取超时是**两件事**，文案必须分开。
            //   连接超时 = 根本没连上（服务器没起 / 网络不通 / 手机在移动数据下）
            //   读取超时 = 连上了但服务器算得太慢（status 查询要扫 500 条 positions）
            // 原来一律说「连接超时，请确认手机和服务器在同一个网络」——
            // 服务器慢也被说成网络问题，**方向完全反了**。
            //
            // ⚠️ v0.22.0：OkHttp 的连接超时抛出的 message 是「timeout」，
            // **不含 "connect"** —— 原来那个字符串判定根本匹配不上，
            // 「根本没连上」被说成「服务器响应太慢」。
            // 现在**只要是 SocketTimeoutException** 就归到「连不上」那一侧，
            // 读取超时由 OkHttp 的另一类异常（SocketTimeoutException 带 "read"）区分 ——
            // 判据改用 message 里是否含 "read"（OkHttp 的 read timeout message 是 "timeout"，
            // 但 socketTimeoutStartCall 的 read 超时会带 "read"）。
            val isRead = e.message?.contains("read", ignoreCase = true) == true
            throw UserFacingException(
                if (isRead) "服务器响应太慢，请稍后重试"
                else "连不上服务器。请确认家里那台电脑开着、服务器在跑，" +
                    "以及手机上的 Tailscale 已连接。",
                ApiErrorKind.NETWORK,
            )
        } catch (e: SerializationException) {
            // ⚠️ v0.9.0：SerializationException **不是** IOException，
            // 原来会**逃出全部 catch**，用户直接看到 Java 原文异常 ——
            // 这违反了本文件顶部立下的「所有异常都是可直接展示的中文」契约。
            throw UserFacingException(
                "服务器返回的数据格式不对，请确认服务端是最新的版本",
                ApiErrorKind.PARSE,
            )
        } catch (e: DateTimeParseException) {
            // 时间解析同理（InstantSer 里 Instant.parse 抛的）。
            throw UserFacingException(
                "服务器返回的时间格式不对，请确认服务端是最新的版本",
                ApiErrorKind.PARSE,
            )
        } catch (e: IOException) {
            throw UserFacingException("网络异常，请稍后重试", ApiErrorKind.NETWORK)
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

    /**
     * ⚠️ v0.22.0：服务器健康详情（给 App 的「服务器状态」页用）。
     *
     * 与 `/healthz` 的区别：`/healthz` **免鉴权**且对外映射，只回最小 ok/503；
     * 这个端点**带鉴权**，可以返回 Fleet 状态、最后采集时间、数据库延迟。
     */
    fun health(): HealthDetail = get("/api/v1/health")
}
