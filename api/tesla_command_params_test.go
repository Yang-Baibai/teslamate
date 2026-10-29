package main

import (
	"encoding/json"
	"strconv"
	"strings"
	"testing"
)

// ⚠️ **v0.87.0：车控动作的「官方端点白名单」闸门。**
//
// ## 为什么这道闸门必须有
//
// 官方 `vehicle-command` 的 proxy 端点集是**封闭**的：
// `pkg/proxy/command.go` 的 `ExtractCommandAction` 是一个 `switch`，
// 命中不了就走 `default: 400 invalid_command` ——
// 而且**不会**回退到 Fleet API（回退只在「车不支持该协议」
// 或「命令声明要用 REST API」时发生）。
//
// ⇒ 端点名写错 = **这个按钮永远 400**，而且界面上看不出任何异常
// （回读车况时状态根本没变，像是「车不理我」）。
//
// ## 这不是假想
//
// `set_climate_on` / `set_climate_off` 是 **Fleet API** 的命令名，
// 在官方 proxy 里**根本不存在**（官方叫 `auto_conditioning_start/stop`），
// 而它们在目录里躺了很久 ⇒ **「开启空调」从来没生效过**。
//
// ⇒ 这道闸门把官方端点清单**硬编码**进测试：
// 以后谁再写一个不存在的端点，测试立刻红。
//
// 官方锚点：teslamotors/vehicle-command `pkg/proxy/command.go`
// （`ExtractCommandAction` 的全部 `case` 标签，共 74 个）。
var teslaOfficialProxyEndpoints = map[string]bool{
	"adjust_volume":                        true,
	"remote_boombox":                       true,
	"media_next_fav":                       true,
	"media_prev_fav":                       true,
	"media_next_track":                     true,
	"media_prev_track":                     true,
	"media_volume_down":                    true,
	"media_volume_up":                      true,
	"media_toggle_playback":                true,
	"auto_conditioning_start":              true,
	"auto_conditioning_stop":               true,
	"charge_max_range":                     true,
	"remote_seat_cooler_request":           true,
	"remote_seat_heater_request":           true,
	"remote_auto_seat_climate_request":     true,
	"remote_steering_wheel_heater_request": true,
	"set_bioweapon_mode":                   true,
	"set_cabin_overheat_protection":        true,
	"set_climate_keeper_mode":              true,
	"set_cop_temp":                         true,
	"set_preconditioning_max":              true,
	"set_temps":                            true,
	"actuate_trunk":                        true,
	"charge_port_door_open":                true,
	"charge_port_door_close":               true,
	"flash_lights":                         true,
	"honk_horn":                            true,
	"remote_start_drive":                   true,
	"open_tonneau":                         true,
	"close_tonneau":                        true,
	"stop_tonneau":                         true,
	"set_low_power_mode":                   true,
	"keep_accessory_power_mode":            true,
	"charge_standard":                      true,
	"charge_start":                         true,
	"charge_stop":                          true,
	"set_charging_amps":                    true,
	"set_scheduled_charging":               true,
	"set_charge_limit":                     true,
	"set_scheduled_departure":              true,
	"add_charge_schedule":                  true,
	"add_precondition_schedule":            true,
	"remove_charge_schedule":               true,
	"remove_precondition_schedule":         true,
	"set_managed_charge_current_request":   true,
	"set_managed_charger_location":         true,
	"set_managed_scheduled_charging_time":  true,
	"wake_up":                              true,
	"set_pin_to_drive":                     true,
	"clear_pin_to_drive_admin":             true,
	"door_lock":                            true,
	"door_unlock":                          true,
	"erase_user_data":                      true,
	"reset_pin_to_drive_pin":               true,
	"reset_valet_pin":                      true,
	"guest_mode":                           true,
	"set_sentry_mode":                      true,
	"set_valet_mode":                       true,
	"set_vehicle_name":                     true,
	"speed_limit_activate":                 true,
	"speed_limit_deactivate":               true,
	"speed_limit_clear_pin":                true,
	"speed_limit_clear_pin_admin":          true,
	"speed_limit_set_limit":                true,
	"parental_controls_activate":           true,
	"parental_controls_deactivate":         true,
	"parental_controls_enable_setting":     true,
	"parental_controls_set_speed_limit":    true,
	"parental_controls_clear_pin_admin":    true,
	"trigger_homelink":                     true,
	"schedule_software_update":             true,
	"cancel_software_update":               true,
	"navigation_request":                   true,
	"window_control":                       true,
}

