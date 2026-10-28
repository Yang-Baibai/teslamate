package main

import (
	"encoding/json"
	"fmt"
	"strings"
	"time"
)

// ---------- 充电结局判定（唯一事实源） ----------
//
// ## 为什么需要它
//
// 旧版通知里，充电结束**一律**说「✅ 充电完成」——
// 不管它到底是充到上限了，还是在 80% 就停了。
//
// 实测本机最近一次充电（2026-10-28 04:32→04:51，Tesla 超充）：
// **54% → 80%**，充电上限 100%。
// 也就是说，车没充满，而通知说的是「充电完成」。
//
// 这和 v0.76.0 修掉的「车端拒绝过的事被报成已下发」是**同一类错**：
// **假成功**。用户看到「完成」就不会再看第二眼，
// 而真正需要知道「为什么没充满」的时刻被错过了。
//
// ## 判据：只认「当时的上限」，曲线只能当辅助证据
//
// ### ⚠️ v0.79.0 修正：v0.78.0 的曲线判据**不成立**，已拿掉
//
// v0.78.0 用「末次非零功率 / 本场峰值 ≤ 0.18 ⇒ 充到上限」当主判据，
// 并声称「实测 30 场两簇分得很开，中间没有空隙」。
//
// **那个验证是自我循环论证**：阈值就是按分位点选的，
// 再拿它去分组当然分得开。拿 79 场重新检验，两个方向都站不住：
//
//	低比值组（≤0.18，18 场）：结束电量 **全部 ≥ 94%**
//	  ⇒ 「是按充到上限的方式收尾的」—— **成立**（18/18，无反例）
//
//	高比值组（>0.18，61 场）：结束电量 8% ~ 100% 都有
//	  其中会话 2(94%)、28(94%)、54(96%) 明明结束在高位，却被判「拔枪」
//	  ⇒ 「所以没充到上限」—— **不成立**（有反例）
//
// 也就是说：**曲线能支持「充到上限」，但完全不能支持「没到上限」**。
// 「没到充电上限」这句话，用收尾曲线**得不出来** ——
// v0.78.0 却正是靠它给出「没到充电上限」的结论。
// 这不是措辞问题，是**假事实**：会话 79（54%→80%）在当时上限是 80% 的情况下，
// 实际是**充到了上限**，而被判成「没到上限」。
//
// ### ① 当时的上限（主判据，唯一精确的）
//
// 「结束电量 vs **当时**的上限」才是能判「充没充满」的东西。
// ⚠️ 必须是**当时**的上限，不是现在的 —— `charge_limit_soc` 会变，
// 而且**变化方向不确定**（可能之后调高，也可能调低）：
//
//	E < 现在上限 ⇒ 当时上限可能更高也可能更低 ⇒ **推不出**「当时到没到」
//	E ≥ 现在上限 ⇒ 当时上限可能更低 ⇒ 也推不出
//
// ⇒ 所以「当时的上限」必须**在充电期间记下来**。
//   v0.79.0 起，`tm_charge_limits` 表在充电期间记录每个会话观测到的上限。
//
// ### ② 收尾曲线（辅助证据，只能支持「充到上限」）
//
// 电池逼近充电上限时，BMS 会把充电功率一路压低；
// 而无论什么原因结束，末次功率相对峰值都偏高。
// 18 场低比值会话**无一例外**结束在 94% 以上 ⇒ 这个方向可靠。
// 反方向（高比值 ⇒ 没到上限）**不成立**，所以它**只能**用来支持「充到上限」。
//
// ### ③ 车端 charging_state（只回答「枪还在不在」）
//
// 官方枚举：Charging / Complete / Stopped / NoPower / Disconnected / Unknown。
// 它唯一不可替代的作用是回答**枪现在还插着吗**：
// `Stopped` / `NoPower` 说明枪还插着（车端亲口说的），
// 这时才能说「中断」「桩没供电」；
// 枪已经拔了（Disconnected）时，它对「充没充满」没有任何发言权。
//
// ### ④ 判不出来就说判不出来
//
// 没有「当时的上限」（历史会话都是）、曲线也不支持方向时，
// **只报事实**（充到多少、充了多少度、用了几分钟），
// 结论写「结束方式判断不了」—— **不编**。

// 车端 charging_state 的官方取值（Fleet API）。
// ⚠️ 只认官方列出来的；不认识的一律当 unknown，**不猜**。
const (
	ChargeStateCharging     = "Charging"
	ChargeStateComplete     = "Complete"
	ChargeStateStopped      = "Stopped"
	ChargeStateNoPower      = "NoPower"
	ChargeStateDisconnected = "Disconnected"
	ChargeStateUnknown      = "Unknown"
)

