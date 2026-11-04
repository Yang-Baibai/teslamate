package main

// ---------- 电池健康度的「时间诚实性」 ----------
//
// ## 为什么要这个文件
//
// 「现在还能装 65.3 度」「当前 83.3%」这两个说法，都**没有说是哪一次充电测的**。
//
// 而它们的来源是明确的：sqlBattery 取**所有充到 90% 以上**的充电会话，
// 最后一个有容量的点就是「现在还能装」那个数（见 computeBatteryHealth）。
//
// ⇒ 那个数其实是**上一次充电**的折算满电容量，不是「此刻」的读数。
// ⇒ 车停着不动、用户两个月没充电时，「现在还能装」就是把**旧值当现在讲**
//    （项目红线第 2 条：宁可说「不知道」，不可编）。
//
// ## 分工（ADR #38 / #41）
//
//   · **服务端**算「这个数有多旧」并**生成中文口径**（本文件）；
//   · App / Hermes / 简报**只读**那一句，不自己算日期、不自己拼措辞。
//
// ⚠️ 阈值只在这里出现一次（客户端不许抄 —— Hermes 闸门 14 机器核对）。

import (
	"fmt"
	"time"
)

const (
	// batteryMeasurementFreshDays 「新鲜期」：测出来多少天之内还算「刚测的」。
	//
	// ⚠️ 取 7 天：本机实测满充间隔在 2~7 天之间（2026-10-22 / 10-24 / 10-31 三次），
	// 7 天之内说明用户在正常用车 ⇒ 这个数确实代表「车现在的状态」。
	batteryMeasurementFreshDays = 7

	// batteryMeasurementRefreshDays 超过这个天数，口径里**建议**再充一次刷新。
	//
	// ⚠️ 为什么 30 天：一个月不充电 ⇒ 那个数几乎肯定已经不代表现在的状态，
	// 而「数据偏旧」这四个字还太轻 —— 要明说「满充一次可刷新」。
	batteryMeasurementRefreshDays = 30
)

// batteryMeasurement 算「这个数是什么时候测的」（纯函数，可单测）。
//
// 参数：
//   - at：那一次充电的结束时刻（nil = 没有测量点 ⇒ 不知道，不编）
//   - now：当前时刻
//
// ⚠️ **返回 nil 时调用方必须当成「不知道」** —— 不是「正常」，
// 更不能当成「新鲜」（那是把缺数据当好消息，v0.9.2 踩过同款坑）。
func batteryMeasurement(at *time.Time, now time.Time) *BatteryMeasurement {
	if at == nil {
		return nil
	}
	m := &BatteryMeasurement{At: at}

	// ⚠️ 负数（时钟回拨 / 数据来自未来）⇒ 当作 0 天，不说「负 1 天」。
	age := int(now.Sub(*at).Hours() / 24)
	if age < 0 {
		age = 0
	}
	m.AgeDays = &age

	// ⚠️ 跨年时写完整日期 —— 只写「10-31」会让人以为是**今年**的 10-31。
	day := at.Format("01-02")
	if at.Year() != now.Year() {
		day = at.Format("2006-01-02")
	}

	switch {
	case age == 0:
		m.Note = "基于今天那次充电实测"
	case age <= batteryMeasurementFreshDays:
		m.Note = fmt.Sprintf("基于 %s 那次充电实测（%d 天前）", day, age)
	default:
		m.Note = fmt.Sprintf("基于 %s 那次充电实测（%d 天前）· 数据偏旧", day, age)
		if age > batteryMeasurementRefreshDays {
			m.Note += "，满充一次可刷新"
		}
	}
	m.Stale = age > batteryMeasurementFreshDays

	// ⚠️ 短版给简报正文与 App 的副标题行 —— 长版太长，那两处放不下。
	//    短版**只保留事实**（哪天测的 + 偏不偏旧），不含建议。
	m.NoteShort = day + " 实测"
	if m.Stale {
		m.NoteShort += " · 数据偏旧"
	}
	return m
}
