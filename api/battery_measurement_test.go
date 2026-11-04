package main

import (
	"strings"
	"testing"
	"time"
)

// batteryMTestLoc 单测用的服务端时区。
//
// ⚠️ 固定 UTC+8（中国全境无夏令时）⇒ 期望值不依赖本机 / CI 的 TZ_NAME。
var batteryMTestLoc = time.FixedZone("CST", 8*3600)

// batteryMNow 固定的「现在」—— 用 UTC，避免夏令时让天数差 1。
func batteryMNow() time.Time {
	return time.Date(2026, 11, 4, 12, 0, 0, 0, time.UTC)
}

// ① 没有测量时刻 ⇒ **不知道**（nil），不是「新鲜」。
func TestBatteryMeasurementNilIsUnknown(t *testing.T) {
	if m := batteryMeasurement(nil, batteryMNow(), batteryMTestLoc); m != nil {
		t.Errorf("没有测量时刻时应当返回 nil（不知道），实际 %+v", m)
	}
}

// ② 同一天测的 ⇒ 「今天」，不算偏旧。
func TestBatteryMeasurementToday(t *testing.T) {
	now := batteryMNow()
	at := now.Add(-3 * time.Hour)
	m := batteryMeasurement(&at, now, batteryMTestLoc)
	if m == nil {
		t.Fatal("应当有测量信息")
	}
	if m.Note != "基于今天那次充电实测" {
		t.Errorf("长版 = %q，期望「基于今天那次充电实测」", m.Note)
	}
	if m.NoteShort != "11-04 实测" {
		t.Errorf("短版 = %q，期望「11-04 实测」", m.NoteShort)
	}
	if m.Stale {
		t.Error("今天测的不该算偏旧")
	}
	if m.AgeDays == nil || *m.AgeDays != 0 {
		t.Errorf("天数 = %v，期望 0", m.AgeDays)
	}
}

// ③ 新鲜档：如实给出「哪天测的 + 几天前」。
func TestBatteryMeasurementFresh(t *testing.T) {
	now := batteryMNow()
	at := now.AddDate(0, 0, -4)
	m := batteryMeasurement(&at, now, batteryMTestLoc)
	if m.Note != "基于 10-31 那次充电实测（4 天前）" {
		t.Errorf("长版 = %q，期望「基于 10-31 那次充电实测（4 天前）」", m.Note)
	}
	if m.NoteShort != "10-31 实测" {
		t.Errorf("短版 = %q，期望「10-31 实测」", m.NoteShort)
	}
	if m.Stale {
		t.Error("4 天前测的不该算偏旧")
	}
}

// ④ 新鲜期边界：7 天仍新鲜，8 天开始偏旧。
func TestBatteryMeasurementFreshBoundary(t *testing.T) {
	now := batteryMNow()
	at7 := now.AddDate(0, 0, -7)
	if m := batteryMeasurement(&at7, now, batteryMTestLoc); m.Stale {
		t.Error("7 天前测的应当仍算新鲜（新鲜期 = 7 天）")
	}
	at8 := now.AddDate(0, 0, -8)
	m8 := batteryMeasurement(&at8, now, batteryMTestLoc)
	if !m8.Stale {
		t.Error("8 天前测的应当算偏旧")
	}
	if !strings.Contains(m8.Note, "数据偏旧") {
		t.Errorf("偏旧时长版应明说「数据偏旧」，实际 %q", m8.Note)
	}
	if !strings.Contains(m8.NoteShort, "数据偏旧") {
		t.Errorf("偏旧时短版也要说明，实际 %q", m8.NoteShort)
	}
}

// ⑤ 刷新建议的边界：30 天还只是「偏旧」，31 天才建议再充一次。
func TestBatteryMeasurementRefreshBoundary(t *testing.T) {
	now := batteryMNow()
	at30 := now.AddDate(0, 0, -30)
	if m := batteryMeasurement(&at30, now, batteryMTestLoc); strings.Contains(m.Note, "满充一次可刷新") {
		t.Errorf("30 天前还不该建议刷新，实际 %q", m.Note)
	}
	at31 := now.AddDate(0, 0, -31)
	m31 := batteryMeasurement(&at31, now, batteryMTestLoc)
	if !strings.Contains(m31.Note, "满充一次可刷新") {
		t.Errorf("31 天前应当建议刷新，实际 %q", m31.Note)
	}
}

