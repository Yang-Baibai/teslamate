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
// 定时任务使命里有一格是「**周期性健康报告**：能耗统计简报」——
// 而 App 的统计卡**推不到飞书**（用户在 App 里看得到、在飞书里问得到，
// 但没人**定时**把结论送到眼前）。
//
// ## 为什么文案放服务端（而不是脚本或 MCP 各写一份）
//
// 本仓库反复吃过「同一句话写了两遍」的亏：
//
//   · v0.76.0：「完成后是什么样」在服务端与客户端各写了一份；
//   · v0.78.0：通知里「充电完成」写死在脚本里 ⇒ 实测只充到 80% 也说「完成」（**假成功**）。
//
// ⇒ **判定与文案都在服务端**（本文件），推送脚本与 MCP 只**转述**
//   （与故障码告警一个套路：`api/telemetry.go` 给文案，脚本原样转述）。
//
// ## 数字从哪来
//
// 直接复用 [summarizeChargeRange] ⇒ 与 App 卡片是**同一份聚合**，
// 不存在「卡片一个数、简报另一个数」的可能（ADR #38 的同一事实只留一处）。
//
// ## 反幻觉三条
//
//  1. **窗口必须露出来** —— 只说「近 7 天」不说边界，用户没法核对「是哪 7 天」。
//  2. **没有样本就不给数** —— 平均时长没有样本就是 null，正文里就不写那一段。
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
func renderChargeBrief(loc *time.Location, tz string, carName string, r *ChargeStatsRange) (string, string) {
	label := chargeBriefPeriodLabel[r.Key]
	if label == "" {
		label = r.Key
	}
	if carName == "" {
		carName = "车辆"
	}
	title := fmt.Sprintf("📊 %s · %s充电简报", carName, label)

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
		lines = append(lines, fmt.Sprintf("窗口：%s → %s（服务器时区 %s）", ws, we, tz))
	}

	if r.Count == 0 {
		// 没有样本 ⇒ **不给数**，只说清「这个区间没有充电记录」。
		lines = append(lines, "这个区间没有充电记录。")
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
	return title, strings.Join(lines, "\n")
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
	if _, ok := chargeBriefPeriodLabel[period]; !ok {
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
	name := carDisplayName(id)
	title, body := renderChargeBrief(loc, tz, name, target)
	writeJSON(w, &ChargeBrief{
		CarName:          name,
		PeriodLabel:      chargeBriefPeriodLabel[period],
		ServerTimezone:   tz,
		Title:            title,
		Body:             body,
		ChargeStatsRange: *target,
	})
}
