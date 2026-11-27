package main

import (
	"strings"
	"testing"
)

// ⚠️ **v0.198.0**：当前充电状态的判定 —— 官方枚举**只认已核验的那几个**。
//
// ⚠️ 重点：**不认识的一律「充电状态不明」**（不猜，反幻觉 ADR #46）。
func TestChargeStatusConclusion(t *testing.T) {
	cases := []struct {
		state string
		want  string
	}{
		{ChargeStateCharging, appCopyChargeStateCharging},
		{ChargeStateComplete, appCopyChargeStateComplete},
		{ChargeStateStopped, appCopyChargeStateStopped},
		{ChargeStateNoPower, appCopyChargeStateNoPower},
		{ChargeStateDisconnected, appCopyChargeStateDisconnected},
		{ChargeStateUnknown, appCopyChargeStateUnknown},
		// ⚠️ 官方没列过的取值 ⇒ **不明**，不许自己解释
		{"NotConnected", appCopyChargeStateUnknown},
		{"Scheduled", appCopyChargeStateUnknown},
		// ⚠️ 空状态 ⇒ **不给数**（不是「不明」）⇒ 见 TestChargeStatusNoData
	}
	for _, c := range cases {
		cs := computeChargeStatus(FleetCharge{State: c.state}, true)
		if cs == nil {
			t.Fatalf("[%s] 不该是 nil", c.state)
		}
		if cs.Conclusion != c.want {
			t.Errorf("[%s] 结论不符：\n got %q\nwant %q", c.state, cs.Conclusion, c.want)
		}
	}
}

// ⚠️ **功率与「还差几分钟」只在正在充电时给** ——
//
//	官方在没充电时也给这两个字段但值是 0 ⇒ 「0 kW」「剩 0 分钟」是噪音。
func TestChargeStatusDetailOnlyWhenCharging(t *testing.T) {
	soc := 68
	limit := 90
	power := 120.0
	mins := 12
	fc := FleetCharge{
		State: ChargeStateCharging, BatteryLevel: &soc, LimitSoc: &limit,
		PowerKw: &power, MinutesToFull: &mins,
	}
	cs := computeChargeStatus(fc, true)
	for _, must := range []string{"当前 68%", "上限 90%", "120 kW", "剩 12 分钟"} {
		if !strings.Contains(cs.Detail, must) {
			t.Errorf("明细里少了 %q：%q", must, cs.Detail)
		}
	}
	// 没在充电 ⇒ 功率与分钟**不出现**（哪怕官方给了 0）
	fc.State = ChargeStateDisconnected
	cs = computeChargeStatus(fc, true)
	for _, must := range []string{"kW", "剩"} {
		if strings.Contains(cs.Detail, must) {
			t.Errorf("没在充电时明细里不该有 %q：%q", must, cs.Detail)
		}
	}
}

// ⚠️ **车睡着 ⇒ 这份读数是「断线前的最后已知值」** ⇒ 必须标 stale。
func TestChargeStatusStaleWhenCarAsleep(t *testing.T) {
	fc := FleetCharge{State: ChargeStateCharging}
	if cs := computeChargeStatus(fc, true); cs.Stale {
		t.Error("车醒着时不该是 stale")
	}
	cs := computeChargeStatus(fc, false)
	if !cs.Stale {
		t.Error("车睡着时必须是 stale（断线前的最后已知值）")
	}
}

// ⚠️ **一个字段都没读到 ⇒ 不给数**（宁可不说，也不说半句）。
func TestChargeStatusNoData(t *testing.T) {
	if cs := computeChargeStatus(FleetCharge{}, true); cs != nil {
		t.Errorf("没读到就不该给状态：%+v", cs)
	}
}

// ⚠️ **读不到时给一句说明**（不给数字）——
//
//	车离线 / 休眠时车端确实没有任何实时数据 ⇒ 那是**真实状态**；
//	但界面什么都不显示 ⇒ 用户会以为功能坏了 ⇒ 明说「现在读不到，因为车在离线」。
func TestChargeStatusUnavailableExplains(t *testing.T) {
	cases := []struct {
		state string
		want  string
	}{
		{"asleep", appCopyChargeUnavailableAsleep},
		{"offline", appCopyChargeUnavailableOffline},
		{"", appCopyChargeUnavailableNoData},
	}
	for _, c := range cases {
		cs := unavailableChargeStatus(c.state)
		if cs.Conclusion != c.want {
			t.Errorf("[%s] 说明句不符：\n got %q\nwant %q", c.state, cs.Conclusion, c.want)
		}
		if cs.Detail != "" {
			t.Errorf("[%s] 读不到时不该给数字明细：%q", c.state, cs.Detail)
		}
		if cs.Stale {
			t.Errorf("[%s] 读不到时没有「旧值」可言 ⇒ 不该是 stale", c.state)
		}
	}
}

// ⚠️ **宽度护栏**：结论 ≤ 44（常驻层）、明细 ≤ 96（ⓘ / 明细区）。
//
// ⚠️ 用**极端值**渲染（100% / 100% / 250 kW / 99 分钟）⇒
//
//	数字位数会变 ⇒ 措辞必须按最坏情况留余量。
func TestChargeStatusWidth(t *testing.T) {
	soc := 100
	limit := 100
	power := 250.0
	mins := 99
	cs := computeChargeStatus(FleetCharge{
		State: ChargeStateCharging, BatteryLevel: &soc, LimitSoc: &limit,
		PowerKw: &power, MinutesToFull: &mins,
	}, true)
	if w := copyVisualWidth(cs.Conclusion); w > appCopyWidthLimit {
		t.Errorf("充电状态结论宽度 %d > %d：%q", w, appCopyWidthLimit, cs.Conclusion)
	}
	if w := copyVisualWidth(cs.Detail); w > noteLineWidthLimit {
		t.Errorf("充电状态明细宽度 %d > %d：%q", w, noteLineWidthLimit, cs.Detail)
	}
}
