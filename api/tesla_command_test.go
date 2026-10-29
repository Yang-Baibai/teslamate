package main

import (
	"encoding/json"
	"strings"
	"testing"
)

// ⚠️ **v0.66.0（第 33 轮「车辆控制下沉」）**：车控参数校验的回归测试。
//
// 车控是**不可逆**的（门开了就是开了）⇒ 参数校验必须在服务端**强制**，
// 客户端的提示只是体验、不是安全边界。
// 这里把每个动作的 body 与越界/缺参的拒绝行为都钉死。

func TestBuildTeslaCommandBody_StaticBodies(t *testing.T) {
	cases := []struct {
		action string
		want   map[string]any
	}{
		{"door_lock", map[string]any{"on": true}},
		{"door_unlock", map[string]any{"on": true}},
		{"set_climate_on", map[string]any{"on": true}},
		{"set_climate_off", map[string]any{"on": false}},
		{"honk_horn", map[string]any{}},
		{"flash_lights", map[string]any{}},
		{"charge_start", map[string]any{}},
		{"charge_stop", map[string]any{}},
		{"actuate_trunk", map[string]any{}},
		{"wake_up", map[string]any{}},
	}
	for _, c := range cases {
		got, err := buildTeslaCommandBody(c.action, nil)
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
}

func TestBuildTeslaCommandBody_UnknownAction(t *testing.T) {
	if _, err := buildTeslaCommandBody("self_destruct", nil); err == nil {
		t.Fatal("未知动作必须报错 —— 不能把没校验过的动作发出去")
	}
}

func TestBuildTeslaCommandBody_SetTemperature(t *testing.T) {
	// 只给 driver_temp ⇒ passenger 跟着 driver
	got, err := buildTeslaCommandBody("set_temperature", map[string]any{"driver_temp": 22.0})
	if err != nil {
		t.Fatalf("不该报错：%v", err)
	}
	if got["driver_temp"] != 22 || got["passenger_temp"] != 22 {
		t.Errorf("body = %v，期望 driver=passenger=22", got)
	}
	// 越界
	for _, bad := range []float64{14.0, 29.0} {
		if _, err := buildTeslaCommandBody("set_temperature", map[string]any{"driver_temp": bad}); err == nil {
			t.Errorf("driver_temp=%v 越界必须被拒（允许 15~28）", bad)
		}
	}
	// 缺参
	if _, err := buildTeslaCommandBody("set_temperature", map[string]any{}); err == nil {
		t.Error("缺 driver_temp 必须被拒")
	}
}

func TestBuildTeslaCommandBody_ChargeLimitAndAmps(t *testing.T) {
	got, err := buildTeslaCommandBody("set_charge_limit", map[string]any{"percent": 80.0})
	if err != nil {
		t.Fatalf("不该报错：%v", err)
	}
	if got["percent"] != 80 {
		t.Errorf("percent = %v，期望 80", got["percent"])
	}
	// 低于 50 车端会拒
	if _, err := buildTeslaCommandBody("set_charge_limit", map[string]any{"percent": 49.0}); err == nil {
		t.Error("percent=49 必须被拒（Tesla 最低 50）")
	}
	if _, err := buildTeslaCommandBody("set_charge_limit", map[string]any{"percent": 101.0}); err == nil {
		t.Error("percent=101 必须被拒（最高 100）")
	}
	// 电流：Model 3 Performance 在 208V 下最高 48A
	if _, err := buildTeslaCommandBody("set_charging_amps", map[string]any{"charging_amps": 49.0}); err == nil {
		t.Error("charging_amps=49 必须被拒（最高 48）")
	}
	if _, err := buildTeslaCommandBody("set_charging_amps", map[string]any{"charging_amps": 16.0}); err != nil {
		t.Errorf("charging_amps=16 不该被拒：%v", err)
	}
}

func TestBuildTeslaCommandBody_Credential(t *testing.T) {
	// 口令太短
	for _, pwd := range []string{"", "123"} {
		if _, err := buildTeslaCommandBody("remote_start_drive", map[string]any{"password": pwd}); err == nil {
			t.Errorf("password=%q 必须被拒（4~20 位）", pwd)
		}
	}
	// 合规
	got, err := buildTeslaCommandBody("remote_start_drive", map[string]any{"password": "1234"})
	if err != nil {
		t.Fatalf("不该报错：%v", err)
	}
	if got["password"] != "1234" {
		t.Errorf("password = %v，期望 1234", got["password"])
	}
	// 代客模式：on + password 都要
	if _, err := buildTeslaCommandBody("set_valet_mode", map[string]any{"on": true}); err == nil {
		t.Error("代客模式缺 password 必须被拒")
	}
}

func TestBuildTeslaCommandBody_SentryMode(t *testing.T) {
	// 默认开
	got, err := buildTeslaCommandBody("set_sentry_mode", map[string]any{})
	if err != nil {
		t.Fatalf("不该报错：%v", err)
	}
	if got["on"] != true {
		t.Errorf("默认 on = %v，期望 true", got["on"])
	}
	// 显式关
	got, err = buildTeslaCommandBody("set_sentry_mode", map[string]any{"on": false})
	if err != nil {
		t.Fatalf("不该报错：%v", err)
	}
	if got["on"] != false {
		t.Errorf("on = %v，期望 false", got["on"])
	}
}

func TestTeslaCommandCatalog_RiskLevels(t *testing.T) {
	// ⚠️ 高危名单必须与文案一致 —— 解锁/尾门/远程启动会动车或开孔。
	high := map[string]bool{
		"door_unlock":        true,
		"actuate_trunk":      true,
		"remote_start_drive": true,
	}
	for _, s := range teslaCommandCatalog {
		if s.Risk == "" {
			t.Errorf("%s 没写风险级别", s.Action)
		}
		if high[s.Action] && s.Risk != teslaCmdRiskHigh {
			t.Errorf("%s 必须是 high，实际 %s", s.Action, s.Risk)
		}
		if !high[s.Action] && s.Risk == teslaCmdRiskHigh {
			t.Errorf("%s 不该是 high", s.Action)
		}
	}
	if len(teslaCommandCatalog) != 16 {
		t.Errorf("动作数 = %d，期望 16", len(teslaCommandCatalog))
	}
}

func TestRedactCommandParams_HidesPassword(t *testing.T) {
	s := redactCommandParams("remote_start_drive", map[string]any{"password": "1234"})
	if contains(s, "1234") {
		t.Errorf("审计日志里不能出现明文口令：%s", s)
	}
	if !contains(s, "***") {
		t.Errorf("口令应被替换成 ***：%s", s)
	}
}

func contains(hay, needle string) bool {
	return len(hay) >= len(needle) && (func() bool {
		for i := 0; i+len(needle) <= len(hay); i++ {
			if hay[i:i+len(needle)] == needle {
				return true
			}
		}
		return false
	})()
}

// ⚠️ **v0.74.0（模拟器真机复核发现）**：车控清单的 JSON 形状。
//
// 现象：App 的车控卡只显示「读不到车控能力清单」，而服务端返回 200、动作也是 16 个。
// 原因：Go 的 nil slice 序列化成 `"params": null`，而 App 端的 kotlinx.serialization
// 遇到**显式 null** 会抛异常（它只对「键缺失」用默认值）⇒ 整份清单解析失败。
//
// 这条测试把「无参数 ⇒ []」钉死，防止有人改回 nil。
func TestTeslaCommandSpec_MarshalParamsNeverNull(t *testing.T) {
	b, err := json.Marshal(teslaCommandSpec{Action: "honk_horn", Risk: "low"})
	if err != nil {
		t.Fatalf("序列化失败：%v", err)
	}
	if !strings.Contains(string(b), `"params":[]`) {
		t.Errorf("无参数时 params 应输出 []，实际：%s", string(b))
	}
	if strings.Contains(string(b), "null") {
		t.Errorf("输出里不该出现 null：%s", string(b))
	}
}

func TestTeslaCommandSpec_MarshalKeepsParams(t *testing.T) {
	b, err := json.Marshal(teslaCommandSpec{Action: "set_temperature", Params: []string{"driver_temp"}})
	if err != nil {
		t.Fatalf("序列化失败：%v", err)
	}
	if !strings.Contains(string(b), `"params":["driver_temp"]`) {
		t.Errorf("有参数时 params 应原样输出，实际：%s", string(b))
	}
}

// 整份清单里不允许出现 "params":null —— 消费方（App / MCP）会直接解析失败。
func TestTeslaCommandCatalog_NoNullParams(t *testing.T) {
	b, err := json.Marshal(map[string]any{
		"actions": teslaCommandCatalog,
		"proxy":   true,
	})
	if err != nil {
		t.Fatalf("序列化失败：%v", err)
	}
	if strings.Contains(string(b), `"params":null`) {
		t.Errorf("清单里出现了 \"params\":null ⇒ App 端会解析失败")
	}
}

// --------------------------------------------------------------------------
// ⚠️ **v0.76.0**：车端「执行结果」的解析 —— 把假成功堵死。
//
// ## 为什么这是最重要的一条
//
// 原来只有一个 `sent` 标志，语义是「HTTP 2xx」。
// 于是车端**明确拒绝**（`{"response":{"result":false,"reason":"already_charging"}}`）
// 也会被报成「已下发」＝ **假成功**。
//
// 车没照做，App 却说搞定了 —— 用户会以为车没听指挥，**然后再点一次**
// （而「开始充电」这种命令点两次是有副作用的）。
//
// ⇒ 官方响应形状（developer.tesla.cn → Vehicle Commands）：
//
//	{"response": {"result": true|false, "reason": "..."}}
//
// 三态必须分清：**已执行** / **被拒绝** / **发出去了但没确认**。
// --------------------------------------------------------------------------

func TestParseTeslaCommandOutcome_Executed(t *testing.T) {
	body := map[string]any{
		"response": map[string]any{"result": true, "reason": ""},
	}
	executed, hasResult, reason := parseTeslaCommandOutcome(body)
	if !hasResult {
		t.Fatal("应该能读到 result")
	}
	if !executed {
		t.Error("result=true 应解析成已执行")
	}
	if reason != "" {
		t.Errorf("reason 应为空，实际 %q", reason)
	}
}

func TestParseTeslaCommandOutcome_Rejected(t *testing.T) {
	body := map[string]any{
		"response": map[string]any{"result": false, "reason": "already_charging"},
	}
	executed, hasResult, reason := parseTeslaCommandOutcome(body)
	if !hasResult {
		t.Fatal("应该能读到 result")
	}
	if executed {
		t.Error("result=false 必须解析成「没执行」—— 假成功就是从这儿漏出去的")
	}
	if reason != "already_charging" {
		t.Errorf("reason = %q，期望 already_charging", reason)
	}
}

func TestParseTeslaCommandOutcome_NoResult(t *testing.T) {
	// 拿到了 2xx 但没有车端确认 ⇒ 只能说「已下发」，不能说「已完成」。
	for _, body := range []map[string]any{
		nil,
		{},
		{"response": "base64-not-an-object"},
		{"response": map[string]any{"reason": "some text"}},
	} {
		executed, hasResult, _ := parseTeslaCommandOutcome(body)
		if hasResult {
			t.Errorf("%v 不该解析出 result", body)
		}
		if executed {
			t.Errorf("%v 不该解析成已执行", body)
		}
	}
}

// 结果条上的文案必须**分得出成败** —— 三个状态三句话。
func TestTeslaCommandReasonChinese(t *testing.T) {
	cases := []struct{ code, want string }{
		{"already_charging", "车端拒绝（already_charging）"}, // 未收录 ⇒ 保留原码，不臆造
		{"is_charging", "车已经在充电了"},
		{"not_charging", "车当前没有在充电"},
		{"disconnected", "充电枪没插"},
		{"no_power", "充电桩没有供电"},
		{"already_set", "充电上限已经是这个值了"},
		{"car_wash", "车在洗车模式"},
		{"not allowed", "车的当前状态不允许这个操作"},
	}
	for _, c := range cases {
		got := teslaCommandReasonChinese(c.code)
		if got != c.want {
			t.Errorf("%s ⇒ %q，期望 %q", c.code, got, c.want)
		}
	}
	// 空原因不该编出一句话
	if got := teslaCommandReasonChinese(""); got != "" {
		t.Errorf("空原因应返回空串，实际 %q", got)
	}
}

// 下划线写法与空格写法要能查到同一张表（Tesla 两种都用过）。
func TestTeslaNormalizeReason(t *testing.T) {
	if teslaNormalizeReason("already_set") != "already set" {
		t.Errorf("归一化不对：%q", teslaNormalizeReason("already_set"))
	}
	if teslaCommandReasonChinese("already_set") != "充电上限已经是这个值了" {
		t.Errorf("下划线写法没查到：%q", teslaCommandReasonChinese("already_set"))
	}
	if teslaCommandReasonChinese("already set") != "充电上限已经是这个值了" {
		t.Errorf("空格写法没查到：%q", teslaCommandReasonChinese("already set"))
	}
}

// ⚠️ **v0.76.0 真车实测**：Tesla 回来的原因码**带前缀** ——
// 文档里写的是裸码 `disconnected`，实际收到的是
// `"car could not execute command: disconnected"`。
//
// ⇒ 不剥前缀的话，最有用的那截原因会被埋掉，用户只看到一串英文。
func TestTeslaCommandReasonChinese_Prefixed(t *testing.T) {
	cases := []struct{ code, want string }{
		{"car could not execute command: disconnected", "充电枪没插"},
		{"car could not execute command: is_charging", "车已经在充电了"},
		{"car could not execute command: not_charging", "车当前没有在充电"},
		{"car could not execute command: already_set", "充电上限已经是这个值了"},
	}
	for _, c := range cases {
		got := teslaCommandReasonChinese(c.code)
		if got != c.want {
			t.Errorf("%q ⇒ %q，期望 %q", c.code, got, c.want)
		}
	}
	// 前缀剥掉后仍不认识 ⇒ 整句摊开（保留原文，不臆造）
	got := teslaCommandReasonChinese("car could not execute command: some_new_code")
	if got != "车端拒绝（car could not execute command: some_new_code）" {
		t.Errorf("未收录的原因应保留原文，实际 %q", got)
	}
}

// 每个动作都得有完成态文案 —— 结果条上要说「现在的样子」。
func TestTeslaCommandCatalog_HasDone(t *testing.T) {
	for _, s := range teslaCommandCatalog {
		if strings.TrimSpace(s.Done) == "" {
			t.Errorf("%s 缺少完成态文案（done）", s.Action)
		}
	}
}
