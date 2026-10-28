package main

import (
	"strings"
	"testing"
)

// ⚠️ **v0.78.0：充电结局判定**。
//
// 这组用例守的是通知通道里最要命的一类错：**假成功**。
//
// 旧版：充电结束一律说「✅ 充电完成」。
// 实测本机最近一次（2026-10-28，Tesla 超充）：**54% → 80%**，充电上限 **100%**。
// ⇒ 车没充满，旧版说「完成」。
//
// 判定必须**只用官方事实**，判不出来时**降级**而不是硬猜。

func intPtr(v int) *int         { return &v }
func fltPtr(v float64) *float64 { return &v }
func boolPtr(v bool) *bool      { return &v }

func TestChargeContextFromRaw(t *testing.T) {
	// 真机形状（中国区 vehicle_data 的 charge_state 段）
	raw := map[string]any{
		"charge_state": map[string]any{
			"charging_state":      "Disconnected",
			"charge_limit_soc":    float64(100),
			"battery_level":       float64(74),
			"charge_energy_added": float64(17.52),
		},
	}
	fc, ok := chargeContextFromRaw(raw)
	if !ok {
		t.Fatal("应当读得到 charge_state")
	}
	if fc.State != ChargeStateDisconnected {
		t.Errorf("state = %q, want %q", fc.State, ChargeStateDisconnected)
	}
	if fc.LimitSoc == nil || *fc.LimitSoc != 100 {
		t.Errorf("limit_soc = %v, want 100", fc.LimitSoc)
	}
	if fc.BatteryLevel == nil || *fc.BatteryLevel != 74 {
		t.Errorf("battery_level = %v, want 74", fc.BatteryLevel)
	}
	if fc.EnergyAdded == nil || *fc.EnergyAdded != 17.52 {
		t.Errorf("energy_added = %v, want 17.52", fc.EnergyAdded)
	}
}

func TestChargeContextFromRawUnknown(t *testing.T) {
	cases := []struct {
		name string
		raw  map[string]any
	}{
		{"没有原始数据", nil},
		{"没有 charge_state", map[string]any{"vehicle_state": map[string]any{}}},
		{"charge_state 不是对象", map[string]any{"charge_state": "x"}},
		{"charge_state 里一个字段都没有", map[string]any{"charge_state": map[string]any{}}},
	}
	for _, c := range cases {
		if _, ok := chargeContextFromRaw(c.raw); ok {
			t.Errorf("%s：应当判为「没读到」", c.name)
		}
	}
}

func TestChargeContextFromRawMissingFieldsAreNil(t *testing.T) {
	// ⚠️ 缺字段必须 nil，**不能压成 0** ——
	// 「上限 0%」和「没读到上限」是完全不同的两件事。
	fc, ok := chargeContextFromRaw(map[string]any{
		"charge_state": map[string]any{"charging_state": "Stopped"},
	})
	if !ok {
		t.Fatal("有 charging_state 就应当判为读到了")
	}
	if fc.LimitSoc != nil {
		t.Errorf("limit_soc 应当是 nil，实际 %v", *fc.LimitSoc)
	}
	if fc.BatteryLevel != nil {
		t.Errorf("battery_level 应当是 nil，实际 %v", *fc.BatteryLevel)
	}
}

