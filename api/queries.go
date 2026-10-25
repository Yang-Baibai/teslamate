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

// ⚠️ v0.24.0（长途充电规划专家 P0-2）：**真实逐公里 SOC 剖面**（单趟）。
//
// 原来 App 的线路余量剖面只有 2~6 个点（起点电量、充电跳升、终点电量），
// 中间是**直线插值** —— 山区长途（爬升 1500~2600 m）误差实测达 **13 公里**：
// 真实跌破 20% 保底在第 244 公里，插值算出来是 231 公里。
// 用户按 231 公里规划，到 244 公里时电已经见底 —— **会导致危险决策**。
//
// 数据本来就在库里：positions 有 162 万个点，采样间隔中位 **0.251 秒**。
// 现在按**里程 5 公里**分箱，取箱内 battery_level 的**中位数**
// （中位数比均值抗离群 —— 停车开空调时的短暂掉电不会带偏整箱）。
//
// ⚠️ 为什么是**独立端点**而不是随 /drives 一起返回：
// 全表算 11 趟长途的剖面要 350 ms（positions 只有 BRIN 索引，
// 一次查全部长途会退化成全表扫描），会让记录页首屏多等 280 ms。
// 单趟查只要 **19 ms**（BRIN 的 drive_id 索引用上了）—— 按需拉才对。
//
// $1 = drive_id，$2 = 分箱步长（km）
const sqlSocProfile = `
SELECT floor((p.odometer - sp.odometer) / $2) * $2 AS km,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY p.battery_level)::float8 AS soc
FROM positions p
JOIN drives d ON d.id = p.drive_id
JOIN positions sp ON sp.id = d.start_position_id
WHERE p.drive_id = $1
  AND p.battery_level IS NOT NULL
  AND p.odometer IS NOT NULL
GROUP BY 1 ORDER BY 1`

