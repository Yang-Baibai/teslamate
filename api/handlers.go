package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	_ "time/tzdata" // alpine/Windows 无系统 tzdb 也能 LoadLocation

	"github.com/jackc/pgx/v5"
)

// ---------- Handlers ----------

func handleHealth(w http.ResponseWriter, _ *http.Request) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if err := db.Ping(ctx); err != nil {
		writeErr(w, http.StatusServiceUnavailable, "数据库不可达")
		return
	}
	writeJSON(w, map[string]string{"status": "ok"})
}

// handleHealthDetailed 是**给车主看的**服务器状态（需鉴权）。
//
// ⚠️ v0.22.0（自托管运维评审 P1-1）：自托管服务的用户体验，八成体现在**它坏的时候**。
// 而「我的服务器现在是什么状态」在 App 里**无处可查** ——
// `/healthz` 只回一个 "ok"，不报 Fleet 状态、服务器时间、最后采集时间。
//
// ⚠️ 为什么**必须鉴权**：而 `/healthz` 是**免鉴权**且映射到 0.0.0.0:8090，
// 扩字段会泄露内部状态（车队 VIN、服务器时间、版本号）。
// 所以新端点带 `auth()`，`/healthz` 只保留最小的 ok/503。
func handleHealthDetailed(w http.ResponseWriter, _ *http.Request) {
	out := map[string]any{}

	// ① 数据库连通性与延迟
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	start := time.Now()
	dbOK := db.Ping(ctx) == nil
	dbMs := time.Since(start).Milliseconds()
	out["db_ok"] = dbOK
	out["db_latency_ms"] = dbMs

	// ② 服务器时间与时区（分日桶用的那个）
	tz := getenv("TZ_NAME", "Asia/Shanghai")
	out["server_timezone"] = tz
	out["server_time"] = time.Now().UTC().Format(time.RFC3339)

	// ③ Fleet 实时状态
	fleet := map[string]any{
		"enabled":  teslaFleetEnabled(),
		"state":    teslaFleetState(),
		"last_ok":  teslaFleetLastOK(),
		"error":    teslaFleetError(),
	}
	out["fleet"] = fleet

	// ④ 最后一次车况采集（TeslaMate 报上来的）
	var lastPos *time.Time
	var ageHours *float64
	_ = db.QueryRow(context.Background(),
		`SELECT max(date) FROM positions`).Scan(&lastPos)
	if lastPos != nil {
		h := time.Since(*lastPos).Hours()
		ageHours = &h
	}
	out["teslamate"] = map[string]any{
		"last_position_at": lastPos,
		"age_hours":        ageHours,
	}

	writeJSON(w, out)
}

func handleCars(w http.ResponseWriter, _ *http.Request) {
	rows, err := db.Query(context.Background(), sqlCars)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	cars := []*Car{}
	for rows.Next() {
		var c Car
		if err := rows.Scan(&c.ID, &c.Name, &c.Model, &c.TrimBadging, &c.VIN, &c.Marketing, &c.Efficiency, &c.Exterior, &c.WheelType); err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		cars = append(cars, &c)
	}
	writeJSON(w, cars)
}

func handleStatus(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	var (
		s            CarStatus
		chargeID     *int
		chargeStart  *time.Time
		chargeAdded  *float64
		chargePower  *int
		chargeVolt   *int
		chargeAmps   *int
		chargePhase  *int
		chargeFast   *bool
		chargeSoc    *int
		driveID      *int
		driveStart   *time.Time
	)
	s.CarID = id
	// ⚠️ v0.29.0：把**服务端时区**一并下发 ——
	// App 要用它统一分日/分月的口径（与服务端的聚合桶一致）。
	s.ServerTimezone = getenv("TZ_NAME", "Asia/Shanghai")
	err = db.QueryRow(context.Background(), sqlStatus, id).Scan(
		&s.UpdatedAt, &s.BatteryLevel, &s.UsableLevel, &s.IdealRangeKm, &s.EstRangeKm, &s.RatedRangeKm,
		&s.Odometer, &s.Speed, &s.Power, &s.OutsideTemp, &s.InsideTemp, &s.IsClimateOn, &s.Elevation,
		&s.Lat, &s.Lng, &s.MapLat, &s.MapLng,
		&s.TpmsFL, &s.TpmsFR, &s.TpmsRL, &s.TpmsRR,
		&s.LocationName,
		&s.State, &s.StateSince,
		&chargeID, &chargeStart, &chargeAdded, &chargePower, &chargeVolt, &chargeAmps, &chargePhase, &chargeFast, &chargeSoc,
		&driveID, &driveStart,
	)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			// ⚠️ v0.9.0：区分「这台车还没数据」与「根本没这台车」。
			// 原来两种情况都是同一个 404「车辆不存在或还没有位置数据」，
			// App 只能一律渲染成红色错误条 —— 但「车还没开过第一次」是**正常状态**，
			// 不该看起来像故障（可靠性复核：「正常状态被渲染成错误」）。
			if carExists(id) {
				writeErrCode(w, http.StatusNotFound,
					"这台车还没有任何数据（可能还没开过，或采集器还没上报）", "no_data")
			} else {
				writeErrCode(w, http.StatusNotFound,
					"找不到这台车，请在「设置」页确认车辆编号", "no_car")
			}
			return
		}
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if chargeID != nil {
		s.ActiveCharge = &ActiveCharge{
			ChargeID: *chargeID, StartedAt: *chargeStart, EnergyAdded: chargeAdded,
			ChargerPower: chargePower, ChargerVolt: chargeVolt, CurrentAmps: chargeAmps,
			Phases: chargePhase, FastCharger: chargeFast, SocNow: chargeSoc,
		}
	}
	if driveID != nil {
		s.ActiveDrive = &ActiveDrive{DriveID: *driveID, StartedAt: *driveStart}
	}
	// 服务端统一算好告警判定，App 与推送脚本读同一份结果（见 alerts.go）
	tpmsAlert := EvaluateTpms(s.TpmsFL, s.TpmsFR, s.TpmsRL, s.TpmsRR)
	s.TpmsAlert = tpmsAlert.Alerting
	s.TpmsAlertWheels = tpmsAlert.Wheels
	if s.TpmsAlertWheels == nil {
		s.TpmsAlertWheels = []string{}
	}
	s.TpmsAlertDetail = strings.Join(tpmsAlert.Values, "  ")
	s.TpmsMinBar = TpmsMinBar
	s.TpmsMaxBar = TpmsMaxBar
	age := EvaluateDataAge(s.UpdatedAt)
	s.DataAgeHours = age.Hours
	s.DataStale = age.Stale
	s.DataWarn = age.Warn

	// ⚠️ v0.9.0：Fleet API 后台轮询的健康状态。
	// 轮询失败时我们**保留**上次已知的锁车/胎压（见 tesla.go），
	// 这里把失败原因与「最后一次成功」的时刻一并交给 App，
	// 让它能说清「这会儿没取到，显示的是 X 分钟前的数据」。
	s.FleetError, s.FleetLastOK = teslaFleetStatus()

	// ⚠️ v0.21.0：**是否配置了 Fleet API 凭据**。
	// false = 这台服务器**没有实时状态源**，锁车与实时胎压恒为「未知」——
	// 这与「这会儿读不到」是两件事，App 必须分开说。
	fleetEnabled := teslaFleetEnabled()
	s.FleetEnabled = &fleetEnabled
	// ⚠️ v0.22.0：Fleet 实时状态四态（disabled / asleep / unauthorized / unreachable / ok）。
	s.FleetState = teslaFleetState()

	// ⚠️ v0.21.0：**车端里程表**（Fleet API 的 vehicle_state.odometer）。
	// 与 `s.Odometer`（TeslaMate 按 GPS 点累计）不同：
	// 车端那个数是车辆自己记的，保养基准应该对齐它。
	s.OdometerVehicle = teslaOdometer()

	// 锁车状态来自 Tesla Fleet API 的后台轮询缓存（api/tesla.go）。
	// 没配凭据或拉取失败时是 nil/nil = 未知，JSON 里输出 null。
	s.Locked, s.LockedAt = teslaLockState()

	// 实时胎压同样来自 Fleet API 的后台轮询缓存（同一个 vehicle_data 响应）。
	// ⚠️ 与上面的 tpms_fl/fr/rl/rr 是两个来源，不互相覆盖：
	//   tpms_fl…   = TeslaMate 的稀疏采样（数小时才有一条）
	//   tpms_live… = Fleet API 的实时值（车醒着时每 2 分钟一条，明显更鲜）
	// App 端：live 有值就用 live，没有才回退 tpms_fl…。
	live := teslaLiveTPMS()
	s.TpmsLiveFL = live.FL.Pressure
	s.TpmsLiveFR = live.FR.Pressure
	s.TpmsLiveRL = live.RL.Pressure
	s.TpmsLiveRR = live.RR.Pressure
	s.TpmsLiveAt = live.At
	// 告警判定：Tesla 服务端自己的硬/软告警位优先，阈值兜底（见 alerts.go）。
	// 四轮一个都没读到 = 未知（null），不能报成「正常」。
	if live.FL.Pressure != nil || live.FR.Pressure != nil ||
		live.RL.Pressure != nil || live.RR.Pressure != nil {
		liveEval := EvaluateLiveTpms(live)
		alerting := liveEval.Alerting
		s.TpmsLiveAlert = &alerting
		s.TpmsLiveAlertWheels = liveEval.Wheels
		s.TpmsLiveAlertDetail = strings.Join(liveEval.Values, "  ")
		s.TpmsLiveWarnWheels = liveEval.WarnWheels
	}
	if s.TpmsLiveAlertWheels == nil {
		s.TpmsLiveAlertWheels = []string{}
	}
	if s.TpmsLiveWarnWheels == nil {
		s.TpmsLiveWarnWheels = []string{}
	}

	writeJSON(w, s)
}

func handleDrives(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	limit := clampLimit(r.URL.Query().Get("limit"), 20)
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	rows, err := db.Query(context.Background(), sqlDrives, id, limit, offset)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	list := []*Drive{}
	for rows.Next() {
		var d Drive
		if err := rows.Scan(
			&d.ID, &d.StartDate, &d.EndDate, &d.DurationMin, &d.Distance,
			&d.StartPlace, &d.EndPlace, &d.StartSoc, &d.EndSoc,
			&d.EnergyKwh, &d.WhPerKm, &d.SpeedMax, &d.OutsideTemp, &d.OdoStart, &d.OdoEnd,
			&d.AvgSpeedKph, &d.AscentM, &d.DescentM, &d.InsideTempAvg,
			&d.PowerMaxKw, &d.PowerMinKw,
			&d.StartRatedKm, &d.EndRatedKm,
			&d.StartIdealKm, &d.EndIdealKm,
			&d.StartGeofence, &d.EndGeofence,
			&d.HasSupercharger, &d.ChargeAfterTrip,
		); err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		list = append(list, &d)
	}
	writeJSON(w, list)
}

