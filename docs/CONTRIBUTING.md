# 开发约定与工作流

> 给任何在这个项目上写代码的人（包括未来的我自己）。目标是：**改动可追溯、不破坏既有口径、不引入新的坑。**

## 0. 环境速查

| 用途 | 位置 |
|---|---|
| 项目根（Compose 工作区） | `<仓库路径>`（下称 `ROOT`） |
| App 构建目录（暂存） | `C:\tm-app\teslamate-app`（ASCII 路径，见下方"为什么不是 ROOT"） |
| Gradle 用户目录 | `C:\gradle\gradle-home` |
| Gradle 发行版 | `C:\gradle\gradle-8.10.2\bin\gradle.bat` |
| JDK / Android SDK | `C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot` / `%LOCALAPPDATA%\Android\Sdk` |
| 构建环境变量 | `bash dev-env.sh` 或 `source setup-android-sdk.sh` 生成的 `dev-env.sh` |

### 为什么 App 构建目录不在项目里

项目路径含**中文用户名**且在 **OneDrive 同步目录**内。Gradle 构建会产生数万个小文件，
在这两个条件下会出现文件锁、编码、同步冲突。实测踩过多次。

**规则（v0.131.0）**：**代码只改 `ROOT/app-android/`（唯一真相）**，
构建/测试前跑 `bash scripts/sync-app-build-dir.sh` 把仓库同步到构建目录，**APK 从构建目录产出**。

⚠️ **不要**在构建目录里改代码（随时被同步覆盖），**更不要**把它同步回仓库 ——
副本会被写旧（2026-11-04 实测：仓库 v0.130.0、副本 v0.103.0，差 17 个版本）⇒
反向同步就是**旧副本覆盖新代码**，改动凭空消失且不报错。

## 1. 改动流程（标准动作）

```
1. 备份（动数据库前）：bash scripts/backup-db.sh
2. 改代码（api/ 或 app-android/）
3. 跑验证：bash scripts/smoke.sh          （改了 api/ 必跑）
   App 改动：bash scripts/sync-app-build-dir.sh && cd /c/tm-app/teslamate-app && gradle assembleDebug
4. 真机验证（改了 UI）：adb install -r ... && 真机点一遍，别只信编译过
5. 更新文档（见下"什么改动必须改哪份文档"）
6. 提交：
   - 改了版本号：bash scripts/release.sh "更新说明"（含构建+部署+验证）
   - 纯文档/小修：git add -A && git commit
7. CHANGELOG 记一条（做了什么 / 为什么 / 影响 / 回滚）
```

## 2. 什么改动必须改哪份文档

| 我改了 | 必须同步 | 备注 |
|---|---|---|
| 任何计算公式/字段口径 | `docs/data-dictionary.md` + `docs/api-spec.md` + CHANGELOG | 三方缺一不可，防止两处数字打架 |
| API 加/删端点或字段 | `docs/api-spec.md` + `api/README.md` + `scripts/smoke.sh` | 新字段要有冒烟断言 |
| 架构性选择（换技术方案） | `docs/adr.md` 加一行 + CHANGELOG 详述 | 含"否决了哪些替代方案、为什么" |
| 用户可见行为/文案 | `docs/手机客户端使用说明.md` + CHANGELOG | 用户手册是给家人看的 |
| 新增服务/目录 | `README.md` 结构树 + 本文第 0 节 | |
| 运维动作（新的启停/备份/排查步骤） | `docs/operations.md` + 尽量做成 `scripts/*.sh` | |

## 3. 代码约束

### API（Go）
- **只读**：对数据库只允许 SELECT。写操作属于越界。
- **参数化查询**：一律 `db.Query(sql, args...)`，禁止拼接用户输入进 SQL。
- **时间**：库存 UTC，聚合时 `AT TIME ZONE 'UTC' → AT TIME ZONE TZ`；出参带 Z。
- **坐标**：对外同时给 `lat/lng` 与 `map_lat/map_lng`，不擅自删其一。
- **高德**：参数错时返回 HTTP 200 + JSON body。处理响应必须嗅探 `buf[0]=='{'`，不能当二进制。
- **关联子查询**：PG 对含 `$n` 的关联子查询易报 `42P18`。聚合写成 CTE + JOIN。
- 新处理器：在 `handlers.go` 加函数 + `main.go` 注册路由 + `smoke.sh` 加一条断言。