// 充电结局（服务端算好，App / 推送脚本 / Hermes 工具都只读结果）。
const (
	// OutcomeCharging 还在充。
	OutcomeCharging = "charging"
	// OutcomeComplete 充到上限了（**精确**：有当时的充电上限作证）。
	OutcomeComplete = "complete"
	// OutcomeTapered 收尾时电流已降下来 ⇒ 大概率充到位了，但**不知道当时的上限**，
	// 所以只能说「充到上限附近」，不能说「充到上限了」。
	//
	// ⚠️ 这是 v0.79.0 新增的一档。v0.78.0 把「曲线降流」直接叫「充到上限」，
	// 而 18 场低比值会话里有 3 场结束在 94~96% —— 不是 100%。
	OutcomeTapered = "tapered"
	// OutcomeEndedEarly 没充到上限就结束了（**精确**：有当时的充电上限作证）。
	OutcomeEndedEarly = "ended_early"
	// OutcomeInterrupted 枪还插着，但车端停了（充不了了）。
	OutcomeInterrupted = "interrupted"
	// OutcomeNoPower 枪插着，但充电桩没有供电。
	OutcomeNoPower = "no_power"
	// OutcomeUnknown **判不出来**。事实照报，结论不编。
	OutcomeUnknown = "unknown"
)

// FleetCharge 车端 charge_state 里我们用得上的那几个字段。
type FleetCharge struct {
	State        string   // charging_state（官方枚举，空 = 没读到）
	LimitSoc     *int     // charge_limit_soc：充电上限（%）
	BatteryLevel *int     // battery_level：当前电量（%）
	EnergyAdded  *float64 // charge_energy_added：本次充入（kWh）
}

// ---------- 收尾曲线 ----------

// CurveFacts 收尾曲线的两个数（来自 charges 表这场会话的采样）。
type CurveFacts struct {
	// LastNonZeroPower 最后一个非零功率采样（kW）。
	// 「收尾那一刻车还在吃多少功率」。
	LastNonZeroPower *int
	// PeakPower 本场峰值功率（kW）。
	PeakPower *int
}

// TaperThreshold 收尾降流的判定阈值。
//
// ⚠️ 取 0.18 而不是两簇的几何中位数（≈0.164），是**故意偏向「不是完成」**：
// 说「完成」而实际没充满，正是本轮要消灭的假成功；
// 反过来只是少说一句「完成」，代价小得多。
const TaperThreshold = 0.18

// DetectTaper 判定这场会话是不是「BMS 降流收尾」。
//
// 返回 nil = **判不出来**（没有采样 / 全程 0 kW），
// 调用方必须降级处理，**不能**当成 false（那等于说「肯定没充到上限」）。
//
// ⚠️ v0.79.0：它的返回值**只能**用来支持「充到上限**附近**」，
// **不能**用来支持「没充到上限」——
// 高比值组（>0.18）里会话 2(94%)、28(94%)、54(96%) 就是反例。
// 低比值组（≤0.18）18 场**全部**结束在 94% 以上 ⇒ 这个方向可靠。
func DetectTaper(c CurveFacts) *bool {
	if c.LastNonZeroPower == nil || c.PeakPower == nil {
		return nil
	}
	last, peak := *c.LastNonZeroPower, *c.PeakPower
	if peak <= 0 || last <= 0 {
		return nil
	}
	hit := float64(last) <= TaperThreshold*float64(peak)
	return &hit
}

