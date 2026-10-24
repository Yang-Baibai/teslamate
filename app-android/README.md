 # Android 客户端（爱车车况）

 TeslaMate 私有部署的手机查看端。Kotlin + Jetpack Compose 原生 Android 应用。

 ## 功能

 | Tab | 内容 |
 |---|---|
 | 车况 | 电量环图、**真实可达续航**（按实测 Wh/km 折算，样本不足时回落标定续航）、里程表、充电中/行驶中动态卡片、当前位置（可展开地图）、车内外温度、海拔、**四轮胎压俯视图**（异常轮标红）、15 秒自动刷新。**首屏零滚动**：位置与空调/车内温度提到电量环之后，一屏就能看到「电量 + 续航 + 位置 + 空调 + 车内温度」 |
 | 充电 | 按月分组历史（含汇总条：次数/电量/快充占比）→ 单次详情（充电功率与电量**折线**，真实时间轴） |
 | 行程 | 按月分组历史（起止地点/里程/耗时/能耗）→ 单次详情（高德静态图轨迹 + **功率剖面图**：净功率随累计里程变化，正值耗电 / 负值能量回收） |
 | 统计 | 周/月/年切换：里程柱图、充电量快慢充分堆叠图、行车用电图、电池容量趋势与健康度（带铭牌容量基准线） |
 | 设置 | 服务器地址 + API 令牌 + 测试连接 + 多车选择（DataStore 存储） |

 ## 目录结构

 ```
 app/src/main/java/com/teslamate/app/
 ├── MainActivity.kt        入口
 ├── TeslaMateApp.kt        Application：初始化 Coil ImageLoader（注入 Bearer 拦截器）
 ├── Api.kt                 API 客户端 + 全部数据模型（kotlinx-serialization）
 ├── SettingsStore.kt       DataStore 设置（地址/令牌/车辆）
 ├── Fmt.kt                 中文时间/数字格式化（含 relTime 相对时间、firstSegment 地址首段）
 ├── ConsumptionModel.kt    实测 Wh/km → 真实可达续航（按车速分桶取中位数）
 ├── Haptics.kt             触感体系（振动波形控幅）
 └── ui/
     ├── AppRoot.kt         主题 + 底部导航（材质分层 / 毛玻璃 / 透明顶栏）
     ├── Theme.kt           配色与材质 token（cardSurface / cardInset / chartTrack …）
     ├── Widgets.kt         复用组件（电量环/能量条/俯视车/信息块/状态胶囊/错误横幅…）
     ├── Charts.kt          自绘图表（堆叠柱状/折线/功率剖面，Canvas 实现）
    ├── PowerProfile.kt     行程功率剖面（哈弗森算累计里程 + 1 km 分箱取平均）
    ├── ChargeStats.kt      充电站历史统计（典型 kW + 充到 80% 耗时 + 与中位对比）
     ├── TrackMap.kt        高德静态图（经服务端 /api/v1/app/staticmap 中转，Coil 加载）
     ├── StatusScreen.kt    车况页
     ├── ChargesScreen.kt   充电页（列表+详情）
     ├── DrivesScreen.kt    行程页（列表+详情）
     ├── StatsScreen.kt     统计页
     ├── SettingsScreen.kt  设置页
     ├── StatusStore.kt     App 级车况缓存（切 Tab 不闪骨架屏）
     ├── RecentSummaryCard.kt  充电/行程页顶部「近 30 天」汇总卡
     ├── UpdateDialog.kt    启动更新弹窗
     └── PushTransition.kt  页面切换过渡
 ```
 顶层还有构建脚本：`build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、`app/build.gradle.kts`。
 资源在 `app/src/main/res/`：启动图标与 Material3 主题。

 ## 坐标系约定（改动前必读）

 服务端 API 同时返回 `lat/lng`（WGS-84）与 `map_lat/map_lng`（GCJ-02，国内自动纠偏）。
 本 App 地图统一用 **map_lat/map_lng 配高德静态图**：App 拼 URL → 自己的服务端
 `/api/v1/app/staticmap` 中转（Key 只存在服务端）→ Coil 加载返回的 PNG。
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

### v0.8.x（UX 迭代第 2~4 轮）
- **0.8.4（2026-10-24）**：接入 **Tesla Fleet API**（只读），车况页新增**锁车状态**。已上锁 / 未上锁 / 未知三态严格区分 —— 未知绝不冒充已锁；未上锁用开锁图标 + 琥珀色（图标与文字同时变化，色觉障碍也读得出）。锁车是车停着不动时唯一还会变化的信息（电量/续航/温度/胎压在停车时全部冻结）。新增 `scripts/tesla-authorize.ps1`（中国区一次性授权，贴码模式不占端口）、`api/tesla.go`（2 分钟轮询，refresh_token 一次性、3 个月有效，服务端自动轮换并原子写回 `.env`）。**已知边界**：车深度休眠时 Fleet API 返回 408，锁车状态读不到，此时显示「未知」。
- **0.8.3（2026-10-24）**：行程详情「地点与归属」新增「这次充电」—— 客户端用里程关联把行程与充电接上（|充电起始里程 − 行程结束里程| < 2 km；实测 17 次长途充电全部唯一命中、误差 ≤ 1 km），给出地点、SOC 变化、时长与功率。行程列表的里程数字与充电列表的费用数字从品牌红改为 onSurface（红色只表示告警）。
- **0.8.2（2026-10-24）**：第 4 轮 UX 迭代。**充电站历史**：充电列表每行新增「该站历史 N kW · 充到 80% 约 M 分钟（K 次）」，充电详情可对比本次与该站中位水平，新增 `ui/ChargeStats.kt`。峰值功率没有决策价值（国标直流桩典型 50~110 kW，峰值只出现在起步那几分钟），真正决定「进不进这个服务区」的是这个站**历史实测**的典型水平与等待时间 —— 这些数此前只躺在数据库里；电池容量由每次充电的「充入 kWh ÷ ΔSOC%」反推取中位数。逐条背景与回滚方式见 `docs/CHANGELOG.md` v0.8.2 段。
- **0.8.1（2026-10-24）**：第 3 轮 UX 迭代。**安全**：地图加载失败不再输出请求 URL（那个 URL 里含车辆实时经纬度与内网服务器地址，截图/录屏/读屏朗读就等于泄露位置）。**无障碍**：fontScale 封顶 1.15 → 1.30（1.15 把系统大字体档全部截断，违反 WCAG 1.4.4）、`SocRing` 圆环随字号缩放、`KV` 的值 `maxLines` 2 → 3、新增 `ScreenTitle` 给页面标题加 `heading()` 语义（TalkBack 可按标题跳转）。**功能**：行程详情新增「功率剖面」图（净功率 kW，正值耗电蓝 / 负值回收绿；X 轴是**累计里程**不是时间；里程用哈弗森公式在原始 GPS 点上算；按 1 km 分箱取平均），新增 `ui/PowerProfile.kt`。**首屏重排**：位置与空调/车内温度提到电量环之后，车况页零滚动即可看到「电量 + 续航 + 位置 + 空调 + 车内温度」（信息一点没删，只是把高频答案提前）。**图表/地图**：柱图空槽轨道降 alpha、峰值标注从图上移除、Y 轴刻度改用真实数据最大值；地图 `contentDescription` 按场景区分（单点不再念「行程轨迹地图」）、加载状态加 `liveRegion`、12dp 圆角、深色模式叠 45% 暗色蒙版。逐条背景与回滚方式见 `docs/CHANGELOG.md` v0.8.1 段。
- **0.8.0（2026-10-24）**：卡片材质分层真正落地（新增 `cardSurface` / `cardInset` / `cardStroke` / `cardStrokeEnd` 四个 token，卡片与页底从 1.00:1 变为可辨分层；描边改读 token，卡片阴影去掉红色光晕）；图表接入真实时间轴，补 Y 轴刻度 / 单位 / 网格线，电池健康曲线加铭牌容量基准线；俯视车重画、电量环刻度修 bug（统一 10 格 / 27°）；车况页改显**真实可达续航**（新增 `ConsumptionModel.kt`，按车速分桶的实测 Wh/km 折算）；车况数据上提为 App 级 `StatusStore`，切 Tab 不再闪骨架屏；Canvas 图形补 `contentDescription`。逐条背景与回滚方式见 `docs/CHANGELOG.md` v0.8.0 段。

### v0.7.x（UX 迭代第 1 轮 + 车况提醒接入 Hermes）
- **0.7.0（2026-10-24）**：第 1 轮 UX 迭代（7 位评审员驱动）：iOS 排版尺度、深浅色重做、能量条 / 俯视车 / 电量环等视觉母题、新增 `Haptics.kt` 触感体系、修 4 个确凿 bug；服务端稀疏列回填（续航 / 胎压 / 温度 / 海拔不再为空）；App 退回纯查看（删除本地通知），胎压与数据新鲜度判定收进服务端 `api/alerts.go`。

### v0.6.x（后台轮询与阈值修正）
- **0.6.0（2026-10-16）**：胎压阈值 2.4–2.9 → 2.5–3.3（误报率 63.03% → 1.62%）；15 秒轮询跟随界面生命周期（后台停止、回前台立即刷）；网络错误翻译成中文（401 / 超时 / 连不上等）。

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
- **0.2.2（2026-10-14）**：按小米官方文档修 edge-to-edge（`enableEdgeToEdge()`）+ 字体缩放封顶 1.15（MIUI 大字号档会导致布局错乱）。（**v0.8.1 把封顶提到 1.30**：1.15 把用户绝大多数系统大字体档全部截断，违反 WCAG 1.4.4。）
- **0.2.1（2026-10-14）**：修 APK 被浏览器当 zip 下载（alpine 无 mime 库，`.apk` 被嗅探成 `application/zip`；修法 `mime.AddExtensionType`）。
- **0.2.0（2026-10-14）**：界面全面改版（Telegram / Google Play 风格）：M3 完整配色、tonal 卡片、骨架屏加载、图表生长动画、Tab 切换动效。

### v0.1.x（首个可用版本）
- **0.1.4（2026-10-14）**：App 内一键更新（启动自动检查新版本，弹窗「立即更新」→ 系统下载 → 跳安装）。
- **0.1.3（2026-10-14）**：充电详情电压电流占位值识别（V<10 / A<1 视为未上报，显示「—」并加说明）。根因：非超充直流桩的 V/A 是特斯拉 API 的握手占位值。
- **0.1.2（2026-10-13）**：车况电量环修复、充电/行程详情改 LazyColumn 修地图白屏 + 信息块被吞；充电 +13 字段、行程 +13 字段。
- **0.1.1（2026-10-13）**：源码镜像回本目录。
- **0.1.0（2026-10-13）**：首个可用版本，五页全部完成，对应 API v1。
