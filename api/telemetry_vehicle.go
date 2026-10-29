package main

// --------------------------------------------------------------------------
// 遥测「车辆实况」的语义解析（v0.81.0）
//
// ⚠️ **反幻觉铁律在这里落地**：
//
//	官方 `protos/vehicle_data.proto` 是**唯一权威**。
//	我们只翻译「官方逐字写过的」枚举名与消息字段名；
//	**认不出来的一律摊原文**（Raw 字段），绝不按前缀猜含义。
//
//	这份文件里的每个映射表都注明了官方出处（枚举名 / 消息名 / 编号）。
// --------------------------------------------------------------------------

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// --------------------------------------------------------------------------
// 官方枚举 → 中文
// --------------------------------------------------------------------------

// telemetryWindowStates 官方 `enum WindowState` 的中文。
//
// ⚠️ 编号来自 `protos/vehicle_data.proto`：
//
//	WindowStateUnknown = 0 / Closed = 1 / PartiallyOpen = 2 / Opened = 3
//
// key 用**规范编号的字符串**（值本身也是数字，但这里统一成字符串便于当 map key）。
var telemetryWindowStates = map[string]string{
	"0": "状态未知",
	"1": "已关闭",
	"2": "半开",
	"3": "已打开",
}

// telemetryWindowStateNames 官方枚举名 → 规范编号。
//
// ⚠️ 官方 protojson 按枚举**名**编码 —— 我们实测 `DetailedChargeState`
//
//	收到的是 `"DetailedChargeStateDisconnected"`（**带引号**）。
//	这里同时收「带前缀的官方名」与「裸名」（固件偶尔省掉前缀）。
var telemetryWindowStateNames = map[string]string{
	"WindowStateUnknown":       "0",
	"WindowStateClosed":        "1",
	"WindowStatePartiallyOpen": "2",
	"WindowStateOpened":        "3",
	"Unknown":                  "0",
	"Closed":                   "1",
	"PartiallyOpen":            "2",
	"Opened":                   "3",
}

// telemetrySentryStates 官方 `enum SentryModeState` 的中文。
//
// ⚠️ 编号来自官方 proto：
//
//	Unknown = 0 / Off = 1 / Idle = 2 / Armed = 3 / Aware = 4 / Panic = 5 / Quiet = 6
var telemetrySentryStates = map[string]string{
	"0": "状态未知",
	"1": "未开启",
	"2": "待命",
	"3": "已布防",
	"4": "感知中",
	"5": "警报触发",
	"6": "静默",
}

// telemetrySentryStateNames 官方枚举名 → 规范编号。
var telemetrySentryStateNames = map[string]string{
	"SentryModeStateUnknown": "0",
	"SentryModeStateOff":     "1",
	"SentryModeStateIdle":    "2",
	"SentryModeStateArmed":   "3",
	"SentryModeStateAware":   "4",
	"SentryModeStatePanic":   "5",
	"SentryModeStateQuiet":   "6",
}

// --------------------------------------------------------------------------
// 官方消息 → 中文
// --------------------------------------------------------------------------

// telemetryDoorOrder 官方 `message Doors` 的字段顺序。
//
// ⚠️ 出处：`protos/vehicle_data.proto` → `message Doors`
//
//	DriverFront / DriverRear / PassengerFront / PassengerRear / TrunkFront / TrunkRear
//
// 固定顺序 ⇒ 解析结果**稳定**（同样一组门开着，每次给同样的顺序，便于对比）。
var telemetryDoorOrder = []string{
	"DriverFront", "DriverRear", "PassengerFront", "PassengerRear", "TrunkFront", "TrunkRear",
}

// telemetryDoorNames 官方 `message Doors` 的字段 → 中文。
var telemetryDoorNames = map[string]string{
	"DriverFront":    "主驾前门",
	"DriverRear":     "主驾后门",
	"PassengerFront": "副驾前门",
	"PassengerRear":  "副驾后门",
	"TrunkFront":     "前备箱",
	"TrunkRear":      "后备箱",
}