// TestDetectTaper 收尾曲线判据 —— 用**真机 30 场会话**的数据。
//
// 物理事实：电池逼近充电上限时，BMS 把功率一路压低；
// 拔枪走人则是满功率一刀切。两者在曲线上分得很开。
//
// 数据来自本机 charging_processes + charges（2026-10-28 时刻的快照）。
func TestDetectTaper(t *testing.T) {
	cases := []struct {
		name string
		last *int // 末次非零功率 kW
		peak *int // 本场峰值功率 kW
		want *bool
	}{
		// —— 充到上限（BMS 降流收尾）：比值 0.031 ~ 0.104 ——
		{"会话78 充到100%", intPtr(4), intPtr(69), boolPtr(true)},
		{"会话77 充到100%", intPtr(5), intPtr(66), boolPtr(true)},
		{"会话76 充到100%", intPtr(3), intPtr(98), boolPtr(true)},
		{"会话65 充到100%", intPtr(3), intPtr(98), boolPtr(true)},
		{"会话62 充到100%", intPtr(10), intPtr(96), boolPtr(true)},
		// —— 拔枪结束（满功率一刀切）：比值 0.260 ~ 1.000 ——
		{"会话79 拔枪停在80%", intPtr(36), intPtr(87), boolPtr(false)},
		{"会话75 拔枪停在36%", intPtr(122), intPtr(166), boolPtr(false)},
		{"会话74 拔枪停在62%", intPtr(72), intPtr(173), boolPtr(false)},
		{"会话73 拔枪停在77%", intPtr(37), intPtr(116), boolPtr(false)},
		{"会话66 拔枪停在63%", intPtr(73), intPtr(123), boolPtr(false)},
		{"会话64 拔枪停在78%", intPtr(41), intPtr(82), boolPtr(false)},
		{"会话63 拔枪停在63%", intPtr(72), intPtr(98), boolPtr(false)},
		{"会话56 拔枪停在84%", intPtr(35), intPtr(59), boolPtr(false)},
		{"会话55 拔枪停在87%", intPtr(25), intPtr(94), boolPtr(false)},
		{"会话54 拔枪停在96%", intPtr(13), intPtr(50), boolPtr(false)},
		{"会话53 拔枪停在76%", intPtr(42), intPtr(71), boolPtr(false)},
		{"会话50 拔枪停在56%", intPtr(81), intPtr(97), boolPtr(false)},
		{"会话72 几乎没充上", intPtr(19), intPtr(19), boolPtr(false)},
		{"会话61 全程2kW", intPtr(2), intPtr(2), boolPtr(false)},
		{"会话52 恒定功率", intPtr(98), intPtr(98), boolPtr(false)},
		// —— 判不出来 ⇒ nil（**不能**当成 false）——
		{"没有采样", nil, nil, nil},
		{"只有末次功率", intPtr(5), nil, nil},
		{"只有峰值功率", nil, intPtr(80), nil},
		{"全程0kW", nil, intPtr(0), nil},
		{"末次也是0", intPtr(0), intPtr(80), nil},
	}
	for _, c := range cases {
		got := DetectTaper(CurveFacts{LastNonZeroPower: c.last, PeakPower: c.peak})
		switch {
		case c.want == nil && got != nil:
			t.Errorf("%s：应当判不出来（nil），实际 %v", c.name, *got)
		case c.want != nil && got == nil:
			t.Errorf("%s：应当判出 %v，实际 nil（判不出来）", c.name, *c.want)
		case c.want != nil && got != nil && *got != *c.want:
			t.Errorf("%s：判成 %v，want %v", c.name, *got, *c.want)
		}
	}
}

