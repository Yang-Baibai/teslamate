package main

import (
	"fmt"
	"net/http"
	"strconv"
	"strings"
)

// ── 电池体检报告（服务端渲染好的标题 + 正文）─────────────────────────
//
// ## 为什么要有这个端点（v0.190.0）
//
// 定时任务使命 §2.2 有一格是「**周期性健康报告**：电池健康度（SOH）变化趋势」——
// 而现在电池健康只以「数据」的形式存在：
//   · App 车况页 / 用量页有卡片（v0.189.0 起车况页也有），
//   · 飞书里能**问**（teslamate_battery）。
//
// 但「**体检报告**」这种形态还没有：没人能把一堆结论**汇成一份能直接看的短报告**。
// ⇒ 这一轮补上：**服务端渲染好的标题 + 正文**（与充电简报 /charge_brief 同一个套路）。
//
// ## 三条规矩（照抄充电简报的老规矩）
//
//  1. **判定与文案都在服务端** —— App / MCP / 推送脚本只**转述**（ADR #41）。
//     ⇒ 报告里出现的那几个数，**全部**来自 [computeBatteryHealth]
//     （与 /battery 端点、App 卡片、teslamate_battery **是同一个数**，ADR #38）。
//  2. **不新造口径** —— 每一行都是**既有措辞**的复用：
//     · 电池那行直接复用充电简报的 [renderBatteryBriefLine]；
//     · 「相对首测」「现在还能装」与 App 卡片**逐字一致**；
//     · 衰减结论用服务端那份 [decayHeadline]（v0.190.0 起客户端也透传它）；
//     · 样本与覆盖、那次落差 ⇒ 直接用服务端的 [decayNote]。
//  3. **没有样本就不给数** —— 没有满充记录 ⇒ 报告只说「还没有满充记录」，
//     不拿别的数凑（反幻觉）。

// BatteryReport 电池体检报告（服务端渲染好的标题 + 正文 + 结构化数字）。
type BatteryReport struct {
	CarName        string `json:"car_name"`
	ServerTimezone string `json:"server_timezone"`
	// Title / Body 是**服务端渲染的原文** —— 客户端与 MCP 只转述（ADR #41）。
	Title string `json:"title"`
	Body  string `json:"body"`
	// ── 结构化数字（供 MCP / 契约自检逐项核对）─────────────────
	// ⚠️ 全部来自 [computeBatteryHealth] ⇒ 与 /battery 端点同一个数。
	HealthPercent           *float64            `json:"health_percent"`
	HealthPercentOfBaseline *float64            `json:"health_percent_of_baseline"`
	CurrentCapacityKwh      *float64            `json:"current_capacity_kwh"`
	Measurement             *BatteryMeasurement `json:"measurement"`
	Decay                   *BatteryDecay       `json:"decay"`
	SpanDays                *int                `json:"span_days"`
	SpanKm                  *float64            `json:"odometer_span_km"`
	// ⚠️ **v0.202.0**：报告正文里的**趋势行**（服务端渲染的原文）＋**点数**。
	//
	// ⚠️ 为什么结构化字段里**不放整个序列**：趋势点已经在 /battery 里
	//   （MCP 的 teslamate_battery 直接透传）⇒ 报告这里只留**一行**与**点数**。
	TrendLine   string `json:"trend_line"`
	TrendPoints int    `json:"trend_points"`
}

