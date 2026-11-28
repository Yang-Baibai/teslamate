package main

import (
	"fmt"
	"sort"
	"strconv"
)

// ---------- 电池趋势图（v0.199.0） ----------
//
// ## 为什么要有这张图
//
// 用户的方向是「检测电池健康」⇒ 数字之外还要**看得见形状**：
// 「现在还能装 65.4 度」是**此刻**的数，而「有没有在掉」得看**一路上的点**。
//
// ## 关键：图与判定**同一批点**
//
// 图上的点 = 服务端判定衰减用的**严格档**（充到 100%、有容量、有里程）
// ⇒ 图上看到的形状 **就是** 结论依据的形状 ⇒ 界面与结论**不会自相矛盾**。
//
// ⚠️ 客户端**不许自己挑点**（客户端闸门 59 钉住）——
// 「哪些点算数」是**口径**（ADR #38），不是排版。
//
// ## 措辞
//
// 图里**不印任何文字**（数字卡上已有）⇒ 只有一条 ⓘ 口径说明
// ⇒ 它是 **ⓘ 文案**（上限 noteLineWidthLimit = 96）
// ⇒ 定义在本模块（与 [decayMethodTmpl] 同一套做法：ⓘ 文案放模块里，不放 app_copy.go）。

// batteryTrendLineTmpl 体检报告正文里的**趋势行**（服务端渲染的原文）。
//
// ⚠️ 这是**飞书正文**里的一行（不是 App 常驻文案）⇒ 上限取 noteLineWidthLimit（96）。
// ⇒ 定义在**模块里**（与 [decayMethodTmpl] 同一套做法：ⓘ / 报告正文文案放模块，
//
//	不放 app_copy.go —— 那里只放「App 用独立 Text(...) 直接印的常驻文案」）。
//
// ⚠️ 措辞**只报事实**：起点与终点两个实测值，不写「掉了多少」「该不该换」。
const batteryTrendLineTmpl = "实测容量：%s 度（%d 公里）→ %s 度（%d 公里）"

// batteryTrendLine 渲染体检报告正文里的**趋势行**（纯函数，可单测）。
//
// ⚠️ 少于 2 个点 ⇒ **不给这行**（一个点说明不了趋势 —— 与「图不画」同一套规矩）。
func batteryTrendLine(points []*BatteryTrendPoint) string {
	if len(points) < 2 {
		return ""
	}
	first := points[0]
	last := points[len(points)-1]
	return fmt.Sprintf(batteryTrendLineTmpl,
		strconv.FormatFloat(first.Kwh, 'f', 1, 64), int(first.Km),
		strconv.FormatFloat(last.Kwh, 'f', 1, 64), int(last.Km))
}

// batterySeriesNote 趋势图的**口径说明**（ⓘ 文案；服务端唯一一份，ADR #41）。
//
// ⚠️ 这一句是「**图怎么读**」的说明书 ⇒ 放在服务端 ⇒ App 与飞书同一句。
const batterySeriesNote = "每个点是一次充到 100% 的满充实测：横轴是里程，纵轴是实测可用容量（与服务端判定衰减用的是同一批点）"

// batteryTrendPoints 从**严格档**点里取出趋势图要画的那些点（纯函数，可单测）。
//
// ⚠️ **入参必须是服务端判定用的那批点**（[decayPoint]）
//
//	⇒ 图与结论**同一份依据** ⇒ 界面上「看到的」与「说的」永远一致。
func batteryTrendPoints(strict []decayPoint) []*BatteryTrendPoint {
	out := make([]*BatteryTrendPoint, 0, len(strict))
	for _, p := range strict {
		out = append(out, &BatteryTrendPoint{Km: p.km, Kwh: p.kwh})
	}
	// ⚠️ **按里程升序**：图要按横轴连线 ⇒ 顺序必须对。
	//   ⇒ **服务端排**（客户端只画、不排序 ⇒ 免得两边各排一次）。
	sort.Slice(out, func(i, j int) bool { return out[i].Km < out[j].Km })
	return out
}
