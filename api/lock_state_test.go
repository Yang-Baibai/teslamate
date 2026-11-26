package main

import (
	"testing"
	"time"
)

// ⚠️ **v0.193.0 / v0.194.0（用户反馈 #226）：持续状态族（锁车 / 车窗）的双源取舍与 stale。**
//
// 守的是**这次真实误报**的形状：
// 遥测 Locked=false（07:04，断线前 broker 重发的 retained 旧值）
// 压过轮询 locked=true（07:14，新 9 分钟）
// ⇒ 推送侧拿到陈旧的 false ⇒ 未锁车计时器持续走 ⇒ 30 分钟后必推「已解锁超过 30 分钟」。
//
// ⚠️ **v0.194.0**：车窗是这一族里唯一没有 stale 标志的 ⇒ 同样的假告警风险。

func lockT_At(h int, m int) *time.Time {
	t := time.Date(2026, 11, 26, 7, h, m, 0, time.UTC)
	return &t
}

// 本次实测那组数：遥测旧（说没锁）＋ 轮询新（说锁了）⇒ **取更新的那个**。
func TestPickNewerLockedTakesNewerTimestamp(t *testing.T) {
	poll := true
	tel := false
	locked, at, source := pickNewerLocked(&poll, lockT_At(14, 9), &tel, lockT_At(4, 58))
	if locked == nil || *locked != true {
		t.Fatalf("应取轮询的 true（07:14 比 07:04 新），实际 %v", locked)
	}
	if source != "poll" {
		t.Errorf("来源应是 poll，实际 %q", source)
	}
	if at == nil || !at.Equal(*lockT_At(14, 9)) {
		t.Errorf("采集时刻应跟着被选中的那个（轮询 07:14），实际 %v", at)
	}
}

// 遥测更新 ⇒ 取遥测。
func TestPickNewerLockedTelemetryWinsWhenNewer(t *testing.T) {
	poll := false
	tel := true
	locked, _, source := pickNewerLocked(&poll, lockT_At(4, 58), &tel, lockT_At(14, 9))
	if locked == nil || !*locked {
		t.Fatalf("应取遥测的 true，实际 %v", locked)
	}
	if source != "telemetry" {
		t.Errorf("来源应是 telemetry，实际 %q", source)
	}
}

// 三态：只有一边 / 都没有。
func TestPickNewerLockedThreeStates(t *testing.T) {
	poll := true
	tel := false
	// 只有轮询
	locked, _, source := pickNewerLocked(&poll, lockT_At(14, 9), nil, nil)
	if locked == nil || !*locked || source != "poll" {
		t.Errorf("只有轮询时应取轮询，实际 locked=%v source=%q", locked, source)
	}
	// 只有遥测
	locked, _, source = pickNewerLocked(nil, nil, &tel, lockT_At(4, 58))
	if locked == nil || *locked || source != "telemetry" {
		t.Errorf("只有遥测时应取遥测，实际 locked=%v source=%q", locked, source)
	}
	// 两边都没有 ⇒ 不知道（**不是「没锁」**）
	locked, at, source := pickNewerLocked(nil, nil, nil, nil)
	if locked != nil || at != nil || source != "unknown" {
		t.Errorf("两边都没有时应是 unknown，实际 locked=%v at=%v source=%q", locked, at, source)
	}
}

// 两边**语义一致**时也要取更新的那个（不能因为一致就固定用遥测）。
func TestPickNewerLockedSameSemanticsTakesNewer(t *testing.T) {
	poll := true
	tel := true
	_, _, source := pickNewerLocked(&poll, lockT_At(14, 9), &tel, lockT_At(4, 58))
	if source != "poll" {
		t.Errorf("语义一致时也应取更新的那个（轮询 07:14），实际来源 %q", source)
	}
}

// **持续状态族的 stale 统一规矩**（锁车与车窗共用，服务端唯一一份）。
//
// ⚠️ 「**时刻可信**」≠「**值是新的**」——
// 车离线时遥测给的是断线前的最后已知值 ⇒ 拿它当「现在没锁 / 现在窗开着」的事实
// ⇒ 推送侧会推出**假告警**（实测：车已锁好，却收到「已解锁超过 30 分钟」）。
func TestContinuousStateStale(t *testing.T) {
	cases := []struct {
		name      string
		source    string
		freshness bool
		carOnline bool
		wantStale bool
	}{
		{"遥测 + 车离线 ⇒ 旧值", "telemetry", false, false, true},
		{"遥测 + 车在线 ⇒ 新值", "telemetry", false, true, false},
		{"轮询 + 车离线 ⇒ 不是旧值（它不是 retained）", "poll", false, false, false},
		{"轮询 + 车在线 ⇒ 新值", "poll", false, true, false},
		{"通用判据说 stale ⇒ 旧值", "telemetry", true, true, true},
		{"没有来源 ⇒ 不知道（不是旧值）", "", false, false, false},
		{"来源未知 ⇒ 不知道", "unknown", false, false, false},
	}
	for _, c := range cases {
		got := continuousStateStale(c.source, c.freshness, c.carOnline)
		if got != c.wantStale {
			t.Errorf("[%s] stale 应为 %v，实际 %v", c.name, c.wantStale, got)
		}
	}
}
