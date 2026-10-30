package main

// --------------------------------------------------------------------------
// 座舱状态：空调 / 座椅加热 / 方向盘加热（v0.88.0）
// --------------------------------------------------------------------------
//
// ## 为什么要有这一块
//
// v0.87.0 让人能**设**座椅加热、方向盘加热、空调（官方 21 个车控动作）。
// 但「能设」不等于「能读回」——
//
//	Hermes 被问「座椅加热现在开着吗」「方向盘加热开了没」「空调设到几度」，
//	此前只能答「不知道」⇒ 全量控车能力**只做了一半**。
//
// ⇒ 这一块把**座舱的当下状态**从 Fleet Telemetry 接进来，
//   补上「设 → 读回」的闭环（使命书 §2.1「状态双向同步」）。
//
// ## 官方依据（逐字，不是推断）
//
// 字段名与编号：teslamotors/fleet-telemetry → protos/vehicle_data.proto
// （`enum Field`，共 278 项）：
//
//	SeatHeaterLeft  = 87    SeatHeaterRight = 88
//	HvacACEnabled   = 196  HvacAutoMode    = 197  HvacFanSpeed   = 198
//	HvacFanStatus   = 199  HvacLeftTemperatureRequest = 200
//	HvacPower       = 201  HvacRightTemperatureRequest = 202
//	HvacSteeringWheelHeatAuto = 203  HvacSteeringWheelHeatLevel = 204
//
// ⚠️ **固件门槛（官方 proto 注释原文）**：
//
//	"fields 180-183, 185-228 are first available in firmware version
//	 2024.44.25 and 2024.45.25"
//
//	⇒ 196~204 这一段要 **2024.44.25+**；本车实测固件 2026.26.200 ✓
//	（87/88 在更早的段 ⇒ 无门槛）。
//
// 字段含义与取值：官方 Fleet Telemetry「Available Data」表
// （https://developer.tesla.com/docs/fleet-api/fleet-telemetry/available-data）
//
//	SeatHeaterLeft  integer  "The level of the front left seat heater.
//	                              **Values range from 0 (off) to 3 (high).**"
//	HvacPower       HvacPowerState enum  "The power state of the HVAC system."
//	HvacACEnabled   boolean  "If AC is enabled."
//	HvacFanSpeed    integer  "The HVAC fan speed."
//	HvacLeftTemperatureRequest  real  "…Reported in celsius."
//	HvacSteeringWheelHeatLevel integer  "The level of steering wheel heat."
//
// ⚠️ **官方「Vehicle Data Equivalent」列把遥测逐字对到了轮询键**：
//
//	HvacPower            ↔ climate_state.is_climate_on
//	HvacAutoMode         ↔ climate_state.hvac_auto_request
//	HvacFanStatus        ↔ climate_state.fan_status
//	HvacSteeringWheelHeatAuto ↔ climate_state.auto_steering_wheel_heat
//	HvacSteeringWheelHeatLevel ↔ climate_state.steering_wheel_heat_level
//	SeatHeaterLeft       ↔ climate_state.seat_heater_left
//	SeatHeaterRight      ↔ climate_state.seat_heater_right
//	ClimateSeatCoolingFrontLeft ↔ climate_state.seat_fan_front_left
//
// ⇒ 它们与轮询是**同一个底层状态的两份表达**，不是两个不相干的数。
//
// ## 反幻觉：方向盘加热档位**不给中文档位名**
//
// ⚠️ 官方对 `HvacSteeringWheelHeatLevel` 只说「The level of steering wheel heat.」——
//
//	**中英两站文档 + 官方 proto + 官方 vehicle-command 全部没有给范围**
//	（vehicle-command 的方向盘加热命令只有开关：protobuf `PowerOn: enabled`）。
//
// ⇒ 所以这里**原样给出整数**，**不编**「关/低/中/高」。
//   （座椅加热不同：官方明写 0..3 且 0=off ⇒ 可以标中文档位。）
// --------------------------------------------------------------------------

import (
	"strconv"
	"strings"
	"time"
)

// --------------------------------------------------------------------------
// 官方枚举
// --------------------------------------------------------------------------

