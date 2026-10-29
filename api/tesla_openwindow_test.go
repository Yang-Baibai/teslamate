package main

import (
	"testing"
)

// ⚠️ **v0.77.0：车窗开合的解析与三态语义**。
//
// 这组用例守的是推送引擎「车窗未关超时提醒」的**前提**：
// 脚本必须能分清三件事 ——「开着」「全关着」「不知道」。
//
// 为什么要分三态：
// 「读不到」≠「关上了」。压成空数组的话，脚本的计时器会把
// 「这会儿没取到数据」误判成「用户刚把窗关上」，
// 于是「开着 29 分钟」的提醒在第 30 分钟凭空消失 ——
// 而车窗其实一直开着。这是最阴的一类静默失效。

// rawWithWindows 造一个最小 vehicle_state。
func rawWithWindows(vals map[string]any) map[string]any {
	vs := map[string]any{"locked": true}
	for k, v := range vals {
		vs[k] = v
	}
	return map[string]any{"vehicle_state": vs}
}

func TestWindowsFromRawUnknown(t *testing.T) {
	cases := []struct {
		name string
		raw  map[string]any
	}{
		{"没有原始数据", nil},
		{"没有 vehicle_state", map[string]any{"charge_state": map[string]any{}}},
		{"vehicle_state 不是对象", map[string]any{"vehicle_state": "x"}},
		{"一个车窗字段都没有", rawWithWindows(map[string]any{"locked": true})},
		{"只读到一部分字段且都是关", rawWithWindows(map[string]any{
			"fd_window": float64(0), "fp_window": float64(0)})},
	}
	for _, c := range cases {
		got := windowsFromRaw(c.raw)
		if got != nil {
			t.Errorf("%s：期望 nil（不知道），实际 %#v", c.name, got)
		}
	}
}

func TestWindowsFromRawAllClosed(t *testing.T) {
	got := windowsFromRaw(rawWithWindows(map[string]any{
		"fd_window": float64(0), "fp_window": float64(0),
		"rd_window": float64(0), "rp_window": float64(0),
	}))
	if got == nil {
		t.Fatal("四个车窗都读到且全关 ⇒ 必须是「确定全关」（空数组），不能是 nil")
	}
	if len(got) != 0 {
		t.Fatalf("期望空数组，实际 %#v", got)
	}
}

func TestWindowsFromRawOpen(t *testing.T) {
	got := windowsFromRaw(rawWithWindows(map[string]any{
		"fd_window": float64(1), "fp_window": float64(0),
		"rd_window": float64(0), "rp_window": float64(1),
	}))
	if len(got) != 2 || got[0] != "左前窗" || got[1] != "右后窗" {
		t.Fatalf("期望 [左前窗 右后窗]，实际 %#v", got)
	}
}

func TestWindowsFromRawBoolForm(t *testing.T) {
	// 全球区有的版本给的是布尔，不是 0/1。
	got := windowsFromRaw(rawWithWindows(map[string]any{
		"fd_window": false, "fp_window": true,
		"rd_window": false, "rp_window": false,
	}))
	if len(got) != 1 || got[0] != "右前窗" {
		t.Fatalf("期望 [右前窗]，实际 %#v", got)
	}
}

func TestWindowsFromRawPartialButOpen(t *testing.T) {
	// 只读到一部分字段，但**读到开着的那扇** ⇒ 仍然是确定事实。
	got := windowsFromRaw(rawWithWindows(map[string]any{
		"fd_window": float64(1),
	}))
	if len(got) != 1 || got[0] != "左前窗" {
		t.Fatalf("期望 [左前窗]，实际 %#v", got)
	}
}

// ⚠️ **v0.83.0**：取值**合法性**与「开/关」是两个维度。
//
// 旧用例只验了「非 0 即开」，没验「**取值不在编码里**该怎么办」——
// 于是 `rt = 32`（真车实测出现过）会被说成「后备箱开着」。
func TestPollOpenFlagCheckedValid(t *testing.T) {
	open := []any{true, float64(1), float64(2), int(1), "1", "true", "OPEN"}
	for _, v := range open {
		got, valid := pollOpenFlagChecked(v)
		if !valid {
			t.Errorf("%#v 应当是合法取值", v)
			continue
		}
		if !got {
			t.Errorf("%#v 应当算「开着」", v)
		}
	}
	closed := []any{false, float64(0), int(0), "0", "false"}
	for _, v := range closed {
		got, valid := pollOpenFlagChecked(v)
		if !valid {
			t.Errorf("%#v 应当是合法取值", v)
			continue
		}
		if got {
			t.Errorf("%#v 应当算「关着」", v)
		}
	}
}

// ⚠️ 这些值**不在** legacy 编码 {0,1,2} 里 ⇒ 必须判为「无效」。
//
// ⚠️ 特别地：**空串与 nil 不等于「关」** —— 它们是「没读到」，
// 把「没读到」说成「全关」是假安全。
func TestPollOpenFlagCheckedInvalid(t *testing.T) {
	for _, v := range []any{
		float64(32), // 真车实测出现过的 rt
		float64(3),  // 官方枚举里是 Opened，但 legacy 里不存在
		float64(-1),
		int(99),
		nil,
		"",
		"unknown",
	} {
		if _, valid := pollOpenFlagChecked(v); valid {
			t.Errorf("%#v 应当判为无效取值（= 没读到），不能当成「关」", v)
		}
	}
}
