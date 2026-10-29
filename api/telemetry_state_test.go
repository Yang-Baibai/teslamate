package main

// ---------- Fleet Telemetry 的 V 字段（车辆实时数据）单测 ----------
//
// ⚠️ 这些用例守的是四件最容易出错、又最容易被忽略的事：
//
//  1. **字段名必须与官方 proto 逐字一致** —— 写错**不报错**，只是那个字段永远不来
//  2. **类型会漂移** —— 官方明说同一个字段可能是数字也可能是字符串
//  3. **时间诚实性** —— retained 重放时「收到时刻」≠「采集时刻」
//  4. **非有限数不能进库** —— Inf / NaN 存进 DOUBLE PRECISION 是脏数据

import (
	"math"
	"testing"
	"time"
)

// --------------------------------------------------------------------------
// ① 字段名：必须与官方 vehicle_data.proto 逐字一致
// --------------------------------------------------------------------------

// telemetryOfficialFieldNumbers 官方 `protos/vehicle_data.proto` 里
// `enum Field` 的**编号**（只列我们关心的这批）。
//
// ⚠️ 出处：teslamotors/fleet-telemetry → protos/vehicle_data.proto
//
//	这是一份**手抄的权威名单**，抄错会有两种后果：
//	- 抄错编号 ⇒ 以后有人按编号反查时对不上
//	- 名单里少了某个我们用到的字段 ⇒ 测试会当场报「这个字段不在官方名单里」
var telemetryOfficialFieldNumbers = map[string]int{
	"Odometer":            5,
	"Soc":                 8,
	"ChargeLimitSoc":      38,
	"EstBatteryRange":     40,
	"IdealBatteryRange":   41,
	"RatedRange":          32,
	"DoorState":           58,
	"Locked":              59,
	"FdWindow":            60,
	"FpWindow":            61,
	"RdWindow":            62,
	"RpWindow":            63,
	"SentryMode":          65,
	"TpmsPressureFl":      69,
	"TpmsPressureFr":      70,
	"TpmsPressureRl":      71,
	"TpmsPressureRr":      72,
	"InsideTemp":          85,
	"OutsideTemp":         86,
	"ChargeAmps":          49,
	"ChargerVoltage":      184,
	"DetailedChargeState": 179,
	"TpmsHardWarnings":    224,
	"TpmsSoftWarnings":    225,
	"ChargePortDoorOpen":  183,
	"IsolationResistance": 11,
	"Version":             68,
	// ⚠️ **v0.84.0**：`SoftwareUpdateVersion` = 「可安装的固件版本」。
	"SoftwareUpdateVersion": 220,
}

// TestTelemetryFieldNamesAreOfficial 我们用的每个字段名都必须在官方名单里。
//
// ⚠️ 这是**反幻觉的可执行版本**：
//
//	字段名写错**不会报错** —— 车照常推送、订阅照常成功，
//	只是那个字段**永远不来**（静默失效，最难查的一种）。
//	⇒ 把「名字对不对」变成一条会红的断言。
func TestTelemetryFieldNamesAreOfficial(t *testing.T) {
	for name := range telemetryFieldNames {
		if _, ok := telemetryOfficialFieldNumbers[name]; !ok {
			t.Errorf("字段 %q 不在官方 vehicle_data.proto 的 Field 枚举里 —— "+
				"写错不报错，只会让那个字段永远不来", name)
		}
	}
}

// TestTelemetryOfficialFieldNumbersUnique 官方名单里不能有重名。
//
// ⚠️ 重名意味着我们抄错了 proto —— 而 proto 是**唯一权威**，
// 抄错还可能引出「这个字段其实是另一个含义」这种更糟的问题。
func TestTelemetryOfficialFieldNumbersUnique(t *testing.T) {
	seen := map[int]string{}
	for name, num := range telemetryOfficialFieldNumbers {
		if num <= 0 {
			t.Errorf("字段 %q 的官方编号 %d 不合法", name, num)
		}
		if prev, ok := seen[num]; ok {
			t.Errorf("官方编号 %d 出现了两次：%s 与 %s", num, prev, name)
		}
		seen[num] = name
	}
}

// TestTelemetryFieldNamesHaveLabels 每个字段都该有人话名。
//
// ⚠️ 没标签的字段在界面上就只剩一个英文名 —— 车主看不懂。
func TestTelemetryFieldNamesHaveLabels(t *testing.T) {
	for name, label := range telemetryFieldNames {
		if label == "" {
			t.Errorf("字段 %q 没有中文标签", name)
		}
	}
}

// --------------------------------------------------------------------------
// ② 值的解析：类型漂移
// --------------------------------------------------------------------------