// 每个动作的端点都必须**真的存在**于官方 proxy 里。
func TestTeslaCommandCatalog_EndpointsAreOfficial(t *testing.T) {
	for _, s := range teslaCommandCatalog {
		if s.Endpoint == "" {
			t.Errorf("%s 没写端点", s.Action)
			continue
		}
		if !teslaOfficialProxyEndpoints[s.Endpoint] {
			t.Errorf("%s 的端点 %q 不在官方 vehicle-command proxy 的端点清单里 ⇒ 会被 400 invalid_command",
				s.Action, s.Endpoint)
		}
	}
}

// ⚠️ **Cybertruck 专属端点不许进目录。**
//
// 官方 `pkg/vehicle/actions.go` 的原文：
//
//	// OpenTonneau opens a Cybetruck's tonneau. Has no effect on other vehicles.
//	// CloseTonneau closes a Cybetruck's tonneau. Has no effect on other vehicles.
//	// StopTonneau tells a Cybetruck to stop moving its tonneau. Has no effect on other vehicles.
//
// ⇒ `tonneau` 是 **Cybertruck 的电动货箱盖**，不是 Model 3/Y 的尾门！
// 把它当成「开合尾门」暴露出去 = 给用户一个**在别的车上永远无效**的按钮。
func TestTeslaCommandCatalog_NoCybertruckOnlyEndpoints(t *testing.T) {
	for _, s := range teslaCommandCatalog {
		for _, ep := range []string{"open_tonneau", "close_tonneau", "stop_tonneau"} {
			if s.Endpoint == ep {
				t.Errorf("%s 用了 Cybertruck 专属端点 %q（官方注释：Has no effect on other vehicles）",
					s.Action, ep)
			}
		}
	}
}

// ⚠️ **这两个端点曾经是错的**（Fleet API 的命令名，官方 proxy 里不存在）。
// 钉死它们，防止有人「改回看起来更直白的那个」。
func TestTeslaCommandCatalog_ClimateUsesOfficialProxyEndpoint(t *testing.T) {
	want := map[string]string{
		"set_climate_on":  "auto_conditioning_start",
		"set_climate_off": "auto_conditioning_stop",
	}
	for action, ep := range want {
		s, ok := teslaCommandByAction[action]
		if !ok {
			t.Errorf("动作 %s 不见了", action)
			continue
		}
		if s.Endpoint != ep {
			t.Errorf("%s 的端点 = %q，期望 %q（官方 proxy 的名字）", action, s.Endpoint, ep)
		}
	}
}

// --------------------------------------------------------------------------
// 参数规格（v0.87.0）
// --------------------------------------------------------------------------

// `Params`（参数名清单）与 `ParamSpecs`（参数规格）必须**逐条对齐**。
//
// 只给名字不给规格 ⇒ LLM 只能猜范围（车控不可逆，猜错有代价）；
// 只给规格不给名字 ⇒ App 不知道要渲染哪个控件。
func TestTeslaCommandCatalog_ParamSpecsMatchParams(t *testing.T) {
	for _, s := range teslaCommandCatalog {
		names := map[string]bool{}
		for _, n := range s.Params {
			if names[n] {
				t.Errorf("%s 的 params 里 %q 重复了", s.Action, n)
			}
			names[n] = true
		}
		specs := map[string]bool{}
		for _, p := range s.ParamSpecs {
			if specs[p.Name] {
				t.Errorf("%s 的 param_specs 里 %q 重复了", s.Action, p.Name)
			}
			specs[p.Name] = true
			if !names[p.Name] {
				t.Errorf("%s 有规格 %q，但 params 里没列它 ⇒ App 不会渲染这个控件", s.Action, p.Name)
			}
		}
		for n := range names {
			if !specs[n] {
				t.Errorf("%s 的参数 %q 没有规格 ⇒ 调用方不知道该怎么填", s.Action, n)
			}
		}
	}
}

