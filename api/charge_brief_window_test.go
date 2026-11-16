package main

import (
	"testing"
	"time"
)

// ⚠️ **v0.165.0**：简报的能耗窗口必须与**用量页**同一个窗口（否则对不上账）。

// TestBriefEnergyWindowMatchesStatsWindow 简报借来的窗口起点
// 必须换算到**本地时区**再分桶（否则按 UTC 加天数 ⇒ 今天的行程整段漏掉）。
func TestBriefEnergyWindowMatchesStatsWindow(t *testing.T) {
	loc, err := time.LoadLocation("Asia/Shanghai")
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 11, 16, 12, 0, 0, 0, loc)
	// 充电区间的 7d 窗口起点（库里按 UTC 存 ⇒ 取出来是 UTC 时刻）。
	ws := dayWindowStart(loc, now, 7).UTC()

	got := briefEnergyWindowStart(&ws, loc)
	want := dayWindowStart(loc, now, 7)
	if !got.Equal(want) {
		t.Fatalf("简报的窗口起点 = %s，期望 %s（两者必须同一个窗口）",
			got.Format("2006-01-02 15:04:05 -07:00"), want.Format("2006-01-02 15:04:05 -07:00"))
	}

	// 7 个桶（本地日期）必须**包含今天** —— 不包含就说明今天的行程被漏掉了。
	seen := false
	for i := 0; i < 7; i++ {
		if got.AddDate(0, 0, i).Format("2006-01-02") == "2026-11-16" {
			seen = true
			break
		}
	}
	if !seen {
		t.Fatalf("7 个桶里没有今天（2026-11-16）⇒ 今天的行程会被整段漏掉")
	}
}

// TestBriefEnergyWindowNil nil ⇒ 零值（不许 panic）。
func TestBriefEnergyWindowNil(t *testing.T) {
	loc, _ := time.LoadLocation("Asia/Shanghai")
	if got := briefEnergyWindowStart(nil, loc); !got.IsZero() {
		t.Fatalf("nil 窗口起点应给零值，得到 %s", got)
	}
}
