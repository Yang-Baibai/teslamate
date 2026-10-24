# 运维手册（Operations）

> 面向日常使用与应急处理。所有命令在项目根目录 `C:\Users\<用户名>\OneDrive\文档\teslamate` 下执行（Git Bash）。

## 1. 日常操作

### 1.1 启停整套服务
```bash
docker compose up -d          # 启动全部 5 个服务（database/mosquitto/teslamate/grafana/api）
docker compose stop           # 停止（数据保留）
docker compose ps             # 查看状态；database 应显示 (healthy)
```
电脑重启后 Docker Desktop 会自动拉起全部容器（restart: unless-stopped）。

### 1.2 手机 App 连接信息
| 项目 | 值 |
|---|---|
| 服务器地址 | 家里 WiFi：`http://<电脑IP>:8090`（`ipconfig` 查 IPv4）；外出：Tailscale 组网后 `http://<电脑Tailscale IP>:8090` |
| 令牌 | `.env` 文件中 `API_TOKEN=` 的值 |
| 换令牌 | 改 `.env` → `docker compose up -d api` → App 设置页同步修改 |

### 1.3 日志查看
```bash
docker compose logs -f --tail 100 teslamate   # 采集主程序（升级后必看）
docker compose logs -f --tail 100 grafana     # 仪表盘
docker compose logs -f --tail 100 api         # 手机 API（含每请求耗时）
```

## 2. 备份与恢复

### 2.1 数据库备份（唯一有状态数据源）
```bash
mkdir -p backups
docker exec teslamate-database-1 pg_dump -U teslamate -d teslamate > /c/tm-backups/db-$(date +%Y%m%d).sql
```
- 历史基线 `db-baseline-20261012.sql`（189 MB）已于 2026-10-24 删除；当前可用备份在 `C:\tm-backups\`（**不在 OneDrive 内**）。
- 建议频率：每月一次 + 任何动库操作之前。

### 2.2 整栈恢复（灾难场景）
```bash
# 1. 清空 database/ 目录（或换新目录），compose up -d database，等 healthy
# 2. 恢复
cat /c/tm-backups/db-YYYYMMDD.sql | docker exec -i teslamate-database-1 psql -U teslamate -d teslamate
# 3. docker compose up -d（其余服务重建 schema 缓存即可）
# 4. 注意：恢复后 teslamate_cn_extension_meta 若丢失，需重跑中文仪表盘 SQL（见 3.1）
```

## 3. 升级流程

### 3.1 Grafana（中文仪表盘）
```bash
docker compose pull grafana
docker compose up -d grafana
# 升级后必查（SQL 兼容契约）：
#   镜像要求: docker exec teslamate-grafana-1 cat /opt/teslamate-cn/versions.env
#   库里现状: docker exec teslamate-database-1 psql -U teslamate -d teslamate -tA \
#             -c "SELECT sql_revision FROM teslamate_cn_extension_meta"
# 两者不一致 → 重跑 SQL（幂等，先备份！）：
#   for f in coord-functions unit-functions tou indexes; do
#     docker exec -i teslamate-database-1 psql -U teslamate -d teslamate -v ON_ERROR_STOP=1 \
#       < teslamate-chinese-dashboards/sql/install-$f.sql; done
```

### 3.2 TeslaMate 本体
```bash
docker compose pull teslamate && docker compose up -d teslamate
docker compose logs -f teslamate   # 观察数据库迁移是否正常完成
```
⚠️ 升级 TeslaMate 前先备份（§2.1）；若其新迁移与中文仪表盘的函数冲突（历史上是 convert_* 函数），先看 teslamate-chinese-dashboards 仓库 TROUBLESHOOTING.md。

### 3.3 API（自研）
```bash
# 改了 api/ 源码后：
docker compose build api && docker compose up -d api
```

### 3.4 Android App 构建
```bash
# 构建机（本机）：
cd /c/tm-app/teslamate-app
export JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot' \
       ANDROID_HOME='C:\Users\<用户名>\AppData\Local\Android\Sdk' \
       GRADLE_USER_HOME='C:\gradle\gradle-home'
