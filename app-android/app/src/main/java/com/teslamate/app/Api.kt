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

/**
 * ⚠️ **v0.33.0（第 20 轮爬升能耗建模评审）：本车的爬升能耗系数。**
 *
 * 服务端用 `positions.power × Δt` 整趟积分拟合：
 * 毛耗电 `max(P,0)` 与回充 `max(−P,0)` **分开**拟合（两者物理上互斥），
 * 回归式 `Wh/km ~ 1 + 车速 + 坡度(m/km) + 时长(h)`。
 *
 * - [c_up_wh_per_m]：上坡每米多耗的电（Wh/m）。实测约 6.4，隐含传动效率约 78%
 *   （物理区间 75~90%）→ 可宣称「本车实测」。
 * - [c_regen_wh_per_m]：下坡每米回充抵扣（Wh/m）。实测约 3.2，隐含效率约 64%。
 * - [conservative_c_regen]：**回充系数不可信时**用它（分裂半下界 = 少抵扣回充 → 偏安全）。
 *
 * ⚠️ 端点首次调用约 **5 秒**（要扫 162 万个 positions），服务端缓存 30 分钟。
 */
@Serializable
data class ClimbModel(
    /** 上坡每米的额外耗电（Wh/m） */
    val c_up_wh_per_m: Double = 0.0,
    /** 下坡每米的回充抵扣（Wh/m） */
    val c_regen_wh_per_m: Double = 0.0,
    /** 上坡系数的隐含传动效率（m·g ÷ 系数） */
    val implied_eta_up: Double = 0.0,
    /** 回充系数的隐含效率（系数 ÷ m·g） */
    val implied_eta_regen: Double = 0.0,
    /** 参与拟合的行程数 */
    val n_drive: Int = 0,
    /** 「纯上坡」行程数 */
    val n_pure_up: Int = 0,
    /** 「纯下坡」行程数 */
    val n_pure_down: Int = 0,
    /** 毛耗电回归 R² */
    val r2_gross: Double = 0.0,
    /** 回充回归 R² */
    val r2_regen: Double = 0.0,
    /** 分裂半拟合的 c_up */
    val split_half_c_up: List<Double> = emptyList(),
    /** 分裂半拟合的 c_regen */
    val split_half_c_regen: List<Double> = emptyList(),
    /** 拟合用的最小行程里程（km） */
    val min_km: Double = 20.0,
    /** 上坡系数能否宣称「本车实测」 */
    val c_up_trustworthy: Boolean = false,
    /** 回充系数能否宣称「本车实测」 */
    val c_regen_trustworthy: Boolean = false,
    /** 回充系数的**保守取值**（不可信时 = 分裂半下界） */
    val conservative_c_regen: Double = 0.0,
    /** 方法说明 */
    val method: String = "",
    /** 警告说明 */
    val caveat: String = "",

    // ────────────────────────────────────────────────────────────
    // ⚠️ **v0.34.0（第 21 轮口径审计 P0-2 + 独立复算）：标定法口径的新系数。**
    //
    // 上面那套（c_up / c_regen）是**车轮功率毛耗电**口径，
    // 与能耗底档（标定法净值）**不同源** —— 混用会三重重复计量，
    // 实测高估 **+12%**、造成 **4 次结论翻转**。
    //
    // 现在**只用**下面这套（[basis] 必须是 "calib_net"）。
    // ────────────────────────────────────────────────────────────

    /** 口径标记：固定 "calib_net"。不是这个值就**不给**修正。 */
    val basis: String = "",
    /** 标定法口径的上坡系数（Wh/m，正数 = 上坡每米多耗） */
    val c_up_calib_net_wh_per_m: Double = 0.0,
    /**
     * 标定法口径的下坡系数（Wh/m）。
     *
     * ⚠️ **是负数**：标定法净值口径下「下降越多、净 Wh/km 越低」（回充已抵扣）。
     * 客户端按**带符号**使用：`修正 = c_up × Δg_up + c_regen × Δg_dn`。
     */
    val c_regen_calib_net_wh_per_m: Double = 0.0,
    /** 上坡系数的标准误 */
    val c_up_calib_net_se: Double = 0.0,
    /** 下坡系数的标准误 */
    val c_regen_calib_net_se: Double = 0.0,
    /** 上坡系数的 95% 置信区间 */
    val c_up_calib_net_ci95: List<Double> = emptyList(),
    /** 下坡系数的 95% 置信区间 */
    val c_regen_calib_net_ci95: List<Double> = emptyList(),
    /** 参与标定法拟合的行程数 */
    val n_calib: Int = 0,
    /** 标定法回归 R² */
    val r2_calib: Double = 0.0,
    /** 每个车速档的典型坡度（delta 基准） */
    val typical_grade: List<TypicalGrade> = emptyList(),
)

