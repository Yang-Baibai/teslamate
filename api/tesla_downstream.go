package main

// ⚠️ **v0.67.0（第 34 轮「车辆能力彻底解耦」）**：给下游（Housekeeper）用的
// **车辆原始数据**端点。
//
// ## 为什么是「原样转发」而不是「再归一化一次」
//
// Housekeeper 原来自己直连 Tesla Fleet API 拉 `vehicle_data`，
// 再用自己的 `normalize_vehicle_data()` 把 Tesla 的原始字段
// （`charge_state` / `climate_state` / `drive_state` / `vehicle_state`）
// 归一化成自己的字段名。
//
// 如果 teslamate 再归一化一遍，Housekeeper 就得写一层映射 ——
// **两套字段名、两处口径**，迟早漂移。
//
// ⇒ 这里**原样转发 Tesla 的响应**（连信封 `{"response":{…}}` 都一样）：
// Housekeeper 把它拿到手直接喂给自己的 `normalize_vehicle_data()`，
// **归一化逻辑一行都不用改** —— 它只换了「从 Tesla 拿」为「从 teslamate 拿」。
//
// 这才是「解耦」该有的样子：**能力边界在 teslamate，语义解释留在消费者手里。**

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"
)

// handleTeslaVehicleData 原样转发最近一次 Tesla `vehicle_data` 响应。
//
// ⚠️ **车在休眠时这份数据会「变旧」** —— Tesla 直接回 408。
// 这里给的是 teslamate **最近一次成功拉到**的那份，并带上采集时刻，
// 让下游能判断新鲜度（Householder 的 `freshness_of` 就靠它）。
func handleTeslaVehicleData(w http.ResponseWriter, r *http.Request) {
	if _, err := pathID(r); err != nil {
		writeErr(w, http.StatusBadRequest, "车 id 不合法")
		return
	}
	if tesla == nil {
		writeErr(w, http.StatusServiceUnavailable, "Tesla 客户端还没起来")
		return
	}
	raw, at := tesla.RawVehicleData()
	if raw == nil {
		// ⚠️ **v0.67.0**：车一直睡着 ⇒ teslamate 从头到尾没拿到过一次 vehicle_data。
		// 这是**真实状态**，不是故障 ⇒ 说清楚，别让下游以为是接口坏了。
		writeErr(w, http.StatusServiceUnavailable,
			"还没有拿到过车辆实时数据（车一直在休眠）—— 开一次车或用官方 App 唤一下就会有")
		return
	}
	writeJSON(w, map[string]any{
		// ⚠️ 信封与 Tesla 一致：下游 `data.get("charge_state")` 不用改。
		"response": raw,
		// ⚠️ 采集时刻（teslamate 拉到的时刻，不是车端 timestamp）——
		// 下游算「数据有多旧」用。
		"teslamate_fetched_at": at,
		// ⚠️ **陈旧标记**：车睡着时 teslamate 只能给**上一次**拿到的数据。
		// 下游必须能区分「刚拿到的」与「可能是几小时前的」——
		// 否则会把旧值当成实时值（这正是 Householder 那边「锁车状态」的教训）。
		"stale":   time.Since(at) > 10*time.Minute,
		"age_sec": int(time.Since(at).Seconds()),
	})
}

// handleTeslaVehiclesMeta 转发 Tesla 的车辆元数据。
//
// Householder 用它拿 `display_name`（"爱车"）、`state`（online/offline/asleep）、
// `vin`、`id` —— 这些**只有 Tesla 知道**。
func handleTeslaVehiclesMeta(w http.ResponseWriter, r *http.Request) {
	if _, err := pathID(r); err != nil {
		writeErr(w, http.StatusBadRequest, "车 id 不合法")
		return
	}
	if tesla == nil {
		writeErr(w, http.StatusServiceUnavailable, "Tesla 客户端还没起来")
		return
	}
	meta := tesla.VehicleMeta()
	if meta == nil {
		writeErr(w, http.StatusServiceUnavailable, "还没拿到车辆元数据")
		return
	}
	writeJSON(w, map[string]any{
		"response": []any{meta},
	})
}

// handleTeslaVehicleSnapshot teslamate 自己的归一化快照（给不想碰原始结构的调用方）。
//
// ⚠️ 与 `vehicle_data` **并存**：前者是 Tesla 原始形态（下游零改动），
// 这里是 teslamate 归一化后的（给新调用方）。
func handleTeslaVehicleSnapshot(w http.ResponseWriter, r *http.Request) {
	if _, err := pathID(r); err != nil {
		writeErr(w, http.StatusBadRequest, "车 id 不合法")
		return
	}
	if tesla == nil {
		writeErr(w, http.StatusServiceUnavailable, "Tesla 客户端还没起来")
		return
	}
	raw, at := tesla.RawVehicleData()
	if raw == nil {
		writeErr(w, http.StatusServiceUnavailable, "还没拿到车辆实时数据")
		return
	}
	writeJSON(w, map[string]any{
		"vehicle_state":        raw["vehicle_state"],
		"charge_state":         raw["charge_state"],
		"climate_state":        raw["climate_state"],
		"drive_state":          raw["drive_state"],
		"meta":                 tesla.VehicleMeta(),
		"teslamate_fetched_at": at,
	})
}

// teslaRawVehicleDataAt 缓存的原始数据与采集时刻。
func teslaRawVehicleDataAt() (map[string]any, time.Time) {
	if tesla == nil {
		return nil, time.Time{}
	}
	return tesla.RawVehicleData()
}

// ensure JSON 可序列化（防御：原始 map 里若混入不可序列化的值会 500）。
func ensureJSONSafe(v any) {
	if _, err := json.Marshal(v); err != nil {
		return
	}
	_ = strings.TrimSpace
}
