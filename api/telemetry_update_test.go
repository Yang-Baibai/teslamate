package main

import "testing"

// ⚠️ **v0.84.0：固件版本 / 待安装固件版本的解析与判定**。
//
// 这组用例守的是两个最容易出错的地方：
//
// ① **「没有更新」与「不知道」必须分开** ——
//    官方没写「没有可用更新时 `SoftwareUpdateVersion` 报什么」，
//    所以拿不到就只能说「不知道」，不能自己假定它等于当前版本。
// ② **「有更新」的判据是「两个版本号都拿到且不相等」** ——
//    把「字段存在」当成「有更新」会天天误报。

func TestTelemetryFirmwareVersion(t *testing.T) {
	idx := map[string]telemetryFieldView{
		"Version": {Field: "Version", Text: "2026.26.200.11"},
	}
	if got := telemetryFirmwareVersion(idx); got != "2026.26.200.11" {
		t.Fatalf("期望 2026.26.200.11，实际 %q", got)
	}
	// 没有这个字段 ⇒ 空串（= 不知道），**不能**编一个版本号出来。
	if got := telemetryFirmwareVersion(map[string]telemetryFieldView{}); got != "" {
		t.Fatalf("缺字段时期望空串，实际 %q", got)
	}
}

func TestTelemetryUpdateVersion(t *testing.T) {
	idx := map[string]telemetryFieldView{
		"SoftwareUpdateVersion": {Field: "SoftwareUpdateVersion", Text: "2026.32.400"},
	}
	if got := telemetryUpdateVersion(idx); got != "2026.32.400" {
		t.Fatalf("期望 2026.32.400，实际 %q", got)
	}
	if got := telemetryUpdateVersion(map[string]telemetryFieldView{}); got != "" {
		t.Fatalf("缺字段时期望空串，实际 %q", got)
	}
}

func TestUpdatePending(t *testing.T) {
	cases := []struct {
		name     string
		current  string
		avail    string
		expected bool
	}{
		{"两个都没有 ⇒ 不知道", "", "", false},
		{"只有当前版本 ⇒ 不知道", "2026.26.200.11", "", false},
		{"只有待装版本 ⇒ 不知道", "", "2026.32.400", false},
		{"两个相等 ⇒ 没有待安装的更新", "2026.26.200.11", "2026.26.200.11", false},
		{"两个不等 ⇒ 有待安装的更新", "2026.26.200.11", "2026.32.400", true},
	}
	for _, c := range cases {
		if got := updatePending(c.current, c.avail); got != c.expected {
			t.Errorf("%s：期望 %v，实际 %v", c.name, c.expected, got)
		}
	}
}

// ⚠️ 官方编号必须逐字对得上（68 / 220）——
// 抄错编号的后果是「以后有人按编号反查时对不上」。
func TestTelemetryUpdateFieldNumbers(t *testing.T) {
	if got := telemetryOfficialFieldNumbers["Version"]; got != 68 {
		t.Errorf("官方编号：Version 应为 68，实际 %d", got)
	}
	if got := telemetryOfficialFieldNumbers["SoftwareUpdateVersion"]; got != 220 {
		t.Errorf("官方编号：SoftwareUpdateVersion 应为 220，实际 %d", got)
	}
}
