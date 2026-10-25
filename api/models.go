package main

import "time"

// ---------- API 数据模型 ----------
// 时间字段统一为 UTC（数据库即 UTC 存储），App 端负责转本地时区显示。

type Car struct {
	ID          int      `json:"id"`
	Name        string   `json:"name"`
	Model       string   `json:"model"`
	TrimBadging *string  `json:"trim_badging"`
	VIN         *string  `json:"vin"`
	Marketing   *string  `json:"marketing_name"`
	Efficiency  *float64 `json:"efficiency"` // kWh/km
	Exterior    *string  `json:"exterior_color"`
	WheelType   *string  `json:"wheel_type"`
}

type CarStatus struct {
	CarID        int        `json:"car_id"`
	UpdatedAt    time.Time  `json:"updated_at"`
	State        *string    `json:"state"` // online / asleep / offline
	StateSince   *time.Time `json:"state_since"`
	BatteryLevel *int       `json:"battery_level"`
	UsableLevel  *int       `json:"usable_battery_level"`
	IdealRangeKm *float64   `json:"ideal_range_km"`
	EstRangeKm   *float64   `json:"est_range_km"`
	RatedRangeKm *float64   `json:"rated_range_km"`
	Odometer     *float64   `json:"odometer"`
	Speed        *int       `json:"speed"`
	Power        *int       `json:"power"`
	OutsideTemp  *float64   `json:"outside_temp"`
	InsideTemp   *float64   `json:"inside_temp"`
	IsClimateOn  *bool      `json:"is_climate_on"`
	Elevation    *int       `json:"elevation"`
	Lat          *float64   `json:"lat"`
	Lng          *float64   `json:"lng"`
	MapLat       *float64   `json:"map_lat"` // GCJ-02（配高德瓦片）
	MapLng       *float64   `json:"map_lng"`
	LocationName *string    `json:"location_name"`
	// v0.5.0 新增：四轮胎压（bar）
	TpmsFL *float64 `json:"tpms_fl"`
	TpmsFR *float64 `json:"tpms_fr"`
	TpmsRL *float64 `json:"tpms_rl"`
	TpmsRR *float64 `json:"tpms_rr"`

	// v0.8.4 新增：锁车状态（来源 Tesla Fleet API，后台轮询后缓存）
	// 三态：true=已上锁 / false=未上锁 / null=未知（没配凭据或拉取失败）。
	// 注意：不能用 omitempty——nil 必须原样输出成 null，否则 App 分不清
	// 「没锁」和「不知道」。
	Locked   *bool      `json:"locked"`
	LockedAt *time.Time `json:"locked_at"` // 本次状态采集时刻（App 用它判断新鲜度）

	// ⚠️ **v0.29.0：服务端时区**（如 "Asia/Shanghai"）。
	//
	// 服务端按这个时区分日/月桶，而 App 的时间戳按**手机时区**显示 ——
	// 跨时区时同一件事在时间轴属于 10-06、在图表里却落到 10-07，
	// 「跨页跨日错位」，用户对不上账。
	//
	// 现在 App 拿到它之后**统一以服务端时区为准**
	// （项目自己的方向：见 `Fmt.kt` 里那段「已知未修」的说明）。
	ServerTimezone string `json:"server_timezone"`

	// ⚠️ v0.21.0：**车端里程表**（km），来源 Fleet API 的 vehicle_state.odometer。
	//
	// 与 `odometer`（TeslaMate 按 GPS 点累计）的区别：
	// 车端里程表是**车辆自己**记的数，保养基准应该对齐它 ——
	// 基准是车主的知识（他记的是仪表盘上的数），
	// 拿一个偏了几百公里的数去套，告警会偏同样多公里。
	//
	// nil = 没读到（没配 Fleet 凭据 / 车在休眠），**不是 0**。
	OdometerVehicle *float64 `json:"odometer_vehicle"`

	// ⚠️ v0.21.0：**是否配置了 Fleet API 凭据**。
	//
	// false = 这台服务器**没有实时状态源** → 锁车与实时胎压恒为「未知」。
	// 这与「暂时读不到」是**两件事**，App 必须分开说：
	// 前者是「没装这个功能」，后者是「这会儿读不到」。
	// （原来 App 一律说「没读到」，车主会以为是车机或网络故障。）
	FleetEnabled *bool `json:"fleet_enabled"`

	// ⚠️ v0.22.0：**Fleet 实时状态**（disabled / asleep / unauthorized / unreachable / ok）。
	//
	// 「没值」其实是四种完全不同的情况，不分开就会误导：
	//   disabled     这台服务器**没有实时状态源**（没配凭据）—— 永远不会变
	//   asleep       车在休眠 —— **正常状态**，等它醒来就更新
	//   unauthorized 授权失效 —— 一次性 token 过期，**不会自愈**，要人工重新授权
	//   unreachable  云端故障 —— 会自愈（退避重试中）
	//
	// 不分开的后果：「授权失效」被说成「稍后自动重试」——
	// 而一次性 token **永远重试也不会成功**，锁车与实时胎压会静默停更。
	FleetState string `json:"fleet_state"`

	// v0.8.5 新增：实时胎压（来源 Tesla Fleet API 的 vehicle_state，后台轮询后缓存）
	//
	// ⚠️ 与上面的 tpms_fl/fr/rl/rr 是**两个来源，不要互相覆盖**：
	//   - tpms_fl…   = TeslaMate 的稀疏采样（车开动/充电时才记，数小时才有一条）
	//   - tpms_live… = Fleet API 的实时值（车醒着时每 2 分钟一条，明显更鲜）
	// App 端：live 有值就用 live，没有才回退 tpms_fl…。
	//
	// 同样不能用 omitempty：nil 必须原样输出成 null，
	// 否则 App 分不清「没读到」和「读到 0」（0 bar 是不可能值）。
	TpmsLiveFL *float64 `json:"tpms_live_fl"`
	TpmsLiveFR *float64 `json:"tpms_live_fr"`
	TpmsLiveRL *float64 `json:"tpms_live_rl"`
	TpmsLiveRR *float64 `json:"tpms_live_rr"`
	TpmsLiveAt *time.Time `json:"tpms_live_at"` // 这批实时值的采集时刻

	// 实时胎压的告警判定（与服务端算好的 tpms_alert* 同口径，但只针对 live 值）。
	// TpmsLiveAlert 三态：true=有异常 / false=已知正常 / null=未知（没读到数据）。
	// 同样不用 omitempty：没数据时必须输出 null，让 App 区分「正常」和「不知道」。
	TpmsLiveAlert       *bool    `json:"tpms_live_alert"`
	TpmsLiveAlertWheels []string `json:"tpms_live_alert_wheels"` // 异常轮次，如 ["左后"]
	TpmsLiveAlertDetail string   `json:"tpms_live_alert_detail"` // 如 "左后 2.80 bar"
	TpmsLiveWarnWheels  []string `json:"tpms_live_warn_wheels"`  // 接近阈值的轮次（软告警）

	// v0.7.0 新增：服务端算好的告警判定（唯一事实源，App 与推送脚本共用）
	// 详见 alerts.go —— 阈值与判定逻辑只在服务端实现一次。
	TpmsAlert       bool     `json:"tpms_alert"`
	TpmsAlertWheels []string `json:"tpms_alert_wheels"` // 如 ["左后"]
	TpmsAlertDetail string   `json:"tpms_alert_detail"` // 如 "左后 2.80 bar"
	TpmsMinBar      float64  `json:"tpms_min_bar"`      // 正常范围下限（供 App 展示，避免两边各写一份）
	TpmsMaxBar      float64  `json:"tpms_max_bar"`      // 正常范围上限
	DataAgeHours    int      `json:"data_age_hours"`    // 数据距今多少小时
	DataStale       bool     `json:"data_stale"`        // 数据是否已过期（超过 DataStaleHours）
	// ⚠️ v0.9.0：新增「注意」级阈值。
	// 原来 App 自己按 6 小时 / 24 小时两档判断，而服务端 DataStaleHours=24
	// 才是唯一事实源 —— 两边各写一份阈值，改一边就打架。
	// 现在两档都在服务端算（DataWarnHours / DataStaleHours），App 只读结果。
	DataWarn bool `json:"data_warn"` // 数据已超过「注意」阈值（未过期）

	// ⚠️ v0.9.0：Fleet API 后台轮询的健康状态。
	//   FleetError  非空 = 轮询失败原因（空 = 正常）
	//   FleetLastOK 最近一次成功拉取的时刻
	// App 用它区分「车真没锁」和「这会儿没取到数据」——
	// 失败时我们**保留**上次已知的锁车状态（宁可给一个带时间戳的旧值，
	// 也不给「未知」），并把原因告诉用户。
	FleetError  string     `json:"fleet_error"`
	FleetLastOK *time.Time `json:"fleet_last_ok"`

	ActiveCharge *ActiveCharge `json:"active_charge,omitempty"`
	ActiveDrive  *ActiveDrive  `json:"active_drive,omitempty"`
}

