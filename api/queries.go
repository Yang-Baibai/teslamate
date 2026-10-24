package main

// 全部 SQL。TeslaMate 的 timestamp 列均为无时区（UTC 存储），
// 统一用 (ts AT TIME ZONE 'UTC') 换算到本地时区后再聚合。
// 坐标：wgs84_to_gcj02_* 只在国内偏移（is_outside_china 判定），国外返回原值，
// 因此 map_lat/map_lng 直接配高德瓦片，国外场景 App 回退用 lat/lng。

const sqlCars = `
SELECT c.id, c.name, c.model, c.trim_badging, c.vin, c.marketing_name,
       c.efficiency, c.exterior_color, c.wheel_type
FROM cars c
ORDER BY c.display_priority, c.id`

const sqlStatus = `
WITH lp AS (
  SELECT date, battery_level, usable_battery_level,
         ideal_battery_range_km::float8 AS ideal_battery_range_km,
         est_battery_range_km::float8  AS est_battery_range_km,
         rated_battery_range_km::float8 AS rated_range_km,
         odometer, speed, power,
         outside_temp::float8 AS outside_temp, inside_temp::float8 AS inside_temp,
         is_climate_on, elevation,
         tpms_pressure_fl::float8 AS tpms_fl,
         tpms_pressure_fr::float8 AS tpms_fr,
         tpms_pressure_rl::float8 AS tpms_rl,
         tpms_pressure_rr::float8 AS tpms_rr,
         latitude::float8 AS lat, longitude::float8 AS lng,
         wgs84_to_gcj02_lat(latitude::float8, longitude::float8) AS map_lat,
         wgs84_to_gcj02_lng(latitude::float8, longitude::float8) AS map_lng
  FROM positions WHERE car_id = $1 ORDER BY date DESC LIMIT 1
), rx AS (
  -- ⚠️ 「最近已知值」兜底 CTE。
  --   TeslaMate 只在特斯拉 API 响应里**包含该字段**时才写对应列，
  --   所以 positions 表里续航/胎压/温度/海拔这些列极度稀疏（实测非空率仅 3.5%）。
  --   结果是「最新一条 position」里这些列常常是 NULL，界面就显示「—」——
  --   胎压卡甚至整块不显示（因为 App 判空后直接 return）。
  --   这里在最近 500 条里，对每个稀疏列各取「最后一个非空值」。
  --   500 条足够：行驶时 positions 约每秒一条。
  SELECT
    (array_agg(ideal_battery_range_km ORDER BY date DESC) FILTER (WHERE ideal_battery_range_km IS NOT NULL))[1] AS ideal_battery_range_km,
    (array_agg(est_battery_range_km  ORDER BY date DESC) FILTER (WHERE est_battery_range_km  IS NOT NULL))[1] AS est_battery_range_km,
    (array_agg(rated_battery_range_km ORDER BY date DESC) FILTER (WHERE rated_battery_range_km IS NOT NULL))[1] AS rated_range_km,
    (array_agg(tpms_pressure_fl ORDER BY date DESC) FILTER (WHERE tpms_pressure_fl IS NOT NULL))[1] AS tpms_fl,
    (array_agg(tpms_pressure_fr ORDER BY date DESC) FILTER (WHERE tpms_pressure_fr IS NOT NULL))[1] AS tpms_fr,
    (array_agg(tpms_pressure_rl ORDER BY date DESC) FILTER (WHERE tpms_pressure_rl IS NOT NULL))[1] AS tpms_rl,
    (array_agg(tpms_pressure_rr ORDER BY date DESC) FILTER (WHERE tpms_pressure_rr IS NOT NULL))[1] AS tpms_rr,
    (array_agg(outside_temp ORDER BY date DESC) FILTER (WHERE outside_temp IS NOT NULL))[1] AS outside_temp,
    (array_agg(inside_temp ORDER BY date DESC) FILTER (WHERE inside_temp IS NOT NULL))[1] AS inside_temp,
    (array_agg(elevation ORDER BY date DESC) FILTER (WHERE elevation IS NOT NULL))[1] AS elevation
  FROM (
    SELECT * FROM positions WHERE car_id = $1 ORDER BY date DESC LIMIT 500
  ) recent
), ls AS (
  SELECT state, start_date FROM states WHERE car_id = $1 ORDER BY start_date DESC LIMIT 1
), ac AS (
  SELECT cp.id AS charge_id, cp.start_date,
         cp.charge_energy_added::float8 AS energy_added,
         ch.charger_power, ch.charger_voltage,
         ch.charger_actual_current AS charger_current,
         ch.charger_phases, ch.fast_charger_present, ch.battery_level
  FROM charging_processes cp
  LEFT JOIN LATERAL (
    SELECT charger_power, charger_voltage, charger_actual_current,
           charger_phases, fast_charger_present, battery_level
    FROM charges WHERE charging_process_id = cp.id ORDER BY date DESC LIMIT 1
  ) ch ON true
  WHERE cp.car_id = $1 AND cp.end_date IS NULL
  ORDER BY cp.start_date DESC LIMIT 1
), ad AS (
  SELECT id AS drive_id, start_date FROM drives
  WHERE car_id = $1 AND end_date IS NULL ORDER BY start_date DESC LIMIT 1
), loc AS (
  SELECT COALESCE(gf.name, na.display_name) AS name
  FROM lp
  LEFT JOIN LATERAL (
    SELECT d.name FROM (
      SELECT g.name AS name, g.radius,
             6371000 * acos(GREATEST(-1, LEAST(1,
               cos(radians(lp.lat)) * cos(radians(g.latitude::float8)) * cos(radians(g.longitude::float8) - radians(lp.lng))
             + sin(radians(lp.lat)) * sin(radians(g.latitude::float8))))) AS dist
      FROM geofences g
      WHERE g.latitude IS NOT NULL AND g.longitude IS NOT NULL
    ) d WHERE d.dist < d.radius ORDER BY d.dist LIMIT 1
  ) gf ON true
  LEFT JOIN LATERAL (
    SELECT a.display_name FROM addresses a
    WHERE a.latitude IS NOT NULL
      AND 6371000 * acos(GREATEST(-1, LEAST(1,
        cos(radians(lp.lat)) * cos(radians(a.latitude::float8)) * cos(radians(a.longitude::float8) - radians(lp.lng))
      + sin(radians(lp.lat)) * sin(radians(a.latitude::float8))))) < 300
    ORDER BY a.id LIMIT 1
  ) na ON true
)
SELECT lp.date, lp.battery_level, lp.usable_battery_level,
       COALESCE(lp.ideal_battery_range_km, rx.ideal_battery_range_km) AS ideal_battery_range_km,
       COALESCE(lp.est_battery_range_km, rx.est_battery_range_km) AS est_battery_range_km,
       COALESCE(lp.rated_range_km, rx.rated_range_km) AS rated_range_km,
       lp.odometer, lp.speed, lp.power,
       COALESCE(lp.outside_temp, rx.outside_temp) AS outside_temp,
       COALESCE(lp.inside_temp, rx.inside_temp) AS inside_temp,
       lp.is_climate_on,
       COALESCE(lp.elevation, rx.elevation) AS elevation,
       lp.lat, lp.lng, lp.map_lat, lp.map_lng,
       COALESCE(lp.tpms_fl, rx.tpms_fl) AS tpms_fl,
       COALESCE(lp.tpms_fr, rx.tpms_fr) AS tpms_fr,
       COALESCE(lp.tpms_rl, rx.tpms_rl) AS tpms_rl,
       COALESCE(lp.tpms_rr, rx.tpms_rr) AS tpms_rr,
       loc.name,
       ls.state, ls.start_date,
       ac.charge_id, ac.start_date, ac.energy_added, ac.charger_power,
       ac.charger_voltage, ac.charger_current, ac.charger_phases,
       ac.fast_charger_present, ac.battery_level,
       ad.drive_id, ad.start_date
FROM lp
LEFT JOIN rx ON true
LEFT JOIN ls ON true
LEFT JOIN ac ON true
LEFT JOIN ad ON true
CROSS JOIN loc`

