package main

import "testing"

// ⚠️ **v0.199.0**：趋势图的点 = 判定用的**同一批**严格档点，且**按里程升序**。
func TestBatteryTrendPointsSortedByKm(t *testing.T) {
	strict := []decayPoint{
		{km: 11559.2, kwh: 65.4},
		{km: 4000.0, kwh: 66.1},
		{km: 8000.5, kwh: 65.8},
	}
	pts := batteryTrendPoints(strict)
	if len(pts) != 3 {
		t.Fatalf("点数不对：%d", len(pts))
	}
	for i := 1; i < len(pts); i++ {
		if pts[i].Km < pts[i-1].Km {
			t.Fatalf("没有按里程升序：%v", pts)
		}
	}
	// ⚠️ 值必须**原样**（客户端只画，不许自己换口径）
	if pts[0].Kwh != 66.1 || pts[0].Km != 4000.0 {
		t.Errorf("点的值不对：%+v", pts[0])
	}
}

// ⚠️ 没有严格档点 ⇒ 空（图不画）⇒ 不许凭空造点。
func TestBatteryTrendPointsEmpty(t *testing.T) {
	if pts := batteryTrendPoints(nil); len(pts) != 0 {
		t.Errorf("没有点就不该造点：%+v", pts)
	}
}

// ⚠️ 趋势图的口径说明：ⓘ 文案 ⇒ 行宽 ≤ noteLineWidthLimit（96）。
func TestBatterySeriesNoteWithinWidth(t *testing.T) {
	if w := copyVisualWidth(batterySeriesNote); w > noteLineWidthLimit {
		t.Errorf("趋势图口径说明宽度 %d > %d：%q", w, noteLineWidthLimit, batterySeriesNote)
	}
	// ⚠️ 必须说清「哪些点算数」与「横纵轴是什么」⇒ 少了就是半句
	for _, must := range []string{"100%", "横轴", "纵轴"} {
		if !containsStr(batterySeriesNote, must) {
			t.Errorf("口径说明里少了 %q：%q", must, batterySeriesNote)
		}
	}
}

// containsStr 小工具（避免为一处测试引入 strings 依赖）。
func containsStr(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}