type ActiveCharge struct {
	ChargeID     int       `json:"charge_id"`
	StartedAt    time.Time `json:"started_at"`
	EnergyAdded  *float64  `json:"energy_added_kwh"`
	ChargerPower *int      `json:"charger_power_kw"`
	ChargerVolt  *int      `json:"charger_voltage"`
	CurrentAmps  *int      `json:"charger_current"`
	Phases       *int      `json:"charger_phases"`
	FastCharger  *bool     `json:"fast_charger_present"`
	SocNow       *int      `json:"battery_level"`
}

type ActiveDrive struct {
	DriveID   int       `json:"drive_id"`
	StartedAt time.Time `json:"started_at"`
}

type Drive struct {
	ID          int        `json:"id"`
	StartDate   time.Time  `json:"start_date"`
	EndDate     *time.Time `json:"end_date"`
	DurationMin *int       `json:"duration_min"`
	Distance    *float64   `json:"distance_km"`
	StartPlace  *string    `json:"start_place"`
	EndPlace    *string    `json:"end_place"`
	StartSoc    *int       `json:"start_soc"`
	EndSoc      *int       `json:"end_soc"`
	EnergyKwh   *float64   `json:"energy_kwh"`
	WhPerKm     *float64   `json:"wh_per_km"`
	SpeedMax    *int       `json:"speed_max"`
	OutsideTemp *float64   `json:"outside_temp_avg"`
	OdoStart    *float64   `json:"odometer_start"`
	OdoEnd      *float64   `json:"odometer_end"`
	// v0.1.2 新增
	AvgSpeedKph      *int     `json:"avg_speed_kph"`
	AscentM          *int     `json:"ascent_m"`
	DescentM         *int     `json:"descent_m"`
	InsideTempAvg    *float64 `json:"inside_temp_avg"`
	PowerMaxKw       *int     `json:"power_max_kw"`
	PowerMinKw       *int     `json:"power_min_kw"`
	StartRatedKm     *float64 `json:"start_rated_range_km"`
	EndRatedKm       *float64 `json:"end_rated_range_km"`
	StartIdealKm     *float64 `json:"start_ideal_range_km"`
	EndIdealKm       *float64 `json:"end_ideal_range_km"`
	StartGeofence    *string  `json:"start_geofence"`
	EndGeofence      *string  `json:"end_geofence"`
	HasSupercharger  bool     `json:"has_supercharger"`
	// v0.7.1 新增：行程终点附近是否发生过充电（含交流慢充）。
	// has_supercharger 只表示「终点附近有直流快充」，两者语义不同，别混用。
	ChargeAfterTrip bool `json:"charge_after_trip"`
}

