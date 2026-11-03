package main

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

// ══════════════════════════════════════════════════════════════════════
// 「真实可达续航」的**取数与端点**（模型在 api/range_estimate.go）
// ══════════════════════════════════════════════════════════════════════

// sqlRangeInputs 当前状态里续航估算要用的几个值。
//
// ⚠️ 取法与 sqlStatus 的 lp / rx **一致**（最近 500 条 positions 里各取最后一个非空值）
// ⇒ App 与服务端拿到的标定续航 / 车外温度 / 电量 / 车速**是同一批**
// ⇒ 两个渠道算出来的数不会对不上。
const sqlRangeInputs = `
WITH lp AS (
  SELECT battery_level,
         rated_battery_range_km::float8 AS rated_range_km,
         ideal_battery_range_km::float8 AS ideal_range_km,
         outside_temp::float8 AS outside_temp,
         speed
  FROM positions WHERE car_id = $1 ORDER BY date DESC LIMIT 1
), rx AS (
  SELECT
    (array_agg(rated_battery_range_km ORDER BY date DESC) FILTER (WHERE rated_battery_range_km IS NOT NULL))[1] AS rated_range_km,
    (array_agg(ideal_battery_range_km ORDER BY date DESC) FILTER (WHERE ideal_battery_range_km IS NOT NULL))[1] AS ideal_range_km,
    (array_agg(outside_temp ORDER BY date DESC) FILTER (WHERE outside_temp IS NOT NULL))[1] AS outside_temp
  FROM (SELECT * FROM positions WHERE car_id = $1 ORDER BY date DESC LIMIT 500) recent
)
SELECT lp.battery_level AS battery_level,
       COALESCE(lp.rated_range_km, rx.rated_range_km) AS rated_range_km,
       COALESCE(lp.ideal_range_km, rx.ideal_range_km) AS ideal_range_km,
       COALESCE(lp.outside_temp, rx.outside_temp) AS outside_temp,
       lp.speed AS speed
FROM lp, rx`