// telemetryHvacPowerStateNames 官方 `enum HvacPowerState` 的枚举名 → 编号。
//
// ⚠️ 编号来自官方 proto（protos/vehicle_data.proto）：
//
//	Unknown=0 / Off=1 / On=2 / Precondition=3 / OverheatProtect=4
//
// ⚠️ 同时收「带前缀的官方名」与「裸名」（固件偶尔省掉前缀）——
// 与遥测侧既有的 WindowState / SentryModeState 同一套做法。
var telemetryHvacPowerStateNames = map[string]string{
	"HvacPowerStateUnknown":         "0",
	"HvacPowerStateOff":             "1",
	"HvacPowerStateOn":              "2",
	"HvacPowerStatePrecondition":    "3",
	"HvacPowerStateOverheatProtect": "4",
	"Unknown":                       "0",
	"Off":                           "1",
	"On":                            "2",
	"Precondition":                  "3",
	"OverheatProtect":               "4",
}

// telemetryHvacPowerStates 官方 `enum HvacPowerState` 编号 → 中文。
var telemetryHvacPowerStates = map[string]string{
	"0": "状态未知",
	"1": "关闭",
	"2": "开启",
	"3": "预热中",
	"4": "防过热保护",
}

// telemetryHvacAutoModeStateNames 官方 `enum HvacAutoModeState` 的枚举名 → 编号。
//
// ⚠️ 官方 proto：Unknown=0 / On=1 / Override=2
var telemetryHvacAutoModeStateNames = map[string]string{
	"HvacAutoModeStateUnknown":  "0",
	"HvacAutoModeStateOn":       "1",
	"HvacAutoModeStateOverride": "2",
	"Unknown":                   "0",
	"On":                        "1",
	"Override":                  "2",
}

// telemetryHvacAutoModeStates 官方 `enum HvacAutoModeState` 编号 → 中文。
var telemetryHvacAutoModeStates = map[string]string{
	"0": "状态未知",
	"1": "自动",
	"2": "手动接管",
}

// cabinSeatLevelTexts 座椅加热档位 → 中文。
//
// ⚠️ **官方给死了这个范围**，不是我们编的：
//
//	官方 Available Data 对 SeatHeaterLeft/Right 的描述原文：
//	"Values range from 0 (off) to 3 (high)."
//
//	官方 teslamotors/vehicle-command → pkg/vehicle/climate.go 的 `type Level int`：
//	LevelOff=0 / LevelLow=1 / LevelMed=2 / LevelHigh=3
//
// ⇒ 两边一致 ⇒ 1/2 可以安全地叫「低档 / 中档」。
var cabinSeatLevelTexts = map[int]string{
	0: "关",
	1: "低档",
	2: "中档",
	3: "高档",
}

// --------------------------------------------------------------------------
// 对外结构
// --------------------------------------------------------------------------

// CabinState 座舱的当下状态（全部来自遥测）。
//
// ⚠️ **每个可空字段都是刻意的** ——
//
//	nil = **没读到**（车没推这个字段 / 固件太老 / 值认不出来）
//	≠ 「关」。
//
// 这是 ADR #25 的硬约束：把「读不到」说成「全关」是**假安全**。
type CabinState struct {
	// Source 这批结论的来源（目前只有 telemetry / 空）。
	Source string `json:"source"`
	// At 这批结论的**最旧**采集时刻（保守：只要有一项是旧的，整组就不能说成新的）。
	At *time.Time `json:"at"`
	// Stale 这个时刻是不是「车断线前的最后已知值」。
	Stale bool `json:"stale"`

	// ClimateOn 空调系统是否开着（nil = 不知道）。
	//
	// ⚠️ 口径：`HvacPower` 官方明确等价于 `climate_state.is_climate_on`。
	//   Off ⇒ 关；On / Precondition / OverheatProtect ⇒ 开；
	//   Unknown(0) ⇒ **不知道**（不当成「关」）。
	ClimateOn *bool `json:"climate_on"`
	// ClimateText 给用户看的中文（认不出来不编 ⇒ 摊「状态未知」）。
	ClimateText string `json:"climate_text"`
	// ClimatePower 官方 `HvacPowerState` 的原始编号（"0"~"4"）。
	ClimatePower string `json:"climate_power"`
	// AutoMode 官方 `HvacAutoModeState` 的中文（自动 / 手动接管 / 状态未知）。
	AutoMode string `json:"auto_mode"`
	// ACEnabled 空调压缩机是否开着（`HvacACEnabled`，nil = 不知道）。
	ACEnabled *bool `json:"ac_enabled"`
	// FanSpeed 风速（`HvacFanSpeed`，nil = 不知道）。
	FanSpeed *int `json:"fan_speed"`
	// DriverTempC 主驾设定温度（`HvacLeftTemperatureRequest`，摄氏度）。
	DriverTempC *float64 `json:"driver_temp_c"`
	// PassengerTempC 副驾设定温度（`HvacRightTemperatureRequest`，摄氏度）。
	PassengerTempC *float64 `json:"passenger_temp_c"`

	// SeatHeater 读到的座椅加热（**只列读到的**，没读到的不列 —— ADR #25）。
	SeatHeater []CabinSeatHeater `json:"seat_heater"`
	// WheelHeater 方向盘加热（`HvacSteeringWheelHeatLevel`）。
	WheelHeater *CabinLevel `json:"steering_wheel_heater"`
	// WheelHeaterAuto 方向盘加热是否设为自动（`HvacSteeringWheelHeatAuto`）。
	WheelHeaterAuto *bool `json:"steering_wheel_heater_auto"`
}

