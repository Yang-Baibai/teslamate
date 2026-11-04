package main

import (
	"math"
	"strings"
	"testing"
	"time"
)

// ── 换算 ────────────────────────────────────────────────────────────

// TestMilesToKmMatchesRealReadings 用**真车实测值**钉死换算。
//
// ⚠️ 这两个不是编的：
//
//	· 63,055.1 英里 = v0.75.0 那次 Fleet 轮询拿到的车端里程表；
//	· 63,250.311 英里 = 2026-11-04 遥测的 Odometer。
//
// 换算后必须与公里侧的 TeslaMate 值吻合到**厘米级**
// （实测 101,791.508834 vs 101,791.508834 ⇒ 差 0.0003 公里）。
func TestMilesToKmMatchesRealReadings(t *testing.T) {
	cases := []struct {
		name string
		mi   float64
		km   float64
	}{
		{"v0.75.0 那次车端读数", 63055.1, 101477.3469},
		{"2026-11-04 遥测读数", 63250.31120433978, 101791.508834},
	}
	for _, c := range cases {
		got := milesToKm(c.mi)
		if math.Abs(got-c.km) > 0.01 {
			t.Fatalf("%s：milesToKm(%v) = %v，期望 %v", c.name, c.mi, got, c.km)
		}
	}
}

// TestOdometerMustNotBeTakenAsKm 钉死 v0.127.0 修掉的那个 bug：
// 官方那个字段是**英里**，绝不能当公里直接用。
func TestOdometerMustNotBeTakenAsKm(t *testing.T) {
	raw := 63055.1
	km := milesToKm(raw)
	if math.Abs(km-raw) < 1000 {
		t.Fatalf("换算后（%v）与原始值（%v）几乎一样 ⇒ 等于没换算", km, raw)
	}
	if math.Abs(km-101477.3469) > 0.01 {
		t.Fatalf("换算结果 %v 与实测的公里值 101477.35 对不上", km)
	}
}

func TestOdometerKmFromMilesKeepsNil(t *testing.T) {
	if got := odometerKmFromMiles(nil); got != nil {
		t.Fatalf("nil 必须还是 nil（读不到 ≠ 0），得到 %v", *got)
	}
}

// ── 两份来源怎么挑 ────────────────────────────────────────────────

func TestPickNewerOdometer(t *testing.T) {
	pollKm := 101791.508834
	telKm := 101800.2
	pollAt := time.Date(2026, 11, 3, 13, 0, 0, 0, time.UTC)
	telAt := pollAt.Add(time.Hour)

	km, source, at := pickNewerOdometer(&pollKm, &pollAt, &telKm, &telAt)
	if source != "telemetry" || km == nil || math.Abs(*km-telKm) > 0.001 || at == nil || !at.Equal(telAt) {
		t.Fatalf("两边都有时应取更新的那个，得到 (%v, %q, %v)", km, source, at)
	}

	km, source, _ = pickNewerOdometer(&pollKm, &pollAt, &telKm, &pollAt)
	if source != "poll" {
		t.Fatalf("遥测不更新时应取轮询，得到 %q", source)
	}

	km, source, _ = pickNewerOdometer(nil, nil, &telKm, &telAt)
	if source != "telemetry" || km == nil || math.Abs(*km-telKm) > 0.001 {
		t.Fatalf("只有遥测时应取遥测，得到 (%v, %q)", km, source)
	}

	km, source, _ = pickNewerOdometer(&pollKm, &pollAt, nil, nil)
	if source != "poll" {
		t.Fatalf("只有轮询时应取轮询，得到 %q", source)
	}

	km, source, _ = pickNewerOdometer(nil, nil, nil, nil)
	if km != nil || source != "" {
		t.Fatalf("都没有时必须是（nil, 空），得到 (%v, %q)", km, source)
	}
}

// ── 限定语文案 ──────────────────────────────────────────────────────

func TestRenderOdometerNote(t *testing.T) {
	loc := time.FixedZone("CST", 8*3600)
	now := time.Date(2026, 11, 4, 12, 0, 0, 0, loc)
	// 2026-11-03 13:17:23Z ⇒ 上海时间 11-03 21:17
	at := time.Date(2026, 11, 3, 13, 17, 23, 0, time.UTC)

	km := 101791.508834
	note := renderOdometerNote(VehicleOdometer{Km: &km, Source: "telemetry", At: &at}, loc, now)
	if note != "（车端 · 11-03 读数）" {
		t.Fatalf("限定语不对：%q", note)
	}

	// ⚠️ 跨年要写完整日期（只写「12-31」会让人以为是今年的）。
	old := time.Date(2025, 12, 31, 20, 0, 0, 0, loc)
	note = renderOdometerNote(VehicleOdometer{Km: &km, At: &old}, loc, now)
	if !strings.Contains(note, "2025-12-31") {
		t.Fatalf("跨年必须写完整日期：%q", note)
	}

	// 车端读不到 ⇒ 说清楚那是估算
	note = renderOdometerNote(VehicleOdometer{}, loc, now)
	if note != "（GPS 估算）" {
		t.Fatalf("车端读不到时应说 GPS 估算：%q", note)
	}

	// ⚠️ 不许暗示方向（趋势 / ↑ ↓ →）
	for _, bad := range []string{"↑", "↓", "→", "增加", "减少", "上升", "下降", "变多", "变少"} {
		if strings.Contains(note, bad) {
			t.Fatalf("限定语不许暗示方向（%q）：%q", bad, note)
		}
	}
}

// TestOdometerNoteNeverLeaksRawMiles 限定语里不许出现**原始英里数**
// （那个数一旦被当成公里，就会重演 v0.127.0 修掉的 bug）。
func TestOdometerNoteNeverLeaksRawMiles(t *testing.T) {
	loc := time.FixedZone("CST", 8*3600)
	now := time.Date(2026, 11, 4, 12, 0, 0, 0, loc)
	at := time.Date(2026, 11, 3, 13, 17, 23, 0, time.UTC)
	km := milesToKm(63250.31120433978)
	note := renderOdometerNote(VehicleOdometer{Km: &km, At: &at}, loc, now)
	if strings.Contains(note, "63,250") || strings.Contains(note, "63250") {
		t.Fatalf("限定语里出现了原始英里数：%q", note)
	}
}
