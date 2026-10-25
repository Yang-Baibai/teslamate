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
| GET | /api/v1/cars/{id}/stats?period=today\|week\|month\|calendar_month\|year | 桶聚合统计（今天 / 近 7 个自然日 / 近 30 个自然日 / 自然月 / 近 12 个自然月，当月为进行中数据）。⚠️ v0.32.0：**period 走白名单**，未知值返回 **400**（不默默夹到 30 天） |
| GET | /api/v1/cars/{id}/cost_summary?period=today\|week\|month\|calendar_month\|year | **充电成本构成**（度数 / 场次 / 时长 / 行驶里程双分母）。同一套 period 白名单，未知值 **400**。⚠️ v0.31.0：同时返回 `km_drives` 与 `km_odometer`（长窗口下遥测断档会让两者差 16.71%，每公里成本要按区间显示） |
| GET | /api/v1/cars/{id}/route_profile?drive_ids=&step_km= | **整条线路**的逐公里 SOC 剖面（多趟行程拼成一条）。v0.31.0 起带每段 `ascent_m` / `descent_m` |
| GET | /api/v1/cars/{id}/driving_profile?limit=&min_km= | 驾驶风格画像（急刹/急加/夜间/超速/能耗 + 平顺分） |
| GET | /api/v1/cars/{id}/stay_points?min_hours= | 常驻点（按距离合并，250 m） |
| GET | /api/v1/cars/{id}/energy_profile | 能耗-车速曲线（车轮功率积分；含 `covered_km` 使平均能耗的分子分母同源） |
| GET | /api/v1/drives/{id}/soc_profile?step_km= | 单次行程的逐公里 SOC 剖面 |
| GET | /api/v1/cars/{id}/battery | 电池容量趋势与健康度 |
| GET | /api/v1/app/latest | App 自更新用的版本元数据（版本号 / APK 地址 / 更新说明） |

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
| **tpms_alert_detail** | **异常轮次读数，如 `"左后 2.80 bar"`；多个异常轮之间用两个空格连接**（服务端 `strings.Join(values, "  ")`） |
| **tpms_min_bar / tpms_max_bar** | **胎压正常范围（供 App 展示，避免两边各写一份阈值）** |
| **data_age_hours** | **数据距今多少小时（整数）** |
| **data_stale** | **数据是否已过期（超过 24 小时）** |
| **locked** | **锁车状态：`true`=已上锁 / `false`=未上锁 / `null`=未知**（数据源：Tesla Fleet API，见下） |
| **locked_at** | **本次锁车状态的采集时刻（UTC，ISO-8601）**。`null` = 从来没拿到过数据 |
| **tpms_live_fl / tpms_live_fr / tpms_live_rl / tpms_live_rr** | **实时胎压（bar）：`null`=没读到 / 数值=该轮读数**（数据源：Tesla Fleet API，见下） |
| **tpms_live_at** | **这批实时胎压的采集时刻（UTC，ISO-8601）**。`null` = 从来没拿到过数据 |
| **tpms_live_alert** | **实时胎压告警判定：`true`=有异常 / `false`=已知正常 / `null`=未知** |
| **tpms_live_alert_wheels** | **异常轮次名数组，如 `["左后"]`**（只针对 live 值） |
| **tpms_live_alert_detail** | **异常轮次读数，如 `"左后 2.80 bar"`**（只针对 live 值） |
| **tpms_live_warn_wheels** | **接近阈值的轮次名数组**（Tesla 的软告警，如 `["右前"]`） |

