 # 变更日志（CHANGELOG）

 > 记录规则：按日期倒序；每条含「做了什么 / 为什么 / 影响 / 回滚方式」。
 > 格式参考 Keep a Changelog，但这是一份部署+开发混合项目的日志。

---

## 2026-10-15（项目基建审计与文档体系补全）

> 交接负责人时做的全面基建审计。目标：任何人接手，不靠翻代码和猜，就能重建整个项目状态。

### P0：版本控制从零建立

**审计发现**：git 仓库**零提交**，19 项全部 untracked；无 `.gitignore`。
**风险**：误删误改无法回溯；若有人 `git add .`，`.env`（API_TOKEN/AMAP_KEY 明文）直接进版本历史 = 密钥永久泄漏；299MB `database/` + 189MB `backups/` 撑爆仓库。

**处理**：
1. 新建 `.gitignore`：排除密钥（`.env`/`teslamate.env`/`compose.yaml.bak.*`）、运行数据（`database/`/`grafana/data/`/`mosquitto/data|log/`/`backups/`/`import/`）、二进制（`apk/*.apk`/`*.log`）、外部克隆（`teslamate-chinese-dashboards/`）、工具沙箱（`.zcode/`/`.mimosa/`）、Android/Go 构建产物。
2. **提交前密钥扫描**（关键步骤）：逐个比对 `.env` 中真实值是否出现在暂存区。**首次扫描即抓到 2 处泄漏**——
   - `docs/CHANGELOG.md` 引用了真实 AMAP_KEY
   - `docs/手机客户端使用说明.md` 把真实 API_TOKEN 当「示例值」写进了手册
   已全部替换为占位描述，复扫干净后才提交。
   **教训**：文档里「顺手贴一下真实值」是密钥泄漏的经典路径。
3. 首次提交 `1ebbb42`：43 个文件（api 源码 + app-android 源码镜像 + 6 份文档 + compose + .gitignore）。

### P0：建立回归测试基线

**审计发现**：**零测试**。开发期间积累的 20+ 条手工 curl 验证全部只存在于对话记录里，随时可能丢失。

**处理**：新建 `scripts/smoke.sh`，固化 **12 项断言**（首次运行 12/12 全过）：
健康检查 · 鉴权拒绝无 token · 车辆列表 · status 含 tpms 字段 · charges 含 loss_percent · stats week/month/year 三档 · 电池铭牌口径校验（读 `.env` 断言 nominal 一致且健康度 ∈ (50,100]）· 静态图返回 PNG 且 >10KB · APK Content-Type 必须是 Android 包 · 自更新元数据。
配套 `scripts/release.sh` 已把发版与冒烟串联，「冒烟不过禁止发版」。

### P1：补齐缺失文档

| 新增 | 解决什么 |
|---|---|
| `docs/data-dictionary.md` | **数据口径单一事实源**。此前能耗/快充/电池容量公式散落三处易打架。现统一，并专章记录「占位值陷阱」（V/A=2V/0A 实为特斯拉握手占位、`<invalid>` 字符串、高德 200+JSON 错） |
| `docs/adr.md` | **16 条架构决策索引**，含「否决了哪些替代方案 + 为什么」。此前「为什么弃 osmdroid」只埋在 CHANGELOG 流水里 |
| `docs/CONTRIBUTING.md` | **开发约定**：改动流程、什么改动必须同步哪份文档、API/App 代码约束、验证纪律、发版顺序、Git 约定、环境限制 |
| `docs/README.md` | 文档索引——按「我现在要干什么」导航，不必通读 |
| `api/README.md` | 4 个 Go 文件的职责与改动频率、11 个端点速查、6 条设计约束 |
| `scripts/backup-db.sh` | 备份从文档文字变成可执行脚本，带失败检测（<10KB 自动删） |
| `scripts/release.sh` | 一键发版：构建 → 部署 APK → 同步源码 → 升 `.env` → 重启 API → 冒烟验证 |

### P1：重写 README 为完整项目说明

旧 README 只有目录树。新版包含：3 条命令快速上手、ASCII 架构图（标注「手机从不直连数据库」「Key 只存服务端」）、带图例的完整目录结构（含 🔒 勿提交标记）、「两个 Android 目录」辨析表、关键配置表（改完要做什么）、8 份文档索引、**「接手者的 30 分钟路径」**、已知限制。

### 文档分工固化（docs/README.md）

| 文档 | 性质 | 更新频率 |
|---|---|---|
| architecture.md | 说「为什么这么搭」 | 低 |
| data-dictionary.md | 口径单一事实源 | 改公式时必改 |
| api-spec.md | API 契约 | 加删改接口时 |
| adr.md | 决策索引 | 架构决策时 |
| operations.md / CONTRIBUTING.md | 说「怎么做」 | 加流程/约束时 |
| CHANGELOG.md | 变更流水 | 每次改动 |
| 手机客户端使用说明.md | 端用户手册 | 用户可见行为变化时 |

三条规矩：① 口径改动三方同步（dictionary + api-spec + CHANGELOG）；② 改完必跑 smoke.sh；③ 决策不埋在流水里。

### 仍待处理（非文档类，见 operations.md §5）

- `database/` 位于 OneDrive 同步目录，PGDATA 直接读写有损坏风险，建议迁出。
- `.env` 中 ChangeMe 弱密码 ×2（Postgres / Grafana）。
- Grafana 匿名 Admin 角色开放。
- 模拟器 headless 启动不稳（SwiftShader Vulkan），真机 USB 调试更可靠。

---

## 2026-10-14（v0.3.0 地图架构换为高德静态图 API）

