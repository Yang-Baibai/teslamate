package main

import (
	"strings"
	"testing"
	"time"
)

// --------------------------------------------------------------------------
// v0.88.0：座舱状态（空调 / 座椅加热 / 方向盘加热）
// --------------------------------------------------------------------------
//
// ⚠️ 这组测试守的是**反幻觉**的三条线：
//
//	① **「没读到」≠「关」**（ADR #25）——
//	   车没推这个字段、或值认不出来，都必须当「不知道」，
//	   **不能**报成「关着」（那是假安全）。
//	② **官方没定义的档位不给中文名** ——
//	   方向盘加热的档位范围官方（中英两站 + proto + vehicle-command）
//	   全部没写 ⇒ 只能报原始整数，**不能**编「关 / 低档 / 中档 / 高档」。
//	③ **固件门槛** ——
//	   官方 proto 注释写明「fields 260-269 … firmware version 2026.32」，
//	   而本车实测固件是 2026.26.200 ⇒ 编号 ≥260 的字段本车**收不到**。
// --------------------------------------------------------------------------

// cabinIdx 造一个遥测字段索引（走真的 parseTelemetryValue）。
func cabinIdx(at time.Time, pairs ...string) map[string]telemetryFieldView {
	idx := map[string]telemetryFieldView{}
	for i := 0; i+1 < len(pairs); i += 2 {
		idx[pairs[i]] = tv(pairs[i], pairs[i+1], at)
	}
	return idx
}

// --------------------------------------------------------------------------
// ① 座椅加热
// --------------------------------------------------------------------------

func TestCabinSeatHeaterLevels(t *testing.T) {
	at := time.Now()
	cases := []struct {
		raw  string
		want string
	}{
		{"0", "关"},
		{"1", "低档"},
		{"2", "中档"},
		{"3", "高档"},
		{`"2"`, "中档"},
	}
	for _, c := range cases {
		st := cabinStateFromTelemetry(cabinIdx(at, "SeatHeaterLeft", c.raw))
		if len(st.SeatHeater) != 1 {
			t.Fatalf("档位 %s：期望 1 个座位，实际 %d 个", c.raw, len(st.SeatHeater))
		}
		got := st.SeatHeater[0]
		if got.Text != c.want {
			t.Errorf("主驾档位 %s：期望 %q，实际 %q", c.raw, c.want, got.Text)
		}
		if got.Seat != "主驾" {
			t.Errorf("座位名期望「主驾」，实际 %q", got.Seat)
		}
		if got.Level == nil {
			t.Errorf("档位 %s：Level 不该是 nil", c.raw)
		}
	}
}

func TestCabinSeatHeaterOutOfRangeIsUnknown(t *testing.T) {
	at := time.Now()
	// ⚠️ 官方只定义 0..3 ⇒ 5 **不能**套档位名，也**不能**当成「关」。
	st := cabinStateFromTelemetry(cabinIdx(at, "SeatHeaterLeft", "5"))
	if len(st.SeatHeater) != 1 {
		t.Fatalf("越界值仍要列出来（它是个读数），实际 %d 个", len(st.SeatHeater))
	}
	if got := st.SeatHeater[0].Text; got != "未知" {
		t.Errorf("越界档位期望「未知」，实际 %q", got)
	}
}

// --------------------------------------------------------------------------
// ② 「没读到」≠「关」（ADR #25）
// --------------------------------------------------------------------------

func TestCabinClimatePowerThreeState(t *testing.T) {
	at := time.Now()
	cases := []struct {
		raw      string
		wantOn   *bool // nil = 期望「不知道」
		wantText string
	}{
		{"HvacPowerStateOff", boolp(false), "关闭"},
		{"HvacPowerStateOn", boolp(true), "开启"},
		{"HvacPowerStatePrecondition", boolp(true), "预热中"},
		{"HvacPowerStateOverheatProtect", boolp(true), "防过热保护"},
		// ⚠️ 车自己说「不知道」⇒ 我们也不能替它说「关」。
		{"HvacPowerStateUnknown", nil, "状态未知"},
		{`"1"`, boolp(false), "关闭"},
		{"1", boolp(false), "关闭"},
	}
	for _, c := range cases {
		st := cabinStateFromTelemetry(cabinIdx(at, "HvacPower", c.raw))
		if st.ClimateText != c.wantText {
			t.Errorf("HvacPower=%s：中文期望 %q，实际 %q", c.raw, c.wantText, st.ClimateText)
		}
		switch {
		case c.wantOn == nil:
			if st.ClimateOn != nil {
				t.Errorf("HvacPower=%s：期望「不知道」（nil），实际 %v", c.raw, *st.ClimateOn)
			}
		default:
			if st.ClimateOn == nil {
				t.Errorf("HvacPower=%s：期望 %v，实际是 nil（没读到）", c.raw, *c.wantOn)
			} else if *st.ClimateOn != *c.wantOn {
				t.Errorf("HvacPower=%s：期望 %v，实际 %v", c.raw, *c.wantOn, *st.ClimateOn)
			}
		}
	}
}