> **v0.8.5 新增的 `tpms_live_*` 是「Fleet API 的实时胎压」，不是数据库里的采样值。**
>
> | 字段组 | 来源 | 新鲜度 | 何时有值 |
> |---|---|---|---|
> | `tpms_fl/fr/rl/rr` | **TeslaMate 数据库**（稀疏采样） | 数小时才有一条，还可能是旧值 | 一直有（只要车开过） |
> | `tpms_live_fl/fr/rl/rr` | **Tesla Fleet API**（`vehicle_state.tpms_pressure_*`） | 车醒着时每 2 分钟一条 | 车休眠 / 没配凭据时为 `null` |
>
> **两套值互不覆盖，各自保留。App 端：有 live 就用 live，没有才回退 `tpms_fl`。**
> 告警判定同理：`tpms_alert*` 针对 TeslaMate 采样值，`tpms_live_alert*` 针对 live 值。
>
> **`null` 的含义是「不知道」，不是 0。** 车休眠时 Fleet API 返回 408，
> 服务端会**保留上一次已知结果**（`tpms_live_at` 越旧说明数据越不可信）；
> 从来没拿到过数据时才是 `null`。
>
> `tpms_live_alert` 的判定口径（服务端 `api/tesla.go`，唯一事实源）：
> **Tesla 服务端自己的硬告警位优先**（`tpms_hard_warning_*` → 异常），
> 软告警位（`tpms_soft_warning_*`）→ 接近阈值（只提示，不算异常），
> 两者都没有时用阈值兜底（`< 2.5` 或 `> 3.3 bar`、单胎比四轮中位数低 `0.2 bar` 以上）。

> **v0.8.4 新增的 `locked` / `locked_at` 来自 Tesla Fleet API，不是数据库。**
> TeslaMate 的库里没有「车门锁没锁」这个字段，只有特斯拉云端知道，
> 所以服务端每 2 分钟拉一次 `vehicle_data`（需 scope `vehicle_device_data`）并缓存。
> ⚠️ **不带 `endpoints` 参数**：实测中国区带上它只会返回车辆元数据（拿不到状态）。
> 锁车与实时胎压在同一个响应里（`response.vehicle_state.*`），一次请求拿全。
>
> **三态语义必须严格区分**：`true`=已上锁、`false`=未上锁、**`null`=未知**。
> `null` 出现在两种情况：没配 Fleet API 凭据、或者拉取失败。
> **App 不能把 `null` 当成 `false`** —— 那会把「不知道」显示成「没锁」，属于危险的反向误报。
>
> 车处于休眠时 Fleet API 返回 408，服务端会**保留上次已知结果**，
> 用 `locked_at` 表达新鲜度（`locked_at` 越旧说明数据越不可信）。
> 凭据缺失时整个功能关闭，主服务照常工作。

> **v0.7.0 新增的四个判定字段是「唯一事实源」。**
> 告警阈值与判定逻辑只在服务端实现一次（`api/alerts.go`），
> App 只读结果并按轮胎着色，推送脚本 `scripts/watch-car.ps1` 也只读结果。
> 这样不会出现「App 说胎压正常但微信推了告警」这种两边算法漂移的情况。
> 详见 data-dictionary.md §3。

### /drives 列表项
`id` `start_date` `end_date` `duration_min` `distance_km` `start_place`/`end_place`（geofence 优先） `start_soc`/`end_soc` `energy_kwh` `wh_per_km` `speed_max` `outside_temp_avg` `odometer_start/end`

| 字段 | 类型 | 含义 |
|---|---|---|
| `has_supercharger` | bool | 这趟行程的终点附近有**直流快充**记录 |
| `charge_after_trip` | bool | 这趟行程的终点附近有**任何**充电记录（含交流慢充） |

> **v0.8.0 修正：充电与行程改用里程关联，不用时间窗。**
> 旧判据 `charges.date BETWEEN drives.start_date AND drives.end_date` 恒为 false——
> 充电发生在行程**结束之后**（到达服务区 → 结束行程 → 开始充电），
> 导致最近 200 条行程的 `has_supercharger` 全是 false。
> 现按 `|充电起始里程 − 行程结束里程| < 2 km` 关联
> （`charging_processes.position_id` → `positions.odometer` 对 `drives.end_km`）。
> 直流/交流的判定复用 `/charges` 的 `is_dc` 同一条判据，两个接口口径一致。

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

**周期口径**（`period` 决定桶的粒度与数量；窗口按 `TZ_NAME`（默认 `Asia/Shanghai`）的自然日 / 自然月切）：

| period | 桶粒度 | 桶数 | 覆盖范围 | 说明 |
|---|---|---|---|---|
| `week` | 日 | 7 | **含今天的近 7 个自然日** | `buckets[0]` 是 6 天前，`buckets[6]` 就是今天 |
| `month` | 日 | 30 | **近 30 个自然日**（含今天） | 默认值——`period` 缺省或非法都落到这一档 |
| `year` | 月 | 12 | **近 12 个自然月** | 当月是**进行中**数据，数值会随当月继续增长 |

