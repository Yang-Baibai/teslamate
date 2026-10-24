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
| 胎压 | bar。**本车正常范围 2.5–3.3**（见 §3 胎压判定） |
| 温度 | ℃ |
| 车辆效率 `cars.efficiency` | **kWh/km**（本车 0.146）。⚠️ TeslaMate 老版本此列是 Wh/km，升级时注意换算 |

## 2. 核心计算口径（API 与 Grafana 仪表盘对齐）

| 指标 | 公式 | 注意 |
|---|---|---|
| 行车能耗 | `(start_rated_range_km − end_rated_range_km) × cars.efficiency` | 用 rated（标定）续航，不用 ideal |
| 百公里能耗 | 能耗 × 1000 ÷ distance | distance ≥ 0.1 才计入 |
| **行程轨迹累计里程**（App 端口径，`app-android` 的 `ui/PowerProfile.kt`） | 哈弗森公式逐点累加 `haversine(前一点, 当前点)`，坐标取 `lat`/`lng`（WGS-84） | **不能**用 `map_lat`/`map_lng`（那是 GCJ-02 偏移后的坐标，仅用于国内地图显示，混用偏 ~500 m）。服务端 track 是「每 N 个取 1 个」的朴素降采样，直接连线会**混叠**（把高频抖动画成大起大落），因此**按 1 km 分箱取平均**后再绘制 |
| 直流快充判定 | 存在采样满足 `charger_phases` 为空/0 **且** `fast_charger_type ≠ 'ACSingleWireCAN'` | ACSingleWireCAN 是车载交流充电机的伪装值。⚠️ **不要用 `fast_charger_brand`**：本车 86.7% 是 `'<invalid>'`，判不出快慢充 |
| **行程 ↔ 充电关联** | `abs(充电起始里程 − 行程结束里程) < 2 km` | 充电起始里程 = `charging_processes.position_id → positions.odometer`；行程结束里程 = `drives.end_km`（`drives` 没有 `odometer_end` 列，`start_km`/`end_km` 本身就是里程表读数）。⚠️ **不能用时间窗**：充电发生在行程**结束之后**（到达服务区 → 结束行程 → 开始充电），旧口径 `charges.date BETWEEN drives.start_date AND drives.end_date` 恒为 false |
| 充电损耗率 | `(energy_used − energy_added) / energy_used × 100` | 仅当 added ≤ used 且结果 ∈ [0,60) 才输出；慢充 ~10%，快充 ~5% |
| 电池当前容量 | `rated_battery_range_km × efficiency × 100 ÷ usable_battery_level` | 仅取充至 ≥90% 的会话，取该会话最后一条采样 |
| **电池健康度** | `当前容量 ÷ NOMINAL_BATTERY_KWH × 100` | **分母是铭牌容量（.env 配置），不是历史最大值**——历史最大值本身随衰减漂移，用它健康度虚高（本项目曾 98% vs 实际 85.2%） |
| **真实可达续航**（App 端口径，`app-android` 的 `ConsumptionModel.kt`） | `标定续航 × 146 ÷ 实测 Wh/km × 温度修正 × 爬升修正 × 0.9` | `146` = 本车 `cars.efficiency`（0.146 kWh/km）。实测 Wh/km 由 App 自己按**车速分桶取中位数**算（数据源 `/drives?limit=200`）。温度修正只做热的：`× (1 + 0.006 × max(0, 气温 − 22))`，上限 +10%；爬升 ≥200m：`× 1.05`。**这是展示口径，不是数据库字段**：服务端 `est_battery_range_km` 全库仅 0.085% 有值，`ideal_battery_range_km` 与 `rated_battery_range_km` 逐行相同，实际显示的一直是 EPA 标定值（夏季高速乐观 25%~40%）。样本不足时回退显示标定续航 |
| **电池可用容量（反推值）**（App 端口径，`app-android` 的 `ui/ChargeStats.kt`） | `充入 kWh ÷ ΔSOC% × 100`，每次充电各算一个再取**中位数** | TeslaMate 不直接提供可用容量字段，只能从充电会话反推。合理区间 **30~120 kWh**（本车实测约 65.9 kWh）—— 写成「kWh per 1% SOC」的 `0.3~1.2` 会把全部样本滤掉。用途：折算「充到 80% 要多久」（v0.8.2 充电站历史）；并作为**「真实可达续航」的交叉校验**（反推 65.89 kWh vs `标定续航 448.30 km × 0.146 = 65.45 kWh`，误差 0.7%）。⚠️「真实可达续航」的**输入**是标定续航与实测 Wh/km，本行不是它的入参 |
| 位置命名 | geofence 命中（半径内）→ 否则 300m 内最近 address | Haversine，acos 参数已做 [-1,1] 钳制 |

