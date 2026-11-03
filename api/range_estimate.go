package main

import (
	"context"
	"fmt"
	"math"
	"sort"
	"sync"
	"time"
)

// ══════════════════════════════════════════════════════════════════════
// 「真实可达续航」—— **服务端口径**（2026-11-03）
//
// ⚠️ **为什么搬到服务端**：这条判定原先**只在客户端**算
//    （app-android/.../ConsumptionModel.kt，配套 StatusScreen.kt 的续航结论区）⇒
//    ① 飞书 / Hermes **问不出**这个数 —— 使命 §2.1「App 能看到的任何数据，Hermes 都能取」
//       目前就差这一条；
//    ② 口径只存在于 Kotlin 一处 ⇒ 每次改动都要跨语言对齐。
//
// ⚠️ **样本总体必须与客户端相同**：App 用 api.drives(carId, 500)（StatusScreen.kt）
//    ⇒ 这里用**同一条 SQL + 同一个 limit=500** ⇒ 两边看到的是**同一批行程**
//    ⇒ 算出来的数**必然相同**（不是「差不多相同」）。
//
// ⚠️ **规则逐条照搬客户端**（分档边界 / 样本过滤 / 温度修正 / 保底 / 残差 P90）：
//    Hermes 闸门 6 会逐条比对客户端与本文件的常量 —— 改了一边忘了另一边，闸门报红。
// ══════════════════════════════════════════════════════════════════════

const (
	// ⚠️ 下面每一个常量都**必须**与客户端 ConsumptionModel 一致
	//    （客户端写法见 app-android/.../ConsumptionModel.kt）。
	rangeEPAWhPerKm         = 146.0 // 客户端 EPA_WH_PER_KM
	rangeTempHotC           = 22.0  // 客户端 minOf(0.10, 0.006 * maxOf(0.0, it - 22.0))
	rangeTempLowC           = 10.0  // 客户端 outsideTempC <= 10.0
	rangeTempCoef           = 0.006 // 客户端 0.006
	rangeTempMax            = 0.10  // 客户端 minOf(0.10, …)
	rangeLowTempMinSamples  = 5     // 客户端 LOW_TEMP_MIN_SAMPLES
	rangeBucketMinSamples   = 2     // 客户端 bucket.samples < 2
	rangeMinUsableSamples   = 4     // 客户端 usable.size < 4
	rangeResidualMinKm      = 100.0 // 客户端 distance_km >= 100.0
	rangeResidualMinSamples = 10    // 客户端 residuals.size >= 10
	rangeDefaultReserve     = 0.90  // 客户端 reserveFactor: Double = 0.90
	rangeFloorReservePct    = 20.0  // 客户端「留 20% 保底」
	rangeMinKm              = 20.0  // 客户端 km >= 20.0
	rangeWhMin              = 50.0  // 客户端 wh in 50.0..400.0
	rangeWhMax              = 400.0 // 客户端 wh in 50.0..400.0
	rangeCruiseMinKm        = 30.0  // 客户端「最近 5 趟 ≥30 km」
	rangeCruiseTrips        = 5     // 客户端 take(5)
	rangeDrivesLimit        = 500   // 客户端 api.drives(carId, 500)
)

// rangeSpeedEdges 车速分桶边界（末档 0 = 开区间）。
//
// ⚠️ 与客户端 ConsumptionModel.EDGES = [0, 60, 90, 110, 130, MAX] 同边界。
var rangeSpeedEdges = []int{0, 60, 90, 110, 130, 0}

// rangeBucket 一档实测 Wh/km（中位数）。
type rangeBucket struct {
	MinKph  int
	MaxKph  int // 0 = 开区间（对应客户端的 Int.MAX_VALUE）
	WhPerKm float64
	Samples int
}

// label 档位名（与客户端 SpeedBucket.label 同写法）。
func (b rangeBucket) label() string {
	if b.MaxKph == 0 {
		return fmt.Sprintf("%d+", b.MinKph)
	}
	return fmt.Sprintf("%d–%d", b.MinKph, b.MaxKph)
}