// handleCostSummary 返回一个时间窗口内的**充电成本构成**。
//
// ⚠️ v0.25.0（第 16 轮充电成本专家 P0-2 / P1-7）。
//
// 单独一个端点而不是让客户端自己算：
//   - 用量页的「7 天 / 30 天 / 1 年」是**服务端时区**的窗口，
//     而客户端的 `api.charges()` 是「最近 N 场」—— 范围不同，混用会算错；
//   - 年度回顾需要**全量**行程（库里 440 趟），而 `api.drives` 上限 200。
//
// 度数一律用**网侧送出**（`charge_energy_used`）——
// 桩是按它送出去的度数收费的，实测与「充入电池」差 6.79%。
func handleCostSummary(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	tz := getenv("TZ_NAME", "Asia/Shanghai")
	loc, err := time.LoadLocation(tz)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "时区无效: "+tz)
		return
	}
	// 窗口与 /stats 保持一致（同样的 period → 同样的起点）
	var buckets int
	// ⚠️ v0.29.0：新增「本月」（自然月）—— 与 /stats 的 calendar_month 同口径。
	isCalendarMonth := false
	switch r.URL.Query().Get("period") {
	case "week":
		buckets = 7
	case "year":
		buckets = 12
	case "calendar_month":
		buckets = 0
		isCalendarMonth = true
	default:
		buckets = 30
	}
	now := time.Now().In(loc)
	var windowStart time.Time
	if isCalendarMonth {
		y, m, _ := now.Date()
		windowStart = time.Date(y, m, 1, 0, 0, 0, 0, loc)
	} else if r.URL.Query().Get("period") == "year" {
		windowStart = time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, loc).AddDate(0, -(buckets - 1), 0)
	} else {
		windowStart = time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, loc).AddDate(0, 0, -(buckets - 1))
	}
	var out CostSummary
	err = db.QueryRow(context.Background(), sqlCostSummary, id, windowStart).Scan(
		&out.DcUsedKwh, &out.AcUsedKwh,
		&out.DcSessions, &out.AcSessions,
		&out.DcMinutes, &out.AcMinutes,
	)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, out)
}

// handleSocProfile 返回一趟行程的**真实逐公里 SOC 剖面**。
//
// ⚠️ v0.24.0（长途充电规划专家 P0-2）。
//
// 原来 App 的线路余量剖面只有 2~6 个点，中间是**直线插值** ——
// 山区长途（爬升 1500~2600 m）误差实测达 **13 公里**：
// 真实跌破 20% 保底在第 244 公里，插值算出来是 231 公里。
// 用户按 231 公里规划，到 244 公里时电已经见底 —— **会导致危险决策**。
//
// 数据本来就在库里（positions 162 万点，采样中位 0.251 秒），
// 现在按里程 5 公里分箱取 battery_level 中位数。
//
// ⚠️ 独立端点而不是随 /drives 返回：全表算 11 趟长途要 350 ms
// （positions 只有 BRIN 索引，一次查全部长途会退化成全表扫描），
// 会让记录页首屏多等 280 ms；单趟查只要 19 ms —— 按需拉才对。
func handleSocProfile(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "drive id 无效")
		return
	}
	stepKm := 5.0
	if v := r.URL.Query().Get("step_km"); v != "" {
		if f, err := strconv.ParseFloat(v, 64); err == nil && f > 0.5 && f <= 50 {
			stepKm = f
		}
	}
	rows, err := db.Query(context.Background(), sqlSocProfile, id, stepKm)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	pts := []SocProfilePoint{}
	for rows.Next() {
		var p SocProfilePoint
		if err := rows.Scan(&p.Km, &p.Soc); err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		pts = append(pts, p)
	}
	writeJSON(w, pts)
}

// maxRouteProfileDrives 一次路线剖面最多接受多少段行程。
//
// ⚠️ v0.26.0。
//
// 400 段已经覆盖「一次长途旅程被充电切成几百段」的极端情况。
// 再往上就不是一次旅程，而是「全年的行程」—— 那种需求该走聚合/分页，
// 不该用一条 SQL 把几百万个采样点拉回来。
const maxRouteProfileDrives = 400

// handleRouteProfile 返回**多趟行程拼成的一条路线**的逐公里 SOC 剖面。
//
// ⚠️ v0.26.0。
//
// 一次长途旅程是被充电切成很多段的（开 300 km → 充 → 再开 200 km → …），
// handleSocProfile 一次只算一趟，App 拿到的会是**互不相干的碎片**，
// 拼不出「这条路上电是怎么掉的」这条曲线。
// 这个端点按 App 传进来的行驶顺序把多段拼成**一条连续曲线**。
//
// 入参：
//   - drive_ids：逗号分隔的行程 id，**有序**（App 按行驶顺序传），最多 400 个
//   - step_km  ：分箱步长（公里），默认 5，范围 [1, 50]
//
// 某一段没有任何可用采样点（battery_level / odometer 全空）时**整段跳过**，
// 不伪造点 —— 宁可少一段，也不要在图上画出一条假的平线。
//
// 返回里 legs 为空数组（HTTP 200）表示「这几个行程 id 都没数据或不属于这台车」，
// 而不是报错 —— 客户端只要判断 legs.isEmpty() 就能走空状态，不会崩。
func handleRouteProfile(w http.ResponseWriter, r *http.Request) {
	carID, err := strconv.Atoi(r.PathValue("car_id"))
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	driveIDs, err := parseDriveIDs(r.URL.Query().Get("drive_ids"))
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	stepKm := clampStepKm(r.URL.Query().Get("step_km"))

	ctx := context.Background()
	// ⚠️ v0.26.0：这条查询必须放在**事务**里跑，因为要临时调大 work_mem、
	// 并强制走「按实际参数重新规划」的方案。
	//
	// 为什么需要调大 work_mem（实测 2026-10-25，positions 162 万点）：
	//   200 段路线的分桶聚合要排 86 万行，
	//   默认 work_mem=4MB 时会**溢写到磁盘**（external sort 27 MB）→ 2609 ms；
	//   提到 64MB 以上就不溢写 → **429 ms**（快 6 倍）。
	//   12 段只要 60 ms，远在 300 ms 预算内。
	//
	// 为什么必须 force_custom_plan（**踩过的坑**）：
	//   PostgreSQL 对同一个 prepared statement 执行 5 次后会切到「通用计划」。
	//   通用计划里优化器**不知道数组里到底有哪几个 drive_id**，
	//   于是选了另一个形状的计划：
	//     自定义计划：一次 Bitmap Index Scan，Index Searches: 200        → 345 ms
	//     通用计划  ：每行再 AND 一次数组，Index Searches: **40000**      → 3850 ms
	//   慢 11 倍，而且**第 6 次调用才开始变慢** —— 压测时很容易漏掉。
	//   force_custom_plan 让它每次都按实际参数重新规划（多花 8 ms 规划时间，
	//   换回 3.5 秒，值得）。
	//
	// 为什么用 SET LOCAL 而不是改数据库全局配置：
	//   SET LOCAL 只在本事务内有效，事务结束自动还原，
	//   不会污染连接池里其它连接（连接是复用的，改全局会连累别的端点）。
	tx, err := db.Begin(ctx)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	// 只读事务，回滚即可（这里忽略返回值：查询失败时上面的 rows.Err() 已经报过错）
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, "SET LOCAL work_mem = '128MB'"); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if _, err := tx.Exec(ctx, "SET LOCAL plan_cache_mode = force_custom_plan"); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	rows, err := tx.Query(ctx, sqlRouteProfile, driveIDs, carID, stepKm)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()

	out := RouteProfile{
		Legs:   []RouteProfileLeg{},
		Points: []RouteProfilePoint{},
	}
	// 段按 ord 收集，最后排序 —— 不依赖数据库返回的行序
	type legRow struct {
		ord int
		leg RouteProfileLeg
	}
	var legRows []legRow
	legSeen := map[int]bool{}
	for rows.Next() {
		var (
			ord        int
			driveID    int
			startKm    float64
			endKm      float64
			distanceKm float64
			kmOffset   float64
			km         float64
			soc        float64
		)
		// ⚠️ Scan 的顺序必须和 SELECT 的列顺序**严格一致**：
		//   ord, drive_id, start_km, end_km, distance_km, km_offset, km, soc
		if err := rows.Scan(&ord, &driveID, &startKm, &endKm,
			&distanceKm, &kmOffset, &km, &soc); err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		if !legSeen[driveID] {
			legSeen[driveID] = true
			legRows = append(legRows, legRow{
				ord: ord,
				leg: RouteProfileLeg{
					DriveID:    driveID,
					StartKm:    round2(startKm),
					EndKm:      round2(endKm),
					DistanceKm: round2(distanceKm),
					KmOffset:   round2(kmOffset),
				},
			})
		}
		out.Points = append(out.Points, RouteProfilePoint{Km: round2(km), Soc: soc})
	}
	if err := rows.Err(); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	// 段按行驶顺序（App 传进来的顺序）排
	sort.SliceStable(legRows, func(i, j int) bool { return legRows[i].ord < legRows[j].ord })
	minStartKm, maxEndKm := 0.0, 0.0
	for i, lr := range legRows {
		out.Legs = append(out.Legs, lr.leg)
		if i == 0 {
			minStartKm, maxEndKm = lr.leg.StartKm, lr.leg.EndKm
			continue
		}
		if lr.leg.StartKm < minStartKm {
			minStartKm = lr.leg.StartKm
		}
		if lr.leg.EndKm > maxEndKm {
			maxEndKm = lr.leg.EndKm
		}
	}
	// 点按 km 升序（稳定排序：里程相同的点保持段的先后顺序）
	sort.SliceStable(out.Points, func(i, j int) bool { return out.Points[i].Km < out.Points[j].Km })
	// total_km = 路线跨度 = 最远终点 − 最早起点。
	// ⚠️ 不用「各段里程之和」：那样不含中间充电停驶的那段路，
	// 而采样点的横轴是按里程表换算的，轴短了曲线尾巴会画到轴外面。
	out.TotalKm = round2(maxEndKm - minStartKm)
	writeJSON(w, out)
}

// parseDriveIDs 解析 drive_ids 参数：逗号分隔的正整数，**有序**。
//
// ⚠️ v0.26.0。
//
// 为什么上限是 400 而不是默默截断：
//   截断会让 App 画出一条**缺尾巴的路线** ——
//   用户会以为「电就是在第 400 段那里用完的」，而真实原因只是被砍掉了。
//   那种错比直接报错危险得多，所以超限直接报错。
//
// 为什么要去重：
//   重复的 id 会让同一段在剖面里出现两次，
//   曲线会突然回折，看着像「电量涨了」—— 那是假的。
func parseDriveIDs(raw string) ([]int32, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil, errors.New("drive_ids 不能为空")
	}
	parts := strings.Split(raw, ",")
	if len(parts) > maxRouteProfileDrives {
		return nil, fmt.Errorf("drive_ids 最多接受 %d 段，实际 %d 段", maxRouteProfileDrives, len(parts))
	}
	out := make([]int32, 0, len(parts))
	seen := make(map[int32]bool, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p == "" {
			return nil, errors.New("drive_ids 格式无效：存在空项")
		}
		n, err := strconv.Atoi(p)
		if err != nil {
			return nil, errors.New("drive_ids 格式无效：" + p)
		}
		if n <= 0 {
			return nil, errors.New("drive_ids 必须是正整数")
		}
		if seen[int32(n)] {
			continue
		}
		seen[int32(n)] = true
		out = append(out, int32(n))
	}
	if len(out) == 0 {
		return nil, errors.New("drive_ids 不能为空")
	}
	return out, nil
}