## 3. 特殊值与占位符（"看起来是数据，其实不是"）

| 现象 | 真相 | 处理 |
|---|---|---|
| 电压 = 2V、电流 = 0A，但功率正常（80kW+） | 特斯拉 API 对非超充直流桩只在握手时回一次 V/A，稳态只更新功率 | API `sanitizeCharge` 把 V<10、A<1 归 null → App 显示"—" |
| `conn_charge_cable = '<invalid>'` | 无桩数据时的占位 | 转 null |
| `charger_brand = '<invalid>'` | 同上 | 转 null |
| 高德静态图 HTTP 200 但 body 是 JSON | 参数错/无权限（如 20003） | 服务端嗅探 `buf[0]=='{'` 转 502 |
| 本车 P74D 满电标定续航 454.59 km × 0.146 = 66.37 kWh ≠ 铭牌 76.8 kWh | 前者是车机按**当前衰减后**可用容量上报的 | 健康度分母必须用铭牌，不能用这个 |

## 3.5 告警判定口径（v0.7.0 起，唯一事实源在服务端）

> **实现位置：`api/alerts.go`**。阈值与判定逻辑**只在服务端写一次**，
> App（`app-android`）与推送脚本（`scripts/watch-car.ps1`）都只读服务端返回的结果。
> 这样不会出现「App 说胎压正常但微信推了告警」这种两边算法漂移。

### 胎压

| 判定 | 条件 |
|---|---|
| 越界 | 读数 < **2.5 bar** 或 > **3.3 bar** |
| 单胎离群 | 该轮比四轮**中位数**低 **0.2 bar** 以上 |

API 返回：`tpms_alert`(bool) / `tpms_alert_wheels`(字符串数组) / `tpms_alert_detail`(读数描述，多个异常轮之间用**两个空格**连接)
/ `tpms_min_bar` / `tpms_max_bar`。

**App 侧怎么用（v0.8.0 起）**：告警判定**优先读服务端**的 `tpms_alert` / `tpms_alert_wheels` /
`tpms_alert_detail`；`tpms_min_bar` / `tpms_max_bar` 用于显示「正常范围 X–Y bar（冷胎）」这句说明文字，
**只在服务端没给出判定结果时**才作为本地兜底阈值。

**为什么不是通用的 2.4–2.9**：那个范围是网上抄来的，与本车实际工况不符。
查 51434 个真实采样点，本车范围 **2.50–3.30、平均 2.96 bar**，
用 2.4–2.9 会有 **62.4%** 的记录误报。一个 62% 时间误报的告警等于没有告警——
用户会当噪音忽略，连带把真正的漏气信号也一起忽略。换车需按新车实测分布重设常量。

**为什么保留「单胎离群」**：绝对范围管的是"整体异常"，离群检测管的是
「一胎明显低于其余三胎」——**这才是漏气的主信号**，物理含义明确。
实测该判定触发率 1.62%，而绝对范围误报被砍到 0.59%。

### 数据新鲜度

| 判定 | 条件 |
|---|---|
| 数据过期 | `now - updated_at ≥ 24 小时` |

`updated_at` 取 `positions` 表最后一条记录的时间。
车停着不动是正常的，所以 24 小时以下不告警；App 在 6 小时以上会显示一条中性提示。

**两种"数据不新"要分清**：
- **车睡着了** —— 请求成功，只是车很久没上报 → App 显示蓝色提示，**推脚本不推**（正常）
- **连不上服务器** —— 请求失败 → App 显示红色错误条，**推脚本也不推**（网络问题不是车况问题）

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
| `geofences` | 0 行 | 「起点/终点围栏名」不命中（「超充经过」已改里程关联，不依赖 geofence） |
| `tou_rates` | 0 行 | 分时电价统计不可用；充电费用全 NULL（App 已隐藏该字段） |
| `positions.elevation` | 原始列常为 null（TeslaMate 只在上报里带海拔时才写这一列） | **v0.7.0 起服务端会回填**：在最近 500 条 positions 里取最后一个非空值（`rx` CTE + `COALESCE`），只有这 500 条全为空时才返回空，App 显示「—」属正常 |
| `charging_processes.cost` | 65 条全 NULL | 配置 tou_rates 后可恢复显示 |

## 6. 变更流程

改任何口径：
1. 改 `api/queries.go`（SQL）或 `api/handlers.go`（清洗）
2. 跑 `api/README.md` 里的冒烟测试
3. 更新本文 + `docs/api-spec.md`
4. CHANGELOG 记一条（含公式前后对照）
5. 真机看一眼对应页面
