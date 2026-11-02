package main

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

// ⚠️ **v0.111.0**：充电简报（给飞书的周期性报告）的护栏。
//
// 三条规矩：
//
//  ① **文案只有服务端一份** —— 推送脚本与 MCP 只转述（ADR #41）。
//  ② **窗口必须露出来** —— 只说「近 7 天」不说边界，用户没法核对「是哪 7 天」。
//  ③ **没有样本就不给数** —— 平均时长没样本时，正文里不许出现「平均」。
//
// ⚠️ 所有断言都**钉具体值**（不钉「有没有超过 N」）——
// 边界算错时，「有没有超过 N」这种断言会假绿（v0.98.0 的教训）。

// briefOf 渲染一个区间的简报。
func briefOf(t *testing.T, key string) (string, string, *ChargeStatsRange) {
	t.Helper()
	loc := testLoc(t)
	now := fixedNow(t)
	ranges := buildChargeStats(loc, now, fixedRows(t))
	r := rangeByKey(ranges, key)
	if r == nil {
		t.Fatalf("区间 %s 缺失", key)
	}
	title, body := renderChargeBrief(loc, "Asia/Shanghai", "爱车", r)
	return title, body, r
}

// ① 标题与正文：**窗口两端都在**（钉具体时刻）。
func TestChargeBriefWindowPinned(t *testing.T) {
	title, body, _ := briefOf(t, "7d")

	if !strings.Contains(title, "爱车") {
		t.Errorf("标题里没有车名：%q", title)
	}
	if !strings.Contains(title, "近 7 天") {
		t.Errorf("标题里没有区间标签：%q", title)
	}
	// 窗口起点 = 2026-10-27 00:00（+08:00）；终点 = now = 2026-11-02 15:44
	if !strings.Contains(body, "2026-10-27 00:00") {
		t.Errorf("正文里没有窗口起点 2026-10-27 00:00：\n%s", body)
	}
	if !strings.Contains(body, "2026-11-02 15:44") {
		t.Errorf("正文里没有窗口终点 2026-11-02 15:44：\n%s", body)
	}
	if !strings.Contains(body, "Asia/Shanghai") {
		t.Errorf("正文里没有写服务器时区（用户对时间会差几小时）：\n%s", body)
	}
}

// ② 正文里的**数**必须与聚合一致（钉死具体字符串）。
func TestChargeBriefBodyNumbersPinned(t *testing.T) {
	_, body, r := briefOf(t, "7d")

	want := []string{
		"充电 2 次 · 充入 37.8 度",
		"直流（快充）：2 次 · 37.8 度 · 平均 35 分钟",
		"交流（慢充）：0 次 · 0.0 度",
		"上次充满：2026-11-02 13:17（80% → 100%）",
	}
	for _, w := range want {
		if !strings.Contains(body, w) {
			t.Errorf("正文里没有 %q：\n%s", w, body)
		}
	}
	// ⚠️ 交流平均时长**没有样本** ⇒ 正文里不许出现交流的「平均」。
	if strings.Contains(body, "交流（慢充）：0 次 · 0.0 度 · 平均") {
		t.Errorf("交流没有样本却给了平均时长：\n%s", body)
	}
	// 数字必须与区间聚合一致（同一事实只留一处 ⇒ 简报不能另算）。
	if r.TotalKwh != 37.8 {
		t.Fatalf("区间聚合变了（期望 37.8，实际 %v）⇒ 简报的数也要跟着变", r.TotalKwh)
	}
}

// ③ **全部历史**这一档：窗口是数据自身的首尾（不是滚动窗口）。
func TestChargeBriefAllWindowIsDataSpan(t *testing.T) {
	_, body, _ := briefOf(t, "all")
	if !strings.Contains(body, "2026-08-15 07:00") {
		t.Errorf("「全部」的窗口起点应是数据首场 2026-08-15 07:00：\n%s", body)
	}
	if !strings.Contains(body, "2026-11-02 13:17") {
		t.Errorf("「全部」的窗口终点应是数据末场 2026-11-02 13:17：\n%s", body)
	}
	if !strings.Contains(body, "充电 5 次 · 充入 97.8 度") {
		t.Errorf("「全部」应是 5 次 / 97.8 度：\n%s", body)
	}
}

// ④ 没有样本 ⇒ **不给数**（不编）。
func TestChargeBriefNoSampleGivesNoNumber(t *testing.T) {
	loc := testLoc(t)
	now := fixedNow(t)
	// 窗口内一场都没有（数据在 10-01，落在 7 天窗口之外）。
	ranges := buildChargeStats(loc, now, []chargeStatRow{
		{StartDate: at(2026, time.October, 1, 8, 0), EnergyAdded: 30.0, DurationMin: intp(40), StartSoc: intp(20), EndSoc: intp(60), IsDC: true},
	})
	r := rangeByKey(ranges, "7d")
	if r == nil {
		t.Fatal("区间 7d 缺失")
	}
	_, body := renderChargeBrief(loc, "Asia/Shanghai", "爱车", r)
	if !strings.Contains(body, "这个区间没有充电记录") {
		t.Errorf("没有样本时正文应明说「没有充电记录」：\n%s", body)
	}
	if strings.Contains(body, "度") {
		t.Errorf("没有样本却报了度数：\n%s", body)
	}
	if strings.Contains(body, "平均") {
		t.Errorf("没有样本却报了平均时长：\n%s", body)
	}
}

// ⑤ **线上契约**：JSON 必须真的带上简报要的键（少一个 ⇒ 客户端静默拿到空串）。
func TestChargeBriefJSONContract(t *testing.T) {
	loc := testLoc(t)
	now := fixedNow(t)
	ranges := buildChargeStats(loc, now, fixedRows(t))
	r := rangeByKey(ranges, "7d")
	title, body := renderChargeBrief(loc, "Asia/Shanghai", "爱车", r)
	payload := ChargeBrief{
		CarName:          "爱车",
		PeriodLabel:      chargeBriefPeriodLabel["7d"],
		ServerTimezone:   "Asia/Shanghai",
		Title:            title,
		Body:             body,
		ChargeStatsRange: *r,
	}
	b, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("序列化失败: %v", err)
	}
	var m map[string]any
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatalf("反序列化失败: %v", err)
	}
	for _, k := range []string{
		"car_name", "period_label", "server_timezone", "title", "body",
		"key", "days", "window_start", "window_end", "count", "total_kwh",
		"dc_kwh", "dc_count", "dc_avg_min", "ac_kwh", "ac_count", "ac_avg_min",
		"last_full",
	} {
		if _, ok := m[k]; !ok {
			t.Errorf("简报缺键 %q", k)
		}
	}
	if m["title"] != title || m["body"] != body {
		t.Error("JSON 里的 title/body 与渲染结果不一致")
	}
}
