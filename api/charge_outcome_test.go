package main

import (
	"strings"
	"testing"
)

// ⚠️ **v0.78.0 / v0.79.0：充电结局判定**。
//
// 这组用例守的是通知通道里最要命的一类错：**假成功**。
//
// 旧版：充电结束一律说「✅ 充电完成」。
// ⇒ 车没充满也说「完成」。用户看到「完成」就不会再看第二眼。
//
// ⚠️ **v0.79.0 修正**：v0.78.0 的主判据（收尾曲线比值）**证明不了**
// 「没到充电上限」—— 高比值组里有 3 场结束在 94~96%。
// 所以「没到充电上限」这句话被拿掉了，
// 主判据换成「结束电量 vs **当时**的上限」。
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

// TestDetectTaper 收尾曲线判据 —— 用**真机 79 场会话**的数据。
//
// 数据来自本机 charging_processes + charges（2026-10-28 时刻的快照）。
//
// ⚠️ **v0.79.0**：这个判据**只能支持一个方向**。
//
//	低比值组（≤0.18，18 场）：结束电量**全部 ≥ 94%**
//	  ⇒ 「是按充到上限的方式收尾的」—— 成立（18/18，无反例）
//
//	高比值组（>0.18，61 场）：结束电量 8%~100% 都有
//	  其中会话 2(94%)、28(94%)、54(96%) 明明结束在高位
//	  ⇒ 「所以没充到上限」—— **不成立**（有反例）
//
// ⚠️ v0.78.0 只验证了「按这个阈值分组有没有空隙」——
// 那是**自我循环论证**（阈值就是按分位点选的）。
// 真正要验的是「这个判据与结论之间有没有反例」，
// 而反例就在高比值组里。
func TestDetectTaper(t *testing.T) {
	cases := []struct {
		name string
		last *int // 末次非零功率 kW
		peak *int // 本场峰值功率 kW
		want *bool
	}{
		// —— 低比值组：18 场，结束电量全部 ≥ 94% ⇒ 判「降流收尾」——
		{"会话78 充到100%", intPtr(4), intPtr(69), boolPtr(true)},
		{"会话77 充到100%", intPtr(5), intPtr(66), boolPtr(true)},
		{"会话76 充到100%", intPtr(3), intPtr(98), boolPtr(true)},
		{"会话65 充到100%", intPtr(3), intPtr(98), boolPtr(true)},
		{"会话62 充到100%", intPtr(10), intPtr(96), boolPtr(true)},
		{"会话22 结束96%", intPtr(13), intPtr(100), boolPtr(true)},
		{"会话9 结束96%", intPtr(11), intPtr(78), boolPtr(true)},
		// —— 高比值组：这里**有反例**（结束在高位却比值高）——
		{"会话54 结束96%（反例）", intPtr(13), intPtr(50), boolPtr(false)},
		{"会话2 结束94%（反例）", intPtr(22), intPtr(75), boolPtr(false)},
		{"会话28 结束94%（反例）", intPtr(18), intPtr(75), boolPtr(false)},
		{"会话79 结束80%", intPtr(36), intPtr(87), boolPtr(false)},
		{"会话75 结束36%", intPtr(122), intPtr(166), boolPtr(false)},
		{"会话74 结束62%", intPtr(72), intPtr(173), boolPtr(false)},
		{"会话73 结束77%", intPtr(37), intPtr(116), boolPtr(false)},
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
			LimitSoc:     intPtr(80),
			BatteryLevel: intPtr(80),
		},
		HasFleet: true,
		// 会话 79 的真实收尾曲线：末次 36 kW / 峰值 87 kW。
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
		//    所以它**优先于**一切。
		{"车端说停了（枪还插着）", func(in *ChargeOutcomeInput) { in.Fleet.State = ChargeStateStopped }, OutcomeInterrupted},
		{"车端说桩没供电", func(in *ChargeOutcomeInput) { in.Fleet.State = ChargeStateNoPower }, OutcomeNoPower},
		// —— 有「当时的上限」⇒ 唯一精确的判据 ——
		{"当时上限80 结束80⇒充到上限", func(in *ChargeOutcomeInput) {
			in.SessionLimitSoc = intPtr(80)
		}, OutcomeComplete},
		{"当时上限90 结束80⇒没到上限", func(in *ChargeOutcomeInput) {
			in.SessionLimitSoc = intPtr(90)
		}, OutcomeEndedEarly},
		// —— 没有「当时的上限」⇒ 曲线降流只能支持「充到上限附近」——
		{"无当时上限+曲线降流⇒附近", func(in *ChargeOutcomeInput) {
			in.Curve = CurveFacts{LastNonZeroPower: intPtr(4), PeakPower: intPtr(69)}
			in.LastEndSoc = intPtr(100)
		}, OutcomeTapered},
		// ⚠️ 无当时上限 + 曲线高比值 ⇒ **什么都推不出来** ⇒ unknown。
		//    v0.78.0 在这里说「没到充电上限」—— 那是没依据的话。
		{"无当时上限+曲线高比值⇒不知道", func(in *ChargeOutcomeInput) {}, OutcomeUnknown},
		// —— 车端说「充满了」⇒ 可信 ——
		{"车端说充满", func(in *ChargeOutcomeInput) {
			in.Fleet.State = ChargeStateComplete
		}, OutcomeComplete},
		// —— 车端状态滞后 / 读不到 ⇒ 降级，绝不谎称中断 ——
		{"车端状态滞后", func(in *ChargeOutcomeInput) {
			in.Fleet.State = ChargeStateCharging
		}, OutcomeUnknown},
		{"车端状态读不到", func(in *ChargeOutcomeInput) {
			in.Fleet.State = ""
			in.HasFleet = false
			in.Curve = CurveFacts{}
		}, OutcomeUnknown},
		{"官方枚举之外的值", func(in *ChargeOutcomeInput) {
			in.Fleet.State = "SomethingNew"
		}, OutcomeUnknown},
		// —— 什么都没有 ⇒ 真的判不出来 ——
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

// TestEvaluateChargeOutcomeSessionLimitIsTheOnlyPreciseJudge
//
// ⚠️ **v0.79.0 的核心用例**：同一场会话（真实会话 79），
// 只因为「当时的上限」不同，结论就**完全相反**：
//
//	当时上限 80% ⇒ 充到上限了（complete）
//	当时上限 90% ⇒ 没充到上限就结束了（ended_early）
//	没有当时上限 ⇒ 结束方式判断不了（unknown）
//
// ⇒ 这证明「当时的上限」是**唯一**能精确判定「充没充满」的东西，
// 也证明 v0.78.0 靠曲线说「没到充电上限」是**没有依据**的。
func TestEvaluateChargeOutcomeSessionLimitIsTheOnlyPreciseJudge(t *testing.T) {
	mk := func(sessionLimit *int) ChargeOutcomeInput {
		return ChargeOutcomeInput{
			Fleet: FleetCharge{
				State:        ChargeStateDisconnected,
				LimitSoc:     intPtr(80), // **现在**的上限（会变，不参与判定）
				BatteryLevel: intPtr(80),
			},
			HasFleet: true,
			// 真实会话 79 的收尾曲线：末次 36 kW / 峰值 87 kW。
			Curve: CurveFacts{
				LastNonZeroPower: intPtr(36),
				PeakPower:        intPtr(87),
			},
			LastEndSoc:      intPtr(80),
			LastEnergy:      fltPtr(17.56),
			LastDurationMin: intPtr(19),
			SessionLimitSoc: sessionLimit,
		}
	}

	hit := EvaluateChargeOutcome(mk(intPtr(80)))
	if hit.Outcome != OutcomeComplete {
		t.Fatalf("当时上限 80%%、结束 80%% ⇒ 应当判 complete，实际 %q（detail=%q）", hit.Outcome, hit.Detail)
	}
	if hit.ReachedLimit == nil || !*hit.ReachedLimit {
		t.Fatalf("reached_limit 应当是 true，实际 %v", hit.ReachedLimit)
	}
	if hit.Verdict != "充到上限了" {
		t.Errorf("verdict = %q, want 「充到上限了」", hit.Verdict)
	}

	miss := EvaluateChargeOutcome(mk(intPtr(90)))
	if miss.Outcome != OutcomeEndedEarly {
		t.Fatalf("当时上限 90%%、结束 80%% ⇒ 应当判 ended_early，实际 %q（detail=%q）", miss.Outcome, miss.Detail)
	}
	if miss.ReachedLimit == nil || *miss.ReachedLimit {
		t.Fatalf("reached_limit 应当是 false，实际 %v", miss.ReachedLimit)
	}
	if miss.Verdict != "没充到上限就结束了" {
		t.Errorf("verdict = %q, want 「没充到上限就结束了」", miss.Verdict)
	}

	unknown := EvaluateChargeOutcome(mk(nil))
	if unknown.Outcome != OutcomeUnknown {
		t.Fatalf("没有当时上限 ⇒ 应当判 unknown，实际 %q（detail=%q）", unknown.Outcome, unknown.Detail)
	}
	if unknown.Verdict != "结束方式判断不了" {
		t.Errorf("verdict = %q, want 「结束方式判断不了」", unknown.Verdict)
	}
	// ⚠️ 但**事实照报**：充到多少、充了多少度、用了多久。
	for _, want := range []string{"80%", "17.6 kWh", "19 分钟"} {
		if !strings.Contains(unknown.Detail, want) {
			t.Errorf("判不出来时 detail 也应当报事实 %q，实际 %q", want, unknown.Detail)
		}
	}
	// ⚠️ **现在的**上限（80%）绝不能出现在结论里 ——
	//    它推不出「当时到没到上限」。
	if strings.Contains(unknown.Detail, "上限") {
		t.Errorf("判不出来时不该拿现在的上限说话，实际 detail=%q", unknown.Detail)
	}
}

// TestEvaluateChargeOutcomeNoUnsupportedLimitClaim
//
// ⚠️ **v0.79.0 的红线**：「没到充电上限」这句话，
// 只有在**有当时的上限**且确实没到时才允许出现。
//
// v0.78.0 靠收尾曲线说这句话 —— 而曲线证明不了它
// （会话 2/28/54 结束在 94~96% 却被判「拔枪」）。
func TestEvaluateChargeOutcomeNoUnsupportedLimitClaim(t *testing.T) {
	cases := []struct {
		name string
		in   ChargeOutcomeInput
	}{
		{"无当时上限+高比值曲线", ChargeOutcomeInput{
			Fleet:           FleetCharge{State: ChargeStateDisconnected, LimitSoc: intPtr(80)},
			HasFleet:        true,
			Curve:           CurveFacts{LastNonZeroPower: intPtr(36), PeakPower: intPtr(87)},
			LastEndSoc:      intPtr(80),
			LastEnergy:      fltPtr(17.56),
			LastDurationMin: intPtr(19),
		}},
		{"无当时上限+降流曲线", ChargeOutcomeInput{
			Fleet:           FleetCharge{State: ChargeStateDisconnected, LimitSoc: intPtr(80)},
			HasFleet:        true,
			Curve:           CurveFacts{LastNonZeroPower: intPtr(4), PeakPower: intPtr(69)},
			LastEndSoc:      intPtr(96),
			LastEnergy:      fltPtr(54.9),
			LastDurationMin: intPtr(83),
		}},
		{"车端状态读不到", ChargeOutcomeInput{
			HasFleet:   false,
			LastEndSoc: intPtr(80),
		}},
	}
	for _, c := range cases {
		got := EvaluateChargeOutcome(c.in)
		for _, banned := range []string{"没到充电上限", "没充到充电上限"} {
			if strings.Contains(got.Verdict, banned) {
				t.Errorf("%s：verdict 出现没依据的话 %q（%q）", c.name, banned, got.Verdict)
			}
			if strings.Contains(got.Detail, banned) {
				t.Errorf("%s：detail 出现没依据的话 %q（%q）", c.name, banned, got.Detail)
			}
		}
	}
}

// TestEvaluateChargeOutcomeTaperedIsNotComplete
//
// 曲线降流只能支持「充到上限**附近**」，不能支持「充到上限了」——
// 18 场低比值会话里有 3 场结束在 94~96%（会话 9/22/34）。
func TestEvaluateChargeOutcomeTaperedIsNotComplete(t *testing.T) {
	got := EvaluateChargeOutcome(ChargeOutcomeInput{
		Fleet:           FleetCharge{State: ChargeStateDisconnected, LimitSoc: intPtr(100)},
		HasFleet:        true,
		Curve:           CurveFacts{LastNonZeroPower: intPtr(11), PeakPower: intPtr(78)},
		LastEndSoc:      intPtr(96),
		LastEnergy:      fltPtr(54.9),
		LastDurationMin: intPtr(83),
	})
	if got.Outcome != OutcomeTapered {
		t.Fatalf("outcome = %q, want %q（detail=%q）", got.Outcome, OutcomeTapered, got.Detail)
	}
	if got.Verdict != "充到上限附近" {
		t.Errorf("verdict = %q, want 「充到上限附近」", got.Verdict)
	}
	if got.ReachedLimit != nil {
		t.Errorf("没有当时的上限 ⇒ reached_limit 应当是 nil，实际 %v", *got.ReachedLimit)
	}
	if !strings.Contains(got.Detail, "96%") {
		t.Errorf("detail 应当报实际结束电量 96%%，实际 %q", got.Detail)
	}
}

// TestEvaluateChargeOutcomeGunStillIn 车端说「枪还插着」时优先于一切。
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
		t.Fatalf("缺 end_soc/当时上限 时 reached_limit 应当是 nil，实际 %v", *got.ReachedLimit)
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
	if v := numFloatField(m, "b"); v == nil || *v != 5 {
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
