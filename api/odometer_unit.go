package main

// ---------- 车端里程表：官方给的是**英里**，换算只在服务端一份（v0.127.0） ----------
//
// ## 为什么要这个文件
//
// 官方 `vehicle_state.odometer`（Fleet 轮询）与遥测 `Odometer`（车主动推）
// 给的都是**英里（mile）**，而本项目全链路用**公里**。
//
// ⚠️ **v0.127.0 之前，本项目把英里当公里用了十几个版本**：
//
//   · App 显示「里程表 63,055 公里」（真值约 101,467 公里 ⇒ **少了 38,412 公里**）；
//   · 保养超期判定用的是同一个错数 ⇒ **该保养时不报**（漏报方向，比误报更隐蔽）。
//
// ⚠️ 而 v0.75.0 那条「GPS 累计值偏 38,422 公里」的结论**本身也是单位错误**：
//
//	63,055.1 英里 = 101,477.35 公里
//	同一天 TeslaMate 的 positions.odometer = 101,477.41 公里
//	⇒ 两者只差 **0.06 公里**，不是 38,422 公里。
//
// ⇒ 那两个数本来就是**同一个数**，差的只是单位。
//
// ## 分工（ADR #38 / #41）
//
//   · **服务端**换算并生成中文口径（本文件）；
//   · App / Hermes **只透传** —— 不许自己乘 1.609344、不许自己拼「（车端）」。

import (
	"context"
	"fmt"
	"time"
)

// milesPerKm 1 英里 = 1.609344 公里（国际法定换算）。
//
// ⚠️ 全项目**只此一处**（客户端闸门会扫客户端有没有抄这个数）。
const milesPerKm = 1.609344

// milesToKm 英里 → 公里。
func milesToKm(mi float64) float64 {
	return mi * milesPerKm
}

// odometerKmFromMiles 官方原始值（英里）→ 公里。
//
// ⚠️ nil = 没读到，**不是 0**（0 公里的车不存在，压成 0 会显示一眼假的数）。
func odometerKmFromMiles(mi *float64) *float64 {
	if mi == nil {
		return nil
	}
	km := milesToKm(*mi)
	return &km
}

// serverLocation 服务端时区（默认 Asia/Shanghai）。
//
// ⚠️ **为什么不用 time.Local**：容器里 time.Local 是 UTC
// （alpine 没有 /etc/localtime）⇒ 按 UTC 日历算日期会与用户看到的差一天
// （v0.98.0 的 8 小时同款坑）。
//
// ⚠️ 电池那套（batteryLocation）也走这里 ⇒ 时区只有一个事实源。
func serverLocation() *time.Location {
	loc, err := time.LoadLocation(getenv("TZ_NAME", "Asia/Shanghai"))
	if err != nil {
		// 兜底：中国全境 UTC+8 且无夏令时 ⇒ 固定偏移是安全的。
		return time.FixedZone("CST", 8*3600)
	}
	return loc
}

// VehicleOdometer 车端里程表的一份结论。
type VehicleOdometer struct {
	// Km 公里（**已换算**）；nil = 没读到。
	Km *float64
	// Source 来源：poll（Fleet 轮询）/ telemetry（车主动推）/ ""（都没有）。
	Source string
	// At 采集时刻（车端上报的时刻）。
	At *time.Time
	// Stale 这个读数是不是「车断线前的最后已知值」。
	Stale bool
}

// Known 有没有读到车端里程表。
func (o VehicleOdometer) Known() bool {
	return o.Km != nil && *o.Km > 0
}

// pickNewerOdometer 在「轮询」与「遥测」两份里挑**更新的**那个。
//
// ⚠️ 规矩与 pickNewerTPMS 一致：不是无条件「遥测优先」——
// 遥测的值可能是断线前的最后已知值，轮询的值是上一次成功拉取的
// ⇒ 按采集时刻取新的那个，才不会把旧的当新的。
//
// ⚠️ 一边「不知道」时直接用另一边 —— 读不到 ≠ 0。
func pickNewerOdometer(pollKm *float64, pollAt *time.Time, telKm *float64, telAt *time.Time) (*float64, string, *time.Time) {
	pollOK := pollKm != nil && *pollKm > 0
	telOK := telKm != nil && *telKm > 0
	switch {
	case !pollOK && !telOK:
		return nil, "", nil
	case !telOK:
		return pollKm, "poll", pollAt
	case !pollOK:
		return telKm, "telemetry", telAt
	}
	if telAt != nil && (pollAt == nil || telAt.After(*pollAt)) {
		return telKm, "telemetry", telAt
	}
	return pollKm, "poll", pollAt
}

// telemetryOdometer 遥测里的车端里程表（**原始单位是英里**）。
//
// ⚠️ 返回的是**原始值** —— 换算由调用方用 [odometerKmFromMiles] 做，
// 这样「单位」这件事只有一个地方能改。
func telemetryOdometer(ctx context.Context, vin string) *telemetryFieldView {
	f, err := loadTelemetryField(ctx, vin, "Odometer")
	if err != nil || f == nil || f.Num == nil {
		return nil
	}
	return f
}

// resolveVehicleOdometer 取车端里程表（轮询 + 遥测两份里挑更新的那个）。
//
// ⚠️ **单一事实源**：`/status` 与保养判定都走这里 ⇒ 两处不会各说各的。
func resolveVehicleOdometer(ctx context.Context, carID int) VehicleOdometer {
	var pollKm *float64
	var pollAt *time.Time
	if tesla != nil {
		pollKm = teslaOdometer()
		pollAt = teslaOdometerAt()
	}
	var telKm *float64
	var telAt *time.Time
	telStale := false
	if vin, err := carVIN(ctx, carID); err == nil {
		if f := telemetryOdometer(ctx, vin); f != nil {
			telKm = odometerKmFromMiles(f.Num)
			telAt = f.At
			telStale = f.Stale
		}
	}
	km, source, at := pickNewerOdometer(pollKm, pollAt, telKm, telAt)
	return VehicleOdometer{
		Km:     km,
		Source: source,
		At:     at,
		Stale:  source == "telemetry" && telStale,
	}
}

// renderOdometerNote 出「里程表」那一行的**限定语**（服务端唯一一份文案，ADR #41）。
//
// ⚠️ 不许暗示方向（趋势 / ↑ ↓ →）—— 这里只报事实。
// ⚠️ 日期按**服务端时区**看（容器 time.Local 是 UTC ⇒ 会差一天，v0.98.0 同款坑）。
func renderOdometerNote(odo VehicleOdometer, loc *time.Location, now time.Time) string {
	if !odo.Known() {
		// 车端读不到 ⇒ 界面只能给 TeslaMate 那份。
		// ⚠️ 那份**也是车的里程表**（同一个官方字段换算来的），
		// 但历史上出现过单日跨度 1,143 公里的采样噪声 ⇒ 只能说「估算」。
		return "（GPS 估算）"
	}
	if loc == nil {
		loc = serverLocation()
	}
	day := ""
	if odo.At != nil {
		d := odo.At.In(loc)
		day = d.Format("01-02")
		if d.Year() != now.In(loc).Year() {
			day = d.Format("2006-01-02")
		}
	}
	if day == "" {
		return "（车端）"
	}
	return fmt.Sprintf("（车端 · %s 读数）", day)
}