func TestEvaluateChargeOutcome(t *testing.T) {
	base := ChargeOutcomeInput{
		Fleet: FleetCharge{
			State:        ChargeStateDisconnected,
			LimitSoc:     intPtr(100),
			BatteryLevel: intPtr(80),
		},
		HasFleet: true,
		// 会话 79 的真实收尾曲线：末次 36 kW / 峰值 87 kW ⇒ 满功率一刀切。
		Curve: CurveFacts{
			LastNonZeroPower: intPtr(36),
			PeakPower:        intPtr(87),
		},
		LastEndSoc:      intPtr(80),
		LastEnergy:      fltPtr(17.56),
		LastDurationMin: intPtr(19),
	}

	cases := []struct {
		name string
		mut  func(*ChargeOutcomeInput)
		want string
	}{
		{"正在充", func(in *ChargeOutcomeInput) { in.ActiveCharging = true }, OutcomeCharging},
		// ⚠️ 车端说「枪插着但停了」⇒ 只有车端能回答「枪还在不在」，
		//    所以它**优先于**曲线。
		{"车端说停了（枪还插着）", func(in *ChargeOutcomeInput) { in.Fleet.State = ChargeStateStopped }, OutcomeInterrupted},
		{"车端说桩没供电", func(in *ChargeOutcomeInput) { in.Fleet.State = ChargeStateNoPower }, OutcomeNoPower},
		{"拔枪了没充到上限", func(in *ChargeOutcomeInput) {}, OutcomeEndedEarly},
		// —— 曲线能判时，曲线说了算 ——
		{"曲线降流⇒充到上限", func(in *ChargeOutcomeInput) {
			in.Curve = CurveFacts{LastNonZeroPower: intPtr(4), PeakPower: intPtr(69)}
			in.LastEndSoc = intPtr(100)
		}, OutcomeComplete},
		{"曲线满功率⇒拔枪", func(in *ChargeOutcomeInput) {}, OutcomeEndedEarly},
		// —— 曲线缺失 ⇒ 退回「结束电量 vs 上限」——
		{"没曲线但电量到上限", func(in *ChargeOutcomeInput) {
			in.Curve = CurveFacts{}
			in.LastEndSoc = intPtr(100)
		}, OutcomeComplete},
		{"没曲线且电量没到上限", func(in *ChargeOutcomeInput) {
			in.Curve = CurveFacts{}
		}, OutcomeEndedEarly},
		// —— 车端状态滞后 / 读不到 ⇒ 降级，绝不谎称中断 ——
		//    曲线缺失时还能靠「结束电量 vs 上限」判 ⇒ 仍然给得出结论。
		{"车端状态滞后", func(in *ChargeOutcomeInput) { in.Fleet.State = ChargeStateCharging }, OutcomeEndedEarly},
		{"车端状态读不到", func(in *ChargeOutcomeInput) {
			in.Fleet.State = ""
			in.HasFleet = false
			in.Curve = CurveFacts{}
		}, OutcomeEndedEarly},
		{"官方枚举之外的值", func(in *ChargeOutcomeInput) {
			in.Fleet.State = "SomethingNew"
			in.Curve = CurveFacts{}
		}, OutcomeEndedEarly},
		// —— 曲线没有、上限也没读到 ⇒ 真的判不出来 ——
		{"什么都没有", func(in *ChargeOutcomeInput) {
			in.Fleet.State = ""
			in.HasFleet = false
			in.Curve = CurveFacts{}
			in.Fleet.LimitSoc = nil
			in.LastEndSoc = nil
		}, OutcomeUnknown},
	}
	for _, c := range cases {
		in := base
		c.mut(&in)
		got := EvaluateChargeOutcome(in)
		if got.Outcome != c.want {
			t.Errorf("%s：outcome = %q, want %q（detail=%q）", c.name, got.Outcome, c.want, got.Detail)
		}
	}
}

