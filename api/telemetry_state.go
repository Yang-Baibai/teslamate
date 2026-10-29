package main

// ---------- Fleet Telemetry 的 V 字段（车辆实时数据）----------
//
// 为什么它重要：
//
//	`vehicle_data` 端点要**我们主动去拉**，2 分钟一次；
//	车睡着时还拉不到（Fleet API 返回 408）。
//	⇒ 车停在家里的那 9 个小时，「锁没锁」一直是**未知**（locked = null），
//	  而「锁没锁」是车主第一诉求。
//
//	而 Fleet Telemetry 是**车主动推**的：车连上时推一轮，之后按间隔推。
//	⇒ 哪怕车已经断线，broker 上的 retained 消息里仍然留着**断线前的最后已知值**。
//	（实测：本机 broker 上确实留着 `v/Locked true`。）
//
// ⚠️ **时间诚实性**（本文件的第一原则）：
//
//	MQTT 的 retained 消息**没有采集时间**（载荷就是裸值）。
//	broker 会在订阅者上线时**重发** retained ⇒ 「收到时刻」≠「采集时刻」。
//	⇒ 绝不能拿「收到时刻」当「数据有多新」——
//	  否则 API 一重启，9 小时前的旧值会显示成「刚刚」。
//
//	判据见 telemetryFieldFreshness：
//
//	- 车**在线**（最近 CONNECTED 晚于最近 DISCONNECTED）⇒ received_at 可信；
//	- 车**离线**且 received_at 晚于「最近 DISCONNECTED」
//	  ⇒ 这是断线后重放的 retained
//	  ⇒ 真实采集时刻 = **车断线的那个时刻**（值就是断线前的最后已知值）；
//	- 车离线且 received_at 早于断线时刻 ⇒ 那是断线前收到的 ⇒ received_at 可信；
//	- 连 CONNECTED/DISCONNECTED 都没收到过 ⇒ **不知道**，不编时刻。

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
)

// --------------------------------------------------------------------------
// 字段名（官方 proto，**逐字核对**过）
// --------------------------------------------------------------------------

// ⚠️ 字段名必须与官方 `vehicle_data.proto` 里的 `Field` 枚举**完全一致** ——
//
//	写错**不报错**，只是那个字段永远不来（静默失效）。
//	出处：teslamotors/fleet-telemetry → protos/vehicle_data.proto
//
//	（本文件目前只消费 Locked。其余字段名先记下来，
//	  等扩大遥测配置时直接用 —— 不重新查、不凭记忆写。）
var telemetryFieldNames = map[string]string{
	"Locked":              "锁车状态",
	"Soc":                 "电量",
	"EstBatteryRange":     "估算续航",
	"ChargeAmps":          "充电电流",
	"DetailedChargeState": "充电状态",
	"Odometer":            "车端里程表",
	"TpmsPressureFl":      "左前胎压",
	"TpmsPressureFr":      "右前胎压",
	"TpmsPressureRl":      "左后胎压",
	"TpmsPressureRr":      "右后胎压",
	"FdWindow":            "左前车窗",
	"FpWindow":            "右前车窗",
	"RdWindow":            "左后车窗",
	"RpWindow":            "右后车窗",
	"DoorState":           "车门状态",
	"ChargeLimitSoc":      "充电上限",
	"InsideTemp":          "车内温度",
	"OutsideTemp":         "车外温度",
	"SentryMode":          "哨兵模式",
}

// --------------------------------------------------------------------------
// 值的解析
// --------------------------------------------------------------------------

// telemetryFieldValue 一个 V 字段的解析结果。
//
// ⚠️ **三个可空字段是刻意的**：官方明确警告过字段类型会随固件版本漂移，
//
//	"a float value like the vehicle's speed might be received as 12.3 (numeric)
//	 in one version and as \"12.3\" (string) in another version"
//
//	（出处：teslamotors/fleet-telemetry → datastore/mqtt/README.md）
//
//	实测本机也确实见过字符串形态：`DetailedChargeState` 收到的是
//	`"DetailedChargeStateDisconnected"`（**带引号**），而 `Soc` 是裸数字。
//	⇒ 解析必须**三种形态都认**，而且识别不出时**保留原文**，不丢数据。
type telemetryFieldValue struct {
	Text string   // 原始文本（字符串去掉引号；数字/布尔是字面量；对象是 JSON）
	Num  *float64 // 能当数字看时填这里
	Bool *bool    // 能当布尔看时填这里
}