// TestParseTelemetryValueTypeDrift 官方明说字段类型会随固件版本漂移：
//
//	"a float value like the vehicle's speed might be received as 12.3 (numeric)
//	 in one version and as \"12.3\" (string) in another version"
//
//	（出处：teslamotors/fleet-telemetry → datastore/mqtt/README.md）
//
// ⚠️ 实测本机也确实见过字符串形态：
//
//	`DetailedChargeState` 收到的是 `"DetailedChargeStateDisconnected"`（带引号），
//	而 `Soc` 是裸数字。
func TestParseTelemetryValueTypeDrift(t *testing.T) {
	cases := []struct {
		name     string
		payload  string
		wantText string
		wantNum  *float64
		wantBool *bool
	}{
		{name: "裸数字", payload: "73.92228293867637", wantText: "73.92228293867637",
			wantNum: f64(73.92228293867637)},
		{name: "裸数字整数", payload: "0", wantText: "0", wantNum: f64(0)},
		{name: "负数", payload: "-12.5", wantText: "-12.5", wantNum: f64(-12.5)},
		{name: "裸 true", payload: "true", wantText: "true", wantBool: bTrue()},
		{name: "裸 false", payload: "false", wantText: "false", wantBool: bFalse()},
		{name: "带引号的枚举字符串", payload: `"DetailedChargeStateDisconnected"`,
			wantText: "DetailedChargeStateDisconnected"},
		// ⚠️ 下面两条是**类型漂移**本体：字符串形态的数字 / 布尔。
		{name: "字符串形态的数字", payload: `"12.3"`, wantText: "12.3", wantNum: f64(12.3)},
		{name: "字符串形态的布尔", payload: `"true"`, wantText: "true", wantBool: bTrue()},
		// ⚠️ 对象（DoorState）⇒ 原样留着，**不丢**。
		{name: "对象原样保留", payload: `{"DriverFront":false,"TrunkFront":true}`,
			wantText: `{"DriverFront":false,"TrunkFront":true}`},
		{name: "空载荷", payload: "", wantText: ""},
		{name: "只有空白", payload: "   ", wantText: ""},
	}
	for _, c := range cases {
		got := parseTelemetryValue([]byte(c.payload))
		if got.Text != c.wantText {
			t.Errorf("%s：Text = %q，期望 %q", c.name, got.Text, c.wantText)
		}
		if c.wantNum == nil {
			if got.Num != nil {
				t.Errorf("%s：Num = %v，期望 nil", c.name, *got.Num)
			}
		} else if got.Num == nil {
			t.Errorf("%s：Num = nil，期望 %v", c.name, *c.wantNum)
		} else if math.Abs(*got.Num-*c.wantNum) > 1e-9 {
			t.Errorf("%s：Num = %v，期望 %v", c.name, *got.Num, *c.wantNum)
		}
		if c.wantBool == nil {
			if got.Bool != nil {
				t.Errorf("%s：Bool = %v，期望 nil", c.name, *got.Bool)
			}
		} else if got.Bool == nil {
			t.Errorf("%s：Bool = nil，期望 %v", c.name, *c.wantBool)
		} else if *got.Bool != *c.wantBool {
			t.Errorf("%s：Bool = %v，期望 %v", c.name, *got.Bool, *c.wantBool)
		}
	}
}

// TestParseTelemetryValueNeverLosesData 认不出类型也**不能丢原文**。
//
// ⚠️ 丢原文 = 那个字段在我们眼里「不存在」⇒ 用户以为没数据。
func TestParseTelemetryValueNeverLosesData(t *testing.T) {
	for _, payload := range []string{
		`{"a":1}`,
		`[1,2,3]`,
		`"WindowStateOpened"`,
		`"not a number"`,
		`"NaN"`,
	} {
		got := parseTelemetryValue([]byte(payload))
		if got.Text == "" {
			t.Errorf("载荷 %q 的原文被丢了", payload)
		}
	}
}

// --------------------------------------------------------------------------
// ③ 时间诚实性：retained 重放
// --------------------------------------------------------------------------

