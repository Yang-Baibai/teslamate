# 项目索引（teslamate · 自托管车况系统）

> **这份文档是给「刚接手 / 换了 agent / 隔了很久回来」的人看的**：
> 读完这一页，你就知道**东西在哪、怎么跑、哪些规矩不能破**。
>
> ⚠️ **维护规则**：新增目录、新增工作流、文件改名 ⇒ **回来更新这一页**。
> 否则索引本身就会过期 —— 而一份过期的索引比没有更坏（它会让人信错）。

---

## 0. 这是什么

把三样东西拼成一套**只给自己用**的车况系统：

```
TeslaMate（开源采集）→ PostgreSQL → 自写 Go API → 自写 Android App
                            ↓                  ↓
                     Fleet Telemetry      Hermes → 飞书 / 微信
```

- 车端数据由 **TeslaMate** 落库；车控走 Tesla 官方 `vehicle-command` sidecar。
- **判定口径只有服务端一处** —— App 与 Hermes/MCP 都只读服务端结果，
  绝不在客户端各算一套阈值（详见 §6）。
- 这不是给多租户用的产品：**一个车主、一台车、一套局域网 + Tailscale**。

---

## 1. 目录地图

| 路径 | 是什么 | 先看这几个文件 |
|---|---|---|
| `api/` | **Go 服务**（唯一的判定与聚合层） | `main.go`（路由表）· `handlers.go`（端点）· `models.go`（响应结构）· `queries.go`（SQL）· `alerts.go`（阈值判定）· `vehicle_safety.go` + `telemetry_vehicle.go`（车窗/车门/哨兵）· `battery_decay.go`（电池衰减）· `charge_outcome.go`（充电结局）· `tesla_command.go`（车控）· `feedback.go`（用户反馈 + 截图） |
| `app-android/` | **Android App**（Compose，Kotlin） | `Api.kt`（所有 HTTP 与数据类）· `ui/AppRoot.kt`（导航与 App 级状态）· `ui/StatusScreen.kt`（车况页）· `ui/RecordsScreen.kt` · `ui/ChargesScreen.kt` · `ui/StatsScreen.kt`（用量页）· `ui/SettingsScreen.kt`（设置 + 所有二级页）· `ui/Theme.kt`（颜色/字阶令牌）· `ui/Motion.kt`（动效令牌）· `ui/Widgets.kt`（共用组件） |
| `mcp/` | **MCP 服务**（给 Hermes/agent 用的工具集） | `teslamate_mcp.py`（全部工具）· `check_tool_fields.py`（**字段映射自检**）· `selftest_mcp.py` |
| `scripts/` | **运维与发版脚本** | `watch-car.ps1`（主动通知引擎）· `watch-car-selftest.ps1`（它的自测）· `release.sh`（一键发版）· `release-feedback-gate.sh`（**发版前的用户反馈闸门**）· `smoke.sh`（冒烟）· `client-gates.sh` / `server-gates.sh`（门禁）· `unit-tests.sh`（JVM 单测）· `backup-db.sh` / `backup-weekly.sh` · `tesla-authorize.ps1` / `tesla-partner.ps1`（Tesla 授权与车控签名） |
| `docs/` | **文档**（先读 `INDEX.md` 再读别的） | `CHANGELOG.md`（**改动史与决策记录**）· `api-spec.md`（接口）· `architecture.md` · `data-dictionary.md` · `operations.md`（运维手册）· `adr.md`（架构决策）· `手机客户端使用说明.md`（给车主看的）· `真机复核清单.md` |
| `apk/` | 发版产物（APK，由 API 静态分发） | — |
| `database/` `grafana/` `mosquitto/` `fleet-telemetry/` | 运行数据与配套服务 | — |
| `compose.yaml` `.env` | 编排与配置（**`.env` 不进版本库**） | — |

---

## 2. 数据流（谁产生什么，谁说了算）

| 数据 | 来源 | 谁解释 |
|---|---|---|
| 行程/充电/位置 | TeslaMate → PostgreSQL | `api/queries.go` |
| 锁车 / 实时胎压 / 遥测故障码 | Fleet API + Fleet Telemetry | `api/tesla.go` · `api/telemetry*.go` |
| 车窗 / 车门 / 哨兵 | **双源合并**（轮询 + 遥测），冲突时以**遥测**（官方消息）为准 | `api/vehicle_safety.go` |
| 告警阈值（胎压/数据过期…） | 服务端一处 | `api/alerts.go` |
| 充电结局（充到上限 / 中断 / 没供电） | 服务端按**收尾曲线**判定 | `api/charge_outcome.go` |
| 用户反馈（含截图） | App 提交 → `tm_user_feedback` | `api/feedback.go` |

---

## 3. 三条主线工作流

### 3.1 迭代（改代码 → 验证）

