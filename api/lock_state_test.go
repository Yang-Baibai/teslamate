package main

import (
	"testing"
	"time"
)

// ⚠️ **v0.193.0（用户反馈 #226）：锁车状态的双源取舍。**
//
// 守的是**这次真实误报**的形状：
// 遥测 Locked=false（07:04，断线前 broker 重发的 retained 旧值）
// 压过轮询 locked=true（07:14，新 9 分钟）
// ⇒ 推送侧拿到陈旧的 false ⇒ 未锁车计时器持续走 ⇒ 30 分钟后必推「已解锁超过 30 分钟」。

func lockT_At(h int, m int) *time.Time {
	t := time.Date(2026, 11, 26, 7, h, m, 0, time.UTC)
	return &t
}

// 本次实测那组数：遥测旧（说没锁）＋ 轮询新（说锁了）⇒ **取更新的那个**。
func TestPickNewerLockedTakesNewerTimestamp(t *testing.T) {
	poll := true
	tel := false
	locked, at, source, stale := pickNewerLocked(&poll, lockT_At(14, 9), &tel, lockT_At(4, 58), true)
	if locked == nil || *locked != true {
		t.Fatalf("应取轮询的 true（07:14 比 07:04 新），实际 %v", locked)
	}
	if source != "poll" {
		t.Errorf("来源应是 poll，实际 %q", source)
	}
	if stale {
		t.Error("选了轮询的值 ⇒ 它不是「断线前的旧值」⇒ stale 应为 false")
	}
	if at == nil || !at.Equal(*lockT_At(14, 9)) {
		t.Errorf("采集时刻应跟着被选中的那个（轮询 07:14），实际 %v", at)
	}
}

// 遥测更新 ⇒ 取遥测，**并把它的 stale 原样带出**（断线前的旧值要如实标）。
func TestPickNewerLockedTelemetryWinsWhenNewer(t *testing.T) {
	poll := false
	tel := true
	locked, _, source, stale := pickNewerLocked(&poll, lockT_At(4, 58), &tel, lockT_At(14, 9), true)
	if locked == nil || !*locked {
		t.Fatalf("应取遥测的 true，实际 %v", locked)
	}
	if source != "telemetry" {
		t.Errorf("来源应是 telemetry，实际 %q", source)
	}
	if !stale {
		t.Error("遥测的 stale 必须原样带出（调用方据此不拿它当事实）")
	}
}

// 三态：只有一边 / 都没有。
func TestPickNewerLockedThreeStates(t *testing.T) {
	poll := true
	tel := false
	// 只有轮询
	locked, at, source, stale := pickNewerLocked(&poll, lockT_At(14, 9), nil, nil, false)
	if locked == nil || !*locked || source != "poll" || stale {
		t.Errorf("只有轮询时应取轮询，实际 locked=%v source=%q stale=%v", locked, source, stale)
	}
	// 只有遥测
	locked, at, source, stale = pickNewerLocked(nil, nil, &tel, lockT_At(4, 58), true)
	if locked == nil || *locked {
		t.Errorf("只有遥测时应取遥测，实际 %v", locked)
	}
	if source != "telemetry" || !stale {
		t.Errorf("只有遥测时来源/stale 应如实带出，实际 source=%q stale=%v", source, stale)
	}
	// 两边都没有 ⇒ 不知道（**不是「没锁」**）
	locked, at, source, _ = pickNewerLocked(nil, nil, nil, nil, false)
	if locked != nil || at != nil || source != "unknown" {
		t.Errorf("两边都没有时应是 unknown，实际 locked=%v at=%v source=%q", locked, at, source)
	}
}

// 两边**语义一致**时也要取更新的那个（不能因为一致就固定用遥测）。
func TestPickNewerLockedSameSemanticsTakesNewer(t *testing.T) {
	poll := true
	tel := true
	_, _, source, _ := pickNewerLocked(&poll, lockT_At(14, 9), &tel, lockT_At(4, 58), true)
	if source != "poll" {
		t.Errorf("语义一致时也应取更新的那个（轮询 07:14），实际来源 %q", source)
	}
}

// 锁车的 stale：「**时刻可信**」≠「**值是新的**」。
//
// ⚠ 实测（用户反馈 #226）：车离线时遥测给的是「断线前的最后已知值」
// ⇒ 通用判据说「时刻可信」⇒ stale=false ⇒ 推送侧把它当事实 ⇒ 假告警。
func TestLockStaleOffline(t *testing.T) {
	if !lockStaleOffline(false, false) {
		t.Error("车离线时锁车状态必须标 stale（断线前的值可能已经变了）")
	}
	if lockStaleOffline(false, true) {
		t.Error("车在线时不该标 stale")
	}
	if !lockStaleOffline(true, true) {
		t.Error("通用判据已说 stale（时刻不可信）⇒ 仍然 stale")
	}
}