// telemetryTireFlagNames 官方 `message TireLocation` 的字段 → 中文轮名。
//
// ⚠️ 出处：`protos/vehicle_data.proto` → `message TireLocation`
//
//	front_left / front_right / rear_left / rear_right
var telemetryTireFlagNames = map[string]string{
	"front_left":  "左前",
	"front_right": "右前",
	"rear_left":   "左后",
	"rear_right":  "右后",
}

// telemetryTireFlagOrder TireLocation 的字段顺序（稳定输出用）。
var telemetryTireFlagOrder = []string{"front_left", "front_right", "rear_left", "rear_right"}

// telemetryWindowFields 遥测的车窗字段 → 中文名。
//
// ⚠️ 中文名与 `tesla.go` 里的 `windowFields`（"左前窗"…）**完全一致** ——
//
//	两个来源的结果可以直接拼/比，不会出现「遥测说左前窗、轮询说左前窗但拼写不同」。
var telemetryWindowFields = []struct{ key, name string }{
	{"FdWindow", "左前窗"},
	{"FpWindow", "右前窗"},
	{"RdWindow", "左后窗"},
	{"RpWindow", "右后窗"},
}

// --------------------------------------------------------------------------
// 值的解析（纯函数 ⇒ 可单测）
// --------------------------------------------------------------------------

// telemetryParseState 解析一个「官方枚举」形态的值。
//
// ⚠️ 认三种形态（官方 README 明说字段类型会随固件漂移）：
//
//   - 枚举名："WindowStateClosed"
//   - 裸数字：1
//   - 带引号的数字："1"
//
// ⚠️ **认不出来就返回 ok=false** —— 调用方**摊原文**，不猜。
func telemetryParseState(raw string, names, labels map[string]string) (code, text string, ok bool) {
	s := strings.TrimSpace(raw)
	s = strings.Trim(s, "\"")
	if s == "" {
		return "", "", false
	}
	if c, hit := names[s]; hit {
		return c, labels[c], true
	}
	if n, err := parseFloatStrict(s); err == nil {
		// ⚠️ 枚举编号必须是整数 —— 1.5 不是任何枚举值。
		if n == float64(int64(n)) {
			c := strconv.FormatInt(int64(n), 10)
			if lbl, hit := labels[c]; hit {
				return c, lbl, true
			}
		}
	}
	return "", "", false
}

// telemetryJSONBool 把 JSON 里的一个值当布尔看。
//
// ⚠️ 官方 Doors / TireLocation 是 `bool`，但固件偶尔给数字 —— 两种都认。
//
//	第三种返回 ok=false（不是布尔就**不知道**，不当成 false）。
func telemetryJSONBool(v any) (bool, bool) {
	switch x := v.(type) {
	case bool:
		return x, true
	case float64:
		return x != 0, true
	case string:
		if x == "true" {
			return true, true
		}
		if x == "false" {
			return false, true
		}
	}
	return false, false
}

// telemetryParseDoors 解析官方 `message Doors`（JSON 对象）。
//
// ⚠️ 返回 (开着的位置清单, 认识到的字段数, 整体认出来了吗)：
//
//	「只读到一部分」与「全关」必须分开 ——
//	门状态是「车窗未关提醒」那类判定的**事实源**，
//	把「读不到」压成「全关」会让提醒凭空消失。
func telemetryParseDoors(raw string) (open []string, known int, ok bool) {
	s := strings.TrimSpace(raw)
	if s == "" || s[0] != '{' {
		return nil, 0, false
	}
	var obj map[string]any
	if err := json.Unmarshal([]byte(s), &obj); err != nil {
		return nil, 0, false
	}
	open = []string{}
	for _, k := range telemetryDoorOrder {
		v, present := obj[k]
		if !present {
			continue
		}
		known++
		if b, isBool := telemetryJSONBool(v); isBool && b {
			open = append(open, telemetryDoorNames[k])
		}
	}
	return open, known, known > 0
}