// CostSummary 一个时间窗口内的充电成本构成。
//
// ⚠️ v0.25.0（第 16 轮充电成本专家 P0-2 / P1-7）。
//
// 度数一律是**网侧送出**（`charge_energy_used`）而不是充入电池 ——
// 桩是按它送出去的度数收费的，实测两者差 6.79%。
type CostSummary struct {
	// 公共桩（直流）网侧度数
	DcUsedKwh float64 `json:"dc_used_kwh"`
	// 家充（交流）网侧度数
	AcUsedKwh float64 `json:"ac_used_kwh"`
	// 公共桩场次
	DcSessions int `json:"dc_sessions"`
	// 家充场次
	AcSessions int `json:"ac_sessions"`
	// 公共桩总时长（分钟）—— 按分钟计服务费时用它
	DcMinutes float64 `json:"dc_minutes"`
	// 家充总时长（分钟）
	AcMinutes float64 `json:"ac_minutes"`

	// ⚠️ **v0.30.0（第 19 轮财务评审 P0-3）：围栏命中的「真家充」。**
	//
	// 这是 [AcUsedKwh] 的**子集**：交流 ∧ 有 `geofence_id` 归属。
	// 没有围栏归属的交流度数 = `AcUsedKwh − HomeUsedKwh`，
	// 客户端要用它按「公共/其它交流」价算 —— 不能重复计数。
	//
	// ⚠️ 本机实测：78 场充电的 `geofence_id` **全为 NULL**，
	// 而「家」围栏是 2026-10-25 才建的（在全部数据之后），
	// 所以历史窗口的 `home_used_kwh` 恒为 0 —— 这是**事实**，不是 bug。
	// 今后 TeslaMate 会给新会话回填围栏，这个数才会起来。
	HomeUsedKwh float64 `json:"home_used_kwh"`
	// 围栏命中的家充场次
	HomeSessions int `json:"home_sessions"`
	// 围栏命中的家充总时长（分钟）
	HomeMinutes float64 `json:"home_minutes"`
}

// SocProfilePoint 剖面上的一个点（真实逐公里 SOC）。
//
// ⚠️ v0.24.0（长途充电规划专家 P0-2）：
// 原来 App 的线路余量剖面只有 2~6 个点，中间是**直线插值** ——
// 山区长途（爬升 1500~2600 m）误差实测达 **13 公里**：
// 真实跌破 20% 保底在第 244 公里，插值算出来是 231 公里。
// 用户按 231 公里规划，到 244 公里时电已经见底 —— 会导致危险决策。
//
// 数据本来就在库里（positions 162 万点，采样中位 0.251 秒），
// 现在按**里程 5 公里**分箱取 battery_level 中位数。
// 独立端点 `GET /api/v1/drives/{id}/soc_profile`。
type SocProfilePoint struct {
	Km  float64 `json:"km"`
	Soc float64 `json:"soc"`
}

