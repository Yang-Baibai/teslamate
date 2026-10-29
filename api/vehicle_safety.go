package main

// --------------------------------------------------------------------------
// 车辆「当下安全状态」：车门 / 哨兵（v0.82.0）
//
// ⚠️ **反幻觉铁律在这里落地**：
//
//	本文档里每一个字段名都来自**官方 Fleet Telemetry「Available Data」表**
//	的 related field 列，**逐字照抄**：
//
//		DoorState  → vehicle_state.df / dr / pf / pr / ft / rt
//		SentryMode → vehicle_state.sentry_mode
//
//	https://developer.tesla.cn/docs/fleet-api/fleet-telemetry/available-data
//
//	⇒ 遥测与轮询是**同一个底层状态的两份表达**，
//	   可以互相校验、互相兜底（不是「两个不相干的数」）。
//
// ⚠️ **官方明说的固件陷阱（DoorState 原文）**：
//
//	"This field has passenger front and driver rear doors swapped in
//	 firmware versions prior to 2024.44.32."
//
//	⇒ 2024.44.32 之前，**副驾前门与主驾后门**的值是互换的。
//	见 doorFirmwareSwapBefore。
// --------------------------------------------------------------------------

import (
	"context"
	"log"
	"strconv"
	"strings"
	"time"
)

// --------------------------------------------------------------------------
// 轮询侧（Fleet API vehicle_data.vehicle_state）
// --------------------------------------------------------------------------

// doorFields 轮询侧的门位字段表。
//
// ⚠️ 字段名与中文名**逐字**来自官方 Available Data 表的 related field 列：
//
//	DoorState → vehicle_state.df vehicle_state.dr vehicle_state.pf
//	            vehicle_state.pr vehicle_state.ft vehicle_state.rt
//
//	中文名与遥测 `message Doors`（telemetryDoorNames）**完全一致** ——
//	两个来源的结果可以直接拼/比，不会出现「遥测说副驾前门、轮询说副驾前门
//	但拼写不同」。
//
// ⚠️ 顺序（df dr pf pr ft rt）与官方一致，也与遥测 `message Doors` 的
//
//	字段顺序（DriverFront / DriverRear / PassengerFront / PassengerRear /
//	TrunkFront / TrunkRear）**逐位对应** ⇒ 这不是巧合，是官方自己给的对照。
var doorFields = []struct{ key, name string }{
	{"df", "主驾前门"},
	{"dr", "主驾后门"},
	{"pf", "副驾前门"},
	{"pr", "副驾后门"},
	{"ft", "前备箱"},
	{"rt", "后备箱"},
}

// vehicleStateRaw 从轮询缓存里取出 vehicle_state 段（没有就是 nil）。
func vehicleStateRaw(raw map[string]any) map[string]any {
	if raw == nil {
		return nil
	}
	vs, ok := raw["vehicle_state"].(map[string]any)
	if !ok || vs == nil {
		return nil
	}
	return vs
}

// carVersionFromRaw 读车端固件版本（如 "2026.26.200.11"）。
//
// ⚠️ 官方说 2024.44.32 之前副驾前门 / 主驾后门互换 ——
//
//	要知道要不要对调，就得知道固件版本。
func carVersionFromRaw(raw map[string]any) string {
	vs := vehicleStateRaw(raw)
	if vs == nil {
		return ""
	}
	s, _ := vs["car_version"].(string)
	return strings.TrimSpace(s)
}

// parseFirmwareTriple 解析 "2026.26.200.11" → (2026, 26, 200)。
//
// ⚠️ 固件版本**不一定有三段**（有的只有 "2024.26.200"），
//
//	有的带四段（"2026.26.200.11"）⇒ 取前三段比较。
func parseFirmwareTriple(v string) (int, int, int, bool) {
	parts := strings.Split(strings.TrimSpace(v), ".")
	if len(parts) < 3 {
		return 0, 0, 0, false
	}
	nums := make([]int, 3)
	for i := 0; i < 3; i++ {
		n, err := strconv.Atoi(strings.TrimSpace(parts[i]))
		if err != nil {
			return 0, 0, 0, false
		}
		nums[i] = n
	}
	return nums[0], nums[1], nums[2], true
}

// doorFirmwareSwapBefore 判断这台车是否落在「副驾前门 / 主驾后门互换」的
// 固件区间里。
//
// ⚠️ 官方原文：prior to **2024.44.32** ⇒ 严格小于才算。
//
// ⚠️ 解析不出来时**返回 false**（不对调）：
//
//	绝大多数车早就过了 2024.44；宁可不对调，也不要把两个门的名字搞反。
//	真的落在旧固件区间时，日志里会有一条明确的提示。
func doorFirmwareSwapBefore(carVersion string) bool {
	maj, min, pat, ok := parseFirmwareTriple(carVersion)
	if !ok {
		return false
	}
	switch {
	case maj < 2024:
		return true
	case maj > 2024:
		return false
	case min < 44:
		return true
	case min > 44:
		return false
	default:
		return pat < 32
	}
}