// telemetryParseTireFlags 解析官方 `message TireLocation`（JSON 对象）。
//
// 返回 map[轮名]bool —— 哪些轮带（硬/软）告警。
//
// ⚠️ **实测车端会给字面量 `null`**（2026-10-29 真车）：
//
//	"TpmsHardWarnings": "null"
//
//	含义 = **这个字段没有告警位**（官方 proto 的 `Value` 是 oneof，
//	`null` 就是「这个分支是空的」）。
//	⇒ 解析成「没有轮次带告警」。
//
// ⚠️ 两种「没有告警位」的效果一样（都不额外报警），
//
//	而且**阈值兜底照跑**（见 EvaluateLiveTpms）⇒ 不会漏报真正的胎压异常。
func telemetryParseTireFlags(raw string) map[string]bool {
	out := map[string]bool{}
	s := strings.TrimSpace(raw)
	if s == "" || s == "null" {
		return out
	}
	if s[0] != '{' {
		return out
	}
	var obj map[string]any
	if err := json.Unmarshal([]byte(s), &obj); err != nil {
		return out
	}
	for _, k := range telemetryTireFlagOrder {
		v, present := obj[k]
		if !present {
			continue
		}
		if b, isBool := telemetryJSONBool(v); isBool && b {
			out[telemetryTireFlagNames[k]] = true
		}
	}
	return out
}

// --------------------------------------------------------------------------
// 快照
// --------------------------------------------------------------------------

// telemetryTPMSView 胎压（对外）。
//
// ⚠️ 单位是 **bar** —— 与 Fleet API 的 `vehicle_state.tpms_pressure_*` 同单位，
//
//	App 端的阈值（2.5 / 3.3 bar）直接复用。
//	⚠️ 遥测 proto **没有**标注单位 ⇒ 这一条靠**实测交叉验证**：
//	同一个时刻遥测值与轮询值必须相等（差 14.5 倍就是 psi，需要换算）。
//	验证结论记在 docs/CHANGELOG.md 里。
type telemetryTPMSView struct {
	FL *float64 `json:"fl"`
	FR *float64 `json:"fr"`
	RL *float64 `json:"rl"`
	RR *float64 `json:"rr"`
	// Unit 单位（bar）。
	Unit string `json:"unit"`
	// Alert 三态：true=有异常 / false=已知正常 / null=不知道（没读到数据）。
	// ⚠️ 不用 omitempty：没数据时必须输出 null，调用方才能区分「正常」与「不知道」。
	Alert       *bool    `json:"alert"`
	AlertWheels []string `json:"alert_wheels"`
	WarnWheels  []string `json:"warn_wheels"`
	Detail      string   `json:"detail"`
}

// telemetryWindowView 一个车窗（对外）。
type telemetryWindowView struct {
	Field string `json:"field"` // 官方字段名（如 FdWindow）
	Label string `json:"label"` // 中文（如 左前窗）
	// State 规范编号（"1"=已关闭 …）；认不出来时是空串。
	State string `json:"state"`
	// Text 中文状态；**认不出来时这里就是车端的原文**（摊开，不猜）。
	Text string `json:"text"`
	// Raw 车端原样（永远保留，方便用户/客服自己查）。
	Raw   string     `json:"raw"`
	At    *time.Time `json:"at"`
	Stale bool       `json:"stale"`
}

// telemetryDoorView 一个门/舱盖（对外）。
type telemetryDoorView struct {
	Field string `json:"field"` // 官方字段名（如 TrunkFront）
	Label string `json:"label"` // 中文（如 前备箱）
	Open  bool   `json:"open"`
}

// telemetrySentryView 哨兵模式（对外）。
type telemetrySentryView struct {
	State string     `json:"state"` // 规范编号；认不出来是空串
	Text  string     `json:"text"`  // 中文；认不出来就是原文
	Raw   string     `json:"raw"`
	At    *time.Time `json:"at"`
	Stale bool       `json:"stale"`
}