cmd //c "C:\gradle\gradle-8.10.2\bin\gradle.bat assembleDebug"
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

### 3.5 Android App 发版（一键更新流程，v0.1.4 起）

> ⚠️ 步骤 2 和 3 的顺序不能反：**必须先放 APK，再改 .env**。
> 因为 App 启动时就会去拉 `/api/v1/app/latest`，若元数据已升版但 APK 还是旧文件，
> 用户会下载到旧版却显示版本号已更新。

```bash
# 1. 构建（canonical 构建目录，见 README「两个目录」说明）
cd /c/tm-app/teslamate-app && cmd //c "C:\gradle\gradle-8.10.2\bin\gradle.bat assembleDebug"

# 2. 部署到固定路径（API 通过 FileServer 暴露 ./apk/teslamate-app.apk）
cp /c/tm-app/teslamate-app/app/build/outputs/apk/debug/app-debug.apk \
   "C:/Users/<用户名>/OneDrive/文档/teslamate/apk/teslamate-app.apk"

# 3. 同步源码回工作区镜像（排除 build 产物）
cmd //c "robocopy C:\tm-app\teslamate-app\app\src\main C:\Users\<用户名>\OneDrive\文档\teslamate\app-android\app\src\main /E /XD build .gradle .kotlin /NFL /NDL"
cp /c/tm-app/teslamate-app/app/build.gradle.kts "C:/Users/<用户名>/OneDrive/文档/teslamate/app-android/app/build.gradle.kts"

# 4. 升版本号（两处必须一致）
#    app/build.gradle.kts：versionCode +1、versionName 改新
#    .env：APP_VERSION_CODE 同步 +1、APP_VERSION_NAME 同步改新
#        APP_APK_URL       不变
#        APP_RELEASE_NOTES 改更新说明（会显示在弹窗里）
#        APP_MANDATORY     0/1（强制更新时才设 1）

# 5. 让 API 容器加载新元数据
docker compose up -d api

# 6. 验证（两步都要过）
TOKEN=$(grep '^API_TOKEN=' .env | cut -d= -f2)
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8090/api/v1/app/latest
#   应看到新的 version_code / version_name / release_notes
curl -sI http://localhost:8090/apk/teslamate-app.apk | grep -iE "HTTP|content-type|content-length"
#   应看到 HTTP 200、Content-Type: application/vnd.android.package-archive、Length ≈ 18 MB
#   ⚠️ Content-Type 若显示 application/zip，说明 .apk 的 MIME 没注册（v0.2.1 曾踩过）

# 7. 手机端：杀掉旧 App 重开（划掉最近任务，或 adb shell am force-stop）
#    会弹「发现新版本 vX.Y.Z」→ 点「立即更新」→ 系统下载 → 跳安装
```

⚠️ `APP_MANDATORY=1` 时对话框「稍后」按钮消失，必须升级才能用——只在 API 与旧 App 不兼容时启用。

⚠️ **不要**手动把 APK 拷贝到外部分享路径发给别人——那份会过期。对方直接从 `http://<你的地址>:8090/apk/teslamate-app.apk` 拿最新的即可。

### 3.6 常用配置速查（`.env`）

| 变量 | 什么时候改 | 改完要做 |
|---|---|---|
| `API_TOKEN` | 怀疑泄漏，或想让 App 重新认证 | `docker compose up -d api` + App 设置页同步改令牌 |
| `AMAP_KEY` | 高德配额用尽/被封，换新 Key | `docker compose up -d api`（App 端不用动） |
| `NOMINAL_BATTERY_KWH` | **换车**，填新车铭牌电池容量（kWh） | `docker compose up -d api`（App 端不用动） |
| `TZ_NAME` | 统计口径的本地时区 | `docker compose up -d api` |
| `TESLA_CLIENT_ID` | Tesla Fleet API 应用凭据（client id），换应用时改 | `docker compose up -d api`（App 端不用动） |
| `TESLA_CLIENT_SECRET` | Tesla Fleet API 应用凭据（client secret），换应用时改 | `docker compose up -d api`（App 端不用动） |
| `TESLA_REFRESH_TOKEN` | 锁车状态用的 refresh_token。**一次性、会轮换**，服务端刷新后自动回写本行 | 浏览器授权拿到后填这里 → `docker compose up -d api` |
| `TESLA_REGION` | 账号区域：`cn`（默认）/ `na` / `eu` | `docker compose up -d api` |
| `TESLA_VEHICLE_ID` | 指定车辆 id；不填就自动取车辆列表第一台 | `docker compose up -d api` |

