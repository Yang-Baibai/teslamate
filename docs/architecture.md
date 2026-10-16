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

> **2026-10-16 实测复核**。此前记的「github.com 直连不通」**已过时**——现在直连正常。
> 但**镜像仓库仍然必须走代理**，这决定了 Clash 不能关。

| 资源 | 直连 | 经 Clash | 方案 |
|---|---|---|---|
| github.com | ✅ 200 | 200 | 已可直连，`ghfast.top` 前缀不再必需（备用保留） |
| raw.githubusercontent.com | ✅ 301 | 301 | 同上 |
| ghcr.io（镜像仓库） | ❌ 000 | ✅ 401 | **必须经代理**（401 是 registry 正常未授权响应 = 可达） |
| Docker Hub（镜像仓库） | ❌ 000 | ✅ 401 | **必须经代理**；另配 USTC 源 |
| goproxy.cn（Go 模块） | ✅ 200 | 200 | 直连可用（api/Dockerfile 已配） |
| maven.aliyun.com（Maven） | ✅ 404* | 404 | 直连可用（settings.gradle.kts 已配） |
| mirrors.ustc.edu.cn | ✅ 200 | 200 | 直连可用 |

\* 404 是请求的路径不存在，属正常响应，说明域名可达。

**结论：Clash（系统代理 `127.0.0.1:7897`）不能关。**
关掉后 App、API、Gradle 构建、Go 构建都不受影响，但 `docker compose pull` 会全部失败——
Grafana 用的 `ghcr.io/wjsall/teslamate-chinese-dashboards`，以及 postgres / mosquitto / teslamate
三个核心镜像都拉不下来，等于**再也无法升级整套栈**。

**但关 Clash 也解决不了 Tailscale 外出断连**——实测清空代理环境变量后
`tailscale netcheck` 的 DERP 结果一模一样（详见 operations.md §8.5）。
Tailscale 的 DERP 探测走直连 UDP（Clash 未开 TUN 抓不到），高延迟是宽带到 DERP 的真实网络状况。

## 3. 数据流与数据口径

### 3.1 采集链路
特斯拉云 → teslamate（拉取+流式 API）→ PostgreSQL（positions/drives/charging_processes/charges/states 等表）+ MQTT（实时主题，本栈内 mosquitto）。

### 3.2 关键计算口径（API 与仪表盘共用）
> 完整口径与"占位值"陷阱见 [`data-dictionary.md`](data-dictionary.md)，本表为速查。

| 指标 | 公式 | 出处 |
|---|---|---|
| 行车能耗 | `(start_rated_range_km - end_rated_range_km) × cars.efficiency` | 官方 drives 仪表盘 |
| 百公里能耗 | 上式 ×1000 ÷ distance | 同上 |
| 直流快充判定 | `charger_phases` 为空/0 且 `fast_charger_type != 'ACSingleWireCAN'` | 官方超充统计 |
| 电池可用容量 | `rated_battery_range_km × efficiency × 100 ÷ usable_battery_level`（取充电至 ≥90% 的会话） | 官方电池健康仪表盘 |
| **电池健康度** | 最近容量 ÷ **铭牌容量**（`NOMINAL_BATTERY_KWH` 环境变量） | **v0.5.0 修正**，见下 |
| 充电损耗率 | `(energy_used - energy_added) ÷ energy_used × 100` | v0.5.0 新增 |
| 位置命名 | 命中 geofence（半径内）否则 300m 最近地址 | Grafana 常用做法 |
| 本车 efficiency | 0.146 kWh/km（cars 表，TeslaMate 依车型维护） | — |

**⚠️ 健康度分母不能用"历史最大值"**（v0.4.0 及以前的错误，v0.5.0 修正）：
历史最大值本身是衰减后的测量值，随时间漂移 → 车越老分母越小 → 比值永远逼近 100%。
也不能用"满电续航 × efficiency"（车机按当前衰减上报，本车 454.59×0.146=66.37 ≠ 铭牌 76.8）。
正确分母是铭牌容量：65.41 / 76.8 = **85.2%**（此前误报 98%）。配置在 `.env` 的 `NOMINAL_BATTERY_KWH`。

### 3.3 时区约定
- PostgreSQL 全部 `timestamp without time zone`，**存 UTC**。
- API 出参 ISO-8601 带 Z（UTC）；App 端 `Instant` + 系统时区显示。
- 按日/按月聚合在 SQL 内用 `AT TIME ZONE 'UTC' → AT TIME ZONE 'Asia/Shanghai'`（API 环境变量 `TZ_NAME`）。

### 3.4 客户端渲染方案演进（2026-10-14，重要）

| 版本 | 方案 | 结局 |
|---|---|---|
| v0.1.x–v0.2.x | App 内嵌 **osmdroid** + 高德 GCJ-02 瓦片源 | ❌ 失败。logcat 证实 `Using tile source: Mapnik`——osmdroid 6.x 在 Compose `AndroidView` 重建时回落默认 Mapnik，而 OSM 在国内被 GFW 屏蔽（全部 404）→ 灰色网格。且瓦片源在 `factory` 设置、`update` 未重设，Compose 重组即失效 |
| v0.3.0 起 | **高德静态图 API**（`/v3/staticmap`）+ 服务端中转 + Coil 加载 | ✅ 当前方案。服务端合成 PNG（含底图+路线），App 只当图片显示。彻底绕开客户端地图 SDK 的兼容性与可达性风险 |

取舍详见 [`adr.md`](adr.md) #6。副作用：地图为静态图，**无手势缩放/拖动**。

### 3.5 验证策略（2026-10-15 建立）

- **API 改动** → `bash scripts/smoke.sh`：12 项断言（健康/鉴权/字段存在性/统计三档/电池口径/静态图 PNG/APK Content-Type/更新元数据）。任一失败禁止发版。
- **UI 改动** → 真机 `adb shell screencap -p` 看实际渲染。**编译通过 ≠ 显示正确**（地图那次就是编译通过、代码无误，真机才发现瓦片源被覆盖）。
- **历史教训**：v0.2.0→v0.5.2 期间积累的 20+ 条手工 curl 验证已全部固化为冒烟脚本，避免改一处 SQL 不知有没有弄坏别处。

## 4. 安全模型与遗留风险
- API：Bearer Token（constant-time 比对）、错误信息不带堆栈、数据库只读账号权限（复用 teslamate 账号，未来可收紧为只读角色）。
- 高德 Key / API Token / 铭牌容量等敏感配置只在 `.env`，永不出服务端；已加 `.gitignore` + 提交前密钥扫描。
- 明文 HTTP 的边界：局域网（家庭 WiFi，可接受）+ Tailscale 隧道（WireGuard 加密，可接受）。**不要**把 8090/3000/4000 端口直接映射到公网。
- APK 下载端点 `/apk/` 免鉴权（DownloadManager 无法加 header），依赖 Tailscale 隧道隔离；若将来暴露公网需改为带签名 URL。
- 遗留（见 operations.md §5）：`.env` 中 ChangeMe 弱密码×2、Grafana 匿名 Admin、`database/` 在 OneDrive 目录内、模拟器 headless 启动不稳。