### /battery
`current_capacity_kwh`（最近一次估算）`max_capacity_kwh`（历史最佳）`health_percent`（当前/最佳）
`series[]`：每次「充到 ≥90%」会话的 `{date, capacity_kwh, end_soc}`。

### ⚠️ v0.35.0：`/cars/{id}/battery` 的 `decay` 块（电池容量衰减判定）

`decay` 是**服务端唯一事实源**的衰减判定，客户端只显示、不自己算。

| 字段 | 说明 |
|---|---|
| `n` / `strict_n` / `excluded_n` | 参与回归的点数（**只用 `end_soc = 100` 的严格档**）/ 严格档点数 / 未计入的非满充点数 |
| `span_km` / `span_days` | 覆盖的里程与天数 |
| `naive_per_1000km` `naive_ci_lo/hi` | **朴素线性**的速率与 95% 置信区间 |
| `has_step` `step_km` `step_date` `step_kwh` `step_ci_lo/hi` `step_significant` | 一次性**阶跃**的位置、幅度、区间与显著性 |
| `naive_overstate` | 朴素速率把真实连续速率**高估了几倍** |
| `underlying_per_1000km` `underlying_ci_lo/hi` `underlying_significant` | **扣掉阶跃后**的连续衰减速率 |
| `pre_per_1000km` / `post_per_1000km`（+ `_significant`、`pre_n`/`post_n`） | 阶跃前后两段各自的速率 |

**口径**：
- 严格档 = `end_soc == 100`（90~98% 的点从 94% 外推到 100% 会**系统性偏高 0.56%**）；
- 自变量用**里程**（衰减按公里发生）；
- 显著性用 **t 检验**（95% 置信区间），**不是**「样本 < N 个就不给结论」这种经验闸门。

**实测**（本机 17 个严格档满充点）：
朴素线性 −0.1375 度/1000km（R²=0.641）；
线性 + 一次阶跃 R²=**0.829**（F=**15.41** 显著），阶跃 **−0.5624 度**
（95% 区间 [−0.870, −0.255]，t=−3.92，位置 2026-09-28 / 96,792 km）；
扣掉阶跃后的连续速率 **−0.0595 度/1000km**（[−0.1184, −0.0006]）——
即朴素速率**高估 2.31 倍**。
而**阶跃前后两段单独看都没有显著趋势**（t=−1.72 / −1.36），
这正是「缓慢连续衰减 + 一次不连续」的指纹。

⚠️ **「那天掉 0.56 度」到底是老化还是续航估算重新校准，数据分不出来** ——
容量口径是「续航估值 × 效率常数」，而续航估值本身是 BMS 的**估算**。

### ⚠️ v0.31.0：`/cars/{id}/cost_summary`
`dc_used_kwh` `ac_used_kwh`（网侧度数）`dc_sessions` `ac_sessions` `dc_minutes` `ac_minutes`
`home_used_kwh` `home_sessions` `home_minutes`（**围栏命中的真家充**，是 `ac_used_kwh` 的**子集**）
`km_drives`（App 记录到的行驶里程）`km_odometer`（里程表在窗口内的跨度）

| 字段 | 说明 |
|---|---|
| `home_*` | 交流 ∧ `charging_processes.geofence_id IS NOT NULL`。⚠️ 本机 78 场充电的 `geofence_id` **全为 NULL**（「家」围栏是 2026-10-25 才建的），所以这三个数现在恒为 0 —— 这是**事实**，不是缺陷 |
| `km_drives` / `km_odometer` | ⚠️ **两个都要给**：两者差的是**遥测断档**里车真开过但没记行程的公里（实测全库 12 次断档合计 1459.62 km = 16.71%，全部发生在 2026-10-01 之前）。长窗口的「每公里成本」因此是个**区间**（下界 ÷ 里程表、上界 ÷ 行驶里程） |

### ⚠️ v0.36.0：`/cars/{id}/charges` 的 `place_label`（完整的人类可读标签）