// ChargeOutcome 「这次充电到底怎么了」。
type ChargeOutcome struct {
	// State 车端原始的 charging_state（便于排查，不解释）。
	State string `json:"state"`
	// Outcome 结局枚举（见上面的常量）。
	Outcome string `json:"outcome"`
	// Verdict 一句话**结论**（不含数字）。
	//
	// ⚠️ **文案只在这里有一份** —— App、推送脚本、Hermes 工具全都用它。
	// 三处各写一份文案，迟早漂移（v0.76.0 已经吃过一次亏：
	// 「完成后是什么样」在服务端与客户端各写了一份）。
	Verdict string `json:"verdict"`
	// Detail 完整一句话 = Verdict + 事实。
	//
	// 给「只要一句话」的场合用（推送正文、Hermes 的回答）。
	Detail string `json:"detail"`
	// LimitSoc 车**现在**的充电上限（%）。nil = 没读到。
	//
	// ⚠️ 这是**当前**状态，**不参与判定** —— 上限会变且方向不确定，
	//   用它推「当时到没到上限」是错的。判定用 SessionLimitSoc。
	LimitSoc *int `json:"limit_soc"`
	// SessionLimitSoc **那场会话当时**的充电上限（%）。nil = 没记到。
	//
	// ✅ 这是唯一能精确判定「充没充满」的依据。
	//   v0.79.0 起由 `tm_charge_limits` 在充电期间记录。
	//   历史会话（v0.79.0 之前）没有 ⇒ nil。
	SessionLimitSoc *int `json:"session_limit_soc,omitempty"`
	// EndSoc 本次会话的**结束电量**（%，DB 精确值）。nil = 没有已结束的会话。
	//
	// ⚠️ **正在充电时它是上一场会话的结束电量**，所以充电中这一项恒为 nil ——
	//    拿上一场的数当本次显示，就是另一种假事实。
	EndSoc *int `json:"end_soc"`
	// BatteryLevel 当前电量（%）。nil = 没读到。
	BatteryLevel *int `json:"battery_level"`
	// EnergyAdded 本次充入（kWh，电池侧口径）。
	EnergyAdded *float64 `json:"energy_added_kwh"`
	// DurationMin 本次时长（分钟）。
	DurationMin *int `json:"duration_min"`
	// ReachedLimit 是否充到了（当时的）充电上限。
	// nil = 判不了（没有当时的上限）。
	//
	// ⚠️ v0.79.0：这个字段现在**只在有「当时的上限」时**才有值。
	//   收尾曲线降流**不**填它 —— 曲线降流只能支持「充到上限附近」，
	//   不能支持「充到上限了」（18 场低比值里有 3 场结束在 94~96%）。
	ReachedLimit *bool `json:"reached_limit"`
	// ChargeID / EndedAt 最近一次已结束会话的 id 与结束时刻。
	ChargeID *int       `json:"charge_id"`
	EndedAt  *time.Time `json:"ended_at"`
	// LastPowerKW / PeakPowerKW 收尾曲线的原始证据。
	//
	// ⚠️ 把判据本身暴露出来，是为了**可审计**：
	// 「为什么说充到上限」这件事，用户应该能自己核对。
	LastPowerKW *int `json:"last_power_kw"`
	PeakPowerKW *int `json:"peak_power_kw"`
}

// ChargeOutcomeInput 判定结局所需的全部事实。
type ChargeOutcomeInput struct {
	Fleet          FleetCharge
	HasFleet       bool // 是否读到了车端 charge_state
	ActiveCharging bool // DB 里是否还有进行中的充电
	// 进行中这场的事实（正在充电时才有意义）。
	ActiveSoc    *int     // 本次充电的当前电量（%）
	ActiveEnergy *float64 // 本次充电的当前充入（kWh）
	Curve        CurveFacts
	// 最近一次**已结束**会话的事实。
	LastEndSoc      *int
	LastEnergy      *float64
	LastDurationMin *int
	LastChargeID    *int
	LastEndedAt     *time.Time
	// SessionLimitSoc **那场会话当时**的充电上限（%）。nil = 没记到。
	//
	// ⚠️ 这是**唯一**能精确判定「充没充满」的东西。
	//   它由 `tm_charge_limits` 表在**充电期间**逐次记录；
	//   历史会话（v0.79.0 之前）没有 ⇒ nil ⇒ 只能给「近似」结论或直说不知道。
	//
	// ⚠️ 千万不要用 `Fleet.LimitSoc`（**现在**的上限）代替它 ——
	//   上限会变，而且**方向不确定**：现在 95%、当时 90% 时，
	//   结束电量 92% 其实是「当时到了上限」，用现在的 95% 会判成「没到」。
	SessionLimitSoc *int
}

// 结论短语（不含数字）。⚠️ 文案只此一份，下游只读不写。
const (
	verdictCharging    = "正在充电"
	verdictComplete    = "充到上限了"
	verdictTapered     = "充到上限附近"
	verdictEndedEarly  = "没充到上限就结束了"
	verdictInterrupted = "枪还插着，但充不了了"
	verdictNoPower     = "充电桩没有供电"
	verdictUnknown     = "结束方式判断不了"
)