// parseTelemetryValue 解析一个 V 字段的载荷。
//
// ⚠️ 认三种形态：**裸布尔** / **裸数字** / **带引号的字符串**；
// 其余（对象、数组）原样保留 JSON 文本。
//
// ⚠️ **类型漂移**：字符串形态的 "12.3" 也要能当数字用，"true" 也要能当布尔用 ——
//
//	同一字段在两次推送里可能是不同形态（固件升级就可能变）。
func parseTelemetryValue(payload []byte) telemetryFieldValue {
	raw := strings.TrimSpace(string(payload))
	if raw == "" {
		return telemetryFieldValue{}
	}
	// ① 裸布尔
	if raw == "true" || raw == "false" {
		b := raw == "true"
		return telemetryFieldValue{Text: raw, Bool: &b}
	}
	// ② 裸数字
	if n, err := parseFloatStrict(raw); err == nil {
		return telemetryFieldValue{Text: raw, Num: &n}
	}
	// ③ 带引号的字符串
	if len(raw) >= 2 && raw[0] == '"' && raw[len(raw)-1] == '"' {
		var s string
		if err := json.Unmarshal([]byte(raw), &s); err == nil {
			out := telemetryFieldValue{Text: s}
			// ⚠️ 类型漂移：字符串形态的数字/布尔也要认出来。
			if n, err := parseFloatStrict(s); err == nil {
				out.Num = &n
			} else if s == "true" || s == "false" {
				b := s == "true"
				out.Bool = &b
			}
			return out
		}
	}
	// ④ 对象 / 数组（DoorState 之类）⇒ 原样留着，不丢。
	return telemetryFieldValue{Text: raw}
}

// --------------------------------------------------------------------------
// 落库
// --------------------------------------------------------------------------

// telemetryStoreField 落一个 V 字段的最新值。
//
// ⚠️ **只存最新值**（UPSERT），不存历史 ——
//
//	这几个字段（锁车 / 电量 / 胎压）问的都是「**现在**怎么样」，
//	不是「历史上怎么样」。历史是 TeslaMate 的活（它已经在记了）。
//
// ⚠️ **时间戳只有一个 `received_at`**（我们收到这条的时刻）。
//
//	看起来少了「车端采集时刻」，但那个时刻**根本拿不到**
//	（MQTT 的 metrics 载荷是裸值，不带 created_at）。
//	⇒ 「这个值到底有多新」靠 connectivity 表反推，见 telemetryFieldFreshness。
func telemetryStoreField(vin, field string, payload []byte) {
	v := parseTelemetryValue(payload)
	_, err := db.Exec(context.Background(), `
INSERT INTO tm_telemetry_vehicle_data (vin, field, value_text, value_num, value_bool, received_at)
VALUES ($1,$2,$3,$4,$5, now())
ON CONFLICT (vin, field) DO UPDATE SET
	value_text  = EXCLUDED.value_text,
	value_num   = EXCLUDED.value_num,
	value_bool  = EXCLUDED.value_bool,
	received_at = EXCLUDED.received_at`,
		vin, field, v.Text, v.Num, v.Bool)
	if err != nil {
		log.Printf("⚠️  落遥测字段失败 %s/%s：%v", vin, field, err)
	}
}

// --------------------------------------------------------------------------
// 读取
// --------------------------------------------------------------------------

// telemetryFieldRecord 一个 V 字段的原始记录。
type telemetryFieldRecord struct {
	Field      string
	ValueText  string
	ValueNum   *float64
	ValueBool  *bool
	ReceivedAt time.Time
}

// telemetryFieldView 一个 V 字段（对外）。
type telemetryFieldView struct {
	Field string `json:"field"`
	// Label 这个字段的中文名（官方 proto 里有 ⇒ 知道就说；不知道就空）。
	Label string `json:"label"`
	// Text 原始文本（字符串去引号；数字/布尔是字面量）。
	Text string `json:"text"`
	// Num / Bool 类型化后的值。**认不出类型就是 null**，不硬塞。
	Num  *float64   `json:"num"`
	Bool *bool      `json:"bool"`
	At   *time.Time `json:"at"`
	// Stale 这个值是不是「车断线前的最后已知值」。
	//
	// ⚠️ **必须外露**：App / 飞书要能说清「这是 9 小时前的状态」，
	// 而不是把旧值当现在的样子报出去。
	Stale bool `json:"stale"`
}