// telemetryVehicleSnapshot 一台车**当前能拿到**的遥测实况。
//
// ⚠️ 「拿不到」一律用 **nil / 空**表示，绝不用假值填充：
//
//	胎压 nil = 没读到（不是 0 bar）；温度 nil = 没读到（不是 0°C）。
type telemetryVehicleSnapshot struct {
	At          *time.Time // 这批数据的真实采集时刻（组内最旧的一个 ⇒ 保守）
	Stale       bool       // 是否是「车断线前的最后已知值」
	TPMS        *telemetryTPMSView
	Windows     []telemetryWindowView
	OpenWindows []string // 开着哪几扇窗（三态：nil=不知道 / []=全关 / 列表=开着这些）
	Doors       []telemetryDoorView
	OpenDoors   []string // 开着的门/舱盖（nil = 不知道）
	Sentry      *telemetrySentryView
	InsideTemp  *float64
	OutsideTemp *float64
	// ChargeLimitSoc 充电上限（%）。
	ChargeLimitSoc *float64
	// IsolationResistance 高压绝缘电阻。
	// ⚠️ 官方 proto **没有标注单位** ⇒ 这里只给原始值，不编单位。
	IsolationResistance *float64
}

// telemetryOldest 取一组字段里**最旧**的采集时刻与是否陈旧。
//
// ⚠️ 取最旧而不是最新 —— 一组里只要有一个是旧的，整组就不能说成「都是新的」。
func telemetryOldest(fs []telemetryFieldView) (*time.Time, bool) {
	var at *time.Time
	stale := false
	for _, f := range fs {
		if f.At == nil {
			continue
		}
		if at == nil || f.At.Before(*at) {
			t := *f.At
			at = &t
		}
		if f.Stale {
			stale = true
		}
	}
	return at, stale
}

// telemetryIdx 把字段列表转成 map（按字段名查）。
func telemetryIdx(fields []telemetryFieldView) map[string]telemetryFieldView {
	idx := make(map[string]telemetryFieldView, len(fields))
	for _, f := range fields {
		idx[f.Field] = f
	}
	return idx
}

// telemetryNum 取一个字段的数值（没有就是 nil）。
func telemetryNum(idx map[string]telemetryFieldView, key string) *float64 {
	f, ok := idx[key]
	if !ok || f.Num == nil {
		return nil
	}
	v := *f.Num
	return &v
}

// telemetryTPMSFromFields 把遥测的胎压字段组装成与轮询**同形状**的快照。
//
// ⚠️ 告警判定直接复用 `EvaluateLiveTpms`（唯一事实源，不另写一套阈值）。
//
//	返回 ok=false = 遥测里一个胎压都没读到（⇒ 不能说「胎压正常」）。
func telemetryTPMSFromFields(idx map[string]telemetryFieldView) (TeslaTPMS, bool) {
	var tp TeslaTPMS
	got := false

	set := func(key string) *float64 {
		v := telemetryNum(idx, key)
		if v != nil {
			got = true
		}
		return v
	}
	tp.FL.Pressure = set("TpmsPressureFl")
	tp.FR.Pressure = set("TpmsPressureFr")
	tp.RL.Pressure = set("TpmsPressureRl")
	tp.RR.Pressure = set("TpmsPressureRr")

	// 硬/软告警位：官方 TireLocation 对象（哪些轮带告警）。
	hard := telemetryParseTireFlags(telemetryRaw(idx, "TpmsHardWarnings"))
	soft := telemetryParseTireFlags(telemetryRaw(idx, "TpmsSoftWarnings"))
	tp.FL.Hard = hard["左前"]
	tp.FR.Hard = hard["右前"]
	tp.RL.Hard = hard["左后"]
	tp.RR.Hard = hard["右后"]
	tp.FL.Soft = soft["左前"]
	tp.FR.Soft = soft["右前"]
	tp.RL.Soft = soft["左后"]
	tp.RR.Soft = soft["右后"]

	// 采集时刻：六个胎压相关字段里**最旧**的。
	group := []telemetryFieldView{}
	for _, k := range []string{
		"TpmsPressureFl", "TpmsPressureFr", "TpmsPressureRl", "TpmsPressureRr",
		"TpmsHardWarnings", "TpmsSoftWarnings",
	} {
		if f, ok := idx[k]; ok {
			group = append(group, f)
		}
	}
	tp.At, _ = telemetryOldest(group)
	return tp, got
}

