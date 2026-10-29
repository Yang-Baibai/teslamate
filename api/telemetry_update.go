package main

// --------------------------------------------------------------------------
// 遥测侧的「固件版本」与「待安装的固件版本」（v0.84.0）
//
// ⚠️ **反幻觉铁律**：两个字段名与编号都逐字来自官方
// `protos/vehicle_data.proto` 的 `enum Field`：
//
//	Version               = 68
//	SoftwareUpdateVersion = 220
//
// 官方「Available Data」表的 related field 列：
//
//	Version               → vehicle_state.car_version
//	SoftwareUpdateVersion → （无 —— 它只存在于遥测侧）
// --------------------------------------------------------------------------

import "strings"

// telemetryFirmwareVersion 取遥测 `Version`（官方编号 68）里的当前固件版本。
//
// ⚠️ 官方原文：
//
//	"The current firmware version of the vehicle."
//
// ⚠️ 同一张表的 `Version` 条目还写着：
//
//	"In firmware versions earlier than 2024.44, this field returned the version
//	 of an available software update."
//
// ⇒ 2024.44 之前它报的是「可下载的更新版本」而不是当前固件。
//
//	本车固件 2026.26.200 ⇒ 不受影响；但结论要写清楚，别含糊。
//
// ⚠️ 拿不到就返回空串 —— 调用方要能分清「不知道」与「有值」。
func telemetryFirmwareVersion(idx map[string]telemetryFieldView) string {
	f, ok := idx["Version"]
	if !ok {
		return ""
	}
	return strings.TrimSpace(f.Text)
}

// telemetryUpdateVersion 取遥测 `SoftwareUpdateVersion`（官方编号 220）。
//
// ⚠️ 官方原文：
//
//	"The version of an available software update."
//
// ⚠️ 官方**没有**写「没有可用更新时这个字段报什么」⇒
//
//	拿不到就返回空串，**不能**自己假定它等于当前版本。
func telemetryUpdateVersion(idx map[string]telemetryFieldView) string {
	f, ok := idx["SoftwareUpdateVersion"]
	if !ok {
		return ""
	}
	return strings.TrimSpace(f.Text)
}

// updatePending 判断「**真的**有可安装的新固件」。
//
// ⚠️ 必须**两个版本号都拿到**且**不相等**才算有：
//
//   - 只有一个拿到 ⇒ **不知道**（不能说「有更新」，也不能说「没有」）
//   - 两个都拿到且相等 ⇒ 没有待安装的更新（车已经是这个版本）
//   - 两个都拿到且不等 ⇒ **有**待安装的更新
//
// ⚠️ 「不相等」这条判据是必须的：官方没写「没有更新时这个字段报什么」，
//
//	所以**不能**把「字段存在」当成「有更新」（那会天天误报）。
func updatePending(current, available string) bool {
	if current == "" || available == "" {
		return false
	}
	return current != available
}