| 字段 | 说明 |
|---|---|
| `place_label` | **界面优先用这个**。优先级：① POI 名 —— 但**当它本身就是路名时不算**（OSM 会把路名也写进 `name`）；② 「路名 + 地点」两段 —— **只在第一段像高速时**（国道编号 / 高速 / 公路 / 省道 / 线）；③ 地址第一段 |

**为什么**：没有 POI 名时原来回退到地址**第一段**，
而第一段经常是**路名**（「G210」「G227;G318」「沪聂线」「梧硕高速」）——
复审原话：「"G210" 我在地图上根本搜不到」。

**实测效果**：`G210` → `G210  车河镇`；`G227;G318` → `G227;G318  更登亚批社区`；
`G227;G549` → `G227;G549  稻城县`；`沪聂线` → `沪聂线  麻格宗`；
`梧硕高速` → `梧硕高速  黎塘工业园区`。

⚠️ `poi_name` 本身在 v0.34.0 改成取**原地址**（canonical 只作兜底）——
原来 canonical 地址的 POI 名会把「中石化富民加油站」顶替成「梧州市第五中学」。

### ⚠️ v0.31.0：`/cars/{id}/charges` 的实测字段
`measured_minutes_to_80` `measured_from_soc` `measured_unavailable_reason`

| 字段 | 说明 |
|---|---|
| `measured_minutes_to_80` | **实测**从起始 SOC 充到 80% 的分钟数（`charges` 表逐点直读，**不是**折算）。`floor((t80 − t0)/60s)` **截断**取整。不可用时为 `null`（字段**始终**出现，不加 `omitempty`） |
| `measured_from_soc` | 口径起点 = **曲线第一个采样点**的电量（不是 `cp.start_battery_level` —— 曲线可能中途才接上监控）。必须与上面一起显示 |
| `measured_unavailable_reason` | `start_soc_ge_80` / `never_reached_80` / `too_few_points` / `soc_regressed` / `too_fast` / `too_long`。实测 78 场里有效仅 **30 场（38.5%）** |

> 为什么必须有它：原来的「充到 80% 约 N 分钟」是按**整场平均功率**线性折算的，
> 而直流桩在高 SOC 段会**降功率** → 整场平均被拖尾砍掉近一半 →
> 实测 30 组可比场次**中位高估 +33%、最大 +185%**。

### ⚠️ v0.31.0：`/cars/{id}/route_profile` 的爬升
`ascent_m` `descent_m`（整条线路）· 每段 `ascent_m` / `descent_m`

口径 = `drives.ascent` / `drives.descent`（原样，不去噪）。
实测 `drives.ascent` 与「相邻点正高差之和」**逐条精确相等**（439/439，最大差 0 米）；
但含 GPS 量化噪声 —— **长途大段偏差 0.8%~2.3%**（够准），**短途中位虚高 17.1%**。
⚠️ **净爬升（毛爬升 − 毛下降）可以是负的**（实测那条 6227 公里线的净爬升只有 +3278 米），
所以做能耗修正必须「上坡按毛爬升算、下坡按毛下降抵扣」，不能用净值。

### ⚠️ v0.34.0：`/cars/{id}/climb_model`（爬升能耗系数 · **v2 口径**）

`GET /api/v1/cars/{id}/climb_model?min_km=20`

**⚠️ v0.34.0 起有两套口径，客户端只准用 `basis == "calib_net"` 的那套。**

| 字段 | 说明 |
|---|---|
| `basis` | **口径标记**，固定 `"calib_net"`。不是这个值客户端**不给**修正 |
| `c_up_calib_net_wh_per_m` | 标定法口径的上坡系数（**正数** = 上坡每米多耗） |
| `c_regen_calib_net_wh_per_m` | 标定法口径的下坡系数（**负数** = 下降越多、净 Wh/km 越低） |
| `c_up_calib_net_se` / `c_regen_calib_net_se` | 两个系数的**标准误** |
| `c_up_calib_net_ci95` / `c_regen_calib_net_ci95` | 两个系数的 **95% 置信区间** |
| `n_calib` / `r2_calib` | 参与拟合的行程数 / 拟合优度 |
| `typical_grade[]` | **每个车速档的典型坡度**（delta 基准）：`min_kph` `max_kph` `grade_up_typ_m_per_km` `grade_down_typ_m_per_km` `n` |
| `c_up_wh_per_m` / `c_regen_wh_per_m` / `implied_eta_*` | **车轮功率毛耗电口径**的旧系数 —— 现在只作**物理交叉核对**，客户端不用 |
| `c_up_se` / `c_regen_se` / `c_up_ci95` / `c_regen_ci95` | 旧口径也补了标准误与 CI（原来只报分裂半） |