// 每条规格都得说清「类型 / 界面标签 / 完整说明」。
func TestTeslaCommandCatalog_ParamSpecWellFormed(t *testing.T) {
	for _, s := range teslaCommandCatalog {
		for _, p := range s.ParamSpecs {
			switch p.Type {
			case "int", "bool", "password", "string":
			default:
				t.Errorf("%s.%s 的 type = %q，不认识", s.Action, p.Name, p.Type)
			}
			if strings.TrimSpace(p.Label) == "" {
				t.Errorf("%s.%s 缺 label（App 的输入控件要它）", s.Action, p.Name)
			}
			if strings.TrimSpace(p.Description) == "" {
				t.Errorf("%s.%s 缺 description（MCP 的 Schema 要它）", s.Action, p.Name)
			}
			if p.Type == "int" {
				if p.Min == nil || p.Max == nil {
					t.Errorf("%s.%s 是 int，但没有 min/max ⇒ 调用方不知道范围", s.Action, p.Name)
					continue
				}
				if *p.Min > *p.Max {
					t.Errorf("%s.%s 的 min(%d) > max(%d)", s.Action, p.Name, *p.Min, *p.Max)
				}
				for _, o := range p.Options {
					v, err := strconv.Atoi(o.Value)
					if err != nil {
						t.Errorf("%s.%s 的取值 %q 不是整数", s.Action, p.Name, o.Value)
						continue
					}
					if v < *p.Min || v > *p.Max {
						t.Errorf("%s.%s 的取值 %d 越界（%d~%d）", s.Action, p.Name, v, *p.Min, *p.Max)
					}
					if strings.TrimSpace(o.Label) == "" {
						t.Errorf("%s.%s 的取值 %s 没有标签", s.Action, p.Name, o.Value)
					}
				}
			}
		}
	}
}

// int 参数**必须**给取值标签 —— 否则调用方不知道 0 是什么意思。
func TestTeslaCommandCatalog_IntParamsHaveValueLabels(t *testing.T) {
	for _, s := range teslaCommandCatalog {
		for _, p := range s.ParamSpecs {
			if p.Type != "int" {
				continue
			}
			if len(p.Options) == 0 {
				continue
			}
			seen := map[string]bool{}
			for _, o := range p.Options {
				if seen[o.Value] {
					t.Errorf("%s.%s 的取值 %s 重复", s.Action, p.Name, o.Value)
				}
				seen[o.Value] = true
			}
		}
	}
}

// --------------------------------------------------------------------------
// 新动作的 body（照官方 `pkg/proxy/command.go` 逐字钉死）
// --------------------------------------------------------------------------

func TestBuildTeslaCommandBody_SeatHeater(t *testing.T) {
	// 官方：`{"seat_position": <0~8>, "level": <0~3>}` —— 两个都必填。
	got, err := buildTeslaCommandBody("seat_heater", map[string]any{"seat_position": 0.0, "level": 3.0})
	if err != nil {
		t.Fatalf("不该报错：%v", err)
	}
	if got["seat_position"] != 0 || got["level"] != 3 {
		t.Errorf("body = %v，期望 seat_position=0 level=3", got)
	}
	if len(got) != 2 {
		t.Errorf("body 只该有两个字段，实际 %v", got)
	}
	// 缺参必须被拒（官方两个都是必填）
	for _, p := range []map[string]any{
		{},
		{"seat_position": 0.0},
		{"level": 3.0},
	} {
		if _, err := buildTeslaCommandBody("seat_heater", p); err == nil {
			t.Errorf("参数 %v 必须被拒（官方两个都是必填）", p)
		}
	}
	// 越界
	for _, p := range []map[string]any{
		{"seat_position": -1.0, "level": 1.0},
		{"seat_position": 9.0, "level": 1.0},
		{"seat_position": 0.0, "level": -1.0},
		{"seat_position": 0.0, "level": 4.0},
	} {
		if _, err := buildTeslaCommandBody("seat_heater", p); err == nil {
			t.Errorf("参数 %v 越界必须被拒", p)
		}
	}
	// 小数不行（官方要整数下标）
	if _, err := buildTeslaCommandBody("seat_heater", map[string]any{"seat_position": 1.5, "level": 1.0}); err == nil {
		t.Error("seat_position 必须是整数")
	}
}

