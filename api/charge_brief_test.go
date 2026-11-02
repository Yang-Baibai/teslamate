package main

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
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
	title, body := renderChargeBrief(loc, "Asia/Shanghai", "爱车", r, nil)
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
	_, body := renderChargeBrief(loc, "Asia/Shanghai", "爱车", r, &ChargeBriefSegments{Energy: energy})
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
	_, body := renderChargeBrief(loc, "Asia/Shanghai", "爱车", r, &ChargeBriefSegments{Energy: energy})
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
	_, body := renderChargeBrief(loc, "Asia/Shanghai", "爱车", r, &ChargeBriefSegments{EnergyNote: "这一档不并能耗段"})
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
	title, body := renderChargeBrief(loc, "Asia/Shanghai", "爱车", r, &ChargeBriefSegments{Energy: energy})
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
		"last_full", "energy", "battery", "battery_note",
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

// ── v0.116.0 补记：电池健康段 ────────────────────────────────────
//
// ⚠️ 这一段最要紧的是**两个口径必须分开**：
//
//	· 「当前 SOH%」—— 窗口**无关**（最近一次满充实测 ÷ 出厂标称容量）；
//	· 「窗口内满充实测趋势」—— 窗口**有关**（严格档实测点的首末）。
//
// ⇒ 文案里**必须**写「当前」，否则用户会以为那是窗口内的 SOH
// （那是一个我们**没算过**的数）。

// ⑪ 电池段文案**逐字**钉死（文案只有服务端一份 ⇒ 改文案必须改这个断言）。
func TestChargeBriefBatteryLinePinned(t *testing.T) {
	b := &ChargeBriefBattery{
		SohPercent: briefFloatp(83.13),
		FullCount:  3,
		// ⚠️ 故意把 First / Last **放反**（65.41 > 65.17）——
		// 正文报的是**范围**（min ~ max），不是「首 → 末」的方向。
		FirstKwh: briefFloatp(65.41),
		LastKwh:  briefFloatp(65.17),
		MinKwh:   briefFloatp(65.17),
		MaxKwh:   briefFloatp(65.41),
	}
	got := renderBatteryBriefLine(b)
	want := "电池健康：当前 83.1% · 本窗口满充实测 3 次 65.2 ~ 65.4 度"
	if got != want {
		t.Errorf("电池段 = %q，期望 %q", got, want)
	}
}

// ⑱ 只有**一次**实测 ⇒ 就一个数，不谈范围（也别写成「65.3 ~ 65.3」）。
func TestChargeBriefBatterySingleFullCharge(t *testing.T) {
	got := renderBatteryBriefLine(&ChargeBriefBattery{
		SohPercent: briefFloatp(83.3),
		FullCount:  1,
		MinKwh:     briefFloatp(65.3),
		MaxKwh:     briefFloatp(65.3),
	})
	want := "电池健康：当前 83.3% · 本窗口满充实测 1 次 65.3 度"
	if got != want {
		t.Errorf("电池段 = %q，期望 %q", got, want)
	}
}

// ⑫ 「当前」二字**必须**出现（口径分离的护栏）。
func TestChargeBriefBatterySaysCurrent(t *testing.T) {
	got := renderBatteryBriefLine(&ChargeBriefBattery{
		SohPercent: briefFloatp(83.13),
		FullCount:  1,
		FirstKwh:   briefFloatp(65.17),
		LastKwh:    briefFloatp(65.17),
	})
	if !strings.Contains(got, "当前") {
		t.Errorf("电池段里没有「当前」⇒ 窗口无关的数被说成了窗口内的数：%q", got)
	}
}