> 换车时记得同时改 `cars` 表里 TeslaMate 记录的 `efficiency`（车辆效率 kWh/km），
> 否则能耗、电池容量反算都会偏。这个值在 TeslaMate 网页（4000 端口）里改。

> **Fleet API 凭据的接线（v0.8.4 起）**
> `compose.yaml` 的 `api` 服务目前只透传**显式列出**的环境变量，
> 上面这几个 `TESLA_*` 不会自动进容器。要让锁车状态生效，需要在 `api` 服务里补上：
>
> 1. `env_file: - .env`（或逐条写 `environment: - TESLA_CLIENT_ID=${TESLA_CLIENT_ID}`）；
> 2. 把 `.env` 以**可写**方式挂进容器（否则服务端没法把轮换后的 refresh_token 回写回来）。
>
> 没接线也不影响主服务：锁车状态恒为 `null`（未知），其余端点照常。
> 回写失败时服务端会打警告并退化为「写兜底文件」，**不会崩、也不会把凭据写坏**。

## 4. 故障排查

| 症状 | 排查顺序 |
|---|---|
| App 全部接口 401 | App 设置页令牌 vs `.env` 的 API_TOKEN 不一致；改后须 `up -d api` |
| App 提示网络错误 | `docker compose ps` 看 api 是否 Up；电脑 IP 是否变了；手机是否同网段/Tailscale 是否连通 |
| Grafana 打不开 | `docker compose logs grafana`；常见为 data 目录权限或镜像升级后 SQL revision 不匹配 |
| 概览页图表报 SQL 错 | 三组兼容 SQL 缺失 → 按 §3.1 重跑并核对 revision |
| 车辆数据停止更新 | teslamate 容器日志；token 过期需在 4000 端口重新登录特斯拉账号 |
| 手机地图无瓦片/偏移 | 瓦片用高德源，坐标必须用 `map_lat/map_lng`；检查 App 是否误用 lat/lng |

## 5. 待办与已知风险（按优先级）

1. ~~**`database/` 位于 OneDrive 同步目录**~~ ❌ **2026-10-16 实测推翻，此风险不成立**。
   项目根路径含 `OneDrive` 字样，但**实测本机并未同步该目录**：同步日志中 `teslamate`/`database` 零命中，1388 个数据库文件属性全为 `Archive`（零占位符），账户配置 9 个月未更新。**无需迁移。**
   ⚠️ **若将来重新登录/启用 OneDrive**：必须先把 `database/` 从同步中排除（或设为「始终保留在此设备上」），否则 PGDATA 会被上传，且一旦被脱水成占位符，Postgres 读到的是 0 字节存根。`backups/` 已迁到 `C:\tm-backups`（OneDrive 之外），历史遗留的旧备份文件仍需你手动迁走。
   > 教训记录在 CHANGELOG 2026-10-16 晚间段：不能从文件夹名推断它在同步。
2. ~~**弱密码**：`.env` 中 `***REMOVED***` / `***REMOVED***`。~~ ⏸️ **2026-10-16 决定维持现状**：局域网自用可接受，暂不轮换。
   ⚠️ 但要注意：**这两个口令的字面值已随本文件进入 git 历史**（`docs/operations.md` 曾直接写出它们），所以它们**不再构成秘密边界**。真正的安全边界是——**绝不把 8090 / 3000 / 4000 端口映射到公网**。若将来暴露面变化（公网访问、开放给他人、换到不可信网络），必须先按 §6 轮换口令。
   > 该行的口令字面值已按用户要求保留原文，未做脱敏改写——这是刻意决定，不是疏漏。