// rangeModel 能耗模型（服务端算出来的那一份）。
type rangeModel struct {
	Buckets         []rangeBucket
	CruiseKph       float64
	TempMinC        *float64
	TempMaxC        *float64
	LowTempSamples  int
	ResidualP90     *float64
	ResidualSamples int
	SampleCount     int
	FirstDrivenAt   *time.Time
	LastDrivenAt    *time.Time
}

// RangeBand 真实可达的区间（悲观, 乐观），单位 km。
type RangeBand struct {
	Lo int `json:"lo_km"`
	Hi int `json:"hi_km"`
}

// RangeEstimate 「真实可达续航」的服务端结论 —— App 与 Hermes 共用这一份。
type RangeEstimate struct {
	// 结论
	TrueRangeKm      *float64   `json:"true_range_km"`
	TrueRangeBandKm  *RangeBand `json:"true_range_band_km"`
	FloorRangeKm     *float64   `json:"floor_range_km"`
	FloorRangeBandKm *RangeBand `json:"floor_range_band_km"`

	// 采用的实测档位
	WhPerKm       *float64 `json:"wh_per_km"`
	BucketLabel   string   `json:"bucket_label"`
	BucketSamples int      `json:"bucket_samples"`
	BucketExact   bool     `json:"bucket_exact"`

	// 参考车速（行驶中用实时车速，停车用巡航均速）
	ReferenceSpeedKph float64 `json:"reference_speed_kph"`
	SpeedAssumed      bool    `json:"speed_assumed"`

	// 温度修正与覆盖范围
	TempFactor     float64  `json:"temp_factor"`
	TempCaveat     string   `json:"temp_caveat,omitempty"`
	TempMinC       *float64 `json:"temp_min_c"`
	TempMaxC       *float64 `json:"temp_max_c"`
	LowTempSamples int      `json:"low_temp_samples"`

	// 模型自身的不确定度
	ResidualP90   *float64   `json:"residual_p90_wh_per_km"`
	ResidualN     int        `json:"residual_samples"`
	SampleCount   int        `json:"sample_count"`
	FirstDrivenAt *time.Time `json:"first_driven_at,omitempty"`
	LastDrivenAt  *time.Time `json:"last_driven_at,omitempty"`

	// 输入（可核对）
	RatedRangeKm       *float64 `json:"rated_range_km"`
	OutsideTempC       *float64 `json:"outside_temp_c"`
	BatteryLevel       *int     `json:"battery_level"`
	ReserveFactor      float64  `json:"reserve_factor"`
	FloorReserveFactor *float64 `json:"floor_reserve_factor,omitempty"`

	// 文案（服务端唯一一份，App 与 Hermes 都直接用）
	Summary string `json:"summary"`
	Basis   string `json:"basis"`
	Note    string `json:"note,omitempty"`
}

// ══════════════════════════════════════════════════════════════════════
// 模型：从行程列表建（逐条照搬客户端 ConsumptionModel.from）
// ══════════════════════════════════════════════════════════════════════

type rangeUsable struct {
	start time.Time
	km    float64
	speed float64
	wh    float64
	temp  *float64
}