### 修复：地图背景从空白 → 高德真底图
- **症状**：v0.2.2 及以前，行程详情页地图区域是"灰色网格+红色路线+Marker"，没有任何底图（路网/地名/公路），完全无法辨识地理位置。
- **根因诊断**（经 logcat + 直连 amap 验证）：
  1. 我们用的是 `osmdroid 6.1.20` + 高德 GCJ-02 瓦片源。
  2. osmdroid 6.x 在 Compose `AndroidView` 重建时会回退到默认 **OSM Mapnik 瓦片**——用户截图证实 logcat `OsmDroid: Using tile source: Mapnik`。
  3. OSM Mapnik 在中国被 GFW 屏蔽 → `HTTP response: Not Found` → 瓦片全 404 → 灰色网格。
  4. 我们之前在 `factory` 块里调 `setTileSource(AMapTiles)`，但 `update` 块没重设，被 osmdroid 默认 Mapnik 覆盖——所以**之前的"地图 bug 修复"实际是假修复**。
  5. 进一步：即便设了瓦片源，osmdroid 在小米 15S Pro（Android 16 / HyperOS 2）上 `Configuration.setUserAgentValue` 时机也有问题（日志里完全没见瓦片拉取请求）。
- **解决方案**：抛弃 osmdroid，改用**高德 Web 服务 API 的静态地图**：
  - 个人开发者免认证；[官方文档](https://lbs.amap.com/api/webservice/guide/api/staticmaps)
  - HTTP GET 返一张完整 PNG（底图+路线+标注），完全 server-side 合成
  - 服务端 `/api/v1/app/staticmap` 端点做 3 件事：① 接收 lng/lat 列表 ② 拼高德 URL（带 key）③ HTTP 反代高德 + 30min 进程内 LRU 缓存
  - **Key 永远不出服务端**（你给的 `你的高德Web服务Key（见 .env 的 AMAP_KEY）` 写在 `.env`，仅 api 容器读）
  - App 端用 Coil `SubcomposeAsyncImage` 直接加载 URL（**通过自定义 OkHttp 拦截器自动注入 Bearer Token**）
- **关键决策**：
  - **App 端先降采到 ≤ 100 点**——轨迹 1500 点会让 URL 30KB+ 多数 HTTP 客户端拒绝；服务端也有 200 点降采作兜底
  - **bbox 自动算 zoom**——按路线跨度的对数映射到高德 zoom 3~17
  - **修复高德 200 错误响应处理 bug**：高德返 `HTTP 200 + body 是 JSON 错`时，旧代码会把 JSON 当 PNG 写到缓存。修：在 `buf[0] == '{'` 时显式解析 status 字段返 502
- **限制**：这台高德账号"静态地图-markers"子服务未开通（加 markers 参数立刻 20003 UNKNOWN_ERROR），起终点无文字标记；起终点信息靠下方详情卡的"开始/结束时间/地点"承担。修法：在 [lbs.amap.com/dev/key/app](https://lbs.amap.com/dev/key/app) →「爱车车况」应用 →「添加服务」→ 勾「静态地图 - 标注」→ App 端 0 改动，地图自动出 A/B 标记
- **验证**：真机小米 15S Pro 行程详情页 → 完整高德底图（路网/地名/公路/学校/公园），红色路线沿碧海→西乡→合正汇一城→径贝小学清晰可见；APK 从 18MB 缩到 18MB（osmdroid 减 2.5MB + Coil 加 0.4MB，净减 2.1MB；加上 Coil 内存/磁盘缓存，运行时内存压力也更小）

### 顺带修复
- **详情页底部被 NavigationBar 挡**：`contentPadding` 用 `WindowInsets.systemBars.add(...)` 自适应，不留死硬 120dp
- **详情页顶部异常空白**：`LargeTopAppBar` → `MediumTopAppBar`（上一轮 v0.2.2 已做，本轮保留）

### 技术栈替换
| 组件 | 旧 | 新 | 差异 |
|---|---|---|---|
| 地图渲染 | osmdroid 6.1.20（2.5MB） | 删 | 国内不友好、Compose 集成脆弱 |
| 图像加载 | 无 | Coil 2.7.0 | Compose 原生、OkHttp 共享、自带缓存 |
| 瓦片源 | OSM Mapnik（GFW 屏蔽）| 高德静态图（个人 Key） | 中国大陆稳定可达 |
| 起终点标记 | osmdroid Marker | 暂无（账号无 markers 权限）| 加服务即可恢复 |

### CHANGELOG
- v0.3.0 / versionCode=8
- .env: `AMAP_KEY=你的高德Web服务Key（见 .env 的 AMAP_KEY）`
- 工作区镜像：app-android/ 已同步
- 真机验证：v0.3.0 在小米 15S Pro 上完整渲染（截图 `23_v030_drive_map_ok.png`）

---

## 2026-10-15（v0.5.2 行程列表顶部空白修复）

### 修复：行程页标题与列表之间多出一块空白
- **症状**：行程页「行程」标题与「最近 30 天」卡片之间约 100px 空白，充电页没有；用户反馈"看起来是显示问题"。
- **根因**：行程页 `LazyColumn` 的 `contentPadding` 用了
  `WindowInsets.systemBars.add(WindowInsets(...))`，而 **`systemBars` 包含 top inset（状态栏高度）**。
  页面上的 `TopAppBar` 已经消费了状态栏空间，列表又额外加了一次状态栏高度，于是多出一块空白。
  这是 v0.4.0 重做顶栏时的遗留——当时统一改成 TopAppBar，但行程页的 padding 写法没跟上。
- **排查范围**：逐一检查 5 个页面的 `contentPadding`，确认只有行程页用了 `systemBars`，其余（充电/统计/设置）都是固定 `PaddingValues`，不受影响。
- **修复**：行程列表页改为与充电页完全一致的 `PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)`。
- **顺带统一**：行程详情页底部 padding 从 `systemBars` 改为固定 `120dp`，与充电详情页一致（此前两页不一致）。
- **验证**：真机截图对比，标题到统计卡间距从 ~100px 变为标准 16dp；且因此多显示约半条行程记录。

---

## 2026-10-15（v0.5.0 / v0.5.1 数据口径矫正 + 列表页 30 天统计）

### 新增：五个用户挑选的字段
- **车况页**：可用电量 %、海拔 m、四轮胎压（2×2 网格）
- **充电详情**：充电损耗率、平均车外温度
- **额外收获（数据现成、零成本）**：
  - **胎压异常告警**：正常范围 2.4–2.9 bar（Model 3/Y 冷胎）；超范围或单胎比四胎中位数低 0.2 bar 以上时整格转红 + 标题变「建议检查」。这是 Tesla App 也没做但对爆胎预兆有实际价值的一环。
  - **充电桩类型**：GB_AC 国标交流 / GB_DC 国标直流 / Tesla 超充，可一眼看出是否自家桩。
- **按用户要求删除**：充电费用字段（`charging_processes.cost` 全库 65 条均为 NULL，本来就只显示「未记录」），「能量与费用」卡改名为「能量」。

### 修复：电池健康度的分母错了（重要数据口径 bug）
- **症状**：App 显示电池健康度 98%，明显虚高。
- **根因**：v0.4.0 及以前用「历史实测最大值」当分母。历史最大值本身是**衰减后的测量值**，会随时间漂移——车越老最大值越小，于是「当前 / 历史最大」这个比值永远接近 100%，健康度不可比较。
- **验证过的三个数字**：
  - 铭牌容量 **76.8 kWh**（用户提供的出厂标称值）
  - 车机上报满电标定续航 454.59 km × 车辆效率 0.146 = **66.37 kWh**（这是车机按当前衰减后的可用容量上报的，与铭牌差 15%，**同样不能当分母**）
  - API 实测当前满充容量 65.41 kWh
- **修正**：健康度 = 当前实测 / **铭牌容量** = 65.41 / 76.8 = **85.2%**（v0.4.0 显示 98%）
- **实现**：新增环境变量 `NOMINAL_BATTERY_KWH`（写在 `.env`），API `/cars/{id}/battery` 返回 `nominal_capacity_kwh` 新字段并用它作分母；未配置时回退到旧行为（历史最大值）并在代码注释里标明该口径不可信。换车只需改 `.env`，不改代码。

### 变更：充电页 / 行程页顶部小框改为「最近 30 天」统计
- **之前**：充电页顶部显示的是**全量汇总**（「共 58 次 · 充入 1535.2 kWh」），随数据库积累会越来越没参考价值；行程页**没有**顶部统计框。
- **现在**：两页顶部都改为调用 `/cars/{id}/stats?period=month`（服务端按 Asia/Shanghai 本地日界聚合最近 30 个自然日），显示：里程、充入电量、充电次数、行程次数、平均能耗，以及**快充占比 + 进度条**。
- **一致性**：与「统计」页的「月」档是同一份数据、同一个接口，保证两处数字不会打架。
- **实现**：新建 `ui/RecentSummaryCard.kt`，充电/行程两页共用；数据加载中显示骨架条，接口失败时回退到页面原有汇总（不让顶部空着）。

---

## 2026-10-14（v0.4.0 顶部栏重构 + 车况页可读性）

### 修复：顶部「异常空白」彻底消除
- **症状**：每个页面顶部有一大块纯白空白（density 2.5 的 1440×3200 屏上约 270px）。
- **三轮排查**（真机截图逐次确认）：
  1. `LargeTopAppBar`（152dp）→ 改 `MediumTopAppBar`（112dp），空白从 400px 缩到 270px，**仍不够**。
  2. 发现 `Scaffold(contentWindowInsets = WindowInsets.systemBars)` 与 TopAppBar 自带的 statusBars inset **双重计算**。
  3. 最终方案：**改用普通 `TopAppBar`（固定 64dp、不滚动折叠）**，与 Google Play / Telegram 列表页一致；同时 `contentWindowInsets` 只保留 bottom。
- **验证**：真机截图确认标题紧贴状态栏（y 从 400 → 180），与两个对标 App 的顶栏高度一致。

### 修复：启动默认进入「车况」而非「行程」
- 显式 `mutableStateOf(Tab.STATUS)`。车况是核心入口，不该让用户一打开就看到行程列表。

### 修复：电量环数字不居中 / 列表地点被截断
- `SocRing`：Box 从 `wrapContentSize` 改为固定 `size(168.dp)` + Column `verticalArrangement = Center`，「33%」精确居中。
- 列表项标题 `maxLines` 1 → 2，充电地点可显示到「…宝安区 深圳市 广东省」。

### 教训：Kotlin 禁用正则做结构化修改
- 本轮用 Python 正则批量改 5 个 Kotlin 文件时**破坏了源码结构**（括号配对被删除，编译失败）。
- 恢复方式：从工作区镜像（上一版编译通过的副本）拷回。
- **结论**：带括号语言的结构化修改必须用 AST 或逐行处理，正则只能用于单行字面替换。

---

## 2026-10-14（v0.2.2 修复小米 15S Pro 布局问题）

### 修复：edge-to-edge + 字体缩放封顶
- **症状**：小米 15S Pro（HyperOS 2 / Android 15）上"页面布局有问题""电量环只显示一半"等。
- **根因诊断**（查小米官方文档）：
  - [小米《Android 15 应用适配指南》](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1826) + [Android 官方 e2e 文档](https://developer.android.com/develop/ui/views/layout/edge-to-edge)：targetSdk 35 的应用在 Android 15（HyperOS 2 底层）上**被强制无边框显示**。我们 App 是 targetSdk 35 但没显式 `enableEdgeToEdge()`——状态栏/手势条与内容重叠是预期后果。
  - [小米《大字体适配说明》](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1632) 原文承认："当字体档位偏大时，可能会造成**布局错乱、重叠**等问题"；MIUI 字体大小改的是 fontScale，且为**非线性缩放**。固定 dp 容器（如 168dp 的电量环、30sp 的数值）在大档位下会被挤压。
- **做了什么**：
  1. `MainActivity.onCreate` 调 `enableEdgeToEdge()`（[官方推荐做法](https://developer.android.com/develop/ui/views/layout/edge-to-edge)），让 Material3 的 `TopAppBar` / `NavigationBar` 自行处理系统栏 insets。
  2. `AppRoot.kt` 用 `CompositionLocalProvider(LocalDensity provides cappedDensity)` 把 App 内 `fontScale` 封顶到 1.15——国内一线 App（微信/淘宝/抖音）的通行做法。保留大字号档的可读性增益，杜绝文字溢出/截断/数字与图标重叠。
- **没做**：彻底根除"大字体"会需要全面重写所有 fixed-dp 容器为 `weight` 或 `fillMaxWidth`——成本高、收益只在极端字号档上才显现。1.15 cap 是 Telegram/Play 的"既照顾视觉又保布局"平衡点。
- **验证**：本机 + Tailscale 双端拉 APK 返回 `Content-Type: application/vnd.android.package-archive` + 18,217,252 B；API `/app/latest` 返 code=6/notes 新文案。
- **回滚**：删 `MainActivity` 的 `enableEdgeToEdge()` 即可关掉强制 e2e；删 `AppRoot` 的 CompositionLocalProvider 块即恢复系统 fontScale。

---

## 2026-10-14（v0.2.1 热修：APK 下载被浏览器当 zip）

### 修复：手机浏览器下载 APK 变成 zip 压缩包
- **症状**：手机浏览器访问 `http://<tailscale IP>:8090/apk/teslamate-app.apk`，下载得到 zip 压缩包，无法安装。
- **根因**：APK 本质是 ZIP 容器（PK 文件头）；Go `http.FileServer` 在 alpine 容器内没有 mime 数据库（无 /etc/mime.types），对未知扩展名按内容嗅探，把 APK 标成 `Content-Type: application/zip`。浏览器严格按 Content-Type 决定保存方式 → 当压缩包存。
- **做了什么**：`api/main.go` 启动时加 `mime.AddExtensionType(".apk", "application/vnd.android.package-archive")`，一行根治；重建容器后本机与 Tailscale 两端响应头均已验证为 `application/vnd.android.package-archive`。
- **教训**：此前验证只查了 HTTP 200 与 Content-Length，漏了 Content-Type——恰好它才是浏览器行为的关键。今后新增静态文件服务，验证清单必须包含 Content-Type。
- **波及范围**：仅影响浏览器手动下载场景；App 内 DownloadManager 自动更新不受影响（它按 URL 文件名与 mime 提示处理）。
- **回滚**：删掉 `mime.AddExtensionType` 一行即可（不建议）。

---

## 2026-10-14（v0.2.0 界面全面改版：Telegram/Play 风格）

### 重构：全部 5 个页面 + 主题 + 动效体系
- **设计目标**（用户指定）：交互动画与显示风格对齐 Telegram / Google Play，全中文。
- **做了什么**：
  1. **新文件 `ui/Theme.kt`**：M3 完整色彩方案（浅色 Telegram 式白底 + 深色 OLED 近黑），层级用 `surfaceContainer*` 色调区分（无阴影），特斯拉红仅做点缀。
  2. **`Widgets.kt` 重构**：新增骨架屏 shimmer（`SkeletonBox`/`ListSkeleton`，Play 式加载态）、按压缩放（`pressScale` 0.97 回弹）、tonal `SectionCard`（圆角 20dp、surfaceContainerLow、零 elevation）、电量环数值变化扫入动画（800ms）。
  3. **`AppRoot.kt` 重构**：底部导航 `surfaceContainerLow` 底色 + pill 指示；Tab 切换 `AnimatedContent` 淡入+方向性轻滑动（Play 式内容优先过渡）；启用新主题。
  4. **`StatusScreen`**：`LargeTopAppBar` 大标题随滚动折叠；`PullToRefreshBox` 下拉刷新（material3.pulltorefresh 子包）；位置卡片展开地图用 `AnimatedVisibility` expand/fade；加载态骨架屏。
  5. **`ChargesScreen` / `DrivesScreen`**：列表页 LargeTopAppBar + `ListSkeleton`；列表项 pressScale + 快慢充胶囊；列表⇄详情用 `AnimatedContent` 推入/滑出（300ms slide+fade）；详情页 `TopAppBar` + 返回；行程详情顶部固定 260dp 地图（不随滚动消失）。
  6. **`StatsScreen`**：LargeTopAppBar + 分段按钮 + 骨架屏 + `animateItem`。
  7. **`SettingsScreen`**：LargeTopAppBar + 新增「关于」卡（当前版本/版本号/更新说明）。
  8. **`Charts.kt`**：柱状图从 0 生长（750ms FastOutSlowIn）；折线从左到右描画（clipRect 裁剪）；峰值标注在动画完成后出现。
- **编译修复**（3 个，都属 API 记忆问题）：`height` 漏 import（Charges/Stats）；`PullToRefreshBox` 在 `androidx.compose.material3.pulltorefresh` 子包不在根包。
- **版本**：versionCode 4→5，versionName 0.1.4→0.2.0；`.env` 同步升 `APP_VERSION_CODE=5`。
- **验证**：编译通过；`/api/v1/app/latest` 返 code=5/notes 新文案；`/apk/teslamate-app.apk` 200（18,217,252 B）；Tailscale 端到端下载 200。
- **回滚**：9 个 UI 文件回退 v0.1.4 版本即可；API 无改动。

### 说明：为什么手机上没收到 v0.1.4 弹窗
自动更新功能是 v0.1.4 才写进 App 的——手机上的旧版（v0.1.3 及以前）**没有检查更新的代码**，永远不会弹。v0.2.0 之前的一切版本都需要手动装最后一次；装上 v0.2.0 后，后续版本都会自动弹窗。

---

## 2026-10-14（v0.1.4 App 内一键更新）

### 新增：App 内一键更新
- **做了什么**：
  1. **API 端**：`api/models.go` 加 `AppLatest{version_code, version_name, apk_url, sha256, release_notes, mandatory}`；`api/handlers.go` 加 `handleAppLatest`（从 env 读 4 个 `APP_*` 变量，返 JSON）；`api/main.go` 加两个路由：
     - `GET /api/v1/app/latest`（鉴权）
     - `GET /apk/{file}`（**不鉴权**——`http.FileServer` 不支持自定义 header；靠 Tailscale 隧道加密保安全）
  2. **服务编排**：`compose.yaml` 给 `api` 加 `volumes: [- ./apk:/apk:ro]`（bind-mount 单一稳定文件名 `teslamate-app.apk`）和 5 个 `APP_*` 环境变量；`.env` 写入 v0.1.4 元数据。
  3. **App 端**：`app/build.gradle.kts` 启用 `buildConfig = true` + `versionCode=4` / `versionName="0.1.4"`；`AndroidManifest.xml` 加 `REQUEST_INSTALL_PACKAGES` 权限 + `<queries>` 块（Android 11+ 包可见性）；`Api.kt` 加 `AppLatest` 数据类与 `appLatest()` 方法。
  4. **新文件 `ui/UpdateDialog.kt`**：Material 3 `AlertDialog`（"立即更新" / "稍后" 两按钮，强制更新时只显示一个）；`Context.downloadAndInstall(url)` 扩展——`DownloadManager` enqueue + 系统下载 UI + `BroadcastReceiver(RECEIVER_NOT_EXPORTED)` 监听 `ACTION_DOWNLOAD_COMPLETE` 后跳系统安装；MIUI 兜底：`canRequestPackageInstalls()` 假时弹 Toast 引导用户到 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 设置页。
  5. **App 启动逻辑**：`TeslaMateApp()` 新增 `LaunchedEffect(settings?.configured)`：配置好就后台调 `api.appLatest()`，仅当 `version_code.toInt() > BuildConfig.VERSION_CODE` 时弹窗；"稍后"把 state 置 null（本次会话不再弹）；"立即更新"调 `ctx.downloadAndInstall(url)`。
- **为什么**：用户明确反馈"想自动检查更新，不要每次手动下载"。商业 App 标配体验。
- **决策**：
  - **API 返绝对 URL**：避免 App 端 URL 拼接陷阱（"API 返 IP ≠ serverUrl IP"那种诡异 bug）
  - **仅启动时查一次**：商业 App 标准做法；防打扰；网络抖动下次启动重试
  - **可推迟**：默认体验
  - **单一稳定文件名**：避免多版本管理（差分更新超出收益）
  - **不强制更新**：`mandatory=true` 字段已就位但 v0.1.4 关闭；下个版本若发现 API 旧版不兼容，再启用
- **影响**：用户从"每版本开链接 + 浏览器下载 + 允许安装"缩减到"开 App → 弹窗 → 点立即更新 → 系统下载 → 跳安装"四步。
- **回滚**：
  - API：`api/main.go` 删 `/apk/` 与 `/api/v1/app/latest` 两行；`compose.yaml` 删 volumes + 5 个环境变量；`.env` 删 `APP_*`。
  - App：`build.gradle.kts` 删 `buildConfig = true` 与回退 versionCode；`AppRoot.kt` 删 `LaunchedEffect` 块；删除 `UpdateDialog.kt`；Manifest 删权限与 queries。
- **关闭 9090**：旧的 `python -m http.server 9090` 已 kill（PID 36040），改用 API 容器内的 `http.FileServer` 暴露 APK，端到端在 8090 一个端口闭环。

### 已知小坑（不修，留给接手者）
- `ContextCompat.unregisterReceiver(ctx, this)` 是没这个 API 的，`unregisterReceiver` 本身就是 `Context` 的方法，已改用 `unregisterReceiver(this)`。如果未来要支持 IPC 跨进程接收器再回来。
- `DownloadManager` 把文件存到公共 `Downloads/teslamate-app.apk`，**会**被系统文件管理器列出；如要在更新后清理，需要在 `ACTION_PACKAGE_REPLACED` 时删——但这是 polish 项，与核心功能无关。

---

## 2026-10-13（v0.1.3 充电详情 V/A 数据修正）

### 修复：充电详情电压电流显示"错误值"
- **症状**：所有 65 号之类的国标直流快充充电详情里，电压显示 2V、电流 0A。
- **根因诊断**：直接查数据库 `charges` 表确认——这种桩 667 个采样点几乎全部是 `charger_voltage=2, charger_actual_current=0`，**但 `charger_power` 是真实的 80–98 kW**。是特斯拉 API 对非超充的直流快充桩只在握手阶段回一次 V/A，进入稳态充电后只更新 power。App 显示的"2V/0A"是数据库的原始占位值，不是计算错。
- **做了什么**：
  1. `api/handlers.go` 加 `sanitizeCharge()`：V<10 V 或 A<1 A 视为未上报，归 nil（不是"归 0"，这样 App 端的可空 Int 才会显示"—"而不是误导性的"0"）。
  2. `ChargesScreen.kt` 在「充电路况」卡里加一行文案："本充电桩（直流快充）未上报电压与电流，仅记录功率。"
  3. 真超充（id=61）数据不受影响：peakV=238V / peakA=8A 仍正常显示。
- **验证**：
  - 65 号（占位）：`peak_voltage=None avg_voltage=None peak_current=None avg_current=None last_voltage=None last_current=None`
  - 61 号（超充）：`peak_voltage=238 avg_voltage=230 peak_current=8 avg_current=8`
  - stats 回归：dc=183 km=870 不变。
- **影响**：用户对 App 的"专业感"信任 +1——不展示假数据比展示"是但看着像错"的数据重要。
- **回滚**：`api/handlers.go` 删 `sanitizeCharge` 调用与函数定义；`ChargesScreen.kt` 删 if 块的 else if 分支。

---

## 2026-10-13（v0.1.2 体验修复 + 信息扩展）

### 修复：App 详情页三个交互/显示 bug
- **做了什么**：
  1. **行程详情地图白屏**：详情页从 `verticalScroll` 嵌套改为 `Column{ 顶部固定 280dp 地图 + 下方 LazyColumn }`——地图不再因父级滚动态测量失效，osmdroid 瓦片能正常出图。
  2. **充电详情"下拉信息不完整 / 信息块被吞"**：从 `Column+verticalScroll+if(powers.isNotEmpty())` 改为 `LazyColumn`，每个 SectionCard 独立 item，幂等响应数据。
  3. **车况页电量环只能显示一半**：`SocRing` 内部 Box 加 `wrapContentSize`，Canvas 改用固定 `size(160.dp)`，不再依赖父级 Modifier 强占空间，环图与右侧 BigStat 列可共存。
- **为什么**：用户真机体验反馈，且功能上影响核心使用（"看不到完整数据"=信息抓取失效）。
- **影响**：v0.1.2 视觉与交互都重做了两个详情页，所有显示字段都需要重新测。
- **回滚**：4 个改动的文件（Api.kt / Widgets.kt / ChargesScreen.kt / DrivesScreen.kt）回退到 v0.1.1 即可；UI 行为可滚回前版但 SQL 新字段会让 Charge/Drive 解析到更多字段，无破坏性。

### 扩展：充电详情 +12 字段，行程详情 +10 字段
- **做了什么**：
  - API `sqlCharges` 用 CTE（`ch_agg`）聚合每个充电过程的 peak/avg 功率、电压、电流及最后采样值；新增 `geofence_name/start_range_km/end_range_km/start_outside_temp/end_outside_temp/start_indoor_temp/end_indoor_temp/peak_power_kw/avg_power_kw/peak_voltage/avg_voltage/peak_current/avg_current` 13 字段。
  - API `sqlDrives` 用 CTE（`drive_sc`）判断行程中是否经过超级充电；新增 `avg_speed_kph/ascent_m/descent_m/inside_temp_avg/power_max_kw/power_min_kw/start_rated_range_km/end_rated_range_km/start_ideal_range_km/end_ideal_range_km/start_geofence/end_geofence/has_supercharger` 13 字段。
  - App 端 `Charge` / `Drive` 数据模型同步加字段；详情页改用 5 张分主题 SectionCard（时间/能量/充电路况/温度 之于充电；时间/能耗/车速与功率/地形/地点 之于行程）。
  - 充电详情新增「电费单价」即时计算：`cost / energy_added` 元/kWh。
  - 行程详情新增「按里程算能耗」：`energy_kwh * 1000 / odometer差`。
- **为什么**：用户明确反馈"App 开发目的就是方便手机上获取更多数据"——补齐与 Grafana 仪表盘对齐的字段。
- **影响**：API JSON 体积小幅增长（充电每条约 600B → 1KB，行程每条约 400B → 900B），流量无感；功能实质增益。
- **回滚**：API 可在 `api/queries.go` 把 `ch_agg` CTE 注释掉并回退到原 EXISTS（注意 EXISTS 在该子查询里曾触发 42P18，所以回退要完整改回 v0.1.1 的 EXISTS 写法）；App 端只要在 `Api.kt` 数据模型里删掉新字段即可——但要保证 SQL 不返这些字段，否则 kotlinx-serialization 报 `missing field` 错。

### 关键决策：CTE 替换 correlated subquery
- **为什么**：v0.1.2 第一版尝试给 sqlCharges 加 5 个 `(SELECT max(...) FROM charges WHERE charging_process_id = cp.id)` 类子查询，PG 报 `42P18 could not determine data type of parameter $1`（correlated subquery 在参数 $1 未确定时不能正确推断其类型）。改用 CTE `ch_agg` 一次性按 charging_process_id 聚合，5 列 JOIN 进来，问题解决。**教训**：当 SQL 有大量 correlated subquery 且参数来自 WHERE，PG 优化器会卡在参数类型推断上，CTE 总是稳的。
- **影响**：API 性能略好（CTE 一次聚合代替每行 5 次子查询）。
- **回滚**：用 v0.1.1 的 EXISTS 写法（要重做 5 个字段），但性能/可读性都不如 CTE。

### 部署与验证
- `docker compose build api && up -d api`（API 端约 30 秒冷启动）。
- `gradle assembleDebug`（App 端约 9 秒，缓存命中）。
- APK：18.23 MB，已放 `C:\tm-app\teslamate-app-0.1.2.apk`，并通过 `python -m http.server 9090`（cwd=`C:\tm-app`）暴露在 Tailscale 内网 `http://<tailscale IP>:9090/teslamate-app-0.1.2.apk`，用户可直接在手机浏览器打开下载。
- 回归：charges/drives/stats 三个端点用真实数据冒烟通过，stats dc_kwh 仍=charge_kwh（围栏"supercharger" ILIKE 命中，符合预期）。
- 工作区镜像同步：`robocopy C:\tm-app\teslamate-app\app\src\main C:\Users\<用户名>\OneDrive\文档\teslamate\app-android\app\src\main /E /XD build .gradle .kotlin` 验证 4 个改动的文件 size 一致。

---

## 2026-10-13（晚间收尾）

### 收尾：源码镜像回工作区 + 用户手册 + Windows 中文路径应对
- **做了什么**：把 `C:\tm-app\teslamate-app/` 下 21 个源文件（13 .kt + AndroidManifest + 3 res XML + 4 Gradle 文件）通过 `robocopy /E /XD build .gradle .kotlin` 镜像到工作区 `app-android/`，**不含 build 产物**。`C:\tm-app\teslamate-app` 仍是 canonical 构建目录。
- **为什么**：之前 CHANGELOG 已承诺「构建成功后源码副本同步回工作区」，但实际只有 README 一份，文档与现实脱节；其他接手者以工作区为真值会看不到任何 App 代码。
- **影响**：工作区状态与文档口径一致；接手者在 `app-android/` 下能读到完整源码。
- **回滚**：`rmdir C:\tm-ws` 撤销 junction（不影响 OneDrive 原目录）；`rmdir /s /q C:\tm-ws\app-android\app` 与 `*.kts` / `*.properties` 可恢复为单 README 目录。

 ### cmd.exe 中文路径坑（一次记录）
 - **症状**：写带中文路径（如 `OneDrive\文档\…`）的 `.cmd` 脚本时，脚本里的 `文档` 被 cmd.exe 解释成分隔符 + 命令并触发 `'xxx' is not recognized`，整个脚本崩；即便首行加 `chcp 65001 >nul` 也不顶用——码页切换发生在脚本解析完成之后。
 - **变通方案**：在 `C:\tm-ws` 建 junction 指向项目根目录，所有脚本命令改走 `C:\tm-ws`。junction 是 Windows 目录重定向，零数据迁移，删除/移动都不影响原路径。
 - **命令**：`mklink /J C:\tm-ws "<项目完整路径含中文>"`；撤销 `rmdir C:\tm-ws`。
 - **教训**：以后涉及中文路径的 Windows 自动化，直接放 junction 后面的 ASCII 短名；不要在 `.cmd` 里绕 `chcp`。

 ### 用户手册（`docs/手机客户端使用说明.md`）
 - **给谁看**：项目主人自己 / 家人，不会敲命令也能照做。
 - **覆盖**：安装（USB / 网盘 / Tailscale）、首次配置（地址 + Token + 选车）、4 Tab 用途、Tailscale 装机、常见问题对照表、升级、卸载。
 - **不覆盖**：开发 / 调试 / Docker / 数据库；那是 `operations.md` 的范围。

 ### `app-android/README.md` 重写
 - 明确标注「canonical 构建目录 = `C:\tm-app\teslamate-app`」「本目录只做源码镜像」并附变更原因指针（架构文档 §2.6）。
 - 新增「源码同步约定」小节，给接手者一条命令（robocopy + junction）而不是再讲一遍 Python。
 - 版本号 → 0.1.1；引用用户手册。

 ## 2026-10-13（白天）

 ### 新增：项目文档体系
 - 建立 `README.md`（项目总览）、`docs/architecture.md`、`docs/api-spec.md`、`docs/operations.md`、本文件。
 - 目的：保证项目交接时任何人可以完整理解设计意图与操作方式。

 ### 新增：Android 客户端（v0.1.0）代码完成
 - 位置：构建目录 `C:\tm-app\teslamate-app`（源码副本同步至工作区 `app-android/`，详见下方「晚间收尾」）。
 - 技术：Kotlin 2.0.21 + Jetpack Compose（BOM 2024.12.01）+ Material3，OkHttp + kotlinx-serialization，osmdroid 6.1.20（高德瓦片），DataStore 设置存储。
 - 功能：车况（15s 自刷新/充电中卡片/位置+内嵌地图）、充电列表+单次曲线、行程列表+轨迹地图、统计（周/月/年+电池健康）、设置（地址/令牌/测试连接/多车选择）。
 - 关键决策：
   - 地图用高德栅格瓦片（无需 API Key），坐标用服务端已纠偏的 GCJ-02（`map_lat/map_lng`），与 Grafana 仪表盘同源同口径。
   - `android:usesCleartextTraffic=true`：因为 API 是家庭内网/Tailscale 的明文 HTTP（Tailscale 隧道本身加密）。
   - 图表全部自绘 Canvas（BarChart/LineChart），不引重型图表库。
 - 工具链：Gradle 8.10.2（C:\gradle，腾讯镜像分发）、AGP 8.7.3，依赖走阿里云 Maven 镜像，`GRADLE_USER_HOME=C:\gradle\gradle-home`（规避中文用户名路径）。
 - 状态：代码完成，构建成功（`assembleDebug` → `app-debug.apk` 18.17 MB，13 .kt + AndroidManifest + 3 res XML 全部编译通过）；**模拟器/真机验证待办**。
 - 模拟器自测尝试（2026-10-13 晚，未成功）：`emulator -avd teslamate_test -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect` 启动后约 90 秒内 `adb devices` 一直空，`sys.boot_completed` 未触发；进程常驻但不前进；WHPX 已 OK、SwiftShader Vulkan pipe 在 headless 下可能死在 `vk_swiftshader_icd` 加载。下次接手者建议改用 `-gpu off`（纯软件）或 `-gpu host`（宿主机 GPU），避免 `swiftshader_indirect`。


 ## 2026-10-12

 ### 新增：手机 API 服务（api/，Docker 化）
 - compose 新增第 5 个服务 `api`：Go 1.23 + pgx v5，只读查询 PostgreSQL，Bearer Token 鉴权（`API_TOKEN` 在 `.env`，48 位随机 hex）。
 - 端点 9 个：cars / status / drives / track / charges / curve / stats(周月年) / battery / healthz。详见 [api-spec.md](api-spec.md)。
 - 关键实现口径（与 Grafana 仪表盘对齐）：
   - 能耗 = 行驶前后 rated 续航差 × `cars.efficiency`（0.146 kWh/km）；
   - 直流快充判定 = `charger_phases` 为空且 `fast_charger_type != 'ACSingleWireCAN'`（同官方超充统计 SQL）；
   - 电池容量 = `rated_battery_range_km × efficiency × 100 / usable_battery_level`，仅取充电至 ≥90% 的会话；
   - 坐标出参同时给 WGS-84（lat/lng）与 GCJ-02（map_lat/map_lng，国内自动纠偏，复用中文仪表盘安装的 `wgs84_to_gcj02_*` SQL 函数）；
   - 位置命名 = 300m 内最近 geofence/地址（Haversine，acos 参数做了 [-1,1] 钳制防浮点域错误）。
 - 冒烟验证：全部端点用真实数据通过；周统计自洽（315.8 kWh ÷ 1794.7 km = 176 Wh/km）。
 - 期间修复 3 个自研 bug（复盘记录）：① 重写时丢失全部数据模型 → 补 models.go；② stats 未用参数 `$3` 被 PG 拒绝 → 删参重编号；③ 汇总行 `kwh_used` 漏赋值。

 ### 补救：数据库基线备份
 - `backups/db-baseline-20261012.sql`（pg_dump，189 MB）。
 - 背景：10-11 安装中文仪表盘 SQL 前未先备份（脚本幂等、回算改动 0 行，实际无损，但流程缺失）。以此为训：今后任何动库操作先 `pg_dump`。

 ### 环境：Android 构建工具链安装
 - Temurin JDK 17（winget）、Android SDK（platform-tools / platforms;android-35 / build-tools;35.0.0 / emulator / 系统镜像 default x86_64 API35，位于 `%LOCALAPPDATA%\Android\Sdk`）、AVD `teslamate_test`。
 - 安装脚本 `setup-android-sdk.sh` 幂等可重跑；首次运行因 Git Bash 路径转换问题失败，修复后成功（教训：`cmd //c` 调 .bat 必须传 Windows 风格路径）。

 ## 2026-10-11

 ### 变更：Grafana 切换中文仪表盘（官方英文 → 中文 v1.11.0）
 - 做了什么：
   1. compose 中 `teslamate/grafana:latest` → `ghcr.io/wjsall/teslamate-chinese-dashboards:latest`（v1.11.0，digest 与 git tag 互相验证）；
   2. 移除 `./grafana/dashboards*` 三个挂载（本地英文 JSON 会遮蔽镜像内置中文仪表盘——这是该镜像的已知坑 issue #20/#21 的挂载变体）；旧英文 JSON 保留在原目录作存档；
   3. 删除无效环境变量 `GF_LANG`/`GF_DEFAULT_LANGUAGE`（Grafana 不识别；镜像内置 `GF_USERS_DEFAULT_LANGUAGE=zh-Hans`）；
   4. 安装 4 组 SQL（坐标纠偏/单位换算/分时电价/性能索引），`teslamate_cn_extension_meta` 记录 `sql_revision=14`，与镜像 `/opt/teslamate-cn/versions.env` 一致；
   5. 分时电价回算（65 笔全跳过——当时未配置电价规则）；清理空的 Reports 文件夹。
 - 为什么：用户要求中文仪表盘；选镜像替换方案（方法四）是因为它自带 volkovlabs-form-panel 插件（分时电价表单需要）和配套 provisioning。
 - 影响：49 个仪表盘全中文；TeslaMate/数据库/MQTT 容器未动，数据零丢失；volkovlabs 插件位于 `/opt/grafana-plugins`（volume 外），不会被 `./grafana/data` 挂载遮蔽。
 - 回滚：`cp compose.yaml.bak.20261012-000215 compose.yaml && docker compose up -d grafana`（SQL 函数对官方版无害，无需清理）。
 - 验证：API 数了 49 个中文标题仪表盘；浏览器实测首页/概览/分时电价三页渲染正常。

 ### 环境事实（长期有效）
 - 本机直连 GitHub 全线超时；可用代理 `https://ghfast.top/`（前缀拼接）。`ghcr.io`、Docker Hub 可直连（Docker Desktop 配了 USTC 镜像源，偶尔 EOF 重试即可）。
 - 项目根是 git 仓库但从未 commit；所有敏感文件（.env 等）处于未跟踪状态。