// ⚠️ v0.26.0：**整条路线**的逐公里 SOC 剖面（多趟行程拼成一条）。
//
// 一次长途旅程是被充电切成很多段的（开 300 km → 充 → 再开 200 km → …），
// App 想画的是**一条连续的余量曲线**，不是七八张互不相干的碎片。
// 端点：`GET /api/v1/cars/{car_id}/route_profile?drive_ids=1,2,3&step_km=5`。
type RouteProfile struct {
	// TotalKm 整条路线的里程跨度 = 最远一段的终点里程 − 最早一段的起点里程。
	//
	// ⚠️ 为什么不是「各段里程之和」：
	//   各段之和**不含中间充电时停的那段路**（可能是几十公里），
	//   而采样点的相对 km 是按**里程表**换算的，
	//   用「各段之和」当横轴长度会把曲线尾巴画到轴外面去。
	//   跨度才是采样点横轴的真实长度。
	TotalKm float64 `json:"total_km"`
	// Legs 参与这条路线的各段，按 App 传入的行驶顺序。
	Legs []RouteProfileLeg `json:"legs"`
	// Points 整条路线上的采样点，按 km 升序。
	Points []RouteProfilePoint `json:"points"`
}

// RouteProfileLeg 一段行程在整条路线里的位置。
type RouteProfileLeg struct {
	DriveID int `json:"drive_id"`
	// StartKm / EndKm / DistanceKm 绝对里程（公里表读数）与本段里程
	StartKm    float64 `json:"start_km"`
	EndKm      float64 `json:"end_km"`
	DistanceKm float64 `json:"distance_km"`
	// KmOffset 本段起点相对**整条路线起点**的公里数
	// = 本段起点里程 − 各段起点里程的最小值。
	//
	// ⚠️ 它保证**正好落在本段第一个采样点上**，
	// 这样 App 把段边界和曲线对齐时不会错位。
	KmOffset float64 `json:"km_offset"`
}

// RouteProfilePoint 路线上的一个 SOC 采样点。
//
// 口径和 SocProfilePoint 一致：段内按 step_km 分桶取 battery_level 的中位数。
type RouteProfilePoint struct {
	Km  float64 `json:"km"`
	Soc float64 `json:"soc"`
}

// ⚠️ v0.27.0：**驾驶风格画像**（最近 N 趟行程的评分 + 汇总）。
//
// 端点：`GET /api/v1/cars/{car_id}/driving_profile?limit=30`。
//
// 为什么要有这个端点：`/drives` 给的是「这趟开了多远、多久」，
// 但**开得急不急、是不是夜里开、能耗高不高**这些「驾驶风格」信息
// 一次都没给过 —— 而这恰恰是用户最想知道的（「我这车到底费不费油」）。
//
// 所有阈值都随 params 一起下发：分数是**有口径的**，
// 用户可以自己核对服务端是怎么算的，不必相信一个神秘数字。
type DrivingProfile struct {
	Drives  []DrivingProfileDrive  `json:"drives"`
	Summary DrivingProfileSummary  `json:"summary"`
	Params  DrivingProfileParams   `json:"params"`
}

// DrivingProfileDrive 一趟行程的驾驶风格画像。
//
// ⚠️ 所有比率都保留 1 位小数：这些数是要给人看的，
// 小数点后面拖 8 位只会让人怀疑服务端在乱算。
type DrivingProfileDrive struct {
	DriveID     int       `json:"drive_id"`
	StartDate   time.Time `json:"start_date"`
	DistanceKm  float64   `json:"distance_km"`
	DurationMin int       `json:"duration_min"`
	// Score 驾驶风格分（0~100，越高越好）。
	//
	// ⚠️ 它是**扣分制**：100 分满分，按「急刹车 / 急加速 / 超速 / 能耗 / 夜驾」五项扣。
	// 扣分上限写死（30/20/20/15/15 = 最多扣 100 分），所以分数永远不会跑出 0~100。
	Score float64 `json:"score"`
	// HardBrake / HardAccel 2 秒窗口内速度落差达到 ±20 km/h 的次数
	HardBrake int `json:"hard_brake"`
	HardAccel int `json:"hard_accel"`
	// BrakePer100Km / AccelPer100Km 每 100 公里的急刹车 / 急加速次数
	BrakePer100Km float64 `json:"brake_per_100km"`
	AccelPer100Km float64 `json:"accel_per_100km"`
	// NightPct 这趟行程里在夜间（22:00~06:00）的比例（%）
	NightPct float64 `json:"night_pct"`
	// OverSpeedPct 这趟行程里速度超过 120 km/h 的时间比例（%）
	OverSpeedPct float64 `json:"over_speed_pct"`
	// WhPerKm 每公里耗电（Wh/km），由车轮功率梯形积分算出来（**净值**，下坡回充会抵扣）
	WhPerKm float64 `json:"wh_per_km"`
	// ⚠️ v0.29.0：WhPerKmGross 每公里**毛耗电**（Wh/km，不含回充抵扣）
	// —— 评分里的「能耗」项用它，净值会被下坡拉出假象（下坡白送分）
	WhPerKmGross float64 `json:"wh_per_km_gross"`
	DescentM int `json:"descent_m"`
	AscentM  int `json:"ascent_m"`
}