// telemetryRaw 取一个字段的原始文本（没有就是空串）。
func telemetryRaw(idx map[string]telemetryFieldView, key string) string {
	if f, ok := idx[key]; ok {
		return f.Text
	}
	return ""
}

// telemetryOpenWindows 遥测里的「开着哪几扇窗」。
//
// ⚠️ 三态与 `teslaOpenWindows` **完全一致**：
//
//	nil = 不知道（没读到 / 认不出） / []string{} = 知道，全关 / 列表 = 开着这些
//
// 中文名也用同一套（"左前窗"…）⇒ 两个来源可以互换。
func telemetryOpenWindows(idx map[string]telemetryFieldView) []string {
	open := []string{}
	saw := 0
	for _, f := range telemetryWindowFields {
		v, ok := idx[f.key]
		if !ok {
			continue
		}
		code, _, known := telemetryParseState(v.Text, telemetryWindowStateNames, telemetryWindowStates)
		if !known {
			continue
		}
		saw++
		// 2=半开、3=已打开 ⇒ 都算「开着」（推送侧的判定关心的是「有没有开」）。
		if code == "2" || code == "3" {
			open = append(open, f.name)
		}
	}
	if len(open) > 0 {
		return open
	}
	// 四扇窗都读到且都关着 = **确定全关**。
	if saw == len(telemetryWindowFields) {
		return []string{}
	}
	// 只读到一部分 ⇒ **不知道**（不能声称「全关」）。
	return nil
}

// telemetryVehicle 组装这台车的遥测实况。
func telemetryVehicle(ctx context.Context, vin string) (*telemetryVehicleSnapshot, error) {
	fields, err := loadTelemetryFields(ctx, vin)
	if err != nil {
		return nil, err
	}
	idx := telemetryIdx(fields)
	snap := &telemetryVehicleSnapshot{}

	// ── 胎压 ──────────────────────────────────────────────
	if tp, ok := telemetryTPMSFromFields(idx); ok {
		eval := EvaluateLiveTpms(tp)
		alerting := eval.Alerting
		view := &telemetryTPMSView{
			FL: tp.FL.Pressure, FR: tp.FR.Pressure, RL: tp.RL.Pressure, RR: tp.RR.Pressure,
			Unit:        "bar",
			Alert:       &alerting,
			AlertWheels: eval.Wheels,
			WarnWheels:  eval.WarnWheels,
			Detail:      strings.Join(eval.Values, "  "),
		}
		if view.AlertWheels == nil {
			view.AlertWheels = []string{}
		}
		if view.WarnWheels == nil {
			view.WarnWheels = []string{}
		}
		snap.TPMS = view
	}

	// ── 车窗 ──────────────────────────────────────────────
	for _, f := range telemetryWindowFields {
		v, ok := idx[f.key]
		if !ok {
			continue
		}
		code, text, known := telemetryParseState(v.Text, telemetryWindowStateNames, telemetryWindowStates)
		if !known {
			// ⚠️ 认不出来 ⇒ **摊原文**，不猜是哪一种状态。
			text = v.Text
		}
		snap.Windows = append(snap.Windows, telemetryWindowView{
			Field: f.key, Label: f.name, State: code, Text: text, Raw: v.Text,
			At: v.At, Stale: v.Stale,
		})
	}
	snap.OpenWindows = telemetryOpenWindows(idx)

	// ── 门 / 舱盖 ─────────────────────────────────────────
	if f, ok := idx["DoorState"]; ok {
		open, _, known := telemetryParseDoors(f.Text)
		if known {
			snap.OpenDoors = open
			for _, k := range telemetryDoorOrder {
				isOpen := false
				for _, o := range open {
					if o == telemetryDoorNames[k] {
						isOpen = true
						break
					}
				}
				snap.Doors = append(snap.Doors, telemetryDoorView{
					Field: k, Label: telemetryDoorNames[k], Open: isOpen,
				})
			}
		}
	}

	// ── 哨兵模式 ──────────────────────────────────────────
	if f, ok := idx["SentryMode"]; ok {
		code, text, known := telemetryParseState(f.Text, telemetrySentryStateNames, telemetrySentryStates)
		if !known {
			text = f.Text
		}
		snap.Sentry = &telemetrySentryView{
			State: code, Text: text, Raw: f.Text, At: f.At, Stale: f.Stale,
		}
	}

	// ── 数值 ──────────────────────────────────────────────
	snap.InsideTemp = telemetryNum(idx, "InsideTemp")
	snap.OutsideTemp = telemetryNum(idx, "OutsideTemp")
	snap.ChargeLimitSoc = telemetryNum(idx, "ChargeLimitSoc")
	snap.IsolationResistance = telemetryNum(idx, "IsolationResistance")

	// ── 整组的时刻（组内最旧 ⇒ 保守） ──────────────────────
	group := []telemetryFieldView{}
	for _, f := range fields {
		group = append(group, f)
	}
	snap.At, snap.Stale = telemetryOldest(group)
	return snap, nil
}

