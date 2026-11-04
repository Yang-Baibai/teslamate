package main

// ---------- 座舱闷热：判定与文案都在服务端（v0.129.0） ----------
//
// ## 为什么有这个
//
// 本机实测（2026-11-04 查库，71 天有采样的日子）：
//
//   · 座舱峰值 ≥ 45℃ 的有 **17 天（24%）**；
//   · 最高 **62.4℃**（2026-09-29，**车外才 34.0℃**）。
//
// ⇒ 暴晒后车内比车外高 20~30℃ 是这台车的**常态**，不是偶发。
//
// ## 为什么**不做主动推送**
//
// 暴晒后的座舱温度**只有在车被唤醒时才能读到**（车睡着时传感器不报）
// ⇒ 能读到的那一刻，**用户已经在车旁边了**
// ⇒ 「提前预警」没有数据源；而 App / 飞书正好在那一刻能被打开
// ⇒ 正确的出口是 **App 的座舱卡**（上车前那一秒），不是推送。
//
// ## 分工（ADR #38 / #41）
//
//   · **服务端**判「闷不闷热」并**生成中文文案**（本文件）；
//   · App / Hermes **只透传**（不许自己比阈值、不许自己写文案）。

import (
	"fmt"
	"time"
)

// cabinHotC 「座舱闷热」的阈值（℃）。
//
// ⚠️ 依据是**本机实测**，不是拍的：71 天里有 **17 天（24%）**座舱峰值 ≥ 45℃，
// 最高 62.4℃（车外 34.0℃）⇒ 这是常态 ⇒ 取 45℃：
// 既覆盖「明显闷热」，又不会天天都在报（实测 ≥ 50℃ 的只有 9 天）。
const cabinHotC = 45.0

// CabinHeat 座舱闷热的**服务端结论**。
type CabinHeat struct {
	// Hot 现在闷不闷热。
	Hot bool `json:"hot"`
	// InsideC 车内温度（℃）；nil = 没读到。
	InsideC *float64 `json:"inside_c"`
	// OutsideC 车外温度（℃）；nil = 没读到。
	OutsideC *float64 `json:"outside_c"`
	// Text 闷热时的那句**中文原文**（服务端唯一一份，ADR #41）；不闷热时为空。
	Text string `json:"text"`
	// At 读数时刻（车况数据的时间）。
	At *time.Time `json:"at"`
}

// cabinHeat 判「座舱闷热」并出文案（纯函数，可单测）。
//
// 参数：
//   - insideC：车内温度（℃）
//   - outsideC：车外温度（℃），可 nil（没读到）
//   - at：读数时刻
//   - stale：车况数据是否**陈旧**（陈旧 ⇒ 不给结论，宁可说不知道）
//
// ⚠️ **不暗示方向**（趋势 / ↑ ↓ →）—— 这里只报事实与该做的事。
// ⚠️ **温度读数差 0.1℃ 就换算成「度」**（不是「°C」）：界面那一行用「℃」，
//
//	这里跟着同一口径，别一处一种写法。
func cabinHeat(insideC, outsideC *float64, at *time.Time, stale bool) *CabinHeat {
	if insideC == nil || stale {
		// 读不到 / 数据太旧 ⇒ **不知道**（红线第 2 条：宁可说不知道，不可编）。
		return nil
	}
	ch := &CabinHeat{
		Hot:      *insideC >= cabinHotC,
		InsideC:  insideC,
		OutsideC: outsideC,
		At:       at,
	}
	if !ch.Hot {
		return ch
	}
	if outsideC != nil {
		ch.Text = fmt.Sprintf("车内 %.1f℃，车外 %.1f℃ · 上车前先开门窗散热", *insideC, *outsideC)
	} else {
		ch.Text = fmt.Sprintf("车内 %.1f℃ · 上车前先开门窗散热", *insideC)
	}
	return ch
}
