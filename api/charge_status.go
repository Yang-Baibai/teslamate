package main

import (
	"fmt"
	"strings"
	"time"
)

// ---------- 「当前充电状态」（官方 charge_state） ----------
//
// ## 为什么要有它（v0.198.0）
//
// 用户定的方向是「电池健康 **＋ 电池状态**」⇒ 前几轮做的全是**电池健康**
// （容量 / 衰减 / 循环 / 充电习惯）⇒ **当前充电状态**这块是**空的**：
// 车况页看不出「现在在不在充 / 充到多少 / 上限多少 / 还差多久」。
//
// ## 数据来源：车端 charge_state（Fleet API 官方字段）
//
// 实测这台车（中国区 vehicle_data）的 charge_state 段（2026-11-27）：
//
//	charging_state / charge_limit_soc / charge_limit_soc_max|min|std /
//	charger_power / charger_voltage / charger_actual_current /
//	time_to_full_charge / minutes_to_full_charge /
//	battery_heater_on / charge_energy_added / battery_level /
//	charge_port_latch / charge_port_door_open / scheduled_charging_pending ...
//
// ⚠️ **枚举只认官方列出来的**（与 [charge_outcome.go] 同一份：
//   Charging / Complete / Stopped / NoPower / Disconnected / Unknown）
//   ⇒ 不认识的**一律当 unknown**，**不猜**（反幻觉，ADR #46）。
//
// ## 新鲜度：车睡着 ⇒ 轮询缓存停更 ⇒ 值是「断线前的最后已知值」
//
// ⇒ 复用 [cabinReadingCurrent]（v0.137.0 立的判据：车醒着没有）——
//   同一份轮询数据，「是不是当前的」只能有一个答案（ADR #38）。
// ⇒ 车睡着 ⇒ Stale=true ⇒ App 显示「**上次已知**：…」（不当现状，不当告警）。
//
// ## 措辞：只在服务端有一份（ADR #41）
//
//	Conclusion = 状态结论（≤ 44，常驻印）
//	Detail     = 数字明细（≤ 96，ⓘ / 明细区印）
// ⇒ 客户端只透传，**一个字都不自己拼**。

// ChargeStatus 当前充电状态（服务端判定 ＋ 一句结论）。
type ChargeStatus struct {
	// State 车端 charging_state 的**官方原值**（排查用；解释看 Conclusion）。
	State string `json:"state"`
	// Conclusion 状态结论（**服务端唯一一份**，ADR #41）。
	Conclusion string `json:"conclusion"`
	// Detail 数字明细（当前电量 / 上限 / 功率 / 还差几分钟）。
	Detail string `json:"detail"`
	// Soc 当前电量（%，官方 battery_level）。
	Soc *int `json:"soc"`
	// LimitSoc 充电上限（%，官方 charge_limit_soc）。
	LimitSoc *int `json:"limit_soc"`
	// PowerKw 当前充电功率（kW，官方 charger_power）。
	PowerKw *float64 `json:"power_kw"`
	// MinutesToFull 充到上限还差多少分钟（官方 minutes_to_full_charge）。
	MinutesToFull *int `json:"minutes_to_full"`
	// BatteryHeaterOn 电池加热是否开着（官方 battery_heater_on）。
	BatteryHeaterOn *bool `json:"battery_heater_on"`
	// EnergyAddedKwh 本次充入（kWh，官方 charge_energy_added）。
	EnergyAddedKwh *float64 `json:"energy_added_kwh"`
	// Available **读到了**真实的当前状态吗？
	//
	// ⚠️ false = 车端现在**没有任何实时数据**（离线 / 休眠 / 还没拿到过）
	//   ⇒ Conclusion 是「为什么读不到」的那句，**不是**状态本身
	//   ⇒ App 据此降级排版（小字 ＋ 中性色 ⇒ 别把「读不到」说得像结论）。
	Available bool `json:"available"`
	// Stale 这份读数**不是当前的**（车睡着 ⇒ 断线前的最后已知值）。
	Stale bool `json:"stale"`
	// At 这份读数的**车端时刻**（官方 charge_state.timestamp）。
	At *time.Time `json:"at"`
}