### App（Kotlin/Compose）
- **固定 dp 容器慎用**：密度 2.5 的 1440×3200 小米 15S Pro 上，LargeTopAppBar 折叠区高达 270px+，页面顶会出现"异常空白"。新增卡片优先用 `weight` / `fillMaxWidth`，慎用固定 `size` 撑版面。
- **Insets 不重复计算**：`Scaffold` 与 `TopAppBar` / `LazyColumn` 的 `contentPadding` 都要算 statusBar，否则多一块空白（v0.5.2 的 bug 就是这个）。列表页优先固定 `PaddingValues`，仅在确实需要时用 `WindowInsets.systemBars.only(...)`。
- **字号固定容器**：App 内 fontScale 已封顶 1.15，不要绕过。
- **空值显示**：数据库无数据一律显示 `—`，不显示 `0` 或 `未记录`。
- **地图坐标**：只用 `map_lat/map_lng`（GCJ-02）配高德瓦片。
- **正则警告**：禁止用 Python/sed 正则做 Kotlin 的**结构化**修改（删函数、改括号配对）。这类操作曾破坏 5 个文件。逐行精确编辑，或从工作区镜像恢复。

## 4. 验证纪律

> ⚠️ 下面所有 `bash scripts/*.sh` 命令都**必须在 Git Bash 里执行**。本机 `bash` 指向 WSL 垫片且 WSL 未装 `/bin/bash`，在 PowerShell/CMD 里直接敲会报 `execvpe(/bin/bash) failed`。
> PowerShell 中可改用：`& "C:\Program Files\Git\bin\bash.exe" -c "cd /c/tm-ws && bash scripts/smoke.sh"`

1. **改 API → 必须 `bash scripts/smoke.sh` 全过**（12 项断言）。
2. **改 UI → 必须真机看截图**。编译过 ≠ 显示对。曾经的"地图修复"就是编译通过、代码正确，但真机因为瓦片源被默认覆盖而完全没生效（logcat `Using tile source: Mapnik` 才发现）。
3. **抓真实证据**：
   - UI：`adb shell screencap -p`
   - 运行时：`adb logcat -d | grep -i <关键词>`
   - 数据：`bash scripts/smoke.sh` 或直接 curl。
4. 不确定的先抓证据再改，不要"看着像"就改。

## 5. 版本与发版

- App 版本号在**仓库的** `app-android/app/build.gradle.kts`：`versionCode`（整数，递增）+ `versionName`（语义化）。
  （构建目录里的那份由同步脚本带过去，**不要**在那边改。）
- 发版元数据在 `ROOT/.env`：`APP_VERSION_CODE / APP_VERSION_NAME / APP_RELEASE_NOTES`，必须与 App 的 versionCode 一致，否则手机弹不出更新或版本号对不上。
- **发版顺序不可反**：先 `cp` APK 到 `ROOT/apk/teslamate-app.apk`，再改 `.env`，再 `docker compose up -d api`。反了会出现"提示新版本但下载到旧包"。
- `scripts/release.sh` 已把这套流程固化，按它来。
- 高德 Key/铭牌容量/API Token 都在 `.env`，**永不入库**（`.gitignore` 已保护；文档里也不要贴明文）。

## 6. Git 约定

- 首次提交已完成（43 文件，不含密钥/数据/构建产物）。
- 提交信息：`类型: 摘要` —— `feat` / `fix` / `docs` / `chore` / `refactor`。
- 涉及用户可见行为的提交，正文里指向 CHANGELOG 的版本号。
- `.env`、`*.bak.*`、`database/`、`backups/`、`apk/*.apk` 永不入库（`.gitignore` 已配）。

## 7. 已知环境限制

| 限制 | 事实 | 应对 |
|---|---|---|
| 直连 GitHub 不通 | 本机网络 | 走 `ghfast.top` 前缀：`git clone https://ghfast.top/https://github.com/...` |
| cmd 含中文路径的脚本 | 会失败（GBK/UTF-8） | 用 `C:\tm-ws` junction，或 `bash` + `cygpath` |
| 模拟器 headless 启动不稳 | SwiftShader Vulkan 在无头模式卡住 | 用 `-gpu off` 或 `-gpu host`；真机 USB 调试更可靠 |
| 高德账号部分子权限未开 | markers / paths.fillcolor 报 20003 | 控制台加"静态地图-标注"服务；未开时路线无起终点标记 |
