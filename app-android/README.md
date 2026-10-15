 # Android 客户端（爱车车况）

 TeslaMate 私有部署的手机查看端。Kotlin + Jetpack Compose 原生 Android 应用。

 ## 功能

 | Tab | 内容 |
 |---|---|
 | 车况 | 电量环图、续航、里程、充电中/行驶中动态卡片、当前位置（可展开地图）、车内外温度、15 秒自动刷新 |
 | 充电 | 按月分组历史（含汇总条：次数/电量/费用/快充占比）→ 单次详情（功率/SOC 曲线） |
 | 行程 | 按月分组历史（起止地点/里程/耗时/能耗）→ 单次详情（高德瓦片轨迹 + 起终点标记） |
 | 统计 | 周/月/年切换：里程柱图、充电量快慢充分堆叠图、行车用电图、电池容量趋势与健康度 |
 | 设置 | 服务器地址 + API 令牌 + 测试连接 + 多车选择（DataStore 存储） |

 ## 目录结构

 ```
 app/src/main/java/com/teslamate/app/
 ├── MainActivity.kt        入口
 ├── Api.kt                 API 客户端 + 全部数据模型（kotlinx-serialization）
 ├── SettingsStore.kt       DataStore 设置（地址/令牌/车辆）
 ├── Fmt.kt                 中文时间/数字格式化
 └── ui/
     ├── AppRoot.kt         主题（Material3 深浅色）+ 底部导航
     ├── Widgets.kt         复用组件（电量环/信息块/状态胶囊/错误横幅…）
     ├── Charts.kt          自绘图表（堆叠柱状/折线，Canvas 实现）
     ├── TrackMap.kt        osmdroid + 高德瓦片地图（GCJ-02，见下）
     ├── StatusScreen.kt    车况页
     ├── ChargesScreen.kt   充电页（列表+详情）
     ├── DrivesScreen.kt    行程页（列表+详情）
     ├── StatsScreen.kt     统计页
     └── SettingsScreen.kt  设置页
 ```
 顶层还有构建脚本：`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、`app/build.gradle.kts`。
 资源在 `app/src/main/res/`：启动图标与 Material3 主题。

 ## 坐标系约定（改动前必读）

 服务端 API 同时返回 `lat/lng`（WGS-84）与 `map_lat/map_lng`（GCJ-02，国内自动纠偏）。
 本 App 地图统一用 **map_lat/map_lng 配高德瓦片**（`ui/TrackMap.kt` 的 `AMapTiles`）。
 如需换谷歌/OSM 瓦片，必须同时改用 lat/lng，两者混用会有 ~500m 偏移。

 ## 构建

 前置：JDK 17、Android SDK（platform 35 / build-tools 35.0.0）。Windows 下参考主项目 `setup-android-sdk.sh`。

 **canonical 构建目录** = `C:\tm-app\teslamate-app`（为什么不在工作区构建，见主项目 `docs/architecture.md` §2.6）。
 本目录 `app-android/` 是源码镜像，不在此处执行 Gradle（避免 OneDrive + 中文路径上的 `app/build/` 文件锁）。

 ```powershell
 cd C:\tm-app\teslamate-app
 $env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot'
 $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
 $env:GRADLE_USER_HOME='C:\gradle\gradle-home'
 cmd /c "C:\gradle\gradle-8.10.2\bin\gradle.bat assembleDebug"
 # 产物: C:\tm-app\teslamate-app\app\build\outputs\apk\debug\app-debug.apk
 ```

 国内网络：`settings.gradle.kts` 已配阿里云 Maven 镜像；注意**不要**改回 Compose BOM 用法
 （阿里云镜像对 BOM 的 POM 代理不完整，会报 `tooling-preview` 无版本——详见主项目 CHANGELOG 2026-10-13）。

 ## 安装与使用

 - 安装：`adb install -r app-debug.apk`，或直接把 APK 拷到手机点击安装（允许"安装未知应用"）。
 - 首次配置与日常使用见 [docs/手机客户端使用说明.md](../docs/手机客户端使用说明.md)。

 ## 源码同步约定

 在 `C:\tm-app\teslamate-app` 改代码后，在 `cmd` 跑下面这条把源码镜像回本目录（`C:\tm-ws` 是主项目根的 junction，见主项目 `docs/operations.md`；它绕开 Windows + cmd.exe 对中文路径的解析坑）：

 ```cmd
 robocopy "C:\tm-app\teslamate-app" "C:\tm-ws\app-android" /E /XD build .gradle .kotlin /R:1 /W:1 /NFL /NDL
 ```

## 版本历史

### v0.5.x（数据与布局修正）
- **0.5.2（2026-10-15）**：修行程列表页顶部多余空白（`contentPadding` 误用 `WindowInsets.systemBars`，其 top inset 被重复计入）；顺带统一行程详情页底部 padding 为 120dp。
- **0.5.1（2026-10-15）**：充电页/行程页顶部汇总框改为「最近 30 天」统计（新增 `ui/RecentSummaryCard.kt`，与统计页「月」档同源同口径）。
- **0.5.0（2026-10-15）**：新增四轮胎压（含异常告警）、可用电量、海拔、充电损耗率、充电均温、充电桩类型；按用户要求移除充电费用字段（数据源本就全为 NULL）。

### v0.4.x（交互与顶栏重构）
- **0.4.0（2026-10-14）**：顶部「异常空白」彻底消除（`LargeTopAppBar` → 普通 `TopAppBar` 固定 64dp 不折叠）；启动默认进入「车况」；电量环数字居中；列表项地点 `maxLines` 1→2。
- **0.4.0 附带修复**：详情页底部被导航栏遮挡 → 改用自适应 insets。

### v0.3.x（地图重构）
- **0.3.0（2026-10-14）**：地图架构从 osmdroid 换成 **高德静态地图 API**（经服务端 `/api/v1/app/staticmap` 中转，Key 只存在服务端）；App 端改用 Coil 加载；删 osmdroid（-2.5 MB）。

### v0.2.x（界面与设备适配）
- **0.2.2（2026-10-14）**：按小米官方文档修 edge-to-edge（`enableEdgeToEdge()`）+ 字体缩放封顶 1.15（MIUI 大字号档会导致布局错乱）。
- **0.2.1（2026-10-14）**：修 APK 被浏览器当 zip 下载（alpine 无 mime 库，`.apk` 被嗅探成 `application/zip`；修法 `mime.AddExtensionType`）。
- **0.2.0（2026-10-14）**：界面全面改版（Telegram / Google Play 风格）：M3 完整配色、tonal 卡片、骨架屏加载、图表生长动画、Tab 切换动效。

### v0.1.x（首个可用版本）
- **0.1.4（2026-10-14）**：App 内一键更新（启动自动检查新版本，弹窗「立即更新」→ 系统下载 → 跳安装）。
- **0.1.3（2026-10-14）**：充电详情电压电流占位值识别（V<10 / A<1 视为未上报，显示「—」并加说明）。根因：非超充直流桩的 V/A 是特斯拉 API 的握手占位值。
- **0.1.2（2026-10-13）**：车况电量环修复、充电/行程详情改 LazyColumn 修地图白屏 + 信息块被吞；充电 +13 字段、行程 +13 字段。
- **0.1.1（2026-10-13）**：源码镜像回本目录。
- **0.1.0（2026-10-13）**：首个可用版本，五页全部完成，对应 API v1。