**为什么必须分两套口径**：
客户端的能耗底档是**标定法净值**口径（`(Δ标定续航) × efficiency × 1000 / km`，回充**已抵扣**），
而旧的系数是在**车轮功率毛耗电**口径上拟合的。
两者混用会**三重重复计量**：

1. 回充被扣**两次**（底档已抵扣净值，公式又减一次 `c_regen × 下降坡度`）；
2. 该档的**典型爬升**被算**两次**（底档本身建自那些趟次，公式再叠**绝对**坡度）；
3. 同段既上又下时给 `(c_up − c_regen) × grade`，而正确增量应基于**相对该档典型**的增量。

独立复算实测（客户端真实可用样本 88 趟）：

| 公式 | 偏差 | RMSE | 结论翻转 |
|---|---|---|---|
| 不加修正 | −3.62% | 55.35 | 1 |
| **旧公式（绝对坡度 + 车轮口径）** | **+12.08%** | 37.13 | **4** |
| **新公式（delta + 标定法口径）** | **−0.84%** | **24.12** | **0** |

**客户端公式**：
```
修正 = c_up_calib_net × (本段上坡坡度 − 该档典型上坡坡度)
     + c_regen_calib_net × (本段下坡坡度 − 该档典型下坡坡度)
```
delta 形式在「本段坡度 = 该档典型坡度」时修正恰为 0 —— **结构上不可能**系统性抬高。

⚠️ **客户端的口径守卫**：服务端若未下发 `basis == "calib_net"`，
客户端**一律不给修正**（宁可少算，也不混口径）。
⚠️ **段长 < 20 公里不给修正**：那里 `drives.ascent` 的噪声与真实爬升完全不成比例。

⚠️ **系数没有物理解释**：能量自洽性核算显示 `drives.ascent` **不是物理高差**
（被审计段算出 **142% > 100%**，物理不可能），
所以 `c_up ÷ (m·g)` 得到的「隐含传动效率 78%」**不成立** ——
系数只能当**经验标定值**看，必须连同口径 + CI + 样本量一起呈现。

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
| `tpms_fl` `tpms_fr` `tpms_rl` `tpms_rr` | 四轮胎压（bar）。**异常判定在服务端**（`api/alerts.go`，唯一事实源）：越界 <2.5 或 >3.3 bar，或单胎比四轮中位数低 0.2 bar 以上。App 只读 `tpms_alert` / `tpms_alert_wheels` / `tpms_alert_detail`，不再本地算阈值 |
| `usable_battery_level` | 可用电量 %（与标称电量可能不同，Model 3 LR 常见两者相等） |
| `elevation` | 海拔 m。**v0.7.0 起服务端会回填**：在最近 500 条 positions 里取最后一个非空值（`rx` CTE + `COALESCE`），只有这 500 条全为空时才返回空，App 显示「—」 |

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

⚠️ **v0.32.0 订正**：这一段是 v0.5.0~v0.24.0 期间的状态，已经**过时**。

- **费用已在 v0.25.0 加回来了** —— 不用服务端的 `cost`（全库 78 条均为 NULL），
  改用**车主自己在 App 设置页填的电价**在客户端估算（`/cost_summary?period=…`）。
  现在的口径与限制见下方「充电成本构成」一节。
- 「配置 `tou_rates` 后可再启用」这个说法**是错的**（第 20 轮费用复审查出）：
  库里的 TOU 引擎（`compute_tou_cost` / `lookup_tou_rate` / `trigger_compute_tou`）
  把结果写进**旁路表 `charging_processes_tou_cost.cost_tou`**，
  **从不写** `charging_processes.cost` —— 所以配了 `tou_rates` 之后，
  `charging_processes.cost` 仍然是 NULL，本字段仍然是空的。
  要用上那套引擎，必须由 API **主动读 `charging_processes_tou_cost`**（尚未接线）。