// DrivingProfileSummary 最近 N 趟行程的汇总。
//
// ⚠️ 中位数和均值都给：均值会被一趟「狂踩刹车」拉飞，
// 中位数才代表「我平时到底开成什么样」。
type DrivingProfileSummary struct {
	DriveCount      int     `json:"drive_count"`
	TotalKm         float64 `json:"total_km"`
	AvgScore        float64 `json:"avg_score"`
	MedianScore     float64 `json:"median_score"`
	HardBrakeTotal  int     `json:"hard_brake_total"`
	HardAccelTotal  int     `json:"hard_accel_total"`
	NightPctAvg     float64 `json:"night_pct_avg"`
	OverSpeedPctAvg float64 `json:"over_speed_pct_avg"`
	WhPerKmMedian   float64 `json:"wh_per_km_median"`
	// ⚠️ v0.29.0：WhPerKmMedianGross 毛耗电中位（不含回充抵扣）—— App 展示用这个
	WhPerKmMedianGross float64 `json:"wh_per_km_median_gross"`
}

// DrivingProfileParams 服务端这次用的**全部**阈值。
//
// ⚠️ 为什么必须下发：分数本身是「有口径的」，
// 用户要能看见「20 km/h/2s 算急刹车」这条线，否则这个分数就是个玄学数字。
type DrivingProfileParams struct {
	// HardEventThresholdKmhPer2s 判定急刹车/急加速的速度落差阈值（km/h / 2 秒）
	HardEventThresholdKmhPer2s int `json:"hard_event_threshold_kmh_per_2s"`
	// OverSpeedKph 超速判定线（km/h）
	OverSpeedKph int `json:"over_speed_kph"`
	// NightStartHour / NightEndHour 夜间时段（服务器时区的本地小时）
	NightStartHour int `json:"night_start_hour"`
	NightEndHour   int `json:"night_end_hour"`
	// MinDistanceKm 参与统计的最小行程里程（km），短途不计入
	MinDistanceKm float64 `json:"min_distance_km"`
	// Note 阈值来源的说明 —— 免得用户以为这些数是「拟合出来的精准模型」
	Note string `json:"note"`
}

type TrackPoint struct {
	Date      time.Time `json:"date"`
	Lat       float64   `json:"lat"`
	Lng       float64   `json:"lng"`
	MapLat    *float64  `json:"map_lat"`
	MapLng    *float64  `json:"map_lng"`
	Speed     *int      `json:"speed"`
	Power     *int      `json:"power"`
	Battery   *int      `json:"battery_level"`
	Elevation *int      `json:"elevation"`
}

