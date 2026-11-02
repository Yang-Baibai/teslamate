package main

import (
	"context"
	"math"
	"net/http"
	"time"
)

// ── 充电统计（区间）─────────────────────────────────────────────────
//
// 用户反馈 #27：「充电记录顶上这个统计，需要可以手动切换范围（近 7 天 / 近 30 天 / 全部）」。
//
// ## 为什么聚合放服务端，而不是 App 自己过滤列表
//
// ① **窗口口径只能有一份**。用量页的 `/stats?period=week|month` 用的是
//    「今天 00:00 往前 N−1 天」的滚动自然日窗口（服务器时区）。
//    如果 App 端再按 `start_date` 自己切一遍「近 7 天」，
//    两处的窗口起点会差出一个边界（甚至一个时区）⇒
//    同一个 App 里两个页面的「7 天」对不上，用户以为数据在骗人。
//    ⇒ 窗口由服务端算（[dayWindowStart]），App 只负责渲染。
// ② **「全部」不能靠客户端那份列表**。App 只拉最近若干条充电记录，
//    超过这个量，「全部」就变成**静默截断**的历史 —— 那是编数据。
//    服务端查的是全表 ⇒「全部」是真的全部。
// ③ **口径必须与列表一致**：聚合用的表、过滤条件、直交流判据
//    都与 [sqlCharges] 同一套（会话级 [cteChargeDC]）⇒
//    卡片上的「共 N 次」与下面列表里的行是同一批充电会话。
//
// ## 三个区间
//
//   - `7d`  → 含今天在内的最近 7 个自然日（起点 = 今天 00:00 − 6 天）
//   - `30d` → 含今天在内的最近 30 个自然日（起点 = 今天 00:00 − 29 天）
//   - `all` → 全部历史（边界 = 数据自身的首尾，与旧卡片那行一致）
//
// ⚠️ `7d`/`30d` 的窗口边界与 `all` 的数据边界**语义不同**（前者是窗口，
// 后者是数据跨度）—— 但两者都是「这组数说的是哪段时间」，App 只按服务端给的
// 边界显示，不自己算。

// chargeStatRow 聚合用的一行充电会话（只取卡片要用的字段）。
type chargeStatRow struct {
	StartDate   time.Time
	EnergyAdded float64
	DurationMin *int
	StartSoc    *int
	EndSoc      *int
	IsDC        bool
}

// chargeRangeSpec 一个统计区间的定义。
type chargeRangeSpec struct {
	key string
	// days 窗口天数；0 = 全部历史。
	days int
	// calendarMonth = **自然月**（本月 1 号 00:00 起）。
	//
	// ⚠️ 它与 days>0 是**两种不同的口径**：「近 30 天」是滚动窗口，
	// 「本月」是自然月（实测两者差 25.9%，见 v0.29.0）。
	// 飞书里用户说「这个月充了多少度」指的是**自然月** ⇒ 必须有这一档。
	calendarMonth bool
}

// chargeRangeSpecs 四个区间。
//
// ⚠️ 顺序 = 服务端下发的顺序（App 与 Hermes 都按 key 取，顺序不影响取数，
// 但闸门会检查「App 的区间键 ⊆ 服务端且相对顺序一致」）。
var chargeRangeSpecs = []chargeRangeSpec{
	{key: "7d", days: 7},
	{key: "30d", days: 30},
	{key: "month", calendarMonth: true},
	{key: "all", days: 0},
}

// chargeRangeSpecByKey 按 key 取区间定义。
func chargeRangeSpecByKey(key string) (chargeRangeSpec, bool) {
	for _, s := range chargeRangeSpecs {
		if s.key == key {
			return s, true
		}
	}
	return chargeRangeSpec{}, false
}

// bucketCount 这个区间要分几个**桶**（按天）。
//
// ⚠️ **v0.115.1**：简报的能耗段要按**同一个窗口**聚合（同一份事实），
// 所以桶数必须和窗口严格对齐：滚动 7/30 天 ⇒ 7/30 个桶；
// 自然月 ⇒ 从 1 号到今天 ⇒ 今天几号就几个桶。
// ⚠️ 「全部历史」⇒ **没有窗口**（边界是数据自身首尾）⇒ 桶数 0
// ⇒ 那一档**不并能耗段**（/stats 没有全量档；drives 上限 500 也不够）。
func (s chargeRangeSpec) bucketCount(loc *time.Location, now time.Time) int {
	if s.calendarMonth {
		return now.Day()
	}
	return s.days
}

// buildChargeStats 把充电行汇总成三个区间的统计（纯函数，可单测）。
func buildChargeStats(loc *time.Location, now time.Time, rows []chargeStatRow) []*ChargeStatsRange {
	out := make([]*ChargeStatsRange, 0, len(chargeRangeSpecs))
	for _, spec := range chargeRangeSpecs {
		out = append(out, summarizeChargeRange(spec, loc, now, rows))
	}
	return out
}