// computeChargeStatus 判定当前充电状态（纯函数，可单测）。
//
// ⚠️ carOnline=false（车睡着）⇒ Stale=true ⇒ 值只是「断线前的最后已知值」。
func computeChargeStatus(fc FleetCharge, carOnline bool) *ChargeStatus {
	if fc.State == "" && fc.LimitSoc == nil && fc.BatteryLevel == nil {
		return nil
	}
	cs := &ChargeStatus{
		Available:       true,
		State:           fc.State,
		Soc:             fc.BatteryLevel,
		LimitSoc:        fc.LimitSoc,
		PowerKw:         fc.PowerKw,
		MinutesToFull:   fc.MinutesToFull,
		BatteryHeaterOn: fc.BatteryHeaterOn,
		EnergyAddedKwh:  fc.EnergyAdded,
		Stale:           !carOnline,
		At:              fc.At,
	}
	cs.Conclusion = renderChargeConclusion(cs)
	cs.Detail = renderChargeDetail(cs)
	return cs
}

// renderChargeConclusion 充电状态的**一句结论**（服务端唯一一份）。
//
// ⚠️ 官方枚举**只认已核验的那几个** ⇒ 其余一律「充电状态不明」（**不猜**）。
func renderChargeConclusion(cs *ChargeStatus) string {
	if cs == nil {
		return ""
	}
	switch cs.State {
	case ChargeStateCharging:
		return appCopyChargeStateCharging
	case ChargeStateComplete:
		return appCopyChargeStateComplete
	case ChargeStateStopped:
		return appCopyChargeStateStopped
	case ChargeStateNoPower:
		return appCopyChargeStateNoPower
	case ChargeStateDisconnected:
		return appCopyChargeStateDisconnected
	default:
		return appCopyChargeStateUnknown
	}
}

// renderChargeDetail 数字明细（**一行**，服务端拼 ⇒ 整行 ≤ noteLineWidthLimit）。
//
// ⚠️ **功率与「还差几分钟」只在正在充电时给** ——
//
//	官方在没充电时也给这两个字段，但它们的值是 0
//	⇒ 「0 kW」「剩 0 分钟」是**噪音**，还会被读成「刚充完」。
func renderChargeDetail(cs *ChargeStatus) string {
	if cs == nil {
		return ""
	}
	parts := []string{}
	if cs.Soc != nil {
		// ⚠️ 车睡着时这份读数**不是现在的** ⇒ 不许说「当前」（那是事实错误）。
		if cs.Stale {
			parts = append(parts, fmt.Sprintf(appCopyChargeSocLastTmpl, *cs.Soc))
		} else {
			parts = append(parts, fmt.Sprintf(appCopyChargeSocTmpl, *cs.Soc))
		}
	}
	if cs.LimitSoc != nil {
		parts = append(parts, fmt.Sprintf(appCopyChargeLimitTmpl, *cs.LimitSoc))
	}
	if cs.State == ChargeStateCharging {
		if cs.PowerKw != nil {
			parts = append(parts, fmt.Sprintf(appCopyChargePowerTmpl, formatChargePowerKw(*cs.PowerKw)))
		}
		if cs.MinutesToFull != nil {
			parts = append(parts, fmt.Sprintf(appCopyChargeRemainTmpl, *cs.MinutesToFull))
		}
	}
	return strings.Join(parts, " · ")
}

// formatChargePowerKw 充电功率显示（kW，整数）。
//
// ⚠️ 官方 charger_power 是 float（实测 120.0）⇒ 显示成「120 kW」而不是「120.0 kW」。
func formatChargePowerKw(v float64) string {
	return fmt.Sprintf("%.0f", v)
}

// currentChargeStatus 当前充电状态（全局入口）。
//
// ⚠️ pollState = 车端 state（online / asleep / offline）⇒
//
//	「这份读数是不是当前的」用 [cabinReadingCurrent] 判（与车外/车内温度同一个答案）。
//
// ⚠️ **读不到时也给一句说明**（不给数字）：车离线 / 休眠时车端**确实**
//
//	没有任何实时数据（实测：Tesla 连 wake_up 都发不出去、vehicle_data 直接 408）
//	⇒ 那是**真实状态** ⇒ 但界面什么都不显示会让用户以为功能坏了
//	⇒ 明说「现在读不到，因为车在离线」（与胎压卡的空态同一套做法）。
func currentChargeStatus(pollState string) *ChargeStatus {
	fc, ok := teslaChargeContext()
	if !ok {
		return unavailableChargeStatus(pollState)
	}
	return computeChargeStatus(fc, cabinReadingCurrent(pollState))
}

// unavailableChargeStatus **读不到**时的那一段（只给一句说明，不给数字）。
func unavailableChargeStatus(pollState string) *ChargeStatus {
	cs := &ChargeStatus{}
	switch pollState {
	case "asleep":
		cs.Conclusion = appCopyChargeUnavailableAsleep
	case "offline":
		cs.Conclusion = appCopyChargeUnavailableOffline
	default:
		cs.Conclusion = appCopyChargeUnavailableNoData
	}
	return cs
}
