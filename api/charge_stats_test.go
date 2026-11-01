package main

import (
	"encoding/json"
	"testing"
	"time"
)

// ⚠️ **v0.108.0（用户反馈 #27）**：充电统计**区间口径**的护栏。
//
// 用户原话（真机截图 `media/feedback/27.jpg`）：
//
//	「充电记录顶上这个统计，需要可以手动切换范围，比如近 7 天，近 30 天，全部。」
//
// 截图上那张卡是**全量**的：1792 度 / 共 72 次 / 2026年7月 至 2026年10月。
// 用户要的是**同一张卡能切区间**。
//
// 三条规矩：
//
//  ① **窗口只有一份** —— 区间起点必须与用量页 `/stats?period=week|month` 同源
//     （`dayWindowStart`）。两处各写一遍 ⇒ 边界迟早漂。
//  ② **「全部」是全表** —— 不是客户端那份有上限的列表（那是静默截断的历史）。
//  ③ **口径与列表一致** —— 同一张表、同一过滤、会话级直交流判据。
//
// ⚠️ 所有断言都**钉具体数值**（不钉「有没有超过 N」）——
// 边界算错时，「有没有超过 N」这种断言会假绿。

// testZone 测试用的服务器时区（与生产一致），一次性载入。
var testZone = func() *time.Location {
	loc, err := time.LoadLocation("Asia/Shanghai")
	if err != nil {
		panic(err)
	}
	return loc
}()

func testLoc(t *testing.T) *time.Location {
	t.Helper()
	return testZone
}

func at(y int, mo time.Month, d, h, mi int) time.Time {
	return time.Date(y, mo, d, h, mi, 0, 0, testZone)
}

func intp(v int) *int { return &v }

// sameIntPtr 两个「可空整数」相等（nil 只跟 nil 相等）。
func sameIntPtr(a, b *int) bool {
	if a == nil || b == nil {
		return a == nil && b == nil
	}
	return *a == *b
}

// 固定「现在」：2026-11-02 15:44（用户截图里的时间）。
func fixedNow(t *testing.T) time.Time {
	t.Helper()
	return time.Date(2026, time.November, 2, 15, 44, 0, 0, testZone)
}

// 一组固定的充电行（跨 7 天 / 30 天 / 全部三个窗口的边界）。
func fixedRows(t *testing.T) []chargeStatRow {
	t.Helper()
	return []chargeStatRow{
		// 今天：直流，充到 100%（⇒ 这是「上次充满」）
		{StartDate: at(2026, time.November, 2, 13, 17), EnergyAdded: 12.9, DurationMin: intp(43), StartSoc: intp(80), EndSoc: intp(100), IsDC: true},
		// 两天前：直流，充到 80%
		{StartDate: at(2026, time.October, 31, 12, 23), EnergyAdded: 24.9, DurationMin: intp(27), StartSoc: intp(42), EndSoc: intp(80), IsDC: true},
		// 8 天前（落在 30 天窗口内、7 天窗口外）：交流，充到 100%
		{StartDate: at(2026, time.October, 25, 9, 0), EnergyAdded: 20.0, DurationMin: intp(300), StartSoc: intp(30), EndSoc: intp(100), IsDC: false},
		// 2 个月前（只有「全部」能看到）：直流，没充满
		{StartDate: at(2026, time.September, 1, 8, 0), EnergyAdded: 30.0, DurationMin: intp(40), StartSoc: intp(20), EndSoc: intp(60), IsDC: true},
		// 3 个月前：交流，**没有时长**（⇒ 不许拿 0 充数）
		{StartDate: at(2026, time.August, 15, 7, 0), EnergyAdded: 10.0, DurationMin: nil, StartSoc: intp(10), EndSoc: intp(50), IsDC: false},
	}
}

func rangeByKey(ranges []*ChargeStatsRange, key string) *ChargeStatsRange {
	for _, r := range ranges {
		if r.Key == key {
			return r
		}
	}
	return nil
}

