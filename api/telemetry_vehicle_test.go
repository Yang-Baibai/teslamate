package main

// --------------------------------------------------------------------------
// v0.81.0：遥测「车辆实况」的单测
//
// ⚠️ 这里最要紧的不是「解析对不对」，而是
//	**「我们下发给车端的字段名对不对」** ——
//	字段名写错**不会报错**，只是那个字段永远不来（静默失效，最难查的一种）。
//	⇒ TestTelemetryConfiguredFieldsAreOfficial 把它变成一条会红的断言。
// --------------------------------------------------------------------------

import (
	"testing"
	"time"
)

// --------------------------------------------------------------------------
// ① 下发给车端的字段名必须在官方名单里
// --------------------------------------------------------------------------

// TestTelemetryConfiguredFieldsAreOfficial 我们**下发给车端**的每个字段名
// 都必须在官方 `vehicle_data.proto` 的 Field 枚举里。
//
// ⚠️ 为什么 `telemetryFieldNames`（我们**消费**的字段）那条断言不够：
//
//	那一条只管「我们看得懂的字段」。
//	而 `telemetryFields` 是**发给车**的 ——
//	这里写错，车端不会报错、订阅不会失败，
//	只是那个字段**永远不会来** ⇒ 我们会一直等一个不存在的字段。
func TestTelemetryConfiguredFieldsAreOfficial(t *testing.T) {
	for name := range telemetryFields {
		if _, ok := telemetryOfficialFieldNumbers[name]; !ok {
			t.Errorf("下发给车端的字段 %q 不在官方 vehicle_data.proto 的 Field 枚举里 —— "+
				"写错不报错，只会让那个字段永远不来", name)
		}
	}
}

// TestTelemetryConfiguredFieldsCoverWhatWeClaim 确认这批新字段**真的**配了。
//
// ⚠️ 这条看着像「测配置」，实际是防「文档写了但没配」：
//
//	我们说「胎压/车窗/门/哨兵/温度/充电上限已经走遥测」，
//	那就必须**真的**在配置里 —— 否则文档在骗人。
func TestTelemetryConfiguredFieldsCoverWhatWeClaim(t *testing.T) {
	want := []string{
		"TpmsPressureFl", "TpmsPressureFr", "TpmsPressureRl", "TpmsPressureRr",
		"TpmsHardWarnings", "TpmsSoftWarnings",
		"FdWindow", "FpWindow", "RdWindow", "RpWindow",
		"DoorState", "SentryMode",
		"InsideTemp", "OutsideTemp",
		"ChargeLimitSoc",
	}
	for _, name := range want {
		if _, ok := telemetryFields[name]; !ok {
			t.Errorf("字段 %q 声称已走遥测，但下发配置里没有它", name)
		}
	}
}

// --------------------------------------------------------------------------
// ② 枚举解析：认官方名，也认数字；认不出就摊原文
// --------------------------------------------------------------------------

func TestTelemetryParseStateWindow(t *testing.T) {
	cases := []struct {
		in   string
		code string
		text string
		ok   bool
	}{
		{"WindowStateClosed", "1", "已关闭", true},
		{"WindowStateOpened", "3", "已打开", true},
		{"WindowStatePartiallyOpen", "2", "半开", true},
		{"WindowStateUnknown", "0", "状态未知", true},
		{"1", "1", "已关闭", true},
		{"3", "3", "已打开", true},
		{`"2"`, "2", "半开", true},
		// ⚠️ 认不出来 ⇒ 摊原文（ok=false），**不猜**。
		{"WindowStateWeird", "", "", false},
		{"9", "", "", false},
		{"1.5", "", "", false},
		{"", "", "", false},
	}
	for _, c := range cases {
		code, text, ok := telemetryParseState(c.in, telemetryWindowStateNames, telemetryWindowStates)
		if ok != c.ok || code != c.code || text != c.text {
			t.Errorf("telemetryParseState(%q) = (%q,%q,%v)，期望 (%q,%q,%v)",
				c.in, code, text, ok, c.code, c.text, c.ok)
		}
	}
}