// EvaluateChargeOutcome 判定「这次充电到底怎么了」（纯函数，可单测）。
//
// 判定顺序（每一步都只认事实，判不出来就降级，**不猜**）：
//
//	① DB 说还在充                        → charging
//	② 车端说 Stopped（枪插着、车端停了）  → interrupted
//	③ 车端说 NoPower（枪插着、桩没电）    → no_power
//	④ **有「当时的上限」**（唯一精确判据）：
//	     结束电量 ≥ 当时上限 → complete（充到上限了）
//	     结束电量 <  当时上限 → ended_early（没充到上限就结束了）
//	⑤ 收尾曲线降流（辅助证据，只能支持这一个方向）：
//	     降流收尾 → tapered（充到上限**附近**）
//	⑥ 车端说 Complete                       → complete
//	⑦ 都判不出来                             → unknown
//	     ⚠️ 事实照报（充到多少 / 充了多少度 / 用了多久），**结论不编**。
//
// ⚠️ 为什么 ②③ 排在曲线前面：
// 曲线回答「这场会话怎么结束的」，车端状态回答「**枪现在还在不在**」——
// 只有后者能支持「中断」「桩没供电」这类说法（枪还插着）。
// 枪已经拔出时（Disconnected），车端对「充没充满」没有任何发言权。
//
// ⚠️ v0.79.0 的关键修正：
// ① 「没到充电上限」这句话**删掉了** —— 收尾曲线证明不了它
//
//	（高比值组里有 3 场结束在 94~96%），v0.78.0 靠它下结论是假事实。
//
// ② 「当前上限」不再参与判定 —— 上限会变且方向不确定，推不出「当时到没到」。
// ③ 「当时的上限」从 `tm_charge_limits` 取（充电期间记录）。
func EvaluateChargeOutcome(in ChargeOutcomeInput) ChargeOutcome {
	out := ChargeOutcome{
		State:           in.Fleet.State,
		LimitSoc:        in.Fleet.LimitSoc,
		SessionLimitSoc: in.SessionLimitSoc,
		EndSoc:          in.LastEndSoc,
		BatteryLevel:    in.Fleet.BatteryLevel,
		EnergyAdded:     in.LastEnergy,
		DurationMin:     in.LastDurationMin,
		ChargeID:        in.LastChargeID,
		EndedAt:         in.LastEndedAt,
		LastPowerKW:     in.Curve.LastNonZeroPower,
		PeakPowerKW:     in.Curve.PeakPower,
	}

	// 收尾曲线（辅助证据）。nil = 判不出来。
	taper := DetectTaper(in.Curve)

	// 「结束电量 vs **当时**的上限」—— 唯一精确的判据。
	// ⚠️ 用的是会话当时记下的上限，不是现在的上限。
	if in.LastEndSoc != nil && in.SessionLimitSoc != nil {
		hit := *in.LastEndSoc >= *in.SessionLimitSoc
		out.ReachedLimit = &hit
	} else if taper != nil {
		// 没有当时的上限 ⇒ 曲线降流只能支持「充到上限**附近**」，
		// 不能支持「没到上限」（那个方向有反例）。
		out.ReachedLimit = nil
	}

	if in.ActiveCharging {
		// ⚠️ **正在充电时，上一场会话的数字全部作废** ——
		//    EndSoc / EnergyAdded / DurationMin 都是**上一场**的，
		//    当成本次显示就是另一种假事实。
		out.Outcome = OutcomeCharging
		out.Verdict = verdictCharging
		out.EndSoc = nil
		out.EnergyAdded = nil
		out.DurationMin = nil
		out.ChargeID = nil
		out.EndedAt = nil
		out.ReachedLimit = nil
		if in.ActiveSoc != nil {
			out.BatteryLevel = in.ActiveSoc
		}
		if in.ActiveEnergy != nil {
			out.EnergyAdded = in.ActiveEnergy
		}
		out.Detail = chargeDetail(out, verdictCharging)
		return out
	}

	// ②③ 车端亲口说的「枪还插着，但充不了」。
	if in.HasFleet {
		switch in.Fleet.State {
		case ChargeStateStopped:
			out.Outcome = OutcomeInterrupted
			out.ReachedLimit = taper
			out.Verdict = verdictInterrupted
			out.Detail = chargeDetail(out, verdictInterrupted)
			return out
		case ChargeStateNoPower:
			out.Outcome = OutcomeNoPower
			out.ReachedLimit = taper
			out.Verdict = verdictNoPower
			out.Detail = chargeDetail(out, verdictNoPower)
			return out
		}
	}

	// ④ **有「当时的上限」** ⇒ 唯一精确的判据。
	if out.ReachedLimit != nil {
		if *out.ReachedLimit {
			out.Outcome = OutcomeComplete
			out.Verdict = verdictComplete
		} else {
			out.Outcome = OutcomeEndedEarly
			out.Verdict = verdictEndedEarly
		}
		out.Detail = chargeDetail(out, out.Verdict)
		return out
	}

	// ⑤ 收尾曲线降流（辅助证据，**只能**支持这一个方向）。
	//
	// ⚠️ 曲线降流 ⇒ 「充到上限**附近**」，不是「充到上限」——
	// 18 场低比值会话里有 3 场结束在 94~96%，说「充到上限」是吹牛。
	// ⚠️ 曲线**高**比值 ⇒ 什么都不推：高比值组结束电量 8%~100% 都有，
	//    会话 2(94%)、28(94%)、54(96%) 就落在高比值侧 ⇒ 这方向有反例。
	if taper != nil && *taper {
		out.Outcome = OutcomeTapered
		out.Verdict = verdictTapered
		out.Detail = chargeDetail(out, out.Verdict)
		return out
	}

	// ⑥ 车端说「充满了」（车端亲口说的，可信）。
	if in.HasFleet && in.Fleet.State == ChargeStateComplete {
		out.Outcome = OutcomeComplete
		out.Verdict = verdictComplete
		out.Detail = chargeDetail(out, verdictComplete)
		return out
	}

	// ⑦ 都判不出来 ⇒ 说不知道，**事实照报、结论不编**。
	//
	// ⚠️ 事实照报很重要：用户至少该知道「充到多少、充了多少度、用了多久」，
	//    只是「怎么结束的」还说不准。v0.78.0 这里只回了结论词，
	//    等于白丢了一堆已知事实。
	out.Outcome = OutcomeUnknown
	out.Verdict = verdictUnknown
	out.Detail = chargeDetail(out, verdictUnknown)
	return out
}