3. **Grafana 匿名 Admin**：`GF_AUTH_ANONYMOUS_ENABLED=true` 且角色 Admin。局域网便利性优先；若需收紧，置 false 并用 admin 账号登录。
4. ~~**无自动备份**~~ ✅ **2026-10-16 已解决**：Windows 计划任务 `TeslaMate-WeeklyDBBackup`，每周日 03:00 自动执行 `scripts/backup-weekly.sh`（备份 + 轮转 + 写日志）。详见 §9。手动补备份仍用 §2.1 的 `backup-db.sh`。
5. **API 明文 HTTP**：限定局域网 + Tailscale 内使用；严禁将 8090/3000/4000 直接端口映射到公网。

## 6. 改密钥操作指南（如需）

```bash
# 1) 数据库密码：改 .env 的 POSTGRES_PASSWORD 后
docker exec teslamate-database-1 psql -U teslamate -c "ALTER USER teslamate PASSWORD '新密码';"
docker compose up -d        # 让 teslamate/grafana/api 拿到新密码
# 2) API_TOKEN：改 .env → docker compose up -d api → App 设置页更新令牌
# 3) Grafana admin：改 .env 的 GF_SECURITY_ADMIN_PASSWORD → docker compose up -d grafana
```

## 7. 回滚速查

| 变更 | 回滚 |
|---|---|
| Grafana 切中文镜像（2026-10-11） | `cp compose.yaml.bak.20261012-000215 compose.yaml && docker compose up -d grafana` |
| 新增 api 服务 | compose 删掉 api 段落 + `docker compose up -d`（SQL/数据无影响） |
| 数据库动过（SQL/回算） | `cat /c/tm-backups/db-*.sql \| docker exec -i teslamate-database-1 psql -U teslamate -d teslamate`（整库恢复） |

## 8. Windows 中文路径应对（junction 速查）

 主项目根路径含中文（`OneDrive\文档\...`），`cmd.exe` 在解析含该路径的 `.cmd` 脚本时容易把 `文档` 解析为命令分隔符并触发 `'<x>' is not recognized` 错误。`chcp 65001 > nul` 加在脚本首行无效——码页切换发生在脚本解析完成之后。

 **应对**：在 `C:\tm-ws` 建一个 Windows 目录 junction（零数据迁移）指向项目根：

 ```cmd
 mklink /J C:\tm-ws "C:\Users\<用户名>\OneDrive\文档\teslamate"
 ```

 之后所有脚本一律走 `C:\tm-ws`，例如：

 ```cmd
 robocopy "C:\tm-app\teslamate-app" "C:\tm-ws\app-android" /E /XD build .gradle .kotlin
 docker compose -f C:\tm-ws\compose.yaml logs -f --tail 100 api
 ```

 撤销：`rmdir C:\tm-ws`（仅删 junction，原中文路径不受影响）。也可用于本项目的源码同步脚本（见 `app-android/README.md`）。

## 8.5 远程访问（Tailscale）

### 当前状态（2026-10-16 实测更新）

| 场景 | 状态 |
|---|---|
| 家里 WiFi | ✅ 直连（`direct <局域网IP>`） |
| **外出（蜂窝网）** | ✅ **已修复**——路由器开启 UPnP + 端口映射后走 IPv6 直连 |

**App 连的是什么**：手机上服务器地址是 `http://<主机名>:8090`，即 **Tailscale MagicDNS
主机名**，解析到 `<tailscale IP>`。因此 Tailscale 是必要条件。局域网 IP `<局域网IP>:8090`
虽然从手机 TCP 可达，但只在家里有效——**不要改成局域网 IP**，核心需求是外出也能用。

### 修复记录：外出断连 → 已解决

**症状**：切到蜂窝网后断连，`tailscale status` 显示 `relay "lax"`（洛杉矶中继），
`tailscale ping` 超时，服务端零请求，App 一直显示断网前的缓存。

**根因**：路由器（小米路由器，`<局域网IP>1`）**未开启 UPnP**，`PortMapping` 为空。
手机在运营商 NAT 后（10.22.51.x），双方都不暴露端口 → 打洞失败 → 退化成 DERP → 而 DERP 在
洛杉矶且那条也超时。

**修复**：小米路由���后台开启 UPnP 状态 → Tailscale 自动注册映射
（`41641/UDP` → `<局域网IP>`）。