// swapDoorLabels 把「副驾前门」与「主驾后门」两个名字对调。
//
// ⚠️ 只在旧固件（< 2024.44.32）上调用。
func swapDoorLabels(names []string) {
	for i, n := range names {
		switch n {
		case "副驾前门":
			names[i] = "主驾后门"
		case "主驾后门":
			names[i] = "副驾前门"
		}
	}
}

// doorsFromRaw 从轮询缓存里解析「开着哪几个门」。
//
// ⚠️ **三态**（与车窗、与遥测完全一致）：
//
//	nil = 不知道（没读到 / 只读到一部分且没读到开着的）
//	[]   = 知道，全关
//	列表 = 开着这些
//
// ⚠️ 中国区实测这些字段是**整数 0/1**，不是布尔 —— 两种都认（isOpenFlag）。
func doorsFromRaw(raw map[string]any) []string {
	vs := vehicleStateRaw(raw)
	if vs == nil {
		return nil
	}
	open := []string{}
	saw := 0
	for _, f := range doorFields {
		v, present := vs[f.key]
		if !present {
			continue
		}
		saw++
		if isOpenFlag(v) {
			open = append(open, f.name)
		}
	}
	// 有一个开着 = **确定**开着（哪怕只读到一部分字段）。
	if len(open) > 0 {
		return open
	}
	// 六个都读到、且全是关的 = **确定全关**。
	if saw == len(doorFields) {
		return []string{}
	}
	// 只读到一部分、且没读到开着的那 ⇒ **不知道**。
	return nil
}

// --------------------------------------------------------------------------
// 哨兵模式
// --------------------------------------------------------------------------

// 哨兵的**归一化状态词表**。
//
// ⚠️ 遥测侧直接用官方枚举名的小写（Official enum names，逐字）：
//
//	off / idle / armed / aware / panic / quiet
//
// ⚠️ 轮询侧只有两个额外取值：
//
//	on         —— 中国区实测 `vehicle_state.sentry_mode` 是**布尔**，
//	              只能分辨「开 / 关」，分辨不出是哪一种开启状态。
//	unsupported —— `vehicle_state.sentry_mode_available` 为 false（车不支持哨兵）。
//
// ⚠️ **不做跨词表臆造映射**：布尔 true 只说「已开启」，
//
//	**不猜**它是 armed 还是 aware 还是 panic —— 猜错就是在编事实。
const (
	sentryStateOff         = "off"
	sentryStateIdle        = "idle"
	sentryStateArmed       = "armed"
	sentryStateAware       = "aware"
	sentryStatePanic       = "panic"
	sentryStateQuiet       = "quiet"
	sentryStateOn          = "on"
	sentryStateUnsupported = "unsupported"
	sentryStateUnknown     = "unknown"
)

// sentryStateTexts 归一化状态 → 中文。
//
// ⚠️ 遥测那七个是官方枚举名（Unknown/Off/Idle/Armed/Aware/Panic/Quiet）的
//
//	**直接翻译**，不是对行为的推测。
var sentryStateTexts = map[string]string{
	sentryStateOff:   "未开启",
	sentryStateIdle:  "待命",
	sentryStateArmed: "已布防",
	sentryStateAware: "感知中",
	sentryStatePanic: "警报触发",
	sentryStateQuiet: "静默",
	sentryStateOn:    "已开启",
	// ⚠️ 文案要**短**：App 把这三项排在**一行**里，
	// 「此车不支持哨兵」会把整行撑到放不下 ⇒ 只说「不支持」。
	sentryStateUnsupported: "不支持",
	sentryStateUnknown:     "状态未知",
}

// sentryText 取状态的中文。
//
// ⚠️ 空串（**完全没读到**）⇒ 返回空串，不是「状态未知」——
//
//	「没读到」与「车自己说不知道」在界面上要分开：
//	前者是「我们没有数据」，后者是「车报告的状态未知」。
func sentryText(state string) string {
	if state == "" {
		return ""
	}
	if t, ok := sentryStateTexts[state]; ok {
		return t
	}
	return sentryStateTexts[sentryStateUnknown]
}

// sentryKnown 这个状态**算不算「知道了」**。
//
// ⚠️ "unknown"（官方枚举 0）= 车自己说「不知道」⇒ 不算知道。
//
//	所以合并时它不参与竞争（见 pickNewerSentry）。
func sentryKnown(state string) bool {
	return state != "" && state != sentryStateUnknown
}

