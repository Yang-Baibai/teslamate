package main

import (
	"testing"
	"time"
)

// ⚠️ **v0.83.0：轮询侧「取值合法性闸门」与车窗的口径冲突规则**。
//
// 这组用例守的是一个具体的坑：
//
// 2026-10-29 真车实测，轮询侧 `vehicle_state.rt` 出现过 **32** ——
// 它**不在** legacy 编码 {0,1,2} 里，**什么都不表示**。
// 旧代码「非 0 即开」会把它说成「后备箱开着」
// （那次碰巧与遥测一致，**纯属运气**）：
// 遥测一旦缺失，那就是一句**编出来的「开着」**。
//
// ⚠️ 另外钉死一件事：**轮询侧与遥测侧的编码不是同一套**。
//
//	轮询侧 = legacy 整数（0 关 / 1 开一条缝 / 2 开）
//	遥测侧 = 官方 `WindowState` 枚举（0=Unknown / 1=Closed /
//	         2=PartiallyOpen / 3=Opened）
//
// 官方「Available Data」表把 `FdWindow` 对到 `vehicle_state.fd_window`，
// 但它只说明「**是哪个字段**」，**没说编码相同**。
// 实测佐证：轮询四扇窗全 0，遥测同时给 "WindowStateClosed"（枚举 1）。
//
// ⇒ 跨源**只能比语义**（「开着哪几扇」），不能比原始编码值。

// rawWithDoors 造一个最小 vehicle_state。
func rawWithDoors(vals map[string]any) map[string]any {
	vs := map[string]any{"locked": true}
	for k, v := range vals {
		vs[k] = v
	}
	return map[string]any{"vehicle_state": vs}
}

// rawWithWindowFields 造一个只含车窗字段的最小 vehicle_state。
//
// ⚠️ **自己定义、不复用别的测试文件里的 helper** ——
//
//	本文件要能**独立编译**：万一哪个测试文件被 GOOS/GOARCH 规则
//	悄悄排除（这仓库真发生过：tesla_windows_test.go 因 "_windows"
//	后缀被当成 GOOS=windows 专用文件，Linux 上从来没编译过），
//	依赖别人就跟着一起消失。
func rawWithWindowFields(vals map[string]any) map[string]any {
	vs := map[string]any{"locked": true}
	for k, v := range vals {
		vs[k] = v
	}
	return map[string]any{"vehicle_state": vs}
}

func TestDoorsFromRawInvalidValueBecomesUnknown(t *testing.T) {
	// rt=32 不在 legacy 编码里 ⇒ 那个门**按「没读到」处理** ⇒
	// 其余五门都读到且全关，但没读到全部六个 ⇒ 整体「不知道」。
	got := doorsFromRaw(rawWithDoors(map[string]any{
		"df": float64(0), "dr": float64(0), "pf": float64(0),
		"pr": float64(0), "ft": float64(0), "rt": float64(32),
	}))
	if got != nil {
		t.Fatalf("rt=32 不合法 ⇒ 期望 nil（不知道），实际 %#v", got)
	}
}

func TestDoorsFromRawLegacyOpenStillWorks(t *testing.T) {
	// legacy 2 = 开 ⇒ 后备箱开着（合法的取值不能被闸门误伤）。
	got := doorsFromRaw(rawWithDoors(map[string]any{
		"df": float64(0), "dr": float64(0), "pf": float64(0),
		"pr": float64(0), "ft": float64(0), "rt": float64(2),
	}))
	if len(got) != 1 || got[0] != "后备箱" {
		t.Fatalf("期望 [后备箱]，实际 %#v", got)
	}
}

func TestDoorsFromRawAllClosedStillClosed(t *testing.T) {
	// 六个都读到且全是 0 ⇒ 确定全关（不能因为闸门变成「不知道」）。
	got := doorsFromRaw(rawWithDoors(map[string]any{
		"df": float64(0), "dr": float64(0), "pf": float64(0),
		"pr": float64(0), "ft": float64(0), "rt": float64(0),
	}))
	if got == nil {
		t.Fatal("六个门都读到且全关 ⇒ 必须是「确定全关」（空数组），不能是 nil")
	}
	if len(got) != 0 {
		t.Fatalf("期望空数组，实际 %#v", got)
	}
}

