package main

// ⚠️ **v0.182.0：按站画像（StationStat）—— 收归服务端。**
//
// ## 为什么搬
//
// 原来「这个桩到底快不快 / 我要等多久」是**客户端**按地点聚合充电历史算的
// （app-android/.../ui/ChargeStats.kt 的 buildStationStats）。
// ⇒ ① **飞书里问不出来**（mission §2.1）：清单里 `charge_measured_80`
//    与 `charge_fold_80` 两条**假 covered** 就是这么来的（v0.180.0 实测）；
//   ② **两端各算一套** —— 那套口径有 9 个阈值 ＋ 5 处评审修出来的坑。
//
// ## ⚠️ 样本总体必须与客户端**完全一致**
//
// 本文件**复用** sqlCharges 与**同样的 limit**（300）
// ⇒ 与客户端 api.charges(carId, 300) 是**同一批充电、同一个顺序**
// ⇒ 同一套算法 ⇒ 两边算出来的数**必然相同**。
//
// ## ⚠️ 分组键：改用服务端的 `place_key`
//
// 客户端原来用 `stationKey(c)`（围栏名 → 坐标 2 位小数 → 地址首段）。
// 服务端已有**按真实距离聚类（500 m）的 place_key**（v0.31.0 起就是全项目的
// 「同一个地方」身份，长途选线已经在用它）⇒ 两端各留一份分组规则 = 两把尺子。
// ⇒ 现在**统一用 place_key**，客户端按 `c.place_key` 查表。
//
// ⚠️ **实测分组数一致**（本机 84 场充电：坐标 2 位小数分 **58** 组，
// place_key 也是 **58** 组 ⇒ 同一批站、同一批样本 ⇒ **数值不变**）。
// ⇒ 且 place_key **没有一场缺失**（0 null）⇒ 不会漏掉任何一场。
//
// ## 本文件还顺带把「反推电池容量」收归服务端
//
// 那个容量（充入 kWh ÷ ΔSOC% × 100 的中位数）原来是客户端算的，
// 而它**同时**被线路余量表的判定用到 ⇒ 留在客户端就等于判定还留一半在客户端。
// ⇒ 现在服务端给（同一批充电、同一套筛选 ⇒ 数值相同）。

import (
	"context"
	"math"
	"net/http"
	"sort"
	"strings"
)

// ---------- 阈值（与客户端 buildStationStats 逐条对应） ----------
//
// ⚠️ **这些数是判据的一部分** —— 改一个，「这个桩要等多久」就变了。
const (
	// stationCapacityMinKwh / MaxKwh 反推容量的合理区间（kWh，整包）。
	// ⚠️ v0.8.2：这里算的是**整包容量**（kWh / ΔSOC% × 100），
	// 原来误写成 0.3~1.2 ⇒ 那一档会把**全部样本**滤掉。
	stationCapacityMinKwh = 30.0
	stationCapacityMaxKwh = 120.0

	// stationMinDeltaSoc ΔSOC 太小 ⇒ 量化误差过大（不参与容量反推）。
	stationMinDeltaSoc = 5

	// stationMinAddedKwh 充入 < 0.5 度的场次是「插了一下没充上」，
	// 不参与**功率**统计（v0.31.0：实测 id=31 把某站典型功率从 32 kW 拉到 18.5 kW）。
	stationMinAddedKwh = 0.5

	// stationMinPowerKw 功率太小的场次不参与统计。
	stationMinPowerKw = 1.0

	// stationMinDcSamples 直流典型功率至少要 2 场（1 场没有「典型」可言）。
	stationMinDcSamples = 2

	// stationMinMinutesSamples 折算耗时的中位数至少要 2 条。
	stationMinMinutesSamples = 2

	// stationMinP90Samples P90 至少要 10 条（更少时 P90 与最大值没区别）。
	stationMinP90Samples = 10

	// stationFromSocMax 只统计「从 ≤50% 出发」的场次（折算口径的起点区间）。
	stationFromSocMax = 50

	// stationTargetSoc 折算的目标电量。
	stationTargetSoc = 80

	// stationMinutesMin / Max 折算耗时的合理区间（分钟）。
	stationMinutesMin = 3.0
	stationMinutesMax = 600.0

	// stationShortStayMin 「没充到 80% 且停留很短」的分钟门槛（≤20 分钟）。
	stationShortStayMin = 20

	// stationChargesLimit **必须与客户端一致** ⇒ 样本总体才相同。
	stationChargesLimit = 300
)