// sentryFromRaw 从轮询缓存里解析哨兵状态。
//
// ⚠️ 中国区实测：`vehicle_state.sentry_mode` 是**布尔**（true/false），
//
//	不是官方文档里遥测侧那个 7 态枚举。
//
//	⇒ 布尔只能分辨「开 / 关」：
//	   false → off（未开启）
//	   true  → on（已开启）—— **不细分**是哪一种开启状态。
//
// ⚠️ `sentry_mode_available` 为 false ⇒ 这台车压根不支持哨兵（不是「关着」）。
func sentryFromRaw(raw map[string]any) (state string, ok bool) {
	vs := vehicleStateRaw(raw)
	if vs == nil {
		return "", false
	}
	if av, present := vs["sentry_mode_available"]; present {
		if b, isBool := telemetryJSONBool(av); isBool && !b {
			return sentryStateUnsupported, true
		}
	}
	v, present := vs["sentry_mode"]
	if !present {
		return "", false
	}
	switch t := v.(type) {
	case bool:
		if t {
			return sentryStateOn, true
		}
		return sentryStateOff, true
	case string:
		switch strings.ToLower(strings.TrimSpace(t)) {
		case "off", "disabled":
			return sentryStateOff, true
		case "on", "enabled":
			return sentryStateOn, true
		case "user_disabled":
			return sentryStateOff, true
		case "unavailable", "not_configured":
			return sentryStateUnsupported, true
		}
	}
	// 数字 0/1 也认（有的固件给 int）。
	if b, isKnown := telemetryJSONBool(v); isKnown {
		if b {
			return sentryStateOn, true
		}
		return sentryStateOff, true
	}
	return "", false
}

// --------------------------------------------------------------------------
// 遥测侧（复用 telemetry_vehicle.go 的解析层）
// --------------------------------------------------------------------------

// telemetrySafetyForVIN 一次读库，取遥测里的「车窗 / 车门 / 哨兵」三组结论。
//
// ⚠️ 为什么要合并成一次读库：
//
//	/status 这三样都要用；分三次读就是三次 DB 查询。
//	（锁车 / 胎压各读一次是历史遗留，本轮不再扩大。）
func telemetrySafetyForVIN(ctx context.Context, vin string) telemetrySafety {
	var out telemetrySafety
	fields, err := loadTelemetryFields(ctx, vin)
	if err != nil {
		log.Printf("⚠️  读遥测车辆安全状态失败：%v", err)
		return out
	}
	idx := telemetryIdx(fields)

	// ── 车窗 ──────────────────────────────────────────────
	winGroup := []telemetryFieldView{}
	for _, f := range telemetryWindowFields {
		if v, ok := idx[f.key]; ok {
			winGroup = append(winGroup, v)
		}
	}
	out.Windows = telemetryOpenWindows(idx)
	out.WindowsAt, _ = telemetryOldest(winGroup)

	// ── 车门 ──────────────────────────────────────────────
	// ⚠️ 时刻取 DoorState **这一个字段**的时刻（门是一个整体消息）。
	if f, ok := idx["DoorState"]; ok {
		open, _, known := telemetryParseDoors(f.Text)
		if known {
			out.Doors = open
			out.DoorsAt = f.At
			out.DoorsStale = f.Stale
		}
	}

	// ── 哨兵 ──────────────────────────────────────────────
	if f, ok := idx["SentryMode"]; ok {
		code, _, known := telemetryParseState(f.Text, telemetrySentryStateNames, telemetrySentryStates)
		if known {
			out.SentryState = telemetrySentryStateToNormalized(code)
			out.SentryText = sentryText(out.SentryState)
			out.SentryAt = f.At
			out.SentryStale = f.Stale
			out.SentryOK = true
		}
	}
	return out
}

// telemetrySentryStateToNormalized 官方枚举编号 → 归一化状态词。
//
// ⚠️ 官方 `enum SentryModeState`（编号来自 protos/vehicle_data.proto）：
//
//	Unknown=0 / Off=1 / Idle=2 / Armed=3 / Aware=4 / Panic=5 / Quiet=6
//
// ⚠️ 0（Unknown）= 车自己说「不知道」⇒ 归一化成 unknown
//
//	（合并时不参与竞争，见 sentryKnown）。
func telemetrySentryStateToNormalized(code string) string {
	switch code {
	case "1":
		return sentryStateOff
	case "2":
		return sentryStateIdle
	case "3":
		return sentryStateArmed
	case "4":
		return sentryStateAware
	case "5":
		return sentryStatePanic
	case "6":
		return sentryStateQuiet
	}
	return sentryStateUnknown
}

// telemetrySafety 遥测侧的「车辆安全状态」三组结论。
type telemetrySafety struct {
	Windows   []string
	WindowsAt *time.Time

	Doors      []string
	DoorsAt    *time.Time
	DoorsStale bool

	SentryState string
	SentryText  string
	SentryAt    *time.Time
	SentryStale bool
	SentryOK    bool
}

