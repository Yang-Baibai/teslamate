package main

// ⚠️ **v0.186.0**：「我附近的桩哪个快」的 HTTP 端点。
//
// GET /api/v1/cars/{id}/nearby_stations?radius_km=30&sort=distance
//
// ⚠️ **参数**：
//   · radius_km 半径（公里，默认 30，范围 1~200）—— 超出范围**夹到边界**（不报错）。
//   · sort      distance（默认）/ power（按典型直流功率从高到低 ——「哪个快」）。

import (
	"context"
	"net/http"
	"strconv"
	"strings"
)

func handleNearbyStations(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	radius := nearbyDefaultRadiusKm
	if raw := strings.TrimSpace(r.URL.Query().Get("radius_km")); raw != "" {
		v, perr := strconv.ParseFloat(raw, 64)
		if perr != nil {
			writeErr(w, http.StatusBadRequest, "radius_km 必须是数字")
			return
		}
		radius = v
	}
	if radius < nearbyMinRadiusKm {
		radius = nearbyMinRadiusKm
	}
	if radius > nearbyMaxRadiusKm {
		radius = nearbyMaxRadiusKm
	}
	sortBy := strings.TrimSpace(r.URL.Query().Get("sort"))
	if sortBy != "power" {
		sortBy = "distance"
	}
	ctx := context.Background()
	pos, err := queryLatestPosition(ctx, id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	out := NearbyStationsResponse{
		CarID:    id,
		RadiusKm: radius,
		Sort:     sortBy,
		Position: *pos,
		Stations: []NearbyStation{},
		Note:     nearbyNote,
	}
	charges, err := queryStationCharges(ctx, id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	_, stats, ok := buildStationStats(charges)
	if !ok {
		// ⚠️ 画像算不出来 ⇒ 不给一张空表充数（与 /station_stats 同一口径）。
		writeJSON(w, out)
		return
	}
	list := buildNearbyStations(pos, stats, radius, sortBy)
	out.StationCount = len(list)
	if len(list) > nearbyMaxStations {
		list = list[:nearbyMaxStations]
	}
	out.Stations = list
	writeJSON(w, out)
}