**修复前后实测对比**：

| 指标 | 修复前（蜂窝） | 修复后（蜂窝） |
|---|---|---|
| 服务端收到的轮询 | **0**（2.5 分钟零请求） | **4**，精确 15 秒间隔 |
| Tailscale 路径 | `relay "lax"` | **`direct [240e:...]`（IPv6 直连）** |
| `PortMapping` | 空 | `UPnP, NAT-PMP, PCP` |

走 IPv6 直连比端口转发本身更干净——手机在运营商网络上拿到了 IPv6 地址，直接绕开 NAT。

⚠️ **端口映射是租约制，会过期。** 请把 `<局域网IP>`（本机）在小米后台设为
**DHCP 静态绑定**，否则电脑 IP 一变，转发静默失效且不报错。

### ⚠️ 未解决：网络切换后 Tailscale 会卡死

从 WiFi 切到蜂窝、再切回 WiFi 之后，Tailscale 停在 `idle`——**tun0 接口还在、VPN 显示
「已连接」、路由表也正常，但就是不通**。App 一直显示旧数据，服务端日志零请求。
**与端口转发无关，是独立问题。**

**解法**（亲测有效）：

```bash
adb shell am force-stop com.tailscale.ipn
adb shell monkey -p com.tailscale.ipn -c android.intent.category.LAUNCHER 1
```

手工的话：下拉通知栏 → 关掉 Tailscale → 再打开。

**判断是否卡死**：`adb shell ping -c 3 <tailscale IP>` —— 有包=通，100% 丢包=卡死了。

### ⚠️ 未完全解决：约 1 小时后再次卡死

**长时间观察实测（2026-10-16，蜂窝网下连续监测 80 分钟）**：

```
300s  ~ 2400s   IPv6 直连   请求 2    ← 稳定 40 分钟 ✓
2700s           IPv6 直连   请求 1    ← 开始衰减
3000s ~ 3300s   直连(v4)   请求 4/6  ← 退化为非 IPv6 直连
3600s           中继       请求 0    ← 掉线
3900s ~ 4800s   卡死       请求 0    ← 彻底卡死
```

**PC 侧端口映射全程有效**（`PortMapping: UPnP, NAT-PMP, PCP` 始终在），
所以不是租约过期，也不是 UPnP 配置问题。

**最可能的原因**：手机在移动网络上拿到的 **IPv6 地址是有寿命的**（运营商侧临时地址按小时
分配，跨基站时前缀还会变）。约一小时后地址变化 → Tailscale 缓存的直连端点失效
→ 退化成 IPv4 打洞 → 再退化到中继 → 卡死。

两次卡死的时间点都对得上：**一次是网络切换时，一次是移动网络地址自然轮换后**。

**实际影响**：出门可用，但**约每小时可能需要重启一次 Tailscale**。
相比修复前「外出完全连不上」已是根本性改善。

**解法**（亲测有效，每次都能立刻恢复到 IPv6 直连）：

```bash
adb shell am force-stop com.tailscale.ipn
adb shell monkey -p com.tailscale.ipn -c android.intent.category.LAUNCHER 1
```

手工的话：下拉通知栏 → 关掉 Tailscale → 再打开。

**判断是否卡死**：`adb shell ping -c 3 <tailscale IP>` —— 有包=通，100% 丢包=卡死了。

**下次出门前建议**：先打开 App 确认能刷出数据；若显示的是几分钟前的旧数据，
就是卡死了，重启 Tailscale 即可。

### ⚠️ 不要为解决这个问题去关 Clash

实测：**关掉 Clash 对外出断连零帮助，却会砸掉 Docker 镜像升级能力**。

| 用途 | 关 Clash 后 |
|---|---|
| App / API / 本地通信 | 不受影响 |
| Gradle 构建（阿里云 Maven） | 不受影响（直连可用） |
| Go 构建（goproxy.cn） | 不受影响（直连可用） |
| git clone / push（github.com） | 不受影响（现在直连已通） |
| **`docker compose pull`** | **全部失败**（ghcr.io 与 Docker Hub 直连均为 000） |

关掉它等于**再也无法升级 Grafana 中文仪表盘和三个核心镜像**。详见 architecture.md §2.7。