// CabinSeatHeater 一个座位的加热状态。
type CabinSeatHeater struct {
	// Seat 座位名（主驾 / 副驾）。
	Seat string `json:"seat"`
	// Level 官方档位（0=关 … 3=高档；nil = 不知道）。
	Level *int `json:"level"`
	// Text 中文档位（关 / 低档 / 中档 / 高档；认不出来 ⇒ 「未知」）。
	Text string `json:"text"`
}

// CabinLevel 一个档位（方向盘加热用）。
type CabinLevel struct {
	// Level 官方给来的原始整数（**原样**，不解释成关/低/中/高）。
	Level *int `json:"level"`
	// Text 给用户看的中文。
	//
	// ⚠️ 方向盘加热的档位**官方没给范围** ⇒ 这里写「档位 N」，
	//   **不编**「关 / 低档 / 中档 / 高档」（那是凭空造出来的）。
	Text string `json:"text"`
}

// --------------------------------------------------------------------------
// 解析
// --------------------------------------------------------------------------

// cabinStateFromTelemetry 从遥测字段索引里解析座舱状态。
//
// ⚠️ **纯函数**（不碰 DB）⇒ 可以直接单测，而且**必须**单测 ——
// 这里错一次，飞书就会把「没读到」说成「关着」。
//
// ⚠️ 「没读到」与「读到了但是关」必须分得开：
//   - 字段没来 ⇒ 对应项保持 nil / 不进列表；
//   - 字段来了但值认不出来 ⇒ 同样当「没读到」。
func cabinStateFromTelemetry(idx map[string]telemetryFieldView) CabinState {
	var out CabinState
	var used []telemetryFieldView

	// ── 空调电源（HvacPower）──────────────────────────────────
	if f, ok := idx["HvacPower"]; ok {
		used = append(used, f)
		code, text, known := telemetryParseState(f.Text,
			telemetryHvacPowerStateNames, telemetryHvacPowerStates)
		if known {
			out.ClimatePower = code
			out.ClimateText = text
			switch code {
			case "1": // Off
				b := false
				out.ClimateOn = &b
			case "2", "3", "4": // On / Precondition / OverheatProtect
				b := true
				out.ClimateOn = &b
			}
			// ⚠️ "0"（Unknown）⇒ ClimateOn 保持 nil —— 车自己说「不知道」，
			//    我们也不能替它说「关」（ADR #25）。
		}
	}

	// ── 空调自动模式 ──────────────────────────────────────────
	if f, ok := idx["HvacAutoMode"]; ok {
		used = append(used, f)
		if _, text, known := telemetryParseState(f.Text,
			telemetryHvacAutoModeStateNames, telemetryHvacAutoModeStates); known {
			out.AutoMode = text
		}
	}

	// ── 空调压缩机 ────────────────────────────────────────────
	if f, ok := idx["HvacACEnabled"]; ok {
		used = append(used, f)
		if b, known := cabinBoolFromText(f.Text); known {
			out.ACEnabled = &b
		}
	}

	// ── 风速 ──────────────────────────────────────────────────
	if f, ok := idx["HvacFanSpeed"]; ok {
		used = append(used, f)
		if n, known := cabinIntFromText(f.Text); known {
			out.FanSpeed = &n
		}
	}

	// ── 设定温度 ──────────────────────────────────────────────
	if f, ok := idx["HvacLeftTemperatureRequest"]; ok {
		used = append(used, f)
		if v, known := cabinFloatFromText(f.Text); known {
			out.DriverTempC = &v
		}
	}
	if f, ok := idx["HvacRightTemperatureRequest"]; ok {
		used = append(used, f)
		if v, known := cabinFloatFromText(f.Text); known {
			out.PassengerTempC = &v
		}
	}

	// ── 座椅加热 ──────────────────────────────────────────────
	for _, s := range []struct{ field, seat string }{
		{"SeatHeaterLeft", "主驾"},
		{"SeatHeaterRight", "副驾"},
	} {
		f, ok := idx[s.field]
		if !ok {
			continue
		}
		used = append(used, f)
		n, known := cabinIntFromText(f.Text)
		if !known {
			continue
		}
		text, inRange := cabinSeatLevelTexts[n]
		if !inRange {
			// ⚠️ 官方只定义了 0..3 ⇒ 超出范围**不当成「关」**，也不硬套档位名。
			text = "未知"
		}
		level := n
		out.SeatHeater = append(out.SeatHeater, CabinSeatHeater{
			Seat:  s.seat,
			Level: &level,
			Text:  text,
		})
	}

	// ── 方向盘加热 ────────────────────────────────────────────
	if f, ok := idx["HvacSteeringWheelHeatLevel"]; ok {
		used = append(used, f)
		if n, known := cabinIntFromText(f.Text); known {
			level := n
			out.WheelHeater = &CabinLevel{
				Level: &level,
				// ⚠️ 官方没给方向盘加热档位的范围 ⇒ 只报原值。
				Text: "档位 " + strconv.Itoa(n),
			}
		}
	}
	if f, ok := idx["HvacSteeringWheelHeatAuto"]; ok {
		used = append(used, f)
		if b, known := cabinBoolFromText(f.Text); known {
			out.WheelHeaterAuto = &b
		}
	}

	// ⚠️ 采集时刻取**最旧**的那个（保守）：
	//    同一批里只要有一项是旧的，整组就不能说成「都是现在的」。
	if at, ok := cabinOldest(used); ok {
		out.At = at
		out.Stale = cabinAnyStale(used)
	}
	// ⚠️ **一个字段都没到 ⇒ 不算「有遥测来源」** ——
	//    来源写「遥测」会让模型以为「我们从遥测读到的是「关」」，
	//    而真相是「遥测里根本没有座舱这批字段」。
	if len(used) > 0 {
		out.Source = "telemetry"
	}
	return out
}

