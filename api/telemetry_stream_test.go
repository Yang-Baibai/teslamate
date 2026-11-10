package main

import (
	"testing"
	"time"
)

// ---------- 遥测流级新鲜度（v0.136.0） ----------

func tsTime(t *testing.T, s string) *time.Time {
	t.Helper()
	tm, err := time.Parse(time.RFC3339, s)
	if err != nil {
		t.Fatalf("时间解析失败 %q：%v", s, err)
	}
	return &tm
}

// TestClassifyTelemetryOrigin 判「车端新消息 vs broker 重放」的三条分支。
func TestClassifyTelemetryOrigin(t *testing.T) {
	cases := []struct {
		name    string
		known   bool
		online  bool
		want    telemetryOrigin
		comment string
	}{
		{"车端在线", true, true, telemetryOriginCar, "在线 ⇒ 它发得出来 ⇒ 算车端新消息"},
		{"车端离线", true, false, telemetryOriginReplay, "离线 ⇒ 它发不出来 ⇒ 只能是 broker 重放"},
		{"连接状态未知", false, false, telemetryOriginUnknown, "刚启动还没收到连接状态 ⇒ 保守，不冒充新鲜"},
		{"连接状态未知但标记在线", false, true, telemetryOriginUnknown, "状态未知时不能只凭 online 说是车端消息"},
	}
	for _, c := range cases {
		got := classifyTelemetryOrigin(c.known, c.online)
		if got != c.want {
			t.Errorf("%s：classifyTelemetryOrigin(known=%v, online=%v) = %v，期望 %v（%s）",
				c.name, c.known, c.online, got, c.want, c.comment)
		}
	}
}

// TestTelemetryStreamFreshness 流级诚实时刻：取各字段诚实时刻里最新的那个。
func TestTelemetryStreamFreshness(t *testing.T) {
	t1 := tsTime(t, "2026-11-09T11:40:22Z")
	t2 := tsTime(t, "2026-11-09T15:01:09Z")

	// ① 车离线：所有字段的诚实时刻都是「断线时刻」⇒ 流级也应该是它。
	at, stale := telemetryStreamFreshness([]telemetryFieldView{
		{Field: "InsideTemp", At: t1, Stale: true},
		{Field: "Soc", At: t1, Stale: true},
	})
	if at == nil || !at.Equal(*t1) {
		t.Errorf("车离线时流级时刻 = %v，期望断线时刻 %v", at, t1)
	}
	if !stale {
		t.Errorf("车离线时流级应当标 stale（那是车断线前的最后已知值）")
	}

	// ② 车在线：最新的那个字段时刻就是流级时刻，且**不** stale。
	at, stale = telemetryStreamFreshness([]telemetryFieldView{
		{Field: "InsideTemp", At: t1, Stale: true},
		{Field: "Soc", At: t2, Stale: false},
	})
	if at == nil || !at.Equal(*t2) {
		t.Errorf("车在线时流级时刻 = %v，期望最新字段时刻 %v", at, t2)
	}
	if stale {
		t.Errorf("有字段不是旧的 ⇒ 流级不该标 stale")
	}

	// ③ 一个字段都没有 ⇒ **不知道** ⇒ nil + stale（不冒充新鲜）。
	at, stale = telemetryStreamFreshness(nil)
	if at != nil {
		t.Errorf("没有字段时流级时刻应为 nil，实际 %v", at)
	}
	if !stale {
		t.Errorf("没有字段时必须标 stale（宁可说不知道）")
	}

	// ④ 字段有时刻但为 nil ⇒ 跳过它，不影响别的字段。
	at, _ = telemetryStreamFreshness([]telemetryFieldView{
		{Field: "Broken", At: nil},
		{Field: "Soc", At: t1, Stale: true},
	})
	if at == nil || !at.Equal(*t1) {
		t.Errorf("跳过 At=nil 的字段后，流级时刻 = %v，期望 %v", at, t1)
	}
}

// TestNoteCarStatus 只认更新的事件：broker 重发旧事件不许覆盖当前状态。
func TestNoteCarStatus(t *testing.T) {
	newer := tsTime(t, "2026-11-09T11:40:22Z")
	older := tsTime(t, "2026-11-09T11:28:28Z")

	// ① 第一个事件 ⇒ 记下来，算「车端新消息」。
	at, known, origin := noteCarStatus(nil, false, carStatusEvent{Status: "CONNECTED", At: newer})
	if !known || at == nil || !at.Equal(*newer) {
		t.Errorf("首个事件应被记住：known=%v at=%v", known, at)
	}
	if origin != telemetryOriginCar {
		t.Errorf("首个事件应算车端新消息，实际 %v", origin)
	}

	// ② 更旧的 retained 重放 ⇒ **不覆盖**状态，但如实归类为重放。
	at2, known2, origin2 := noteCarStatus(at, known, carStatusEvent{Status: "DISCONNECTED", At: older})
	if !known2 || at2 == nil || !at2.Equal(*newer) {
		t.Errorf("重放不许覆盖当前状态：at=%v（期望仍为 %v）", at2, newer)
	}
	if origin2 != telemetryOriginReplay {
		t.Errorf("更旧的事件应归类为重放，实际 %v", origin2)
	}

	// ③ 时刻不明 ⇒ 不知道，但**不覆盖**已知状态。
	at3, known3, origin3 := noteCarStatus(at, known, carStatusEvent{Status: "DISCONNECTED", At: nil})
	if at3 == nil || !at3.Equal(*newer) {
		t.Errorf("时刻不明的事件不许覆盖已知状态：at=%v", at3)
	}
	if known3 {
		t.Errorf("时刻不明 ⇒ 必须标「不知道」")
	}
	if origin3 != telemetryOriginUnknown {
		t.Errorf("时刻不明应归类为未知，实际 %v", origin3)
	}
}

// TestCarStatusOnline 状态词的归一化。
func TestCarStatusOnline(t *testing.T) {
	if !carStatusOnline("CONNECTED") {
		t.Errorf("CONNECTED 应算在线")
	}
	if carStatusOnline("DISCONNECTED") {
		t.Errorf("DISCONNECTED 不该算在线")
	}
	if carStatusOnline("") {
		t.Errorf("空状态不该算在线")
	}
}