**补充说明**：Clash 也**不是** DERP 选到洛杉矶的原因——实测清空 `HTTP_PROXY`/`HTTPS_PROXY`
后重跑 `netcheck`，Nearest DERP 与延迟数值几乎不变。Tailscale 的 DERP 探测走直连 UDP
（Clash 未开 TUN，抓不到它），那些 160~230ms 是宽带到 DERP 的真实网络状况。

### 待办

| 项 | 说明 | 优先级 |
|---|---|---|
| 网络切换卡死 | 见上，目前只有手动重启的 workaround | 中 |
| 手机 Tailscale「忽略电池优化」 | 减少被系统杀进程导致的断连 | 低 |
| 自建中国境内 DERP | 兜底路径从 178ms 降到 30~50ms | 低（直连已够用） |

---

## 9. 自动备份（Windows 计划任务）

> 2026-10-16 建立。此前从 10-12 基线之后一直没再备份，而数据库是本项目**唯一有状态资产**。

### 9.1 现状
| 项 | 值 |
|---|---|
| 任务名 | `TeslaMate-WeeklyDBBackup` |
| 触发 | 每周日 03:00 |
| 实际执行 | `"C:\Program Files\Git\bin\bash.exe" -c "cd /c/tm-ws && bash scripts/backup-weekly.sh"` |
| 产物 | `/c/tm-backups/db-YYYYMMDD.sql`，保留最近 8 份（`TM_BACKUP_DIR` 可改目录） |
| 日志 | `/c/tm-backups/backup.log`（每次运行追加） |

`backup-weekly.sh` = 跑一次 `backup-db.sh` + 轮转清理 + 写日志。它会在开始前检查 `docker` 是否可用、`teslamate-database-1` 是否在跑，不满足直接记失败退出（计划任务的输出默认被系统丢弃，没有日志就等于黑盒）。

### 9.2 为什么要走 `C:\tm-ws`
计划任务的命令行由 Task Scheduler 解析，中文路径会踩 §8 那个坑。junction 路径全 ASCII，一次绕开。

### 9.3 轮转的 baseline 保护
轮转只匹配 `$BACKUP_DIR/db-[0-9]*.sql`，**`db-baseline-*` 不匹配**（`db-` 后面是字母 `b` 不是数字），因此基线种子文件永远不会因轮转被删。改脚本时别把这个 glob 改成 `db-*.sql`。
> 2026-10-24：历史基线 `db-baseline-20261012.sql` 已按用户要求删除（它当时还在 OneDrive 同步路径下）。上述 glob 机制本身不变。

### 9.4 常用操作
```bash
# 手动跑一次（和计划任务完全一致，含轮转）
bash scripts/backup-weekly.sh

# 只补一份备份、不轮转（动数据库前用）
bash scripts/backup-db.sh
```

```powershell
# 查看任务状态 / 上次结果码（0 = 成功）
schtasks /Query /TN "TeslaMate-WeeklyDBBackup" /FO LIST /V

# 立刻触发一次（验证用，不等周日）
schtasks /Run /TN "TeslaMate-WeeklyDBBackup"

# 改触发时间（示例：改成周六 04:00）
schtasks /Change /TN "TeslaMate-WeeklyDBBackup" /ST 04:00

# 删除自动备份（改回手动）
schtasks /Delete /TN "TeslaMate-WeeklyDBBackup" /F
```

### 9.5 已知限制
- **电脑关机或 Docker Desktop 没启动时会失败**（`execvpe`/容器未运行）。脚本会写日志并以非 0 退出，**不会**产出半截文件——`backup-db.sh` 内置了 10KB 体积校验，失败即删。
- 备份**已迁出项目目录**，落在 C:\tm-backups（默认 /c/tm-backups，可用 TM_BACKUP_DIR 覆盖）。
 原因：项目目录在 OneDrive 同步路径下，200MB 级的 pg_dump 全文（含**全部精确位置历史**、充电记录、行程，以及 private.tokens 里 Cloak 加密的 TeslaMate 凭据）会被自动上传到云端 —— 与「私有部署、不上云」的初衷相反。database/ 同样必须排除。
 历史上已生成在旧目录的备份仍留在 OneDrive 里，需要你决定是否迁走（见交付说明）。
