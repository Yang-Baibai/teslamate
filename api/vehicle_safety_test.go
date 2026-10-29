package main

// --------------------------------------------------------------------------
// 车辆「当下安全状态」（车门 / 哨兵）的单测 —— v0.82.0
//
// ⚠️ 这些用例的作用不只是「跑绿」，更是**把官方口径钉死**：
//
//	门位字段名、顺序、哨兵枚举编号，只要官方定义变了，这些用例必须先红。
// --------------------------------------------------------------------------

import (
	"reflect"
	"testing"
	"time"
)

// --------------------------------------------------------------------------
// 官方字段名单
// --------------------------------------------------------------------------

// TestDoorFieldsMatchOfficialOrder 门位字段必须**逐字**与官方一致，且与
// 遥测 `message Doors` 的字段顺序**逐位对应**。
//
// ⚠️ 官方原文（Fleet Telemetry「Available Data」表 · DoorState 行，
//
//	related field 列）：vehicle_state.df vehicle_state.dr vehicle_state.pf
//	                    vehicle_state.pr vehicle_state.ft vehicle_state.rt
//
//	而遥测的 `message Doors` 字段顺序（protos/vehicle_data.proto）是：
//	DriverFront / DriverRear / PassengerFront / PassengerRear /
//	TrunkFront / TrunkRear
//
//	两边的**顺序逐位对应**本身就是官方给的对照 ⇒ 这不是我们猜的映射。
func TestDoorFieldsMatchOfficialOrder(t *testing.T) {
	wantKeys := []string{"df", "dr", "pf", "pr", "ft", "rt"}
	wantNames := []string{"主驾前门", "主驾后门", "副驾前门", "副驾后门", "前备箱", "后备箱"}

	if len(doorFields) != len(wantKeys) {
		t.Fatalf("门位数量 = %d，期望 %d", len(doorFields), len(wantKeys))
	}
	for i, f := range doorFields {
		if f.key != wantKeys[i] {
			t.Errorf("第 %d 个门位 key = %q，期望 %q", i, f.key, wantKeys[i])
		}
		if f.name != wantNames[i] {
			t.Errorf("第 %d 个门位中文名 = %q，期望 %q", i, f.name, wantNames[i])
		}
	}

	// 轮询侧与遥测侧的中文名必须**完全一致** ⇒ 两个来源的结果可以直接拼/比。
	telNames := []string{}
	for _, k := range telemetryDoorOrder {
		telNames = append(telNames, telemetryDoorNames[k])
	}
	got := []string{}
	for _, f := range doorFields {
		got = append(got, f.name)
	}
	if !reflect.DeepEqual(got, telNames) {
		t.Errorf("轮询侧门名 %v 与遥测侧门名 %v 不一致", got, telNames)
	}
}

// TestSentryStateNormalizedFromOfficialEnum 哨兵归一化状态必须**逐字**来自
// 官方 `enum SentryModeState` 的编号。
//
// ⚠️ 编号出处：protos/vehicle_data.proto
//
//	Unknown=0 / Off=1 / Idle=2 / Armed=3 / Aware=4 / Panic=5 / Quiet=6
func TestSentryStateNormalizedFromOfficialEnum(t *testing.T) {
	cases := map[string]string{
		"0": sentryStateUnknown,
		"1": sentryStateOff,
		"2": sentryStateIdle,
		"3": sentryStateArmed,
		"4": sentryStateAware,
		"5": sentryStatePanic,
		"6": sentryStateQuiet,
	}
	for code, want := range cases {
		if got := telemetrySentryStateToNormalized(code); got != want {
			t.Errorf("官方枚举 %s → %q，期望 %q", code, got, want)
		}
	}
	// 认不出来的编号 ⇒ unknown（不猜）。
	if got := telemetrySentryStateToNormalized("99"); got != sentryStateUnknown {
		t.Errorf("未知枚举 99 → %q，期望 %q", got, sentryStateUnknown)
	}
}

// --------------------------------------------------------------------------
// 轮询侧门位解析
// --------------------------------------------------------------------------

func vsRaw(m map[string]any) map[string]any {
	return map[string]any{"vehicle_state": m}
}

