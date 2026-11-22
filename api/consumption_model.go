package main

// ⚠️ **v0.183.0：标定法实测能耗模型 —— 收归服务端。**
//
// ## 为什么搬
//
// 原来「本车实测能耗模型（车速 → Wh/km 桶）」是**客户端**现拟合的
// （app-android/.../ConsumptionModel.kt 的 `ConsumptionModel.from(drives)`）。
// ⇒ ① **飞书里问不出来**（mission §2.1）：清单里 `consumption_model` 那条 gap 就是它；
//   ② **两份口径同时存在** —— 服务端 /energy_profile 也有一个能耗模型，
//   而它**不是同一个口径**（那是车轮功率梯形积分的曲线，见 api/handlers.go 的 sqlEnergyProfile），
//   ⇒ 用户在 App 与飞书里可能看到**两个不同的「实测 Wh/km」**。
//
// ## ⚠️ 这不是「把 /energy_profile 换个格式」
//
// 服务端另有一份**逐条一致**的拟合 —— `sqlClimbCalib`（爬升系数用的那份），
// 它的注释写着「样本过滤与客户端 ConsumptionModel.from() 逐条一致」
// （km ≥ 20、均速 > 0、wh ∈ [50,400]、最近 500 趟）且分桶边界相同（0/60/90/110/130/∞）。
// ⇒ 本文件**照抄客户端的算法**，并**沿用同一套筛选** ⇒ 样本总体一致 ⇒ **数值不变**。
//
// ## ⚠️ 与 /energy_profile 的关系
//
// 两者是**不同口径**，都保留：
//   · 本文件（标定法净值）：按行程的 `wh_per_km` 分桶中位 —— **规划用这个**；
//   · /energy_profile（车轮功率净值）：逐点积分的曲线 —— **曲线展示用这个**。
// ⇒ 不合并、不改名，各说各的（口径不同就摆明说）。

import (
	"context"
	"fmt"
	"math"
	"net/http"
	"sort"
	"time"
)

// ---------- 阈值（与客户端 ConsumptionModel.from 逐条对应） ----------
const (
	// consumptionMinUsableSamples 可用样本少于这个数就**不给模型**。
	consumptionMinUsableSamples = 4

	// consumptionMinKm 单趟至少这么多公里才进模型。
	consumptionMinKm = 20.0

	// consumptionMinWh / MaxWh Wh/km 的合理区间（标定法净值）。
	consumptionMinWh = 50.0
	consumptionMaxWh = 400.0

	// consumptionMinBucketSamples 一个桶少于这么多样本就不建（桶中位数不可信）。
	consumptionMinBucketSamples = 2

	// consumptionMinCruiseKm 参考车速只取「最近 5 趟 ≥30 公里」的均速。
	consumptionMinCruiseKm = 30.0
	consumptionCruiseTrips = 5

	// consumptionMinResidualSamples 残差 P90 至少这么多样本（不给数）。
	consumptionMinResidualSamples = 10

	// consumptionMinResidualKm 残差只用 **km ≥ 100** 的长途样本（短途残差被放大）。
	consumptionMinResidualKm = 100.0

	// consumptionLowTempC 低温样本的门槛（≤10°C）。
	consumptionLowTempC = 10.0

	// consumptionMaxKph 最后一档的开区间上界 —— 与客户端 Int.MAX_VALUE **同值**，
	// 否则客户端的 whSample 会把高速当成「落在桶外」⇒ 借用邻档 ⇒ 数值会变。
	consumptionMaxKph = 2147483647
)

// consumptionBucketEdges 车速分桶边界（与服务端 rangeSpeedEdges **同款**：末档 0 = 开区间）。
//
// ⚠️ 末档在**下发给客户端**时仍是 [consumptionMaxKph]（2147483647）——
// 因为客户端的 whSample 用 `speed < maxKph` 判「落在本档」⇒ 写成别的数会变成「借用邻档」。
var consumptionBucketEdges = []int{0, 60, 90, 110, 130, 0}