type Charge struct {
	ID          int        `json:"id"`
	StartDate   time.Time  `json:"start_date"`
	EndDate     *time.Time `json:"end_date"`
	DurationMin *int       `json:"duration_min"`
	EnergyAdded *float64   `json:"energy_added_kwh"`
	EnergyUsed  *float64   `json:"energy_used_kwh"`
	Cost        *float64   `json:"cost"`
	StartSoc    *int       `json:"start_soc"`
	EndSoc      *int       `json:"end_soc"`
	Place       *string    `json:"place"`
	// ⚠️ v0.24.0：**真实 POI 名**（「金田服务区」这种）。
	//
	// 原来只有 `place` —— 那是**反向地理编码的长串**
	//（「汕昆高速公路, 北庄, 宜州区, 河池市…」），
	// 而库里其实有 OSM 的原始 POI 名。用户要找的是「哪个服务区」，不是一段地址。
	PoiName *string `json:"poi_name"`
	// POI 类型（services / charging_station / fuel …）
	PoiType *string `json:"poi_type"`
	// ⚠️ v0.24.0：**是不是特斯拉超充** ——
	// 官方 App 的核心区分（超充 / 国标直流 / 交流），
	// 原来只有「直流 / 交流」两档，用户没法判断「这个桩是不是超充」。
	IsTeslaSC bool `json:"is_tesla_sc"`
	MapLat      *float64   `json:"map_lat"`
	MapLng      *float64   `json:"map_lng"`
	IsDC        bool       `json:"is_dc"`
	// v0.1.2 新增
	GeofenceName  *string  `json:"geofence_name"`
	StartRangeKm  *float64 `json:"start_range_km"`  // start_rated_range_km
	EndRangeKm    *float64 `json:"end_range_km"`    // end_rated_range_km
	StartOdoKm    *float64 `json:"start_odometer_km"`
	EndOdoKm      *float64 `json:"end_odometer_km"`
	PeakPowerKw   *int     `json:"peak_power_kw"`
	AvgPowerKw    *int     `json:"avg_power_kw"`
	// ⚠️ v0.21.0：**时间加权**平均功率（kW）—— 真正的「这个桩平均能跑多少 kW」。
	//
	// `avg_power_kw` 是**按采样点数**的算术平均，而采样间隔不均匀
	// （高功率段 5 秒一个点、掉功率段几十秒一个点），所以它**系统性高估**（实测 18%~50%）。
	// 它是「从 X% 充到 80% 约 N 分钟」的唯一来源 ——
	// 功率高估 45% 就意味着**等待时间低估 31%**。
	//
	// 保留旧字段是为了兼容旧客户端；新代码请用这个。
	AvgPowerKwTrue *int `json:"avg_power_kw_true"`
	PeakVoltage   *int     `json:"peak_voltage"`
	AvgVoltage    *int     `json:"avg_voltage"`
	PeakCurrent   *int     `json:"peak_current"`
	AvgCurrent    *int     `json:"avg_current"`
	StartOutsideT *float64 `json:"start_outside_temp"`
	EndOutsideT   *float64 `json:"end_outside_temp"`
	StartIndoorT  *float64 `json:"start_indoor_temp"`
	EndIndoorT    *float64 `json:"end_indoor_temp"`
	ChargerPhases *int     `json:"charger_phases"`
	ChargerVolts  *int     `json:"charger_voltage"` // 末尾充电采样（最近一次）
	ChargerAmps   *int     `json:"charger_current"`
	// v0.5.0 新增
	OutsideTempAvg *float64 `json:"outside_temp_avg"`              // 充电全程平均车外温度
	CableType      *string  `json:"cable_type,omitempty"`          // GB_AC / GB_DC / Tesla
	ChargerBrand   *string  `json:"charger_brand,omitempty"`       // fast_charger_brand
	LossPercent    *float64 `json:"loss_percent,omitempty"`        // (表计-充入)/表计 ×100
}

type CurvePoint struct {
	Date    time.Time `json:"date"`
	Battery *int      `json:"battery_level"`
	PowerKw *int      `json:"charger_power_kw"`
	Voltage *int      `json:"charger_voltage"`
	Current *int      `json:"charger_current"`
	Phases  *int      `json:"charger_phases"`
}

type StatsBucket struct {
	Start       string   `json:"start"`
	Km          float64  `json:"km"`
	Minutes     float64  `json:"minutes"`
	KwhUsed     float64  `json:"kwh_used"`
	WhPerKm     *float64 `json:"wh_per_km"`
	DriveCount  int      `json:"drive_count"`
	ChargeKwh   float64  `json:"charge_kwh"`
	DcKwh       float64  `json:"dc_kwh"`
	AcKwh       float64  `json:"ac_kwh"`
	Cost        float64  `json:"cost"`
	ChargeCount int      `json:"charge_count"`
}

type Stats struct {
	Granularity string         `json:"granularity"`
	Buckets     []*StatsBucket `json:"buckets"`
	Summary     *StatsBucket   `json:"summary"`
	// ⚠️ v0.21.0：**服务端分日用的时区**（如 "Asia/Shanghai"）。
	//
	// 服务端按这个时区分桶，App 的时间戳却按**手机时区**显示 ——
	// 对非该时区的用户，同一件事在时间轴与图表里会落到不同的日子。
	// 显式下发，客户端才能把话说准（「按北京时间分日」）。
	ServerTimezone string `json:"server_timezone"`
}

