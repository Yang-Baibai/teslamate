# 手机 API 接口文档（api 服务 · 端口 8090）

- Base URL：`http://<主机>:8090`
- 鉴权：除 `/healthz` 外全部要求 `Authorization: Bearer <API_TOKEN>`（令牌在项目根 `.env` 的 `API_TOKEN=`）
- 数据格式：JSON，UTF-8；**时间全部为 UTC（ISO-8601，Z 结尾），客户端自行转本地时区**
- 源码：`api/main.go`（路由/鉴权）、`api/handlers.go`（处理器）、`api/queries.go`（全部 SQL）、`api/models.go`（数据模型）

## 端点一览

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | /healthz | 健康检查（免鉴权） |
| GET | /api/v1/cars | 车辆列表 |
| GET | /api/v1/cars/{id}/status | 实时车况 |
| GET | /api/v1/cars/{id}/drives?limit=&offset= | 行程列表（默认 20，最大 200） |
| GET | /api/v1/drives/{id}/track | 单次行程轨迹（自动降采样 ≤1500 点） |
| GET | /api/v1/cars/{id}/charges?limit=&offset= | 充电记录（默认 20，最大 200） |
| GET | /api/v1/charges/{id}/curve | 单次充电分钟级曲线 |
| GET | /api/v1/cars/{id}/stats?period=week\|month\|year | 桶聚合统计（周=7 天/月=30 天/年=12 月） |
| GET | /api/v1/cars/{id}/battery | 电池容量趋势与健康度 |

错误统一格式：`{"error":"人类可读原因"}`，配合 HTTP 401/404/500。

## 各端点字段说明（节选关键字段）

### /cars
`id` `name` `model` `trim_badging` `vin` `marketing_name` `efficiency`(kWh/km) `exterior_color` `wheel_type`

### /cars/{id}/status
| 字段 | 说明 |
|---|---|
| state | online / asleep / offline（states 表最新） |
| battery_level / usable_battery_level | 电量 % |
| rated_range_km / est_range_km | 续航 km（rated=标定，est=预估） |
| odometer / speed / power | 里程表 km / km/h / kW（负值=动能回收） |
| outside_temp / inside_temp / is_climate_on | 温度与空调 |
| lat,lng 与 map_lat,map_lng | **WGS-84 原始坐标 与 GCJ-02 已纠偏坐标（配高德瓦片用后者）** |
| location_name | geofence 或 300m 内最近地址名 |
| active_charge | 充电中才有：charge_id/charger_power_kw/energy_added_kwh/fast_charger_present… |
| active_drive | 行驶中才有：drive_id/started_at |
| **tpms_alert** | **服务端算好的胎压异常判定（bool）** |
| **tpms_alert_wheels** | **异常轮次名数组，如 `["左后"]`** |
| **tpms_alert_detail** | **异常轮次读数，如 `"左后 2.80 bar"`** |
| **tpms_min_bar / tpms_max_bar** | **胎压正常范围（供 App 展示，避免两边各写一份阈值）** |
| **data_age_hours** | **数据距今多少小时（整数）** |
| **data_stale** | **数据是否已过期（超过 24 小时）** |

> **v0.7.0 新增的四个判定字段是「唯一事实源」。**
> 告警阈值与判定逻辑只在服务端实现一次（`api/alerts.go`），
> App 只读结果并按轮胎着色，推送脚本 `scripts/watch-car.ps1` 也只读结果。
> 这样不会出现「App 说胎压正常但微信推了告警」这种两边算法漂移的情况。
> 详见 data-dictionary.md §3。

### /drives 列表项
`id` `start_date` `end_date` `duration_min` `distance_km` `start_place`/`end_place`（geofence 优先） `start_soc`/`end_soc` `energy_kwh` `wh_per_km` `speed_max` `outside_temp_avg` `odometer_start/end`

### /drives/{id}/track 点
`date` `lat` `lng` `map_lat` `map_lng` `speed` `power` `battery_level` `elevation`

### /charges 列表项
`id` `start_date` `end_date` `duration_min` `energy_added_kwh`(充入) `energy_used_kwh`(表计) `cost`(可空=未记录) `start_soc`/`end_soc` `place` `map_lat`/`map_lng` `is_dc`

### /charges/{id}/curve 点
`date` `battery_level` `charger_power_kw` `charger_voltage` `charger_current` `charger_phases`

### /stats
```jsonc
{
  "granularity": "day",          // day | month
  "buckets": [                    // 周 7 桶 / 月 30 桶 / 年 12 桶（本地时区 Asia/Shanghai）
    { "start": "2026-10-12", "km": 1002.5, "minutes": 642, "kwh_used": 176.4,
      "wh_per_km": 176, "drive_count": 3, "charge_kwh": 157.7, "dc_kwh": 157.7,
      "ac_kwh": 0, "cost": 0, "charge_count": 2 }
  ],
  "summary": { ... 同结构合计 ... }
}
```

### /battery
`current_capacity_kwh`（最近一次估算）`max_capacity_kwh`（历史最佳）`health_percent`（当前/最佳）
`series[]`：每次「充到 ≥90%」会话的 `{date, capacity_kwh, end_soc}`。

## curl 示例（真实数据验证于 2026-10-12）