func TestCabinMissingFieldsAreUnknownNotOff(t *testing.T) {
	at := time.Now()
	// 一个字段都没有 ⇒ 全部「不知道」。
	st := cabinStateFromTelemetry(cabinIdx(at))
	if st.ClimateOn != nil {
		t.Errorf("没有任何字段时 ClimateOn 应为 nil，实际 %v", *st.ClimateOn)
	}
	if st.ClimateText != "" {
		t.Errorf("没有任何字段时 ClimateText 应为空，实际 %q", st.ClimateText)
	}
	if len(st.SeatHeater) != 0 {
		t.Errorf("没有任何字段时座椅加热应为空列表，实际 %d 个", len(st.SeatHeater))
	}
	if st.WheelHeater != nil {
		t.Errorf("没有任何字段时方向盘加热应为 nil")
	}
	if st.FanSpeed != nil || st.DriverTempC != nil || st.PassengerTempC != nil {
		t.Errorf("没有任何字段时风速/温度都该是 nil")
	}
	if st.ACEnabled != nil || st.WheelHeaterAuto != nil {
		t.Errorf("没有任何字段时布尔都该是 nil")
	}
}

func TestCabinUnrecognizedValueIsUnknown(t *testing.T) {
	at := time.Now()
	// 认不出来的枚举名 ⇒ 当「没读到」。
	st := cabinStateFromTelemetry(cabinIdx(at, "HvacPower", "HvacPowerStateWeird"))
	if st.ClimateOn != nil || st.ClimateText != "" {
		t.Errorf("认不出来的枚举应摊「没读到」，实际 on=%v text=%q", st.ClimateOn, st.ClimateText)
	}
	// 认不出来的数字 ⇒ 同样。
	st = cabinStateFromTelemetry(cabinIdx(at, "SeatHeaterLeft", "abc"))
	if len(st.SeatHeater) != 0 {
		t.Errorf("认不出来的档位不应列出来，实际 %d 个", len(st.SeatHeater))
	}
}

// --------------------------------------------------------------------------
// ③ 方向盘加热：官方没给档位范围 ⇒ 不编中文档位名
// --------------------------------------------------------------------------

func TestCabinSteeringWheelHeatHasNoInventedScale(t *testing.T) {
	at := time.Now()
	st := cabinStateFromTelemetry(cabinIdx(at, "HvacSteeringWheelHeatLevel", "2"))
	if st.WheelHeater == nil {
		t.Fatalf("方向盘加热档位没解析出来")
	}
	if st.WheelHeater.Level == nil || *st.WheelHeater.Level != 2 {
		t.Errorf("期望原始档位 2，实际 %v", st.WheelHeater.Level)
	}
	// ⚠️ 官方（中英两站 + proto + vehicle-command）**都没给**这个字段的范围 ⇒
	//    这里只能报「档位 N」，**不许**出现「关 / 低档 / 中档 / 高档」。
	for _, forbidden := range []string{"关", "低档", "中档", "高档"} {
		if strings.Contains(st.WheelHeater.Text, forbidden) {
			t.Errorf("方向盘加热文案 %q 含 %q —— 官方没定义这个范围，"+
				"编档位名就是造事实", st.WheelHeater.Text, forbidden)
		}
	}
	if !strings.Contains(st.WheelHeater.Text, "2") {
		t.Errorf("方向盘加热文案 %q 应含原始数字 2", st.WheelHeater.Text)
	}
}

// --------------------------------------------------------------------------
// ④ 其余字段
// --------------------------------------------------------------------------

func TestCabinBooleansNumbersAndTemperatures(t *testing.T) {
	at := time.Now()
	st := cabinStateFromTelemetry(cabinIdx(at,
		"HvacACEnabled", "true",
		"HvacAutoMode", "HvacAutoModeStateOverride",
		"HvacFanSpeed", "3",
		"HvacLeftTemperatureRequest", "22.5",
		"HvacRightTemperatureRequest", `"21"`,
		"HvacSteeringWheelHeatAuto", "false",
	))
	if st.ACEnabled == nil || !*st.ACEnabled {
		t.Errorf("HvacACEnabled 应解析为 true，实际 %v", st.ACEnabled)
	}
	if st.AutoMode != "手动接管" {
		t.Errorf("HvacAutoMode 期望「手动接管」，实际 %q", st.AutoMode)
	}
	if st.FanSpeed == nil || *st.FanSpeed != 3 {
		t.Errorf("HvacFanSpeed 期望 3，实际 %v", st.FanSpeed)
	}
	if st.DriverTempC == nil || *st.DriverTempC != 22.5 {
		t.Errorf("主驾设定温度期望 22.5，实际 %v", st.DriverTempC)
	}
	if st.PassengerTempC == nil || *st.PassengerTempC != 21 {
		t.Errorf("副驾设定温度期望 21，实际 %v", st.PassengerTempC)
	}
	if st.WheelHeaterAuto == nil || *st.WheelHeaterAuto {
		t.Errorf("方向盘加热自动期望 false，实际 %v", st.WheelHeaterAuto)
	}
}