// TestDoorsFromRawThreeState 门位解析必须是**三态**。
func TestDoorsFromRawThreeState(t *testing.T) {
	// 全部关着 ⇒ 知道「全关」。
	all := map[string]any{
		"df": 0, "dr": 0, "pf": 0, "pr": 0, "ft": 0, "rt": 0,
	}
	if got := doorsFromRaw(vsRaw(all)); got == nil || len(got) != 0 {
		t.Errorf("六门全关 ⇒ 期望 []（知道全关），得到 %v", got)
	}

	// 有一个开着 ⇒ 知道开着哪个。
	one := map[string]any{
		"df": 0, "dr": 1, "pf": 0, "pr": 0, "ft": 0, "rt": 0,
	}
	got := doorsFromRaw(vsRaw(one))
	if !reflect.DeepEqual(got, []string{"主驾后门"}) {
		t.Errorf("dr=1 ⇒ 期望 [主驾后门]，得到 %v", got)
	}

	// 布尔形态也认（全球区有的版本给布尔）。
	boolOne := map[string]any{
		"df": false, "dr": false, "pf": false, "pr": false, "ft": true, "rt": false,
	}
	got = doorsFromRaw(vsRaw(boolOne))
	if !reflect.DeepEqual(got, []string{"前备箱"}) {
		t.Errorf("ft=true ⇒ 期望 [前备箱]，得到 %v", got)
	}

	// 只读到一部分且全关 ⇒ **不知道**（不能声称「全关」）。
	partial := map[string]any{"df": 0, "dr": 0}
	if got := doorsFromRaw(vsRaw(partial)); got != nil {
		t.Errorf("只读到 2/6 个门位且全关 ⇒ 期望 nil（不知道），得到 %v", got)
	}

	// 只读到一部分、但读到开着的那 ⇒ **确定开着**（哪怕只读到一个）。
	partialOpen := map[string]any{"df": 0, "pf": 1}
	if got := doorsFromRaw(vsRaw(partialOpen)); !reflect.DeepEqual(got, []string{"副驾前门"}) {
		t.Errorf("只读到 2/6 个门位但 pf=1 ⇒ 期望 [副驾前门]，得到 %v", got)
	}

	// 整个 vehicle_state 都没有 ⇒ 不知道。
	if got := doorsFromRaw(nil); got != nil {
		t.Errorf("没有 vehicle_state ⇒ 期望 nil，得到 %v", got)
	}
}

// TestDoorFirmwareSwapBefore 官方明说 2024.44.32 之前副驾前门/主驾后门互换。
func TestDoorFirmwareSwapBefore(t *testing.T) {
	cases := []struct {
		ver  string
		want bool
	}{
		{"2026.26.200.11", false}, // 本车实测固件 ⇒ 不换
		{"2026.26.200", false},
		{"2025.1.1", false},
		{"2024.44.32", false}, // 「prior to」是严格小于 ⇒ 恰好 2024.44.32 不换
		{"2024.44.31", true},
		{"2024.44.8", true},
		{"2024.26.200", true},
		{"2023.38.1", true},
		{"2021.1.1", true},
		{"", false},        // 读不到 ⇒ 不换（宁可不对调）
		{"abc", false},     // 解析不了 ⇒ 不换
		{"2024.44", false}, // 段数不够 ⇒ 不换
	}
	for _, c := range cases {
		if got := doorFirmwareSwapBefore(c.ver); got != c.want {
			t.Errorf("doorFirmwareSwapBefore(%q) = %v，期望 %v", c.ver, got, c.want)
		}
	}
}

// TestSwapDoorLabels 只对调「副驾前门」与「主驾后门」，其余不动。
func TestSwapDoorLabels(t *testing.T) {
	names := []string{"主驾前门", "副驾前门", "主驾后门", "前备箱", "后备箱"}
	swapDoorLabels(names)
	want := []string{"主驾前门", "主驾后门", "副驾前门", "前备箱", "后备箱"}
	if !reflect.DeepEqual(names, want) {
		t.Errorf("对调后 = %v，期望 %v", names, want)
	}
}

// --------------------------------------------------------------------------
// 哨兵
// --------------------------------------------------------------------------

// TestSentryFromRaw 中国区实测 `sentry_mode` 是**布尔**，只能分辨开/关。
func TestSentryFromRaw(t *testing.T) {
	// false ⇒ 明确未开启。
	st, ok := sentryFromRaw(vsRaw(map[string]any{
		"sentry_mode": false, "sentry_mode_available": true,
	}))
	if !ok || st != sentryStateOff {
		t.Errorf("sentry_mode=false ⇒ 期望 (off, true)，得到 (%q, %v)", st, ok)
	}

	// true ⇒ 已开启（**不细分**是哪一种开启状态）。
	st, ok = sentryFromRaw(vsRaw(map[string]any{
		"sentry_mode": true, "sentry_mode_available": true,
	}))
	if !ok || st != sentryStateOn {
		t.Errorf("sentry_mode=true ⇒ 期望 (on, true)，得到 (%q, %v)", st, ok)
	}

	// 这台车不支持哨兵 ⇒ unsupported（**不是**「关着」）。
	st, ok = sentryFromRaw(vsRaw(map[string]any{
		"sentry_mode": false, "sentry_mode_available": false,
	}))
	if !ok || st != sentryStateUnsupported {
		t.Errorf("sentry_mode_available=false ⇒ 期望 (unsupported, true)，得到 (%q, %v)", st, ok)
	}

	// 没有这个字段 ⇒ 不知道。
	if _, ok := sentryFromRaw(vsRaw(map[string]any{"locked": true})); ok {
		t.Errorf("没有 sentry_mode ⇒ 期望 ok=false")
	}
	if _, ok := sentryFromRaw(nil); ok {
		t.Errorf("没有 vehicle_state ⇒ 期望 ok=false")
	}
}

