# 系统架构与设计决策

## 1. 总体拓扑

```
                        ┌────────────────────── 本机 Docker Desktop ──────────────────────┐
                        │                                                                  │
  特斯拉云 API ──────────┼─▶ teslamate(4000) ──▶ mosquitto(1883)                           │
  (车辆数据拉取/流式)      │       │                                                          │
                        │       ▼                                                          │
                        │   database ── postgres:18-alpine（PGDATA=./database，唯一数据源）│
                        │       ▲                  ▲                                       │
                        │       │ 只读 SQL             只读 SQL                            │
                        │   grafana(3000)          api(8090)  ◀── 自研，本文件主角         │
                        │   49个中文仪表盘           Bearer Token REST                     │
                        └──────────┬───────────────────┬──────────────────────────────────┘
                                   │                   │
                          局域网浏览器 │                   │ HTTP(明文)
                                   │        ┌──────────┴──────────┐
                                   │        │ Tailscale WireGuard │ ← 手机在外时走这条加密隧道
                                   │        └──────────┬──────────┘
                                   │                   │
                              家里 WiFi ◀──────────── Android App（爱车车况）
```

## 2. 设计决策与理由

### 2.1 为什么自研 api 容器，而不是让 App 直连数据库？
- **安全**：数据库不暴露端口，手机只需一个 48 位令牌；API 层做字段裁剪（不吐 VIN 之外的账号信息）、行数限制（轨迹降采样 ≤1500 点）。
- **口径统一**：能耗/快充判定/电池容量等计算口径与 Grafana 仪表盘逐条对齐（见 api-spec.md 附注），手机与网页看到的数字一致。
- **解耦**：App 不感知数据库 schema；TeslaMate 升级改表只影响 API 层。

### 2.2 为什么 API 是只读的？
控制车辆（开锁/空调/鸣笛）需要特斯拉账号级令牌，放手机端网络里风险不可控。本项目定位是「查看」，控制类需求明确排除在范围外。

### 2.3 坐标系设计（重要，改动前必读）
- TeslaMate 库存 WGS-84 坐标；高德瓦片是 GCJ-02（国测局加密）。
- 中文仪表盘已安装 SQL 函数 `wgs84_to_gcj02_lat/lng`（国内偏移、国外原样）。
- API 同时返回两套：`lat/lng`（WGS-84 原始）与 `map_lat/map_lng`（已纠偏）。
- **App 地图一律用 map_lat/map_lng 配高德瓦片**；若以后想换谷歌/OSM 瓦片，应改用 lat/lng，勿混用（混用会产生 ~500m 偏移）。

### 2.4 为什么 Grafana 用整镜像替换而不是挂载 JSON？
中文仪表盘镜像内置：仪表盘文件、配套 provisioning、volkovlabs-form-panel 插件（放在 volume 之外的 `/opt/grafana-plugins`，规避 `./grafana/data` 挂载覆盖的官方已知坑）。镜像方案升级 = `docker compose pull grafana`，一条命令。

### 2.5 SQL 兼容契约（中文仪表盘项目的机制，升级时必须核对）
- 数据库表 `teslamate_cn_extension_meta.sql_revision` 记录已装 SQL 版本（当前 14）。
- 镜像内 `/opt/teslamate-cn/versions.env` 记录镜像要求的版本。
- **每次 pull 新镜像后必须比对两者**；不一致就重跑 `teslamate-chinese-dashboards/sql/install-*.sql`（幂等）。
- 同理，特斯拉充电费用等表结构由 TeslaMate 自身迁移管理；升级 teslamate 镜像后留意容器日志。

### 2.6 为什么 App 构建目录在 C:\tm-app 而不是工作区？
- 工作区路径含中文用户名 + OneDrive 实时同步；Gradle 构建会产生数万小文件，两者组合是已知雷区（文件锁/编码）。
- 方案：构建目录 `C:\tm-app\teslamate-app`（纯 ASCII），`GRADLE_USER_HOME=C:\gradle\gradle-home`；构建成功后源码副本同步回工作区 `app-android/`（不含 build 产物）。

### 2.7 网络/镜像源事实
| 资源 | 状态 | 方案 |
|---|---|---|
| github.com / raw / API | 直连不通 | 前缀 `https://ghfast.top/`（备用 gh-proxy.com） |
| ghcr.io、Docker Hub | 可用 | Docker Desktop 配 USTC 源，偶发 EOF 重试即可 |
| Gradle 分发包 | 慢 | 腾讯镜像 `mirrors.cloud.tencent.com/gradle/` |
| Maven 依赖 | 慢 | 阿里云 `maven.aliyun.com`（settings.gradle.kts 已配） |
| Go 模块 | 慢 | `goproxy.cn`（api/Dockerfile 已配） |

## 3. 数据流与数据口径

### 3.1 采集链路
特斯拉云 → teslamate（拉取+流式 API）→ PostgreSQL（positions/drives/charging_processes/charges/states 等表）+ MQTT（实时主题，本栈内 mosquitto）。

### 3.2 关键计算口径（API 与仪表盘共用）
| 指标 | 公式 | 出处 |
|---|---|---|
| 行车能耗 | `(start_rated_range_km - end_rated_range_km) × cars.efficiency` | 官方 drives 仪表盘 |
| 百公里能耗 | 上式 ×1000 ÷ distance | 同上 |
| 直流快充判定 | `charger_phases` 为空/0 且 `fast_charger_type != 'ACSingleWireCAN'` | 官方超充统计 |
| 电池可用容量 | `rated_battery_range_km × efficiency × 100 ÷ usable_battery_level`（取充电至 ≥90% 的会话） | 官方电池健康仪表盘 |
| 电池健康度 | 最近容量 ÷ 历史最佳容量 | 中文仪表盘口径 |
| 位置命名 | 命中 geofence（半径内）否则 300m 最近地址 | Grafana 常用做法 |
| 本车 efficiency | 0.146 kWh/km（cars 表，TeslaMate 依车型维护） | — |

### 3.3 时区约定
- PostgreSQL 全部 `timestamp without time zone`，**存 UTC**。
- API 出参 ISO-8601 带 Z（UTC）；App 端 `Instant` + 系统时区显示。
- 按日/按月聚合在 SQL 内用 `AT TIME ZONE 'UTC' → AT TIME ZONE 'Asia/Shanghai'`（API 环境变量 `TZ_NAME`）。

## 4. 安全模型与遗留风险
- API：Bearer Token（constant-time 比对）、错误信息不带堆栈、数据库只读账号权限（复用 teslamate 账号，未来可收紧为只读角色）。
- 明文 HTTP 的边界：局域网（家庭 WiFi，可接受）+ Tailscale 隧道（WireGuard 加密，可接受）。**不要**把 8090/3000/4000 端口直接映射到公网。
- 遗留（见 operations.md 待办）：`.env` 中 ChangeMe 弱密码×2、Grafana 匿名 Admin、`database/` 在 OneDrive 目录内。