// clampStepKm 解析 step_km：默认 5，范围 [1, 50]。
//
// ⚠️ v0.26.0。下限是 1 而不是 0.5：
//   0.5 公里的分箱会让 200 段路线的采样点涨到 40 万个，App 根本画不动。
//
// 越界夹到边界、解析不了就用默认值 —— 和 handleSocProfile 一个口径，
// 免得同一个参数在两个端点上有两套脾气。
func clampStepKm(raw string) float64 {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return 5
	}
	f, err := strconv.ParseFloat(raw, 64)
	if err != nil {
		return 5
	}
	if f < 1 {
		return 1
	}
	if f > 50 {
		return 50
	}
	return f
}

// round2 保留两位小数。
//
// ⚠️ v0.26.0。为什么要在服务端舍入：
//   里程是 double8，98176.421376 − 98176.4 这种浮点噪声会原样传给 App，
//   画到图上就是「0.0000001 公里」的毛刺，还得让客户端自己去修。
//   误差 ≤ 0.005 公里（5 毫米），对「还剩多少电」的判断毫无影响。
func round2(v float64) float64 {
	return math.Round(v*100) / 100
}

func handleTrack(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "drive id 无效")
		return
	}
	rows, err := db.Query(context.Background(), sqlTrack, id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	pts := []*TrackPoint{}
	for rows.Next() {
		var p TrackPoint
		if err := rows.Scan(&p.Date, &p.Lat, &p.Lng, &p.MapLat, &p.MapLng, &p.Speed, &p.Power, &p.Battery, &p.Elevation); err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		pts = append(pts, &p)
	}
	// 轨迹点过多时均匀降采样，上限 1500 点
	if len(pts) > 1500 {
		step := len(pts) / 1500
		out := make([]*TrackPoint, 0, 1501)
		for i := 0; i < len(pts); i += step {
			out = append(out, pts[i])
		}
		if out[len(out)-1] != pts[len(pts)-1] {
			out = append(out, pts[len(pts)-1])
		}
		pts = out
	}
	writeJSON(w, pts)
}

func handleCharges(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	limit := clampLimit(r.URL.Query().Get("limit"), 20)
	offset, _ := strconv.Atoi(r.URL.Query().Get("offset"))
	rows, err := db.Query(context.Background(), sqlCharges, id, limit, offset)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	list := []*Charge{}
	for rows.Next() {
		var c Charge
		if err := rows.Scan(
			&c.ID, &c.StartDate, &c.EndDate, &c.DurationMin,
			&c.EnergyAdded, &c.EnergyUsed, &c.Cost, &c.StartSoc, &c.EndSoc,
			&c.Place, &c.PoiName, &c.PoiType, &c.MapLat, &c.MapLng, &c.IsDC,
			&c.GeofenceName,
			&c.StartRangeKm, &c.EndRangeKm,
			&c.StartOdoKm, &c.EndOdoKm,
			&c.PeakPowerKw, &c.AvgPowerKw, &c.AvgPowerKwTrue,
			&c.PeakVoltage, &c.AvgVoltage,
			&c.PeakCurrent, &c.AvgCurrent,
			&c.StartOutsideT, &c.EndOutsideT,
			&c.StartIndoorT, &c.EndIndoorT,
			&c.OutsideTempAvg,
			&c.ChargerPhases, &c.ChargerVolts, &c.ChargerAmps,
			&c.CableType, &c.ChargerBrand, &c.IsTeslaSC,
		); err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		list = append(list, sanitizeCharge(&c))
	}
	writeJSON(w, list)
}

// sanitizeCharge 把不真实的占位值归零：
// 特斯拉 API 对非超充的直流桩常常只返 2V/0A 的握手占位（功率却正常）。
// 一旦 V<10 或 A<1，视为桩未上报 V/A，置 nil 让 App 显示"—"。
// 功率单独保留（这部分数据一般是真的）。
func sanitizeCharge(c *Charge) *Charge {
	// ⚠️ v0.21.0：V/A 清洗抽成 [sanitizeVA] —— 充电会话与**充电曲线**共用同一套阈值，
	// 免得曲线端点又是「直接 Scan 就返回」（实测 82.3% 的曲线点是 2V 握手占位）。
	sanitizeVA(&c.ChargerVolts, &c.ChargerAmps)
	if c.PeakVoltage != nil && *c.PeakVoltage < 10 {
		c.PeakVoltage = nil
	}
	if c.AvgVoltage != nil && *c.AvgVoltage < 10 {
		c.AvgVoltage = nil
	}
	if c.PeakCurrent != nil && *c.PeakCurrent < 1 {
		c.PeakCurrent = nil
	}
	if c.AvgCurrent != nil && *c.AvgCurrent < 1 {
		c.AvgCurrent = nil
	}
	// 充电损耗率 = (表计用电 - 实际充入) / 表计用电 × 100
	// 典型值：交流慢充 ~10%，直流快充 ~5%。
	// 只有两个值都存在且表计 > 充入（物理上必须）时才计算。
	if c.EnergyUsed != nil && c.EnergyAdded != nil &&
		*c.EnergyUsed > 0 && *c.EnergyAdded > 0 && *c.EnergyAdded <= *c.EnergyUsed {
		v := (*c.EnergyUsed - *c.EnergyAdded) / *c.EnergyUsed * 100
		if v >= 0 && v < 60 { // 超过 60% 视为数据异常，不展示
			c.LossPercent = &v
		}
	}
	// 电缆类型 <invalid> 是 TeslaMate 在无桩数据时的占位，转 nil
	if c.CableType != nil && *c.CableType == "<invalid>" {
		c.CableType = nil
	}
	if c.ChargerBrand != nil && *c.ChargerBrand == "<invalid>" {
		c.ChargerBrand = nil
	}
	return c
}

// handleAppLatest 返回 App 内更新检查用的元数据。
// 配置来源：compose env（APP_VERSION_CODE / APP_VERSION_NAME / APP_APK_URL / APP_RELEASE_NOTES / APP_MANDATORY）。
// 缺省值刻意保守——版本号缺失时 version_code="0" 让客户端永不提示更新（避免误弹）。
func handleAppLatest(w http.ResponseWriter, _ *http.Request) {
	mandatory := getenv("APP_MANDATORY", "0") == "1"
	writeJSON(w, &AppLatest{
		VersionCode:  getenv("APP_VERSION_CODE", "0"),
		VersionName:  getenv("APP_VERSION_NAME", "0.0.0"),
		ApkURL:       getenv("APP_APK_URL", ""),
		Sha256:       getenv("APP_APK_SHA256", ""),
		ReleaseNotes: getenv("APP_RELEASE_NOTES", ""),
		Mandatory:    mandatory,
	})
}

// handleStaticMap：代理高德静态地图 API。
// 请求：?points=lng,lat;lng,lat;...&width=720&height=480&start_label=A&end_label=B
// 返回：image/png（30 分钟进程内缓存）
//
// 设计要点：
//   - AMAP_KEY 必须在 .env 配置；缺则直接 500 给出明确错误
//   - 路线坐标按高德规则（lng,lat）拼 paths=10,0xCC1011,1,,:lng,lat;lng,lat;...
//   - 终点标为两个 markers（mid 红色），起点可选
//   - 不传 location/zoom：高德自动按覆盖物几何中心 + bbox 算——但当路线
//     长度悬殊（跨城长途）时高德会偏，强制显式传 center + zoom 更稳
//   - LRU 缓存：key = (points 字符串 hash, width, height)；30 min 过期
//   - 单 points 限制 ≤ 200（超 200 降采样）；markers 数固定 2（起终点）
//   - 拼好 URL 后不再校验坐标值范围（前端已纠偏到 GCJ-02）
func handleStaticMap(w http.ResponseWriter, r *http.Request) {
	amapKey := getenv("AMAP_KEY", "")
	if amapKey == "" {
		writeErr(w, http.StatusInternalServerError, "服务端未配置 AMAP_KEY")
		return
	}
	q := r.URL.Query()
	pointsStr := strings.TrimSpace(q.Get("points"))
	if pointsStr == "" {
		writeErr(w, http.StatusBadRequest, "缺少 points 参数")
		return
	}
	width := clampInt(q.Get("width"), 400, 1024, 720)
	height := clampInt(q.Get("height"), 200, 1024, 400)
	showMarkers := q.Get("markers") != "0" // 默认带起终点 marker

	// 解析 + 降采样 points
	points := parsePoints(pointsStr)
	if len(points) == 0 {
		writeErr(w, http.StatusBadRequest, "points 格式错误（要求 lng,lat;lng,lat）")
		return
	}
	if len(points) > 200 {
		points = downsamplePoints(points, 200)
	}

	center, zoom := bboxToCenterZoom(points, width, height)

	// 拼 URL
	u := "https://restapi.amap.com/v3/staticmap"
	v := url.Values{}
	v.Set("key", amapKey)
	v.Set("size", fmt.Sprintf("%d*%d", width, height))
	v.Set("scale", "2") // 高清屏
	v.Set("zoom", strconv.Itoa(zoom))
	v.Set("location", fmt.Sprintf("%.6f,%.6f", center.Lng, center.Lat))

	// 路线：paths=10,0xCC1011,1,,:lng,lat;lng,lat;...
	// 高德 pathsStyle 必须 5 段（逗号分隔）：weight, color, transparency, fillcolor, filltransparency
	pathStrs := make([]string, 0, len(points))
	for _, p := range points {
		pathStrs = append(pathStrs, fmt.Sprintf("%.6f,%.6f", p.Lng, p.Lat))
	}
	v.Set("paths", "10,0xCC1011,1,,:"+strings.Join(pathStrs, ";"))

	// markers 子服务在我们这台账号上未开通，加 markers 参数会触发 20003。
	// paths fillcolor 也未开通（同理 20003）。
	// 这台账号只支持最基础的 paths 单条线。
	// 折中：只画路线（红色），起终点标记由下方详情卡承担。
	_ = showMarkers // 保留参数兼容 App
	v.Set("paths", fmt.Sprintf("10,0xCC1011,1,,:%s", strings.Join(pathStrs, ";")))

	// 缓存：key = url.String()
	cacheKey := v.Encode()
	if png, ok := staticMapCache.Get(cacheKey); ok {
		writePNG(w, png)
		return
	}

	req, _ := http.NewRequestWithContext(r.Context(), "GET", u+"?"+v.Encode(), nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "调用高德失败: "+err.Error())
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		writeErr(w, http.StatusBadGateway, "高德返回 "+strconv.Itoa(resp.StatusCode))
		return
	}
	// 一次读全 body（高德静态图 ≤ 200KB，足够）
	buf := make([]byte, 0, 64*1024)
	tmp := make([]byte, 8192)
	for {
		n, err := resp.Body.Read(tmp)
		if n > 0 {
			buf = append(buf, tmp[:n]...)
		}
		if err != nil {
			break
		}
		if len(buf) > 2*1024*1024 { // 防御：2 MB 上限
			break
		}
	}
	// 高德错误响应：HTTP 200 但 body 是 JSON，含 status=0
	// 用前缀嗅探：PNG 头是 0x89 0x50；JSON 头是 '{'
	if len(buf) > 0 && buf[0] == '{' {
		var er struct {
			Status, Info, Infocode string
		}
		_ = json.Unmarshal(buf, &er)
		writeErr(w, http.StatusBadGateway,
			"高德: "+er.Info+" (code "+er.Infocode+")")
		return
	}
	staticMapCache.Set(cacheKey, buf)
	writePNG(w, buf)
}

