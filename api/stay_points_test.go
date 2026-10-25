package main

import (
	"math"
	"testing"
)

// ---------- min_hours 参数解析 ----------

// TestParseStayPointsMinHours 越界必须报错 —— 默默截断会让 App 以为
// 「就这么多数据了」，而真实原因只是被砍掉了。
func TestParseStayPointsMinHours(t *testing.T) {
	cases := []struct {
		name    string
		in      string
		want    float64
		wantErr bool
	}{
		{"空串用默认值", "", 3, false},
		{"空白用默认值", "   ", 3, false},
		{"正常值", "6", 6, false},
		{"小数", "2.5", 2.5, false},
		{"下限 0.5", "0.5", 0.5, false},
		{"上限 24", "24", 24, false},
		{"低于下限报错", "0.4", 0, true},
		{"零报错", "0", 0, true},
		{"负数报错", "-3", 0, true},
		{"超过上限报错", "24.5", 0, true},
		{"远远超过上限报错", "9999", 0, true},
		{"非数字报错", "abc", 0, true},
		{"NaN 报错", "NaN", 0, true},
		{"Inf 报错", "Inf", 0, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, err := parseStayPointsMinHours(tc.in)
			if tc.wantErr {
				if err == nil {
					t.Fatalf("parseStayPointsMinHours(%q) 应该报错，实际 %v", tc.in, got)
				}
				return
			}
			if err != nil {
				t.Fatalf("parseStayPointsMinHours(%q): %v", tc.in, err)
			}
			if math.Abs(got-tc.want) > 1e-9 {
				t.Fatalf("parseStayPointsMinHours(%q) = %v, want %v", tc.in, got, tc.want)
			}
		})
	}
}

// ---------- 口径常量 ----------

// TestStayPointsThresholds 阈值必须与注释里承诺的一致。
//
// ⚠️ 这些数是对外承诺的行为（App 按这个范围做输入框校验），
// 改了却不改测试 = 悄悄改了契约。
func TestStayPointsThresholds(t *testing.T) {
	if stayPointsDefaultMinHours != 3 {
		t.Errorf("默认 min_hours = %v, want 3", stayPointsDefaultMinHours)
	}
	if stayPointsMinHours != 0.5 {
		t.Errorf("min_hours 下限 = %v, want 0.5", stayPointsMinHours)
	}
	if stayPointsMaxHours != 24 {
		t.Errorf("min_hours 上限 = %v, want 24", stayPointsMaxHours)
	}
	if stayPointsLimit != 12 {
		t.Errorf("常驻点上限 = %d, want 12", stayPointsLimit)
	}
}
