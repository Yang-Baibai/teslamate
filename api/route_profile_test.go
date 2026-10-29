package main

import (
	"strings"
	"testing"
)

// ---------- drive_ids 解析 ----------

// TestParseDriveIDs 顺序必须原样保留，去重不能打乱顺序。
func TestParseDriveIDs(t *testing.T) {
	cases := []struct {
		name    string
		in      string
		want    []int32
		wantErr bool
	}{
		{"单个", "387", []int32{387}, false},
		{"多个保持顺序", "387,392,398", []int32{387, 392, 398}, false},
		{"顺序不能被排序打乱", "392,387,398", []int32{392, 387, 398}, false},
		{"空格容忍", " 387 , 392 ", []int32{387, 392}, false},
		{"重复去重但保序", "387,392,387,398", []int32{387, 392, 398}, false},
		{"空串报错", "", nil, true},
		{"空项报错", "387,,392", nil, true},
		{"非数字报错", "387,abc", nil, true},
		{"负数报错", "387,-5", nil, true},
		{"零报错", "0", nil, true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, err := parseDriveIDs(tc.in)
			if tc.wantErr {
				if err == nil {
					t.Fatalf("parseDriveIDs(%q) 应该报错，实际 %v", tc.in, got)
				}
				return
			}
			if err != nil {
				t.Fatalf("parseDriveIDs(%q): %v", tc.in, err)
			}
			if len(got) != len(tc.want) {
				t.Fatalf("parseDriveIDs(%q) = %v, want %v", tc.in, got, tc.want)
			}
			for i := range got {
				if got[i] != tc.want[i] {
					t.Fatalf("parseDriveIDs(%q) = %v, want %v", tc.in, got, tc.want)
				}
			}
		})
	}
}

// TestParseDriveIDsLimit 超过 400 段必须报错 —— 默默截断会让曲线缺尾巴。
func TestParseDriveIDsLimit(t *testing.T) {
	ok := make([]string, maxRouteProfileDrives)
	for i := range ok {
		ok[i] = "1"
	}
	// 去重后只有 1 段，不该报错
	if _, err := parseDriveIDs(strings.Join(ok, ",")); err != nil {
		t.Fatalf("400 段不该报错：%v", err)
	}
	tooMany := make([]string, maxRouteProfileDrives+1)
	for i := range tooMany {
		tooMany[i] = "1"
	}
	if _, err := parseDriveIDs(strings.Join(tooMany, ",")); err == nil {
		t.Fatal("401 段应该报错")
	}
}

// ---------- step_km 解析 ----------

// TestClampStepKm 越界夹到边界，解析不了用默认值 5。
func TestClampStepKm(t *testing.T) {
	cases := []struct {
		in   string
		want float64
	}{
		{"", 5},
		{"5", 5},
		{"1", 1},
		{"50", 50},
		{"2.5", 2.5},
		{"0.5", 1},   // 下限 1
		{"0", 1},     // 下限 1
		{"-3", 1},    // 下限 1
		{"100", 50},  // 上限 50
		{"abc", 5},   // 解析不了 → 默认值
		{"  5  ", 5}, // 容忍空格
	}
	for _, tc := range cases {
		if got := clampStepKm(tc.in); got != tc.want {
			t.Errorf("clampStepKm(%q) = %v, want %v", tc.in, got, tc.want)
		}
	}
}

// ---------- 数值舍入 ----------

// TestRound2 里程要舍到两位小数，误差不超过 0.005 公里。
func TestRound2(t *testing.T) {
	cases := []struct {
		in   float64
		want float64
	}{
		{98176.421376, 98176.42},
		{98246.699666, 98246.7},
		{70.27829000000202, 70.28},
		{83.04215, 83.04},
		{0, 0},
		{-1.005, -1.0}, // 负数也要对称
	}
	for _, tc := range cases {
		if got := round2(tc.in); got != tc.want {
			t.Errorf("round2(%v) = %v, want %v", tc.in, got, tc.want)
		}
	}
}
