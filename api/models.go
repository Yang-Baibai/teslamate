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

	// v0.7.0 新增：服务端算好的告警判定（唯一事实源，App 与推送脚本共用）
	// 详见 alerts.go —— 阈值与判定逻辑只在服务端实现一次。
	TpmsAlert       bool     `json:"tpms_alert"`
	TpmsAlertWheels []string `json:"tpms_alert_wheels"` // 如 ["左后"]
	TpmsAlertDetail string   `json:"tpms_alert_detail"` // 如 "左后 2.80 bar"
	TpmsMinBar      float64  `json:"tpms_min_bar"`      // 正常范围下限（供 App 展示，避免两边各写一份）
	TpmsMaxBar      float64  `json:"tpms_max_bar"`      // 正常范围上限
	DataAgeHours    int      `json:"data_age_hours"`    // 数据距今多少小时
	DataStale       bool     `json:"data_stale"`        // 数据是否已过期

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
}

type BatteryPoint struct {
	Date        time.Time `json:"date"`
	CapacityKwh *float64  `json:"capacity_kwh"`
	EndSoc      *int      `json:"end_soc"`
}

type BatteryHealth struct {
	CurrentCapacityKwh *float64 `json:"current_capacity_kwh"` // 最近一次满充实测容量
	MaxCapacityKwh     *float64 `json:"max_capacity_kwh"`     // 记录中的最大实测值（仅供趋势参考，不是基准）
	NominalCapacityKwh *float64 `json:"nominal_capacity_kwh"` // 铭牌（出厂标称）容量，来自 NOMINAL_BATTERY_KWH；健康度的分母
	HealthPercent      *float64 `json:"health_percent"`       // 当前实测 / 基准 × 100
	Series             []*BatteryPoint `json:"series"`
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