// consumptionModelCaveat 样本不足时那句说明（服务端唯一一份）。
const consumptionModelCaveat = "可用实测样本不足，这条模型给不出结论。"

// ---------- DTO ----------

// ConsumptionModelBucket 一个速度档（对应客户端的 SpeedBucket）。
type ConsumptionModelBucket struct {
	MinKph  int     `json:"min_kph"`
	MaxKph  int     `json:"max_kph"`
	WhPerKm float64 `json:"wh_per_km"`
	Samples int     `json:"samples"`
}

// ConsumptionModelPayload 标定法实测能耗模型（服务端唯一一份）。
type ConsumptionModelPayload struct {
	CarID int `json:"car_id"`
	// Buckets 各速度档（按 min_kph 升序）。
	Buckets []ConsumptionModelBucket `json:"buckets"`
	// CruiseKph 参考车速（停车时用）—— 最近 5 趟 ≥30 公里行程的均速中位。
	CruiseKph float64 `json:"cruise_kph"`
	// TempMinC / TempMaxC 样本覆盖的气温范围（界面要说明「没有按气温修正」）。
	TempMinC *float64 `json:"temp_min_c"`
	TempMaxC *float64 `json:"temp_max_c"`
	// LowTempSamples ≤10°C 的样本条数。
	LowTempSamples int `json:"low_temp_samples"`
	// FirstDrivenAt / LastDrivenAt 样本覆盖的起止日期（yyyy-MM-dd，服务端时区）。
	FirstDrivenAt string `json:"first_driven_at"`
	LastDrivenAt  string `json:"last_driven_at"`
	// ResidualP90WhPerKm 模型残差的 90 分位（Wh/km）；样本不足时为 null。
	ResidualP90WhPerKm *float64 `json:"residual_p90_wh_per_km"`
	// ResidualSamples 算 P90 用了多少条样本。
	ResidualSamples int `json:"residual_samples"`
	// SampleCount 参与分桶的样本总数。
	SampleCount int `json:"sample_count"`
	// Caveat 算不出模型时的原因（空 = 有模型）。
	Caveat string `json:"caveat"`
	// Note 口径说明（服务端唯一一份）—— 随数据一起下发，客户端不许自己编。
	Note string `json:"note"`
}

// ---------- 取数 ----------

// consumptionRow 拟合要用的**最小**行程字段集。
type consumptionRow struct {
	km          float64
	avgSpeedKph int
	whPerKm     float64
	outsideTemp *float64
	startDate   time.Time
}

// sqlConsumptionModelRows 取拟合样本。
//
// ⚠️ **筛选与 sqlClimbCalib 逐条一致**（那是服务端已有的、与客户端同口径的一份拟合）：
// km ≥ 20、duration > 0、标定续航在降、wh ∈ [50,400]、最近 500 趟。
// ⇒ 与客户端 `ConsumptionModel.from(drives)` 吃的**同一批行程**。
//
// ⚠️ wh_per_km 的公式也必须**逐字一致**（与 sqlDrives / sqlClimbCalib 同一套）：
// (start_rated − end_rated) × efficiency × 1000 ÷ distance。
const sqlConsumptionModelRows = `
SELECT d.distance::float8 AS km,
       round(d.distance::numeric / d.duration_min * 60)::int AS avg_speed_kph,
       (d.start_rated_range_km - d.end_rated_range_km)::float8
         * c.efficiency * 1000.0 / d.distance AS wh_per_km,
       d.outside_temp_avg::float8 AS outside_temp_avg,
       d.start_date
FROM drives d
JOIN cars c ON c.id = d.car_id
WHERE d.car_id = $1
  AND d.distance >= 20.0
  AND d.duration_min > 0
  AND d.start_rated_range_km IS NOT NULL
  AND d.end_rated_range_km IS NOT NULL
  AND d.start_rated_range_km > d.end_rated_range_km
  AND (d.start_rated_range_km - d.end_rated_range_km)::float8
        * c.efficiency * 1000.0 / d.distance BETWEEN 50.0 AND 400.0
ORDER BY d.start_date DESC
LIMIT 500`