// TestTelemetryFieldFreshness 「收到时刻」≠「采集时刻」。
//
// ⚠️ 这是本轮最危险的一处：
//
//	MQTT 的 retained 消息**不带采集时间**（载荷就是裸值），
//	broker 会在订阅者上线时**重发** retained。
//	⇒ API 一重启，9 小时前的旧值会显示成「刚刚」。
//
//	判据（纯函数，必须可测）：
//	- 车在线 ⇒ received_at 可信
//	- 车离线 + received_at 晚于断线时刻 ⇒ 这是重放 ⇒ 时刻 = 断线时刻，且 stale
//	- 车离线 + received_at 早于断线时刻 ⇒ 断线前收到的 ⇒ received_at 可信
//	- 从来没有连接状态 ⇒ 不知道 ⇒ stale
func TestTelemetryFieldFreshness(t *testing.T) {
	base := time.Date(2026, 10, 29, 0, 0, 0, 0, time.UTC)
	t0 := base.Add(-2 * time.Hour) // 断线前收到
	t1 := base.Add(-1 * time.Hour) // 车断线的时刻
	t2 := base.Add(30 * time.Minute)
	t3 := base.Add(10 * time.Minute) // 车再连上之后收到的

	cases := []struct {
		name           string
		receivedAt     time.Time
		lastConnected  *time.Time
		lastDisconnect *time.Time
		wantAt         time.Time
		wantStale      bool
	}{
		{
			name: "车在线：收到的就是最新的", receivedAt: t2,
			lastConnected: &t2, lastDisconnect: &t1,
			wantAt: t2, wantStale: false,
		},
		{
			name: "车在线且从未断过", receivedAt: t2,
			lastConnected: &t2, lastDisconnect: nil,
			wantAt: t2, wantStale: false,
		},
		{
			// ⚠️ 最关键的一条：API 重启后 broker 重发 retained。
			name:       "车离线 + 断线后重放的 retained ⇒ 时刻是断线那一刻",
			receivedAt: t3, lastConnected: &t0, lastDisconnect: &t1,
			wantAt: t1, wantStale: true,
		},
		{
			name:       "车离线 + 断线前收到的 ⇒ received_at 就是采集时刻",
			receivedAt: t0, lastConnected: &t0, lastDisconnect: &t1,
			wantAt: t0, wantStale: false,
		},
		{
			name:       "从来没有连接状态 ⇒ 不知道 ⇒ 保守标 stale",
			receivedAt: t2, lastConnected: nil, lastDisconnect: nil,
			wantAt: t2, wantStale: true,
		},
	}
	for _, c := range cases {
		at, stale := telemetryFieldFreshness(c.receivedAt, c.lastConnected, c.lastDisconnect)
		if at == nil {
			t.Errorf("%s：at = nil，期望 %v", c.name, c.wantAt)
			continue
		}
		if !at.Equal(c.wantAt) {
			t.Errorf("%s：at = %v，期望 %v", c.name, at, c.wantAt)
		}
		if stale != c.wantStale {
			t.Errorf("%s：stale = %v，期望 %v", c.name, stale, c.wantStale)
		}
	}
}

// TestTelemetryFieldFreshnessNeverClaimsFreshWhenOffline 车离线时，
// **绝不能**把「收到时刻」当成采集时刻。
//
// ⚠️ 这条单独立出来，因为它对应的是最要命的场景：
// 车在家放了 9 小时，App 上写「刚刚已上锁」⇒ 用户以为车现在锁着。
func TestTelemetryFieldFreshnessNeverClaimsFreshWhenOffline(t *testing.T) {
	disconnected := time.Date(2026, 10, 29, 0, 32, 40, 0, time.UTC)
	// API 重启的时刻：远晚于断线时刻。
	restarted := time.Date(2026, 10, 29, 1, 39, 11, 0, time.UTC)
	connected := disconnected.Add(-12 * time.Minute)

	at, stale := telemetryFieldFreshness(restarted, &connected, &disconnected)
	if at == nil {
		t.Fatal("at = nil")
	}
	if !at.Equal(disconnected) {
		t.Errorf("at = %v，期望断线时刻 %v", at, disconnected)
	}
	if !stale {
		t.Error("stale = false —— 车离线时把旧值说成新鲜的，是假信息")
	}
}

// --------------------------------------------------------------------------
// ④ 非有限数不能进库
// --------------------------------------------------------------------------

// TestParseFloatStrictRejectsNonFinite Inf / NaN 不能进 value_num。
//
// ⚠️ Go 的 `strconv.ParseFloat` **接受** "Inf" / "NaN" / "+Inf"，
// 而 Postgres 的 DOUBLE PRECISION 也会照收 ⇒ 库里就多了一条 'Infinity'。
// 读到它的那个字段会渲染成 "Inf" 或直接崩在格式化上。
func TestParseFloatStrictRejectsNonFinite(t *testing.T) {
	for _, s := range []string{"Inf", "+Inf", "-Inf", "Infinity", "NaN", "nan", "1_000", "abc", ""} {
		if n, err := parseFloatStrict(s); err == nil {
			t.Errorf("parseFloatStrict(%q) 竟然通过了（得到 %v）—— 非有限数会进库", s, n)
		}
	}
}

// TestParseFloatStrictAcceptsRealNumbers 正常数字必须能过。
func TestParseFloatStrictAcceptsRealNumbers(t *testing.T) {
	cases := map[string]float64{
		"0":       0,
		"12.3":    12.3,
		"-12.5":   -12.5,
		"1e5":     100000,
		"-3.2e-2": -0.032,
		".5":      0.5,
	}
	for s, want := range cases {
		got, err := parseFloatStrict(s)
		if err != nil {
			t.Errorf("parseFloatStrict(%q) 报错：%v", s, err)
			continue
		}
		if math.Abs(got-want) > 1e-9 {
			t.Errorf("parseFloatStrict(%q) = %v，期望 %v", s, got, want)
		}
	}
}

// --------------------------------------------------------------------------
// 小工具
// --------------------------------------------------------------------------

func f64(v float64) *float64 { return &v }

func bTrue() *bool {
	b := true
	return &b
}

func bFalse() *bool {
	b := false
	return &b
}
