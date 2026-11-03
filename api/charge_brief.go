package main

import (
	"context"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// ── 充电简报（服务端渲染好的标题 + 正文）────────────────────────────
//
// ## 为什么要有这个端点（v0.111.0）
//
// 定时任务使命里有一格是「**周期性健康报告**」——
// 而 App 的统计卡**推不到飞书**（用户在 App 里看得到、在飞书里问得到，
// 但没人**定时**把结论送到眼前）。
//
// ## 为什么文案放服务端（而不是脚本或 MCP 各写一份）
//
// 本项目反复吃过「同一句话写两遍」的亏：
//
//   · v0.76.0：「完成后是什么样」在服务端与客户端各写了一份；
//   · v0.78.0：通知里「充电完成」**写死在脚本里** ⇒ 实测只充到 80% 也说「完成」（**假成功**）。
//
// ⇒ **判定与文案都在服务端**（本文件），推送脚本与 MCP 只**转述**
//   （与故障码告警一个套路：`api/telemetry.go` 给文案，脚本原样转述）。
//
// ## 数字从哪来
//
// 直接复用 [summarizeChargeRange] ⇒ 与 App 卡片是**同一份聚合**，
// 不存在「卡片一个数、简报另一个数」的可能（ADR #38 的同一事实只留一处）。
//
// ⚠️ **v0.115.1**：并入**能耗统计**这一段（使命 §2.2 的那半句还空着）。
// 而能耗段必须和 App 用量页**说同一个数** ⇒
//   · 窗口**从充电段的区间借**（[ChargeStatsRange.WindowStart]）—— **不许再算一次**；
//   · 聚合走 [statsBucketsForWindow]（用量页同一个函数）。
//
// ## 反幻觉三条
//
//  1. **窗口必须露出来** —— 只说「近 30 天」不说边界，用户没法核对「是哪 30 天」。
//     （能耗段与充电段**同一个窗口** ⇒ 全篇只有一行窗口。）
//  2. **没有样本就不给数** —— 平均时长/平均能耗没有样本就是 null，正文里就不写那一段。
//  3. **没充满过就说没充满过** —— 不用「上次充电」冒充「上次充满」。

// chargeBriefPeriodLabel 区间 key → 中文标签（**服务端这一份**）。
//
// ⚠️ 与 MCP 的 `_CHARGE_RANGE_CN` 是**两件事**：那是 MCP 输出给模型的**字段词表**，
// 这里是服务端简报里的**文案**。两者不许合并（合并会让模型侧的措辞与推送侧绑死）。
var chargeBriefPeriodLabel = map[string]string{
	"7d":    "近 7 天",
	"30d":   "近 30 天",
	"month": "本月（自然月）",
	"all":   "全部历史",
}

// fmtKwh 度数统一保留 1 位小数（37.8 / 0.0）。
func fmtKwh(v float64) string {
	return strconv.FormatFloat(v, 'f', 1, 64)
}

// renderChargeBrief 渲染简报的标题与正文（纯函数，可单测）。
//
// ⚠️ 时间一律按**服务器时区**渲染，并把时区名写进正文 ——
// 用户拿手机上的时间对一遍才不会以为差了几小时（v0.98.0 的时区教训）。
//
// @param seg 简报里的可选段（能耗段 / 电池健康段）；nil 等价于「一段都没有」。
func renderChargeBrief(loc *time.Location, tz string, carName string, r *ChargeStatsRange,
	seg *ChargeBriefSegments) (string, string) {
	label := chargeBriefPeriodLabel[r.Key]
	if label == "" {
		label = r.Key
	}
	if carName == "" {
		carName = "车辆"
	}
	// ⚠️ **v0.115.1**：标题从「充电简报」改成「用车简报」——
	// 这一份现在**同时**讲充电与能耗，只说「充电」会漏掉一半。
	title := fmt.Sprintf("📊 %s · %s用车简报", carName, label)

	lines := []string{}
	// 窗口：两个边界都要（「全部」给的是数据自身的首尾）。
	ws, we := "", ""
	if r.WindowStart != nil {
		ws = r.WindowStart.In(loc).Format("2006-01-02 15:04")
	}
	if r.WindowEnd != nil {
		we = r.WindowEnd.In(loc).Format("2006-01-02 15:04")
	}
	if ws != "" && we != "" {
		// ⚠️ 充电段与能耗段**共用这一个窗口** ⇒ 只有一行窗口，不会出现两个口径。
		lines = append(lines, fmt.Sprintf("窗口：%s → %s（服务器时区 %s）", ws, we, tz))
	}

	if r.Count == 0 {
		// 没有样本 ⇒ **不给数**，只说清「这个区间没有充电记录」。
		lines = append(lines, "这个区间没有充电记录。")
		// ── 续航段（v0.119.0 补记）──────────────────────────────
		//
		// ⚠️ 这一段是**当前值**（与窗口无关）⇒ 文案**必须**写「当前」
		// —— 否则用户会以为「近 30 天能开 171 公里」（那是一个我们没算过的数）。
		if seg != nil {
			if line := renderRangeBriefLine(seg.Range); line != "" {
				lines = append(lines, line)
			}
			if seg.RangeNote != "" {
				lines = append(lines, seg.RangeNote)
			}
		}

		return title, strings.Join(lines, "\n")
	}

	lines = append(lines, fmt.Sprintf("充电 %d 次 · 充入 %s 度", r.Count, fmtKwh(r.TotalKwh)))

	dc := fmt.Sprintf("直流（快充）：%d 次 · %s 度", r.DcCount, fmtKwh(r.DcKwh))
	if r.DcAvgMin != nil {
		dc += fmt.Sprintf(" · 平均 %d 分钟", *r.DcAvgMin)
	}
	lines = append(lines, dc)

	ac := fmt.Sprintf("交流（慢充）：%d 次 · %s 度", r.AcCount, fmtKwh(r.AcKwh))
	if r.AcAvgMin != nil {
		ac += fmt.Sprintf(" · 平均 %d 分钟", *r.AcAvgMin)
	}
	lines = append(lines, ac)

	if r.LastFull != nil {
		line := "上次充满：" + r.LastFull.Date.In(loc).Format("2006-01-02 15:04")
		switch {
		case r.LastFull.FromSoc != nil && r.LastFull.ToSoc != nil:
			line += fmt.Sprintf("（%d%% → %d%%）", *r.LastFull.FromSoc, *r.LastFull.ToSoc)
		case r.LastFull.ToSoc != nil:
			line += fmt.Sprintf("（→ %d%%）", *r.LastFull.ToSoc)
		case r.LastFull.FromSoc != nil:
			line += fmt.Sprintf("（%d%% →）", *r.LastFull.FromSoc)
		}
		lines = append(lines, line)
	} else {
		lines = append(lines, "上次充满：这个区间里没有充满过")
	}

	// ── 能耗段（v0.115.1）────────────────────────────────────
	var energy *ChargeBriefEnergy
	energyNote := ""
	if seg != nil {
		energy = seg.Energy
		energyNote = seg.EnergyNote
	}
	if energy != nil {
		line := fmt.Sprintf("能耗：行驶 %.1f 公里 · 用电 %s 度", energy.Km, fmtKwh(energy.KwhUsed))
		if energy.KwhPer100Km != nil {
			// ⚠️ 单位用 **kWh/100km**（界面上给用户看的那个口径）——
			// 与 App 的 Fmt.kt.fmtKwh100 同口径（Wh/km ÷ 10）。
			line += fmt.Sprintf(" · 平均 %.1f kWh/100km", *energy.KwhPer100Km)
		}
		lines = append(lines, line)
	} else if energyNote != "" {
		// ⚠️ 只说**一句话**（用户真读的东西）；理由在代码注释里。
		lines = append(lines, energyNote)
	}

	// ── 电池健康段（v0.116.0 补记）────────────────────────────
	//
	// ⚠️ 这一段里有**两个口径**：当前 SOH%（**窗口无关**）+ 窗口内满充实测趋势（**窗口有关**）
	// ⇒ 文案里**必须**写「当前」，否则用户会以为那是「近 30 天的 SOH」
	// —— 那是一个我们**没算过**的数（反幻觉）。
	if seg != nil {
		if line := renderBatteryBriefLine(seg.Battery); line != "" {
			lines = append(lines, line)
		}
		if seg.BatteryNote != "" {
			lines = append(lines, seg.BatteryNote)
		}
	}
	return title, strings.Join(lines, "\n")
}

// renderBatteryBriefLine 渲染电池健康段那一行（纯函数）。
//
// ⚠️ **不给方向性结论**：窗口内首末两个实测值的差，可能只是**测量噪声**
// （严格档离散度 MAD/中位数 ≈ 0.59%）⇒ 说「变好了 / 变差了」是编的。
// 这里只如实报两个实测值（宁可少说，不多说）。
func renderBatteryBriefLine(b *ChargeBriefBattery) string {
	if b == nil {
		return ""
	}
	parts := []string{}
	if b.SohPercent != nil {
		// ⚠️ 「当前」两个字**必须**有 —— 它标明这个数与窗口无关。
		parts = append(parts, fmt.Sprintf("当前 %.1f%%", *b.SohPercent))
	}
	if b.FullCount > 0 && b.MinKwh != nil && b.MaxKwh != nil {
		if b.FullCount == 1 {
			// 一次实测 ⇒ 就一个数，不谈范围。
			parts = append(parts, fmt.Sprintf("本窗口满充实测 1 次 %s 度", fmtKwh(*b.MinKwh)))
		} else {
			// ⚠️ **用「~」不用「→」** ——
			// 「64.8 → 65.3」会被读成「电池变好了」，
			// 而那个方向**不是结论**（严格档离散度 MAD/中位数 ≈ 0.59%
			// ⇒ 窗口内首末差很可能只是测量噪声）⇒ 只报**范围**，不下方向性结论。
			parts = append(parts, fmt.Sprintf("本窗口满充实测 %d 次 %s ~ %s 度",
				b.FullCount, fmtKwh(*b.MinKwh), fmtKwh(*b.MaxKwh)))
		}
	} else if b.SohPercent != nil && b.Windowed {
		// 窗口内没有充满过 ⇒ **不给趋势**，但**当前的 SOH 仍然要给**
		//（它来自最近一次满充实测，与窗口无关）。
		//
		// ⚠️ 只在**有窗口**时说这句 —— 「全部历史」那一档压根没有「本窗口」，
		// 说「本窗口没有满充实测」是编的。
		parts = append(parts, "本窗口没有满充实测")
	}
	if len(parts) == 0 {
		return ""
	}
	return "电池健康：" + strings.Join(parts, " · ")
}

// renderRangeBriefLine 渲染续航段那一行（纯函数）。
//
// ⚠️ **「当前」两个字必须有** —— 这一段是「**现在**能开多远」，与简报窗口**无关**
// ⇒ 不写「当前」就会被读成「近 30 天能开 171 公里」（那是一个我们**没算过**的数）。
//
// ⚠️ **不给方向性结论**：这里只有**一个**当前值 ⇒ 不存在「变好 / 变差」⇒ 也不许出现箭头。
func renderRangeBriefLine(r *ChargeBriefRange) string {
	if r == nil || r.TrueRangeKm == nil {
		return ""
	}
	parts := []string{fmt.Sprintf("当前真实可达 %.0f 公里", *r.TrueRangeKm)}
	if r.TrueRangeLoKm != nil && r.TrueRangeHiKm != nil {
		parts = append(parts, fmt.Sprintf("约 %d ~ %d 公里", *r.TrueRangeLoKm, *r.TrueRangeHiKm))
	}
	if r.FloorRangeKm != nil {
		f := fmt.Sprintf("留 %.0f%% 保底能开 %.0f 公里", rangeFloorReservePct, *r.FloorRangeKm)
		if r.FloorRangeLoKm != nil && r.FloorRangeHiKm != nil {
			f += fmt.Sprintf("（约 %d ~ %d 公里）", *r.FloorRangeLoKm, *r.FloorRangeHiKm)
		}
		parts = append(parts, f)
	}
	return "续航：" + strings.Join(parts, " · ")
}

// chargeBriefBatteryFromHealth 从电池健康里取出简报的**电池段**（纯函数，可单测）。
//
// ⚠️ **窗口从充电区间借**（[ChargeStatsRange.WindowStart] / [WindowEnd]）——
// **不许在这里再算一次**（dayWindowStart / monthStart 只在 charge_stats.go 里出现）
// ⇒ 全篇仍然只有**一行**窗口。
//
// ⚠️ 窗口为 nil ⇒ **没有「窗口内」可言**（"全部历史"那一档的边界是**充电记录**的首尾，
// 而电池曲线是**满充点**序列 ⇒ 两者边界语义不同）⇒ 只给「当前 SOH%」。
func chargeBriefBatteryFromHealth(h *BatteryHealth, ws, we *time.Time) *ChargeBriefBattery {
	if h == nil {
		return nil
	}
	out := &ChargeBriefBattery{SohPercent: h.HealthPercent}
	if ws == nil || we == nil {
		// ⚠️ 没有窗口 ⇒ 这一段**只有当前值**，没有「窗口内」可言。
		return out
	}
	out.Windowed = true
	var first, last *BatteryPoint
	for _, p := range h.Series {
		// ⚠️ **严格档**：只取**真的充到 100%** 的点 ——
		// 90~98% 的点是从 94% 线性外推到 100% 的，系统性偏高 0.56%（服务端自己写明）。
		if p == nil || p.CapacityKwh == nil || p.EndSoc == nil || *p.EndSoc < 100 {
			continue
		}
		if p.Date.Before(*ws) || p.Date.After(*we) {
			continue
		}
		out.FullCount++
		// ⚠️ 显式比时间，**不依赖序列顺序**（SQL 是 ORDER BY end_date，但别把正确性押在上面）。
		if first == nil || p.Date.Before(first.Date) {
			first = p
		}
		if last == nil || p.Date.After(last.Date) {
			last = p
		}
		// 范围：窗口内**所有**实测点的最小 / 最大值（正文只报它，不报方向）。
		if out.MinKwh == nil || *p.CapacityKwh < *out.MinKwh {
			v := *p.CapacityKwh
			out.MinKwh = &v
		}
		if out.MaxKwh == nil || *p.CapacityKwh > *out.MaxKwh {
			v := *p.CapacityKwh
			out.MaxKwh = &v
		}
	}
	if first != nil {
		out.FirstKwh = first.CapacityKwh
		f := first.Date
		out.FirstAt = &f
	}
	if last != nil {
		out.LastKwh = last.CapacityKwh
		l := last.Date
		out.LastAt = &l
	}
	return out
}

// chargeBriefEnergyFromSummary 把用量页的**汇总**变成简报的**能耗段**（纯函数，可单测）。
//
// ⚠️ **没有里程就不给数**（反幻觉）：里程不足 0.1km ⇒ 没有能耗结论 ⇒ nil。
// ⚠️ 单位换算只有这一处：Wh/km ÷ 10 = kWh/100km（界面上给用户看的那个口径）。
func chargeBriefEnergyFromSummary(summary *StatsBucket) *ChargeBriefEnergy {
	if summary == nil || summary.Km <= 0.1 {
		return nil
	}
	var wh, k100 *float64
	if summary.WhPerKm != nil {
		wh = summary.WhPerKm
		v := *summary.WhPerKm / 10.0
		k100 = &v
	}
	return &ChargeBriefEnergy{
		Km:          summary.Km,
		Minutes:     summary.Minutes,
		KwhUsed:     summary.KwhUsed,
		WhPerKm:     wh,
		KwhPer100Km: k100,
		DriveCount:  summary.DriveCount,
	}
}

// carDisplayName 这台车的名字（简报标题要写清「谁的车」）。
//
// ⚠️ 查不到就用「车辆」——**不编**车名。
func carDisplayName(id int) string {
	var name string
	if err := db.QueryRow(context.Background(), `SELECT name FROM cars WHERE id = $1`, id).Scan(&name); err != nil {
		return "车辆"
	}
	if strings.TrimSpace(name) == "" {
		return "车辆"
	}
	return name
}

// handleChargeBrief GET /api/v1/cars/{id}/charge_brief?period=7d
//
// ⚠️ period 只认服务端真有的档位（7d / 30d / month / all）——
// 别名表在 MCP 那边（那解决的是「LLM 传了近义词」），服务端只认 key。
func handleChargeBrief(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	period := strings.TrimSpace(r.URL.Query().Get("period"))
	if period == "" {
		period = "7d"
	}
	spec, ok := chargeRangeSpecByKey(period)
	if !ok {
		writeErr(w, http.StatusBadRequest, "period 只能是 7d / 30d / month / all")
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
		var row chargeStatRow
		if err := rows.Scan(
			&row.StartDate, &row.EnergyAdded, &row.DurationMin,
			&row.StartSoc, &row.EndSoc, &row.IsDC,
		); err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		list = append(list, row)
	}
	now := time.Now().In(loc)
	ranges := buildChargeStats(loc, now, list)
	var target *ChargeStatsRange
	for _, rg := range ranges {
		if rg.Key == period {
			target = rg
			break
		}
	}
	if target == nil {
		writeErr(w, http.StatusInternalServerError, "区间缺失")
		return
	}

	// ── 能耗段（v0.115.1）：**与充电同一个窗口** ──────────────────
	//
	// ⚠️ 窗口**从充电段的区间借**（target.WindowStart）—— **不许在这里再算一次**
	// （dayWindowStart / monthStart 只在 charge_stats.go 里出现）。
	// ⇒ 否则同一段话里两个窗口 ⇒ 用户对不上账（ADR #38）。
	//
	// ⚠️ 聚合走 [statsBucketsForWindow]（用量页**同一个函数**）⇒ 两边不会各说各话。
	var energy *ChargeBriefEnergy
	energyNote := ""
	// ⚠️ 「全部历史」这一档：桶数 = 0 ⇒ 它的边界是**数据自身首尾**（不是日历窗口）
	// ⇒ /stats 没有对应档（period 只有 today / week / month / calendar_month / year）
	// ⇒ **不并能耗段**。理由写在这里，**不塞进飞书正文**（啰嗦棘轮）。
	if n := spec.bucketCount(loc, now); n == 0 {
		energyNote = "这一档不并能耗段"
	} else {
		_, summary, err := statsBucketsForWindow(id, "day", n, tz, *target.WindowStart)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, err.Error())
			return
		}
		if energy = chargeBriefEnergyFromSummary(summary); energy == nil {
			// 窗口内没有行驶 ⇒ **不给数**。
			energyNote = "这个区间没有行驶记录。"
		}
	}

	// ── 电池健康段（v0.116.0 补记）────────────────────────────
	//
	// ⚠️ **SOH% 必须来自 [computeBatteryHealth]** —— 与 /battery 端点、
	// MCP 的 teslamate_battery **是同一个数**（ADR #38），
	// **绝不在简报里另算一套**。
	health, err := computeBatteryHealth(id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	var battery *ChargeBriefBattery
	batteryNote := ""
	if n := spec.bucketCount(loc, now); n == 0 {
		// 「全部历史」这一档：窗口边界 = **充电记录**的首尾，而电池曲线是
		// **满充点**序列 ⇒ 两者边界语义不同（末场充电的 end_date 晚于它的
		// start_date ⇒ 拿充电记录的末场当上界会误杀那一场）⇒ **不并趋势段**。
		// 「当前 SOH%」与窗口无关 ⇒ 照给。
		battery = chargeBriefBatteryFromHealth(health, nil, nil)
		if battery != nil && battery.SohPercent != nil {
			batteryNote = "这一档不并电池趋势段"
		}
	} else {
		// ⚠️ 窗口**从充电区间借**（不许在这里再算一次）。
		battery = chargeBriefBatteryFromHealth(health, target.WindowStart, target.WindowEnd)
	}

	// ── 续航段（v0.119.0 补记）：**当前值** ─────────────────────
	//
	// ⚠️ 数值**直接取** [computeRangeEstimate]（与 /range_estimate 端点、
	// App 首屏**同一个函数**）⇒ 同一个数（ADR #38 / #47 / #48）。
	// **绝不在简报里另算一套**。
	var rangeSeg *ChargeBriefRange
	rangeNote := ""
	est, err := computeRangeEstimate(id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	if est.TrueRangeKm != nil {
		rangeSeg = &ChargeBriefRange{
			TrueRangeKm:  est.TrueRangeKm,
			FloorRangeKm: est.FloorRangeKm,
			BucketLabel:  est.BucketLabel,
			SampleCount:  est.SampleCount,
		}
		// ⚠️ 区间**摊平**存（见 [ChargeBriefRange] 的说明）⇒ 要判空再取。
		if est.TrueRangeBandKm != nil {
			rangeSeg.TrueRangeLoKm = &est.TrueRangeBandKm.Lo
			rangeSeg.TrueRangeHiKm = &est.TrueRangeBandKm.Hi
		}
		if est.FloorRangeBandKm != nil {
			rangeSeg.FloorRangeLoKm = &est.FloorRangeBandKm.Lo
			rangeSeg.FloorRangeHiKm = &est.FloorRangeBandKm.Hi
		}
	} else {
		rangeNote = "续航：行程样本不足，暂不给数"
	}

	name := carDisplayName(id)
	title, body := renderChargeBrief(loc, tz, name, target, &ChargeBriefSegments{
		Energy:      energy,
		EnergyNote:  energyNote,
		Battery:     battery,
		BatteryNote: batteryNote,
		Range:       rangeSeg,
		RangeNote:   rangeNote,
	})
	writeJSON(w, &ChargeBrief{
		CarName:          name,
		PeriodLabel:      chargeBriefPeriodLabel[period],
		ServerTimezone:   tz,
		Title:            title,
		Body:             body,
		ChargeStatsRange: *target,
		Energy:           energy,
		EnergyNote:       energyNote,
		Battery:          battery,
		BatteryNote:      batteryNote,
	})
}
