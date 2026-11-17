package main

import "testing"

// ⚠️ **v0.168.0**：哨兵警报的判据 —— 只有官方枚举里的 panic 算「警报触发」。
func TestEvaluateSentryAlert(t *testing.T) {
	if !EvaluateSentryAlert(sentryStatePanic) {
		t.Fatal("panic（警报触发）必须判为告警")
	}
	for _, st := range []string{
		sentryStateOff, sentryStateIdle, sentryStateArmed, sentryStateAware,
		sentryStateQuiet, sentryStateOn, sentryStateUnsupported, sentryStateUnknown, "",
	} {
		if EvaluateSentryAlert(st) {
			t.Fatalf("状态 %q 不是警报触发 ⇒ 不该报", st)
		}
	}
}