// ⚠️ v0.21.0：**会话级**「是否直流快充」的判据（sqlDrives / sqlCharges / sqlStatsCharges 三处共用）。
//
// ## 为什么必须是会话级
//
// 原来的判据是：
//
//	bool_or(NULLIF(ch.charger_phases,0) IS NULL AND ch.fast_charger_type <> 'ACSingleWireCAN')
//
// 这是**单点 or 语义**：一个异常采样点就能把整场会话翻成直流。
//
// 实测（charging_processes.id=61）：
//
//	conn_charge_cable = GB_AC（交流）
//	101 个采样点里 100 个有相数（=1 相）、只有 1 个为 NULL
//	→ 就这 1 个点让整场会话被标成「直流快充」
//
// 后果有三处，**同一份数据、三个地方同时说错**：
//   - 充电列表上的「直流快充」标签
//   - 用量页「充入电量」里的直流柱（那 0.70 度被算进直流）
//   - drive_link 的 has_supercharger → 这趟行程被挂上「终点有超充」
//
// ## 新判据（按可靠性排序）
//
//  1. **电缆类型** conn_charge_cable：GB_DC → 直流，GB_AC → 交流（最可靠）
//  2. 没有电缆类型时：**超充品牌** fast_charger_brand = 'Tesla' → 直流
//  3. 再兜底：**相数多数票**（交流桩报相数，直流桩不报）
//
// 三处共用同一份 CTE 定义，所以口径**不可能**再分叉 ——
// 原来三处各写一遍判据，正是它们分叉的原因。
const cteChargeDC = `
charge_dc AS (
  SELECT charging_process_id,
         CASE
           WHEN bool_or(conn_charge_cable = 'GB_DC') THEN true
           WHEN bool_or(conn_charge_cable = 'GB_AC') THEN false
           WHEN bool_or(fast_charger_brand = 'Tesla') THEN true
           WHEN count(*) FILTER (WHERE NULLIF(charger_phases, 0) IS NOT NULL)
              > count(*) FILTER (WHERE NULLIF(charger_phases, 0) IS NULL) THEN false
           ELSE true
         END AS is_dc
  FROM charges
  GROUP BY charging_process_id
)`