func TestTelemetryParseStateSentry(t *testing.T) {
	cases := []struct {
		in   string
		code string
		text string
	}{
		{"SentryModeStateArmed", "3", "已布防"},
		{"SentryModeStateOff", "1", "未开启"},
		{"SentryModeStatePanic", "5", "警报触发"},
		{"3", "3", "已布防"},
	}
	for _, c := range cases {
		code, text, ok := telemetryParseState(c.in, telemetrySentryStateNames, telemetrySentryStates)
		if !ok || code != c.code || text != c.text {
			t.Errorf("telemetryParseState(%q) = (%q,%q,%v)，期望 (%q,%q,true)",
				c.in, code, text, ok, c.code, c.text)
		}
	}
}

// --------------------------------------------------------------------------
// ③ 门 / 胎压告警位：官方消息 → 中文
// --------------------------------------------------------------------------

func TestTelemetryParseDoors(t *testing.T) {
	raw := `{"DriverFront":true,"DriverRear":false,"PassengerFront":false,` +
		`"PassengerRear":false,"TrunkFront":false,"TrunkRear":true}`
	open, known, ok := telemetryParseDoors(raw)
	if !ok {
		t.Fatalf("官方 Doors 对象应能解析")
	}
	if known != 6 {
		t.Errorf("认识的字段数 = %d，期望 6", known)
	}
	if len(open) != 2 || open[0] != "主驾前门" || open[1] != "后备箱" {
		t.Errorf("开着的位置 = %v，期望 [主驾前门 后备箱]", open)
	}

	// ⚠️ 不是对象 ⇒ 认不出来（摊原文），不能当成「全关」。
	if _, _, ok := telemetryParseDoors("WindowStateClosed"); ok {
		t.Errorf("非对象载荷不该被当成 Doors")
	}
	if _, _, ok := telemetryParseDoors(""); ok {
		t.Errorf("空载荷不该被当成 Doors")
	}
}

func TestTelemetryParseTireFlags(t *testing.T) {
	flags := telemetryParseTireFlags(`{"front_left":true,"front_right":false,` +
		`"rear_left":false,"rear_right":true}`)
	if len(flags) != 2 || !flags["左前"] || !flags["右后"] {
		t.Errorf("胎压告警位解析 = %v，期望 左前+右后", flags)
	}
	if flags["右前"] || flags["左后"] {
		t.Errorf("不该有的轮次被判成有告警：%v", flags)
	}

	// ⚠️ **实测车端给的就是字面量 `null`**（2026-10-29 真车）——
	//    含义是「这个字段没有告警位」，**不是**「解析失败」。
	//    两种情况的效果一样（都不额外报警），但不能因此报「有告警」。
	if f := telemetryParseTireFlags("null"); len(f) != 0 {
		t.Errorf("字面量 null 应解析成「没有告警位」，实际 %v", f)
	}
	if f := telemetryParseTireFlags(""); len(f) != 0 {
		t.Errorf("空载荷应解析成「没有告警位」，实际 %v", f)
	}
}

// --------------------------------------------------------------------------
// ④ 车窗三态：不知道 ≠ 全关
// --------------------------------------------------------------------------

// tv 造一个「已解析的字段」。
//
// ⚠️ 走**真的** parseTelemetryValue ——
//
//	因为线上就是这样把 MQTT 载荷变成 Text/Num/Bool 的，
//	测试里手填 Num 就等于「假设解析器是对的」，那这条断言就白写了。
func tv(field, text string, at time.Time) telemetryFieldView {
	t := at
	v := parseTelemetryValue([]byte(text))
	return telemetryFieldView{Field: field, Text: v.Text, Num: v.Num, Bool: v.Bool, At: &t}
}