// telemetryFieldFreshness 算出一个 V 字段的**真实采集时刻**与是否陈旧。
//
// ⚠️ 这是**纯函数**（不碰 DB）⇒ 可以直接单测，而且**必须**单测 ——
// 这里错一次，App 上就会出现「9 小时前的数据说成刚刚」。
//
// 参数：
//   - receivedAt：这条消息被我们收到的时刻
//   - lastConnected：车最近一次 CONNECTED 的时刻（nil = 从没连过）
//   - lastDisconnected：车最近一次 DISCONNECTED 的时刻（nil = 从没断过）
func telemetryFieldFreshness(receivedAt time.Time, lastConnected, lastDisconnected *time.Time) (at *time.Time, stale bool) {
	switch {
	case lastConnected != nil && (lastDisconnected == nil || lastConnected.After(*lastDisconnected)):
		// 车**在线**：收到的就是最新的。
		t := receivedAt
		return &t, false

	case lastDisconnected != nil && receivedAt.After(*lastDisconnected):
		// 车**离线**，而这条是断线**之后**收到的
		// ⇒ 这是 broker 重发的 retained
		// ⇒ 真实时刻 = **车断线的那个时刻**（值就是断线前的最后已知值）。
		t := *lastDisconnected
		return &t, true

	case lastDisconnected != nil:
		// 车离线，但这条是断线**之前**收到的 ⇒ received_at 就是采集时刻。
		t := receivedAt
		return &t, false

	default:
		// 连连接状态都没有 ⇒ 只知道「收到过」，**不知道有多新**。
		// ⇒ 保守标 stale，不冒充新鲜。
		t := receivedAt
		return &t, true
	}
}

// telemetryConnectivityTimes 取这台车最近一次 CONNECTED / DISCONNECTED 的时刻。
//
// ⚠️ `created_at` 可能为 NULL（消息里没带 CreatedAt）⇒ 用 `first_seen_at` 兜底
// （那是我们收到这条的时刻，至少不比 NULL 差）。
func telemetryConnectivityTimes(ctx context.Context, vin string) (lastConnected, lastDisconnected *time.Time, err error) {
	var c, d sql.NullTime
	err = db.QueryRow(ctx, `
SELECT
	MAX(COALESCE(created_at, first_seen_at)) FILTER (WHERE status = 'CONNECTED'),
	MAX(COALESCE(created_at, first_seen_at)) FILTER (WHERE status = 'DISCONNECTED')
FROM tm_telemetry_connectivity WHERE vin = $1`, vin).Scan(&c, &d)
	if err != nil {
		return nil, nil, err
	}
	if c.Valid {
		t := c.Time
		lastConnected = &t
	}
	if d.Valid {
		t := d.Time
		lastDisconnected = &t
	}
	return lastConnected, lastDisconnected, nil
}