// ⑥ 跨年必须写完整日期 —— 只写「12-20」会被读成**今年**的 12-20。
func TestBatteryMeasurementCrossYear(t *testing.T) {
	now := time.Date(2026, 1, 5, 12, 0, 0, 0, time.UTC)
	at := time.Date(2025, 12, 20, 12, 0, 0, 0, time.UTC)
	m := batteryMeasurement(&at, now, batteryMTestLoc)
	if !strings.Contains(m.Note, "2025-12-20") {
		t.Errorf("跨年必须写完整日期，实际 %q", m.Note)
	}
	if !strings.Contains(m.NoteShort, "2025-12-20") {
		t.Errorf("跨年时短版也要写完整日期，实际 %q", m.NoteShort)
	}
}

// ⑦ 文案**不许暗示方向**（ADR #46：窗口内首末差多半只是测量噪声）。
func TestBatteryMeasurementNeverInventsDirection(t *testing.T) {
	now := batteryMNow()
	for _, age := range []int{0, 4, 8, 31} {
		at := now.AddDate(0, 0, -age)
		m := batteryMeasurement(&at, now, batteryMTestLoc)
		for _, bad := range []string{"↑", "↓", "→", "变好", "变差", "上升", "下降", "衰减"} {
			if strings.Contains(m.Note, bad) || strings.Contains(m.NoteShort, bad) {
				t.Errorf("%d 天前的文案里出现了方向词 %q（长版 %q / 短版 %q）",
					age, bad, m.Note, m.NoteShort)
			}
		}
	}
}

// ⑧ 数据来自未来（时钟回拨）⇒ 天数钳为 0，**不许说负数**。
func TestBatteryMeasurementFutureClockNotNegative(t *testing.T) {
	now := batteryMNow()
	at := now.Add(24 * time.Hour)
	m := batteryMeasurement(&at, now, batteryMTestLoc)
	if m.AgeDays == nil || *m.AgeDays < 0 {
		t.Errorf("天数 = %v，期望 >= 0", m.AgeDays)
	}
}

// ⑨ **跨日边界**：昨天 23:50 充的、今天 00:10 看到 ⇒ 必须是「昨天」。
//
// ⚠️ 用「经过时长 ÷ 24」会把它说成「今天」（才过了 20 分钟）
// ⇒ 而日历上那是**昨天** ⇒ 文案里的日期与天数会互相打架。
func TestBatteryMeasurementAcrossMidnight(t *testing.T) {
	now := time.Date(2026, 11, 4, 0, 10, 0, 0, batteryMTestLoc)
	at := time.Date(2026, 11, 3, 23, 50, 0, 0, batteryMTestLoc)
	m := batteryMeasurement(&at, now, batteryMTestLoc)
	if m.AgeDays == nil || *m.AgeDays != 1 {
		t.Errorf("天数 = %v，期望 1（昨天）", m.AgeDays)
	}
	if m.Note != "基于 11-03 那次充电实测（1 天前）" {
		t.Errorf("长版 = %q，期望「基于 11-03 那次充电实测（1 天前）」", m.Note)
	}
}

// ⑩ at 存的是 **UTC 时刻** ⇒ 日期要按**服务端时区**看。
//
// ⚠️ 2026-11-03 16:30 UTC = 2026-11-04 00:30（UTC+8）
// ⇒ 按 UTC 算会把它说成「11-03」，比用户看到的日期早一天。
func TestBatteryMeasurementUsesServerTimezone(t *testing.T) {
	at := time.Date(2026, 11, 3, 16, 30, 0, 0, time.UTC)
	now := time.Date(2026, 11, 4, 0, 40, 0, 0, batteryMTestLoc)
	m := batteryMeasurement(&at, now, batteryMTestLoc)
	if m.AgeDays == nil || *m.AgeDays != 0 {
		t.Errorf("天数 = %v，期望 0（本地已是同一天）", m.AgeDays)
	}
	if m.Note != "基于今天那次充电实测" {
		t.Errorf("长版 = %q，期望「基于今天那次充电实测」", m.Note)
	}
}