// ---------- DTO ----------

// StationMeasuredSample 一笔**实测**样本（起点 SOC ＋ 分钟数）。
type StationMeasuredSample struct {
	FromSoc int `json:"from_soc"`
	Minutes int `json:"minutes"`
}

// StationStat 一个充电站的画像（对应客户端的 StationStat）。
type StationStat struct {
	// PlaceKey 分组键（服务端按真实距离聚类出来的「同一个地方」）。
	PlaceKey string `json:"place_key"`
	// Name 这个站的**显示名**（服务端唯一一份）—— 飞书里按名字回答要用它。
	//
	// ⚠️ 口径与客户端的 stopDisplayName 一致：place_label → poi_name →
	// geofence_name → 地址首段 → 「充电站」。
	Name string `json:"name"`
	// Sessions 该站的充电场次。
	Sessions int `json:"sessions"`
	// TypicalPowerKw 典型充电功率（kW，中位数，全部类型混算）。
	TypicalPowerKw float64 `json:"typical_power_kw"`
	// TypicalPowerDcKw 仅**直流**场次的中位功率（kW）；不足 2 场时为 null。
	TypicalPowerDcKw *float64 `json:"typical_power_dc_kw"`
	// TypicalMinutesTo80 折算口径：从 ≤50% 充到 80% 的中位耗时（分钟）。
	TypicalMinutesTo80 *int `json:"typical_minutes_to_80"`
	// P90MinutesTo80 最近秩法 P90（分钟）；样本 < 10 时为 null。
	P90MinutesTo80 *int `json:"p90_minutes_to_80"`
	// MeasuredSamples 该站**直流**场次里的实测样本（服务端逐点直读）。
	MeasuredSamples []StationMeasuredSample `json:"measured_samples"`
	// DcNeverReached80 该站直流场次里「没充到 80%」的次数。
	DcNeverReached80 int `json:"dc_never_reached_80"`
	// DcNeverReached80Short 其中停留 ≤20 分钟的次数（「人提前走了」）。
	DcNeverReached80Short int `json:"dc_never_reached_80_short"`
}

// StationStatsResponse 按站画像的完整结论。
type StationStatsResponse struct {
	CarID int `json:"car_id"`
	// CapacityKwh 反推出的**整包**容量（kWh）—— 折算耗时的分母。
	CapacityKwh float64 `json:"capacity_kwh"`
	// SampleCount 参与统计的充电场次。
	SampleCount int `json:"sample_count"`
	// StationCount 站点数。
	StationCount int `json:"station_count"`
	// Stations 各站画像（按首次出现顺序）。
	Stations []StationStat `json:"stations"`
}

// ---------- 算法 ----------

// stationEffectivePowerKw 有效平均功率：优先时间加权值（v0.21.0）。
func stationEffectivePowerKw(c *Charge) *int {
	if c.AvgPowerKwTrue != nil {
		return c.AvgPowerKwTrue
	}
	return c.AvgPowerKw
}

// stationCapacity 反推**整包**容量（kWh）：充入 kWh ÷ ΔSOC% × 100 的中位数。
//
// ⚠️ v0.8.2 的坑：这里算的是整包容量（合理区间 30~120 kWh），
// 原来误写成 0.3~1.2 ⇒ 那一档会把**全部样本**滤掉 ⇒ 站点历史永远不显示。
func stationCapacity(charges []*Charge) (float64, bool) {
	perPercent := make([]float64, 0, len(charges))
	for _, c := range charges {
		if c.EnergyAdded == nil || c.StartSoc == nil || c.EndSoc == nil {
			continue
		}
		dsoc := *c.EndSoc - *c.StartSoc
		if dsoc <= stationMinDeltaSoc {
			continue
		}
		v := *c.EnergyAdded / float64(dsoc) * 100.0
		if v < stationCapacityMinKwh || v > stationCapacityMaxKwh {
			continue
		}
		perPercent = append(perPercent, v)
	}
	if len(perPercent) == 0 {
		return 0, false
	}
	return medianOfFloats(perPercent), true
}