func TestTelemetryOpenWindowsThreeState(t *testing.T) {
	at := time.Now()

	// ① 四扇都读到、都是关着 ⇒ **确定全关**（空数组，非 nil）。
	idx := telemetryIdx([]telemetryFieldView{
		tv("FdWindow", "WindowStateClosed", at),
		tv("FpWindow", "WindowStateClosed", at),
		tv("RdWindow", "WindowStateClosed", at),
		tv("RpWindow", "WindowStateClosed", at),
	})
	got := telemetryOpenWindows(idx)
	if got == nil {
		t.Errorf("四扇窗都读到且全关 ⇒ 应返回空数组（确定全关），不该是 nil（不知道）")
	}
	if len(got) != 0 {
		t.Errorf("全关时不该有开着的位置：%v", got)
	}

	// ② 有一扇开着 ⇒ 列出它（半开也算开）。
	idx = telemetryIdx([]telemetryFieldView{
		tv("FdWindow", "WindowStateClosed", at),
		tv("FpWindow", "WindowStatePartiallyOpen", at),
		tv("RdWindow", "WindowStateClosed", at),
		tv("RpWindow", "WindowStateClosed", at),
	})
	got = telemetryOpenWindows(idx)
	if len(got) != 1 || got[0] != "右前窗" {
		t.Errorf("开着的位置 = %v，期望 [右前窗]", got)
	}

	// ③ 只读到一部分 ⇒ **不知道**（nil），不能声称「全关」。
	idx = telemetryIdx([]telemetryFieldView{
		tv("FdWindow", "WindowStateClosed", at),
		tv("FpWindow", "WindowStateClosed", at),
	})
	if got := telemetryOpenWindows(idx); got != nil {
		t.Errorf("只读到两扇窗时应为 nil（不知道），实际 %v", got)
	}

	// ④ 一个都没读到 ⇒ nil。
	if got := telemetryOpenWindows(telemetryIdx(nil)); got != nil {
		t.Errorf("没有任何车窗字段时应为 nil，实际 %v", got)
	}
}

// --------------------------------------------------------------------------
// ⑤ 胎压组装 + 告警位
// --------------------------------------------------------------------------

func TestTelemetryTPMSFromFields(t *testing.T) {
	at := time.Now()
	idx := telemetryIdx([]telemetryFieldView{
		tv("TpmsPressureFl", "2.90", at),
		tv("TpmsPressureFr", "2.91", at),
		tv("TpmsPressureRl", "2.92", at),
		tv("TpmsPressureRr", "2.05", at),
		tv("TpmsHardWarnings", `{"rear_right":true}`, at),
	})
	tp, ok := telemetryTPMSFromFields(idx)
	if !ok {
		t.Fatalf("读到 4 个胎压值，应当 ok=true")
	}
	if tp.FL.Pressure == nil || *tp.FL.Pressure != 2.90 {
		t.Errorf("左前胎压 = %v，期望 2.90", tp.FL.Pressure)
	}
	if !tp.RR.Hard {
		t.Errorf("右后轮应带硬告警（官方 TireLocation 说它带了）")
	}
	if tp.FL.Hard {
		t.Errorf("左前轮不该带硬告警")
	}
	if tp.At == nil {
		t.Errorf("采集时刻不该为空")
	}

	// ⚠️ 一个胎压都没读到 ⇒ ok=false（不能说「胎压正常」）。
	if _, ok := telemetryTPMSFromFields(telemetryIdx(nil)); ok {
		t.Errorf("没有任何胎压字段时不应 ok=true")
	}
}

// --------------------------------------------------------------------------
// ⑦ 真车实测载荷的「金标准」回归
// --------------------------------------------------------------------------