// ⑬ 窗口筛选：**严格档** + 窗口两端（越界与不充满的点都要被排除）。
func TestChargeBriefBatteryWindowFilter(t *testing.T) {
	ws := at(2026, time.October, 27, 0, 0)
	we := at(2026, time.November, 2, 15, 44)
	h := &BatteryHealth{
		HealthPercent: briefFloatp(83.13),
		Series: []*BatteryPoint{
			{Date: at(2026, time.October, 20, 10, 0), CapacityKwh: briefFloatp(66.0), EndSoc: intp(100)},  // 窗口外
			{Date: at(2026, time.October, 28, 10, 0), CapacityKwh: briefFloatp(65.17), EndSoc: intp(100)}, // 窗口内·严格档
			{Date: at(2026, time.October, 29, 10, 0), CapacityKwh: briefFloatp(66.5), EndSoc: intp(96)},   // 窗口内·非严格档
			{Date: at(2026, time.November, 1, 10, 0), CapacityKwh: briefFloatp(65.41), EndSoc: intp(100)}, // 窗口内·严格档
			{Date: at(2026, time.November, 3, 10, 0), CapacityKwh: briefFloatp(64.0), EndSoc: intp(100)},  // 窗口外
		},
	}
	b := chargeBriefBatteryFromHealth(h, &ws, &we)
	if b.FullCount != 2 {
		t.Fatalf("窗口内严格档点数 = %d，期望 2", b.FullCount)
	}
	if !b.Windowed {
		t.Errorf("有窗口时 Windowed 应为 true")
	}
	if b.FirstKwh == nil || briefAbs(*b.FirstKwh-65.17) > 0.001 {
		t.Errorf("窗口内首个实测 = %v，期望 65.17", b.FirstKwh)
	}
	if b.LastKwh == nil || briefAbs(*b.LastKwh-65.41) > 0.001 {
		t.Errorf("窗口内末个实测 = %v，期望 65.41", b.LastKwh)
	}
	if b.SohPercent == nil || briefAbs(*b.SohPercent-83.13) > 0.001 {
		t.Errorf("SOH%% = %v，期望 83.13", b.SohPercent)
	}
	if b.MinKwh == nil || briefAbs(*b.MinKwh-65.17) > 0.001 {
		t.Errorf("窗口内容量最小 = %v，期望 65.17", b.MinKwh)
	}
	if b.MaxKwh == nil || briefAbs(*b.MaxKwh-65.41) > 0.001 {
		t.Errorf("窗口内容量最大 = %v，期望 65.41", b.MaxKwh)
	}
}

// ⑭ 窗口为 nil（「全部历史」那一档）⇒ **只有当前值**，没有趋势。
func TestChargeBriefBatteryNoWindowNoTrend(t *testing.T) {
	h := &BatteryHealth{
		HealthPercent: briefFloatp(83.13),
		Series: []*BatteryPoint{
			{Date: at(2026, time.October, 28, 10, 0), CapacityKwh: briefFloatp(65.17), EndSoc: intp(100)},
		},
	}
	b := chargeBriefBatteryFromHealth(h, nil, nil)
	if b.FullCount != 0 {
		t.Errorf("没有窗口却给出了 %d 个趋势点 ⇒ 趋势不是窗口内的", b.FullCount)
	}
	got := renderBatteryBriefLine(b)
	want := "电池健康：当前 83.1%"
	if got != want {
		t.Errorf("无窗口时的电池段 = %q，期望 %q", got, want)
	}
}

// ⑮ 没有出厂基准 ⇒ **不给 SOH**（反幻觉），但窗口内的实测仍要给。
func TestChargeBriefBatteryNoSohStillGivesTrend(t *testing.T) {
	ws := at(2026, time.October, 27, 0, 0)
	we := at(2026, time.November, 2, 15, 44)
	h := &BatteryHealth{
		Series: []*BatteryPoint{
			{Date: at(2026, time.October, 28, 10, 0), CapacityKwh: briefFloatp(65.17), EndSoc: intp(100)},
			{Date: at(2026, time.November, 1, 10, 0), CapacityKwh: briefFloatp(65.41), EndSoc: intp(100)},
		},
	}
	b := chargeBriefBatteryFromHealth(h, &ws, &we)
	if b.SohPercent != nil {
		t.Errorf("没有出厂基准却给了 SOH：%v", *b.SohPercent)
	}
	got := renderBatteryBriefLine(b)
	want := "电池健康：本窗口满充实测 2 次 65.2 ~ 65.4 度"
	if got != want {
		t.Errorf("电池段 = %q，期望 %q", got, want)
	}
}

// ⑯ 窗口内没有满充实测 ⇒ 只说事实，**不给趋势**。
func TestChargeBriefBatteryNoFullInWindow(t *testing.T) {
	got := renderBatteryBriefLine(&ChargeBriefBattery{SohPercent: briefFloatp(83.13), FullCount: 0, Windowed: true})
	want := "电池健康：当前 83.1% · 本窗口没有满充实测"
	if got != want {
		t.Errorf("电池段 = %q，期望 %q", got, want)
	}
}

// ⑰ 电池段 JSON 契约：键齐全（少一个 ⇒ 客户端静默拿到空值）。
func TestChargeBriefBatteryJSONContract(t *testing.T) {
	b := &ChargeBriefBattery{
		SohPercent: briefFloatp(83.13),
		FullCount:  2,
		FirstKwh:   briefFloatp(65.17),
		LastKwh:    briefFloatp(65.41),
	}
	raw, err := json.Marshal(b)
	if err != nil {
		t.Fatalf("序列化失败: %v", err)
	}
	var m map[string]any
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatalf("反序列化失败: %v", err)
	}
	for _, k := range []string{"soh_percent", "full_count", "windowed", "first_kwh", "last_kwh", "min_kwh", "max_kwh", "first_at", "last_at"} {
		if _, ok := m[k]; !ok {
			t.Errorf("battery 缺键 %q", k)
		}
	}
}
