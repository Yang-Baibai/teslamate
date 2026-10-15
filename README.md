# TeslaMate 私有部署 + 手机客户端项目

> 目标：在自家电脑上完整私有化运行 [TeslaMate](https://github.com/teslamate-org/teslamate/)（特斯拉行车数据记录），
> 使用 [teslamate-chinese-dashboards](https://github.com/wjsall/teslamate-chinese-dashboards) 中文仪表盘，
> 并配套一个自研的小米 Android 手机客户端，随时随地查看车况。

## 项目结构

```
teslamate/                          ← 本目录（Docker Compose 项目根）
├── compose.yaml                    ← 5 个服务：database / mosquitto / teslamate / grafana / api
├── .env                            ← Compose 密钥与配置（API_TOKEN / AMAP_KEY / NOMINAL_BATTERY_KWH）※勿外传
├── teslamate.env                   ← teslamate 应用容器专属环境变量
├── database/                       ← PostgreSQL 18 数据（PGDATA，勿动）
├── grafana/
│   ├── data/                       ← Grafana 运行数据（sqlite 库）
│   └── dashboards*/                ← 旧版官方英文仪表盘存档（已不再挂载，仅备份）
├── mosquitto/                      ← MQTT broker 配置与数据
├── api/                            ← 自研手机 API 服务源码（Go，只读查库）
├── apk/                            ← 发版目录：teslamate-app.apk 由 API 容器挂载并对外提供下载
├── app-android/                    ← Android 客户端源码副本（**不是构建目录**，见下）
├── teslamate-chinese-dashboards/   ← 中文仪表盘仓库克隆（v1.11.0，含 SQL 脚本与升级工具）
├── backups/                        ← 数据库基线备份（pg_dump）
├── docs/                           ← 项目文档（本次交付核心）
│   ├── CHANGELOG.md                ← 变更日志（做了什么/为什么/怎么回滚）
│   ├── architecture.md             ← 系统架构与设计决策
│   ├── api-spec.md                 ← 手机 API 接口文档
│   ├── operations.md               ← 运维手册（启动/备份/升级/回滚/故障排查）
│   └── 手机客户端使用说明.md         ← 端用户视角：安装/配置/字段口径
├── compose.yaml.bak.20261012-*     ← 切换中文镜像前的 compose 备份
├── setup-android-sdk.sh            ← Android 构建环境一键安装脚本（幂等）
└── dev-env.sh                      ← source 它获得 JDK/SDK 环境变量
```

## ⚠️ Android 源码的两个目录（容易搞混）

| 目录 | 角色 |
|---|---|
| `C:\tm-app\teslamate-app` | **canonical 构建目录**，Gradle 在这里跑，APK 产物在这里 |
| `./app-android/` | **只读源码镜像**，用于版本管理与交接；构建完从 canonical 同步过来 |

同步命令（排除 build 产物）：
```bash
robocopy C:\tm-app\teslamate-app\app\src\main ./app-android/app/src\main /E /XD build .gradle .kotlin
```
放在 `C:\tm-app` 而不是工作区的原因：工作区路径含中文用户名且在 OneDrive 同步目录内，Gradle 会在构建时遇到文件锁与编码问题。

## 服务一览

| 服务 | 端口 | 用途 |
|---|---|---|
| teslamate | http://localhost:4000 | 数据采集主程序（车辆登录/设置） |
| grafana | http://localhost:3000 | 49 个中文仪表盘（v1.11.0） |
| api | http://localhost:8090 | 手机客户端专用只读 REST API |
| database | 内部 5432 | PostgreSQL 18（唯一数据源） |
| mosquitto | 内部 1883 | MQTT（teslamate 内部使用） |

## 手机 App 配置所需

| 项目 | 值 |
|---|---|
| 服务器地址 | 家里 WiFi：`http://<电脑IP>:8090`；外出：`http://<电脑Tailscale主机名或IP>:8090` |
| 访问令牌 | `.env` 里 `API_TOKEN=` 的值 |
| 当前版本 | v0.5.2（App 内「设置 → 关于」也能看到） |

App 支持**启动时自动检查更新**：有新版本会弹窗，点「立即更新」→ 系统下载 → 跳安装。发版见 [docs/operations.md §3.5](docs/operations.md)。

## 关键配置项（都在 .env）

| 变量 | 当前值 | 作用 |
|---|---|---|
| `API_TOKEN` | 48 位随机串 | App 访问 API 的令牌，**换手机/改设置时要同步** |
| `AMAP_KEY` | 高德 Web 服务 Key | 行程/位置地图的底图（只存在服务端，App 不持有） |
| `NOMINAL_BATTERY_KWH` | `76.8` | **铭牌电池容量**，电池健康度的分母。换车改这里 |
| `APP_VERSION_CODE` / `APP_VERSION_NAME` | `12` / `0.5.2` | 发版时递增，App 靠它判断有无新版本 |
| `APP_APK_URL` | `http://<tailscale IP>:8090/apk/teslamate-app.apk` | 新版本下载地址 |
| `APP_RELEASE_NOTES` | 变更说明 | 弹窗里显示 |

## 交接必读（按顺序）

1. [docs/CHANGELOG.md](docs/CHANGELOG.md) —— 这个项目经历过什么（含每个 bug 的根因与教训）
2. [docs/architecture.md](docs/architecture.md) —— 系统怎么设计的、为什么这么设计
3. [docs/operations.md](docs/operations.md) —— 日常怎么管（备份/升级/发版/回滚/故障排查）
4. [docs/api-spec.md](docs/api-spec.md) —— 手机 API 契约与数据口径
5. [app-android/README.md](app-android/README.md) —— App 源码结构、构建方法、完整版本历史
6. [docs/手机客户端使用说明.md](docs/手机客户端使用说明.md) —— 端用户视角：安装/配置/字段含义/常见问题

## 已知注意事项

- 电脑需保持开机，栈跑在本机 Docker Desktop 上；关机后手机 App 显示离线。
- `.env` / `teslamate.env` / `compose.yaml.bak.*` 都含密钥（API_TOKEN、AMAP_KEY、数据库密码），**不要**提交到任何公开仓库。
- `database/` 目录在 OneDrive 同步范围内，Docker 直接读写它有损坏风险（当前能跑，但建议后续迁移出 OneDrive，见 operations.md）。
- Grafana 匿名访问当前开启（Admin 权限、仅局域网），如需收紧见 operations.md。
- 本机直连 GitHub 不通，克隆/拉取需走 `ghfast.top` 代理前缀。
- **中文路径技巧（2026-10-13 新增）**：写含本项目根的 `.cmd` 脚本请走 `C:\tm-ws` junction，而不是 `C:\Users\<用户名>\OneDrive\文档\teslamate`。详见 [docs/operations.md §8](docs/operations.md#8-windows-中文路径应对junction-速查)。