// ⚠️ v0.25.0（第 16 轮充电成本专家 P0-2 / P1-7）：**成本汇总**（给定时间窗口）。
//
// 为什么要单独一个端点：
//   - 用量页的「7 天 / 30 天 / 1 年」是**服务端时区**的窗口，
//     而客户端的 `api.charges()` 是「最近 N 场」—— 两者范围不同，
//     混用会算出「用 30 天的公里数除以全部历史的度数」这种错数；
//   - 年度回顾需要**全量**行程（库里 440 趟），而 `api.drives` 上限 200 ——
//     靠客户端拉全量做不出来。
//
// $1=car_id $2=窗口起点(timestamptz)
//
// ⚠️ **v0.30.0（第 19 轮财务评审 P0-3）：增加「围栏命中」家充桶。**
//
// 原来 App 只能按「交流 / 直流」两分，而本机实测：
// **5 场交流慢充全部在 286~1506 公里外**（甘孜的酒店、广西梧州的路边），
// 没有一场在家 —— 「交流 ⇒ 家充」这个判据 **5/5 全错**。
//
// 现在 TeslaMate 已经有 `geofences`（本机有一行「家」），
// 新会话会带上 `geofence_id` —— 这才是**真凭据**，不再靠猜。
//
// ⚠️ 家充桶是**交流的子集**（`NOT is_dc AND geofence_id IS NOT NULL`），
// 不是第三个独立桶 —— 客户端用 `ac_used_kwh − home_used_kwh`
// 得到「没有围栏归属的交流度数」，两者不会重复计数。
const sqlCostSummary = `
WITH` + cteChargeDC + `
SELECT COALESCE(SUM(CASE WHEN cd.is_dc THEN cp.charge_energy_used ELSE 0 END), 0)::float8 AS dc_used_kwh,
       COALESCE(SUM(CASE WHEN NOT cd.is_dc THEN cp.charge_energy_used ELSE 0 END), 0)::float8 AS ac_used_kwh,
       COUNT(*) FILTER (WHERE cd.is_dc)::int AS dc_sessions,
       COUNT(*) FILTER (WHERE NOT cd.is_dc)::int AS ac_sessions,
       COALESCE(SUM(CASE WHEN cd.is_dc THEN cp.duration_min ELSE 0 END), 0)::float8 AS dc_minutes,
       COALESCE(SUM(CASE WHEN NOT cd.is_dc THEN cp.duration_min ELSE 0 END), 0)::float8 AS ac_minutes,
       -- ⚠️ v0.30.0：围栏命中的**真家充**（交流 ∧ 有围栏归属）
       COALESCE(SUM(CASE WHEN NOT cd.is_dc AND cp.geofence_id IS NOT NULL
                          THEN cp.charge_energy_used ELSE 0 END), 0)::float8 AS home_used_kwh,
       COUNT(*) FILTER (WHERE NOT cd.is_dc AND cp.geofence_id IS NOT NULL)::int AS home_sessions,
       COALESCE(SUM(CASE WHEN NOT cd.is_dc AND cp.geofence_id IS NOT NULL
                          THEN cp.duration_min ELSE 0 END), 0)::float8 AS home_minutes
FROM charging_processes cp
LEFT JOIN charge_dc cd ON cd.charging_process_id = cp.id
WHERE cp.car_id = $1
  AND cp.end_date IS NOT NULL
  AND cp.charge_energy_added > 0.01
  AND (cp.start_date AT TIME ZONE 'UTC') >= $2`

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
         (array_agg(fast_charger_brand ORDER BY date DESC))[1] AS brand,
         -- ⚠️ v0.24.0（长途充电规划专家 P1-5）：**是不是特斯拉超充** ——
         -- 官方 App 的核心区分（超充 / 国标直流 / 交流），
         -- 我们原来只有「直流 / 交流」两档，用户没法判断「这个桩是不是超充」。
         bool_or(fast_charger_brand = 'Tesla') AS tesla_sc
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
       -- ⚠️ v0.24.0（长途充电规划专家 P1-4）：**真实 POI 名**。
       --
       -- 原来显示的是**反向地理编码的长串**（「汕昆高速公路, 北庄, 宜州区, 河池市…」），
       -- 而库里其实有 OSM 的原始 POI 名 —— 「金田服务区」「合江荔乡服务区」这种。
       -- 用户要找的是「**哪个服务区**」，不是一段地址。
       NULLIF(a.raw->>'name', '') AS poi_name,
       -- POI 类型（services / charging_station / fuel …）
       NULLIF(a.raw->>'type', '') AS poi_type,
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
       ch_agg.cable_type, ch_agg.brand,
       COALESCE(ch_agg.tesla_sc, false) AS is_tesla_sc
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