// TestSentryText 空串（完全没读到）与 unknown（车自己说不知道）要分开。
func TestSentryText(t *testing.T) {
	if got := sentryText(""); got != "" {
		t.Errorf("sentryText(\"\") = %q，期望空串", got)
	}
	if got := sentryText(sentryStateUnknown); got != "状态未知" {
		t.Errorf("sentryText(unknown) = %q，期望 状态未知", got)
	}
	if got := sentryText(sentryStatePanic); got != "警报触发" {
		t.Errorf("sentryText(panic) = %q，期望 警报触发", got)
	}
	// 认不出来的状态词 ⇒ 摊「状态未知」，不编。
	if got := sentryText("weird"); got != "状态未知" {
		t.Errorf("sentryText(weird) = %q，期望 状态未知", got)
	}
}

// --------------------------------------------------------------------------
// 合并：取更新的那个
// --------------------------------------------------------------------------

// TestPickNewerDoors 与车窗同构：一边不知道就用另一边；两边都有取新的。
func TestPickNewerDoors(t *testing.T) {
	t1 := time.Date(2026, 10, 29, 10, 0, 0, 0, time.UTC)
	t2 := time.Date(2026, 10, 29, 10, 2, 0, 0, time.UTC)

	// 两边都没有 ⇒ 不知道。
	got, src := pickNewerDoors(nil, nil, nil, nil)
	if got != nil || src != "" {
		t.Errorf("两边都没有 ⇒ 期望 (nil, \"\")，得到 (%v, %q)", got, src)
	}
	// 一边不知道 ⇒ 用另一边。
	got, src = pickNewerDoors(nil, nil, []string{"前备箱"}, &t1)
	if !reflect.DeepEqual(got, []string{"前备箱"}) || src != "telemetry" {
		t.Errorf("只有遥测 ⇒ 期望 ([前备箱], telemetry)，得到 (%v, %q)", got, src)
	}
	got, src = pickNewerDoors([]string{"主驾前门"}, &t1, nil, nil)
	if !reflect.DeepEqual(got, []string{"主驾前门"}) || src != "poll" {
		t.Errorf("只有轮询 ⇒ 期望 ([主驾前门], poll)，得到 (%v, %q)", got, src)
	}
	// 两边都有且**口径一致** ⇒ 取更新的。
	// ⚠️ 口径**不一致**时走另一条规则（以遥测为准），见 TestPickNewerDoorsConflictPrefersTelemetry。
	got, src = pickNewerDoors([]string{"主驾前门"}, &t1, []string{"主驾前门"}, &t2)
	if !reflect.DeepEqual(got, []string{"主驾前门"}) || src != "telemetry" {
		t.Errorf("遥测更新 ⇒ 期望 ([主驾前门], telemetry)，得到 (%v, %q)", got, src)
	}
	got, src = pickNewerDoors([]string{"主驾前门"}, &t2, []string{"主驾前门"}, &t1)
	if !reflect.DeepEqual(got, []string{"主驾前门"}) || src != "poll" {
		t.Errorf("轮询更新 ⇒ 期望 ([主驾前门], poll)，得到 (%v, %q)", got, src)
	}
	// 「全关」也是一个**已知**结论，不能被「不知道」覆盖。
	got, src = pickNewerDoors([]string{}, &t1, nil, nil)
	if got == nil || len(got) != 0 || src != "poll" {
		t.Errorf("轮询说全关、遥测没读到 ⇒ 期望 ([], poll)，得到 (%v, %q)", got, src)
	}
}