func TestWindowsFromRawInvalidValueBecomesUnknown(t *testing.T) {
	// rp_window=3 不在 legacy 编码里（3 是官方枚举里的 Opened，
	// 但轮询侧是 legacy 编码）⇒ 该字段按「没读到」⇒ 整体「不知道」。
	got := windowsFromRaw(rawWithWindowFields(map[string]any{
		"fd_window": float64(0), "fp_window": float64(0),
		"rd_window": float64(0), "rp_window": float64(3),
	}))
	if got != nil {
		t.Fatalf("rp_window=3 不合法 ⇒ 期望 nil（不知道），实际 %#v", got)
	}
}

// ⚠️ 车窗：两边**语义不一致**时以遥测为准（ADR #24），
// 即使轮询那份**更新**。
//
// 为什么不能只看时间：官方 related field 表把 FdWindow 对到
// vehicle_state.fd_window ⇒ 官方认为两者是「同一个字段」，
// 但**编码不同**（实测：轮询 0 ↔ 遥测 Closed=1）。
// ⇒ 一旦 Tesla 换编码形态，轮询侧的「1」可能是官方枚举里的「Closed」，
//
//	而旧代码会把「关着」说成「开着」。
func TestPickNewerWindowsConflictGoesToTelemetry(t *testing.T) {
	newer := time.Now()
	older := time.Now().Add(-time.Hour)

	got, src := pickNewerWindows([]string{"左前窗"}, &newer, []string{}, &older)
	if src != "telemetry" {
		t.Fatalf("口径冲突时应以遥测为准，实际 src=%s", src)
	}
	if got == nil || len(got) != 0 {
		t.Fatalf("口径冲突时应取遥测的「全关」，实际 %#v", got)
	}
}

// ⚠️ 语义**一致**时，才按采集时刻取更新的那个。
func TestPickNewerWindowsAgreeTakesNewer(t *testing.T) {
	newer := time.Now()
	older := time.Now().Add(-time.Hour)

	got, src := pickNewerWindows([]string{}, &older, []string{}, &newer)
	if src != "telemetry" {
		t.Fatalf("语义一致且遥测更新时应取遥测，实际 src=%s", src)
	}
	if got == nil || len(got) != 0 {
		t.Fatalf("期望空数组（两边都全关），实际 %#v", got)
	}
}

// ⚠️ 门与窗走**同一套**规则：冲突时遥测赢。
func TestPickNewerDoorsConflictGoesToTelemetry(t *testing.T) {
	newer := time.Now()
	older := time.Now().Add(-time.Hour)

	got, src := pickNewerDoors([]string{"后备箱"}, &newer, []string{}, &older)
	if src != "telemetry" {
		t.Fatalf("口径冲突时应以遥测为准，实际 src=%s", src)
	}
	if got == nil || len(got) != 0 {
		t.Fatalf("口径冲突时应取遥测的「全关」，实际 %#v", got)
	}
}

// ⚠️ **跨源只比语义**：实测那对「轮询 0×4 ↔ 遥测 Closed×4」
// 两边编码不同但**语义一致**（都是全关）⇒ 不算冲突。
func TestPollAndTelemetryAgreeSemanticallyDespiteDifferentEncoding(t *testing.T) {
	// 轮询侧：legacy 0 = 关。
	poll := windowsFromRaw(rawWithWindowFields(map[string]any{
		"fd_window": float64(0), "fp_window": float64(0),
		"rd_window": float64(0), "rp_window": float64(0),
	}))
	if poll == nil || len(poll) != 0 {
		t.Fatalf("轮询侧四扇窗全 0 ⇒ 应为「确定全关」，实际 %#v", poll)
	}
	// 遥测侧：官方枚举 1 = Closed ⇒ 语义同样是「全关」。
	tel := []string{}
	if !sameOpenSet(poll, tel) {
		t.Fatalf("轮询「全关」与遥测「全关」语义应一致，实际 poll=%v tel=%v", poll, tel)
	}
}