func writePNG(w http.ResponseWriter, b []byte) {
	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("Cache-Control", "public, max-age=1800")
	w.Write(b)
}

func clampInt(raw string, lo, hi, def int) int {
	n, err := strconv.Atoi(raw)
	if err != nil || n < lo {
		return def
	}
	if n > hi {
		return hi
	}
	return n
}

type latLng struct {
	Lat float64
	Lng float64
}

func parsePoints(s string) []latLng {
	parts := strings.Split(s, ";")
	out := make([]latLng, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		coords := strings.Split(p, ",")
		if len(coords) != 2 {
			continue
		}
		lng, err1 := strconv.ParseFloat(strings.TrimSpace(coords[0]), 64)
		lat, err2 := strconv.ParseFloat(strings.TrimSpace(coords[1]), 64)
		if err1 != nil || err2 != nil {
			continue
		}
		if lat < -90 || lat > 90 || lng < -180 || lng > 180 {
			continue
		}
		out = append(out, latLng{Lat: lat, Lng: lng})
	}
	return out
}

func downsamplePoints(in []latLng, target int) []latLng {
	if len(in) <= target {
		return in
	}
	out := make([]latLng, 0, target)
	out = append(out, in[0])
	step := float64(len(in)-1) / float64(target-1)
	for i := 1; i < target-1; i++ {
		out = append(out, in[int(float64(i)*step)])
	}
	out = append(out, in[len(in)-1])
	return out
}

// bboxToCenterZoom：按路线 bbox 中心 + 边长算高德 zoom level。
// 高德 zoom [1,17]：3=大陆、5=省、10=市区、13=街道、16=楼栋。
// 简化为边长 km → zoom 的对数映射。
func bboxToCenterZoom(pts []latLng, w, h int) (latLng, int) {
	if len(pts) == 1 {
		return pts[0], 16
	}
	minLat, maxLat := pts[0].Lat, pts[0].Lat
	minLng, maxLng := pts[0].Lng, pts[0].Lng
	for _, p := range pts[1:] {
		if p.Lat < minLat {
			minLat = p.Lat
		}
		if p.Lat > maxLat {
			maxLat = p.Lat
		}
		if p.Lng < minLng {
			minLng = p.Lng
		}
		if p.Lng > maxLng {
			maxLng = p.Lng
		}
	}
	// 简化的中心点（带横向 padding）
	center := latLng{
		Lat: (minLat + maxLat) / 2,
		Lng: (minLng + maxLng) / 2,
	}
	// 用经纬度差估距离（米）。赤道附近 1° ≈ 111 km；纬度越高越短。
	latKm := (maxLat - minLat) * 111.0
	lngKm := (maxLng - minLng) * 111.0 * cosDeg(center.Lat)
	spanKm := max(latKm, lngKm)
	if spanKm < 0.1 {
		spanKm = 0.1
	}
	// 经验公式：spanKm 与 zoom 的关系（参考常用配置）
	zoom := 17
	switch {
	case spanKm > 2000:
		zoom = 3
	case spanKm > 1000:
		zoom = 4
	case spanKm > 500:
		zoom = 5
	case spanKm > 200:
		zoom = 6
	case spanKm > 100:
		zoom = 7
	case spanKm > 50:
		zoom = 8
	case spanKm > 20:
		zoom = 9
	case spanKm > 10:
		zoom = 10
	case spanKm > 5:
		zoom = 11
	case spanKm > 2:
		zoom = 12
	case spanKm > 1:
		zoom = 13
	case spanKm > 0.5:
		zoom = 14
	case spanKm > 0.2:
		zoom = 15
	case spanKm > 0.1:
		zoom = 16
	}
	// 短图（h<=300）再放大 1 级；宽图（w>=900）再缩 1 级
	if h <= 300 {
		zoom++
	}
	if w >= 900 {
		zoom--
	}
	if zoom < 3 {
		zoom = 3
	}
	if zoom > 17 {
		zoom = 17
	}
	return center, zoom
}

func cosDeg(deg float64) float64 {
	return math.Cos(deg * math.Pi / 180.0)
}

// 进程内 LRU 缓存（30 min TTL，最多 64 项）
var staticMapCache = newPNGCache(64, 30*time.Minute)

type pngCache struct {
	mu    sync.Mutex
	data  map[string]pngEntry
	order []string
	max   int
	ttl   time.Duration
}

type pngEntry struct {
	body     []byte
	expireAt time.Time
}

func newPNGCache(max int, ttl time.Duration) *pngCache {
	return &pngCache{data: make(map[string]pngEntry, max), max: max, ttl: ttl}
}

func (c *pngCache) Get(k string) ([]byte, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.data[k]
	if !ok {
		return nil, false
	}
	if time.Now().After(e.expireAt) {
		delete(c.data, k)
		return nil, false
	}
	return e.body, true
}

func (c *pngCache) Set(k string, b []byte) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, ok := c.data[k]; !ok {
		c.order = append(c.order, k)
	}
	c.data[k] = pngEntry{body: b, expireAt: time.Now().Add(c.ttl)}
	for len(c.order) > c.max {
		old := c.order[0]
		c.order = c.order[1:]
		delete(c.data, old)
	}
}

func handleChargeCurve(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "charge id 无效")
		return
	}
	rows, err := db.Query(context.Background(), sqlChargeCurve, id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	pts := []*CurvePoint{}
	for rows.Next() {
		var p CurvePoint
		if err := rows.Scan(&p.Date, &p.Battery, &p.PowerKw, &p.Voltage, &p.Current, &p.Phases); err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		// ⚠️ v0.21.0：曲线端点也走**占位值清洗**（ADR #15）。
		//
		// 原来只有 `sanitizeCharge`（充电会话）做了清洗，曲线端点直接 Scan 就返回 ——
		// 而实测 22,912 个曲线采样点里 **18,861 个（82.3%）** 的 `charger_voltage`
		// 是握手占位值 **2 V**、`charger_actual_current = 0`。
		//
		// 现在 App 只画功率与 SOC，所以这是**潜伏**的；
		// 但任何「V/A 曲线」或「V/A 实时显示」的新功能一上线，
		// 画出来的就是一条恒定 2 V / 0 A 的直线。
		//
		// 功率**不**清洗 —— 功率这部分数据一般是真的。
		sanitizeVA(&p.Voltage, &p.Current)
		pts = append(pts, &p)
	}
	writeJSON(w, pts)
}

// sanitizeVA 把不真实的占位值置 nil（ADR #15）。
//
// 特斯拉 API 对非超充的直流桩常常只返 2V/0A 的握手占位（功率却正常）。
// 一旦 V<10 或 A<1，视为桩未上报 V/A，置 nil 让 App 显示「—」。
func sanitizeVA(volts, amps **int) {
	if volts != nil && *volts != nil && **volts < 10 {
		*volts = nil
	}
	if amps != nil && *amps != nil && **amps < 1 {
		*amps = nil
	}
}

func handleStats(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	var granularity string
	var buckets int
	// ⚠️ **v0.29.0：新增「本月」= 自然月**（当月 1 号 → 今天）。
	//
	// 为什么需要它（通勤车主 P0-3）：
	// 「30 天」是**滚动窗口**，实测比自然月多算 25.9%（5262.6 vs 4180.0 公里），
	// 而车主脑子里的「这个月花了多少钱」是**自然月**。
	// 两个数都在界面上却不加区分，用户对不上账。
	isCalendarMonth := false
	switch r.URL.Query().Get("period") {
	case "week":
		granularity, buckets = "day", 7
	case "year":
		granularity, buckets = "month", 12
	case "calendar_month":
		granularity, buckets = "day", 0
		isCalendarMonth = true
	default:
		granularity, buckets = "day", 30
	}
	tz := getenv("TZ_NAME", "Asia/Shanghai")
	loc, err := time.LoadLocation(tz)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "时区无效: "+tz)
		return
	}
	now := time.Now().In(loc)
	var windowStart time.Time
	if isCalendarMonth {
		// 当月 1 号 00:00 → 今天（桶数 = 今天是几号）
		y, m, _ := now.Date()
		windowStart = time.Date(y, m, 1, 0, 0, 0, 0, loc)
		buckets = now.Day()
	} else if granularity == "day" {
		y, m, d := now.Date()
		windowStart = time.Date(y, m, d, 0, 0, 0, 0, loc).AddDate(0, 0, -(buckets - 1))
	} else {
		windowStart = time.Date(now.Year(), now.Month(), 1, 0, 0, 0, 0, loc).AddDate(0, -(buckets - 1), 0)
	}

	driveAgg, err := scanBucketAgg(sqlStatsDrives, id, granularity, tz, windowStart,
		func(sb *StatsBucket, f1, f2, f3 float64, cnt int) {
			sb.Km, sb.Minutes, sb.KwhUsed, sb.DriveCount = f1, f2, f3, cnt
		})
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	chargeAgg, err := scanBucketAgg(sqlStatsCharges, id, granularity, tz, windowStart,
		func(sb *StatsBucket, f1, f2, f3 float64, cnt int) {
			sb.ChargeKwh, sb.DcKwh, sb.Cost, sb.ChargeCount = f1, f2, f3, cnt
			sb.AcKwh = f1 - f2
		})
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}

	stats := &Stats{Granularity: granularity}
	// ⚠️ v0.21.0：**把服务端时区显式告诉客户端**。
	//
	// 背景：服务端按 `TZ_NAME` 分日桶（默认 Asia/Shanghai），
	// 而 App 的时间戳按**手机时区**显示/分组。
	// 对 CST 用户两者一致（本机主人就是），但对任何非 CST 时区用户，
	// 同一个事件在时间轴上属于 10-06、在图表里却落到 10-07 ——
	// 「跨页跨日错位」，用户对不上账。
	//
	// 客户端无法自己猜服务端用哪个时区，所以这里显式下发。
	stats.ServerTimezone = tz
	for i := 0; i < buckets; i++ {
		bucketStart := windowStart
		if granularity == "month" {
			bucketStart = windowStart.AddDate(0, i, 0)
		} else {
			bucketStart = windowStart.AddDate(0, 0, i)
		}
		key := bucketStart.Format("2006-01-02")
		b := &StatsBucket{Start: key}
		if d, ok := driveAgg[key]; ok {
			b.Km, b.Minutes, b.KwhUsed, b.DriveCount = d.Km, d.Minutes, d.KwhUsed, d.DriveCount
		}
		if c, ok := chargeAgg[key]; ok {
			b.ChargeKwh, b.DcKwh, b.AcKwh, b.Cost, b.ChargeCount = c.ChargeKwh, c.DcKwh, c.AcKwh, c.Cost, c.ChargeCount
		}
		if b.Km > 0.1 {
			v := b.KwhUsed * 1000 / b.Km
			b.WhPerKm = &v
		}
		stats.Buckets = append(stats.Buckets, b)
	}
	stats.Summary = summarize(stats.Buckets)
	writeJSON(w, stats)
}