// ⚠️ v0.26.0：**整条路线**的逐公里 SOC 剖面（多趟行程拼成一条）。
//
// ## 为什么需要这个端点
//
// sqlSocProfile（v0.24.0）一次只算**一趟**行程的剖面。
// 但现实里一次长途旅程是**被充电切成很多段**的：
// 出发 → 开 300 km → 充 40 分钟 → 再开 200 km → 再充 → ……
// App 想画的是**一条连续的余量曲线**，而不是七八张互不相干的碎片。
// 所以需要把多趟行程按行驶顺序拼成一条剖面。
//
// ## 口径（和 sqlSocProfile 完全一致）
//
// 每段内部按 step_km 分桶，取箱内 battery_level 的**中位数**
// （percentile_cont(0.5)）—— 中位数比均值抗离群，
// 停车开空调时的短暂掉电不会带偏整箱。
//
// 分桶用**绝对里程**算，最后再减去整条路线的起点里程换成相对 km：
//   桶起点 = floor((里程 − 本段起点里程) / step_km) * step_km + 本段起点里程
//   相对 km = 桶起点 − 路线起点里程
//
// ⚠️ 为什么 km_offset 也要用「里程差」而不是「前面几段的里程累加」：
//   km_offset 必须**正好落在本段第一个采样点上**，
//   否则 App 把段边界和曲线对不上，会画出一段错位的折线。
//   用里程差时，本段第一个桶就是 floor(...)=0 的那一箱，
//   相对 km 恰好等于本段起点里程 − 路线起点里程 = km_offset，对得上。
//
// ## 性能（实测，2026-10-25，positions 162 万点 / 20551 页）
//
// 用 p.drive_id = ANY($1) 命中 BRIN，**不是全表扫描**：
//   3 段  → lossy heap 2197 页
//   12 段 → lossy heap 1556 页，Index Searches: 12
//   200 段 → lossy heap 11399 页，Index Searches: 200
// 段数越多覆盖的时间跨度越大，BRIN 摘要越粗，这是 BRIN 的固有粒度；
// 但**始终是 Bitmap Index Scan**，没有一次 Seq Scan。
//
// 200 段的聚合要排 86 万行：默认 work_mem=4MB 时**溢写到磁盘**
// （external sort 27 MB）→ 2609 ms；
// 提到 64MB 以上就不溢写 → 350 ms 左右（快 7 倍）。
//
// ⚠️ 另一个坑：**通用计划**。PostgreSQL 对同一个 prepared statement
// 执行 5 次后会切到「通用计划」，那时优化器不知道数组里有哪些 drive_id，
// 于是每行再 AND 一次数组 → Index Searches 从 200 涨到 **40000** → 3850 ms。
// 所以 handler 里必须 SET LOCAL plan_cache_mode = force_custom_plan。
//
// $1 = drive_id 数组（有序），$2 = car_id，$3 = 分箱步长（km）
const sqlRouteProfile = `
WITH ordered AS (
  -- 用 unnest ... WITH ORDINALITY 保住 App 传进来的**行驶顺序**。
  -- 为什么不能直接 ORDER BY d.start_date：
  --   App 传什么顺序就是什么顺序（它可能已经按「充电段」排好序了），
  --   服务端再按时间排一次等于替 App 做决定。
  SELECT o.ord AS ord,
         d.id AS drive_id,
         d.start_km::float8 AS start_km,
         d.end_km::float8 AS end_km,
         d.distance::float8 AS distance_km
  FROM unnest($1::int4[]) WITH ORDINALITY AS o(drive_id, ord)
  JOIN drives d ON d.id = o.drive_id AND d.car_id = $2
  -- 起止里程缺失的行程没法定位，直接剔除（不做插值）
  WHERE d.start_km IS NOT NULL AND d.end_km IS NOT NULL
), buckets AS (
  -- 每段按 step_km 分桶，取箱内电量的中位数。
  -- ⚠️ WHERE 里必须再写一遍 p.drive_id = ANY($1)：
  --   光靠 JOIN conditions 的话优化器可能选另一条路（实测会退化成逐段嵌套循环，
  --   12 段时索引被扫 144 次）。显式写出来才稳定命中 BRIN 的 Bitmap Index Scan。
  SELECT l.drive_id,
         floor((p.odometer - l.start_km) / $3) * $3 + l.start_km AS abs_km,
         percentile_cont(0.5) WITHIN GROUP (ORDER BY p.battery_level)::float8 AS soc
  FROM ordered l
  JOIN positions p ON p.drive_id = l.drive_id
  WHERE p.drive_id = ANY($1::int4[])
    AND p.battery_level IS NOT NULL
    AND p.odometer IS NOT NULL
  GROUP BY 1, 2
), leg_info AS (
  -- 只保留**真的有采样点**的段。
  -- ⚠️ 为什么在 buckets 之后过滤而不是单独查一次 positions：
  --   buckets 已经把「这一段有没有可用采样」算清楚了（有没有桶），
  --   再查一次 positions 等于把最贵的那一步做两遍。
  -- 一段都没有可用采样（battery_level 全空 / odometer 全空）的行程
  -- 直接跳过 —— **不伪造点**，宁可少一段也不要画出一条假的平线。
  SELECT o.ord, o.drive_id, o.start_km, o.end_km, o.distance_km
  FROM ordered o
  JOIN (SELECT DISTINCT drive_id FROM buckets) k ON k.drive_id = o.drive_id
), origin AS (
  -- 路线起点 = 各段起点里程的最小值。
  -- 相对 km 全部由它换算，所以整条剖面共用同一个原点。
  SELECT MIN(start_km) AS base_km FROM leg_info
)
SELECT li.ord,
       li.drive_id,
       li.start_km,
       li.end_km,
       li.distance_km,
       li.start_km - o.base_km AS km_offset,
       b.abs_km - o.base_km AS km,
       b.soc
FROM leg_info li
JOIN buckets b ON b.drive_id = li.drive_id
CROSS JOIN origin o
ORDER BY li.ord, b.abs_km`