// ① 窗口起点：**含今天在内的最近 N 个自然日**（服务器时区）。
//
// ⚠️ 钉的是**具体时刻**：2026-11-02 15:44 ⇒ 7 天窗口起点 = 2026-10-27 00:00 +08:00。
func TestChargeStatsWindowStartPinned(t *testing.T) {
	loc := testLoc(t)
	now := fixedNow(t)
	rows := fixedRows(t)
	ranges := buildChargeStats(loc, now, rows)

	want := map[string]time.Time{
		// 今天 00:00 往前 6 天
		"7d": time.Date(2026, time.October, 27, 0, 0, 0, 0, loc),
		// 今天 00:00 往前 29 天
		"30d": time.Date(2026, time.October, 4, 0, 0, 0, 0, loc),
		// 自然月：本月 1 号 00:00（⚠️ 与「近 30 天」**不是**一回事）
		"month": time.Date(2026, time.November, 1, 0, 0, 0, 0, loc),
	}
	for key, w := range want {
		r := rangeByKey(ranges, key)
		if r == nil {
			t.Fatalf("区间 %s 缺失", key)
		}
		if r.WindowStart == nil {
			t.Fatalf("区间 %s 没有窗口起点", key)
		}
		if !r.WindowStart.Equal(w) {
			t.Fatalf("区间 %s 的窗口起点 = %s，期望 %s",
				key, r.WindowStart.In(loc).Format("2006-01-02 15:04:05 -07:00"),
				w.Format("2006-01-02 15:04:05 -07:00"))
		}
	}

	// 「全部」没有窗口起点 —— 它的边界是**数据自身**的首尾。
	all := rangeByKey(ranges, "all")
	if all == nil {
		t.Fatal("区间 all 缺失")
	}
	if all.WindowStart == nil || !all.WindowStart.Equal(at(2026, time.August, 15, 7, 0)) {
		t.Fatalf("区间 all 的起点 = %v，期望 2026-08-15 07:00", all.WindowStart)
	}
	if all.WindowEnd == nil || !all.WindowEnd.Equal(at(2026, time.November, 2, 13, 17)) {
		t.Fatalf("区间 all 的终点 = %v，期望 2026-11-02 13:17", all.WindowEnd)
	}
}

// ② 与用量页 /stats 的窗口**同源**：两个端点必须调同一个函数。
//
// ⚠️ 「本月」也必须同源（/stats 的 calendar_month 用的是 monthStart）。
func TestDayWindowStartPinned(t *testing.T) {
	loc := testLoc(t)
	now := fixedNow(t)
	cases := []struct {
		days int
		want time.Time
	}{
		{1, time.Date(2026, time.November, 2, 0, 0, 0, 0, loc)},
		{7, time.Date(2026, time.October, 27, 0, 0, 0, 0, loc)},
		{30, time.Date(2026, time.October, 4, 0, 0, 0, 0, loc)},
	}
	// 自然月的起点：**不是** dayWindowStart 的任何一天（它是本月 1 号）。
	if got := monthStart(loc, now); !got.Equal(time.Date(2026, time.November, 1, 0, 0, 0, 0, loc)) {
		t.Fatalf("monthStart = %s，期望 2026-11-01 00:00", got.Format("2006-01-02 15:04:05 -07:00"))
	}
	for _, c := range cases {
		got := dayWindowStart(loc, now, c.days)
		if !got.Equal(c.want) {
			t.Fatalf("dayWindowStart(days=%d) = %s，期望 %s",
				c.days, got.Format("2006-01-02 15:04:05 -07:00"), c.want.Format("2006-01-02 15:04:05 -07:00"))
		}
	}
}