// 执行桶聚合 SQL（两个聚合查询同为 bucket/f1/f2/f3/count 五列）并按日期建索引
func scanBucketAgg(sql string, carID int, granularity, tz string, windowStart time.Time,
	apply func(*StatsBucket, float64, float64, float64, int),
) (map[string]*StatsBucket, error) {
	rows, err := db.Query(context.Background(), sql, carID, granularity, tz, windowStart)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]*StatsBucket{}
	for rows.Next() {
		var bucket string
		var f1, f2, f3 float64
		var cnt int
		if err := rows.Scan(&bucket, &f1, &f2, &f3, &cnt); err != nil {
			return nil, err
		}
		sb := &StatsBucket{Start: bucket}
		apply(sb, f1, f2, f3, cnt)
		out[bucket] = sb
	}
	return out, rows.Err()
}

func handleBattery(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	rows, err := db.Query(context.Background(), sqlBattery, id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	h := &BatteryHealth{}
	var last *float64
	// ⚠️ v0.22.0（电池健康专家 P1-5 / P1-1 / P0-3）：下面这些累加器。
	var (
		lastOdo      *float64
		firstOdo     *float64
		firstDate    time.Time
		lastDate     time.Time
		haveDate     bool
		strictMax    *float64
		energyCaps   []float64
		baselineCaps []float64
	)
	for rows.Next() {
		p := &BatteryPoint{}
		if err := rows.Scan(&p.Date, &p.CapacityKwh, &p.EndSoc, &p.EfficiencyUsed, &p.OdometerKm, &p.CapacityKwhEnergy); err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		if p.CapacityKwh != nil {
			if h.MaxCapacityKwh == nil || *p.CapacityKwh > *h.MaxCapacityKwh {
				h.MaxCapacityKwh = p.CapacityKwh
			}
			// ⚠️ P1-5：**严格档**只取**充到 100%** 的点。
			// e_soc 90~98 的点系统性偏高 0.56%（从 94% 线性外推到 100% 会高估，
			// 车机的续航-电量曲线在顶部是平的）——
			// 而旧的 max_capacity_kwh 取的正是这样一个 e_soc=96 的点（66.72 度），
			// 比真实上限（65.41）虚高 0.31 度，让窗口内的跌幅看起来更大。
			if p.EndSoc != nil && *p.EndSoc >= 100 && (strictMax == nil || *p.CapacityKwh > *strictMax) {
				strictMax = p.CapacityKwh
			}
			// ⚠️ P1-1：**首测基线**取**前 3 次的中位数** ——
			// 只取第一次的话，首测当天若气温异常就会把整条基线锚歪。
			if len(baselineCaps) < 3 {
				baselineCaps = append(baselineCaps, *p.CapacityKwh)
			}
			last = p.CapacityKwh
		}
		if p.CapacityKwhEnergy != nil {
			energyCaps = append(energyCaps, *p.CapacityKwhEnergy)
		}
		if p.OdometerKm != nil {
			if firstOdo == nil {
				firstOdo = p.OdometerKm
			}
			lastOdo = p.OdometerKm
		}
		if !haveDate {
			firstDate = p.Date
			haveDate = true
		}
		lastDate = p.Date
		h.Series = append(h.Series, p)
	}
	h.CurrentCapacityKwh = last
	h.MaxCapacityKwhStrict = strictMax

	// ⚠️ v0.22.0（P1-1）：**相对首测** —— 唯一不受 `cars.efficiency` 影响的健康指标。
	//
	// 分子分母同比例缩放，所以车主改 `cars.efficiency`（那是为了修 Wh/km 显示）
	// 这个数**一点都不变**。
	// 基准取**前 3 次的中位数**而不是第一次 —— 单次测量受当天温度影响太大。
	if len(baselineCaps) > 0 {
		base := medianOfFloats(baselineCaps)
		h.BaselineCapacityKwh = &base
		if last != nil && base > 0 {
			v := *last * 100 / base
			h.HealthPercentOfBaseline = &v
		}
	}

	// ⚠️ P1-6：能量反推口径的容量中位数（与主口径互相印证）。
	if len(energyCaps) > 0 {
		v := medianOfFloats(energyCaps)
		h.CapacityKwhEnergy = &v
	}

	// ⚠️ P0-3：**数据覆盖范围** —— 电池曲线只覆盖车龄的一小段，
	// 不说清楚的话，车主会把那条线当成「电池的全部历史」。
	if haveDate && len(h.Series) >= 2 {
		days := int(lastDate.Sub(firstDate).Hours() / 24)
		h.SeriesSpanDays = &days
		if firstOdo != nil && lastOdo != nil {
			span := *lastOdo - *firstOdo
			h.OdometerSpanKm = &span
		}
	}
	// 健康度基准 = 铭牌（出厂标称）容量，来自环境变量 NOMINAL_BATTERY_KWH。
	//
	// 为什么不能用"历史最大值"当基准（v0.4.0 及以前的错误做法）：
	//   历史最大值本身是衰减后的测量值，会随时间漂移——车越老，最大值越小，
	//   于是"当前/历史最大"这个比值永远接近 100%，健康度虚高且不可比较。
	//   本车曾报 98%，实际是 65.41/76.8 = 85.2%。
	//
	// 为什么不用"满电续航 × 车辆效率"反推：
	//   Model 3 P74D 满电标定续航 454.59 km × 0.146 = 66.37 kWh，这是车机按
	//   当前衰减后的可用容量上报的，与铭牌 76.8 kWh 差 15%，用它当基准同样失真。
	//
	// ⚠️ v0.9.2（口径审计 S1-1）：**删掉「回退到历史最大值」的兜底**。
	//
	// 原来这里是这样的：
	//     base := h.NominalCapacityKwh
	//     if base == nil { base = h.MaxCapacityKwh }
	//
	// 后果：任何一次**不带 .env 的重建**（换机 / 迁移 / 灾备 / CI 拉起），
	// 健康度会从 85% 静默跳到 ~100%（当前/历史最大 这个比值永远接近 100%），
	// 而且界面上**没有任何提示** —— 这是本项目唯一一个「数据缺失 → 数字变好」的路径。
	//
	// 现在：没有铭牌容量就**不给健康度**（null），由 App 明确显示「暂无基准」。
	// 数据缺失时正确的结果是「不知道」，不是「看起来很好」。
	if nominal := getenv("NOMINAL_BATTERY_KWH", ""); nominal != "" {
		if n, err := strconv.ParseFloat(nominal, 64); err == nil && n > 0 {
			h.NominalCapacityKwh = &n
		}
	}
	base := h.NominalCapacityKwh
	if last != nil && base != nil && *base > 0 {
		v := *last * 100 / *base
		h.HealthPercent = &v
	}
	writeJSON(w, h)
}

// ---------- 工具 ----------

// medianOfFloats 返回中位数（偶数个取两个中间值的平均）。
//
// ⚠️ v0.22.0：Go 标准库没有中位数，电池健康的新指标要用。
// 取两个中间值的平均（而不是上中位数），与 numpy/pandas/R 一致 ——
// 电池专家按教科书算法复算时要对得上。
func medianOfFloats(v []float64) float64 {
	if len(v) == 0 {
		return 0
	}
	s := append([]float64(nil), v...)
	sort.Float64s(s)
	n := len(s)
	if n%2 == 1 {
		return s[n/2]
	}
	return (s[n/2-1] + s[n/2]) / 2
}

func clampLimit(raw string, def int) int {
	n, err := strconv.Atoi(raw)
	if err != nil || n <= 0 {
		return def
	}
	// ⚠️ v0.17.0：上限 200 → **500**。
	//
	// 原来 200 条是够的（那只是「最近的行程列表」）；
	// 但 v0.17.0 加了**同线路跨行程对比**之后，200 条就不够了 ——
	// 窗口被日常通勤的短途占满，常跑的长途线路被挤出窗口，
	// 于是「这条线路只跑过 1 次，永远比不出来」。
	//
	// 实测：200 条里 49 条满足对比口径（≥20 公里且 Wh/km 有效），
	// 而每条线路仍然只有 1 次 —— 把窗口放到 500 条后，
	// 「张掖 - 孟连」这类常跑线路才有 2 次以上可比的样本。
	//
	// 代价：500 条行程约 420 KB JSON（局域网/Tailscale 上可忽略）。
	if n > 500 {
		return 500
	}
	return n
}

func summarize(buckets []*StatsBucket) *StatsBucket {
	s := &StatsBucket{}
	var kwh float64
	for _, b := range buckets {
		s.Km += b.Km
		s.Minutes += b.Minutes
		kwh += b.KwhUsed
		s.DriveCount += b.DriveCount
		s.ChargeKwh += b.ChargeKwh
		s.DcKwh += b.DcKwh
		s.AcKwh += b.AcKwh
		s.Cost += b.Cost
		s.ChargeCount += b.ChargeCount
	}
	if s.Km > 0.1 {
		s.KwhUsed = kwh
		v := kwh * 1000 / s.Km
		s.WhPerKm = &v
	}
	return s
}

// ---------- 驾驶风格画像（v0.27.0） ----------

// 驾驶风格画像的固定口径（v0.27.0）。
//
// ⚠️ 这些数是**行业惯例值，不是拟合出来的** —— 这一点会随 params 一起下发给 App，
// 用户能自己核对服务端怎么算的，不必相信一个玄学分数。
const (
	// drivingHardEventKmhPer2s 判定急刹车/急加速的 2 秒速度落差阈值（km/h）。
	//
	// 20 km/h / 2s ≈ 2.8 m/s²，实测 1 Hz 重采样后 dv2 的噪声底 p01 = −12.0，
	// 留了约 8σ 余量 —— 阈值再往低压，量化噪声会开始冒头。
	drivingHardEventKmhPer2s = 20
	// drivingOverSpeedKph 超速判定线（km/h）。高速限速 120，路面限速更高。
	drivingOverSpeedKph = 120
	// drivingNightStartHour / drivingNightEndHour 夜间时段（服务器时区的本地小时）。
	drivingNightStartHour = 22
	drivingNightEndHour   = 6
	// drivingMinDistanceKm 参与统计的**默认**最小行程里程（km）。
	//
	// ⚠️ 为什么不统计短途：
	//   一趟 1 公里的行程，里程表本身就有 ±0.1 公里的噪声，
	//   算出来的「每公里能耗」和「每百公里急刹车次数」会离谱到没法看。
	//
	// ⚠️ **v0.28.0（主控修正）：默认从 5 提到 20。**
	//
	// 复审实测：近 30 趟里有 **16 趟不到 20 公里**（只占总里程的 15%），
	// 而**低分的那几趟全是 6~9 公里的挪车**（停车场里挪车）——
	// 一次急刹就算「42 次/百公里」，直接扣满 30 分。
	// 于是**主角数字被停车场主导**：一个反映「你怎么开长途」的分数，
	// 实际上大部分由「你在商场挪车」决定。
	//
	// 现在默认只统计 **≥20 公里**的行程（App 端也会显式传 min_km=20），
	// 「挪车」不再进入统计。
	drivingMinDistanceKm = 20.0
	// drivingMinDistanceKmMin / Max 是 min_km 参数的合法范围（公里）。
	drivingMinDistanceKmMin = 1.0
	drivingMinDistanceKmMax = 200.0
	// drivingMaxWindowSpanSec 2 秒窗口允许的最大跨度（秒）。
	//
	// ⚠️ 为什么不是「严格 2 秒」也不是「不设限」：
	//   LAG(v,2) 拿的是「序列里往前数第二个桶」，它**不保证**真的隔了 2 秒。
	//   采样有断洞时两个桶可能隔 13 秒，那算出来的是十几秒的平均速率，不是 2 秒落差。
	//   名义窗口 2 秒 + 最多 10 秒数据断点（与能耗积分「丢弃 Δt>10 秒断点」同一条线）。
	//   实测标定：放宽到 13 秒 → drive_id=273 多 1 次假急加速（分数 21.1→20.0）；
	//   收紧到 11 秒 → drive_id=491 漏 2 次真急刹车。
	drivingMaxWindowSpanSec = 12
	// drivingDefaultLimit / drivingMaxLimit limit 参数的默认值与上限
	drivingDefaultLimit = 20
	drivingMaxLimit     = 60
)

// drivingProfileRaw 一趟行程的**原始计数**（SQL 直接算出来的那些）。
type drivingProfileRaw struct {
	DistanceKm float64
	HardBrake  int
	HardAccel  int
	OverN      int
	NValid     int // 有效 2 秒窗口数（超速占比的分母）
	NClean     int // 有完整 2 秒观测的窗口数（夜间占比的分母）
	NightN     int
	Kwh        float64
	// ⚠️ v0.29.0：毛耗电（不含回充抵扣）—— 评分用它
	KwhGross   float64
}

// drivingProfileDerived 派生指标 + 评分（纯函数，可单测）。
type drivingProfileDerived struct {
	Score         float64
	BrakePer100Km float64
	AccelPer100Km float64
	NightPct      float64
	OverSpeedPct  float64
	WhPerKm       float64
	// ⚠️ v0.29.0：毛耗电（Wh/km）—— 评分的能耗项用它
	WhPerKmGross  float64
}

// deriveDrivingProfile 由原始计数算出派生指标与评分（**纯函数**）。
//
// ⚠️ 为什么把「原始计数 → 派生指标」这一步留在 Go 侧：
//   这样评分公式能被单元测试直接覆盖（sql 里写死了就只能在库里验），
//   而且口径改了只要改这一处，SQL 只负责取数。
func deriveDrivingProfile(r drivingProfileRaw) drivingProfileDerived {
	var d drivingProfileDerived
	if r.DistanceKm > 0 {
		d.BrakePer100Km = 100.0 * float64(r.HardBrake) / r.DistanceKm
		d.AccelPer100Km = 100.0 * float64(r.HardAccel) / r.DistanceKm
		d.WhPerKm = r.Kwh * 1000.0 / r.DistanceKm
		// ⚠️ v0.29.0：毛耗电（不含回充抵扣）——
		// 净值会把下坡回充抵扣掉，于是下坡那两趟 wh_per_km 只有 21.6 / 3.6，
		// 「能耗」项不扣分 → **下坡白送 +15 分**。
		d.WhPerKmGross = r.KwhGross * 1000.0 / r.DistanceKm
	}
	// 夜间占比只统计**有完整 2 秒观测**的窗口（t 和 t−2 秒那一刻都有采样）：
	// 夜间问的是「这一刻算夜里吗」，跨了数据断洞的窗口说不清该按哪一秒算，
	// 把它们算进去只会让夜间比例失真。
	if r.NClean > 0 {
		d.NightPct = 100.0 * float64(r.NightN) / float64(r.NClean)
	}
	// 超速占比统计所有**有效**窗口（2~12 秒跨度），
	// 因为「这一刻速度超过 120」这件事本身不需要窗口是干净的。
	if r.NValid > 0 {
		d.OverSpeedPct = 100.0 * float64(r.OverN) / float64(r.NValid)
	}
	d.Score = drivingScore(d)
	return d
}

// drivingScore 驾驶风格评分公式（**纯函数**，0~100 分，越高越好）。
//
// ⚠️ 扣分制，每一项都有**封顶**，所以分数永远落在 0~100：
//
//	100
//	  - LEAST(30, brake_per_100km * 1.5)          急刹车
//	  - LEAST(20, accel_per_100km)                急加速
//	  - LEAST(20, GREATEST(0, over120_pct - 3))   超速
//	  - LEAST(15, GREATEST(0, wh_km - 150) / 5)   能耗
//	  - LEAST(15, night_pct * 0.2)                夜驾
//
// 封顶的意义：一趟「全是急刹车」的行程不能把分数压成负数，
// 也不能因为某一项爆表就抹掉其它项的信息。
func drivingScore(d drivingProfileDerived) float64 {
	s := 100.0
	s -= math.Min(30, d.BrakePer100Km*1.5)
	s -= math.Min(20, d.AccelPer100Km)
	s -= math.Min(20, math.Max(0, d.OverSpeedPct-3))
	// ⚠️ v0.29.0：能耗项用**毛耗电**（不含回充抵扣），否则下坡白送分。
	s -= math.Min(15, math.Max(0, d.WhPerKmGross-150)/5)
	s -= math.Min(15, d.NightPct*0.2)
	// ⚠️ 上下都夹住：负的中间量（理论上不会出现，但别让它变成 370 分）不能撑破上限。
	return math.Max(0, math.Min(100, s))
}

// parseDrivingProfileLimit 解析 limit 参数：默认 20，范围 [1, 60]。
//
// ⚠️ 为什么越界要报错而不是默默夹到边界：
//   默默夹断会让 App 以为「就这么多趟了」，
//   而真实原因只是被砍掉了 —— 那种错比直接报错危险得多。
func parseDrivingProfileLimit(raw string) (int, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return drivingDefaultLimit, nil
	}
	n, err := strconv.Atoi(raw)
	if err != nil {
		return 0, errors.New("limit 必须是整数")
	}
	if n < 1 || n > drivingMaxLimit {
		return 0, fmt.Errorf("limit 必须在 1..%d 之间，实际 %d", drivingMaxLimit, n)
	}
	return n, nil
}

