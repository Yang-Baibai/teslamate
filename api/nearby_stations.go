package main

// ⚠️ **v0.186.0：「我附近的桩哪个快」—— 附近充电站的画像。**
//
// ## 为什么加
//
// 飞书里问「我现在附近的桩哪个快、要等多久」原来**问不出来**：
// 按站画像有（/station_stats），但**没有距离** ⇒ 模型只能拿到 58 个站的全量数据自己挑
// ⇒ **挑不出「附近」**（它不知道车在哪）。
//
// ## 口径（逐条照抄既有那份，别新造）
//
// ① **位置** = 车**最后一次**位置（positions 最新一条）⇒ 车睡着时那是「最后已知位置」
//    ⇒ 必须**带时刻 ＋ 陈旧结论**（复用 `EvaluateDataAge`：6 小时提醒 / 24 小时过期，
//    **不新造阈值** —— 那个口径是服务端唯一事实源，见 alerts.go 的 DataWarnHours / DataStaleHours）。
// ② **距离** = **GCJ-02** 坐标上的球面距离（车与站都用 map_lat/map_lng）
//    ⇒ 不混 WGS-84 与 GCJ-02（国内两者差几百米 ⇒ 混用算出来的距离是错的）。
// ③ **半径** 默认 30 公里（可调，上限 200 公里）。
// ④ **排序**：`distance`（默认，按距离）/ `power`（按**典型直流功率**从高到低 ——「哪个快」）。
//
// ⚠️ **判定只在服务端**：「附近」「哪个快」都是这里的结论 ⇒ App / Hermes 只读（ADR #38）。

import (
	"context"
	"errors"
	"math"
	"sort"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"
)

// ---------- 阈值 ----------

const (
	// nearbyDefaultRadiusKm 默认半径（公里）。
	nearbyDefaultRadiusKm = 30.0
	// nearbyMaxRadiusKm 半径上限（公里）—— 再大就不是「附近」了。
	nearbyMaxRadiusKm = 200.0
	// nearbyMinRadiusKm 半径下限（公里）。
	nearbyMinRadiusKm = 1.0
	// nearbyMaxStations 最多回多少个站（**总数照给**，列表才截断）。
	nearbyMaxStations = 20
	// nearbyEarthRadiusKm 地球平均半径（公里）。
	nearbyEarthRadiusKm = 6371.0088
)

// nearbyNote 这个端点的口径说明（**服务端唯一一份**）。
const nearbyNote = "「附近」按车最后一次位置算（车睡着时是最后已知位置，见位置说明）；距离用 GCJ-02 坐标的球面距离；「哪个快」按该站典型直流功率排。"

// ---------- DTO ----------

// NearbyPosition 车最后一次位置（**含时刻与陈旧结论**）。
type NearbyPosition struct {
	// Known 有没有位置数据（车还没开过 ⇒ 没有）。
	Known bool `json:"known"`
	// Lat / Lng 坐标（**GCJ-02**，与 map_lat/map_lng 同一套坐标系）。
	Lat *float64 `json:"lat"`
	Lng *float64 `json:"lng"`
	// At 那条位置数据的时刻（RFC3339，UTC）。
	At string `json:"at"`
	// AgeHours 距今多少小时（向下取整）。
	AgeHours int `json:"age_hours"`
	// Stale 位置已过期（≥ DataStaleHours 小时）。
	Stale bool `json:"stale"`
	// Warn 该提醒了（≥ DataWarnHours 小时，但还没过期）。
	Warn bool `json:"warn"`
	// Note 这条位置**能不能当「我现在在哪」用**（服务端唯一一份的中文结论）。
	Note string `json:"note"`
}

// NearbyStation 附近的一个充电站（按站画像 ＋ 距离）。
type NearbyStation struct {
	PlaceKey string `json:"place_key"`
	Name     string `json:"name"`
	Address  string `json:"address"`
	// DistanceKm 离车多远（公里）。
	DistanceKm float64 `json:"distance_km"`
	// Sessions 该站的历史场次。
	Sessions int `json:"sessions"`
	// TypicalPowerKw / TypicalPowerDcKw 典型功率（kW）。
	TypicalPowerKw   float64  `json:"typical_power_kw"`
	TypicalPowerDcKw *float64 `json:"typical_power_dc_kw"`
	// TypicalMinutesTo80 折算充到 80% 的耗时（分钟）。
	TypicalMinutesTo80 *int `json:"typical_minutes_to_80"`
	// LastSession 最近一次真充电的实测（v0.185.0）。
	LastSession *StationLastSession `json:"last_session"`
	// MapLat / MapLng 这个站的坐标（GCJ-02）。
	MapLat *float64 `json:"map_lat"`
	MapLng *float64 `json:"map_lng"`
}

// NearbyStationsResponse 「我附近的桩」的完整结论。
type NearbyStationsResponse struct {
	CarID int `json:"car_id"`
	// RadiusKm 这次用的半径（公里）。
	RadiusKm float64 `json:"radius_km"`
	// Sort 这次用的排序（distance / power）。
	Sort string `json:"sort"`
	// Position 车的位置（含时刻与陈旧结论）。
	Position NearbyPosition `json:"position"`
	// StationCount 半径内的站数（**截断前的总数**）。
	StationCount int `json:"station_count"`
	// Stations 站列表（按 Sort 排好，最多 nearbyMaxStations 个）。
	Stations []NearbyStation `json:"stations"`
	// Note 口径说明（服务端唯一一份）。
	Note string `json:"note"`
}