// buildRangeModel 建模型；样本不足时返回 nil（宁可不给数，也不编一个）。
func buildRangeModel(drives []*Drive) *rangeModel {
	// ── 样本过滤：km ≥ 20、均速 > 0、wh ∈ [50,400]（与客户端 usable 过滤一致）──
	var usable []rangeUsable
	for _, d := range drives {
		if d.WhPerKm == nil || d.Distance == nil || d.AvgSpeedKph == nil {
			continue
		}
		wh := *d.WhPerKm
		km := *d.Distance
		sp := float64(*d.AvgSpeedKph)
		if km < rangeMinKm || sp <= 0 || wh < rangeWhMin || wh > rangeWhMax {
			continue
		}
		usable = append(usable, rangeUsable{start: d.StartDate, km: km, speed: sp, wh: wh, temp: d.OutsideTemp})
	}
	if len(usable) < rangeMinUsableSamples {
		return nil
	}

	// ── 分桶：每档取 Wh/km 的**中位数**，样本 < 2 条的档不建 ──────────
	var buckets []rangeBucket
	for i := 0; i+1 < len(rangeSpeedEdges); i++ {
		lo := rangeSpeedEdges[i]
		hi := rangeSpeedEdges[i+1]
		var vals []float64
		for _, u := range usable {
			in := false
			if hi == 0 { // 末档：开区间
				in = u.speed >= float64(lo)
			} else {
				in = u.speed >= float64(lo) && u.speed < float64(hi)
			}
			if in {
				vals = append(vals, u.wh)
			}
		}
		if len(vals) < rangeBucketMinSamples {
			continue
		}
		buckets = append(buckets, rangeBucket{MinKph: lo, MaxKph: hi, WhPerKm: medianOf(vals), Samples: len(vals)})
	}
	if len(buckets) == 0 {
		return nil
	}

	// ── 参考车速（停车时用）：最近 5 趟 ≥30 km 行程的均速中位数 ────────
	var recent []rangeUsable
	for _, u := range usable {
		if u.km >= rangeCruiseMinKm && u.speed > 0 {
			recent = append(recent, u)
		}
	}
	sort.Slice(recent, func(i, j int) bool { return recent[i].start.After(recent[j].start) })
	if len(recent) > rangeCruiseTrips {
		recent = recent[:rangeCruiseTrips]
	}
	cruise := 60.0
	if len(recent) > 0 {
		vals := make([]float64, 0, len(recent))
		for _, u := range recent {
			vals = append(vals, u.speed)
		}
		cruise = medianOf(vals)
	} else {
		vals := make([]float64, 0, len(usable))
		for _, u := range usable {
			vals = append(vals, u.speed)
		}
		if len(vals) > 0 {
			cruise = medianOf(vals)
		}
	}

	// ── 气温覆盖范围 + 低温样本条数（≤10°C 才有低温修正）──────────────
	var temps []float64
	lowTemp := 0
	for _, u := range usable {
		if u.temp == nil {
			continue
		}
		temps = append(temps, *u.temp)
		if *u.temp <= rangeTempLowC {
			lowTemp++
		}
	}
	var tmin, tmax *float64
	if len(temps) > 0 {
		mn, mx := temps[0], temps[0]
		for _, t := range temps {
			if t < mn {
				mn = t
			}
			if t > mx {
				mx = t
			}
		}
		tmin = &mn
		tmax = &mx
	}

	// ── 样本覆盖的起止时刻 ─────────────────────────────────────────
	var first, last *time.Time
	for _, u := range usable {
		if first == nil || u.start.Before(*first) {
			t := u.start
			first = &t
		}
		if last == nil || u.start.After(*last) {
			t := u.start
			last = &t
		}
	}

	// ── 模型残差 P90：只看 km ≥ 100 的长途样本；样本 < 10 不给数 ──────
	var residuals []float64
	for _, u := range usable {
		if u.km < rangeResidualMinKm {
			continue
		}
		b, ok := bucketForSpeed(u.speed, buckets)
		if !ok {
			continue
		}
		residuals = append(residuals, math.Abs(u.wh-b.WhPerKm))
	}
	var p90 *float64
	residualN := 0
	if len(residuals) >= rangeResidualMinSamples {
		sort.Float64s(residuals)
		rank := (len(residuals)*9 + 9) / 10
		if rank < 1 {
			rank = 1
		}
		if rank > len(residuals) {
			rank = len(residuals)
		}
		v := residuals[rank-1]
		p90 = &v
		residualN = len(residuals)
	}

	return &rangeModel{
		Buckets:         buckets,
		CruiseKph:       cruise,
		TempMinC:        tmin,
		TempMaxC:        tmax,
		LowTempSamples:  lowTemp,
		ResidualP90:     p90,
		ResidualSamples: residualN,
		SampleCount:     len(usable),
		FirstDrivenAt:   first,
		LastDrivenAt:    last,
	}
}