// parseDrivingProfileMinKm 解析 min_km 参数：默认 20 公里，范围 [1, 200]。
//
// ⚠️ v0.28.0（主控修正）：默认从 5 提到 20 ——
// 近 30 趟里 16 趟不到 20 公里（占里程 15%），而低分那几趟全是 6~9 公里的挪车，
// 于是主角分数被「你在商场挪车」主导。
func parseDrivingProfileMinKm(s string) (float64, error) {
	if strings.TrimSpace(s) == "" {
		return drivingMinDistanceKm, nil
	}
	v, err := strconv.ParseFloat(s, 64)
	if err != nil || math.IsNaN(v) || math.IsInf(v, 0) {
		return 0, fmt.Errorf("min_km 不是数字")
	}
	if v < drivingMinDistanceKmMin || v > drivingMinDistanceKmMax {
		return 0, fmt.Errorf("min_km 必须在 %g..%g 之间，实际 %g",
			drivingMinDistanceKmMin, drivingMinDistanceKmMax, v)
	}
	return v, nil
}

// handleDrivingProfile 返回**最近 N 趟行程**的驾驶风格画像 + 汇总。
//
// ⚠️ v0.27.0。
//
// 入参：limit = 最近多少趟行程（默认 20，范围 [1,60]，越界 400）。
//
// 口径与阈值全部随 params 下发（行业惯例值，不是拟合出来的）。
//
// 性能：这条查询必须放在**事务**里跑（要临时调大 work_mem + 强制自定义计划），
// 理由与 handleRouteProfile 完全一样：
//   - 默认 work_mem=4MB 时大聚合会溢写到磁盘；
//   - PostgreSQL 对同一个 prepared statement 执行 5 次后会切「通用计划」，
//     通用计划里优化器不知道 drive_id 数组里有哪些值，
//     于是每行再 AND 一次数组 → Index Searches 从 N 涨到 N×200 → 慢一个数量级。
func handleDrivingProfile(w http.ResponseWriter, r *http.Request) {
	carID, err := strconv.Atoi(r.PathValue("car_id"))
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	limit, err := parseDrivingProfileLimit(r.URL.Query().Get("limit"))
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	// ⚠️ **v0.28.0（主控修正）：min_km 可调** ——
	// 默认 20 公里（排除充电站/商场里的挪车微行程），
	// 越界 400 而不是默默夹到边界（理由同 limit）。
	minKm, err := parseDrivingProfileMinKm(r.URL.Query().Get("min_km"))
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	// 时区用**服务端配置**的 TZ_NAME（默认 Asia/Shanghai），
	// 与 sqlStatsDrives 分日用的是同一个来源 —— 不硬编码。
	tz := getenv("TZ_NAME", "Asia/Shanghai")

	ctx := context.Background()
	tx, err := db.Begin(ctx)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	// 只读事务，回滚即可
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, "SET LOCAL work_mem = '128MB'"); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if _, err := tx.Exec(ctx, "SET LOCAL plan_cache_mode = force_custom_plan"); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	rows, err := tx.Query(ctx, sqlDrivingProfile, carID, tz, minKm, limit, drivingMaxWindowSpanSec)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()

	out := DrivingProfile{
		Drives: []DrivingProfileDrive{},
		Params: DrivingProfileParams{
			HardEventThresholdKmhPer2s: drivingHardEventKmhPer2s,
			OverSpeedKph:               drivingOverSpeedKph,
			NightStartHour:             drivingNightStartHour,
			NightEndHour:               drivingNightEndHour,
			MinDistanceKm:              minKm,
			Note:                       "阈值是行业惯例值，不是拟合出来的",
		},
	}
	var (
		scores    []float64
		whPerKms  []float64
		whPerKmsGross []float64
		nightPcts []float64
		overPcts  []float64
	)
	for rows.Next() {
		var (
			driveID     int
			startDate   time.Time
			distanceKm  float64
			durationMin int
			ascent      int
			descent     int
			hardBrake   int
			hardAccel   int
			overN       int
			nValid      int
			nClean      int
			nightN      int
			kwh         float64
			kwhGross    float64
		)
		// ⚠️ Scan 的顺序必须和 SELECT 的列顺序**严格一致**：
		//   drive_id, start_date, distance_km, duration_min, ascent, descent,
		//   hard_brake, hard_accel, over_n, n_valid, n_clean, night_n, kwh, kwh_gross
		if err := rows.Scan(&driveID, &startDate, &distanceKm, &durationMin, &ascent, &descent,
			&hardBrake, &hardAccel, &overN, &nValid, &nClean, &nightN, &kwh, &kwhGross); err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		raw := drivingProfileRaw{
			DistanceKm: distanceKm,
			HardBrake:  hardBrake,
			HardAccel:  hardAccel,
			OverN:      overN,
			NValid:     nValid,
			NClean:     nClean,
			NightN:     nightN,
			Kwh:        kwh,
			KwhGross:   kwhGross,
		}
		d := deriveDrivingProfile(raw)
		out.Drives = append(out.Drives, DrivingProfileDrive{
			DriveID:      driveID,
			StartDate:    startDate,
			// 里程保留 2 位小数（与 route_profile 同一口径），
			// 其余（分数、各比率）保留 1 位 —— 小数点后面拖 8 位只会让人怀疑服务端在乱算。
			DistanceKm:   round2(distanceKm),
			DurationMin:  durationMin,
			Score:        round1(d.Score),
			HardBrake:    hardBrake,
			HardAccel:    hardAccel,
			BrakePer100Km: round1(d.BrakePer100Km),
			AccelPer100Km: round1(d.AccelPer100Km),
			NightPct:     round1(d.NightPct),
			OverSpeedPct: round1(d.OverSpeedPct),
			WhPerKm:      round1(d.WhPerKm),
			// ⚠️ v0.29.0：毛耗电也下发（App 的能耗分项要用它）
			WhPerKmGross: round1(d.WhPerKmGross),
			DescentM:     descent,
			AscentM:      ascent,
		})
		scores = append(scores, d.Score)
		whPerKms = append(whPerKms, d.WhPerKm)
		whPerKmsGross = append(whPerKmsGross, d.WhPerKmGross)
		nightPcts = append(nightPcts, d.NightPct)
		overPcts = append(overPcts, d.OverSpeedPct)
	}
	if err := rows.Err(); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}

	sum := DrivingProfileSummary{DriveCount: len(out.Drives)}
	for i := range out.Drives {
		sum.TotalKm += out.Drives[i].DistanceKm
		sum.HardBrakeTotal += out.Drives[i].HardBrake
		sum.HardAccelTotal += out.Drives[i].HardAccel
		sum.AvgScore += out.Drives[i].Score
		sum.NightPctAvg += out.Drives[i].NightPct
		sum.OverSpeedPctAvg += out.Drives[i].OverSpeedPct
	}
	if n := len(out.Drives); n > 0 {
		sum.AvgScore = round1(sum.AvgScore / float64(n))
		sum.NightPctAvg = round1(sum.NightPctAvg / float64(n))
		sum.OverSpeedPctAvg = round1(sum.OverSpeedPctAvg / float64(n))
		sum.MedianScore = round1(medianOfFloats(scores))
		sum.WhPerKmMedian = round1(medianOfFloats(whPerKms))
		sum.WhPerKmMedianGross = round1(medianOfFloats(whPerKmsGross))
	}
	sum.TotalKm = round1(sum.TotalKm)
	out.Summary = sum
	writeJSON(w, out)
}

