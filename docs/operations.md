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
docker exec teslamate-database-1 pg_dump -U teslamate -d teslamate > backups/db-$(date +%Y%m%d).sql
```
- 现有基线：`backups/db-baseline-20261012.sql`（189 MB）。
- 建议频率：每月一次 + 任何动库操作之前。

### 2.2 整栈恢复（灾难场景）
```bash
# 1. 清空 database/ 目录（或换新目录），compose up -d database，等 healthy
# 2. 恢复
cat backups/db-YYYYMMDD.sql | docker exec -i teslamate-database-1 psql -U teslamate -d teslamate
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

> 换车时记得同时改 `cars` 表里 TeslaMate 记录的 `efficiency`（车辆效率 kWh/km），
> 否则能耗、电池容量反算都会偏。这个值在 TeslaMate 网页（4000 端口）里改。

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
   ⚠️ **若将来重新登录/启用 OneDrive**：必须先把 `database/` 从同步中排除（或设为「始终保留在此设备上」），否则 PGDATA 会被上传，且一旦被脱水成占位符，Postgres 读到的是 0 字节存根。`backups/` 可以继续同步（每周才写一次）。
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
| 数据库动过（SQL/回算） | `cat backups/db-*.sql \| docker exec -i teslamate-database-1 psql -U teslamate -d teslamate`（整库恢复） |

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

## 9. 自动备份（Windows 计划任务）

> 2026-10-16 建立。此前从 10-12 基线之后一直没再备份，而数据库是本项目**唯一有状态资产**。

### 9.1 现状
| 项 | 值 |
|---|---|
| 任务名 | `TeslaMate-WeeklyDBBackup` |
| 触发 | 每周日 03:00 |
| 实际执行 | `"C:\Program Files\Git\bin\bash.exe" -c "cd /c/tm-ws && bash scripts/backup-weekly.sh"` |
| 产物 | `backups/db-YYYYMMDD.sql`，保留最近 8 份 |
| 日志 | `backups/backup.log`（每次运行追加） |

`backup-weekly.sh` = 跑一次 `backup-db.sh` + 轮转清理 + 写日志。它会在开始前检查 `docker` 是否可用、`teslamate-database-1` 是否在跑，不满足直接记失败退出（计划任务的输出默认被系统丢弃，没有日志就等于黑盒）。

### 9.2 为什么要走 `C:\tm-ws`
计划任务的命令行由 Task Scheduler 解析，中文路径会踩 §8 那个坑。junction 路径全 ASCII，一次绕开。

### 9.3 轮转的 baseline 保护
轮转只匹配 `backups/db-[0-9]*.sql`，**`db-baseline-*` 不匹配**（`db-` 后面是字母 `b` 不是数字），因此基线种子文件永远不会因轮转被删。改脚本时别把这个 glob 改成 `db-*.sql`。

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
- 备份落在项目目录内（同 `database/`）。实测本机 OneDrive 未同步该目录，因此无同步风险；若将来启用 OneDrive，`backups/` 可以放心同步（每周才写一次），但 `database/` 必须排除。
- 磁盘占用：8 × ~195MB ≈ 1.6GB。超过就开始轮转删旧的。