// --------------------------------------------------------------------------
// 合并：两个来源取**更新的**那个
// --------------------------------------------------------------------------

// sameOpenSet 两份「开着哪些」结论是否**一致**（按集合比，不看顺序）。
func sameOpenSet(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	set := map[string]int{}
	for _, x := range a {
		set[x]++
	}
	for _, x := range b {
		set[x]--
		if set[x] < 0 {
			return false
		}
	}
	return true
}

// pickNewerDoors 在「轮询」与「遥测」两份车门结论里挑一份。
//
// ⚠️ 与 pickNewerWindows 相比**多一条规则**：两边**口径不一致**时，
// 以**遥测**为准（而不是取更新的那个）。
//
// **为什么**（2026-10-29 真车实测）：
//
// 遥测 `DoorState` 是官方 `message Doors` 消息（六个布尔：
// DriverFront / DriverRear / PassengerFront / PassengerRear /
// TrunkFront / TrunkRear）的**逐字**编码 ⇒ 语义明确。
//
// 轮询的 `vehicle_state.df/dr/pf/pr/ft/rt` 官方也说是同一状态，
// 但**编码不透明** —— 实测 `rt` 出现过 **32**（既不是 0 也不是 1）。
// ⇒ 编码一旦变卦（32 变成「关」的含义），「取更新的」会跟着说错，
// 而且是**静默**说错（车没开门，App 却说开着）。
//
// ⇒ 口径冲突时，**官方消息**赢；一致时才按采集时刻取新的。
//
// ⚠️ 其余同 pickNewerWindows：
//
//   - 一边是 nil（不知道）⇒ 直接用另一边
//     （不能因为「读不到」就当成「全关」）
//   - 返回的第二个值是来源（telemetry / poll / 空）。
func pickNewerDoors(poll []string, pollAt *time.Time, tel []string, telAt *time.Time) ([]string, string) {
	switch {
	case poll == nil && tel == nil:
		return nil, ""
	case tel == nil:
		return poll, "poll"
	case poll == nil:
		return tel, "telemetry"
	}
	if !sameOpenSet(poll, tel) {
		log.Printf("⚠️ 车门口径冲突：轮询=%v 遥测=%v ⇒ 以遥测（官方 message Doors）为准", poll, tel)
		return tel, "telemetry"
	}
	if telAt != nil && (pollAt == nil || telAt.After(*pollAt)) {
		return tel, "telemetry"
	}
	return poll, "poll"
}

// pickNewerSentry 在「轮询」与「遥测」两份哨兵结论里挑**更有信息、且更新**的那份。
//
// ⚠️ 与车门/车窗的差别：**遥测的 "unknown" 不算「知道了」**。
//
//	官方枚举 0（Unknown）是车自己说「不知道」，
//	而轮询的布尔 false 是**确切的「未开启」**
//	⇒ 遥测说不知道、轮询说未开启时，取轮询。
//
// ⚠️ 两边都知道时，仍然**按采集时刻取新的那个**（同胎压/车窗的口径）。
func pickNewerSentry(
	pollState string, pollAt *time.Time,
	telState string, telAt *time.Time,
) (state, source string, at *time.Time) {
	pollOK := sentryKnown(pollState)
	telOK := sentryKnown(telState)
	switch {
	case !pollOK && !telOK:
		return "", "", nil
	case !telOK:
		return pollState, "poll", pollAt
	case !pollOK:
		return telState, "telemetry", telAt
	}
	if telAt != nil && (pollAt == nil || telAt.After(*pollAt)) {
		return telState, "telemetry", telAt
	}
	return pollState, "poll", pollAt
}

// oldestTime 取一组时刻里**最旧**的那个。
//
// ⚠️ 一组里只要有一个是旧的，整组就不能说成「都是新的」⇒ 取最旧。
//
// ⚠️ nil 直接跳过（那一项没读到，不参与比较）。
func oldestTime(ts ...*time.Time) *time.Time {
	var out *time.Time
	for _, t := range ts {
		if t == nil {
			continue
		}
		if out == nil || t.Before(*out) {
			v := *t
			out = &v
		}
	}
	return out
}

// --------------------------------------------------------------------------
// 轮询侧统一入口
// --------------------------------------------------------------------------

// teslaVehicleDataAt 取轮询缓存的原始 vehicle_data + 采集时刻。
//
// ⚠️ 时刻取的是**我们拉到的时刻**（rawFetchedAt）——
//
//	它比车端 timestamp 晚几十毫秒，但两边都是 UTC，可比。
func teslaVehicleDataAt() (map[string]any, *time.Time) {
	if tesla == nil {
		return nil, nil
	}
	raw, at := tesla.RawVehicleData()
	if raw == nil || at.IsZero() {
		return nil, nil
	}
	return raw, &at
}