type BatteryHealth struct {
	CurrentCapacityKwh *float64 `json:"current_capacity_kwh"` // 最近一次满充实测容量
	MaxCapacityKwh     *float64 `json:"max_capacity_kwh"`     // 记录中的最大实测值（仅供趋势参考，不是基准）
	NominalCapacityKwh *float64 `json:"nominal_capacity_kwh"` // 铭牌（出厂标称）容量，来自 NOMINAL_BATTERY_KWH；健康度的分母
	HealthPercent      *float64 `json:"health_percent"`       // 当前实测 / 基准 × 100
	Series             []*BatteryPoint `json:"series"`

	// ⚠️ v0.22.0（电池健康专家 P0-1 / P0-2 / P1-1 / P1-5 / P0-3）。
	//
	// 原来的 `health_percent` 有两个硬伤：
	//   ① **分母口径不同**：分子是「可用容量」（实测），分母是「铭牌毛容量」（出厂），
	//      两者不是同一个量 —— 它回答的是「可用容量占铭牌多少」，
	//      **不是「电池衰减了多少」**。界面上却叫它「健康度」。
	//   ② **依赖一个车主可改的常数**：容量公式里的 `cars.efficiency` 是车主为修
	//      Wh/km 显示而配的**实际行驶能耗**（含空调/路况/风格），
	//      与「电池容量」不是同一个 kWh/km。实测把它从 0.146 改成 0.153，
	//      健康度会从 84.9% 跳到 88.9% —— 一次改配置就改了 4 个百分点。
	//
	// 下面是**不受该常数影响**的一组新指标。
	//
	// **相对首测**：分母取**首测**容量（不是历史最大值 —— 历史最大值会随衰减漂移，
	// ADR #11 已否决用它当基准）。
	// 分子分母同比例缩放，所以 `cars.efficiency` 改多少，这个数都**不变**。
	// 这才是「电池比刚开始测的时候差了多少」的直接答案。
	BaselineCapacityKwh     *float64 `json:"baseline_capacity_kwh"`      // 首测容量（取前 3 次的中位数，抗首测锚歪）
	HealthPercentOfBaseline *float64 `json:"health_percent_of_baseline"` // 当前 ÷ 首测 × 100

	// ⚠️ P1-5：严格档 —— 只取**充到 100%** 的测量点。
	//
	// 原来的 `max_capacity_kwh` 取的是全部 `end_battery_level >= 90` 的点里的最大值，
	// 而 e_soc 90~98 的点**系统性偏高 0.56%**（从 94% 线性外推到 100% 会高估，
	// 车机的续航-电量曲线在顶部是平的）——
	// 实测「历史最好时 66.72 度」取的正是一个 e_soc=96 的点，
	// 比严格档的真实上限（65.41）虚高 0.31 度。
	MaxCapacityKwhStrict *float64 `json:"max_capacity_kwh_strict"` // 只取充到 100% 的点

	// ⚠️ P1-6：**能量反推口径**的容量 —— 与上面的续航×效率口径**完全独立**。
	//
	// 公式：充入 kWh ÷ ΔSOC% × 100，**不需要任何 kWh/km 常数**。
	// 实测两个口径：65.2（续航×效率） vs 65.89（能量反推），差 1.1%。
	// 同一个 App 里出现三个容量数（这里两个 + 充电页的 65.89），
	// 而没有一个地方说明它们为什么不同 —— 现在各自标出来源。
	CapacityKwhEnergy *float64 `json:"capacity_kwh_energy"` // 能量反推口径的容量中位数

	// ⚠️ P0-3：**数据覆盖范围** —— 电池曲线只覆盖车龄的一小段，
	// 却没有任何说明（车主会以为那条下滑的线就是电池的全部历史）。
	// 实测本机只覆盖 83 天 / 9,936 公里 = 车龄的 10%，且**没有任何冬季数据**。
	SeriesSpanDays *int     `json:"series_span_days"`  // 序列覆盖的天数
	OdometerSpanKm *float64 `json:"odometer_span_km"`  // 序列覆盖的里程
}

type BatteryPoint struct {
	Date        time.Time `json:"date"`
	CapacityKwh *float64  `json:"capacity_kwh"`
	EndSoc      *int      `json:"end_soc"`
	// ⚠️ v0.22.0：这个点用的是**哪个 kWh/km 常数**。
	//
	// 以前它藏在一句 SQL 里，用户无从核对 —— 而它恰恰是「健康度」会漂移的原因。
	// 现在随点下发，至少能看出「这个数依赖哪个常数」。
	EfficiencyUsed *float64 `json:"efficiency_used"`
	// ⚠️ 该次测量时的里程表（km）—— 用于算「每万公里衰减」。
	OdometerKm *float64 `json:"odometer_km"`
	// ⚠️ 能量反推口径的容量（不需要 kWh/km 常数）。
	CapacityKwhEnergy *float64 `json:"capacity_kwh_energy"`
}

// AppLatest — App 内更新元数据。
// 字段用 string 是为了与 Android BuildConfig 的字符串比较语义保持一致；
// versionCode 必须可解析为正整数（>=1）才能视为有效。
type AppLatest struct {
	VersionCode  string `json:"version_code"`
	VersionName  string `json:"version_name"`
	ApkURL       string `json:"apk_url"`
	Sha256       string `json:"sha256,omitempty"`
	ReleaseNotes string `json:"release_notes,omitempty"`
	Mandatory    bool   `json:"mandatory"`
}

// ⚠️ v0.28.0：**常驻点**（车平时停在哪 / 停多久）。
//
// 端点：`GET /api/v1/cars/{car_id}/stay_points?min_hours=3`。
//
// ⚠️ 为什么叫「**推断**停留」而不是「停车时长」：
// 车停下来之后 TeslaMate **不再记录位置**（states 里 asleep 44 / offline 123），
// 所以「这一停停了多久」在数据库里并不存在 —— 只能由
// **相邻两趟行程之间的时间间隙**推断出来。
// 界面文案写「停车时长」会让人以为是实时监控，那是骗人的。
type StayPoints struct {
	// Points 常驻点，按累计停留时长降序，最多 12 个
	Points []StayPoint `json:"points"`
	// Note 口径说明 —— 必须随数据一起下发（App 直接展示，不用自己编话术）
	Note string `json:"note"`
}