```bash
TOKEN=$(grep '^API_TOKEN=' .env | cut -d= -f2)

# 车况：电量/续航/位置（GCJ-02 纠偏后坐标与原始坐标存在偏移）
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8090/api/v1/cars/1/status
# → {"car_id":1,"state":"asleep","battery_level":71,"rated_range_km":319.81,
#    "odometer":100399.3,"lat":22.58,"lng":113.86,
#    "map_lat":22.572459,"map_lng":113.867427,"location_name":"新湖路, 西乡街道, 宝安区, 深圳市…"}

# 周统计自洽性：315.8 kWh ÷ 1794.7 km × 1000 = 176 Wh/km ✓
```

## 口径附注（与 Grafana 仪表盘对齐，详见 architecture.md §3.2）
能耗=续航差×efficiency；快充判定=phases 空且非 ACSingleWireCAN；电池容量取 ≥90% 充电会话估算。

---

# 2026-10-15 增补（v0.5.x）

## 新增端点：GET /api/v1/app/staticmap

高德静态地图的中转。App 用 Coil 直接加载这个 URL 拿一张 PNG（底图+路线），**不再有客户端地图 SDK**。

| 参数 | 说明 |
|---|---|
| `points` | 必填。`lng,lat;lng,lat;…`（注意是 lng 在前）。超过 200 点服务端自动降采样 |
| `width` / `height` | 可选，默认 720 / 400，上限 1024 |
| `markers` | 可选，传 `0` 时不画起终点标记 |

返回：`image/png` + `Cache-Control: public, max-age=1800`；服务端另有 30 分钟进程内 LRU 缓存（最多 64 项）。

**坐标系**：`points` 必须传 `map_lat`/`map_lng`（GCJ-02），不是 `lat`/`lng`（WGS-84）。

**高德错误码注意**：高德在参数错误时返回 **HTTP 200 + body 是 JSON**（`{"status":"0","info":"UNKNOWN_ERROR","infocode":"20003"}`），不是非 2xx。服务端已加判断（`buf[0] == '{'` 时解析并返 502），**不要**直接把响应体当 PNG 缓存。

**本账号的限制**：`markers`（标注）与 `paths` 的 `fillcolor`（填充）子权限未开通，加上就 20003。已折中为只画路线线段，起终点标记不可用。开通方式：lbs.amap.com 控制台 → 应用 →「添加服务」→ 勾选「静态地图 - 标注」。

## 新增字段

### `/cars/{id}/status`
| 字段 | 说明 |
|---|---|
| `tpms_fl` `tpms_fr` `tpms_rl` `tpms_rr` | 四轮胎压（bar）。App 侧异常判定：超出 2.4–2.9 或单胎比中位数低 0.2 bar 以上 → 红色告警 |
| `usable_battery_level` | 可用电量 %（与标称电量可能不同，Model 3 LR 常见两者相等） |
| `elevation` | 海拔 m。平原地区常为 null，App 显示「—」 |

### `/cars/{id}/charges`
| 字段 | 说明 |
|---|---|
| `outside_temp_avg` | 充电全程平均车外温度 ℃（来自 `charging_processes.outside_temp_avg`，比首尾采样更准） |
| `loss_percent` | 充电损耗率 = (表计用电 − 充入) / 表计 × 100。典型值：交流慢充 ~10%、直流快充 ~5%。数据异常（<0 或 ≥60）时不返回 |
| `cable_type` | `GB_AC` 国标交流 / `GB_DC` 国标直流 / `Tesla` 系列；`<invalid>` 占位值转为不返回 |
| `charger_brand` | 快充品牌（`fast_charger_brand`），同上处理 |

### `/cars/{id}/battery`（重要口径变更）
| 字段 | 说明 |
|---|---|
| `current_capacity_kwh` | 最近一次满充实测容量（从 ≥90% 的充电会话反算） |
| `max_capacity_kwh` | 记录中的最大实测值。**仅供趋势参考，不作为健康度分母** |
| `nominal_capacity_kwh` | **铭牌（出厂标称）容量**，来自环境变量 `NOMINAL_BATTERY_KWH`。健康度的分母 |
| `health_percent` | `current ÷ 基准 × 100`。基准优先用铭牌容量，未配置时回退 `max_capacity_kwh`（该口径不可信，见下） |

**为什么不能用历史最大值当分母**：历史最大值本身是衰减后的测量值，会随时间往下漂；车越老分母越小，「当前/历史最大」永远逼近 100%，健康度虚高且不可跨车比较。本车曾因此显示 98%，实际 65.41 / 76.8 = **85.2%**。

**为什么不用「满电续航 × 车辆效率」反推**：那是车机按当前衰减后的可用容量上报的。本车 454.59 km × 0.146 = 66.37 kWh，与铭牌 76.8 差 15%。

**配置方式**：`.env` 里 `NOMINAL_BATTERY_KWH=76.8`，换车改这一行即可，不用改代码。

## 已移除的展示

充电费用相关字段（`cost`、电费单价）已从 App 界面移除：`charging_processes.cost` 全库 65 条均为 NULL（未配置分时电价），展示价值为零。字段仍在 API 响应中保留，将来配置 `tou_rates` 后可再启用。