// ⚠️ v0.27.0：**驾驶风格画像**（最近 N 趟行程的评分 + 汇总）。
//
// ## 口径（三步，缺一不可）
//
// ① **1 Hz 重采样**：`floor(EXTRACT(EPOCH FROM p.date))` 分桶，桶内取**最后一个**速度值。
//    ⚠️ 绝对不要直接微分 `positions.speed`：它是 smallint（整 km/h），
//    相邻点间隔中位 **0.251 秒** —— 逐点微分会造出 **117 万个恰好 ±8.00 km/h/s** 的
//    纯量化伪信号（实测），那不是驾驶风格，那是采样精度。
//    先降到 1 Hz 再比，噪声底 dv2 的 p01 = −12.0，阈值 20 才有约 8σ 余量。
//
// ② **2 秒窗口速度落差** dv2 = v(t) − v(t−2)：用 `LAG(v,2)` 拿 t−2 秒那个桶的值。
//
// ③ **事件判定**：硬刹车 dv2 ≤ −20，硬加速 dv2 ≥ +20（≈ ±2.8 m/s²）。
//
// ## 为什么窗口跨度要卡在 12 秒
//
// `LAG(v,2)` 拿的是「序列里往前数第二个桶」，它**不保证**真的隔了 2 秒：
// 采样有断洞时，两个桶可能隔了 13 秒 —— 那时候 dv2 量的根本不是 2 秒的落差，
// 而是十几秒的平均速率，判出来的「急加速」是假的。
// 实测（2026-10-25，drive_id=273）：断洞那一下算出 dv2 = +20，恰好踩在阈值上，
// 把一趟夜间市区行程的急加速从 3 次虚报成 4 次，分数从 21.1 掉到 20.0。
//
// 所以窗口必须**真的**在 2 秒附近：**跨度 2~12 秒**（名义 2 秒 + 最多 10 秒数据断点，
// 与下面能耗积分「丢弃 Δt > 10 秒的数据断点」是同一条容忍线）。
// 12 秒这条线是实测标出来的：容忍度放宽到 13 秒，drive_id=273 立刻多出 1 次假急加速；
// 收紧到 11 秒，drive_id=491 会漏掉 2 次真急刹车（它的采样断洞是 12 秒）。
//
// ## 能耗（车轮功率交叉验证）
//
// 梯形积分：`(power + prev_power)/2 × Δt/3600` = kWh，丢弃 Δt > 10 秒的断点。
// 它同时用来**佐证事件方向**：功率掉下去的时刻应该和 dv2 掉下去的时刻对得上。
//
// ## 时区陷阱
//
// `positions.date` / `drives.start_date` 存的是 **UTC naive**。
// ❌ `AT TIME ZONE 'Asia/Shanghai'` 直接作用在 naive 列上 → 偏 8 小时。
// ✅ 正确形式：`col AT TIME ZONE 'UTC' AT TIME ZONE $2`（与 sqlStatsDrives 同一写法）。
//
// ## 夜间占比为什么用另一套窗口（exact2）
//
// 事件（急刹/急加速）和超速占比用的是「序列里往前数第二个桶」（能容忍采样断洞）。
// 但夜间占比问的是**时间归属**：「这一刻算夜里吗」。
// 跨了数据断洞的窗口说不清该按哪一秒算，所以只统计**完整的 2 秒观测**
// （t 和 t−2 秒那一刻都真的有采样桶）。
// 实测 drive_id=273：完整窗口 1086 个，其中夜间 1085 个 → **99.91%**
// （这趟 21:59:52 起夜，开头那几秒采样是断的，不算进分母）。
//
// ## 性能（实测 2026-10-25，positions 162 万点 / 20551 页）
//
// `p.drive_id = ANY(ARRAY(SELECT drive_id FROM recent))` 命中 BRIN：
// Bitmap Index Scan + Index Searches = 段数，**没有一次 Seq Scan on positions**。
// handler 里必须配 `SET LOCAL work_mem='128MB'` + `SET LOCAL plan_cache_mode=force_custom_plan`
// （理由见 handleRouteProfile：通用计划会让 Index Searches 从 200 涨到 40000）。
//
// $1 = car_id，$2 = 时区名，$3 = 最小行程里程(km)，$4 = 取最近多少趟，
// $5 = 2 秒窗口允许的最大跨度（秒）
const sqlDrivingProfile = `
WITH recent AS (
  SELECT d.id AS drive_id, d.start_date, d.distance::float8 AS distance_km,
         d.duration_min, d.ascent, d.descent
  FROM drives d
  WHERE d.car_id = $1 AND d.distance >= $3
  ORDER BY d.start_date DESC
  LIMIT $4
), hz AS (
  -- ① 1 Hz 重采样：桶内取最后一个速度值
  SELECT p.drive_id,
         floor(EXTRACT(EPOCH FROM p.date))::bigint AS sec,
         (array_agg(p.speed ORDER BY p.date DESC))[1] AS v,
         max(p.date) AS t
  FROM positions p
  WHERE p.drive_id = ANY(ARRAY(SELECT drive_id FROM recent))
    AND p.speed IS NOT NULL
  GROUP BY 1, 2
), win AS (
  -- ② 2 秒窗口：v2 是「序列里往前数第二个桶」，span 是这两个桶的真实时间跨度。
  --    s1 是紧挨着的上一个桶 —— 用来判断「t−2 秒那一刻到底有没有采样」。
  SELECT hz.drive_id, hz.sec, hz.v, hz.t,
         LAG(hz.v,2) OVER w AS v2,
         LAG(hz.sec,2) OVER w AS s2,
         LAG(hz.sec,1) OVER w AS s1
  FROM hz
  WINDOW w AS (PARTITION BY hz.drive_id ORDER BY hz.sec)
), win2 AS (
  SELECT drive_id, v, v2,
         (sec - s2) AS span,
         -- 「完整的 2 秒观测」：t 和 t−2 秒那一刻**都**有采样桶。
         -- （t−2 时刻的桶要么是 s1，要么是 s2 —— 只可能是这两个之一。）
         (s1 = sec - 2 OR s2 = sec - 2) AS exact2,
         (EXTRACT(HOUR FROM ((t AT TIME ZONE 'UTC') AT TIME ZONE $2))::int >= 22
          OR EXTRACT(HOUR FROM ((t AT TIME ZONE 'UTC') AT TIME ZONE $2))::int < 6) AS night
  FROM win
), ev AS (
  SELECT drive_id,
         count(*) FILTER (WHERE span BETWEEN 2 AND $5 AND v - v2 <= -20)::int AS hard_brake,
         count(*) FILTER (WHERE span BETWEEN 2 AND $5 AND v - v2 >=  20)::int AS hard_accel,
         count(*) FILTER (WHERE span BETWEEN 2 AND $5 AND v > 120)::int AS over_n,
         count(*) FILTER (WHERE span BETWEEN 2 AND $5)::int AS n_valid,
         count(*) FILTER (WHERE exact2)::int AS n_clean,
         count(*) FILTER (WHERE exact2 AND night)::int AS night_n
  FROM win2
  GROUP BY drive_id
), pw AS (
  -- 能耗：车轮功率梯形积分，丢弃 Δt > 10 秒的数据断点
  --
  -- ⚠️ **v0.29.0（复审 P0-2）：同时算「毛耗电」（不含回充抵扣）。**
  --
  -- 原来只算净值（(P_i + P_{i-1})/2 × Δt）—— 净值会**把下坡回充抵扣掉**，
  -- 于是 2026-10-04 那两趟下坡的 wh_per_km 只有 21.6 / 3.6，
  -- 「能耗」这一项**不扣分**（阈值 150 以下免赔）→ **白送 +15 分**。
  -- 评审原话：「下山比平稳开车『加分』。」
  --
  -- 毛耗电 = max(P_i, 0) × Δt，不含回充 → 下坡不再白送分。
  SELECT drive_id,
         SUM((power + ppower) / 2.0 * dt / 3600.0) AS kwh,
         SUM(GREATEST(power, 0) * dt / 3600.0) AS kwh_gross
  FROM (
    SELECT p.drive_id, p.power,
           LAG(p.power) OVER (PARTITION BY p.drive_id ORDER BY p.date) AS ppower,
           EXTRACT(EPOCH FROM (p.date - LAG(p.date) OVER (PARTITION BY p.drive_id ORDER BY p.date))) AS dt
    FROM positions p
    WHERE p.drive_id = ANY(ARRAY(SELECT drive_id FROM recent))
      AND p.power IS NOT NULL
  ) t
  WHERE ppower IS NOT NULL AND dt > 0 AND dt <= 10
  GROUP BY drive_id
)
SELECT r.drive_id, r.start_date, r.distance_km,
       -- ⚠️ duration_min / ascent / descent 在表里是**可空**的，
       -- 直接扫进 Go 的 int 遇到 NULL 会报 "can't scan into dest[N]"（整个端点 500）。
       -- 这里 COALESCE 成 0：宁可少一个字段，也不能让一次数据缺失打挂整个请求。
       COALESCE(r.duration_min, 0), COALESCE(r.ascent, 0), COALESCE(r.descent, 0),
       COALESCE(e.hard_brake, 0), COALESCE(e.hard_accel, 0),
       COALESCE(e.over_n, 0), COALESCE(e.n_valid, 0),
       COALESCE(e.n_clean, 0), COALESCE(e.night_n, 0),
       COALESCE(p.kwh, 0),
       -- ⚠️ v0.29.0：毛耗电（评分用这个，净值只作参考）
       COALESCE(p.kwh_gross, 0)
FROM recent r
LEFT JOIN ev e ON e.drive_id = r.drive_id
LEFT JOIN pw p ON p.drive_id = r.drive_id
ORDER BY r.start_date DESC`