// chargeDetail 组装完整一句话 = 结论 + 事实。
//
// ⚠️ 数字全部来自事实（DB 的结束电量 / 车端充入量 / DB 的时长），
// 缺哪个就少说哪个，**不填占位数字**。
//
// ⚠️ v0.79.0：**不再摆「（上限 X%）」** —— 那个上限是**现在**的，
// 不是那场会话当时的 ⇒ 把它跟结束电量并排显示，等于在暗示
// 「当时差了多少」，而我们并不知道「当时的上限是多少」。
// 「当时的上限」要等 `tm_charge_limits` 里有了记录才能说。
func chargeDetail(o ChargeOutcome, verdict string) string {
	parts := []string{}
	if o.EndSoc != nil {
		parts = append(parts, fmt.Sprintf("充到 %d%%", *o.EndSoc))
	} else if o.BatteryLevel != nil {
		parts = append(parts, fmt.Sprintf("当前 %d%%", *o.BatteryLevel))
	}
	if o.EnergyAdded != nil {
		parts = append(parts, fmt.Sprintf("充入 %.1f kWh", *o.EnergyAdded))
	}
	if o.DurationMin != nil {
		parts = append(parts, fmt.Sprintf("用了 %d 分钟", *o.DurationMin))
	}
	if len(parts) == 0 {
		return verdict
	}
	return verdict + "：" + strings.Join(parts, "，")
}

// ---------- 从缓存的原始 vehicle_data 里取 charge_state ----------

// chargeContextFromRaw 解析车端 charge_state（纯函数，可单测）。
//
// ⚠️ 没读到的字段一律 nil，**不压成 0** ——
// 「上限 0%」和「没读到上限」是完全不同的两件事。
func chargeContextFromRaw(raw map[string]any) (FleetCharge, bool) {
	if raw == nil {
		return FleetCharge{}, false
	}
	cs, ok := raw["charge_state"].(map[string]any)
	if !ok || cs == nil {
		return FleetCharge{}, false
	}
	fc := FleetCharge{}
	if v, ok := cs["charging_state"].(string); ok {
		fc.State = strings.TrimSpace(v)
	}
	fc.LimitSoc = numIntField(cs, "charge_limit_soc")
	fc.BatteryLevel = numIntField(cs, "battery_level")
	fc.EnergyAdded = numFloatField(cs, "charge_energy_added")
	// 一个字段都没读到 ⇒ 当作「不知道」。
	if fc.State == "" && fc.LimitSoc == nil && fc.BatteryLevel == nil && fc.EnergyAdded == nil {
		return FleetCharge{}, false
	}
	return fc, true
}

// numFloatField 取一个数值字段（JSON 数字 → float64）。
func numFloatField(m map[string]any, key string) *float64 {
	v, ok := m[key]
	if !ok || v == nil {
		return nil
	}
	switch t := v.(type) {
	case float64:
		return &t
	case int:
		f := float64(t)
		return &f
	case json.Number:
		f, err := t.Float64()
		if err != nil {
			return nil
		}
		return &f
	default:
		return nil
	}
}

// numIntField 取一个整数字段。
func numIntField(m map[string]any, key string) *int {
	f := numFloatField(m, key)
	if f == nil {
		return nil
	}
	i := int(*f)
	return &i
}