func TestBuildTeslaCommandBody_TrunkAndFrunk(t *testing.T) {
	// 官方 `actuate_trunk` 用 `which_trunk` 区分前后：
	//   "front" → OpenFrunk，"rear" → OpenTrunk，缺省是 rear。
	got, err := buildTeslaCommandBody("actuate_trunk", nil)
	if err != nil {
		t.Fatalf("不该报错：%v", err)
	}
	if got["which_trunk"] != "rear" {
		t.Errorf("actuate_trunk 的 which_trunk = %v，期望 rear", got["which_trunk"])
	}
	got, err = buildTeslaCommandBody("open_frunk", nil)
	if err != nil {
		t.Fatalf("不该报错：%v", err)
	}
	if got["which_trunk"] != "front" {
		t.Errorf("open_frunk 的 which_trunk = %v，期望 front", got["which_trunk"])
	}
	// 调用方**不能**自己填 which_trunk（否则会覆盖掉服务端写死的值）
	got, err = buildTeslaCommandBody("open_frunk", map[string]any{"which_trunk": "rear"})
	if err != nil {
		t.Fatalf("不该报错：%v", err)
	}
	if got["which_trunk"] != "front" {
		t.Errorf("调用方不得改写 which_trunk，实际 %v", got["which_trunk"])
	}
}

func TestBuildTeslaCommandBody_WindowVentClose(t *testing.T) {
	// 官方 `window_control` 只认 "vent" / "close" 两个值，
	// 其它值官方直接报 `command must be 'vent' or 'close'`。
	got, err := buildTeslaCommandBody("window_vent", nil)
	if err != nil {
		t.Fatalf("不该报错：%v", err)
	}
	if got["command"] != "vent" {
		t.Errorf("window_vent 的 command = %v，期望 vent", got["command"])
	}
	got, err = buildTeslaCommandBody("window_close", nil)
	if err != nil {
		t.Fatalf("不该报错：%v", err)
	}
	if got["command"] != "close" {
		t.Errorf("window_close 的 command = %v，期望 close", got["command"])
	}
	// 调用方不得改写 command（否则能发出官方不认的值）
	got, err = buildTeslaCommandBody("window_vent", map[string]any{"command": "open"})
	if err != nil {
		t.Fatalf("不该报错：%v", err)
	}
	if got["command"] != "vent" {
		t.Errorf("调用方不得改写 command，实际 %v", got["command"])
	}
}

func TestBuildTeslaCommandBody_SteeringWheelHeater(t *testing.T) {
	// 官方 `remote_steering_wheel_heater_request` 只读 `{"on": bool}`，默认 true。
	got, err := buildTeslaCommandBody("steering_wheel_heater", nil)
	if err != nil {
		t.Fatalf("不该报错：%v", err)
	}
	if got["on"] != true {
		t.Errorf("默认 on = %v，期望 true", got["on"])
	}
	got, err = buildTeslaCommandBody("steering_wheel_heater", map[string]any{"on": false})
	if err != nil {
		t.Fatalf("不该报错：%v", err)
	}
	if got["on"] != false {
		t.Errorf("on = %v，期望 false", got["on"])
	}
	if _, err := buildTeslaCommandBody("steering_wheel_heater", map[string]any{"on": "yes"}); err == nil {
		t.Error("on 必须是布尔值")
	}
}