// ⚠️ v0.28.0：**常驻点**（车平时停在哪 / 停多久）。
//
// ## 语义：为什么只能是「**推断停留**」
//
// 车停下来之后 TeslaMate 就**不再记录位置**了（states 里 asleep 44 / offline 123），
// 所以「现在停了多久」在数据库里**根本不存在** —— 官方 App 能告诉你「车现在在哪」，
// 但那是**当下**的位置；这里告诉你的是**它平时停在哪、停多久**。
//
// 唯一能算出来的口径是：**相邻两趟行程之间的时间间隙** ——
// 上一趟结束到下一趟开始，中间那段时间车就是停着的。
// 界面文案必须写「推断停留」，**不能**写「停车时长」（那会让人以为是实时监控）。
//
// ## 为什么归属到 **start_address_id** 而不是 end_address_id
//
// 同一趟间隙的两端描述的是**同一个物理地点**，但 geocoder 可能给两个不同的
// addresses 行（实测本机 267 行地址里，「新湖路」与「永丰社区」是同一个停车点，
// 相距约 170 m，坐标差 0.0004°）。用哪个键，结果**差得离谱**：
//
//   按 end_address_id 分组：新湖路 1 次 / 47.3 h，永丰社区 10 次 / 748.8 h
//   按 start_address_id 分组：新湖路 20 次 / 990.2 h，永丰社区 7 次 / 320.8 h
//
// 「这趟行程是**从哪儿开出去**的」= 车停着的位置 = 下一趟的起点，
// 所以用 **start_address_id** 才是对的（用 end_address_id 会把停车点记到隔壁地址上）。
//
// ## 性能
//
// drives 440 行 + addresses 267 行 —— 都是**小表**，
// `Seq Scan on drives` 在这里是**正确**的选择（走 trips_car_id_index 反而更慢，
// 因为索引扫描后还要回表 440 次）。实测 < 5 ms，无需 work_mem 调优。
//
// $1 = car_id，$2 = 最小停留小时数，$3 = 最多返回几个常驻点
const sqlStayPoints = `
WITH o AS (
  SELECT d.id, d.start_date, d.end_date, d.start_address_id
  FROM drives d
  WHERE d.car_id = $1 AND d.end_date IS NOT NULL AND d.start_address_id IS NOT NULL
), g AS (
  -- LAG 拿到「上一趟的结束时间」；两趟之间的空档就是这台的停留时长。
  -- ⚠️ 第一趟没有 prev_end（NULL），WHERE 里过滤掉。
  SELECT o.*, LAG(o.end_date) OVER (ORDER BY o.start_date) AS prev_end
  FROM o
)
SELECT a.id,
       a.display_name,
       a.latitude::float8 AS latitude,
       a.longitude::float8 AS longitude,
       count(*)::int AS visits,
       sum(EXTRACT(epoch FROM (g.start_date - g.prev_end)) / 3600.0) AS total_hours,
       avg(EXTRACT(epoch FROM (g.start_date - g.prev_end)) / 3600.0) AS avg_hours,
       max(EXTRACT(epoch FROM (g.start_date - g.prev_end)) / 3600.0) AS max_hours,
       min(g.start_date) AS first_seen,
       max(g.start_date) AS last_seen
FROM g
JOIN addresses a ON a.id = g.start_address_id
WHERE g.prev_end IS NOT NULL
  -- ⚠️ 阈值过滤放在聚合**之前**：先滤掉不够长的间隙，再统计 ——
  -- 否则 avg/max 会被一堆 5 分钟的短停拉偏。
  AND EXTRACT(epoch FROM (g.start_date - g.prev_end)) / 3600.0 >= $2
GROUP BY a.id, a.display_name, a.latitude, a.longitude
ORDER BY total_hours DESC
LIMIT $3`

