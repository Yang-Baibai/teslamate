package main

// ---------- 遥测「流级」新鲜度：把 broker 重放与车端新消息分开 ----------
//
// ## 为什么需要它（2026-11-09 实测打出来的）
//
// MQTT 的 **retained 消息在订阅者上线时会重发** —— broker 上明明留着 98 条，
// API 容器一重启就收到 35 条（实测：2026-11-09 15:01:09 那批 34 个字段全部落地，
// 与容器启动同一秒）。
//
// ⇒ 「我们收到这条消息的时刻」**≠**「车端发出它的时刻」。
//
// ⇒ 后果（**假新鲜度**）：车端 11:40（19:40 本地）就断连了，
// 但流级「最后一条消息」显示的是 15:01（23:01 本地）——
// 那是**容器重启补落**的时刻，不是车端上报的时刻
// ⇒ 飞书里问「遥测多久没更新」，答出来的会是「刚刚」。
//
// ⇒ 字段级**早就有正解**（`telemetryFieldFreshness`：车离线 ⇒ 真实采集时刻 =
// 车断线的那个时刻），本文件把**同一套口径**推到流级 ——
// 一个口径，不许两处各算一套（ADR #38 / #41）。
//
// ## 分工
//
//   · **服务端**判「这条消息是车端新发的，还是 broker 重放的」并给诚实时刻；
//   · App / Hermes（飞书）**只透传**，不许自己拿「收到时刻」当「采集时刻」。
import (
	"strings"
	"time"
)

// telemetryOrigin 一条 MQTT 消息的来源。
type telemetryOrigin int

const (
	// telemetryOriginUnknown 我们**不知道**车端当时在不在线
	// （刚启动、还没收到连接状态）⇒ 保守当成「不是车端新消息」。
	telemetryOriginUnknown telemetryOrigin = iota
	// telemetryOriginCar 车端**在线**时发来的 ⇒ 车端新消息。
	telemetryOriginCar
	// telemetryOriginReplay 车端**离线**时收到的 ⇒ broker 重发的 retained。
	telemetryOriginReplay
)

// classifyTelemetryOrigin 判一条消息是「车端新发的」还是「broker 重放的」。
//
// 判据只有一条：**车端在线吗**。
// 车离线时它不可能发消息 ⇒ 我们收到的只能是 broker 留着的 retained。
//
// ⚠️ 未知（刚启动、还没收到连接状态）⇒ 归 Unknown ⇒ **保守**：
// 宁可少说「车端在报」，也不把重放说成新鲜（红线第 2 条：宁可说不知道，不可编）。
func classifyTelemetryOrigin(carStatusKnown, carOnline bool) telemetryOrigin {
	switch {
	case !carStatusKnown:
		return telemetryOriginUnknown
	case carOnline:
		return telemetryOriginCar
	default:
		return telemetryOriginReplay
	}
}

// telemetryStreamFreshness 由「各字段的诚实时刻」推出**流级**的诚实时刻。
//
// 参数：各 V 字段的视图（`At` / `Stale` 已由 `telemetryFieldFreshness` 算好）。
//
// 规则：
//
//	· 有字段的诚实时刻 ⇒ at = 其中**最新的那个**；
//	  stale = 这些时刻**全部**都是「车断线前的最后已知值」；
//	· 一个字段都没有 ⇒ at = nil（**不知道**）、stale = true。
//
// ⇒ 车在线时：at = 我们收到最新那批的时刻（车端刚发的）。
// ⇒ 车离线时：at = 车断线的那个时刻（**不是**我们收到 retained 的时刻）。
//
// ⚠️ 为什么取「各字段的最新」而不是「最后一次收到的消息」：
// 后者是我们**落地**的时刻（重放会把它刷新成假的），
// 前者是**车端数据本身**的时刻（重放不会改它 —— 字段级已经算好了）。
func telemetryStreamFreshness(views []telemetryFieldView) (at *time.Time, stale bool) {
	var best *time.Time
	allStale := true
	for _, v := range views {
		if v.At == nil {
			continue
		}
		if best == nil || v.At.After(*best) {
			t := *v.At
			best = &t
		}
		if !v.Stale {
			allStale = false
		}
	}
	if best == nil {
		// 一个字段都没有 ⇒ **不知道**它有多新 ⇒ 不给时刻，不冒充新鲜。
		return nil, true
	}
	return best, allStale
}

// carStatusEvent 车端连接状态的一个事件（来自 MQTT 的 connectivity 报文）。
type carStatusEvent struct {
	// Status CONNECTED / DISCONNECTED。
	Status string
	// At 这个事件发生的时刻；nil = 报文里没带 ⇒ 我们**不知道**它什么时候发生的。
	At *time.Time
}

// noteCarStatus 记一次车端连接状态事件，并回答「这是车端新消息还是重放」。
//
// ⚠️ **只认更新的事件**：broker 会在订阅者上线时重发 retained，
// 那些旧事件（更早的时刻）**不许覆盖**当前状态
// ⇒ 否则「车明明在线」会被一条重发的旧 DISCONNECTED 说成离线。
//
// ⚠️ 报文里没有时刻 ⇒ 我们不知道它什么时候发生 ⇒ **不猜**：
// 状态标成「未知」，后续消息一律保守当成重放（宁可少说，不冒充新鲜）。
func noteCarStatus(prevAt *time.Time, prevKnown bool, ev carStatusEvent) (carAt *time.Time, known bool, origin telemetryOrigin) {
	if ev.At == nil {
		// 时刻不明 ⇒ 不覆盖已有状态（那更可能是真的），只把「已知」降级为 false。
		if prevKnown {
			return prevAt, false, telemetryOriginUnknown
		}
		return nil, false, telemetryOriginUnknown
	}
	if prevKnown && prevAt != nil && !ev.At.After(*prevAt) {
		// 更旧的事件（多半是 retained 重放）⇒ 不覆盖当前状态，
		// 并且**如实**归类为重放。
		return prevAt, true, telemetryOriginReplay
	}
	return ev.At, true, telemetryOriginCar
}

// carStatusOnline 由最近一次连接状态事件判「车端在线吗」。
func carStatusOnline(status string) bool {
	return strings.EqualFold(strings.TrimSpace(status), "CONNECTED")
}