func queryConsumptionRows(ctx context.Context, carID int) ([]consumptionRow, error) {
	rows, err := db.Query(ctx, sqlConsumptionModelRows, carID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []consumptionRow{}
	for rows.Next() {
		var r consumptionRow
		if err := rows.Scan(
			&r.km, &r.avgSpeedKph, &r.whPerKm, &r.outsideTemp, &r.startDate,
		); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// ---------- 算法（照抄客户端 ConsumptionModel.from） ----------

// consumptionBucketForSpeed 某个车速落在**哪个档**（含借用邻档的规则）。
//
// ⚠️ 与客户端的 bucketForSpeed **同一套规则**（v0.36.0：算残差时要用同一个分档规则）。
func consumptionBucketForSpeed(speed float64, buckets []ConsumptionModelBucket) *ConsumptionModelBucket {
	for i := range buckets {
		b := buckets[i]
		if b.MaxKph == consumptionMaxKph {
			if speed >= float64(b.MinKph) {
				return &b
			}
			continue
		}
		if speed >= float64(b.MinKph) && speed < float64(b.MaxKph) {
			return &b
		}
	}
	// 借用最近的一档（速度落在所有桶外时）。
	best := -1
	bestDist := 0.0
	for i := range buckets {
		b := buckets[i]
		var d float64
		if speed < float64(b.MinKph) {
			d = float64(b.MinKph) - speed
		} else {
			d = speed - float64(b.MaxKph)
		}
		ad := math.Abs(d)
		if best < 0 || ad < bestDist {
			best, bestDist = i, ad
		}
	}
	if best < 0 {
		return nil
	}
	b := buckets[best]
	return &b
}

// buildConsumptionModel 拟合实测能耗模型（**逐条照抄**客户端的 from）。
//
// @param loc 日期格式化用的时区（服务端时区，与客户端的 ServerZone 一致）。
func buildConsumptionModel(rows []consumptionRow, loc *time.Location) ConsumptionModelPayload {
	out := ConsumptionModelPayload{Buckets: []ConsumptionModelBucket{}}

	// ① 可用样本。
	usable := make([]consumptionRow, 0, len(rows))
	for _, r := range rows {
		if r.km < consumptionMinKm || r.avgSpeedKph <= 0 {
			continue
		}
		if r.whPerKm < consumptionMinWh || r.whPerKm > consumptionMaxWh {
			continue
		}
		usable = append(usable, r)
	}
	if len(usable) < consumptionMinUsableSamples {
		out.Caveat = consumptionModelCaveat
		return out
	}

	// ② 分桶（每档取 Wh/km 的**中位数**；样本不足 2 条的桶不建）。
	buckets := make([]ConsumptionModelBucket, 0, len(consumptionBucketEdges)-1)
	for i := 0; i < len(consumptionBucketEdges)-1; i++ {
		lo := consumptionBucketEdges[i]
		hi := consumptionBucketEdges[i+1]
		vals := make([]float64, 0, len(usable))
		for _, r := range usable {
			sp := float64(r.avgSpeedKph)
			if hi == 0 {
				if sp >= float64(lo) {
					vals = append(vals, r.whPerKm)
				}
				continue
			}
			if sp >= float64(lo) && sp < float64(hi) {
				vals = append(vals, r.whPerKm)
			}
		}
		if len(vals) < consumptionMinBucketSamples {
			continue
		}
		maxKph := hi
		if maxKph == 0 {
			maxKph = consumptionMaxKph
		}
		buckets = append(buckets, ConsumptionModelBucket{
			MinKph:  lo,
			MaxKph:  maxKph,
			WhPerKm: medianOfFloats(vals),
			Samples: len(vals),
		})
	}
	if len(buckets) == 0 {
		out.Caveat = consumptionModelCaveat
		return out
	}
	out.Buckets = buckets
	for _, b := range buckets {
		out.SampleCount += b.Samples
	}

	// ③ 参考车速：最近 5 趟 ≥30 公里的均速**中位数**。
	sorted := append([]consumptionRow(nil), usable...)
	sort.SliceStable(sorted, func(i, j int) bool { return sorted[i].startDate.After(sorted[j].startDate) })
	recent := make([]float64, 0, consumptionCruiseTrips)
	for _, r := range sorted {
		if r.km >= consumptionMinCruiseKm && r.avgSpeedKph > 0 {
			recent = append(recent, float64(r.avgSpeedKph))
			if len(recent) == consumptionCruiseTrips {
				break
			}
		}
	}
	if len(recent) > 0 {
		out.CruiseKph = medianOfFloats(recent)
	} else {
		all := make([]float64, 0, len(usable))
		for _, r := range usable {
			all = append(all, float64(r.avgSpeedKph))
		}
		if len(all) > 0 {
			out.CruiseKph = medianOfFloats(all)
		} else {
			out.CruiseKph = 60.0
		}
	}

	// ④ 覆盖范围（气温 + 起止日期）。
	temps := make([]float64, 0, len(usable))
	lowTemp := 0
	var firstAt, lastAt time.Time
	for _, r := range usable {
		if r.outsideTemp != nil {
			temps = append(temps, *r.outsideTemp)
			if *r.outsideTemp <= consumptionLowTempC {
				lowTemp++
			}
		}
		if firstAt.IsZero() || r.startDate.Before(firstAt) {
			firstAt = r.startDate
		}
		if lastAt.IsZero() || r.startDate.After(lastAt) {
			lastAt = r.startDate
		}
	}
	if len(temps) > 0 {
		mn := temps[0]
		mx := temps[0]
		for _, t := range temps {
			if t < mn {
				mn = t
			}
			if t > mx {
				mx = t
			}
		}
		out.TempMinC = &mn
		out.TempMaxC = &mx
	}
	out.LowTempSamples = lowTemp
	if !firstAt.IsZero() {
		out.FirstDrivenAt = firstAt.In(loc).Format("2006-01-02")
	}
	if !lastAt.IsZero() {
		out.LastDrivenAt = lastAt.In(loc).Format("2006-01-02")
	}

	// ⑤ 残差 90 分位（只用 **km ≥ 100** 的长途样本）。
	residuals := make([]float64, 0, len(usable))
	for _, r := range usable {
		if r.km < consumptionMinResidualKm {
			continue
		}
		b := consumptionBucketForSpeed(float64(r.avgSpeedKph), buckets)
		if b == nil {
			continue
		}
		residuals = append(residuals, math.Abs(r.whPerKm-b.WhPerKm))
	}
	if len(residuals) >= consumptionMinResidualSamples {
		sortedRes := append([]float64(nil), residuals...)
		sort.Float64s(sortedRes)
		// 最近秩法 P90（与客户端 p90NearestRank 同一口径）：整数算 ceil(size×0.9)
		rank := (len(sortedRes)*9 + 9) / 10
		if rank < 1 {
			rank = 1
		}
		if rank > len(sortedRes) {
			rank = len(sortedRes)
		}
		v := sortedRes[rank-1]
		out.ResidualP90WhPerKm = &v
		out.ResidualSamples = len(residuals)
	}

	return out
}

// ---------- 端点 ----------

// handleConsumptionModel 标定法实测能耗模型（**服务端算的结论**）。
//
// GET /api/v1/cars/{id}/consumption_model
//
// ⚠️ **v0.183.0 新增**：原来这套模型是**客户端**现拟合的 ⇒ 飞书里问不出来，
// 而且与服务端 /energy_profile **是两套口径**（那个是车轮功率曲线）。
func handleConsumptionModel(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	ctx := context.Background()
	rows, err := queryConsumptionRows(ctx, id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	tz := getenv("TZ_NAME", "Asia/Shanghai")
	loc, err := time.LoadLocation(tz)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "时区无效: "+tz)
		return
	}
	out := buildConsumptionModel(rows, loc)
	out.CarID = id
	if out.Caveat == "" {
		out.Note = fmt.Sprintf("基于 %d 条实测行程拟合", out.SampleCount)
	}
	writeJSON(w, out)
}