// round1 保留一位小数。
//
// ⚠️ v0.27.0。驾驶风格画像里的比率都要给人看，
// 拖 8 位小数只会让人怀疑服务端在乱算（也确实是浮点噪声）。
func round1(v float64) float64 {
	return math.Round(v*10) / 10
}

// carExists 这台车在 cars 表里存不存在。
//
// 用于区分「车存在但还没有任何位置数据」（正常状态）与「根本没有这台车」
// （配置错误）。两者原来都是同一个 404，App 无法区分。
func carExists(id int) bool {
	var one int
	err := db.QueryRow(context.Background(), `SELECT 1 FROM cars WHERE id = $1`, id).Scan(&one)
	return err == nil
}

// ---------- v0.28.0：常驻点（stay_points） ----------

const (
	// stayPointsDefaultMinHours min_hours 默认值（小时）
	stayPointsDefaultMinHours = 3.0
	// stayPointsMinHours / stayPointsMaxHours min_hours 允许的范围
	//
	// ⚠️ 下限 0.5 小时：低于半小时的间隙几乎都是「路上临时停一下 / 等红灯」，
	// 算进常驻点会把统计搅成一锅粥。
	// ⚠️ 上限 24 小时：超过一天的间隙说明数据断了（车被开走 / 换了司机 / 停放很久），
	// 这不是「常驻点」的语义，交给用户自己放宽阈值去看更合适。
	stayPointsMinHours = 0.5
	stayPointsMaxHours = 24.0
	// stayPointsLimit 最多返回几个常驻点
	stayPointsLimit = 12
)

// parseStayPointsMinHours 解析 min_hours 参数：默认 3 小时，范围 [0.5, 24]。
//
// ⚠️ 为什么越界要报错而不是默默夹到边界（与 parseDrivingProfileLimit 同一条理由）：
// 默默夹断会让 App 以为「就这么多数据了」，而真实原因只是被砍掉了。
func parseStayPointsMinHours(raw string) (float64, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return stayPointsDefaultMinHours, nil
	}
	v, err := strconv.ParseFloat(raw, 64)
	if err != nil {
		return 0, errors.New("min_hours 必须是数字")
	}
	if math.IsNaN(v) || math.IsInf(v, 0) {
		return 0, errors.New("min_hours 必须是有限数字")
	}
	if v < stayPointsMinHours || v > stayPointsMaxHours {
		return 0, fmt.Errorf("min_hours 必须在 %g..%g 之间，实际 %g",
			stayPointsMinHours, stayPointsMaxHours, v)
	}
	return v, nil
}

// handleStayPoints 返回**常驻点** —— 车平时停在哪、停多久。
//
// ⚠️ v0.28.0。
//
// 入参：min_hours = 最短停留小时数（默认 3，范围 [0.5, 24]，越界 400）。
//
// 口径：相邻两趟行程之间的时间间隙（详见 sqlStayPoints 的注释）。
// ⚠️ 返回的 Note 会随数据一起下发 —— App 直接展示它，
// 不要自己编「停车时长」这种话术（数据库里根本没有实时停车时长）。
func handleStayPoints(w http.ResponseWriter, r *http.Request) {
	carID, err := strconv.Atoi(r.PathValue("car_id"))
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	minHours, err := parseStayPointsMinHours(r.URL.Query().Get("min_hours"))
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}

	ctx := context.Background()
	// ⚠️ 这条查询**不需要**事务：drives（440 行）+ addresses（267 行）都是小表，
	// Seq Scan 比走索引回表还快，实测 < 5 ms。调 work_mem 对它没有意义。
	rows, err := db.Query(ctx, sqlStayPoints, carID, minHours, stayPointsLimit)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()

	out := StayPoints{
		Points: []StayPoint{},
		Note:   "停留时长由相邻行程的时间间隙推断 —— 车睡着后 TeslaMate 不再记位置，所以这不是实时停车时长",
	}
	for rows.Next() {
		var (
			id         int
			name       string
			lat        float64
			lng        float64
			visits     int
			totalHours float64
			avgHours   float64
			maxHours   float64
			firstSeen  time.Time
			lastSeen   time.Time
		)
		// ⚠️ Scan 的顺序必须和 SELECT 的列顺序**严格一致**：
		//   id, display_name, latitude, longitude, visits,
		//   total_hours, avg_hours, max_hours, first_seen, last_seen
		if err := rows.Scan(&id, &name, &lat, &lng, &visits,
			&totalHours, &avgHours, &maxHours, &firstSeen, &lastSeen); err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		out.Points = append(out.Points, StayPoint{
			AddressID:  id,
			Name:       name,
			Latitude:   lat,
			Longitude:  lng,
			Visits:     visits,
			TotalHours: round1(totalHours),
			AvgHours:   round1(avgHours),
			MaxHours:   round1(maxHours),
			FirstSeen:  firstSeen,
			LastSeen:   lastSeen,
		})
	}
	if err := rows.Err(); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	// ⚠️ **v0.28.0（主控修正 / 复审）：相距 ≤250 米的地址要合并。**
	//
	// 实测第 1 行「新湖路, 永丰社区…」（990.2 小时 / 20 次）与
	// 第 2 行「永丰社区…」（320.8 小时 / 7 次）是**同一个小区**，
	// 地理编码把同一个停车位置拆成了两行地址 —— 实测**相距 176 米**。
	// 不合并的话用户会读成「两个常驻点」，而 1311 小时是**重复计**的。
	//
	// ⚠️ **v0.29.0（复审 P1-1）：250 米这个阈值的依据要说准。**
	// 原来写的「全库唯一在 250 米内的一对就是它，下一对已跳到 1777 米」**不成立** ——
	// 全库 267 个地址里 ≤250 米的对远不止一对（9.4 / 12.2 / 25.1 / 32.1 / 37.9 / 44.5 / 46.8 /
	// 53.5 / 55.5 / 56.2 / 65.2 / 71.6 / 71.8 / 72.0 米…）。
	// 真正的事实是：**在常驻点集合里**需要合并的正是这一对，
	// 而它**本来就是同一个小区**（评审实测：行程 499 的终点就是「永丰社区, 西乡街道」，
	// 「新湖路, 永丰社区」是同一处被地理编码拆成的两行）。
	// 250 米是**保守**取值：比要合并的那一对（176 米）宽，
	// 合并的风险由「同小区」这个事实兜住。
	//
	// ⚠️ 已知风险：这是**链式聚类**（比对已合并点的首坐标），
	// A-B≤250、B-C≤250、A-C>250 时会把 A、C 并上。本库未触发。
	out.Points = mergeStayPointsByDistance(out.Points, 250.0)
	writeJSON(w, out)
}

// mergeStayPointsByDistance 把相距不超过 maxMeters 的停留点合并成一个。
//
// ⚠️ 合并口径：
//   · visits / total_hours **相加**（本来就是同一处的停留）；
//   · max_hours 取**较大**的（最长的一次停多久）；
//   · avg_hours 用「合并后的总时长 ÷ 合并后的次数」重算（不能把两个均值再平均）；
//   · name 取**停得更多**的那一行的（更可能是用户认识的那个名字）。
func mergeStayPointsByDistance(points []StayPoint, maxMeters float64) []StayPoint {
	if len(points) < 2 {
		return points
	}
	merged := make([]StayPoint, 0, len(points))
	for _, p := range points {
		hit := -1
		for i := range merged {
			if stayPointsNear(merged[i], p, maxMeters) {
				hit = i
				break
			}
		}
		if hit < 0 {
			merged = append(merged, p)
			continue
		}
		m := &merged[hit]
		m.Visits += p.Visits
		m.TotalHours = round1(m.TotalHours + p.TotalHours)
		if p.MaxHours > m.MaxHours {
			m.MaxHours = p.MaxHours
		}
		if m.Visits > 0 {
			m.AvgHours = round1(m.TotalHours / float64(m.Visits))
		}
		// 名字保留**先出现**的那一个 ——
		// points 是按累计时长倒序来的，先出现的那个停得更久、更可能是用户认识的名字。
		if p.FirstSeen.Before(m.FirstSeen) {
			m.FirstSeen = p.FirstSeen
		}
		if p.LastSeen.After(m.LastSeen) {
			m.LastSeen = p.LastSeen
		}
	}
	return merged
}

// stayPointsNear 两个停留点是否在 maxMeters 之内（球面距离）。
func stayPointsNear(a, b StayPoint, maxMeters float64) bool {
	const earthRadius = 6371000.0
	lat1 := a.Latitude * math.Pi / 180
	lat2 := b.Latitude * math.Pi / 180
	dLat := (b.Latitude - a.Latitude) * math.Pi / 180
	dLng := (b.Longitude - a.Longitude) * math.Pi / 180
	h := math.Sin(dLat/2)*math.Sin(dLat/2) +
		math.Cos(lat1)*math.Cos(lat2)*math.Sin(dLng/2)*math.Sin(dLng/2)
	d := 2 * earthRadius * math.Asin(math.Sqrt(math.Min(1, h)))
	return d <= maxMeters
}