// summarizeChargeRange 汇总一个区间。
func summarizeChargeRange(spec chargeRangeSpec, loc *time.Location, now time.Time, rows []chargeStatRow) *ChargeStatsRange {
	// 窗口起点：days>0 用 [dayWindowStart]（与 /stats 同一套）；
	// 自然月用 [monthStart]（与 /stats 的 calendar_month 同一套）；
	// days=0 且非自然月（全部）没有起点 —— 边界取数据自身的首尾。
	var windowStart *time.Time
	switch {
	case spec.calendarMonth:
		ms := monthStart(loc, now)
		windowStart = &ms
	case spec.days > 0:
		ws := dayWindowStart(loc, now, spec.days)
		windowStart = &ws
	}

	kept := make([]chargeStatRow, 0, len(rows))
	for _, r := range rows {
		if windowStart != nil && r.StartDate.Before(*windowStart) {
			continue
		}
		kept = append(kept, r)
	}

	res := &ChargeStatsRange{Key: spec.key, Days: spec.days, Count: len(kept)}

	var total, dcKwh, acKwh float64
	var dcCount, acCount int
	var dcDurSum, acDurSum float64
	var dcDurN, acDurN int
	var lastFull *ChargeStatsLastFull
	var firstAt, lastAt *time.Time

	for _, r := range kept {
		total += r.EnergyAdded
		if r.IsDC {
			dcKwh += r.EnergyAdded
			dcCount++
			if r.DurationMin != nil {
				dcDurSum += float64(*r.DurationMin)
				dcDurN++
			}
		} else {
			acKwh += r.EnergyAdded
			acCount++
			if r.DurationMin != nil {
				acDurSum += float64(*r.DurationMin)
				acDurN++
			}
		}
		if firstAt == nil || r.StartDate.Before(*firstAt) {
			t := r.StartDate
			firstAt = &t
		}
		if lastAt == nil || r.StartDate.After(*lastAt) {
			t := r.StartDate
			lastAt = &t
		}
		// ⚠️ 「上次充满」的判据是**真的充到 100%**（不是「充过一次」）：
		// 半途拔枪的场次只是路过补一点，不算「充满」。
		// （口径与旧的客户端实现 [lastFullCharge] 相同。）
		if r.EndSoc != nil && *r.EndSoc >= 100 {
			if lastFull == nil || r.StartDate.After(lastFull.Date) {
				lastFull = &ChargeStatsLastFull{Date: r.StartDate, FromSoc: r.StartSoc, ToSoc: r.EndSoc}
			}
		}
	}

	res.TotalKwh = total
	res.DcKwh = dcKwh
	res.DcCount = dcCount
	res.AcKwh = acKwh
	res.AcCount = acCount
	if dcDurN > 0 {
		v := int(math.Round(dcDurSum / float64(dcDurN)))
		res.DcAvgMin = &v
	}
	if acDurN > 0 {
		v := int(math.Round(acDurSum / float64(acDurN)))
		res.AcAvgMin = &v
	}
	res.LastFull = lastFull

	if windowStart != nil {
		res.WindowStart = utcPtr(*windowStart)
		res.WindowEnd = utcPtr(now)
	} else {
		// 全部：边界 = 数据自身的首尾（与旧卡片那行「2026年7月 至 2026年10月」一致）。
		res.WindowStart = firstAt
		res.WindowEnd = lastAt
	}
	return res
}

// utcPtr 时间一律以 UTC 下发 —— Go 的 time.Time 默认会带本地偏移（+08:00），
// 而 App 的 Instant 解析器要的是标准 ISO-8601 ⇒ 统一转成 UTC（末尾 Z）。
func utcPtr(t time.Time) *time.Time {
	u := t.UTC()
	return &u
}

// handleChargeStats 充电统计（三个区间一次给全 —— 切换区间不重新请求）。
func handleChargeStats(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	tz := getenv("TZ_NAME", "Asia/Shanghai")
	loc, err := time.LoadLocation(tz)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "时区无效: "+tz)
		return
	}
	rows, err := db.Query(context.Background(), sqlChargeStats, id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	defer rows.Close()
	list := []chargeStatRow{}
	for rows.Next() {
		var r chargeStatRow
		if err := rows.Scan(
			&r.StartDate, &r.EnergyAdded, &r.DurationMin,
			&r.StartSoc, &r.EndSoc, &r.IsDC,
		); err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		list = append(list, r)
	}
	now := time.Now().In(loc)
	writeJSON(w, &ChargeStats{
		ServerTimezone: tz,
		GeneratedAt:    now.UTC(),
		Ranges:         buildChargeStats(loc, now, list),
	})
}
