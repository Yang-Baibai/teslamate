package main

import (
	"encoding/json"
	"strings"
	"testing"
)

// ⚠️ **v0.111.0**：充电简报（给飞书的周期性报告）的护栏。
//
// 四条规矩：
//
//  ① **文案只有服务端一份** —— 推送脚本与 MCP 只转述（ADR #41）。
//  ② **窗口必须露出来** —— 只说「近 30 天」不说边界，用户没法核对「是哪 30 天」。
//  ③ **没有样本就不给数** —— 平均时长/平均能耗没样本时，正文里不许出现那一段。
//  ④ **v0.115.1**：能耗段与充电段**共用同一个窗口** ⇒ 全篇只有一行窗口。
//
// ⚠️ 所有断言都**钉具体值**（不钉「有没有超过 N」）——
// 边界算错时，「有没有超过 N」这种断言会假绿（v0.98.0 的教训）。
//
// ⚠️ 辅助函数（testZone / at / intp / fixedNow / fixedRows / rangeByKey）
// **复用 charge_stats_test.go 的那一份** —— 同一个包里不许写两份（编译冲突）。

// briefFloatp 一个可空浮点数。
func briefFloatp(v float64) *float64 { return &v }

// briefAbs 取绝对值。
func briefAbs(v float64) float64 {
	if v < 0 {
		return -v
	}
	return v
}

// briefOf 渲染一个区间的简报（**不带能耗段**）。
func briefOf(t *testing.T, key string) (string, string, *ChargeStatsRange) {
	t.Helper()
	loc := testLoc(t)
	now := fixedNow(t)
	ranges := buildChargeStats(loc, now, fixedRows(t))
	r := rangeByKey(ranges, key)
	if r == nil {
		t.Fatalf("区间 %s 缺失", key)
	}
	title, body := renderChargeBrief(loc, "Asia/Shanghai", "爱车", r, nil, "")
	return title, body, r
}

// ① 窗口两端都在正文里（钉具体时刻）。
func TestChargeBriefWindowPinned(t *testing.T) {
	_, body, _ := briefOf(t, "7d")
	if !strings.Contains(body, "2026-10-27 00:00") {
		t.Errorf("正文里没有窗口起点 2026-10-27 00:00：\n%s", body)
	}
	if !strings.Contains(body, "2026-11-02 15:44") {
		t.Errorf("正文里没有窗口终点 2026-11-02 15:44：\n%s", body)
	}
	if !strings.Contains(body, "Asia/Shanghai") {
		t.Errorf("正文里没有写服务器时区：\n%s", body)
	}
}

// ② 正文的**数**钉死（与服务端聚合同源）。
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
	if r.TotalKwh != 37.8 {
		t.Fatalf("区间聚合变了（期望 37.8，实际 %v）", r.TotalKwh)
	}
}

// ③ 没有样本就不给数（交流没有平均时长 ⇒ 正文里不出现交流的「平均」）。
func TestChargeBriefNoSampleGivesNoNumber(t *testing.T) {
	_, body, _ := briefOf(t, "7d")
	if strings.Contains(body, "交流（慢充）：0 次 · 0.0 度 · 平均") {
		t.Errorf("交流没有样本却给了平均时长：\n%s", body)
	}
}

// ④ 「全部历史」这一档：窗口 = 数据自身首尾。
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

// ── v0.115.1：能耗段 ──────────────────────────────────────────────

// ⑤ 能耗段与充电段**共用同一个窗口** ⇒ 全篇只有**一行**窗口。
//
// ⚠️ 这是本轮最要紧的结构事实：一段话里两个窗口 ⇒ 用户对不上账（ADR #38）。
func TestChargeBriefEnergySharesOneWindow(t *testing.T) {
	loc := testLoc(t)
	now := fixedNow(t)
	ranges := buildChargeStats(loc, now, fixedRows(t))
	r := rangeByKey(ranges, "7d")
	energy := &ChargeBriefEnergy{Km: 1234.5, Minutes: 1800, KwhUsed: 168.2, WhPerKm: briefFloatp(136.2), KwhPer100Km: briefFloatp(13.62), DriveCount: 12}
	_, body := renderChargeBrief(loc, "Asia/Shanghai", "爱车", r, energy, "")
	if n := strings.Count(body, "窗口："); n != 1 {
		t.Errorf("正文里有 %d 行窗口（应恰好 1 行）⇒ 能耗段与充电段不是同一个窗口：\n%s", n, body)
	}
}

