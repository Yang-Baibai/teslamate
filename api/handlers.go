package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"net/url"
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
			&c.Place, &c.MapLat, &c.MapLng, &c.IsDC,
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
			&c.CableType, &c.ChargerBrand,
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
	switch r.URL.Query().Get("period") {
	case "week":
		granularity, buckets = "day", 7
	case "year":
		granularity, buckets = "month", 12
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
	if granularity == "day" {
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
	for rows.Next() {
		p := &BatteryPoint{}
		if err := rows.Scan(&p.Date, &p.CapacityKwh, &p.EndSoc); err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		if p.CapacityKwh != nil {
			if h.MaxCapacityKwh == nil || *p.CapacityKwh > *h.MaxCapacityKwh {
				h.MaxCapacityKwh = p.CapacityKwh
			}
			last = p.CapacityKwh
		}
		h.Series = append(h.Series, p)
	}
	h.CurrentCapacityKwh = last
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

// carExists 这台车在 cars 表里存不存在。
//
// 用于区分「车存在但还没有任何位置数据」（正常状态）与「根本没有这台车」
// （配置错误）。两者原来都是同一个 404，App 无法区分。
func carExists(id int) bool {
	var one int
	err := db.QueryRow(context.Background(), `SELECT 1 FROM cars WHERE id = $1`, id).Scan(&one)
	return err == nil
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	enc.Encode(v)
}