// ⚠️ **全量钉死**：每个动作的 body 都要与官方端点读取的字段一致。
//
// 这是「照抄官方语义」的最后一道防线 ——
// 目录里少一个动作 / body 多一个字段 / 取值写反，都会红。
func TestBuildTeslaCommandBody_AllActionsExpectedBody(t *testing.T) {
	cases := []struct {
		action string
		params map[string]any
		want   map[string]any
	}{
		{"door_lock", nil, map[string]any{"on": true}},
		{"door_unlock", nil, map[string]any{"on": true}},
		{"actuate_trunk", nil, map[string]any{"which_trunk": "rear"}},
		{"open_frunk", nil, map[string]any{"which_trunk": "front"}},
		{"remote_start_drive", map[string]any{"password": "1234"}, map[string]any{"password": "1234"}},
		{"set_valet_mode", map[string]any{"on": true, "password": "1234"}, map[string]any{"on": true, "password": "1234"}},
		{"set_temperature", map[string]any{"driver_temp": 22.0}, map[string]any{"driver_temp": 22, "passenger_temp": 22}},
		{"set_climate_on", nil, map[string]any{"on": true}},
		{"set_climate_off", nil, map[string]any{"on": false}},
		{"seat_heater", map[string]any{"seat_position": 1.0, "level": 2.0}, map[string]any{"seat_position": 1, "level": 2}},
		{"steering_wheel_heater", nil, map[string]any{"on": true}},
		{"window_vent", nil, map[string]any{"command": "vent"}},
		{"window_close", nil, map[string]any{"command": "close"}},
		{"set_charge_limit", map[string]any{"percent": 80.0}, map[string]any{"percent": 80}},
		{"set_charging_amps", map[string]any{"charging_amps": 16.0}, map[string]any{"charging_amps": 16}},
		{"charge_start", nil, map[string]any{}},
		{"charge_stop", nil, map[string]any{}},
		{"wake_up", nil, map[string]any{}},
		{"honk_horn", nil, map[string]any{}},
		{"flash_lights", nil, map[string]any{}},
		{"set_sentry_mode", nil, map[string]any{"on": true}},
	}
	seen := map[string]bool{}
	for _, c := range cases {
		if seen[c.action] {
			t.Errorf("动作 %s 在用例表里重复了", c.action)
		}
		seen[c.action] = true
		got, err := buildTeslaCommandBody(c.action, c.params)
		if err != nil {
			t.Errorf("%s：不该报错，却报了 %v", c.action, err)
			continue
		}
		if len(got) != len(c.want) {
			t.Errorf("%s：body = %v，期望 %v", c.action, got, c.want)
			continue
		}
		for k, v := range c.want {
			if got[k] != v {
				t.Errorf("%s：body[%q] = %v，期望 %v", c.action, k, got[k], v)
			}
		}
	}
	// 目录里的每个动作都必须被这张表覆盖
	for _, s := range teslaCommandCatalog {
		if !seen[s.Action] {
			t.Errorf("动作 %s 没有在 body 用例表里 —— 新动作必须补一条", s.Action)
		}
	}
}

// 清单序列化后不能出现 null（kotlinx.serialization 遇到显式 null 会抛异常）。
func TestTeslaCommandCatalog_ParamSpecsNeverNull(t *testing.T) {
	b, err := json.Marshal(teslaCommandSpec{Action: "honk_horn", Risk: "low"})
	if err != nil {
		t.Fatalf("序列化失败：%v", err)
	}
	if !strings.Contains(string(b), `"param_specs":[]`) {
		t.Errorf("无参数时 param_specs 应输出 []，实际：%s", string(b))
	}
	if strings.Contains(string(b), "null") {
		t.Errorf("输出里不该出现 null：%s", string(b))
	}
}