// StayPoint 一个常驻点（一个地址）。
type StayPoint struct {
	AddressID int     `json:"address_id"`
	Name      string  `json:"name"`
	Latitude  float64 `json:"latitude"`
	Longitude float64 `json:"longitude"`
	// Visits 推断出的停留次数（= 满足阈值的间隙个数）
	Visits int `json:"visits"`
	// TotalHours 累计停留小时数 / AvgHours 平均 / MaxHours 单次最长
	TotalHours float64 `json:"total_hours"`
	AvgHours   float64 `json:"avg_hours"`
	MaxHours   float64 `json:"max_hours"`
	// FirstSeen / LastSeen 第一次、最后一次**开出去**的时间（RFC3339）
	FirstSeen time.Time `json:"first_seen"`
	LastSeen  time.Time `json:"last_seen"`
}

// ⚠️ v0.28.0：**能耗-车速曲线**（不同车速下真实耗多少电）。
//
// 端点：`GET /api/v1/cars/{car_id}/energy_profile`。
//
// 官方 App 只给**平均** Wh/km；这里给的是一条曲线（分档）。
type EnergyProfile struct {
	// Bands 按速度分档的能耗曲线
	Bands []EnergyBand `json:"bands"`
	// TotalKm 全车累计里程（公里表口径，与 drives.distance 一致）
	TotalKm float64 `json:"total_km"`
	// ⚠️ **v0.30.0（第 19 轮数据口径审计 P0-2）：剖面实际覆盖的里程。**
	//
	// 分子（能量）只覆盖剖面**分过箱**的那部分里程（实测 8618.1 km），
	// 而 [TotalKm] 是全车 `sum(drives.distance)`（实测 8759.9 km）——
	// 两者**不同源**，用它当分母会把 Wh/km **低估 1.6%**。
	//
	// 现在两个比值都用 [CoveredKm] 做分母（分子分母同源），
	// [TotalKm] 只用于「全车跑了多少」这句话。
	CoveredKm float64 `json:"covered_km"`
	// OverallWhPerKm 全车平均能耗（车轮功率积分 ÷ 覆盖里程）—— **净值**
	OverallWhPerKm float64 `json:"overall_wh_per_km"`
	// ⚠️ v0.29.0：OverallWhPerKmGross 全车**毛耗电**（不含回充抵扣）
	OverallWhPerKmGross float64 `json:"overall_wh_per_km_gross"`
	// BestBand 效率最好的一档（wh_per_km 最小）
	BestBand *EnergyBestBand `json:"best_band"`
	// Note 口径说明 —— 必须随数据一起下发
	Note string `json:"note"`
}

// EnergyBand 一个速度档位。
type EnergyBand struct {
	// MinKph / MaxKph 该档的速度区间（km/h）
	// ⚠️ 最后一档 MaxKph 是**开区间**上界（145+），更高的速度都并进来
	MinKph int `json:"min_kph"`
	MaxKph int `json:"max_kph"`
	// WhPerKm 该档的能耗（Wh/km）—— **净值**（下坡回充会抵扣）
	WhPerKm float64 `json:"wh_per_km"`
	// ⚠️ v0.29.0：WhPerKmGross 该档的**毛耗电**（Wh/km，不含回充抵扣）。
	//
	// 为什么必须分开给：净值口径下「40–50 km/h = 118.8 Wh/km」看着最省电，
	// 但那是**一次下坡 + 一次长途**的产物 —— 物理上不可能。
	// 「最省电的速度」必须按毛耗电选（评审复算：扣掉回充后最低档变成 100–110 km/h）。
	WhPerKmGross float64 `json:"wh_per_km_gross"`
	// Km 该档累计里程、 Bins 该档的 1 km 分箱个数（样本量）
	Km   float64 `json:"km"`
	Bins int     `json:"bins"`
}

// EnergyBestBand 效率最好的一档。
type EnergyBestBand struct {
	MinKph  int     `json:"min_kph"`
	MaxKph  int     `json:"max_kph"`
	WhPerKm float64 `json:"wh_per_km"`
	// ⚠️ v0.29.0：毛耗电（不含回充抵扣）—— **「最省电档」按它选**
	WhPerKmGross float64 `json:"wh_per_km_gross"`
	// ⚠️ v0.28.0（主控修正）：**必须带上 km 与 bins** ——
	// 原来只有 min/max/wh，App 想显示「这一档跑了多少公里」时拿到的是 0，
	// 而且 App 用整字段相等来比较「是不是最省电那档」→ **恒为 false**，
	// 于是「蓝色 = 最省电」的图例是假的。
	Km   float64 `json:"km"`
	Bins int     `json:"bins"`
}