// fetchRangeDrives 取**与客户端同一批**行程（同一条 SQL + 同一个 limit=500）。
func fetchRangeDrives(carID int) ([]*Drive, error) {
	rows, err := db.Query(context.Background(), sqlDrives, carID, rangeDrivesLimit, 0)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	list := []*Drive{}
	for rows.Next() {
		d, err := scanDrive(rows)
		if err != nil {
			return nil, err
		}
		list = append(list, d)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return list, nil
}

// handleRangeEstimate 「真实可达续航」的服务端结论。
//
// ⚠️ 这是**飞书 / Hermes 能问到的第一个「App 首屏数字」** ——
//
//	之前这条判定只在客户端算 ⇒ 飞书问「我现在真实能跑多远」只能答「官方标称」。
func handleRangeEstimate(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	est, err := computeRangeEstimate(id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	// ── 目的地可达性（可选参数 to_km）────────────────────────
	//
	// ⚠️ 只在**显式给了 to_km** 时才算 ⇒ 不给就是「我现在能开多远」那个老问题。
	if raw := strings.TrimSpace(r.URL.Query().Get("to_km")); raw != "" {
		km, err := strconv.ParseFloat(raw, 64)
		if err != nil || km <= 0 {
			writeErr(w, http.StatusBadRequest, "to_km 必须是大于 0 的数字（公里）")
			return
		}
		applyReachEstimate(est, km)
	}

	writeJSON(w, est)
}

// computeRangeEstimate 算「真实可达续航」结论。
func computeRangeEstimate(carID int) (*RangeEstimate, error) {
	var (
		batteryLevel *int
		rated        *float64
		ideal        *float64
		outsideTemp  *float64
		speed        *int
	)
	err := db.QueryRow(context.Background(), sqlRangeInputs, carID).Scan(
		&batteryLevel, &rated, &ideal, &outsideTemp, &speed)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return nil, err
	}

	est := &RangeEstimate{
		ReserveFactor: rangeDefaultReserve,
		BatteryLevel:  batteryLevel,
		OutsideTempC:  outsideTemp,
	}
	// 标定续航：rated 优先，回退 ideal（与客户端 st.rated_range_km ?: st.ideal_range_km 一致）
	ratedRange := rated
	if ratedRange == nil {
		ratedRange = ideal
	}
	est.RatedRangeKm = ratedRange

	model, err := getRangeModel(carID)
	if err != nil {
		return nil, err
	}
	if model == nil {
		est.Summary = "真实可达：样本不足，暂不给数"
		est.Basis = "可用行程样本不足（要 ≥ 4 趟、且每档 ≥ 2 条）⇒ 宁可不给数，也不编一个"
		est.Note = "样本不足时 App 会退回显示「官方标称续航」"
		return est, nil
	}

	// 参考车速：行驶中用实时车速（>5 km/h）；停车用**巡航均速**（最近 5 趟 ≥30 km 的均速中位数）。
	// ⚠️ 与客户端同一条兜底链：模型存在时 cruiseKph 一定非空 ⇒ 后两级兜底实际不可达。
	refSpeed := model.CruiseKph
	if speed != nil && *speed > 5 {
		refSpeed = float64(*speed)
	}
	est.ReferenceSpeedKph = refSpeed
	// ⚠️ **speed_assumed 的含义与客户端一致**：「参考车速是不是**兜底假设值**」。
	//    客户端是 @consumption?.cruiseKph == null && fallbackSpeedKph == null
	//    ⇒ **模型存在时恒为 false**（模型里的巡航均速是实测值，不是假设）。
	//    「车停着所以用巡航均速」是另一件事 ⇒ 写进 note，不占这个标志位。
	est.SpeedAssumed = false

	bucket, exact := bucketForSpeed(refSpeed, model.Buckets)
	est.WhPerKm = &bucket.WhPerKm
	est.BucketLabel = bucket.label()
	est.BucketSamples = bucket.Samples
	est.BucketExact = exact

	est.TempFactor = model.tempFactor(outsideTemp)
	est.TempCaveat = model.tempCaveat(outsideTemp)
	est.TempMinC = model.TempMinC
	est.TempMaxC = model.TempMaxC
	est.LowTempSamples = model.LowTempSamples
	est.ResidualP90 = model.ResidualP90
	est.ResidualN = model.ResidualSamples
	est.SampleCount = model.SampleCount
	est.FirstDrivenAt = model.FirstDrivenAt
	est.LastDrivenAt = model.LastDrivenAt

	est.TrueRangeKm = model.TrueRangeKm(ratedRange, refSpeed, outsideTemp, rangeDefaultReserve)
	est.TrueRangeBandKm = model.TrueRangeBandKm(ratedRange, refSpeed, outsideTemp, rangeDefaultReserve)

	// 「留 20% 保底还能开多少」：可用比例 = (当前电量 − 20%) ÷ 当前电量（与客户端同一口径）。
	if batteryLevel != nil && *batteryLevel > int(rangeFloorReservePct) {
		rf := float64(*batteryLevel-rangeFloorReservePct) / float64(*batteryLevel)
		est.FloorReserveFactor = &rf
		est.FloorRangeKm = model.TrueRangeKm(ratedRange, refSpeed, outsideTemp, rf)
		est.FloorRangeBandKm = model.TrueRangeBandKm(ratedRange, refSpeed, outsideTemp, rf)
	}

	renderRangeText(est)
	return est, nil
}

// renderRangeText 出**唯一一份**中文文案（App 与 Hermes 都直接用 ⇒ 不会各说各话）。
//
// ⚠️ **不许暗示方向**（趋势 / ↑ ↓ / →）—— 这里全是范围与实测值。
func renderRangeText(est *RangeEstimate) {
	var parts []string
	if est.TrueRangeKm != nil {
		s := fmt.Sprintf("真实可达 %.0f 公里", *est.TrueRangeKm)
		if est.TrueRangeBandKm != nil {
			s += fmt.Sprintf("（约 %d ~ %d 公里）", est.TrueRangeBandKm.Lo, est.TrueRangeBandKm.Hi)
		}
		parts = append(parts, s)
	} else {
		parts = append(parts, "真实可达：样本不足，暂不给数")
	}
	if est.FloorRangeKm != nil {
		parts = append(parts, fmt.Sprintf("留 %.0f%% 保底能开 %.0f 公里", rangeFloorReservePct, *est.FloorRangeKm))
	}
	if est.WhPerKm != nil {
		parts = append(parts, fmt.Sprintf("实测 %.1f Wh/km（%s 档，%d 条样本）",
			*est.WhPerKm, est.BucketLabel, est.BucketSamples))
	}
	est.Summary = strings.Join(parts, " · ")

	var b strings.Builder
	if est.RatedRangeKm != nil {
		fmt.Fprintf(&b, "标定续航 %.1f 公里 × %g Wh/km ÷ 实测 %.1f Wh/km（%s 档）",
			*est.RatedRangeKm, rangeEPAWhPerKm, *est.WhPerKm, est.BucketLabel)
	} else {
		fmt.Fprintf(&b, "实测 %.1f Wh/km（%s 档）", *est.WhPerKm, est.BucketLabel)
	}
	fmt.Fprintf(&b, " × 温度修正 %.2f × 保底 %.2f", est.TempFactor, est.ReserveFactor)
	fmt.Fprintf(&b, "；模型样本 %d 条行程", est.SampleCount)
	if est.FirstDrivenAt != nil && est.LastDrivenAt != nil {
		fmt.Fprintf(&b, "（%s ~ %s）",
			est.FirstDrivenAt.Format("2006-01-02"), est.LastDrivenAt.Format("2006-01-02"))
	}
	est.Basis = b.String()

	var notes []string
	if est.TempCaveat != "" {
		notes = append(notes, est.TempCaveat)
	}
	if !est.BucketExact {
		notes = append(notes, fmt.Sprintf("参考车速 %.0f km/h 落在所有档外，借用了 %s 档", est.ReferenceSpeedKph, est.BucketLabel))
	}
	if est.SpeedAssumed {
		notes = append(notes, "参考车速用的是最近行程的巡航均速（车是停着的）")
	}
	if est.ResidualP90 == nil {
		notes = append(notes, fmt.Sprintf("残差 P90 的样本不足 %d 条（≥100 公里的长途）⇒ 不给区间", rangeResidualMinSamples))
	}
	est.Note = strings.Join(notes, "；")
}

// unusedGuard 保住 time / strconv 的引用（SQL 参数与时间格式化用得上）。
var _ = time.Time{}
var _ = strconv.Itoa