// TestEvaluateChargeOutcomeRealCase 本轮修的那个真问题。
//
// 真机数据：2026-10-28 04:32→04:51，Tesla 超充，54% → 80%，充电上限 100%。
// 收尾曲线：末次 36 kW / 峰值 87 kW ⇒ 满功率一刀切 ⇒ 有人拔了枪。
// 旧版会说「✅ 充电完成」—— **假成功**。
func TestEvaluateChargeOutcomeRealCase(t *testing.T) {
	in := ChargeOutcomeInput{
		Fleet: FleetCharge{
			State:        ChargeStateDisconnected,
			LimitSoc:     intPtr(100),
			BatteryLevel: intPtr(80),
		},
		HasFleet: true,
		Curve: CurveFacts{
			LastNonZeroPower: intPtr(36),
			PeakPower:        intPtr(87),
		},
		LastEndSoc:      intPtr(80),
		LastEnergy:      fltPtr(17.56),
		LastDurationMin: intPtr(19),
	}
	got := EvaluateChargeOutcome(in)
	if got.Outcome == OutcomeComplete {
		t.Fatalf("只充到 80%%，上限 100%%，却判成「完成」—— 这就是本轮要修的假成功")
	}
	if got.Outcome != OutcomeEndedEarly {
		t.Fatalf("outcome = %q, want %q", got.Outcome, OutcomeEndedEarly)
	}
	if got.ReachedLimit == nil || *got.ReachedLimit {
		t.Fatalf("reached_limit 应当是 false，实际 %v", got.ReachedLimit)
	}
	// 判据本身必须可审计：曲线证据要露出来。
	if got.LastPowerKW == nil || *got.LastPowerKW != 36 {
		t.Errorf("last_power_kw = %v, want 36", got.LastPowerKW)
	}
	if got.PeakPowerKW == nil || *got.PeakPowerKW != 87 {
		t.Errorf("peak_power_kw = %v, want 87", got.PeakPowerKW)
	}
	// 结论里必须带着事实：80%、上限 100%、17.6 kWh、19 分钟
	for _, want := range []string{"80%", "100%", "17.6 kWh", "19 分钟"} {
		if !strings.Contains(got.Detail, want) {
			t.Errorf("detail 里应当有 %q，实际 %q", want, got.Detail)
		}
	}
	// ⚠️ 文案里不能出现「完成」两个字 —— 这正是本轮要消灭的假成功。
	if strings.Contains(got.Detail, "完成") {
		t.Errorf("没充满却出现「完成」，实际 detail=%q", got.Detail)
	}
	if strings.Contains(got.Verdict, "完成") {
		t.Errorf("没充满却出现「完成」，实际 verdict=%q", got.Verdict)
	}
	// 结论词要短（App 标题行要用），上限数字属于**事实**。
	if got.Verdict != "没到充电上限" {
		t.Errorf("verdict 应当是「没到充电上限」，实际 %q", got.Verdict)
	}
	if !strings.Contains(got.Detail, "上限 100%") {
		t.Errorf("detail 里应当带「上限 100%%」，实际 %q", got.Detail)
	}
}

// TestEvaluateChargeOutcomeRealCompleteCase 对照组：真的充到上限的那几场。
//
// 会话 78：2026-10-24 05:02→05:53，68% → 100%，收尾 4 kW / 峰值 69 kW。
func TestEvaluateChargeOutcomeRealCompleteCase(t *testing.T) {
	in := ChargeOutcomeInput{
		Fleet: FleetCharge{
			State:        ChargeStateDisconnected,
			LimitSoc:     intPtr(100),
			BatteryLevel: intPtr(96),
		},
		HasFleet: true,
		Curve: CurveFacts{
			LastNonZeroPower: intPtr(4),
			PeakPower:        intPtr(69),
		},
		LastEndSoc:      intPtr(100),
		LastEnergy:      fltPtr(20.64),
		LastDurationMin: intPtr(51),
	}
	got := EvaluateChargeOutcome(in)
	if got.Outcome != OutcomeComplete {
		t.Fatalf("outcome = %q, want %q（detail=%q）", got.Outcome, OutcomeComplete, got.Detail)
	}
	if got.ReachedLimit == nil || !*got.ReachedLimit {
		t.Fatalf("reached_limit 应当是 true，实际 %v", got.ReachedLimit)
	}
	for _, want := range []string{"100%", "20.6 kWh", "51 分钟"} {
		if !strings.Contains(got.Detail, want) {
			t.Errorf("detail 里应当有 %q，实际 %q", want, got.Detail)
		}
	}
}

// TestEvaluateChargeOutcomeGunStillIn 车端说「枪还插着」时优先于曲线。
//
// 场景：充电桩半路断电，车停充了但枪没拔。
// 曲线看是「功率掉到 0」，但那是**桩坏了**，不是「充到上限」——
// 只有车端知道枪还在不在，所以必须以车端为准。
func TestEvaluateChargeOutcomeGunStillIn(t *testing.T) {
	in := ChargeOutcomeInput{
		Fleet:    FleetCharge{State: ChargeStateStopped, LimitSoc: intPtr(100)},
		HasFleet: true,
		// 曲线看着像「满功率一刀切」（桩断了）
		Curve: CurveFacts{
			LastNonZeroPower: intPtr(36),
			PeakPower:        intPtr(87),
		},
		LastEndSoc:      intPtr(80),
		LastEnergy:      fltPtr(12.4),
		LastDurationMin: intPtr(30),
	}
	got := EvaluateChargeOutcome(in)
	if got.Outcome != OutcomeInterrupted {
		t.Fatalf("outcome = %q, want %q（detail=%q）", got.Outcome, OutcomeInterrupted, got.Detail)
	}
	if !strings.Contains(got.Detail, "枪还插着") {
		t.Errorf("detail 应当说明「枪还插着」，实际 %q", got.Detail)
	}
}