func TestCabinSeatHeaterOnlyListsReadSeats(t *testing.T) {
	at := time.Now()
	// 只读到副驾 ⇒ 主驾**不列**（ADR #25：无来源的项整项不显示）。
	st := cabinStateFromTelemetry(cabinIdx(at, "SeatHeaterRight", "1"))
	if len(st.SeatHeater) != 1 {
		t.Fatalf("期望只有副驾 1 个，实际 %d 个", len(st.SeatHeater))
	}
	if st.SeatHeater[0].Seat != "副驾" {
		t.Errorf("期望「副驾」，实际 %q", st.SeatHeater[0].Seat)
	}
}

// --------------------------------------------------------------------------
// ⑤ 时间诚实：取最旧的采集时刻
// --------------------------------------------------------------------------

func TestCabinUsesOldestAtAndPropagatesStale(t *testing.T) {
	base := time.Now().Truncate(time.Second)
	older := base.Add(-2 * time.Hour)

	idx := map[string]telemetryFieldView{}
	f1 := tv("HvacPower", "HvacPowerStateOn", base)
	f1.Stale = false
	f2 := tv("SeatHeaterLeft", "2", older)
	f2.Stale = true
	idx["HvacPower"] = f1
	idx["SeatHeaterLeft"] = f2

	st := cabinStateFromTelemetry(idx)
	if st.At == nil {
		t.Fatalf("采集时刻不该是 nil")
	}
	if !st.At.Equal(older) {
		t.Errorf("采集时刻应取**最旧**的那个（%s），实际 %s", older, st.At)
	}
	if !st.Stale {
		t.Errorf("只要有一项是旧的，整组就应标 stale")
	}
}

// --------------------------------------------------------------------------
// ⑥ 官方枚举 / 固件门槛
// --------------------------------------------------------------------------

func TestCabinOfficialEnumNumbers(t *testing.T) {
	// 官方 protos/vehicle_data.proto → enum HvacPowerState
	want := map[string]string{
		"HvacPowerStateUnknown":         "0",
		"HvacPowerStateOff":             "1",
		"HvacPowerStateOn":              "2",
		"HvacPowerStatePrecondition":    "3",
		"HvacPowerStateOverheatProtect": "4",
	}
	for name, code := range want {
		if got, ok := telemetryHvacPowerStateNames[name]; !ok || got != code {
			t.Errorf("HvacPowerState 官方编号不对：%s 应为 %s，实际 %q（存在=%v）",
				name, code, got, ok)
		}
	}
	// 官方 enum HvacAutoModeState
	autoWant := map[string]string{
		"HvacAutoModeStateUnknown":  "0",
		"HvacAutoModeStateOn":       "1",
		"HvacAutoModeStateOverride": "2",
	}
	for name, code := range autoWant {
		if got, ok := telemetryHvacAutoModeStateNames[name]; !ok || got != code {
			t.Errorf("HvacAutoModeState 官方编号不对：%s 应为 %s，实际 %q（存在=%v）",
				name, code, got, ok)
		}
	}
}

// TestCabinFieldsWithinFirmwareReach 确认我们下发的字段**本车收得到**。
//
// ⚠️ 官方 proto 注释原文：
//
//	"fields 260-269 are first available in firmware version 2026.32"
//
//	本车实测固件（遥测 `Version` 字段）是 **2026.26.200** ⇒
//	编号 ≥260 的字段**本车永远不会来**。
//
// ⇒ 这条断言把「官方有 ≠ 本车有」变成会红的断言。
func TestCabinFieldsWithinFirmwareReach(t *testing.T) {
	for name := range telemetryFields {
		num, ok := telemetryOfficialFieldNumbers[name]
		if !ok {
			continue // 名单外的由 TestTelemetryConfiguredFieldsAreOfficial 负责
		}
		if num >= 260 {
			t.Errorf("字段 %q（官方编号 %d）要固件 2026.32+，"+
				"而本车是 2026.26.200 ⇒ 它永远不会来", name, num)
		}
	}
}

// TestCabinConfiguredFieldsAreRequested 确认座舱这批**真的**配进了下发配置。
//
// ⚠️ 防「文档写了但没配」—— 没配 = 车不会推 = 永远读不到。
func TestCabinConfiguredFieldsAreRequested(t *testing.T) {
	want := []string{
		"SeatHeaterLeft", "SeatHeaterRight",
		"HvacACEnabled", "HvacAutoMode", "HvacFanSpeed", "HvacFanStatus",
		"HvacLeftTemperatureRequest", "HvacPower", "HvacRightTemperatureRequest",
		"HvacSteeringWheelHeatAuto", "HvacSteeringWheelHeatLevel",
	}
	for _, name := range want {
		if _, ok := telemetryFields[name]; !ok {
			t.Errorf("座舱字段 %q 声称已走遥测，但下发配置里没有它", name)
		}
	}
}

// boolp 造一个 *bool。
func boolp(b bool) *bool { return &b }