// buildStationStats 按地点聚合充电历史（**照抄**客户端的 buildStationStats）。
//
// @return 容量（kWh）与各站画像；容量算不出来时返回 ok=false（客户端此时也是空表）。
func buildStationStats(charges []*Charge) (float64, []StationStat, bool) {
	if len(charges) < 2 {
		return 0, nil, false
	}
	capacity, ok := stationCapacity(charges)
	if !ok {
		return 0, nil, false
	}

	// 分组：按 place_key（⚠️ 没有 place_key 的场次不参与 ⇒ 两端同一把尺子）。
	order := make([]string, 0, 32)
	groups := map[string][]*Charge{}
	for _, c := range charges {
		if c.Place == nil || strings.TrimSpace(*c.Place) == "" {
			continue
		}
		key := ""
		if c.PlaceKey != nil {
			key = strings.TrimSpace(*c.PlaceKey)
		}
		if key == "" {
			continue
		}
		if _, seen := groups[key]; !seen {
			order = append(order, key)
		}
		groups[key] = append(groups[key], c)
	}

	stats := make([]StationStat, 0, len(order))
	for _, key := range order {
		list := groups[key]

		// ① 典型功率：**排除零电量尾段**（v0.31.0）。
		powerList := make([]*Charge, 0, len(list))
		for _, c := range list {
			added := 0.0
			if c.EnergyAdded != nil {
				added = *c.EnergyAdded
			}
			if added >= stationMinAddedKwh {
				powerList = append(powerList, c)
			}
		}
		powers := make([]float64, 0, len(powerList))
		for _, c := range powerList {
			kw := stationEffectivePowerKw(c)
			if kw == nil || float64(*kw) <= stationMinPowerKw {
				continue
			}
			powers = append(powers, float64(*kw))
		}
		if len(powers) == 0 {
			// ⚠️ v0.14.0：没有功率样本的站**整站丢掉**（客户端同款）。
			continue
		}
		typicalPower := medianOfFloats(powers)

		// ② 直流典型功率（交流不混进来）。
		dcPowers := make([]float64, 0, len(powerList))
		for _, c := range powerList {
			if !c.IsDC {
				continue
			}
			kw := stationEffectivePowerKw(c)
			if kw == nil || float64(*kw) <= stationMinPowerKw {
				continue
			}
			dcPowers = append(dcPowers, float64(*kw))
		}
		var typicalPowerDc *float64
		if len(dcPowers) >= stationMinDcSamples {
			v := medianOfFloats(dcPowers)
			typicalPowerDc = &v
		}

		// ③ 折算耗时：从 ≤50% 充到 80% 要多久（用**全部**该站充电，不只是 powerList）。
		minutes := make([]int, 0, len(list))
		for _, c := range list {
			if c.StartSoc == nil || *c.StartSoc > stationFromSocMax {
				continue
			}
			kw := stationEffectivePowerKw(c)
			if kw == nil || float64(*kw) <= stationMinPowerKw {
				continue
			}
			needKwh := float64(stationTargetSoc-*c.StartSoc) / 100.0 * capacity
			m := needKwh / float64(*kw) * 60.0
			if m < stationMinutesMin || m > stationMinutesMax {
				continue
			}
			minutes = append(minutes, int(math.Round(m)))
		}
		var typicalMinutes *int
		if len(minutes) >= stationMinMinutesSamples {
			v := int(math.Round(medianOfFloats(intsToFloats(minutes))))
			typicalMinutes = &v
		}
		var p90 *int
		if len(minutes) >= stationMinP90Samples {
			sorted := append([]int(nil), minutes...)
			sort.Ints(sorted)
			idx := int(math.Ceil(0.9*float64(len(sorted)))) - 1
			if idx < 0 {
				idx = 0
			}
			if idx > len(sorted)-1 {
				idx = len(sorted) - 1
			}
			v := sorted[idx]
			p90 = &v
		}

		// ④ 实测样本：**只收直流**（v0.34.0：交流慢充混进来会把「最慢」变成 576 分钟）。
		samples := make([]StationMeasuredSample, 0, len(list))
		for _, c := range list {
			if !c.IsDC {
				continue
			}
			if c.MeasuredMinutesTo80 == nil || c.MeasuredFromSoc == nil {
				continue
			}
			samples = append(samples, StationMeasuredSample{
				FromSoc: *c.MeasuredFromSoc,
				Minutes: *c.MeasuredMinutesTo80,
			})
		}

		// ⑤ 「没充到 80%」的场次（其中停留 ≤20 分钟的是「人提前走了」）。
		never80 := 0
		never80Short := 0
		for _, c := range list {
			if !c.IsDC {
				continue
			}
			if c.MeasuredUnavailableReason == nil || *c.MeasuredUnavailableReason != "never_reached_80" {
				continue
			}
			never80++
			dur := math.MaxInt32
			if c.DurationMin != nil {
				dur = *c.DurationMin
			}
			if dur <= stationShortStayMin {
				never80Short++
			}
		}

		stats = append(stats, StationStat{
			PlaceKey:              key,
			Name:                  stationName(list[0]),
			Sessions:              len(list),
			TypicalPowerKw:        typicalPower,
			TypicalPowerDcKw:      typicalPowerDc,
			TypicalMinutesTo80:    typicalMinutes,
			P90MinutesTo80:        p90,
			MeasuredSamples:       samples,
			DcNeverReached80:      never80,
			DcNeverReached80Short: never80Short,
		})
	}
	return capacity, stats, true
}