// loadTelemetryFields 读这台车所有 V 字段的最新值。
func loadTelemetryFields(ctx context.Context, vin string) ([]telemetryFieldView, error) {
	rows, err := db.Query(ctx, `
SELECT field, value_text, value_num, value_bool, received_at
FROM tm_telemetry_vehicle_data
WHERE vin = $1`, vin)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	lastConnected, lastDisconnected, err := telemetryConnectivityTimes(ctx, vin)
	if err != nil {
		return nil, err
	}

	out := []telemetryFieldView{}
	for rows.Next() {
		var rec telemetryFieldRecord
		if err := rows.Scan(&rec.Field, &rec.ValueText, &rec.ValueNum, &rec.ValueBool, &rec.ReceivedAt); err != nil {
			return nil, err
		}
		at, stale := telemetryFieldFreshness(rec.ReceivedAt, lastConnected, lastDisconnected)
		out = append(out, telemetryFieldView{
			Field: rec.Field,
			Label: telemetryFieldNames[rec.Field],
			Text:  rec.ValueText,
			Num:   rec.ValueNum,
			Bool:  rec.ValueBool,
			At:    at,
			Stale: stale,
		})
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	// ⚠️ 排序固定 ⇒ 同一份数据每次给一样的顺序（App 渲染稳定、便于对比）。
	sort.Slice(out, func(i, j int) bool { return out[i].Field < out[j].Field })
	return out, nil
}

// loadTelemetryField 读一个 V 字段。
func loadTelemetryField(ctx context.Context, vin, field string) (*telemetryFieldView, error) {
	rows, err := loadTelemetryFields(ctx, vin)
	if err != nil {
		return nil, err
	}
	for i := range rows {
		if rows[i].Field == field {
			return &rows[i], nil
		}
	}
	return nil, nil
}

// telemetryLockState 遥测里的**锁车状态**。
//
// 返回 nil = 遥测里没有这个字段 / 解析不出来 ⇒ **不知道**。
//
// ⚠️ 车断线后 broker 重发的 retained **也是值**，而且是**真的**（断线前的最后状态）。
// 所以这不是「不可信」，只是**旧** ⇒ 用 At/Stale 如实标出来，而不是丢掉。
func telemetryLockState(ctx context.Context, vin string) *telemetryFieldView {
	f, err := loadTelemetryField(ctx, vin, "Locked")
	if err != nil {
		log.Printf("⚠️  读遥测锁车状态失败：%v", err)
		return nil
	}
	if f == nil || f.Bool == nil {
		return nil
	}
	return f
}

// --------------------------------------------------------------------------
// 对外端点
// --------------------------------------------------------------------------

// telemetryStateResponse GET /api/v1/cars/{id}/telemetry_state 的返回。
type telemetryStateResponse struct {
	// ⚠️ Fields 一定是数组（空数组也是数组）——
	//    null 与 [] 的区别是「没这个概念」与「有但为空」。
	Fields []telemetryFieldView `json:"fields"`
	// Connected 车端此刻是否在线。
	Connected bool `json:"connected"`
	// EverConnected 遥测流**曾经**接上过吗（跨进程重启有效）。
	EverConnected bool `json:"ever_connected"`
	// LastConnectedAt / LastDisconnectedAt 车最近一次连上/断开的时刻。
	// ⚠️ 没有它们，调用方**无法判断**某个字段的值有多旧。
	LastConnectedAt    *time.Time `json:"last_connected_at"`
	LastDisconnectedAt *time.Time `json:"last_disconnected_at"`
}

// handleTelemetryState GET /api/v1/cars/{id}/telemetry_state
//
// 给 App 与 Hermes（飞书）看的**车主动推的实时数据**入口。
//
// ⚠️ 与 `/alerts` 不同：这里给的是**车辆状态**（锁车 / 电量 / 胎压…），
// 故障码那条路在 `/alerts`。
func handleTelemetryState(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "车 id 无效")
		return
	}
	vin, err := carVIN(r.Context(), id)
	if err != nil {
		writeErr(w, http.StatusNotFound, err.Error())
		return
	}
	fields, err := loadTelemetryFields(r.Context(), vin)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	lastConnected, lastDisconnected, err := telemetryConnectivityTimes(r.Context(), vin)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	out := telemetryStateResponse{
		Fields:             fields,
		Connected:          lastConnected != nil && (lastDisconnected == nil || lastConnected.After(*lastDisconnected)),
		LastConnectedAt:    lastConnected,
		LastDisconnectedAt: lastDisconnected,
	}
	// EverConnected 用库里历史判（进程重启后计数器归零，会误判成「没接过」）。
	var ever bool
	if err := db.QueryRow(r.Context(), `
SELECT EXISTS(
	SELECT 1 FROM tm_telemetry_connectivity WHERE vin = $1 AND status = 'CONNECTED'
)`, vin).Scan(&ever); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	out.EverConnected = ever
	writeJSON(w, out)
}

// --------------------------------------------------------------------------
// 小工具（单独抽出来是为了能单测）
// --------------------------------------------------------------------------

// parseFloatStrict 严格解析浮点数。
//
// ⚠️ **不能用 `strconv.ParseFloat` 的默认行为**：
// 它接受 "Inf" / "NaN" / "1_000"（下划线分隔符在 Go 1.13+ 的 ParseFloat 里被接受）。
// 而这些值一旦进了 `value_num DOUBLE PRECISION`，Postgres 会存成
// 'Infinity' / 'NaN' —— 那不是「读不到」，是**存了脏东西**。
func parseFloatStrict(s string) (float64, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0, fmt.Errorf("空串")
	}
	for _, r := range s {
		if r != '+' && r != '-' && r != '.' && r != 'e' && r != 'E' &&
			!(r >= '0' && r <= '9') {
			return 0, fmt.Errorf("不是合法数字：%q", s)
		}
	}
	return strconv.ParseFloat(s, 64)
}
