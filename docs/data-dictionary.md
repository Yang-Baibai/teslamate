# 数据字典与口径基准

> 单一事实源。任何关于"这个字段是什么意思 / 为什么这么算"的争议，以本文为准。
> 修改口径必须同步更新本文 + `docs/api-spec.md` + CHANGELOG。

## 1. 单位与坐标系约定

| 项 | 约定 |
|---|---|
| 时间 | 数据库全部 `timestamp without time zone`，**存 UTC**。API 出参 ISO-8601 带 `Z`。客户端负责转本地时区显示 |
| 统计时区 | 按日/月聚合用 `Asia/Shanghai` 本地日界（API 环境变量 `TZ_NAME`） |
| 坐标 | 库存 WGS-84。API 同时输出：`lat/lng`（WGS-84 原始）+ `map_lat/map_lng`（GCJ-02 纠偏）。地图一律用 map_*，两套混用偏 ~500m |
| 里程 | km |
| 能量 | kWh；能耗 Wh/km |
| 胎压 | bar（Model 3/Y 冷胎正常 2.4–2.9） |
| 温度 | ℃ |
| 车辆效率 `cars.efficiency` | **kWh/km**（本车 0.146）。⚠️ TeslaMate 老版本此列是 Wh/km，升级时注意换算 |

## 2. 核心计算口径（API 与 Grafana 仪表盘对齐）

| 指标 | 公式 | 注意 |
|---|---|---|
| 行车能耗 | `(start_rated_range_km − end_rated_range_km) × cars.efficiency` | 用 rated（标定）续航，不用 ideal |
| 百公里能耗 | 能耗 × 1000 ÷ distance | distance ≥ 0.1 才计入 |
| 直流快充判定 | 存在采样满足 `charger_phases` 为空/0 **且** `fast_charger_type ≠ 'ACSingleWireCAN'` | ACSingleWireCAN 是车载交流充电机的伪装值 |
| 充电损耗率 | `(energy_used − energy_added) / energy_used × 100` | 仅当 added ≤ used 且结果 ∈ [0,60) 才输出；慢充 ~10%，快充 ~5% |
| 电池当前容量 | `rated_battery_range_km × efficiency × 100 ÷ usable_battery_level` | 仅取充至 ≥90% 的会话，取该会话最后一条采样 |
| **电池健康度** | `当前容量 ÷ NOMINAL_BATTERY_KWH × 100` | **分母是铭牌容量（.env 配置），不是历史最大值**——历史最大值本身随衰减漂移，用它健康度虚高（本项目曾 98% vs 实际 85.2%） |
| 位置命名 | geofence 命中（半径内）→ 否则 300m 内最近 address | Haversine，acos 参数已做 [-1,1] 钳制 |

## 3. 特殊值与占位符（"看起来是数据，其实不是"）

| 现象 | 真相 | 处理 |
|---|---|---|
| 电压 = 2V、电流 = 0A，但功率正常（80kW+） | 特斯拉 API 对非超充直流桩只在握手时回一次 V/A，稳态只更新功率 | API `sanitizeCharge` 把 V<10、A<1 归 null → App 显示"—" |
| `conn_charge_cable = '<invalid>'` | 无桩数据时的占位 | 转 null |
| `charger_brand = '<invalid>'` | 同上 | 转 null |
| 高德静态图 HTTP 200 但 body 是 JSON | 参数错/无权限（如 20003） | 服务端嗅探 `buf[0]=='{'` 转 502 |
| 本车 P74D 满电标定续航 454.59 km × 0.146 = 66.37 kWh ≠ 铭牌 76.8 kWh | 前者是车机按**当前衰减后**可用容量上报的 | 健康度分母必须用铭牌，不能用这个 |

## 4. 本车基准值（.env 可改）

| 项 | 值 | 存放 |
|---|---|---|
| 车辆 | Model 3 LR AWD Performance（P74D，2021） | cars 表 |
| 车辆效率 | 0.146 kWh/km | cars.efficiency（TeslaMate 维护） |
| **铭牌电池容量** | **76.8 kWh** | `.env` → `NOMINAL_BATTERY_KWH` |
| 地图 Key | 高德 Web 服务 Key | `.env` → `AMAP_KEY` |
| 已知限制 | 高德账号未开通 markers 与 paths.fillcolor 子权限（20003） | 开通：lbs.amap.com 控制台加服务 |

## 5. 空表与数据盲区（2026-10-15 实测）

| 表/字段 | 状态 | 影响 |
|---|---|---|
| `geofences` | 0 行 | 「起点/终点围栏名」「超充经过」基本不命中 |
| `tou_rates` | 0 行 | 分时电价统计不可用；充电费用全 NULL（App 已隐藏该字段） |
| `positions.elevation` | 深圳平原常为 null | 海拔显示"—"属正常 |
| `charging_processes.cost` | 65 条全 NULL | 配置 tou_rates 后可恢复显示 |

## 6. 变更流程

改任何口径：
1. 改 `api/queries.go`（SQL）或 `api/handlers.go`（清洗）
2. 跑 `api/README.md` 里的冒烟测试
3. 更新本文 + `docs/api-spec.md`
4. CHANGELOG 记一条（含公式前后对照）
5. 真机看一眼对应页面
