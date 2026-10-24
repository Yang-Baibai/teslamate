# TeslaMate 私有部署 + 手机客户端

> 在自家电脑上私有化运行 [TeslaMate](https://github.com/teslamate-org/teslamate/)（特斯拉行车数据记录），
> 使用 [teslamate-chinese-dashboards](https://github.com/wjsall/teslamate-chinese-dashboards) 中文仪表盘，
> 并配套自研的小米 Android 客户端，随时随地查看车况。
>
> **当前版本**：App v0.8.3（versionCode 18）· 仪表盘 v1.11.0（SQL rev 14）· 最后更新 2026-10-24

---

## 快速上手（3 条命令）

> ⚠️ **必须用 Git Bash**，不是 PowerShell、不是 CMD。
> 本机 `bash` 解析到 `C:\WINDOWS\system32\bash.exe`（WSL 垫片），而 WSL 没装 `/bin/bash`，
> 直接敲 `bash scripts/smoke.sh` 会报 `execvpe(/bin/bash) failed: No such file or directory`。
> 打开 Git Bash（开始菜单搜 "Git Bash"）后再执行下面的命令。

```bash
cd "C:/Users/<用户名>/OneDrive/文档/teslamate"

docker compose up -d      # 启动全部 5 个服务
bash scripts/smoke.sh     # 验证一切正常（14 项断言）
docker compose ps         # 看状态
```

已装好 Git Bash 的话，也可以从任意终端直接调用：

```powershell
& "C:\Program Files\Git\bin\bash.exe" -c "cd /c/tm-ws && bash scripts/smoke.sh"
```

| 服务 | 地址 | 用途 |
|---|---|---|
| **grafana** | http://localhost:3000 | 49 个中文仪表盘（浏览器看车况） |
| **api** | http://localhost:8090 | 手机客户端专用只读 API |
| teslamate | http://localhost:4000 | 数据采集主程序（首次登录特斯拉账号用） |
| database | 内部 5432 | PostgreSQL 18，**唯一数据源** |
| mosquitto | 内部 1883 | MQTT（teslamate 内部） |

停止：`docker compose stop`

---

## 整体架构（一图看懂）

```
                      特斯拉云 API
                          │
             ┌────────────▼─────────────┐
             │  teslamate (4000)       │  拉取 + 流式接收车辆数据
             └──────┬──────────┬────────┘
                    │          │
             ┌──────▼───┐  ┌───▼──────┐
             │mosquitto │  │          │
             └──────────┘  │          │
                    ┌──────▼──────────▼────────┐
                    │ database (postgres:18)   │  唯一的持久化数据源
                    └──────┬──────────┬────────┘
                           │只读 SQL   │只读 SQL
                  ┌────────▼──┐   ┌───▼──────────────┐
                  │ grafana   │   │ api (8090, Go)   │  Bearer 鉴权 + LRU 缓存
                  │ 3000 中文字幕│   │ 静态图中转 / APK │  Key 只存服务端
                  └───────────┘   └───────┬──────────┘
                                          │
                              Tailscale 加密隧道
                                          │
                              ┌───────────▼───────────┐
                              │ Android App (爱车车况) │
                              │ 车况/充电/行程/统计/设置 │
                              └───────────────────────┘
```

**要点**：
- 手机**从不直连数据库**，只经 API 容器；API 只做 SELECT，不写库。
- 高德地图 Key、APK 下载地址等敏感配置只在服务端 `.env`，**永不出服务端**。
- 手机端支持**启动自动检查更新**（版本号比对 → 弹窗 → 系统下载 → 跳安装）。

---

## 目录结构

```
teslamate/                          ← 项目根（Docker Compose 工作区）
├── compose.yaml                    ← 5 服务编排（database/mosquitto/teslamate/grafana/api）
├── .env                            ← 🔒 密钥与配置（API_TOKEN/AMAP_KEY/铭牌容量/版本号）
├── teslamate.env                   ← 🔒 teslamate 应用容器环境变量
├── database/                       ← PGDATA（~300MB，勿动，勿提交）
├── grafana/data/                   ← Grafana sqlite
├── mosquitto/                      ← MQTT 配置与数据
├── import/                         ← teslamate 导入目录
├── backups/                        ← pg_dump 备份（勿提交）
│
├── api/                            ← 🔧 手机 API 服务源码（Go，只读查库）
│   ├── README.md                   ←    代码导览 + 设计约束 + 冒烟示例
│   ├── main.go handlers.go queries.go models.go
│   └── Dockerfile
│
├── apk/teslamate-app.apk           ← 发版产物（勿提交；由 API 容器对外分发）
│
├── app-android/                    ← 📱 App 源码镜像（只读，不在此构建）
│   ├── README.md                   ←    源码结构 / 构建方法 / 版本历史
│   └── app/src/main/java/...        （7 个根类 + 15 个 ui/）
│
├── teslamate-chinese-dashboards/   ← 中文仪表盘仓库克隆（外部 .git，不提交）
├── scripts/                        ← 🔧 运维脚本
│   ├── smoke.sh                    ←    12 项冒烟测试（改完必跑）
│   ├── backup-db.sh                ←    数据库备份
│   └── release.sh                  ←    一键发版（构建+部署+升版本+冒烟）
│
├── docs/                           ← 📖 文档（先看 docs/README.md 索引）
├── compose.yaml.bak.*              ← 历史备份（勿提交）
├── setup-android-sdk.sh            ← Android 构建环境一键安装（幂等）
└── dev-env.sh                      ← source 它获得 JDK/SDK 环境变量
```

### ⚠️ 两个 Android 目录（最容易搞混）

| 目录 | 角色 |
|---|---|
| `C:\tm-app\teslamate-app` | **canonical 构建目录**，Gradle 在此跑，APK 在此产出 |
| `./app-android/` | **只读源码镜像**，用于版本管理与交接 |

原因见 [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md) §0：工作区路径含中文用户名且在 OneDrive 内，Gradle 在此构建会锁死。同步用 `scripts/release.sh` 第 3 步。

---

## 关键配置（都在 `.env`，🔒 不入库）

| 变量 | 当前值 | 作用 | 改完要做 |
|---|---|---|---|
| `API_TOKEN` | 48 位随机串 | App 访问 API 的令牌 | `up -d api` + App 设置页同步改 |
| `AMAP_KEY` | 高德 Web 服务 Key | 地图底图 | `up -d api` |
| `NOMINAL_BATTERY_KWH` | `76.8` | **铭牌电池容量**，健康度分母 | `up -d api` |
| `APP_VERSION_CODE/NAME` | `17` / `0.8.2` | App 自更新比对依据 | `up -d api` |
| `APP_APK_URL` | `http://<tailscale IP>:8090/apk/teslamate-app.apk` | 更新包地址 | 通常不变 |

---

## 文档体系

先读 [`docs/README.md`](docs/README.md) 的索引，再按需深入：

| 文档 | 讲什么 |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | 系统怎么搭的、为什么这么搭 |
| [`docs/data-dictionary.md`](docs/data-dictionary.md) | **数据口径单一事实源**（能耗/快充/电池容量怎么算） |
| [`docs/api-spec.md`](docs/api-spec.md) | API 契约（字段级） |
| [`docs/adr.md`](docs/adr.md) | 22 条架构决策索引（含否决的方案与原因） |
| [`docs/operations.md`](docs/operations.md) | 运维手册（启停/备份/升级/发版/排查） |
| [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md) | **开发约定**（改代码前必读） |
| [`docs/CHANGELOG.md`](docs/CHANGELOG.md) | 变更流水（v0.1.0 → v0.8.3，每条含根因/回滚） |
| [`docs/手机客户端使用说明.md`](docs/手机客户端使用说明.md) | 端用户手册（给家人看的） |

---

## 手机端连接

| 项 | 值 |
|---|---|
| 服务器地址 | 家里 WiFi：`http://<电脑IP>:8090`；外出：`http://<主机名>:8090`（Tailscale 主机名） |
| 访问令牌 | `.env` 里 `API_TOKEN=` 的值 |
| 更新方式 | App 启动自动检查，有新版本弹窗点「立即更新」 |

---

## 接手者的 30 分钟路径

1. 读本 README（5 分钟）
2. **Git Bash** 里跑 `docker compose up -d && bash scripts/smoke.sh`（5 分钟）确认环境活着
   —— 注意不能用 PowerShell/CMD，原因见「快速上手」开头的警告
3. 浏览器开 http://localhost:3000 看仪表盘；`adb shell screencap` 看 App（10 分钟）
4. 读 `docs/CONTRIBUTING.md` + `docs/data-dictionary.md`（10 分钟）
5. 要改代码：先读 `docs/CHANGELOG.md` 对应版本了解历史坑，再看 `api/README.md` 或 `app-android/README.md`

---

## 已知限制与注意事项

- **电脑需保持开机**，服务跑在本机 Docker Desktop；关机后 App 显示离线。
- `.env` / `teslamate.env` / `compose.yaml.bak.*` 含密钥，**不要提交到任何公开仓库**（`.gitignore` 已保护）。
- `database/` 在 OneDrive 同步目录内，Docker 直接读写 PGDATA 有损坏风险（当前正常，建议后续迁出，见 `operations.md`）。
- 本机直连 GitHub 不通，克隆走 `https://ghfast.top/` 前缀。
- cmd 含中文路径的脚本请走 `C:\tm-ws` junction（见 `operations.md` §8）。
- 本项目为**单用户自用**，无多租户/高可用设计；升级前请先 `bash scripts/backup-db.sh`。