// ⚠️ v0.28.0：**能耗-车速曲线**（不同车速下真实耗多少电）。
//
// ## 为什么用「车轮功率梯形积分」而不是「续航差 × cars.efficiency」
//
// 官方 App 只能给**平均** Wh/km；标定法（标定续航差 × 效率常数）也只有一个平均值，
// 而且**系统性高估约 7%**（实测标定法/积分法中位比 0.9285，相关系数 0.99686）。
// 梯形积分给的是**一条曲线**，这才是官方给不了的。
//
// ## ⚠️ 坑一：**不能用相邻点的 odometer 差分算距离**
//
// 里程表更新比 positions **稀疏**（一个采样点对应一次里程表上报），
// 逐点差分会漏掉大量里程 —— 评审员第一版算出了「市区 8 Wh/km」的荒谬值。
// 正确做法：**按 1 km 分箱，用箱与箱之间的里程表跨度**算距离。
//
// ## ⚠️ 坑二：**必须按 drive_id 过滤**，否则全表扫描
//
// `p.drive_id = ANY(ARRAY(SELECT id FROM drives WHERE car_id = $1))`
// 命中 positions 上的 BRIN 索引 (drive_id, date)：Index Searches = 行程数，
// **没有一次 Seq Scan on positions**。
// handler 里必须配 `SET LOCAL work_mem='128MB'` + `SET LOCAL plan_cache_mode=force_custom_plan`
// （通用计划会让 Index Searches 从 N 涨到 N×N，理由见 handleRouteProfile）。
//
// ## 分档口径
//
// `floor(spd/10)*10+5` —— 以 5 km/h 为中心、10 km/h 一档：
// 5→[0,10)  15→[10,20)  25→[20,30) …… 135→[130,140)。
// App 侧下发的标签是 0/15/25/…/135/145+（见 energyBandEdges），
// 最后一档把 ≥140 km/h 的分箱并进来。
//
// $1 = car_id
const sqlEnergyProfile = `
WITH s AS (
  -- ⚠️ 窗口必须按 **date** 排序，**不能**按 odometer 排：
  -- 里程表在单趟行程内**并不单调**（实测 161 万点里有 35245 个逆序点、涉及 437/440 趟），
  -- 按 odometer 排会让梯形积分的相邻点配错，能耗算出来是错的。
  SELECT p.drive_id,
         p.odometer,
         p.speed,
         floor(p.odometer)::bigint AS km_bin,
         -- 梯形积分：(P_i + P_{i-1})/2 × Δt_i，单位 kWh
         (p.power + LAG(p.power) OVER w) / 2.0
           * (EXTRACT(EPOCH FROM (p.date - LAG(p.date) OVER w)) / 3600.0) AS kwh,
         -- ⚠️ v0.29.0：**毛耗电**（不含回充抵扣）= max(P_i, 0) × Δt_i
         --
         -- 为什么必须要它：梯形积分的净值会**把下坡回充抵扣掉**，
         -- 于是「40–50 km/h 那一档 118.8 Wh/km」看着像最省电，
         -- 实际是**一次下坡 + 一次长途**的产物 —— 物理上不可能（这台 AWD Performance 做不到）。
         -- 评审复算：扣掉回充后那一档是 215.3 Wh/km，而**最低档变成 100–110 km/h**。
         --
         -- 净值的意义是「这一趟**净**消耗了多少电」（长途规划看这个）；
         -- 毛值的意义是「**从电池里取走**了多少电」（看效率曲线必须用这个）。
         GREATEST(p.power, 0)
           * (EXTRACT(EPOCH FROM (p.date - LAG(p.date) OVER w)) / 3600.0) AS kwh_gross,
         (p.date - LAG(p.date) OVER w) AS dt
  FROM positions p
  WHERE p.drive_id = ANY(ARRAY(SELECT id FROM drives WHERE car_id = $1))
    AND p.power IS NOT NULL
    AND p.odometer IS NOT NULL
  WINDOW w AS (PARTITION BY p.drive_id ORDER BY p.date)
), pair AS (
  -- 丢弃 Δt > 10 秒的数据断点（采样断了就别跨着算平均功率）
  SELECT drive_id, odometer, speed, km_bin, kwh, kwh_gross
  FROM s
  WHERE dt IS NOT NULL AND dt <= interval '10 seconds'
), b AS (
  -- ⚠️ 按 1 km 分箱聚合；距离**不**在这里算，要等下一层的箱间跨度
  SELECT drive_id,
         km_bin,
         sum(kwh) AS kwh,
         sum(kwh_gross) AS kwh_gross,
         max(odometer) AS odo_max,
         avg(speed) AS spd
  FROM pair
  GROUP BY 1, 2
), b2 AS (
  -- 箱间跨度 = 本箱最大里程表 − 上一箱最大里程表（这才是「走了多远」）
  SELECT b.*,
         LAG(odo_max) OVER (PARTITION BY drive_id ORDER BY km_bin) AS prev
  FROM b
), f AS (
  SELECT drive_id,
         spd,
         kwh,
         kwh_gross,
         odo_max - prev AS dist_km
  FROM b2
  WHERE prev IS NOT NULL AND (odo_max - prev) > 0
)
SELECT (floor(spd / 10) * 10 + 5)::int AS band_key,
       count(*)::int AS n_bins,
       sum(dist_km)::float8 AS km,
       sum(kwh)::float8 AS kwh_total,
       -- ⚠️ v0.29.0：毛耗电（不含回充抵扣）——「最省电档」要按它选
       sum(kwh_gross)::float8 AS kwh_gross_total
FROM f
GROUP BY 1
ORDER BY 1`