// bucketForSpeed 车速落在哪一档；落在所有档外时借用最近的一档（exact=false）。
//
// ⚠️ 与客户端 whSample / bucketForSpeed 同规则（两者只在「低于最低档」时走借用，
//
//	那条路径上两个规则给出同一个答案 ⇒ 可以合并成一个实现）。
func bucketForSpeed(speed float64, buckets []rangeBucket) (rangeBucket, bool) {
	for _, b := range buckets {
		if b.MaxKph == 0 {
			if speed >= float64(b.MinKph) {
				return b, true
			}
		} else if speed >= float64(b.MinKph) && speed < float64(b.MaxKph) {
			return b, true
		}
	}
	best := buckets[0]
	bestD := math.Abs(nearestDist(speed, best))
	for _, b := range buckets[1:] {
		d := math.Abs(nearestDist(speed, b))
		if d < bestD {
			best = b
			bestD = d
		}
	}
	return best, false
}

// nearestDist 借用邻档时的距离（与客户端 whSample 同：≥ 上界算 +1）。
func nearestDist(speed float64, b rangeBucket) float64 {
	if speed < float64(b.MinKph) {
		return float64(b.MinKph) - speed
	}
	if b.MaxKph != 0 && speed >= float64(b.MaxKph) {
		return speed - float64(b.MaxKph) + 1
	}
	return 0
}

// lowTempUntrustworthy 低温（≤10°C）且低温样本不足 ⇒ 这个数不可信 ⇒ 不给数。
func (m *rangeModel) lowTempUntrustworthy(outsideTempC *float64) bool {
	return outsideTempC != nil && *outsideTempC <= rangeTempLowC && m.LowTempSamples < rangeLowTempMinSamples
}

// tempFactor 温度修正：只做热的（>22°C），上限 +10%。
func (m *rangeModel) tempFactor(outsideTempC *float64) float64 {
	if outsideTempC == nil {
		return 1.0
	}
	return 1.0 + math.Min(rangeTempMax, rangeTempCoef*math.Max(0, *outsideTempC-rangeTempHotC))
}

// tempCaveat 温度口径说明（与客户端 tempCaveat 同文）。
func (m *rangeModel) tempCaveat(outsideTempC *float64) string {
	if outsideTempC == nil {
		return ""
	}
	if *outsideTempC <= rangeTempLowC && m.LowTempSamples < rangeLowTempMinSamples {
		return fmt.Sprintf("低温未修正：≤%.0f°C 只有 %d 条样本，这个数不可信", rangeTempLowC, m.LowTempSamples)
	}
	if *outsideTempC <= rangeTempHotC {
		return fmt.Sprintf("未做低温修正（≤%.0f°C 样本 %d 条）", rangeTempLowC, m.LowTempSamples)
	}
	return ""
}

// TrueRangeKm 真实可达里程（km）；nil = 不敢给结论。
func (m *rangeModel) TrueRangeKm(ratedRangeKm *float64, speedKph float64, outsideTempC *float64, reserveFactor float64) *float64 {
	if ratedRangeKm == nil || *ratedRangeKm <= 0 {
		return nil
	}
	b, _ := bucketForSpeed(speedKph, m.Buckets)
	if b.Samples < rangeBucketMinSamples {
		return nil
	}
	if m.lowTempUntrustworthy(outsideTempC) {
		return nil
	}
	whEff := b.WhPerKm * m.tempFactor(outsideTempC)
	v := *ratedRangeKm * rangeEPAWhPerKm / whEff * reserveFactor
	return &v
}