// ③ 三个区间的**聚合数值**（场次 / 度数 / 直交流拆分 / 平均时长）。
//
// ⚠️ 平均时长是**可空**的：没有样本 ⇒ null（不给数，不编）。
// 「近 7 天」里两场都是直流 ⇒ 交流平均时长必须**没有**。
func TestChargeStatsAggregatesPinned(t *testing.T) {
	loc := testLoc(t)
	now := fixedNow(t)
	ranges := buildChargeStats(loc, now, fixedRows(t))

	type want struct {
		count    int
		totalKwh float64
		dcKwh    float64
		dcCount  int
		acKwh    float64
		acCount  int
		dcAvgMin *int
		acAvgMin *int
	}
	cases := map[string]want{
		// 近 7 天：只有 11-02 与 10-31 两场（10-25 在窗口外）；全是直流 ⇒ 没有交流平均
		"7d": {count: 2, totalKwh: 37.8, dcKwh: 37.8, dcCount: 2, acKwh: 0, acCount: 0, dcAvgMin: intp(35), acAvgMin: nil},
		// 近 30 天：加上 10-25 那场交流（交流平均 = 300 分钟）
		"30d": {count: 3, totalKwh: 57.8, dcKwh: 37.8, dcCount: 2, acKwh: 20.0, acCount: 1, dcAvgMin: intp(35), acAvgMin: intp(300)},
		// 全部：五场；交流有两场但只有一场有时长（另一场没时长 ⇒ 不进平均）
		"all": {count: 5, totalKwh: 97.8, dcKwh: 67.8, dcCount: 3, acKwh: 30.0, acCount: 2, dcAvgMin: intp(37), acAvgMin: intp(300)},
		// 自然月：只有 11-02 那场（10-31 落在上月）
		"month": {count: 1, totalKwh: 12.9, dcKwh: 12.9, dcCount: 1, acKwh: 0, acCount: 0, dcAvgMin: intp(43), acAvgMin: nil},
	}
	for key, w := range cases {
		r := rangeByKey(ranges, key)
		if r == nil {
			t.Fatalf("区间 %s 缺失", key)
		}
		if r.Count != w.count {
			t.Errorf("区间 %s：场次 = %d，期望 %d", key, r.Count, w.count)
		}
		if diff := r.TotalKwh - w.totalKwh; diff > 0.001 || diff < -0.001 {
			t.Errorf("区间 %s：总度数 = %v，期望 %v", key, r.TotalKwh, w.totalKwh)
		}
		if diff := r.DcKwh - w.dcKwh; diff > 0.001 || diff < -0.001 {
			t.Errorf("区间 %s：直流通数 = %v，期望 %v", key, r.DcKwh, w.dcKwh)
		}
		if r.DcCount != w.dcCount {
			t.Errorf("区间 %s：直流场次 = %d，期望 %d", key, r.DcCount, w.dcCount)
		}
		if diff := r.AcKwh - w.acKwh; diff > 0.001 || diff < -0.001 {
			t.Errorf("区间 %s：交流度数 = %v，期望 %v", key, r.AcKwh, w.acKwh)
		}
		if r.AcCount != w.acCount {
			t.Errorf("区间 %s：交流场次 = %d，期望 %d", key, r.AcCount, w.acCount)
		}
		if !sameIntPtr(r.DcAvgMin, w.dcAvgMin) {
			t.Errorf("区间 %s：直流平均时长 = %v，期望 %v", key, r.DcAvgMin, w.dcAvgMin)
		}
		if !sameIntPtr(r.AcAvgMin, w.acAvgMin) {
			t.Errorf("区间 %s：交流平均时长 = %v，期望 %v", key, r.AcAvgMin, w.acAvgMin)
		}
	}
}