// cabinOldest 一组字段里最旧的采集时刻。
func cabinOldest(fields []telemetryFieldView) (*time.Time, bool) {
	var oldest *time.Time
	for _, f := range fields {
		if f.At == nil {
			continue
		}
		if oldest == nil || f.At.Before(*oldest) {
			oldest = f.At
		}
	}
	return oldest, oldest != nil
}

// cabinAnyStale 这组字段里有没有任何一个是陈旧的。
func cabinAnyStale(fields []telemetryFieldView) bool {
	for _, f := range fields {
		if f.Stale {
			return true
		}
	}
	return false
}

// --------------------------------------------------------------------------
// 取值的解析（认官方 protojson 的三种形态）
// --------------------------------------------------------------------------

// cabinBoolFromText 把一个遥测字段的原文当布尔看。
//
// ⚠️ 官方 README 明说字段类型会随固件漂移（数字 12.3 / 字符串 "12.3" 两种都见过）
// ⇒ 布尔也要认「裸值」与「带引号的值」。
//
// ⚠️ **认不出来就返回 known=false** —— 调用方**摊「没读到」**，不当成 false。
func cabinBoolFromText(raw string) (bool, bool) {
	s := strings.TrimSpace(raw)
	s = strings.Trim(s, "\"")
	switch strings.ToLower(s) {
	case "true":
		return true, true
	case "false":
		return false, true
	}
	return false, false
}

// cabinIntFromText 把一个遥测字段的原文当整数看。
//
// ⚠️ 非整数（如 2.5）**不算** —— 官方档位都是整数，
// 出现小数说明我们没认出它真正的形态 ⇒ 当「没读到」。
func cabinIntFromText(raw string) (int, bool) {
	s := strings.TrimSpace(raw)
	s = strings.Trim(s, "\"")
	if s == "" {
		return 0, false
	}
	n, err := strconv.Atoi(s)
	if err != nil {
		return 0, false
	}
	return n, true
}

// cabinFloatFromText 把一个遥测字段的原文当浮点数看（设定温度是 real）。
func cabinFloatFromText(raw string) (float64, bool) {
	s := strings.TrimSpace(raw)
	s = strings.Trim(s, "\"")
	if s == "" {
		return 0, false
	}
	v, err := strconv.ParseFloat(s, 64)
	if err != nil {
		return 0, false
	}
	return v, true
}