// renderBatteryReport 渲染体检报告的标题与正文（纯函数，可单测）。
//
// ⚠️ **一行一个事实** ⇒ 每行都不许太长（ⓘ 的行宽上限 96 是同一个数）。
func renderBatteryReport(carName string, h *BatteryHealth) (string, string) {
	if carName == "" {
		carName = "车辆"
	}
	title := "🔋 " + carName + " · 电池体检报告"
	if h == nil {
		return title, "还没有电池健康数据。"
	}
	lines := []string{}

	// ① 电池健康（相对出厂）—— **复用充电简报那一行**（逐字一致）。
	//    ⚠️ 「当前」两个字必须保留：那是**最近一次充电**的实测，不是此刻的读数。
	var seg *ChargeBriefBattery
	if h.HealthPercent != nil {
		seg = &ChargeBriefBattery{SohPercent: h.HealthPercent}
		if h.Measurement != nil {
			seg.NoteShort = h.Measurement.NoteShort
		}
	}
	if line := renderBatteryBriefLine(seg); line != "" {
		lines = append(lines, line)
	}
	// ② 相对首测（趋势那一把尺子）—— 与 App 卡片**逐字一致**的标签。
	if h.HealthPercentOfBaseline != nil {
		lines = append(lines, fmt.Sprintf("相对首测 %.1f%%", *h.HealthPercentOfBaseline))
	}
	// ③ 现在还能装多少度（**实测**，不是此刻的读数 ⇒ 上面那行已经说了是哪次充电）。
	if h.CurrentCapacityKwh != nil {
		lines = append(lines, fmt.Sprintf("现在还能装 %.1f 度", *h.CurrentCapacityKwh))
	}
	// ④ 衰减结论（服务端那句，v0.190.0 起全局唯一一份）。
	if h.Decay != nil {
		if h.Decay.Headline != "" {
			lines = append(lines, h.Decay.Headline)
		}
		// ⑤ 样本与覆盖 + 那次落差 —— **直接用服务端那份 note**（逐字）。
		//    ⚠️ note 里有一个空行（两段）⇒ 拆成两行，不留空行。
		for _, l := range strings.Split(h.Decay.Note, "\n") {
			l = strings.TrimSpace(l)
			if l != "" {
				lines = append(lines, l)
			}
		}
	}
	// ⑥ 充电习惯与累计循环 —— **服务端渲染好的那一句**（App 卡片印的是同一句）。
	if h.Usage != nil && h.Usage.Line != "" {
		lines = append(lines, h.Usage.Line)
	}
	// ⑦ **充电习惯**（v0.196.0）—— 也是服务端那一句（App 卡片与体检报告同一句）。
	if h.Usage != nil && h.Usage.UsageLine != "" {
		lines = append(lines, h.Usage.UsageLine)
	}
	// ⑧ **v0.202.0**：**趋势行**（首末两个实测点）——
	//
	// ⚠️ 使命书 §2.2 的「周期性健康报告」原文就写着「电池健康度（SOH）**变化趋势**」
	//   ⇒ 每月推给用户的那条消息**本来就该带趋势**，而不是只有几个数字。
	// ⚠️ **只报事实**：起点与终点两个实测值 ⇒ 不写「掉了多少」「该不该换」
	//   （那类判断只有服务端的衰减结论能说，见上面第 ④ 行）。
	if line := batteryTrendLine(h.TrendPoints); line != "" {
		lines = append(lines, line)
	}
	if len(lines) == 0 {
		// ⚠️ 什么都没有 ⇒ **不编**（反幻觉）：只说清「还没有满充记录」。
		return title, "这台车还没有满充记录，电池健康给不出数。"
	}
	return title, strings.Join(lines, "\n")
}

// handleBatteryReport GET /api/v1/cars/{id}/battery_report
//
// ⚠️ **v0.190.0 新增**：电池体检报告（服务端渲染好的标题 + 正文）。
func handleBatteryReport(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	// ⚠️ **必须**复用 [computeBatteryHealth] —— 与 /battery 端点、App 卡片、
	// teslamate_battery **是同一个数**（ADR #38：同一事实只留一处）。
	// ⚠️ 与 /battery 端点**同一个函数** ⇒ 报告里的数与 App 卡片必然一致（ADR #38）。
	h, err := computeBatteryHealthWithUsage(id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	tz := getenv("TZ_NAME", "Asia/Shanghai")
	name := carDisplayName(id)
	title, body := renderBatteryReport(name, h)
	writeJSON(w, &BatteryReport{
		CarName:                 name,
		ServerTimezone:          tz,
		Title:                   title,
		Body:                    body,
		HealthPercent:           h.HealthPercent,
		HealthPercentOfBaseline: h.HealthPercentOfBaseline,
		CurrentCapacityKwh:      h.CurrentCapacityKwh,
		Measurement:             h.Measurement,
		Decay:                   h.Decay,
		SpanDays:                h.SeriesSpanDays,
		SpanKm:                  h.OdometerSpanKm,
		// ⚠️ **v0.202.0**：报告正文里的趋势行（首末两个实测点）＋点数。
		TrendLine:   batteryTrendLine(h.TrendPoints),
		TrendPoints: len(h.TrendPoints),
	})
}

// batteryReportCapacityKwh 报告里的「还能装多少度」（纯函数，给单测用）。
func batteryReportCapacityKwh(h *BatteryHealth) string {
	if h == nil || h.CurrentCapacityKwh == nil {
		return ""
	}
	return strconv.FormatFloat(*h.CurrentCapacityKwh, 'f', 1, 64)
}

// batteryReportHasData 这份数据够不够撑起一份报告（纯函数，给单测用）。
func batteryReportHasData(h *BatteryHealth) bool {
	if h == nil {
		return false
	}
	return h.HealthPercent != nil || h.CurrentCapacityKwh != nil || h.Decay != nil
}