const sqlDrives = `
WITH` + cteChargeDC + `,
charge_link AS (
  -- 每个充电会话的「起始里程」+ 是否直流快充。
  -- TeslaMate 的 charging_processes 没有 start_odo 列，
  -- 起始里程取 cp.position_id 指向的那条 positions.odometer。
  SELECT cp.id AS charge_id,
         cp.car_id,
         sp.odometer::float8 AS start_odo,
         -- ⚠️ v0.21.0：is_dc 改用**会话级**判据（见 cteChargeDC），
         -- 不再是「任一采样点」的 or —— 一个异常点就能把交流标成直流。
         -- ⚠️ PG 没有 max(boolean)，用 bool_or 聚合。
         COALESCE(bool_or(cd.is_dc), false) AS is_dc
  FROM charging_processes cp
  JOIN positions sp ON sp.id = cp.position_id
  LEFT JOIN charge_dc cd ON cd.charging_process_id = cp.id
  WHERE cp.car_id = $1
  GROUP BY cp.id, cp.car_id, sp.odometer
), drive_sc AS (
  -- 把「行程」和「充电」按里程关联起来。
  -- ⚠️ 不能用时间窗关联：充电发生在行程结束之后
  --    （到达服务区 → 结束行程 → 开始充电），
  --    旧判据 ch.date BETWEEN d.start_date AND d.end_date 因此恒为 false，
  --    has_supercharger 在最近 200 条行程里全是 false。
  -- 里程判据：|充电起始里程 − 行程结束里程| < 2 km 视为同一地点。
  --   实测 17 次长途充电全部唯一命中，误差 ≤ 1 km。
  SELECT d.id AS drive_id,
         -- 终点附近有直流快充 → has_supercharger（保留旧字段名与布尔语义）
         EXISTS (
           SELECT 1 FROM charge_link cl
           WHERE cl.car_id = d.car_id AND cl.is_dc
             AND cl.start_odo IS NOT NULL AND d.end_km IS NOT NULL
             AND abs(cl.start_odo - d.end_km) < 2
         ) AS has_supercharger,
         -- 终点附近有过充电（不限快慢充）→ charge_after_trip
         EXISTS (
           SELECT 1 FROM charge_link cl
           WHERE cl.car_id = d.car_id
             AND cl.start_odo IS NOT NULL AND d.end_km IS NOT NULL
             AND abs(cl.start_odo - d.end_km) < 2
         ) AS charge_after_trip
  FROM drives d
  WHERE d.car_id = $1
)
SELECT d.id, d.start_date, d.end_date, d.duration_min, d.distance,
       COALESCE(sg.name, sa.display_name) AS start_place,
       COALESCE(eg.name, ea.display_name) AS end_place,
       sp.battery_level, ep.battery_level,
       -- ⚠️ v0.21.0：**负能耗护栏**。
       --
       -- 能耗是「标定续航的减少量 × 车辆效率」。但标定续航**不降反升**的行程
       -- （标定续航受温度、胎压、驾驶模式影响，充电后重算可能变高）会算出**负数**：
       --   实测 362 条有效行程里 11 条 start_rated_range_km < end_rated_range_km，
       --   例：id=346 跑 30.2 km，标定续航 357.34 → 368.72（**涨了 11.4 km**）
       --   → wh_per_km = −55.0，energy_kwh = −1.66。
       --
       -- 后果：App 会显示「能耗 **−5.5** kWh/100km」——
       -- 物理上不可能的数被印在屏幕上，而且「比上趟省 137%」这类结论会从它身上长出来。
       --
       -- 现在：**差值 ≤ 0 时给 NULL**（而不是 0，也不是负数），
       -- App 显示「能耗 —」—— 不知道就写不知道。
       CASE WHEN d.start_rated_range_km > d.end_rated_range_km THEN
         (d.start_rated_range_km - d.end_rated_range_km)::float8 * c.efficiency
       END AS energy_kwh,
       CASE WHEN d.distance > 0 AND d.start_rated_range_km > d.end_rated_range_km THEN
         (d.start_rated_range_km - d.end_rated_range_km)::float8 * c.efficiency * 1000.0 / d.distance
       END AS wh_per_km,
       d.speed_max, d.outside_temp_avg::float8,
       d.start_km, d.end_km,
       CASE WHEN d.duration_min > 0 THEN round(d.distance::numeric / d.duration_min * 60)::int END AS avg_speed_kph,
       d.ascent, d.descent, d.inside_temp_avg::float8,
       d.power_max, d.power_min,
       d.start_rated_range_km::float8, d.end_rated_range_km::float8,
       d.start_ideal_range_km::float8, d.end_ideal_range_km::float8,
       sg.name, eg.name,
       COALESCE(sc.has_supercharger, false) AS has_supercharger,
       COALESCE(sc.charge_after_trip, false) AS charge_after_trip
FROM drives d
JOIN cars c ON c.id = d.car_id
LEFT JOIN addresses sa ON sa.id = d.start_address_id
LEFT JOIN addresses ea ON ea.id = d.end_address_id
LEFT JOIN geofences sg ON sg.id = d.start_geofence_id
LEFT JOIN geofences eg ON eg.id = d.end_geofence_id
LEFT JOIN positions sp ON sp.id = d.start_position_id
LEFT JOIN positions ep ON ep.id = d.end_position_id
LEFT JOIN drive_sc sc ON sc.drive_id = d.id
WHERE d.car_id = $1 AND d.distance >= 0.1
ORDER BY d.start_date DESC
LIMIT $2 OFFSET $3`