// ---------- v0.28.0：能耗-车速曲线（energy_profile） ----------

const (
	// energyBandWidthKph 一档的宽度（km/h）。
	//
	// ⚠️ 为什么是 10 而不是 15：实测 10 km/h 一档（5/15/25/…）能把
	// 每一档都凑够样本（最少 130 个分箱 / 65 公里），
	// 15 km/h 一档会让 0–15 km/h 那一档只剩 1/3 的样本量。
	energyBandWidthKph = 10
	// energyBandMinBins / energyBandMinKm 一档要返回所需的最小样本量
	//
	// ⚠️ 为什么宁可少一档，也不给一个噪声数：
	// 5 个 1 km 分箱 = 5 公里，一档 Wh/km 的统计误差能到 ±20%，
	// 画在图上会变成一条假的「U 型曲线」。样本不够就**不返回**。
	energyBandMinBins = 5
	energyBandMinKm   = 30.0
	// energyBandCenterOffsetKph 分档键的中心偏移。
	//
	// ⚠️ SQL 里的分档键是 floor(speed/10)*10+5（以 5 km/h 为中心、10 km/h 一档：
	// 5→[0,10)  15→[10,20)  25→[20,30) …… 135→[130,140)），
	// 这里用它反推 App 侧的标签档位（0/15/25/…/135/145+）。
	energyBandCenterOffsetKph = 5
)

// energyBandEdges App 侧的速度档位边界。
//
// ⚠️ **v0.28.0（主控修正）：边界必须是**真实区间**，不是评审员表里的「档中心」。**
//
// 评审员的实测表用的是 10 km/h 宽的分箱、键是**中心**（5/15/25/…），
// 于是「键 5 那一档」的真实区间是 **[0, 10)**，
// 而按中心当边界发出去会变成「0–15」—— **标签比真实区间高了 5 km/h**，
// 用户读到「45–55 km/h 最省电」，而那一档其实是 40–50。
//
// 所以这里的边界是**真实的档位边界**：0/10/20/…/130/140。
// 分箱本身没变（`energyBandIndexForKey` 用的还是键 5/15/25/…），
// 变的只是**标签**。
//
// ⚠️ 为什么最后一档是「130+」而不是某个具体数字：
// 高速段（≥140 km/h）的分箱本来就少，再切细就没有样本了；
// 合成一个开区间的高档，把所有更高速度都并进来，结论（高速能耗急剧劣化）才立得住。
func energyBandEdges() []int {
	return []int{0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130, 140}
}

// energyBandIndexForKey 把 SQL 返回的分档键（5/15/25/…）映射到 App 侧的档位下标。
//
// ⚠️ 键 ≥ 135 的（含 ≥140 km/h 的更高速分箱）**全部并进最后一档**。
func energyBandIndexForKey(key int) int {
	edges := energyBandEdges()
	last := len(edges) - 2 // 最后一档的下标
	idx := (key - energyBandCenterOffsetKph) / energyBandWidthKph
	if idx < 0 {
		return 0
	}
	if idx > last {
		return last
	}
	return idx
}

// energyBandLabel 返回某一档的 (下界, 上界) 速度（km/h）。
//
// ⚠️ v0.28.0（主控修正）：**最后一档是开区间**（上界返回 0，App 渲染成「130+」）。
// 最后一档把 ≥140 km/h 的分箱也并进来了（分箱少，再切细就没样本了），
// 标成一个具体上界会**低估**那一档的速度范围。
func energyBandLabel(idx int) (int, int) {
	edges := energyBandEdges()
	if idx < 0 {
		idx = 0
	}
	last := len(edges) - 2
	if idx > last {
		idx = last
	}
	if idx == last {
		// 开区间：上界 0 表示「及以上」（App 的 `EnergyBand.label` 按这个渲染）
		return edges[idx], 0
	}
	return edges[idx], edges[idx+1]
}

// energyBandSufficient 这一档的样本量够不够（不够就不返回）。
func energyBandSufficient(bins int, km float64) bool {
	return bins >= energyBandMinBins && km >= energyBandMinKm
}

// energyBandRaw SQL 出来的一档原始聚合值。
type energyBandRaw struct {
	Key  int
	Bins int
	Km   float64
	Kwh  float64
	// ⚠️ v0.29.0：毛耗电（不含回充抵扣）—— 见 sqlEnergyProfile 的说明
	KwhGross float64
}

// energyBandMerged 合并后的一档（能量与里程要一起加，Wh/km 再算 —— 不能先算再平均）。
type energyBandMerged struct {
	Idx  int
	Bins int
	Km   float64
	Kwh  float64
	// ⚠️ v0.29.0：毛耗电（不含回充抵扣）
	KwhGross float64
}

// mergeEnergyBands 把 SQL 的分档合并成 App 侧的档位。
//
// ⚠️ 为什么 Wh/km 要**最后**才算：
// 各档的 Wh/km 是比值，比值不能直接平均（必须按里程加权）。
// 所以先加 kWh 和 km，最后再相除。
func mergeEnergyBands(rows []energyBandRaw) []energyBandMerged {
	edges := energyBandEdges()
	n := len(edges) - 1
	merged := make([]energyBandMerged, n)
	for i := range merged {
		merged[i] = energyBandMerged{Idx: i}
	}
	for _, r := range rows {
		idx := energyBandIndexForKey(r.Key)
		merged[idx].Bins += r.Bins
		merged[idx].Km += r.Km
		merged[idx].Kwh += r.Kwh
		merged[idx].KwhGross += r.KwhGross
	}
	return merged
}

// handleEnergyProfile 返回**能耗-车速曲线** —— 这台车在不同车速下真实耗多少电。
//
// ⚠️ v0.28.0。
//
// 官方 App 只能给**平均** Wh/km；这里给的是一条曲线。
// 口径：车轮功率梯形积分（1 km 分箱，按箱间里程表跨度算距离），详见 sqlEnergyProfile。
//
// 性能：这条查询必须放在**事务**里跑（要临时调大 work_mem + 强制自定义计划），
// 理由与 handleRouteProfile / handleDrivingProfile 完全一样：
// 通用计划里优化器不知道 drive_id 数组里有哪些值，
// 于是每行再 AND 一次数组 → Index Searches 从 N 涨到 N×N → 慢一个数量级。
func handleEnergyProfile(w http.ResponseWriter, r *http.Request) {
	carID, err := strconv.Atoi(r.PathValue("car_id"))
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}

	ctx := context.Background()
	tx, err := db.Begin(ctx)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	// 只读事务，回滚即可
	defer tx.Rollback(ctx)
	// ⚠️ work_mem 这里用 **256MB**（route_profile 用的是 128MB）：
	// 这条查询要把 162 万个 positions 点按 (drive_id, date) 排一次序，
	// 实测排序峰值 **119 MB** —— 128MB 只剩 9% 余量，
	// 数据量稍微涨一点就会**溢写到磁盘**（external sort），耗时翻几倍。
	// 256MB 把这个悬崖挪走；SET LOCAL 只在本事务内生效，不会污染连接池。
	if _, err := tx.Exec(ctx, "SET LOCAL work_mem = '256MB'"); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if _, err := tx.Exec(ctx, "SET LOCAL plan_cache_mode = force_custom_plan"); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}

	rows, err := tx.Query(ctx, sqlEnergyProfile, carID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()

	var raws []energyBandRaw
	var totalKwh float64
	var totalKwhGross float64
	for rows.Next() {
		var (
			key      int
			bins     int
			km       float64
			kwh      float64
			kwhGross float64
		)
		// ⚠️ Scan 的顺序必须和 SELECT 的列顺序**严格一致**：
		//   band_key, n_bins, km, kwh_total, kwh_gross_total
		if err := rows.Scan(&key, &bins, &km, &kwh, &kwhGross); err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		raws = append(raws, energyBandRaw{Key: key, Bins: bins, Km: km, Kwh: kwh, KwhGross: kwhGross})
		totalKwh += kwh
		totalKwhGross += kwhGross
	}
	if err := rows.Err(); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}

	// 总里程用 drives.distance（公里表口径）—— 与 App 其它页面的「总里程」保持一致
	var totalKm float64
	if err := tx.QueryRow(ctx,
		`SELECT COALESCE(sum(distance), 0)::float8 FROM drives WHERE car_id = $1`,
		carID).Scan(&totalKm); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}

	out := EnergyProfile{
		Bands: []EnergyBand{},
		Note: "车轮功率梯形积分（1 km 分箱，按箱内里程表跨度算距离）；" +
			"wh_per_km 是**净值**（下坡回充会抵扣），wh_per_km_gross 是**毛耗电**（不含回充抵扣）—— " +
			"「最省电的速度」必须看毛耗电，净值会被下坡拉出假象",
	}
	var best *EnergyBestBand
	for _, m := range mergeEnergyBands(raws) {
		if !energyBandSufficient(m.Bins, m.Km) {
			continue
		}
		whPerKm := m.Kwh / m.Km * 1000.0
		whPerKmGross := m.KwhGross / m.Km * 1000.0
		minKph, maxKph := energyBandLabel(m.Idx)
		out.Bands = append(out.Bands, EnergyBand{
			MinKph:  minKph,
			MaxKph:  maxKph,
			WhPerKm: round1(whPerKm),
			// ⚠️ v0.29.0：毛耗电一起下发（App 的曲线与「最省电档」都按它）
			WhPerKmGross: round1(whPerKmGross),
			Km:           round1(m.Km),
			Bins:         m.Bins,
		})
		// ⚠️ **v0.29.0（复审 P0-1）：best_band 按**毛耗电**选，不是按净值。**
		//
		// 评审复算：净值口径下「40–50 km/h = 118.8 Wh/km」看着最省电，
		// 但那是**一次下坡 + 一次长途**的产物 —— 物理上不可能（这台 AWD Performance 做不到）。
		// 扣掉回充后那一档是 215.3，而**最低档变成 100–110 km/h**。
		//
		// 所以「最省电的速度」必须按**不含回充抵扣**的毛耗电来选。
		if best == nil || whPerKmGross < best.WhPerKmGross {
			// ⚠️ v0.28.0（主控修正）：带上 km 与 bins ——
			// App 要显示「这一档跑了多少公里」，还要靠 (min,max) 认出哪一档是最省电的。
			best = &EnergyBestBand{
				MinKph:  minKph,
				MaxKph:  maxKph,
				WhPerKm: round1(whPerKm),
				// ⚠️ v0.29.0：App 展示的主角用毛耗电
				WhPerKmGross: round1(whPerKmGross),
				Km:           round1(m.Km),
				Bins:         m.Bins,
			}
		}
	}
	out.BestBand = best
	out.TotalKm = round1(totalKm)
	if totalKm > 0 {
		out.OverallWhPerKm = round1(totalKwh / totalKm * 1000.0)
		out.OverallWhPerKmGross = round1(totalKwhGross / totalKm * 1000.0)
	}
	writeJSON(w, out)
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	enc.Encode(v)
}