- 磁盘占用：8 × ~195MB ≈ 1.6GB。超过就开始轮转删旧的。

## 10. 车况提醒 → Hermes 推送

> 2026-10-24 建立。**App 不再自己发通知**，提醒统一走本机的 Hermes（微信/飞书）。

### 10.1 架构

```
TeslaMate 栈（本机 Docker）              Windows 计划任务
   api:8090  ← 只读 REST                    TeslaMate-CarWatch（每 5 分钟）
        │                                        │
        │ HTTP 127.0.0.1:8090                  │ pwsh scripts/watch-car.ps1
        ▼                                        ▼
   database ← teslamate 采集            Hermes（hermes send --to feishu）
                                             │
                                             ▼
                                        飞书 / 微信（你）
```

**为什么监听放在服务端而不是 App 里**：
- 这台电脑 7×24 开着，是 TeslaMate 的宿主；手机可能连不上它（外出 / Tailscale 卡住）。
  监听放在服务端，外出照样能收到提醒。
- 判定口径统一在 API 层（`api/alerts.go`），App 和推送脚本读同一份结果。

### 10.2 常用操作

```bash
# 手动跑一轮（真实推送）
pwsh -File scripts/watch-car.ps1

# 干跑，只打印不发送（调试用）
pwsh -File scripts/watch-car.ps1 -DryRun
```

```powershell
# 任务状态 / 上次结果码（0 = 成功）
schtasks /Query /TN "TeslaMate-CarWatch" /FO LIST /V

# 立刻触发一次
schtasks /Run /TN "TeslaMate-CarWatch"

# 改周期（示例：改成 10 分钟）
schtasks /Change /TN "TeslaMate-CarWatch" /MO 10

# 停止自动推送
schtasks /Delete /TN "TeslaMate-CarWatch" /F
```

日志：`scripts/.watch-car.log`（每次运行追加）。状态：`scripts/.watch-car-state.json`
（记录上一次车况用于判断跳变，**不要手工改**，删掉它等于让脚本重新建立基线）。

### 10.3 配置（在项目根 `.env`）

| 变量 | 默认 | 说明 |
|---|---|---|
| `WATCH_HERMES_TARGET` | `feishu` | 推送目标。`feishu` 或 `weixin` |
| `WATCH_API_BASE` | `http://127.0.0.1:8090` | API 地址 |
| `WATCH_CAR_ID` | `1` | 车辆 id |
| `HERMES_BIN` | `%LOCALAPPDATA%\hermes\bin\hermes.exe` | Hermes 可执行文件 |

**同名环境变量优先于 `.env`**（临时改配置不用动文件）。

### 10.4 ⚠️ 微信通道的前置条件

```
hermes send --to weixin "xxx"
→ iLink sendmessage session not ready: ret=-2 prepare failed
  — the user must send the bot a message first (or re-pair)
```

**微信必须先给机器人发一条消息**（或重新配对）才能主动推送。
飞书没有这个限制，所以**默认走飞书**。想换微信：先给微信机器人发一句话，
再把 `WATCH_HERMES_TARGET` 改成 `weixin` 即可。

⚠️ **必须用 `hermes.exe`，不能用 `hermes.cmd`**：后者是 batch shim，
程序化调用（计划任务 / `Process.Start`）会静默失败。

### 10.5 推送规则

只在**状态跳变**时推送，否则每 5 分钟一条噪音，几天后就会被关掉。
完整口径见 `docs/data-dictionary.md` §3.5。

| 事件 | 触发 | 首轮（无历史状态） |
|---|---|---|
| 🔌 开始充电 | 停着 → 充电中 | **不推**（是状态不是事件） |
| ✅ 充电完成 | 充电中 → 停止 | **不推**（同上） |
| ⚠️ 胎压异常 | `tpms_alert` 由 false 变 true | **推**（现存问题应立刻知道） |
| ⓘ 数据超 24h 未更新 | `data_stale` 由 false 变 true | **推**（同上） |

同一异常只推一次，恢复正常后才重置。

---