const sqlTrack = `
SELECT p.date,
       p.latitude::float8, p.longitude::float8,
       wgs84_to_gcj02_lat(p.latitude::float8, p.longitude::float8) AS map_lat,
       wgs84_to_gcj02_lng(p.latitude::float8, p.longitude::float8) AS map_lng,
       p.speed, p.power, p.battery_level, p.elevation
FROM positions p
WHERE p.drive_id = $1
ORDER BY p.date`

const sqlCharges = `
WITH ch_agg AS (
  SELECT charging_process_id,
         max(charger_power) AS peak_power_kw,
         round(avg(charger_power) FILTER (WHERE charger_power > 0))::int AS avg_power_kw,
         max(charger_voltage) AS peak_voltage,
         round(avg(charger_voltage) FILTER (WHERE charger_voltage > 0))::int AS avg_voltage,
         max(charger_actual_current) AS peak_current,
         round(avg(charger_actual_current) FILTER (WHERE charger_actual_current > 0))::int AS avg_current,
         (array_agg(charger_phases ORDER BY date DESC))[1] AS last_phases,
         (array_agg(charger_voltage ORDER BY date DESC))[1] AS last_voltage,
         (array_agg(charger_actual_current ORDER BY date DESC))[1] AS last_current,
         (array_agg(conn_charge_cable ORDER BY date DESC))[1] AS cable_type,
         (array_agg(fast_charger_brand ORDER BY date DESC))[1] AS brand
  FROM charges GROUP BY charging_process_id
), ch_pw AS (
  -- ⚠️ v0.21.0：**时间加权**平均功率（真正的「这个桩平均能跑多少 kW」）。
  --
  -- 采样间隔**不均匀**：实测 min 4.9s / avg 8.5~10.1s / max 59.2s
  -- —— 高功率段采得密（5 秒一个点）、掉功率段采得稀（几十秒一个点）。
  -- 所以「按点数平均」必然**高估**。
  --
  -- 实测对照（真实时间均值 = charge_energy_added ÷ (end − start)）：
  --   会话 4  ：采样均值 58 kW vs 真实 39.3 kW  → **高估 48%**
  --   会话 76 ：采样均值 53 kW vs 真实 35.3 kW  → **高估 50%**
  --   63 场直流中位数：65 kW vs 55 kW          → **高估 18%**
  --
  -- 为什么这很重要：它不是装饰数字 —— 它是
  -- 「从 X% 充到 80% 约 N 分钟」的唯一来源。
  -- **功率高估 45% → 耗时低估 31%**：会话 76（15%→100%）真实 72 分钟，
  -- 旧算法给 48 分钟 —— 「服务区还要等多久」乐观了近三分之一。
  --
  -- 算法：Σ(Δt × P) ÷ Σ(Δt)，Δt 用 LAG(date) 求。
  SELECT charging_process_id,
         round(
           SUM(EXTRACT(EPOCH FROM (date - prev)) * charger_power)
           / NULLIF(SUM(EXTRACT(EPOCH FROM (date - prev))), 0)
         )::int AS avg_power_kw_true
  FROM (
    SELECT charging_process_id, date, charger_power,
           LAG(date) OVER (PARTITION BY charging_process_id ORDER BY date) AS prev
    FROM charges
  ) t
  WHERE prev IS NOT NULL AND charger_power > 0
  GROUP BY charging_process_id
),` + cteChargeDC + `
SELECT cp.id, cp.start_date, cp.end_date, cp.duration_min,
       cp.charge_energy_added::float8, cp.charge_energy_used::float8, cp.cost::float8,
       cp.start_battery_level, cp.end_battery_level,
       COALESCE(g.name, a.display_name) AS place,
       wgs84_to_gcj02_lat(sp.latitude::float8, sp.longitude::float8) AS map_lat,
       wgs84_to_gcj02_lng(sp.latitude::float8, sp.longitude::float8) AS map_lng,
       COALESCE(cd.is_dc, false) AS is_dc,
       g.name AS geofence_name,
       cp.start_rated_range_km::float8, cp.end_rated_range_km::float8,
       sp.odometer::float8, ep.odometer::float8,
       ch_agg.peak_power_kw, ch_agg.avg_power_kw,
       ch_pw.avg_power_kw_true,
       ch_agg.peak_voltage, ch_agg.avg_voltage,
       ch_agg.peak_current, ch_agg.avg_current,
       sp.outside_temp::float8, ep.outside_temp::float8,
       sp.inside_temp::float8, ep.inside_temp::float8,
       cp.outside_temp_avg::float8,
       ch_agg.last_phases, ch_agg.last_voltage, ch_agg.last_current,
       ch_agg.cable_type, ch_agg.brand
FROM charging_processes cp
LEFT JOIN addresses a ON a.id = cp.address_id
LEFT JOIN geofences g ON g.id = cp.geofence_id
LEFT JOIN positions sp ON sp.id = cp.position_id
LEFT JOIN positions ep ON ep.id = (SELECT id FROM positions WHERE car_id = cp.car_id AND date <= cp.end_date ORDER BY date DESC LIMIT 1)
LEFT JOIN ch_agg ON ch_agg.charging_process_id = cp.id
LEFT JOIN ch_pw ON ch_pw.charging_process_id = cp.id
LEFT JOIN charge_dc cd ON cd.charging_process_id = cp.id
WHERE cp.car_id = $1 AND cp.charge_energy_added > 0.01
ORDER BY cp.start_date DESC
LIMIT $2 OFFSET $3`