// ④ 「上次充满」：**真的充到 100%** 才算，且取**最近**的一场。
//
// ⚠️ 09-01 那场只充到 60%、08-15 那场充到 50% ⇒ 都不算。
func TestChargeStatsLastFullPinned(t *testing.T) {
	loc := testLoc(t)
	now := fixedNow(t)
	ranges := buildChargeStats(loc, now, fixedRows(t))

	all := rangeByKey(ranges, "all")
	if all.LastFull == nil {
		t.Fatal("全部区间没给出「上次充满」")
	}
	if !all.LastFull.Date.Equal(at(2026, time.November, 2, 13, 17)) {
		t.Errorf("「上次充满」的时刻 = %v，期望 2026-11-02 13:17", all.LastFull.Date)
	}
	if all.LastFull.FromSoc == nil || *all.LastFull.FromSoc != 80 {
		t.Errorf("「上次充满」的起始电量 = %v，期望 80", all.LastFull.FromSoc)
	}
	if all.LastFull.ToSoc == nil || *all.LastFull.ToSoc != 100 {
		t.Errorf("「上次充满」的结束电量 = %v，期望 100", all.LastFull.ToSoc)
	}

	// 7 天窗口里也有那一场（今天）⇒ 一样能给出。
	r7 := rangeByKey(ranges, "7d")
	if r7.LastFull == nil || !r7.LastFull.Date.Equal(at(2026, time.November, 2, 13, 17)) {
		t.Errorf("近 7 天的「上次充满」 = %v，期望 2026-11-02 13:17", r7.LastFull)
	}
}

// ⑤ 没有样本时**不给数**（不许拿 0 充数）。
func TestChargeStatsNoSampleGivesNoNumber(t *testing.T) {
	loc := testLoc(t)
	now := fixedNow(t)
	// 只有一场交流，且没有时长 ⇒ 交流平均时长必须是 null。
	rows := []chargeStatRow{
		{StartDate: at(2026, time.November, 2, 13, 17), EnergyAdded: 12.9, DurationMin: nil, StartSoc: intp(50), EndSoc: intp(80), IsDC: false},
	}
	ranges := buildChargeStats(loc, now, rows)
	all := rangeByKey(ranges, "all")
	if all == nil {
		t.Fatal("区间 all 缺失")
	}
	if all.AcAvgMin != nil {
		t.Errorf("没有时长样本时交流平均时长 = %d，应为 null（不给数）", *all.AcAvgMin)
	}
	if all.DcAvgMin != nil {
		t.Errorf("没有直流样本时直流平均时长 = %d，应为 null", *all.DcAvgMin)
	}
	if all.Count != 1 {
		t.Errorf("场次 = %d，期望 1", all.Count)
	}
	if all.LastFull != nil {
		t.Errorf("没充满过却给出了「上次充满」: %v", all.LastFull)
	}
}

// ⑥ **线上契约**：JSON 必须真的带上 App 要用的键（少一个 ⇒ App 静默拿到默认值）。
func TestChargeStatsJSONContract(t *testing.T) {
	loc := testLoc(t)
	now := fixedNow(t)
	payload := ChargeStats{
		ServerTimezone: "Asia/Shanghai",
		GeneratedAt:    now.UTC(),
		Ranges:         buildChargeStats(loc, now, fixedRows(t)),
	}
	b, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("序列化失败: %v", err)
	}
	var m map[string]any
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatalf("反序列化失败: %v", err)
	}
	for _, k := range []string{"server_timezone", "generated_at", "ranges"} {
		if _, ok := m[k]; !ok {
			t.Errorf("顶层缺键 %q", k)
		}
	}
	rs, ok := m["ranges"].([]any)
	if !ok || len(rs) != 4 {
		t.Fatalf("ranges 不是四个区间: %v", m["ranges"])
	}
	first, ok := rs[0].(map[string]any)
	if !ok {
		t.Fatalf("ranges[0] 不是对象")
	}
	for _, k := range []string{
		"key", "days", "window_start", "window_end", "count", "total_kwh",
		"dc_kwh", "dc_count", "dc_avg_min", "ac_kwh", "ac_count", "ac_avg_min",
		"last_full",
	} {
		if _, ok := first[k]; !ok {
			t.Errorf("区间缺键 %q", k)
		}
	}
	// 区间顺序固定（App 与 Hermes 都按 key 取；顺序即服务端下发的顺序）。
	wantKeys := []string{"7d", "30d", "month", "all"}
	for i, wk := range wantKeys {
		got := rs[i].(map[string]any)["key"]
		if got != wk {
			t.Errorf("ranges[%d].key = %v，期望 %q", i, got, wk)
		}
	}
}
