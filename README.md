# TeslaMate 自托管车况系统 + Android 客户端

> 在自家电脑上私有化运行 [TeslaMate](https://github.com/teslamate-org/teslamate)（特斯拉行车数据记录），
> 配 [teslamate-chinese-dashboards](https://github.com/wjsall/teslamate-chinese-dashboards) 中文仪表盘，
> 上面架一层**自研 Go API** 与**自研 Android 客户端**，再把全部车辆数据、诊断与控车能力
> 接到 **Hermes / 飞书**（自然语言查询 + 主动通知）。
>
> **当前版本**：App 0.136.0（versionCode 145）· 服务端 v0.153.0 · 最后更新 2026-11-13

一句话：**一个车主、一台车**的自托管车况系统 —— 数据不出家门，但手机上像原生 App 一样好用，
飞书里也能直接问「今天充了多少电」「胎压正常吗」「车里还热不热」。

📚 **刚接手 / 隔了很久回来？先读 [docs/INDEX.md](docs/INDEX.md)** ——
目录地图、数据流、三条主线工作流、常用命令、以及**九条红线**都在那一页。
本 README 只讲「它是什么」和「怎么把它跑起来」。

---

## 快速上手（3 条命令）

> ⚠️ **必须用 Git Bash**（Windows 上）。系统自带的 bash 可能解析到 WSL 垫片，
> 而 WSL 里没装 /bin/bash ⇒ 直接敲会报 execvpe(/bin/bash) failed。

@```bash
cd "<仓库路径>"

docker compose up -d      # 启动全部服务
bash scripts/smoke.sh     # 验证一切正常（49 项断言）
docker compose ps         # 看状态
@```

| 服务 | 地址 | 用途 |
|---|---|---|
| **grafana** | http://localhost:3000 | 中文仪表盘（浏览器看车况） |
| **api** | http://localhost:8090 | 手机客户端专用只读 API |
| teslamate | http://localhost:4000 | 数据采集主程序（首次登录特斯拉账号用） |
| database | 内部 5432 | PostgreSQL 18，**唯一数据源** |
| mosquitto | 内部 1883 | MQTT（teslamate 内部） |

停止：docker compose stop

---

## 整体架构（一图看懂）

@```
                      特斯拉云 API
                          |
             +------------v-------------+
             |  teslamate (4000)       |  拉取 + 流式接收车辆数据
             +------+----------+--------+
                    |          |
             +------v---+  +---v------+
             |mosquitto |  |          |
             +----------+  |          |
                    +------v----------v--------+
                    | database (postgres:18)   |  唯一的持久化数据源
                    +------+----------+--------+
                           |只读 SQL   |只读 SQL
                  +--------v--+   +---v--------------+
                  | grafana   |   | api (8090, Go)   |  Bearer 鉴权 + LRU 缓存
                  | 3000 中文字幕|   | 静态图中转 / APK |  Key 只存服务端
                  +-----------+   +-------+----------+
                                          |
                              Tailscale 加密隧道
                                          |
                              +-----------v-----------+
                              | Android App（爱车车况） |
                              | 车况/充电/行程/统计/设置 |
                              +-----------------------+
@```

**要点**：

- 手机**从不直连数据库**，只经 API 容器；API 只做 SELECT，不写库。
- **判定只在服务端做**：阈值、告警、健康度口径只有一份（App / 飞书 / 推送脚本都只读服务端结果）
  ⇒ 不会出现「App 说正常、飞书说异常」这种互相矛盾。
- 高德地图 Key、访问令牌等敏感配置只在服务端 .env，**永不出服务端**。
- 手机端支持**启动自动检查更新**（版本号比对 → 弹窗 → 系统下载 → 跳安装）。

---

## 目录结构

@```
teslamate/                          ← 项目根（Docker Compose 工作区）
├── compose.yaml                    ← 服务编排（database/mosquitto/teslamate/grafana/api/…）
├── .env                            ← 🔒 密钥与配置（API_TOKEN/AMAP_KEY/铭牌容量/版本号）
├── teslamate.env                   ← 🔒 teslamate 应用容器环境变量
├── database/                       ← PGDATA（勿动，勿提交）
├── grafana/data/                   ← Grafana sqlite
├── grafana/dashboards/             ← ⚠️ 第三方 AGPL-3.0 文件（TeslaMate 官方仪表盘存档，见 NOTICE）
├── mosquitto/                      ← MQTT 配置与数据
├── backups/                        ← pg_dump 备份（勿提交）
│
├── api/                            ← 🔧 手机 API 服务源码（Go，只读查库）
│   ├── README.md                   ←    代码导览 + 设计约束 + 冒烟示例
│   ├── main.go handlers.go queries.go models.go
│   └── Dockerfile
│
├── apk/teslamate-app.apk           ← 发版产物（勿提交；由 API 容器对外分发）
│
├── app-android/                    ← 📱 App 源码（**改代码只在这里改**）
│   ├── README.md                   ←    源码结构 / 构建方法 / 版本历史
│   └── app/src/main/java/...       （ui/ 界面层 + 数据层 + 网络层）
│
├── mcp/teslamate_mcp.py            ← 🔧 MCP 服务（Hermes 网关的子进程）
├── scripts/                        ← 🔧 运维脚本（冒烟 / 闸门 / 发版 / 备份 / 巡检）
├── docs/                           ← 📖 文档（先看 docs/INDEX.md 索引）
├── setup-android-sdk.sh            ← Android 构建环境一键安装（幂等）
└── dev-env.sh                      ← source 它获得 JDK/SDK 环境变量
@```

### ⚠️ 构建目录不是源码目录

| 目录 | 角色 |
|---|---|
| ./app-android/ | **源码的唯一真相** —— 改代码只在这里改 |
| 构建暂存目录（ASCII 路径） | **构建前的暂存**（Gradle 拒绝非 ASCII 路径 ⇒ 构建必须在这） |

原因见 [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) §0：工作区路径含非 ASCII 字符时，
Gradle 在此构建会锁死。

> ⚠️ **同步方向只有一个：仓库 → 构建目录。**
> 构建/测试前跑 bash scripts/sync-app-build-dir.sh（release.sh 与 unit-tests.sh 已自动调它）。
> **不要**在构建目录里改代码（随时被覆盖），**更不要**把它同步回仓库 ——
> 那样就是**旧副本覆盖新代码**，改动凭空消失且不报错。

---

## 关键配置（都在 .env，🔒 不入库）

| 变量 | 说明 | 作用 | 改完要做 |
|---|---|---|---|
| API_TOKEN | 48 位随机串 | App 访问 API 的令牌 | up -d api + App 设置页同步改 |
| AMAP_KEY | 高德 Web 服务 Key | 地图底图 | up -d api |
| NOMINAL_BATTERY_KWH | 铭牌电池容量 | 健康度分母 | up -d api |
| APP_VERSION_CODE / APP_VERSION_NAME | App 自更新比对依据 | 客户端闸门会校验 APK 与它一致 |
| APP_APK_URL | 更新包地址 | 通常不变 |
| TELEMETRY_HOST | 遥测接收端的对外域名 | 改后要重签证书 |
| CERTS_DIR | 遥测证书目录（**别放在同步盘里**） | 每周续期脚本用它 |

---

## 文档体系

先读 [docs/INDEX.md](docs/INDEX.md) 的索引，再按需深入：

| 文档 | 讲什么 |
|---|---|
| [docs/INDEX.md](docs/INDEX.md) | 目录地图 / 数据流 / 工作流 / 常用命令 / **九条红线** |
| [docs/architecture.md](docs/architecture.md) | 系统怎么搭的、为什么这么搭 |
| [docs/data-dictionary.md](docs/data-dictionary.md) | **数据口径单一事实源**（能耗/快充/电池容量怎么算） |
| [docs/api-spec.md](docs/api-spec.md) | API 契约（字段级） |
| [docs/adr.md](docs/adr.md) | 76 条架构决策索引（含**被否决的方案与原因**） |
| [docs/operations.md](docs/operations.md) | 运维手册（启停/备份/升级/发版/排查） |
| [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) | **开发约定**（改代码前必读） |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | 变更流水（v0.1.0 → v0.153.0，每条含根因/回滚） |
| [docs/手机客户端使用说明.md](docs/手机客户端使用说明.md) | 端用户手册（给家人看的） |

---

## 手机端连接

| 项 | 值 |
|---|---|
| 服务器地址 | 家里 WiFi：http://<电脑IP>:8090；外出：http://<主机名>:8090（Tailscale 主机名） |
| 访问令牌 | .env 里 API_TOKEN= 的值 |
| 更新方式 | App 启动自动检查，有新版本弹窗点「立即更新」 |

---

## 接手者的 30 分钟路径

1. 读本 README（5 分钟）
2. **Git Bash** 里跑 docker compose up -d && bash scripts/smoke.sh（5 分钟）确认环境活着
3. 浏览器开 http://localhost:3000 看仪表盘；装个 APK 看 App（10 分钟）
4. 读 docs/CONTRIBUTING.md + docs/data-dictionary.md（10 分钟）
5. 要改代码：先读 docs/CHANGELOG.md 对应版本了解历史坑，再看 api/README.md 或 app-android/README.md

---

## 已知限制与注意事项

- **电脑需保持开机**，服务跑在本机 Docker Desktop；关机后 App 显示离线。
- .env / teslamate.env / 证书 / database/ / backups/ 含密钥或车辆数据，**已在 .gitignore 里挡住**，不要提交到任何公开仓库。
- 本项目为**单用户自用**，无多租户 / 高可用设计；升级前请先 bash scripts/backup-db.sh。
- 服务端**只读**数据库；所有写入都来自 TeslaMate 自身。
- 部分判定在遥测数据接上之前会返回「不知道」而不是猜一个数 —— 这是刻意的（见 ADR #38 / #41）。

---

## 许可证与归属

本项目**自有代码**（api/、app-android/、mcp/、scripts/、docs/）采用 **MIT** 许可证，
见 [LICENSE](LICENSE)。

仓库里**混有第三方文件**，它们保留各自的许可证，**不在** MIT 覆盖范围内 —— 逐项清单见
[NOTICE](NOTICE)。要点：

| 组件 | 许可证 | 关系 |
|---|---|---|
| TeslaMate | **AGPL-3.0** | 容器部署、**未改动源码** ⇒ 本项目不是衍生作品 |
| grafana/dashboards/*.json | **AGPL-3.0** | TeslaMate 官方仪表盘的存档副本（未挂载进运行容器） |
| teslamate-chinese-dashboards | **AGPL-3.0** | 运行时容器镜像，未再分发源码 |
| tesla/vehicle-command、tesla/fleet-telemetry | 上游各自 | 运行时容器镜像，未改动 |

⚠️ **不要**把上面那三行 AGPL 组件当成 MIT 内容再分发。

---

## 隐私

- 仓库里**不含**任何密钥、令牌、车辆 VIN、定位坐标、序列号或主机名 ——
  全部在 .env（不入库）或已用占位符替换。
- **运行数据留在你自己家里**：数据库、备份、遥测证书都在本机，且不在同步盘里。
- 仓库内的少量示例数据（里程、能耗、充电曲线）是公开的演示口径，不含可定位到具体车辆的标识。