const sqlChargeCurve = `
SELECT date, battery_level, charger_power, charger_voltage,
       charger_actual_current, charger_phases
FROM charges
WHERE charging_process_id = $1
ORDER BY date`

// 按本地时区聚合。$1=car_id $2=granularity('day'|'month') $3=时区名 $4=窗口起点(timestamptz)
const sqlStatsDrives = `
SELECT to_char(date_trunc($2, (d.start_date AT TIME ZONE 'UTC') AT TIME ZONE $3), 'YYYY-MM-DD') AS bucket,
       COALESCE(SUM(d.distance), 0)::float8 AS km,
       COALESCE(SUM(d.duration_min), 0)::float8 AS minutes,
       -- ⚠️ v0.21.0：**负能耗护栏**（与 sqlDrives 同一套判据）。
       -- 标定续航不降反升的行程会算出负的 kWh，汇总时会把真实用量**拉低**。
       -- 实测 30 天窗口里负值污染了 −11.78 度（1.3%）。
       COALESCE(SUM(CASE WHEN d.start_rated_range_km > d.end_rated_range_km
         THEN (d.start_rated_range_km - d.end_rated_range_km) * c.efficiency END), 0)::float8 AS kwh,
       COUNT(*)
FROM drives d
JOIN cars c ON c.id = d.car_id
WHERE d.car_id = $1 AND d.distance >= 0.1
  AND (d.start_date AT TIME ZONE 'UTC') >= $4
GROUP BY 1 ORDER BY 1`