// stationName 这个站的显示名（服务端唯一一份）。
//
// ⚠️ 取**最新那一场**的显示名（充电列表按 start_date DESC ⇒ 第一条就是最新的）。
func stationName(c *Charge) string {
	if c.PlaceLabel != nil && strings.TrimSpace(*c.PlaceLabel) != "" {
		return *c.PlaceLabel
	}
	if c.PoiName != nil && strings.TrimSpace(*c.PoiName) != "" {
		return *c.PoiName
	}
	if c.GeofenceName != nil && strings.TrimSpace(*c.GeofenceName) != "" {
		return *c.GeofenceName
	}
	if seg := routeFirstSegment(c.Place); seg != nil {
		return *seg
	}
	return "充电站"
}

// intsToFloats int 列表 → float64 列表（中位数用）。
func intsToFloats(v []int) []float64 {
	out := make([]float64, 0, len(v))
	for _, x := range v {
		out = append(out, float64(x))
	}
	return out
}

// ---------- 取数与端点 ----------

// queryStationCharges 取充电（**与客户端同一批**：同一条 SQL、同一个 limit、同一个顺序）。
func queryStationCharges(ctx context.Context, carID int) ([]*Charge, error) {
	rows, err := db.Query(ctx, sqlCharges, carID, stationChargesLimit, 0)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*Charge{}
	for rows.Next() {
		c, err := scanCharge(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// handleStationStats 按站画像（**服务端算的结论**）。
//
// GET /api/v1/cars/{id}/station_stats
//
// ⚠️ **v0.182.0 新增**：原来这套聚合在**客户端**（buildStationStats）
// ⇒ 飞书里问「这个桩充到 80% 要多久」问不出来（mission §2.1）。
func handleStationStats(w http.ResponseWriter, r *http.Request) {
	// ⚠️ **v0.184.0**：支持按**名字**查一个站（飞书里「金田服务区充到 80% 要多久」）。
	//
	// 匹配规则：站名**包含**该关键字（大小写不敏感），或 place_key **精确等于**它
	// ⇒ 既能按「金田服务区」这样的名字问，也能按 place_key 精确取。
	name := strings.TrimSpace(r.URL.Query().Get("name"))
	id, err := pathID(r)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "car id 无效")
		return
	}
	ctx := context.Background()
	charges, err := queryStationCharges(ctx, id)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	out := StationStatsResponse{
		CarID:       id,
		SampleCount: len(charges),
		Stations:    []StationStat{},
	}
	capacity, stats, ok := buildStationStats(charges)
	if !ok {
		// ⚠️ 算不出来就是「不知道」—— 不给数，也不给一张空表充数。
		writeJSON(w, out)
		return
	}
	out.CapacityKwh = capacity
	out.Stations = stats
	out.StationCount = len(stats)
	// ⚠️ **v0.184.0**：按名字过滤时**只回匹配的站**，但**容量照给**（它是全车的一份）。
	if name != "" {
		matched := make([]StationStat, 0, len(stats))
		for _, s := range stats {
			if s.PlaceKey == name || strings.Contains(strings.ToLower(s.Name), strings.ToLower(name)) {
				matched = append(matched, s)
			}
		}
		out.Stations = matched
		out.StationCount = len(matched)
	}
	writeJSON(w, out)
}