// ---------- 位置 ----------

// sqlLatestPosition 取车**最后一次**位置（positions 最新一条）。
//
// ⚠️ 坐标用 **GCJ-02**（wgs84_to_gcj02_*）⇒ 与站的 map_lat/map_lng 同一套坐标系。
const sqlLatestPosition = `
SELECT date,
       wgs84_to_gcj02_lat(latitude::float8, longitude::float8) AS map_lat,
       wgs84_to_gcj02_lng(latitude::float8, longitude::float8) AS map_lng
FROM positions WHERE car_id = $1 ORDER BY date DESC LIMIT 1`

// queryLatestPosition 取车最后一次位置（**没有就是「不知道」**，不编）。
func queryLatestPosition(ctx context.Context, carID int) (*NearbyPosition, error) {
	var at time.Time
	var lat, lng *float64
	err := db.QueryRow(ctx, sqlLatestPosition, carID).Scan(&at, &lat, &lng)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return &NearbyPosition{Known: false, Note: nearbyPositionNote(false, DataAge{})}, nil
		}
		return nil, err
	}
	age := EvaluateDataAge(at)
	return &NearbyPosition{
		Known:    true,
		Lat:      lat,
		Lng:      lng,
		At:       at.UTC().Format(time.RFC3339),
		AgeHours: age.Hours,
		Stale:    age.Stale,
		Warn:     age.Warn,
		Note:     nearbyPositionNote(true, age),
	}, nil
}

// nearbyPositionNote 这条位置**能不能当「我现在在哪」用**（服务端唯一一份）。
//
// ⚠️ 车睡着时 positions 不再更新 ⇒ 「附近」是「最后已知位置的附近」，
// 那可能已经是几小时前的地方 ⇒ 必须**明说**，不能当成现在。
func nearbyPositionNote(known bool, age DataAge) string {
	if !known {
		return "还没有这台车的位置数据 ⇒ 算不出「附近」"
	}
	if age.Stale {
		return "位置已过期（" + strconv.Itoa(age.Hours) + " 小时前的最后已知位置）⇒「附近」是那时的附近"
	}
	if age.Warn {
		return "位置是 " + strconv.Itoa(age.Hours) + " 小时前的 ⇒ 车可能已经不在那儿了"
	}
	return "位置是刚才的"
}

// ---------- 距离与挑选 ----------

// haversineKm 两点球面距离（公里）。
func haversineKm(lat1, lng1, lat2, lng2 float64) float64 {
	dLat := (lat2 - lat1) * math.Pi / 180
	dLng := (lng2 - lng1) * math.Pi / 180
	s := math.Sin(dLat/2)*math.Sin(dLat/2) +
		math.Cos(lat1*math.Pi/180)*math.Cos(lat2*math.Pi/180)*math.Sin(dLng/2)*math.Sin(dLng/2)
	return 2 * nearbyEarthRadiusKm * math.Asin(math.Sqrt(math.Min(1, s)))
}

// buildNearbyStations 从站画像里挑出**半径内**的站，并按要求排序。
//
// ⚠️ 纯函数（不吃 DB）⇒ 可单测。
// ⚠️ 排序用**未取整**的距离 ⇒ 取整只发生在最后（否则四舍五入会让顺序抖一下）。
func buildNearbyStations(pos *NearbyPosition, stats []StationStat, radiusKm float64, sortBy string) []NearbyStation {
	out := []NearbyStation{}
	if pos == nil || !pos.Known || pos.Lat == nil || pos.Lng == nil {
		return out
	}
	for _, s := range stats {
		if s.MapLat == nil || s.MapLng == nil {
			continue
		}
		d := haversineKm(*pos.Lat, *pos.Lng, *s.MapLat, *s.MapLng)
		if d > radiusKm {
			continue
		}
		addr := ""
		if len(s.Addresses) > 0 {
			addr = s.Addresses[0]
		}
		out = append(out, NearbyStation{
			PlaceKey:           s.PlaceKey,
			Name:               s.Name,
			Address:            addr,
			DistanceKm:         d,
			Sessions:           s.Sessions,
			TypicalPowerKw:     s.TypicalPowerKw,
			TypicalPowerDcKw:   s.TypicalPowerDcKw,
			TypicalMinutesTo80: s.TypicalMinutesTo80,
			LastSession:        s.LastSession,
			MapLat:             s.MapLat,
			MapLng:             s.MapLng,
		})
	}
	if sortBy == "power" {
		sort.SliceStable(out, func(i, j int) bool {
			a, b := out[i], out[j]
			ap, bp := a.TypicalPowerDcKw, b.TypicalPowerDcKw
			if ap == nil && bp == nil {
				return a.DistanceKm < b.DistanceKm
			}
			if ap == nil {
				return false
			}
			if bp == nil {
				return true
			}
			if *ap != *bp {
				return *ap > *bp
			}
			return a.DistanceKm < b.DistanceKm
		})
	} else {
		sort.SliceStable(out, func(i, j int) bool {
			if out[i].DistanceKm != out[j].DistanceKm {
				return out[i].DistanceKm < out[j].DistanceKm
			}
			return out[i].PlaceKey < out[j].PlaceKey
		})
	}
	// 取整放到最后（显示用 2 位小数）。
	for i := range out {
		out[i].DistanceKm = routeKotlinRound2(out[i].DistanceKm)
	}
	return out
}