// TestTelemetryRealPayloadsGolden 用**真车真实推送过的载荷**跑一遍解析。
//
// ⚠️ 为什么要有这条：
//
//	解析器是照着官方 proto 写的，但**车端实际给什么形态**只有实测才知道 ——
//	本轮就实测到两个「proto 里看不出来」的形态：
//	- 车窗给的是枚举**名**："WindowStateClosed"（不是数字）
//	- 胎压告警位给的是字面量 **"null"**（不是对象）
//
//	⇒ 把真机载荷钉成回归用例，以后改解析器时不会悄悄改坏真实数据。
//
// 出处：2026-10-29 真车（<VIN>）唤醒后 20 秒内推来的原始载荷。
func TestTelemetryRealPayloadsGolden(t *testing.T) {
	at := time.Now()
	idx := telemetryIdx([]telemetryFieldView{
		tv("TpmsPressureFl", "3.0000000447034836", at),
		tv("TpmsPressureFr", "2.9250000435858965", at),
		tv("TpmsPressureRl", "2.8750000428408384", at),
		tv("TpmsPressureRr", "2.8750000428408384", at),
		tv("TpmsHardWarnings", "null", at),
		tv("TpmsSoftWarnings", "null", at),
		tv("FdWindow", "WindowStateClosed", at),
		tv("FpWindow", "WindowStateClosed", at),
		tv("RdWindow", "WindowStateClosed", at),
		tv("RpWindow", "WindowStateClosed", at),
		tv("DoorState", `{"DriverFront":false,"DriverRear":false,"PassengerFront":false,`+
			`"PassengerRear":false,"TrunkFront":false,"TrunkRear":false}`, at),
		tv("SentryMode", "SentryModeStateOff", at),
		tv("InsideTemp", "29.30000103265047", at),
		tv("OutsideTemp", "28.5", at),
		tv("ChargeLimitSoc", "80", at),
		tv("IsolationResistance", "9830", at),
	})

	// ── 胎压 ──
	tp, ok := telemetryTPMSFromFields(idx)
	if !ok {
		t.Fatalf("真车载荷应能解析出胎压")
	}
	want := []struct {
		name string
		got  *float64
		exp  float64
	}{
		{"左前", tp.FL.Pressure, 3.0},
		{"右前", tp.FR.Pressure, 2.925},
		{"左后", tp.RL.Pressure, 2.875},
		{"右后", tp.RR.Pressure, 2.875},
	}
	for _, w := range want {
		if w.got == nil {
			t.Errorf("%s 胎压没解析出来", w.name)
			continue
		}
		if diff := *w.got - w.exp; diff > 1e-6 || diff < -1e-6 {
			t.Errorf("%s 胎压 = %v，期望 %v", w.name, *w.got, w.exp)
		}
	}
	// 真车当时四轮都正常 ⇒ 不该报异常（阈值 2.5–3.3 bar）。
	eval := EvaluateLiveTpms(tp)
	if eval.Alerting {
		t.Errorf("真车载荷不该判成胎压异常：%v", eval.Wheels)
	}
	if len(eval.WarnWheels) != 0 {
		t.Errorf("真车载荷不该有接近阈值的轮次：%v", eval.WarnWheels)
	}

	// ── 车窗（四扇都是 WindowStateClosed） ──
	win := telemetryOpenWindows(idx)
	if win == nil || len(win) != 0 {
		t.Errorf("真车四扇窗都关 ⇒ 应为「确定全关」（空数组），实际 %v", win)
	}

	// ── 门 ──
	open, known, ok := telemetryParseDoors(idx["DoorState"].Text)
	if !ok || known != 6 || len(open) != 0 {
		t.Errorf("真车门状态解析 = (%v,%d,%v)，期望 ([] 全部关闭,6,true)", open, known, ok)
	}

	// ── 哨兵 ──
	code, text, ok := telemetryParseState(idx["SentryMode"].Text,
		telemetrySentryStateNames, telemetrySentryStates)
	if !ok || code != "1" || text != "未开启" {
		t.Errorf("真车哨兵解析 = (%q,%q,%v)，期望 (\"1\",\"未开启\",true)", code, text, ok)
	}

	// ── 数值 ──
	if v := telemetryNum(idx, "ChargeLimitSoc"); v == nil || *v != 80 {
		t.Errorf("充电上限 = %v，期望 80", v)
	}
	if v := telemetryNum(idx, "InsideTemp"); v == nil || *v < 29.2 || *v > 29.4 {
		t.Errorf("车内温 = %v，期望 ≈29.3", v)
	}
	if v := telemetryNum(idx, "OutsideTemp"); v == nil || *v != 28.5 {
		t.Errorf("车外温 = %v，期望 28.5", v)
	}
	if v := telemetryNum(idx, "IsolationResistance"); v == nil || *v != 9830 {
		t.Errorf("绝缘电阻 = %v，期望 9830", v)
	}
}