// telemetryLiveTPMS 遥测里的实时胎压（给 /status 用）。
//
// ok=false = 遥测里一个胎压都没读到 ⇒ 调用方回退轮询。
func telemetryLiveTPMS(ctx context.Context, vin string) (TeslaTPMS, bool) {
	fields, err := loadTelemetryFields(ctx, vin)
	if err != nil {
		log.Printf("⚠️  读遥测胎压失败：%v", err)
		return TeslaTPMS{}, false
	}
	return telemetryTPMSFromFields(telemetryIdx(fields))
}

// pickNewerTPMS 在「轮询」与「遥测」两份胎压里挑**更新的**那份。
//
// ⚠️ 不是无条件「遥测优先」：
//
//	遥测的值是**断线前的最后已知值**（可能是 9 小时前），
//	轮询的值是**上一次成功拉取**的（也可能更旧）。
//	⇒ 按采集时刻取新的那个，才不会把旧的当新的。
//
// ⚠️ 两边的时刻都是**车端时钟**（轮询用 vehicle_data.timestamp，
//
//	遥测用 connectivity 载荷里的 CreatedAt）⇒ 可比。
func pickNewerTPMS(poll, tel TeslaTPMS, telOK bool) (TeslaTPMS, string) {
	pollOK := poll.FL.Pressure != nil || poll.FR.Pressure != nil ||
		poll.RL.Pressure != nil || poll.RR.Pressure != nil
	switch {
	case telOK && !pollOK:
		return tel, "telemetry"
	case !telOK && pollOK:
		return poll, "poll"
	case !telOK && !pollOK:
		return TeslaTPMS{}, ""
	}
	// 两边都有 ⇒ 取采集时刻更晚的那个（有一边没有时刻就当它更旧）。
	if tel.At != nil && (poll.At == nil || tel.At.After(*poll.At)) {
		return tel, "telemetry"
	}
	return poll, "poll"
}


// pickNewerWindows 在「轮询」与「遥测」两份车窗结论里挑**更新的**那份。
//
// ⚠️ 三态语义：nil = **不知道**（不是「全关」）。
//
//	所以「有一边不知道」时直接用另一边 ——
//	不能因为「读不到」就当成「全关」，否则推送侧的计时器会误判成「刚关上」。
//
// ⚠️ 返回的第二个值是来源（telemetry / poll / 空），供 /status 标出来。
func pickNewerWindows(poll []string, pollAt *time.Time, tel []string, telAt *time.Time) ([]string, string) {
	switch {
	case poll == nil && tel == nil:
		return nil, ""
	case tel == nil:
		return poll, "poll"
	case poll == nil:
		return tel, "telemetry"
	}
	if telAt != nil && (pollAt == nil || telAt.After(*pollAt)) {
		return tel, "telemetry"
	}
	return poll, "poll"
}