func TestEvaluateChargeOutcomeNoFacts(t *testing.T) {
	// 一个事实都没有 ⇒ unknown，detail 必须明说「判不出来」，不能编。
	got := EvaluateChargeOutcome(ChargeOutcomeInput{})
	if got.Outcome != OutcomeUnknown {
		t.Fatalf("outcome = %q, want %q", got.Outcome, OutcomeUnknown)
	}
	if got.Detail == "" {
		t.Fatal("unknown 也必须给一句话，不能是空字符串")
	}
	if got.ReachedLimit != nil {
		t.Fatalf("缺 end_soc/limit_soc 时 reached_limit 应当是 nil，实际 %v", *got.ReachedLimit)
	}
}

// TestEvaluateChargeOutcomeWhileCharging 正在充电时，**上一场**的数字必须作废。
//
// 场景：车正在充（当前 64%），DB 里同时留着上一场结束在 80% 的会话。
// 若把上一场的 end_soc 当本次显示，用户会看到「正在充电 · 充到 80%」——
// 那是另一种假事实。
func TestEvaluateChargeOutcomeWhileCharging(t *testing.T) {
	got := EvaluateChargeOutcome(ChargeOutcomeInput{
		Fleet:          FleetCharge{State: ChargeStateCharging, BatteryLevel: intPtr(64)},
		HasFleet:       true,
		ActiveCharging: true,
		ActiveSoc:      intPtr(64),
		ActiveEnergy:   fltPtr(3.2),
		// 上一场会话（不该出现在本次的显示里）
		LastEndSoc:      intPtr(80),
		LastEnergy:      fltPtr(17.56),
		LastDurationMin: intPtr(19),
		LastChargeID:    intPtr(79),
	})
	if got.Outcome != OutcomeCharging {
		t.Fatalf("outcome = %q, want %q", got.Outcome, OutcomeCharging)
	}
	if got.EndSoc != nil {
		t.Errorf("正在充电时 end_soc 应当是 nil（那是上一场的），实际 %v", *got.EndSoc)
	}
	if got.ChargeID != nil {
		t.Errorf("正在充电时 charge_id 应当是 nil（那是上一场的），实际 %v", *got.ChargeID)
	}
	if got.DurationMin != nil {
		t.Errorf("正在充电时 duration_min 应当是 nil（那是上一场的），实际 %v", *got.DurationMin)
	}
	if got.BatteryLevel == nil || *got.BatteryLevel != 64 {
		t.Errorf("battery_level = %v, want 64", got.BatteryLevel)
	}
	if !strings.Contains(got.Detail, "64%") {
		t.Errorf("detail 应当显示本次的 64%%，实际 %q", got.Detail)
	}
	if strings.Contains(got.Detail, "80%") {
		t.Errorf("detail 里混进了上一场的 80%%，实际 %q", got.Detail)
	}
}

func TestNumFields(t *testing.T) {
	m := map[string]any{
		"a": float64(3.7),
		"b": int(5),
		"c": "x",
		"d": nil,
	}
	if v := numFloatField(m, "a"); v == nil || *v != 3.7 {
		t.Errorf("a = %v, want 3.7", v)
	}
	if v := numIntField(m, "a"); v == nil || *v != 3 {
		t.Errorf("a(int) = %v, want 3", v)
	}
	if v := numIntField(m, "b"); v == nil || *v != 5 {
		t.Errorf("b = %v, want 5", v)
	}
	if v := numFloatField(m, "c"); v != nil {
		t.Errorf("c 应当是 nil，实际 %v", *v)
	}
	if v := numFloatField(m, "d"); v != nil {
		t.Errorf("d 应当是 nil，实际 %v", *v)
	}
	if v := numFloatField(m, "missing"); v != nil {
		t.Errorf("missing 应当是 nil")
	}
}