// ⑥ 能耗段的数钉死（单位换算：Wh/km ÷ 10 = kWh/100km）。
func TestChargeBriefEnergyNumbersPinned(t *testing.T) {
	loc := testLoc(t)
	now := fixedNow(t)
	ranges := buildChargeStats(loc, now, fixedRows(t))
	r := rangeByKey(ranges, "7d")
	energy := &ChargeBriefEnergy{Km: 1234.5, Minutes: 1800, KwhUsed: 168.2, WhPerKm: briefFloatp(136.2), KwhPer100Km: briefFloatp(13.62), DriveCount: 12}
	_, body := renderChargeBrief(loc, "Asia/Shanghai", "爱车", r, energy, "")
	want := "能耗：行驶 1234.5 公里 · 用电 168.2 度 · 平均 13.6 kWh/100km"
	if !strings.Contains(body, want) {
		t.Errorf("正文里没有 %q：\n%s", want, body)
	}
}

// ⑦ 「全部历史」⇒ **不并能耗段**，且正文只说**一句话**。
func TestChargeBriefAllHasNoEnergy(t *testing.T) {
	loc := testLoc(t)
	now := fixedNow(t)
	ranges := buildChargeStats(loc, now, fixedRows(t))
	r := rangeByKey(ranges, "all")
	// ⚠️ 「全部」这一档**有** WindowStart（= 数据首尾，不是 nil）——
	// 「不并能耗段」的判据是**桶数 = 0**（它不是日历窗口）。
	spec, ok := chargeRangeSpecByKey("all")
	if !ok {
		t.Fatal("找不到 all 区间定义")
	}
	if n := spec.bucketCount(loc, now); n != 0 {
		t.Fatalf("「全部」的桶数应是 0（没有日历窗口），实际 %d", n)
	}
	_, body := renderChargeBrief(loc, "Asia/Shanghai", "爱车", r, nil, "这一档不并能耗段")
	if !strings.Contains(body, "这一档不并能耗段") {
		t.Errorf("正文里没有说清「这一档不并能耗段」：\n%s", body)
	}
	if strings.Contains(body, "能耗：") {
		t.Errorf("「全部」这一档不该有能耗段：\n%s", body)
	}
}

// ⑧ 没有里程 ⇒ **不给数**（反幻觉：宁可说没有，不编一个平均能耗）。
func TestChargeBriefEnergyFromSummaryNoKm(t *testing.T) {
	if e := chargeBriefEnergyFromSummary(&StatsBucket{Km: 0.05}); e != nil {
		t.Errorf("里程不足 0.1km 时不该给出能耗段：%+v", e)
	}
	if e := chargeBriefEnergyFromSummary(nil); e != nil {
		t.Errorf("没有汇总时不该给出能耗段：%+v", e)
	}
}

// ⑨ 单位换算钉死：Wh/km ÷ 10 = kWh/100km（界面上给用户看的那个口径）。
func TestChargeBriefEnergyUnitConversion(t *testing.T) {
	e := chargeBriefEnergyFromSummary(&StatsBucket{
		Km: 100, KwhUsed: 13.62, WhPerKm: briefFloatp(136.2), DriveCount: 3,
	})
	if e == nil {
		t.Fatal("有里程却没有能耗段")
	}
	if e.KwhPer100Km == nil {
		t.Fatal("没有 kWh/100km")
	}
	if briefAbs(*e.KwhPer100Km-13.62) > 0.001 {
		t.Errorf("kWh/100km = %v，期望 13.62（Wh/km ÷ 10）", *e.KwhPer100Km)
	}
	if e.WhPerKm == nil || briefAbs(*e.WhPerKm-136.2) > 0.001 {
		t.Errorf("Wh/km = %v，期望 136.2", e.WhPerKm)
	}
}

// ⑩ JSON 契约：键齐全（少一个 ⇒ 客户端静默拿到空值）。
func TestChargeBriefJSONContract(t *testing.T) {
	loc := testLoc(t)
	now := fixedNow(t)
	ranges := buildChargeStats(loc, now, fixedRows(t))
	r := rangeByKey(ranges, "7d")
	energy := &ChargeBriefEnergy{Km: 1234.5, KwhUsed: 168.2, WhPerKm: briefFloatp(136.2), KwhPer100Km: briefFloatp(13.62), DriveCount: 12}
	title, body := renderChargeBrief(loc, "Asia/Shanghai", "爱车", r, energy, "")
	payload := ChargeBrief{
		CarName:          "爱车",
		PeriodLabel:      chargeBriefPeriodLabel["7d"],
		ServerTimezone:   "Asia/Shanghai",
		Title:            title,
		Body:             body,
		ChargeStatsRange: *r,
		Energy:           energy,
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
		"last_full", "energy",
	} {
		if _, ok := m[k]; !ok {
			t.Errorf("简报缺键 %q", k)
		}
	}
	en, ok := m["energy"].(map[string]any)
	if !ok {
		t.Fatalf("energy 不是对象：%v", m["energy"])
	}
	for _, k := range []string{"km", "minutes", "kwh_used", "wh_per_km", "kwh_per_100km", "drive_count"} {
		if _, ok := en[k]; !ok {
			t.Errorf("energy 缺键 %q", k)
		}
	}
}