// TrueRangeBandKm 真实可达的区间（悲观, 乐观），与 TrueRangeKm 同源。
func (m *rangeModel) TrueRangeBandKm(ratedRangeKm *float64, speedKph float64, outsideTempC *float64, reserveFactor float64) *RangeBand {
	if ratedRangeKm == nil || *ratedRangeKm <= 0 {
		return nil
	}
	if m.ResidualP90 == nil {
		return nil
	}
	b, _ := bucketForSpeed(speedKph, m.Buckets)
	if b.Samples < rangeBucketMinSamples {
		return nil
	}
	if m.lowTempUntrustworthy(outsideTempC) {
		return nil
	}
	whEff := b.WhPerKm * m.tempFactor(outsideTempC)
	p90 := *m.ResidualP90
	if whEff <= p90 {
		return nil
	}
	base := *ratedRangeKm * rangeEPAWhPerKm / whEff * reserveFactor
	optimistic := base * whEff / (whEff - p90)
	pessimistic := base * whEff / (whEff + p90)
	return &RangeBand{Lo: int(math.Round(pessimistic)), Hi: int(math.Round(optimistic))}
}

// ══════════════════════════════════════════════════════════════════════
// 模型缓存（App 每 15 秒轮询一次 ⇒ 不能每次都重算）
// ══════════════════════════════════════════════════════════════════════

// rangeModelCacheTTL 缓存有效期。
//
// ⚠️ **缓存的是「模型」，不是「结论」**：结论（真实可达 / 区间 / 保底）每次请求
//
//	都用**当次的输入**（电量 / 标定续航 / 车外温度 / 车速）重算
//	⇒ 缓存命中时结论仍然是**当下的**（气温一变立刻反映）。
const rangeModelCacheTTL = 5 * time.Minute

// rangeModelCacheEntry 一条缓存。
type rangeModelCacheEntry struct {
	at         time.Time
	model      *rangeModel
	driveCount int
}

var rangeModelCacheMu sync.Mutex
var rangeModelCache = map[int]rangeModelCacheEntry{}

// rangeModelCacheValid 这条缓存还能不能用（**纯函数** ⇒ 好测）。
//
// ⚠️ **两个失效条件都要有**：
//
//	① 超时（TTL）；
//	② **行程条数变了** —— 新开了一趟 ⇒ 样本总体变了 ⇒ 模型必须重建。
//
// 只靠 TTL 的话，「模型样本 N 条」会停在旧数上 ⇒ 与飞书 / 契约自检
// （都按**当前**行程重算）对不上 ⇒ **假红**。
func rangeModelCacheValid(hit rangeModelCacheEntry, now time.Time, driveCount int) bool {
	if hit.driveCount != driveCount {
		return false
	}
	return now.Sub(hit.at) < rangeModelCacheTTL
}

// sqlRangeDriveCount 这台车的行程条数（缓存失效判据）。
const sqlRangeDriveCount = `SELECT count(*) FROM drives WHERE car_id = $1`

// fetchRangeDriveCount 取行程条数。
func fetchRangeDriveCount(carID int) (int, error) {
	var n int
	if err := db.QueryRow(context.Background(), sqlRangeDriveCount, carID).Scan(&n); err != nil {
		return 0, err
	}
	return n, nil
}

// getRangeModel 取能耗模型（带缓存）。
//
// ⚠️ 命中时返回的**可能**是 nil —— 那是「样本不足」的**合法答案**
//
//	（与「没有缓存」是两回事，别混）。
func getRangeModel(carID int) (*rangeModel, error) {
	cnt, err := fetchRangeDriveCount(carID)
	if err != nil {
		return nil, err
	}
	now := time.Now()
	rangeModelCacheMu.Lock()
	hit, ok := rangeModelCache[carID]
	rangeModelCacheMu.Unlock()
	if ok && rangeModelCacheValid(hit, now, cnt) {
		return hit.model, nil
	}
	drives, err := fetchRangeDrives(carID)
	if err != nil {
		return nil, err
	}
	model := buildRangeModel(drives)
	rangeModelCacheMu.Lock()
	rangeModelCache[carID] = rangeModelCacheEntry{at: now, model: model, driveCount: cnt}
	rangeModelCacheMu.Unlock()
	return model, nil
}