const sqlStatsCharges = `
WITH` + cteChargeDC + `
SELECT to_char(date_trunc($2, (cp.start_date AT TIME ZONE 'UTC') AT TIME ZONE $3), 'YYYY-MM-DD') AS bucket,
       COALESCE(SUM(cp.charge_energy_added), 0)::float8,
       -- ⚠️ v0.21.0：直流/交流的判定改用**会话级**判据（cteChargeDC），
       -- 不再是「任一采样点相数为 NULL」的 or —— 一个异常点就能把交流算进直流。
       COALESCE(SUM(CASE WHEN cd.is_dc THEN cp.charge_energy_added ELSE 0 END), 0)::float8 AS dc_kwh,
       COALESCE(SUM(cp.cost), 0)::float8,
       COUNT(*)
FROM charging_processes cp
LEFT JOIN charge_dc cd ON cd.charging_process_id = cp.id
WHERE cp.car_id = $1 AND cp.charge_energy_added > 0.01 AND cp.end_date IS NOT NULL
  AND (cp.start_date AT TIME ZONE 'UTC') >= $4
GROUP BY 1 ORDER BY 1`

// 满充容量（kWh）= 满充时额定续航(km) × 车辆效率(kWh/km)；
// 非满充会话按 usable_battery_level 折算：capacity = rated_range × eff × 100 / soc
//
// ⚠️ v0.22.0（电池健康专家 P0-1）：**把用了哪个常数一起下发**。
//
// `c.efficiency` 是车主为修 Wh/km 显示而配的**实际行驶能耗**
// （含空调/路况/驾驶风格），与「电池容量」不是同一个 kWh/km ——
// 把它改成 0.153，健康度会从 84.9% 跳到 88.9%。
// 现在每个点都带上 `efficiency_used`，至少**可核对**；
// 而真正不受它影响的健康指标是 `health_percent_of_baseline`（见 handlers.go）。
//
// 另外两个新列：
//   odometer_km         —— 该次测量时的里程表（算「每万公里衰减」用）
//   capacity_kwh_energy —— **能量反推**口径（充入 kWh ÷ ΔSOC% × 100），
//                          完全不需要 kWh/km 常数，用来与主口径互相印证
const sqlBattery = `
SELECT cp.end_date,
       (ch.rated_battery_range_km::float8 * c.efficiency * 100.0 / ch.usable_battery_level) AS capacity_kwh,
       cp.end_battery_level,
       c.efficiency AS efficiency_used,
       ep.odometer::float8 AS odometer_km,
       CASE WHEN (cp.end_battery_level - cp.start_battery_level) >= 5
             AND cp.charge_energy_added > 0
         THEN cp.charge_energy_added * 100.0
              / (cp.end_battery_level - cp.start_battery_level)
       END AS capacity_kwh_energy
FROM charging_processes cp
JOIN cars c ON c.id = cp.car_id
JOIN LATERAL (
  SELECT rated_battery_range_km, usable_battery_level
  FROM charges
  WHERE charging_process_id = cp.id AND usable_battery_level > 0
    AND rated_battery_range_km IS NOT NULL
  ORDER BY date DESC LIMIT 1
) ch ON true
LEFT JOIN positions ep ON ep.id = (
  SELECT id FROM positions
  WHERE car_id = cp.car_id AND date <= cp.end_date AND odometer IS NOT NULL
  ORDER BY date DESC LIMIT 1
)
WHERE cp.car_id = $1
  AND cp.end_date IS NOT NULL
  AND cp.charge_energy_added > 0.01
  AND cp.end_battery_level >= 90
ORDER BY cp.end_date`