```bash
# ① 读用户反馈（**每次迭代的第一件事**）
python mcp/check_tool_fields.py        # 顺带确认工具字段没漂
#    或直接：GET /api/v1/feedback?status=new
#    逐条评估并写下结论（要处理 / 不处理+理由 / 已修好）：
#    MCP 的 teslamate_feedback_decision，或 POST /api/v1/feedback/{id}/status

# ② 改代码（服务端在 api/，App 在 app-android/）

# ③ 验证（**全绿才发版**）
bash scripts/server-gates.sh            # Go 语法 / 密钥扫描 / 仓库卫生
bash scripts/client-gates.sh            # 客户端 13 道闸门
bash scripts/smoke.sh                   # 冒烟（45 项）
python mcp/check_tool_fields.py         # MCP 字段自检

# ④ 构建 App（**必须走 ASCII 路径**，见 §5）
gradle -p C:/tm-ws/app-android assembleDebug --console=plain --offline
```

### 3.2 发版

```bash
bash scripts/release.sh "更新说明一句话"
```

它依次做：**用户反馈闸门** → 构建 App → 部署 APK → 同步源码 → 升 `.env` 版本号
→ 重建 API 镜像 → 冒烟验证。

⚠️ **改了 `.env` 之后，`docker compose restart` 不算数** ——
容器持有的是**创建时**的环境变量快照 ⇒ 必须 `docker compose up -d --force-recreate api`。

### 3.3 运维

- 车况告警由 **Hermes** 推飞书/微信（`scripts/watch-car.ps1`，Windows 计划任务每 5 分钟跑）。
  ⚠️ 改了它 ⇒ 跑 `pwsh -File scripts/watch-car-selftest.ps1`（引擎有 69 条断言）。
- 数据库备份：`scripts/backup-db.sh`（每天）/ `backup-weekly.sh`（每周）。
- Tesla 授权过期（一次性 refresh_token）：`scripts/tesla-authorize.ps1`。
- 详见 `docs/operations.md`。

---

## 4. 接手先读这 6 个文件

1. `docs/CHANGELOG.md` 顶部 3 条 —— 知道最近改了什么、为什么这么改。
2. `api/main.go` 的路由表 —— 知道服务端能干什么。
3. `app-android/app/src/main/java/com/teslamate/app/Api.kt` —— 知道 App 能取什么。
4. `scripts/release.sh` —— 知道一次发版到底经过哪些步骤。
5. `docs/adr.md` —— 知道哪些事**已经决定过**，别再讨论一遍。
6. `scripts/client-gates.sh` —— 知道客户端的规矩（尤其「啰嗦棘轮」）。

---

## 5. 常用命令

```bash
# 门禁与冒烟
bash scripts/server-gates.sh
bash scripts/client-gates.sh
bash scripts/smoke.sh

# 单测
docker run --rm -v "$(pwd -W)/api:/src" -w /src golang:1.23-alpine sh -c 'gofmt -l .; go test ./...'
gradle -p C:/tm-ws/app-android testDebugUnitTest --offline

# 构建 App（**走 ASCII junction 路径**）
# ⚠️ 直接用仓库路径会失败：Gradle 拒绝含非 ASCII 字符的项目路径
#    （「Your project path contains non-ASCII characters」）。
gradle -p C:/tm-ws/app-android assembleDebug --console=plain --offline

# 服务端
docker compose build api && docker compose up -d --force-recreate api
docker logs -f teslamate-api-1
```

---

## 6. 红线（破了就是事故）

| # | 红线 | 为什么 |
|---|---|---|
| 1 | **判定只在服务端做** | App / MCP / 推送脚本各自算阈值 ⇒ 三处漂移，用户看到互相矛盾的数字 |
| 2 | **反幻觉：宁可说「不知道」，不可编** | 遥测没接上 ≠ 车没故障；查不到上限 ≠ 现在的上限 |
| 3 | **阈值只在服务端一处，且要有单测钉住** | v0.98.0 踩过：计时器被时区吃掉 8 小时，而旧断言只钉「有没有超过阈值」⇒ 绿灯放行 |
| 4 | **改动既有端点的行为要慎重** | App 与 Hermes 都吃这些端点 ⇒ 改字段名 = 静默变 null |
| 5 | **中文窄栏里「限定语」独立成段** | 中文可在任意两字间断行 ⇒ 括号说明会被劈成两半 |
| 6 | **界面文案不许出现 Markdown `**`** | Compose 不解析 ⇒ 会原样印出星号（客户端闸门 1 会拦） |
| 7 | **常驻长说明块要么短，要么折进 ⓘ** | 「啰嗦棘轮」：新增的长说明必须登记进基线并写明理由 |
| 8 | **密钥不进版本库** | `.env` 与证书**不在** OneDrive 同步目录里 |

---

## 7. 相关文档

- 接口细节 → `docs/api-spec.md`
- 字段与口径 → `docs/data-dictionary.md`
- 运维手册 → `docs/operations.md`
- 架构决策 → `docs/adr.md`
- 给车主看的用法 → `docs/手机客户端使用说明.md`
- 改动史 → `docs/CHANGELOG.md`