// TestPickNewerDoorsConflictPrefersTelemetry 两源**口径冲突**时以遥测为准。
//
// ⚠️ 起因（2026-10-29 真车实测）：轮询侧 `vehicle_state.rt` 出现了 **32** ——
// 既不是 0（关）也不是 1（开）⇒ 轮询的门位编码是**不透明**的。
// 遥测 `DoorState` 是官方 `message Doors` 的逐字编码 ⇒ 冲突时它赢。
func TestPickNewerDoorsConflictPrefersTelemetry(t *testing.T) {
	t1 := time.Date(2026, 10, 29, 10, 23, 58, 0, time.UTC)
	t2 := time.Date(2026, 10, 29, 10, 24, 40, 0, time.UTC)

	// 轮询说「后备箱开着」，遥测说「全关」，且**轮询更新** ⇒ 仍然以遥测为准。
	got, src := pickNewerDoors([]string{"后备箱"}, &t2, []string{}, &t1)
	if got == nil || len(got) != 0 || src != "telemetry" {
		t.Errorf("口径冲突 ⇒ 期望 ([], telemetry)，得到 (%v, %q)", got, src)
	}

	// 反过来：遥测说开着、轮询说全关 ⇒ 也以遥测为准。
	got, src = pickNewerDoors([]string{}, &t2, []string{"后备箱"}, &t1)
	if !reflect.DeepEqual(got, []string{"后备箱"}) || src != "telemetry" {
		t.Errorf("口径冲突 ⇒ 期望 ([后备箱], telemetry)，得到 (%v, %q)", got, src)
	}

	// **口径一致**时，仍然按采集时刻取更新的那个（规则不被破坏）。
	got, src = pickNewerDoors([]string{"后备箱"}, &t2, []string{"后备箱"}, &t1)
	if !reflect.DeepEqual(got, []string{"后备箱"}) || src != "poll" {
		t.Errorf("口径一致 ⇒ 期望 ([后备箱], poll)，得到 (%v, %q)", got, src)
	}
}

// TestSameOpenSet 集合比较（不看顺序）。
func TestSameOpenSet(t *testing.T) {
	if !sameOpenSet([]string{}, []string{}) {
		t.Errorf("空 vs 空 ⇒ 期望一致")
	}
	if !sameOpenSet([]string{"前备箱"}, []string{"前备箱"}) {
		t.Errorf("同 ⇒ 期望一致")
	}
	if sameOpenSet([]string{"前备箱"}, []string{"后备箱"}) {
		t.Errorf("不同 ⇒ 期望不一致")
	}
	if sameOpenSet([]string{"前备箱"}, []string{"前备箱", "后备箱"}) {
		t.Errorf("个数不同 ⇒ 期望不一致")
	}
}

// TestPickNewerSentry 遥测的 unknown **不参与竞争**（车自己说不知道）。
func TestPickNewerSentry(t *testing.T) {
	t1 := time.Date(2026, 10, 29, 10, 0, 0, 0, time.UTC)
	t2 := time.Date(2026, 10, 29, 10, 2, 0, 0, time.UTC)

	// 两边都不知道 ⇒ 空。
	st, src, at := pickNewerSentry("", nil, "", nil)
	if st != "" || src != "" || at != nil {
		t.Errorf("两边都不知道 ⇒ 期望 (\"\", \"\", nil)，得到 (%q, %q, %v)", st, src, at)
	}
	// 遥测 unknown、轮询 off ⇒ 取轮询（轮询是**确切**的「未开启」）。
	st, src, _ = pickNewerSentry(sentryStateOff, &t1, sentryStateUnknown, &t2)
	if st != sentryStateOff || src != "poll" {
		t.Errorf("遥测 unknown ⇒ 期望 (off, poll)，得到 (%q, %q)", st, src)
	}
	// 轮询没有、遥测 armed ⇒ 取遥测。
	st, src, _ = pickNewerSentry("", nil, sentryStateArmed, &t1)
	if st != sentryStateArmed || src != "telemetry" {
		t.Errorf("只有遥测 ⇒ 期望 (armed, telemetry)，得到 (%q, %q)", st, src)
	}
	// 两边都知道 ⇒ 取更新的。
	st, src, _ = pickNewerSentry(sentryStateOff, &t1, sentryStatePanic, &t2)
	if st != sentryStatePanic || src != "telemetry" {
		t.Errorf("遥测更新 ⇒ 期望 (panic, telemetry)，得到 (%q, %q)", st, src)
	}
	st, src, _ = pickNewerSentry(sentryStateOff, &t2, sentryStatePanic, &t1)
	if st != sentryStateOff || src != "poll" {
		t.Errorf("轮询更新 ⇒ 期望 (off, poll)，得到 (%q, %q)", st, src)
	}
}

// TestSentryKnown unknown 不算「知道了」。
func TestSentryKnown(t *testing.T) {
	if sentryKnown("") {
		t.Errorf("空串不该算「知道了」")
	}
	if sentryKnown(sentryStateUnknown) {
		t.Errorf("unknown 不该算「知道了」")
	}
	if !sentryKnown(sentryStateArmed) {
		t.Errorf("armed 算「知道了」")
	}
}

// TestCarVersionFromRaw 读车端固件版本。
func TestCarVersionFromRaw(t *testing.T) {
	raw := vsRaw(map[string]any{"car_version": "2026.26.200.11"})
	if got := carVersionFromRaw(raw); got != "2026.26.200.11" {
		t.Errorf("car_version = %q，期望 2026.26.200.11", got)
	}
	if got := carVersionFromRaw(nil); got != "" {
		t.Errorf("没有原始数据 ⇒ 期望空串，得到 %q", got)
	}
}