// telemetryEverConnected 这台车**曾经**接上过遥测吗。
//
// ⚠️ 用库里历史判，而不是进程内计数器 ——
//
//	进程重启后计数器归零，会把「接接过」误判成「从未接过」。
func telemetryEverConnected(ctx context.Context, vin string) bool {
	var ever bool
	if err := db.QueryRow(ctx, `
	SELECT EXISTS(
		SELECT 1 FROM tm_telemetry_connectivity WHERE vin = $1 AND status = 'CONNECTED'
	)`, vin).Scan(&ever); err != nil {
		log.Printf("⚠️  查遥测接入历史失败：%v", err)
		return false
	}
	return ever
}

// --------------------------------------------------------------------------
// 对外端点
// --------------------------------------------------------------------------

// telemetryVehicleResponse GET /api/v1/cars/{id}/telemetry_vehicle 的返回。
type telemetryVehicleResponse struct {
	// Connected 车端此刻是否在线。
	Connected bool `json:"connected"`
	// EverConnected 遥测流**曾经**接上过吗（跨进程重启有效）。
	//
	// ⚠️ 没有它，调用方分不清「车没推过」与「车现在没连着」——
	//	前者是「我们还没接上遥测」，后者是「车睡着了」。
	EverConnected bool `json:"ever_connected"`
	// At / Stale 这批数据的真实采集时刻 / 是否是「车断线前的最后已知值」。
	At    *time.Time `json:"at"`
	Stale bool       `json:"stale"`

	TPMS        *telemetryTPMSView    `json:"tpms"`
	Windows     []telemetryWindowView `json:"windows"`
	OpenWindows []string              `json:"open_windows"`
	Doors       []telemetryDoorView   `json:"doors"`
	OpenDoors   []string              `json:"open_doors"`
	Sentry      *telemetrySentryView  `json:"sentry"`

	InsideTemp  *float64 `json:"inside_temp"`
	OutsideTemp *float64 `json:"outside_temp"`
	// ChargeLimitSoc 充电上限（%）。
	ChargeLimitSoc *float64 `json:"charge_limit_soc"`
	// IsolationResistance 高压绝缘电阻（原始值；官方 proto 未标注单位）。
	IsolationResistance *float64 `json:"insulation_resistance"`

	// Fields 原始字段（逐项带 label / at / stale）——
	// 分组视图之外保留这一份，是为了让调用方能核对**每一项**的新鲜度。
	Fields []telemetryFieldView `json:"fields"`
}

// handleTelemetryVehicle GET /api/v1/cars/{id}/telemetry_vehicle
//
// 车况实况（遥测）：胎压 / 车窗 / 门 / 哨兵 / 温度 / 充电上限。
//
// ⚠️ 与 `/telemetry_state` 的分工：
//   - `/telemetry_state` = **原始字段流**（谁推了就给谁，一行一个字段）
//   - `/telemetry_vehicle` = **解读后的车辆实况**（App / 飞书直接能用）
func handleTelemetryVehicle(w http.ResponseWriter, r *http.Request) {
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
	snap, err := telemetryVehicle(r.Context(), vin)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
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
	out := telemetryVehicleResponse{
		Connected: lastConnected != nil && (lastDisconnected == nil || lastConnected.After(*lastDisconnected)),
		// EverConnected 用库里历史判（进程重启后计数器归零，会误判成「没接过」）。
		EverConnected: telemetryEverConnected(r.Context(), vin),
		At:            snap.At,
		Stale:         snap.Stale,
		TPMS:          snap.TPMS,
		Windows:       snap.Windows,
		Doors:         snap.Doors,
		Sentry:        snap.Sentry,
		// ⚠️ OpenWindows / OpenDoors 可能是 **nil** ——
		//	那是「不知道」，不是「全关」。别压成空数组。
		OpenWindows:         snap.OpenWindows,
		OpenDoors:           snap.OpenDoors,
		InsideTemp:          snap.InsideTemp,
		OutsideTemp:         snap.OutsideTemp,
		ChargeLimitSoc:      snap.ChargeLimitSoc,
		IsolationResistance: snap.IsolationResistance,
		Fields:              fields,
	}
	writeJSON(w, out)
}