/**
 * ⚠️ **v0.34.0：某个车速档的典型坡度（delta 基准）。**
 *
 * 口径：该档**所有**可用趟次的 `ascent/km`、`descent/km` 的中位数。
 * 为什么必须要它：能耗底档是该档趟次的中位数，**已经包含**该档的典型爬升；
 * 用绝对坡度等于把典型值再叠一遍（实测高估 +12%）。
 */
@Serializable
data class TypicalGrade(
    val min_kph: Int = 0,
    /** 末档为 0（开区间） */
    val max_kph: Int = 0,
    val grade_up_typ_m_per_km: Double = 0.0,
    val grade_down_typ_m_per_km: Double = 0.0,
    val n: Int = 0,
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
    /**
     * ⚠️ **v0.29.0：服务端时区**（如 "Asia/Shanghai"）。
     *
     * 服务端按这个时区分日/月桶；App 拿到它之后**统一以它为准**
     * （记录页的按月分组、用量页的图表刻度、服务端的聚合口径全部一致）。
     */
    val server_timezone: String? = null,
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
    /**
     * ⚠️ **v0.31.0：归一后的地点标识**（形如 `g1` / `a24`）。
     *
     * 服务端按**真实距离**把 500 米内的地址聚成一个「地点」，
     * 取被引用最多的那个名字 —— 解决「永丰社区」与「新湖路」
     * 实为同一小区（相距 300 m）却被拆成两个名字的问题。
     *
     * 客户端靠这两个 key 相等与否判断「这一趟是不是**往返**」。
     */
    val start_place_key: String? = null,
    val end_place_key: String? = null,
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
    /**
     * ⚠️ v0.24.0：**真实 POI 名**（「金田服务区」这种）。
     *
     * `place` 是**反向地理编码的长串**
     * （「汕昆高速公路, 北庄, 宜州区, 河池市, 广西, 中国」），
     * 而库里还有 OSM 的原始 POI 名 —— 用户要找的是「哪个服务区」，不是一段地址。
     */
    val poi_name: String? = null,
    /**
     * ⚠️ **v0.36.0（第 21 轮一线车主复审 P1-2）：完整的人类可读标签。**
     *
     * 服务端算好的：POI 名（当它不是路名时）→「路名 + 地点」→ 地址第一段。
     * 界面**优先用这个** —— 「G210」在地图上根本搜不到，
     * 而「G210 车河镇」能。
     */
    val place_label: String? = null,
    /**
     * ⚠️ **v0.31.0：归一后的地点标识**（形如 `g1` / `a11`）。
     *
     * 客户端原来用「名字 + 坐标四舍五入到 2 位小数」当地点键，
     * 而 **300 米**的两个点会被**舍入边界**劈开（22.57 / 22.58）——
     * 于是同一个地方被算成两个。现在由服务端按真实距离聚类后下发。
     */
    val place_key: String? = null,
    /**
     * ⚠️ **v0.31.0（第 20 轮充电系统数据专家 P0-1）：本场「实测」从起始 SOC 充到 80% 的分钟数。**
     *
     * 来源是 `charges` 表的**逐点采样直读**，不是任何模型折算。
     * 口径起点 = 曲线**第一个采样点**的电量（见 [measured_from_soc]），
     * 终点 = 首个 ≥80% 的采样点，分钟数**截断**取整。
     *
     * 不可用时为 null，原因见 [measured_unavailable_reason]。
     */
    val measured_minutes_to_80: Int? = null,
    /** 实测口径的**起点** SOC（= 曲线首个采样点的电量）—— 必须与上面一起显示 */
    val measured_from_soc: Int? = null,
    /**
     * 实测不可用的原因：`start_soc_ge_80` / `never_reached_80` / `too_few_points` /
     * `soc_regressed` / `too_fast` / `too_long`。
     */
    val measured_unavailable_reason: String? = null,
    /** POI 类型（primary / secondary / fuel / cafe / services …） */
    val poi_type: String? = null,
    /**
     * ⚠️ v0.24.0：**是不是特斯拉超充** ——
     * 官方 App 的核心区分（超充 / 国标直流 / 交流），
     * 原来只有「直流 / 交流」两档，用户没法判断「这个桩是不是超充」。
     */
    val is_tesla_sc: Boolean = false,
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

/**
 * ⚠️ **v0.35.0（第 21 轮数据口径审计 P1-D + 独立复算）：电池容量衰减的判定。**
 *
 * ## 为什么不是一条直线
 *
 * 本机 17 个严格档满充点的实测结果：
 *
 * - 朴素线性：斜率 **−0.1375 度/1000km**，R² = 0.641；
 * - 线性 + 一次阶跃：R² = **0.829**（F = 15.41，显著），
 *   阶跃本身 **−0.5624 度**（95% 区间 [−0.870, −0.255]，t = −3.92）；
 * - 扣掉阶跃后的**连续速率**：**−0.0595 度/1000km**。
 *
 * 而**阶跃前后两段单独看都没有显著趋势** ——
 * 这正是「缓慢连续衰减 + 一次不连续」的指纹。
 *
 * 也就是说：把「样本 < 50 个就不给趋势」当闸门，等于用经验规则代替统计判据，
 * 而旧界面报的「每 1000 公里 −0.138 度」**高估了 2.3 倍**
 * —— 它把一次性的阶跃**抹成了连续趋势**。
 */
@Serializable
data class BatteryDecay(
    /** 参与回归的点数（严格档） */
    val n: Int = 0,
    val strict_n: Int = 0,
    /** 未计入的非满充点数 */
    val excluded_n: Int = 0,
    val span_km: Double = 0.0,
    val span_days: Int = 0,
    /** 朴素线性速率（度/1000km） */
    val naive_per_1000km: Double = 0.0,
    val naive_ci_lo: Double = 0.0,
    val naive_ci_hi: Double = 0.0,
    val naive_significant: Boolean = false,
    val r2_linear: Double = 0.0,
    /** 是否检测到一次性阶跃 */
    val has_step: Boolean = false,
    val step_km: Double = 0.0,
    val step_date: String = "",
    val step_kwh: Double = 0.0,
    val step_ci_lo: Double = 0.0,
    val step_ci_hi: Double = 0.0,
    val step_significant: Boolean = false,
    val r2_step: Double = 0.0,
    /** 朴素速率把连续速率高估了多少倍（1 = 没有高估） */
    val naive_overstate: Double = 1.0,
    /** 扣掉阶跃后的连续速率（度/1000km） */
    val underlying_per_1000km: Double = 0.0,
    val underlying_ci_lo: Double = 0.0,
    val underlying_ci_hi: Double = 0.0,
    val underlying_significant: Boolean = false,
    val pre_per_1000km: Double = 0.0,
    val pre_significant: Boolean = false,
    val pre_n: Int = 0,
    val post_per_1000km: Double = 0.0,
    val post_significant: Boolean = false,
    val post_n: Int = 0,
    /**
     * ⚠️ **v0.41.0：没实际充电的场次数**（同一场会话被 TeslaMate 拆成两段的尾巴）。
     * 那不是独立的测量点 —— 实测本机有 1 场（100%→100%、0.04 度、1 分钟）。
     */
    val dropped_empty: Int = 0,
    /**
     * ⚠️ **v0.41.0：气温系数（度/°C）** —— 模型 `y = a + b·km + c·T` 里的 c。
     *
     * 「续航估值 × 效率常数」这个口径受气温影响，所以气温是**混杂因素**。
     * 实测 +0.0090 度/°C —— 20°C 的温差就值 0.18 度，**与那个落差同量级**。
     */
    val temp_per_c: Double = 0.0,
    /** ⚠️ v0.41.0：气温能解释**残差方差**的多少（0~1）。实测只有 12% —— 不是「大部分」。 */
    val temp_r2: Double = 0.0,
    /** ⚠️ v0.41.0：参与气温模型的有效点数 */
    val temp_n: Int = 0,
    /**
     * ⚠️ **v0.41.0：加气温 + 落差后的连续速率**（度/1000km）。
     *
     * 这是「把气温这个混杂因素扣掉之后」的连续速率。
     * 实测 −0.054，区间 [−0.114, +0.006] ⇒ **检不出**。
     */
    val adjusted_per_1000km: Double = 0.0,
    val adjusted_ci_lo: Double = 0.0,
    val adjusted_ci_hi: Double = 0.0,
    val adjusted_significant: Boolean = false,
    /**
     * ⚠️ **v0.41.0：留一法** —— 去掉任意一个点重算时连续速率的取值范围。
     * 实测 [−0.316, −0.038]，且 16 次里只有 1 次仍然显著。
     */
    val leave_one_out_min: Double = 0.0,
    val leave_one_out_max: Double = 0.0,
    val leave_one_out_significant: Int = 0,
    val leave_one_out_total: Int = 0,
    /**
     * ⚠️ **v0.41.0：结论强度** —— `detectable` / `weak` / `not_detectable` / `insufficient`。
     *
     * 不再简单地「显著 / 不显著」两分：一个只差 0.001 就跨过 0 的区间，
     * 嘴上说「显著」是**过度承诺**。
     */
    val conclusion: String = "",
    val method: String = "",
    val caveat: String = "",
)

@Serializable
data class BatteryHealth(
    val current_capacity_kwh: Double? = null,
    val max_capacity_kwh: Double? = null,
    /** 铭牌（出厂标称）容量 —— 「占铭牌」的分母。缺它就无法验算这个数。 */
    val nominal_capacity_kwh: Double? = null,
    val health_percent: Double? = null,
    val series: List<BatteryPoint> = emptyList(),
    /**
     * ⚠️ **v0.35.0：电池容量衰减的判定**（服务端唯一事实源，客户端只显示）。
     *
     * 判定用**严格档**（充到 100%）+ **t 检验** + **阶跃检测**，
     * 而不是「样本数够不够」的经验闸门。
     */
    val decay: BatteryDecay? = null,
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

/**
 * ⚠️ v0.24.0：**真实逐公里 SOC 剖面**上的一个点。
 *
 * 原来线路余量剖面只有 2~6 个点，中间是**直线插值** ——
 * 山区长途（爬升 1500~2600 m）误差实测达 **13 公里**：
 * 真实跌破 20% 保底在第 244 公里，插值算出来是 231 公里。
 * 用户按 231 公里规划，到 244 公里时电已经见底 —— 会导致危险决策。
 */
@Serializable
data class SocProfilePoint(
    val km: Double = 0.0,
    val soc: Double = 0.0,
)

/**
 * ⚠️ **v0.26.0：整条路线的真实逐公里 SOC 剖面**（多趟行程拼起来）。
 *
 * 一次长途被充电切成很多趟（实测一次 3551 公里的出门切成 **118 段**），
 * 而 `/drives/{id}/soc_profile` 只能给**单趟**的剖面。
 * 以前只取最后一段 —— 剖面只覆盖整条路线的最后 1/118，图上看着对，
 * 实际上坐标轴是整条路线、曲线却只有最后一小段。
 */
@Serializable
data class RouteProfile(
    /** 整条路线的里程跨度（公里） */
    val total_km: Double = 0.0,
    val legs: List<RouteProfileLeg> = emptyList(),
    val points: List<RouteProfilePoint> = emptyList(),
)

/** 路线里的一段（一趟行程）在整条路线上的位置。 */
@Serializable
data class RouteProfileLeg(
    val drive_id: Int = 0,
    val start_km: Double = 0.0,
    val end_km: Double = 0.0,
    val distance_km: Double = 0.0,
    /** 相对整条路线起点的公里数 */
    val km_offset: Double = 0.0,
)

/** 整条路线上的一个 SOC 采样点（km 相对整条路线起点）。 */
@Serializable
data class RouteProfilePoint(
    val km: Double = 0.0,
    val soc: Double = 0.0,
)

/**
 * ⚠️ **v0.28.0（第 17 轮官方 App 对标评审 A-3）：常驻点。**
 *
 * 官方 Tesla App 只能告诉你「车现在在哪」；
 * 这个告诉你「它**平时**停在哪、停多久」——
 * 数据来自**相邻两趟行程的时间间隙**。
 *
 * ⚠️ 语义是「**推断停留**」不是「实时停车时长」：
 * 车停下来之后 TeslaMate 会停止记录位置（`states` 里 asleep 44 / offline 123），
 * 所以我们只能从「上一趟结束 → 下一趟开始」这个间隙推断它停在那儿。
 */
@Serializable
data class StayPoints(
    val points: List<StayPoint> = emptyList(),
    val note: String = "",
)

/** 一个常驻点。 */
@Serializable
data class StayPoint(
    val address_id: Int = 0,
    val name: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** 停过多少次 */
    val visits: Int = 0,
    /** 累计停留（小时） */
    val total_hours: Double = 0.0,
    val avg_hours: Double = 0.0,
    val max_hours: Double = 0.0,
    @Serializable(with = InstantSer::class) val first_seen: Instant? = null,
    @Serializable(with = InstantSer::class) val last_seen: Instant? = null,
)

/**
 * ⚠️ **v0.28.0（第 17 轮官方 App 对标评审 A-2）：能耗-车速曲线。**
 *
 * **官方 App 给不了的**：它只有**平均** Wh/km；
 * 而 TeslaMate 有**逐秒的车轮功率** → 能算出「这台车在每个速度下真实耗多少电」。
 *
 * ⚠️ **v0.29.0（复审 P1-1）：删掉硬编码的「85–100 km/h（124–147）」。**
 * 那个数来自评审员表里的**两档**（80–90 的 124.0 与 90–100 的 147.1），
 * 既不是「最佳」也不是「85–100」这个区间 —— **它根本不是数据**。
 *
 * 「最省电的速度」必须由服务端的 `best_band` 下发，
 * 而且要按**毛耗电**（不含回充抵扣）选：
 * 净值口径下「40–50 km/h = 118.8」看着最省电，
 * 但那是**一次下坡 + 一次长途**的产物 —— 这台 AWD Performance 物理上做不到。
 * 改成毛耗电后，最低档是 **100–110 km/h（184 Wh/km）**。
 */
@Serializable
data class EnergyProfile(
    val bands: List<EnergyBand> = emptyList(),
    val total_km: Double = 0.0,
    /**
     * ⚠️ **v0.30.0（第 19 轮数据口径审计 P0-2）：剖面实际覆盖的里程。**
     *
     * 分子（能量）只覆盖剖面分过箱的那部分里程，
     * 而 [total_km] 是全车 `sum(drives.distance)` —— 两者不同源。
     * 服务端的两个平均能耗比值现在都用**这个**做分母（分子分母同源）。
     */
    val covered_km: Double = 0.0,
    /** 全车平均能耗（**净值**，下坡回充会抵扣） */
    val overall_wh_per_km: Double = 0.0,
    /** ⚠️ v0.29.0：全车**毛耗电**（不含回充抵扣）—— 看「真实吃电」用这个 */
    val overall_wh_per_km_gross: Double = 0.0,
    val best_band: EnergyBand? = null,
    val note: String = "",
)

/** 一个速度档的能耗。 */
@Serializable
data class EnergyBand(
    val min_kph: Int = 0,
    val max_kph: Int = 0,
    /** **净值**能耗（Wh/km）—— 下坡回充会抵扣 */
    val wh_per_km: Double = 0.0,
    /**
     * ⚠️ **v0.29.0：毛耗电**（Wh/km，不含回充抵扣）。
     *
     * 为什么必须分开：净值口径下「40–50 km/h = 118.8 Wh/km」看着最省电，
     * 但那是**一次下坡 + 一次长途**的产物 —— 这台 AWD Performance 物理上做不到。
     * 评审复算：扣掉回充后那一档是 215.3，而最低档变成 **100–110 km/h**。
     */
    val wh_per_km_gross: Double = 0.0,
    /** 这一档跑了多少公里 */
    val km: Double = 0.0,
    /** 参与统计的 1 km 分箱数（样本量） */
    val bins: Int = 0,
) {
    /** 可读名，如「85–95」 */
    val label: String get() = if (max_kph <= 0) "$min_kph+" else "$min_kph–$max_kph"
}

/**
 * ⚠️ **v0.27.0（第 17 轮官方 App 对标评审 A-1）：驾驶行为画像。**
 *
 * **官方 Tesla App 没有这个**（只有第三方 TeslaFi / ABetterDrive 有），
 * 而 TeslaMate 的逐点 `speed` + `power` 正是官方拿不到的数据。
 *
 * 口径全部在服务端算（`positions` 的 162 万个采样点），
 * 客户端只负责**画**与**说清限制**。
 */
@Serializable
data class DrivingProfile(
    val drives: List<DriveScore> = emptyList(),
    val summary: DriveScoreSummary = DriveScoreSummary(),
    val params: DriveScoreParams = DriveScoreParams(),
)

/** 一趟行程的驾驶评分。 */
@Serializable
data class DriveScore(
    val drive_id: Int = 0,
    @Serializable(with = InstantSer::class) val start_date: Instant? = null,
    val distance_km: Double = 0.0,
    val duration_min: Int = 0,
    /** 综合驾驶评分（0~100） */
    val score: Double = 0.0,
    /** 硬刹车次数（2 秒窗口速度落差 ≤ −20 km/h） */
    val hard_brake: Int = 0,
    /** 硬加速次数（2 秒窗口速度落差 ≥ +20 km/h） */
    val hard_accel: Int = 0,
    val brake_per_100km: Double = 0.0,
    val accel_per_100km: Double = 0.0,
    /** 夜间（本地 22:00–06:00）行驶时间占比 */
    val night_pct: Double = 0.0,
    /** ≥120 km/h 的时间占比 */
    val over_speed_pct: Double = 0.0,
    /** 车轮功率积分出的能耗（**净值** Wh/km） */
    val wh_per_km: Double = 0.0,
    /**
     * ⚠️ **v0.29.0：毛耗电**（Wh/km，不含下坡回充抵扣）。
     *
     * 评分里的「能耗」项按它算 —— 净值会把下坡回充抵扣掉，
     * 于是下坡那两趟的 wh_per_km 只有 21.6 / 3.6，「能耗」项不扣分 → **下坡白送 +15 分**。
     */
    val wh_per_km_gross: Double = 0.0,
    val descent_m: Int = 0,
    val ascent_m: Int = 0,
)

/** 一批行程的汇总。 */
@Serializable
data class DriveScoreSummary(
    val drive_count: Int = 0,
    val total_km: Double = 0.0,
    val avg_score: Double = 0.0,
    val median_score: Double = 0.0,
    val hard_brake_total: Int = 0,
    val hard_accel_total: Int = 0,
    val night_pct_avg: Double = 0.0,
    val over_speed_pct_avg: Double = 0.0,
    /** 车轮功率积分的能耗中位（**净值** Wh/km） */
    val wh_per_km_median: Double = 0.0,
    /** ⚠️ v0.29.0：毛耗电中位（不含下坡回充抵扣）—— 界面展示用这个 */
    val wh_per_km_median_gross: Double = 0.0,
)

/**
 * ⚠️ 评分用到的**全部阈值**。
 *
 * 这些值是**行业惯例**，不是从这辆车的数据拟合出来的 ——
 * 所以必须随结果一起下发，界面上要**说清它们是拍的**。
 */
@Serializable
data class DriveScoreParams(
    /** 判定硬加/硬刹的 2 秒窗口速度落差阈值（km/h） */
    val hard_event_threshold_kmh_per_2s: Int = 20,
    /** 判定「超速」的阈值（km/h） */
    val over_speed_kph: Int = 120,
    val night_start_hour: Int = 22,
    val night_end_hour: Int = 6,
    /** 只统计 ≥ 这个距离的行程（km） */
    val min_distance_km: Int = 5,
    val note: String = "",
)

@Serializable
data class CostSummary(
    /** 公共桩（直流）网侧度数 —— 桩是按它送出去的度数收费的 */
    val dc_used_kwh: Double = 0.0,
    /** 家充（交流）网侧度数 */
    val ac_used_kwh: Double = 0.0,
    val dc_sessions: Int = 0,
    val ac_sessions: Int = 0,
    /** 公共桩总时长（分钟）—— 按分钟计服务费时用它 */
    val dc_minutes: Double = 0.0,
    val ac_minutes: Double = 0.0,
    // ⚠️ **v0.30.0（第 19 轮财务评审 P0-3）：围栏命中的「真家充」。**
    //
    // 这是 [ac_used_kwh] 的**子集**（交流 ∧ 有 geofence_id 归属），
    // 「没有围栏归属的交流度数」= ac_used_kwh − home_used_kwh。
    //
    // ⚠️ 本机历史 78 场充电的 geofence_id 全是 NULL（「家」围栏 2026-10-25 才建），
    // 所以这个数现在是 0 —— **这是事实**，不是数据缺失。
    val home_used_kwh: Double = 0.0,
    val home_sessions: Int = 0,
    val home_minutes: Double = 0.0,
    /**
     * ⚠️ **v0.31.0（第 20 轮费用复审 P0-1）：行驶里程的两个分母。**
     *
     * - [km_drives] = `sum(drives.distance)`（App 记录到的行驶里程）
     * - [km_odometer] = 里程表在窗口内的跨度（**包含遥测断档里车真开过的公里**）
     *
     * 实测两者差 1459.62 公里（16.71%），且断档全在 2026-10-01 之前 ——
     * 所以短窗口几乎相等，长窗口差很多。
     * 「每公里成本」据此显示**区间**而不是点值。
     */
    val km_drives: Double = 0.0,
    val km_odometer: Double = 0.0,
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

    /**
     * 服务器地址（对外可见）。
     *
     * ⚠️ v0.24.0：`RecordsStore` 用它做「已经拉过哪台车的数据」的键 ——
     * 换服务器或换车时必须重拉，不能拿旧数据冒充。
     */
    val baseUrl: String get() = base

    // ⚠️ v0.24.0（性能与稳定性评审 P1-3）：**OkHttpClient 单例化**。
    //
    // 原来每个 `Api` 实例自带一个 `OkHttpClient`（独立 ConnectionPool + Dispatcher），
    // 而 `Api.create()` 被调了 8 处（5 处 `remember(settings) { settings.api() }`
    // + AppRoot + RecentSummaryCard + SettingsScreen）。
    //
    // 后果有两个：
    // ① **连接池不共享** —— keep-alive 失效，每次请求多一次 TCP + TLS 往返
    //    （真机走 Tailscale / 移动网络时是实打实的 1–2 RTT）；
    // ② `remember(settings)` 的 key 是**整个 AppSettings** ——
    //    改任何一个设置项就换掉全部 5 个 client。
    //
    // `OkHttpClient` 本身线程安全、可复用，所以改成**全局共享一个**。
    // 超时设置保持不变。
    private val client: OkHttpClient = sharedClient

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        /** 全局共享的 HTTP 客户端（连接池 / 线程池都共享）。 */
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }

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

    /**
     * ⚠️ v0.24.0：一趟行程的**真实逐公里 SOC 剖面**。
     *
     * 独立端点而不是随 /drives 返回：全表算 11 趟长途要 350 ms
     * （positions 只有 BRIN 索引，一次查全部长途会退化成全表扫描），
     * 会让记录页首屏多等 280 ms；单趟查只要 17 ms —— 按需拉才对。
     */
    fun socProfile(driveId: Int, stepKm: Int = 5): List<SocProfilePoint> =
        get("/api/v1/drives/$driveId/soc_profile?step_km=$stepKm")

    /**
     * ⚠️ **v0.26.0：整条路线（多趟）的真实逐公里 SOC 剖面。**
     *
     * 服务端一次查完（`positions` 上有 `BRIN (drive_id, date)` 索引）：
     * 实测 12 段 **32~80 ms**、200 段 **321~377 ms**，无全表扫描。
     *
     * 里程轴是**整条路线**的（相对第一段的起点），所以拼出来的图
     * 和「站间距离 / 到下一站剩多少电」用的是**同一把尺子**。
     */
    fun routeProfile(carId: Int, driveIds: List<Int>, stepKm: Int = 5): RouteProfile =
        get(
            "/api/v1/cars/$carId/route_profile" +
                "?drive_ids=${driveIds.joinToString(",")}&step_km=$stepKm"
        )

    /**
     * ⚠️ **v0.27.0：驾驶行为画像**（官方 Tesla App 没有的能力）。
     *
     * 服务端从 `positions` 的 162 万个采样点算出：
     * 急刹车 / 急加速事件、夜间行驶占比、超速时间占比、车轮功率积分能耗。
     *
     * 阈值（2 秒窗口 20 km/h、120 km/h、夜间 22:00–06:00）是**行业惯例**，
     * 随响应一起下发 —— 界面必须说清它们是拍的，不是拟合出来的。
     *
     * @param minKm 只统计 ≥ 这个里程的行程。
     *   ⚠️ **默认 20 公里** —— 实测近 30 趟里有 16 趟不到 20 公里（占里程 15%），
     *   而低分那几趟全是 6~9 公里的**挪车**（商场/充电站内），
     *   一次急刹就算「42 次/百公里」直接扣满 30 分 ——
     *   主角分数会被「你在停车场挪车」主导。
     */
    fun drivingProfile(carId: Int, limit: Int = 20, minKm: Int = 20): DrivingProfile =
        get("/api/v1/cars/$carId/driving_profile?limit=$limit&min_km=$minKm")

    /**
     * ⚠️ **v0.28.0：常驻点**（官方 App 只能告诉你「车现在在哪」）。
     *
     * ⚠️ 语义是「**推断停留**」：车停下来之后 TeslaMate 停止记录位置，
     * 所以停留时长来自**相邻两趟行程的时间间隙**，不是实时停车时长。
     */
    fun stayPoints(carId: Int, minHours: Double = 3.0): StayPoints =
        get("/api/v1/cars/$carId/stay_points?min_hours=$minHours")

    /** ⚠️ **v0.28.0：能耗-车速曲线**（车轮功率积分；官方只有平均 Wh/km）。 */
    fun energyProfile(carId: Int): EnergyProfile =
        get("/api/v1/cars/$carId/energy_profile")

    /**
     * ⚠️ **v0.33.0：本车的爬升能耗系数**（上坡 Wh/m、回充 Wh/m）。
     *
     * ⚠️ 首次调用约 **5 秒**（服务端要扫 162 万个 positions），
     * 所以服务端缓存 30 分钟 —— 客户端**不要**在每次刷新时都调它。
     */
    fun climbModel(carId: Int, minKm: Double = 20.0): ClimbModel =
        get("/api/v1/cars/$carId/climb_model?min_km=$minKm")

    /**
     * ⚠️ v0.25.0：一个时间窗口内的**充电成本构成**（度数 / 场次 / 时长）。
     *
     * 单独一个端点而不是客户端自己算：
     * 用量页的「7 天 / 30 天 / 1 年」是**服务端时区**的窗口，
     * 而 `charges()` 是「最近 N 场」—— 范围不同，混用会算错。
     */
    fun costSummary(carId: Int, period: String): CostSummary =
        get("/api/v1/cars/$carId/cost_summary?period=$period")
}
