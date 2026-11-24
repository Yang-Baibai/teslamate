package main

import (
	"math"
	"strings"
	"testing"
)

// TestHaversineKm 球面距离的**具体数值**（阈值型断言要钉数值）。
func TestHaversineKm(t *testing.T) {
	// 赤道上 1 度经度 ≈ 111.19 公里。
	if d := haversineKm(0, 0, 0, 1); math.Abs(d-111.19) > 0.05 {
		t.Fatalf("1 度经度应约 111.19 公里，实际 %v", d)
	}
	// 赤道上 1 度纬度 ≈ 111.19 公里。
	if d := haversineKm(0, 0, 1, 0); math.Abs(d-111.19) > 0.05 {
		t.Fatalf("1 度纬度应约 111.19 公里，实际 %v", d)
	}
	if d := haversineKm(22.5, 114.0, 22.5, 114.0); d != 0 {
		t.Fatalf("同一个点距离应为 0，实际 %v", d)
	}
}

// TestBuildNearbyStations_半径与排序
//
// ⚠️ **v0.186.0**：半径、距离排序、功率排序三件事都要钉死。
func TestBuildNearbyStations_半径与排序(t *testing.T) {
	plat, plng := 22.5, 114.0
	pos := &NearbyPosition{Known: true, Lat: &plat, Lng: &plng}
	alat := 22.5 + 0.09  // 正北约 10 公里
	elng := 114.0 + 0.09 // 正东约 9.6 公里
	far := 22.5 + 0.9    // 约 100 公里
	dc120, dc60 := 120.0, 60.0
	stats := []StationStat{
		{PlaceKey: "a10", Name: "北边那个", MapLat: &alat, MapLng: &plng, Sessions: 5, TypicalPowerKw: 130, TypicalPowerDcKw: &dc120},
		{PlaceKey: "a20", Name: "东边那个", MapLat: &plat, MapLng: &elng, Sessions: 3, TypicalPowerKw: 70, TypicalPowerDcKw: &dc60},
		{PlaceKey: "a30", Name: "太远了", MapLat: &far, MapLng: &plng, Sessions: 1, TypicalPowerKw: 50},
	}
	list := buildNearbyStations(pos, stats, 30, "distance")
	if len(list) != 2 {
		t.Fatalf("30 公里内应有 2 个站（a30 在约 100 公里外），实际 %d 个", len(list))
	}
	if list[0].PlaceKey != "a20" {
		t.Fatalf("按距离应先回 a20（约 9.6 公里），实际 %s", list[0].PlaceKey)
	}
	if list[1].DistanceKm < 9.9 || list[1].DistanceKm > 10.1 {
		t.Fatalf("a10 的距离应约 10.0 公里，实际 %v", list[1].DistanceKm)
	}
	// 按功率（「哪个快」）：a10（直流 120 kW）应排前面。
	list = buildNearbyStations(pos, stats, 30, "power")
	if len(list) != 2 || list[0].PlaceKey != "a10" {
		t.Fatalf("按功率应先回 a10（直流 120 kW），实际 %v", list)
	}
	// 没有位置 ⇒ 空（是「算不出附近」，不是「附近没有桩」）。
	if got := buildNearbyStations(&NearbyPosition{Known: false}, stats, 30, "distance"); len(got) != 0 {
		t.Fatalf("没有位置时应返回空，实际 %d 个", len(got))
	}
}

// TestNearbyPositionNote 位置新鲜度的三支（**必须明说**，不许当成现在）。
func TestNearbyPositionNote(t *testing.T) {
	if n := nearbyPositionNote(false, DataAge{}); !strings.Contains(n, "还没有") {
		t.Fatalf("没有位置时应说「还没有」，实际 %q", n)
	}
	if n := nearbyPositionNote(true, DataAge{Hours: 30, Stale: true}); !strings.Contains(n, "过期") || !strings.Contains(n, "30") {
		t.Fatalf("过期位置应说「过期」并带小时数，实际 %q", n)
	}
	if n := nearbyPositionNote(true, DataAge{Hours: 8, Warn: true}); !strings.Contains(n, "8") {
		t.Fatalf("该提醒的位置应带小时数，实际 %q", n)
	}
	if n := nearbyPositionNote(true, DataAge{Hours: 0}); !strings.Contains(n, "刚才") {
		t.Fatalf("新鲜位置应说「刚才」，实际 %q", n)
	}
}