// --------------------------------------------------------------------------
// ⑥ 两个来源怎么合：按采集时刻取更新的那个
// --------------------------------------------------------------------------

func tvF64(v float64) *float64 { return &v }

func TestPickNewerTPMS(t *testing.T) {
	older := time.Now().Add(-time.Hour)
	newer := time.Now()

	poll := TeslaTPMS{FL: TeslaTPMSWheel{Pressure: tvF64(2.9)}, At: &older}
	tel := TeslaTPMS{FL: TeslaTPMSWheel{Pressure: tvF64(2.8)}, At: &newer}

	// 只有轮询有 ⇒ 用轮询。
	got, src := pickNewerTPMS(poll, TeslaTPMS{}, false)
	if src != "poll" || got.FL.Pressure == nil || *got.FL.Pressure != 2.9 {
		t.Errorf("只有轮询时应选轮询，实际 src=%s", src)
	}
	// 只有遥测有 ⇒ 用遥测（车睡着时也有答案）。
	got, src = pickNewerTPMS(TeslaTPMS{}, tel, true)
	if src != "telemetry" || got.FL.Pressure == nil || *got.FL.Pressure != 2.8 {
		t.Errorf("只有遥测时应选遥测，实际 src=%s", src)
	}
	// 两边都有 ⇒ 取更新的。
	got, src = pickNewerTPMS(poll, tel, true)
	if src != "telemetry" || *got.FL.Pressure != 2.8 {
		t.Errorf("遥测更新时应选遥测，实际 src=%s", src)
	}
	// 轮询更新 ⇒ 取轮询（**不是**无条件遥测优先）。
	got, src = pickNewerTPMS(TeslaTPMS{FL: TeslaTPMSWheel{Pressure: tvF64(2.7)}, At: &newer}, tel, true)
	if src != "poll" || *got.FL.Pressure != 2.7 {
		t.Errorf("轮询更新时应选轮询，实际 src=%s", src)
	}
	// 两边都没有 ⇒ 不知道。
	if _, src = pickNewerTPMS(TeslaTPMS{}, TeslaTPMS{}, false); src != "" {
		t.Errorf("两边都没有时来源应为空，实际 %q", src)
	}
}

func TestPickNewerWindows(t *testing.T) {
	older := time.Now().Add(-time.Hour)
	newer := time.Now()

	// 轮询不知道 + 遥测知道 ⇒ 用遥测。
	got, src := pickNewerWindows(nil, nil, []string{"左前窗"}, &newer)
	if src != "telemetry" || len(got) != 1 {
		t.Errorf("轮询未知时应取遥测，实际 src=%s got=%v", src, got)
	}
	// 轮询知道 + 遥测不知道 ⇒ 用轮询（**不能**当成「全关」）。
	got, src = pickNewerWindows([]string{}, &older, nil, nil)
	if src != "poll" || got == nil || len(got) != 0 {
		t.Errorf("遥测未知时应取轮询，实际 src=%s got=%v", src, got)
	}
	// 两边都知道 ⇒ 取更新的。
	got, src = pickNewerWindows([]string{}, &older, []string{"左前窗"}, &newer)
	if src != "telemetry" || len(got) != 1 {
		t.Errorf("遥测更新时应取遥测，实际 src=%s got=%v", src, got)
	}
	// 两边都不知道 ⇒ 不知道（nil），不是「全关」。
	got, src = pickNewerWindows(nil, nil, nil, nil)
	if src != "" || got != nil {
		t.Errorf("两边都不知道时应为 (nil, 空)，实际 (%v,%q)", got, src)
	}
}
