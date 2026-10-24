 # 变更日志（CHANGELOG）

 > 记录规则：按日期倒序；每条含「做了什么 / 为什么 / 影响 / 回滚方式」。
 > 格式参考 Keep a Changelog，但这是一份部署+开发混合项目的日志。

---

## 2026-10-24（v0.8.2：UX 迭代第 4 轮 —— 充电站历史）

> 本轮回应长途自驾党评审的一条点名：**决定「进不进这个服务区」的信息，App 里根本没有。**
> 改动只落在 Android 客户端（`app-android/`，versionCode 16 → 17，新增 `ui/ChargeStats.kt`）；
> **本轮未改 API 契约** —— 站点历史由客户端在已返回的充电记录上算出，
> 服务端 `clampLimit` 上限本就是 200。

### 背景

现有充电字段全是**单次**视角：峰值功率、平均功率。但这两个数都不足以支撑
「这个服务区值不值得进」这个决策。

| 问题 | 实测表现 | 根因 |
|---|---|---|
| **决策信息缺失** | 充电列表只有**单次**充电的峰值 / 平均功率，看不出「这个站到底快不快」 | 现有字段全是单次会话统计，没有任何**按站聚合**的历史口径 |
| **峰值没有决策价值** | 22,850 个采样点里峰值 >100 kW 只占 **4.1%**、>50 kW 占 **48.6%**；实测峰值 152 kW | 国标直流桩典型就是 50~110 kW，峰值只出现在起步那几分钟 —— 拿峰值当决策依据，等于被一个瞬时值牵着走 |
| **等待时间不可知** | 26 场「≤50% 充到 ≥70%」的充电，充到 80% 的**中位耗时 49 分钟、P90 86 分钟** | 这两个数只躺在数据库里，App 完全没用上 |
| **样本窗口太窄** | 充电记录只拉最近 **100** 条 | 站点历史要跨场次凑样本，100 条凑不出稳定的中位数 |

### 改动 1：新增充电站历史统计（新增 `ui/ChargeStats.kt`）

- **充电列表每行新增一行灰字**：「该站历史 55 kW · 充到 80% 约 48 分钟（7 次）」。
  样本 < 2 次不显示 —— 一次充电说明不了「这个站」的常态。
- **充电详情「充电路况」卡顶部**新增该站历史摘要 + 「本次：比该站中位快 12%」这类对比
  （|差值| ≤ 8% 判为「与该站中位水平持平」）。
- **典型功率取 `avg_power_kw` 的中位数**（不是均值）：单次充电受起始电量、桩故障、
  排队影响很大，中位数抗离群。
- **电池容量由「充入 kWh ÷ ΔSOC% × 100」反推取中位数**（TeslaMate 不直接提供），
  据此折算「充到 80% 要多久」；只统计**起始 SOC ≤ 50%** 的场次 —— 起点越低，
  折算误差越小。
- 按**地点**聚合，地点取完整地址的**第一段**（`Fmt.kt` 的 `firstSegment()`），
  与列表显示口径一致 —— 否则同一个桩会因为邮编地址不同被拆成两个「站」。
- **充电记录拉取上限 100 → 200**（服务端 `clampLimit` 上限本来就是 200），让站点样本更全。

### 改动 2：修一个量纲 bug —— 容量反推的合理区间

- 容量反推的合理区间最初误写成 `0.3..1.2`（那是一「kWh per 1% SOC」的**量纲**），
  但代码算出来的是**整包容量 kWh** —— 区间写错导致**全部样本被滤掉**，
  `perPercent` 为空 → 函数直接返回空 Map → **站点历史永远不显示**。
  改为 `30.0..120.0` 后正常。

### 影响

- 功能：充电列表与详情首次出现**按站聚合**的历史表现 —— 典型 kW、充到 80% 典型耗时、
  以及「本次比该站中位快 / 慢多少」。
- 决策口径：从「看单次峰值」变成「看这个站的历史典型水平」；峰值仍留在详情里，
  但不再当决策依据。
- 样本覆盖：充电记录窗口 100 → 200 条，同一站点的历史样本更全。
- 本轮**未改服务端**：`clampLimit` 上限本就是 200，App 侧把 `limit` 从 100 提到 200 即可；
  `Api.kt` 无模型变更，API 契约不变，旧版 App 与新版服务端仍互相兼容。

### 回滚

- App 侧：`git revert 271d662` 后重新打包即可，不依赖服务端（改动只读 `/charges` 已有的字段）。
- 只想回滚展示：把 `ChargeItem` / `ChargeDetail` 里那段 `stat` 展示摘掉即可。
- 只想回滚数据量：把 `api.charges(settings.carId, 200)` 改回 `100`。
- 本轮**无服务端改动**，服务端无需回滚。

---

## 2026-10-24（v0.8.1：UX 迭代第 3 轮 —— 功率剖面、首屏零滚动、无障碍补齐）

> 本轮修掉一条**安全缺陷**（地图加载失败时把请求 URL 打在屏幕上 —— 那里面是**车辆实时经纬度**），
> 补齐系统大字体档与页面标题语义，并新增一条能解释能耗的曲线。
> 改动只落在 Android 客户端（`app-android/`，versionCode 15 → 16）；
> **本轮未改 API 契约** —— 功率剖面完全由 `/drives` 已有的 track 点在客户端算出。

### 背景

| 问题 | 实测表现 | 根因 |
|---|---|---|
| **安全** | 地图加载失败时，屏幕上直接打印出请求 URL | `ui/TrackMap.kt` 写的是 `Text("地图加载失败\n$url")`。那个 URL 里含 `points=lng,lat;…`（**车辆实时经纬度**）和内网服务器地址 —— 截图 / 录屏 / 读屏朗读就等于泄露位置 |
| **无障碍** | 用户在系统里把字号调到最大，App 里几乎没变化 | `ui/AppRoot.kt` 把 fontScale 封顶在 **1.15**，而 MIUI/HyperOS 大字号档是 1.2~1.3 —— 绝大多数用户的档位被**全部截断**，违反 WCAG 1.4.4（Resize Text） |
| **信息层级** | 胎压卡和第三行信息块都在折叠线以下 | 按 393×873dp 机型算，首屏顺序是「电量 → 能量条 → 充电卡 → 位置 → 6 个信息块」。折叠线以下 = 用户看不到 = 等于没有 |
| **功能缺口** | 只有总 Wh/km，看不出「哪一段费电、哪一段在回收」 | 行程详情只有静态地图与数值卡，没有一条能解释能耗的曲线 |

### 改动 1：安全 —— 地图加载失败不再输出请求 URL（`ui/TrackMap.kt`）

- 原来：`Text("地图加载失败\n$url")`
- 现在：`地图加载失败 / 请检查网络后下拉重试` —— 不暴露任何请求细节。
- 这条自 **v0.7.0** 起就挂在遗留清单里（v0.8.0 复查时确认仍未修），
  **v0.8.1 才真正修掉**。

### 改动 2：无障碍（`ui/AppRoot.kt`、`ui/Widgets.kt`）

- **fontScale 封顶 1.15 → 1.30**。1.15 把系统大字体档（1.15 / 1.2 / 1.3）**全部截断**，
  用户在系统里把字调到最大，App 里几乎没变化 —— IA 复核指出这违反 WCAG 1.4.4。
  现在 **1.3 以内的系统设置完全生效**（覆盖 MIUI/HyperOS 的常规大档）。
  仍封顶 1.3（而不是解封到 2.0）是因为车况页是仪表盘布局，2.0 倍时纵向会挤爆；
  **完全满足 1.4.4 需要把仪表盘改成可弹性布局，属遗留项**。
- **`SocRing` 圆环随字号缩放**：`168.dp × clamp(fontScale, 1, 1.3)`。
  否则解封的那部分字号会在圆环里溢出。
- **`KV` 的值 `maxLines` 2 → 3**：大字号下长数值（如「412.35 km（+128 km）」）会被截断。
- **新增 `ScreenTitle` 组件**（`ui/Widgets.kt`），所有页面标题
  （车况 / 充电 / 行程 / 统计 / 设置 + 充电详情 / 行程详情 + 更新对话框）改用它，
  给标题加 `heading()` 语义 —— 读屏用户（TalkBack）能按标题在页面间跳转，
  否则整个 App 是一张没有结构的长列表。

### 改动 3：行程详情新增「功率剖面」图（新增 `ui/PowerProfile.kt` + `ui/Charts.kt` 的 `PowerProfileChart`）

- **Y 轴 = 电池净功率（kW）**：正值（耗电）能量蓝，负值（能量回收）回收绿。
- **零线按实际正负范围自适应**，不是固定居中 —— 纯耗电行程固定居中会浪费下半张图，
  而且下方刻度会显示一个数据里根本不存在的负值。现在没有回收时零线贴底，没有耗电时贴顶。
- **X 轴是累计里程（km），不是时间**。时间轴在堵车时会骗人：原地烧电的那 40 分钟
  会被画成一大片低功率，看着像"省电了"。长途的决策变量是「还剩多少公里到下一个服务区」。
- **里程用哈弗森公式**在原始 GPS 点（`lat`/`lng`，WGS-84）上算 ——
  **不能**用 `map_lat`/`map_lng`（那是 GCJ-02 偏移后的坐标，只用于国内地图显示）。
- **按 1 km 分箱取平均**：服务端 track 是「每 N 个取 1 个」的朴素降采样，
  直接连线会混叠，把高频抖动画成大起大落。

### 改动 4：车况首屏重排（`ui/StatusScreen.kt`，新增 `LocationCard`）

原来顺序是「电量 → 能量条 → 充电卡 → 位置 → 6 个信息块」，
按 393×873dp 机型算，胎压和第三行信息块都在折叠线以下 —— 折叠线以下 = 用户看不到 = 等于没有。

新顺序：

1. 电量环 + 真实可达续航
2. 位置（地点名，可展开地图）
3. 空调 / 车内温度
4. 能量条
5. 充电中 / 行驶中状态卡
6. 车外温度 / 功率
7. 海拔 / 可用电量
8. 胎压卡

**信息一点没删，只是把高频答案提前了** —— 「位置」「空调 / 车内温度」两行被提到电量环之后，
其余各块原样保留、只是顺序前移。位置卡同时从页面里抽成 `LocationCard` 组件，
免得 `StatusScreen` 那个 Composable 长到 500 行读不动。

### 改动 5：图表与地图细节（`ui/Charts.kt`、`ui/TrackMap.kt`）

- 柱图空槽轨道改为 `trackColor.copy(alpha = 0.45f)`：原来 30 根**实心灰条**铺满绘图区，
  视觉上变成"条纹背景"，把真正的数据柱淹没。
- **柱图峰值标注从图上移除**：30 根柱子时每根柱宽只有约 30px，"1002" 四个字比柱还宽，
  文字必然溢出柱子，溢出部分是白底 + 白字（浅色模式）→ 看起来像"数字被切了一半"。
  图下方的摘要行已经写了「峰值 1002 km（10-06）」，图上再标一遍是重复。
- **Y 轴刻度最大值改用真实数据最大值**（新增 `tickMax` 参数），而不是绘图区上界 ——
  否则顶部留白会让刻度显示 1082，读者会以为数据到过 1082。绘图区上界仍留 8% 顶部空白。
- 地图 `contentDescription` 按场景区分：行程轨迹 = 「行程轨迹地图：起点 至 终点」，
  单点 = 「车辆位置：xxx」（此前恒为「行程轨迹地图」，而车况页展开的其实是车辆位置，
  读屏用户会以为图里有一条轨迹；`pointTitle` 参数一直存在却从未被使用）。
  加载状态加 `liveRegion` 播报；图片加 12dp 圆角；
  深色模式下叠 45% 暗色蒙版（高德静态图是日间白底瓦片，深色下一块刺眼白板）。

> **时间线更正**：上面三处**柱图**改动（轨道 alpha、峰值标注移除、`tickMax`）的代码与 v0.8.0
> 同批落地于 `8c53619`；v0.8.0 段把它写作「峰值标注避让」，实际是「峰值标注从图上移除」，
> 此处一并更正。真正属于 v0.8.1 的图表改动是**功率剖面图**本身。

### 影响

- 安全：地图加载失败时，屏幕上不再出现车辆经纬度与内网服务器地址。
- 无障碍：系统大字体档（≤1.3）现在完全生效；页面标题可被 TalkBack 当作标题跳转。
- 信息层级：车况页首屏**零滚动**即可看到「电量 + 续航 + 位置 + 空调 + 车内温度」。
- 功能：行程详情多一条「功率剖面（kW · 对应里程）」曲线，正负双向。
- 图表与地图：柱图可读性提升（轨道不再淹没数据柱、峰值不再被切一半、刻度不再虚高）；
  深色模式下的静态地图不再是刺眼白板；地图读屏文案不再张冠李戴。
- 本轮**未改 API 契约**：`Api.kt` 无模型变更，
  `Json { ignoreUnknownKeys = true }` 行为不变，旧版 App 与新版服务端仍互相兼容。

### 回滚

- App 侧：`git revert 856ddb8` 后重新打包即可，不依赖服务端
  （改动只读 `/status`、`/drives` 的既有字段）。
- 只想回滚「功率剖面」：把行程详情里 `powerProfile.size >= 2` 那个 item 摘掉即可。
- 只想回滚「首屏重排」：把 `LocationCard` 的调用挪回能量条之后即可。
- **安全修复（地图文案）不建议回滚**。

---

## 2026-10-24（v0.8.0：UX 迭代第 2 轮 —— 卡片分层、真实可达续航、图表时间轴）

> 本轮回应第 1 轮（v0.7.0）留下的遗留清单，同时修两处「数字在骗人」的问题：续航与里程。
> 改动只落在 Android 客户端（`app-android/`）；服务端只有一处配套修复（见文末）。

### 背景

第 1 轮结束后留下的 4 条遗留，评审员逐条点名：

| 遗留 | 实测表现 | 根因 |
|---|---|---|
| 卡片分层 | 深色下卡片 = 页底 = `#000000`，对比度 **1.00:1** | v0.7.0 的卡片走 `glassSurface` 半透明叠色，黑 @55% 叠在 #000000 上仍是 #000000 |
| 图表没有坐标轴 | 66.0 → 65.4 kWh 的真实衰减被画成一条平线 | Y 轴各自归一化到自己的峰值 |
| Canvas 图表不可达 | TalkBack 完全读不到图表 | Canvas 绘制的内容不进语义树 |
| 切 Tab 闪骨架屏 | 「看一眼统计再回来 = 重新开一次 App」 | `status` 是页面级 `remember`，而底部 Tab 用 `AnimatedContent` 切换会销毁旧页面 |

另有两处是「看起来对、其实骗人」：屏幕上的续航其实是 EPA 标定值（夏季高速乐观 25%~40%），
里程表与续航同级同字号（一屏两个主角）。

### 改动 1：材质分层真正落地（`ui/Theme.kt`）

- 新增 4 个材质 token：`cardSurface` / `cardInset` / `cardStroke` / `cardStrokeEnd`。
  卡片面改为**不透明实色**：浅 `#FFFFFF` / 深 `#1C1C1E`；内嵌块浅 `#F2F2F4` / 深 `#0B0B0D`。
  此前卡片走 `glassSurface` 半透明叠色：深色下黑 @55% 叠 #000000 页底 = 仍是 #000000，
  卡片与背景 **1.00:1** ——「深色改纯黑分层」在卡片上根本没生效；浅色同理只有 1.03:1。
- 卡片阴影去掉红色光晕：`cardDepth` 的 `spotColor` 由 `primary`（特斯拉红）改为中性黑。
- 卡片描边改读 token。此前浅色下是**白描白卡**，肉眼完全看不见边界。
- 页面底色浅色 `#F7F7F9` → `#F2F2F7`，`surfaceContainer` 系列重新校准。
- 图表轨道 `chartTrack` 提亮：深 `#26262A` → `#5A5A5E`（对黑底 1.39:1 → 3.1:1），
  浅 `#E6E8EB` → `#8E8E93`（1.23:1 → 3.3:1），满足 WCAG 1.4.11 非文字图形 ≥3:1。
- 能量条目标刻度由纯白 @75%（浅色下 1.17:1，等于隐形）改为 `onSurfaceVariant`。
- 状态卡（充电中 / 行驶中）由整块 `tertiaryContainer` / `secondaryContainer` 染色，
  改为**与其余卡片同材质 + 顶部 3dp 状态色条**（绿 = 充电 / 蓝 = 行驶）。
  整块染色之后，里面的 InfoTile 成了「深色块嵌在浅色块里」的层级倒置。
- 顶部栏改为透明（`TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)`），
  与底部真毛玻璃导航栏统一为一套材质语言（原来一个不透明 M3、一个玻璃）。
- 底部导航栏去掉 M3 胶囊指示器（`indicatorColor = Color.Transparent`），
  选中态改为图标 + 文字同色 primary，并新增顶部 0.5dp 分割线。

### 改动 2：动效与自绘图形

- Tab 切换动画去掉水平位移，只保留 140ms 淡入 / 120ms 淡出的交叉淡化（iOS UITabBar 语义）。
  v0.7.0 的注释写「无位移」但实现是 16dp 位移——**注释与实现相反**。
- 俯视车（`VehicleTopView`）重画：原图是圆角矩形 + 2 个圆角矩形当风挡 + 4 个小圆角条当轮子；
  现在是真正的俯视轮廓（车头/尾部收窄、腰线、梯形风挡、轮拱槽 + 嵌入式车轮），
  浅色用品牌红纵向渐变、深色用中性灰渐变，描边与轮胎改用轮廓级灰。
- `SocRing` 修两个 bug：① 角度多加了 `+90`，整个刻度盘相对圆弧偏转 90°（「刻度乱转」）；
  ② 12 格 × 22.5° 与「每格 10%」自相矛盾，98% 只亮 9 格。
  现统一 **10 格 / 27°**，亮起判定 `shown > i*10`。另：充电中时环变绿（新增 `charging` 参数）。

### 改动 3：图标与文案

- 清掉正文里残留的系统 emoji（`⚡` / `🔌` / `🚗` / `⚠`），换成 Material 矢量图标
  （`Icons.Filled.Bolt` / `Power` / `DirectionsCar` / `WarningAmber`）。
  emoji 在 Android 被渲染成彩色字形，与矢量图标混排时基线字重都对不齐，TalkBack 朗读也不稳定。
- 快充 / 慢充标签从 `primaryContainer`（深色下是一块深红 `#93000A`）改为充电绿 @16% / 中性灰 @10%，
  圆角 50 → 8dp。红色在本 App 的语义里**只**表示告警。
- 「更新于 HH:mm」改为相对时间「更新于 3 分钟前」（`Fmt.kt` 新增 `Instant?.relTime()`），
  超过一周回落到绝对时间。
- 里程表从 34sp Bold（与续航同级 = 一屏两个主角）降级为一行 13sp 小字，数字加千分位（101,350）。
- 位置卡箭头从「孤零零吊在地点名下方左对齐」移进标题行右端；
  地点主标题只取地址**第一段**（`Fmt.kt` 新增 `String?.firstSegment()`），
  完整邮编地址降为一行灰色小字。充电 / 行程列表行同样只取第一段。
- 汇总卡的「快充占比」进度条由 primary（红）改为能量蓝。

### 改动 4：图表接入真实时间轴与坐标轴

- 充电详情两张折线图接入真实时间轴（`CurvePoint.date` → `xWeights`），
  此前是等距索引 = 时间轴失真；补 Y 轴单位与 `contentDescription`；
  电量曲线配色由 tertiary 改为 secondary。
- 统计页三张柱图补 Y 轴刻度 / 网格线 / 单位、X 轴首中末标签、峰值标注避让。
- 电池健康曲线加 `referenceValue = nominal_capacity_kwh` 基准线
  （此前 Y 轴各自归一化，66.0 → 65.4 kWh 的真实衰减被画成一条平线）。
- `Api.kt` 的 `BatteryHealth` 补 `nominal_capacity_kwh`
  （服务端一直返回，Kotlin 模型缺失，健康度无法验算）。

### 改动 5：真实可达续航（新增 `ConsumptionModel.kt`）

原来屏幕写「预估续航」，实测 `est_battery_range_km` 全库仅 **0.085%** 有值，
`ideal_battery_range_km` 与 `rated_battery_range_km` 在 55,669 行里逐行相同
——实际显示的是 **EPA 标定值**，夏季高速乐观 25%~40%。

现在用 App 自己跑出来的 Wh/km（按车速分桶取中位数，抗离群）折算：

```
真实可达 = 标定续航 × 146 ÷ 实测 Wh/km × 温度修正 × 爬升修正 × 0.9 安全余量
```

- `146` = 本车 `cars.efficiency`（0.146 kWh/km）
- 温度修正只做热的：`× (1 + 0.006 × max(0, 气温 − 22))`，上限 +10%；冷天样本不足，不做
- 爬升 ≥ 200m：`× 1.05`
- 实测示例：98% 电量、标定 446 km、实测 187 Wh/km → **真实可达 308 km**
- 数据源 `/drives?limit=200`（`Api.kt` 的 `drives` 默认 limit 由 30 提到 200，并支持 `offset`）
  ——原 50 条窗口里高速行程 0 条，建不出速度分桶表

样本不足时回退显示标定续航（标题随之改为「标定续航」），不编数字。

### 改动 6：切 Tab 不再闪骨架屏（新增 `ui/StatusStore.kt`）

原来 `status` 是页面级 `remember`，而底部 Tab 用 `AnimatedContent` 切换会销毁旧页面
——每次切回「车况」都等于重新开一次 App（骨架屏 + 重新请求）。
现在数据提到 App 级 store，切回时旧数据立刻显示并后台刷新（stale-while-revalidate）。

### 改动 7：无障碍

`EnergyBar` / `SocRing` / `VehicleTopView` 补 `contentDescription`
（Canvas 图形对屏幕阅读器完全不可达）；俯视图的语义描述里包含四轮读数与异常状态。

### 改动 8：工程

`Charts.kt` 补 `import com.teslamate.app.fmt`——之前漏了，
导致**整个第 2 轮的图表改动其实编译不过**。

### 服务端配套修复（commit `0b04d95`）

- `has_supercharger` 判定从「充电时间落在行程时间窗内」改为**里程关联**
  （|充电起始里程 − 行程结束里程| < 2 km）。原判据永远命中不了（充电发生在行程结束之后），
  实测 200 条行程 0 条命中 → 修复后 **52/200**。
- 新增字段 `charge_after_trip`（任意类型的充电）。
- App 端文案相应从「超充经过 / 途中到达过超级充电桩」改为「到达时充电」。
- 详见本文件上一条《2026-10-24（API 修复：行程「超充经过」判据恒为 false）》。

### 影响

- 视觉：卡片与页底从 1.00:1 变为可辨分层；图表轨道与刻度满足 WCAG 1.4.11。
- 数据：车况页「续航」含义变了——从 EPA 标定值换成**按本车实测能耗折算的真实可达**。
- 性能：切 Tab 回到车况不再重建骨架屏，也不再每次重新请求。
- 可访问性：电量环 / 能量条 / 俯视图 / 图表对屏幕阅读器可达。
- 服务端字段名未变，`has_supercharger` 语义收紧但 App 侧字段名不动；
  新字段 `charge_after_trip` 是纯增量，App 的 `Json { ignoreUnknownKeys = true }` 不会因未知字段崩。
- 本轮未改 API 契约（除服务端单独提交的那一处）。

### 回滚

- App 侧：`git revert` 本轮提交后重新打包即可，不依赖服务端
  （真实可达续航只读既有 `/drives` 与 `/status` 字段）。
- 服务端侧：`git revert 0b04d95` 后 `docker compose up -d api`；旧口径只是恒 false，不影响其他字段。
- 只想回滚「真实可达续航」的话，把 `ConsumptionModel` 的调用点摘掉即可，标定续航仍在。

---

## 2026-10-24（API 修复：行程「超充经过」判据恒为 false）

### 背景

App「行程详情 → 地点与归属」的 `has_supercharger` 恒为 false，最近 200 条行程全是 false。
根因在 `api/queries.go` 的 `sqlDrives`：用**时间窗**关联充电与行程——
`charges.date BETWEEN drives.start_date AND drives.end_date`。
但充电发生在行程**结束之后**（到达服务区 → 结束行程 → 开始充电），
这条判据永远命中不了。实测本车 8/8 长途行程的 `charges_inside` 均为 0。

### 改动

- `sqlDrives` 改用**里程关联**：`|充电起始里程 − 行程结束里程| < 2 km`
  - 充电起始里程：`charging_processes.position_id → positions.odometer`
    （`charging_processes` **没有** `start_odo` 列）
  - 行程结束里程：`drives.end_km`
    （`drives` **没有** `odometer_start/odometer_end` 列，
    `start_km`/`end_km` 本身就是里程表读数）
- `has_supercharger` 保留字段名与布尔语义，含义收紧为「**终点附近有直流快充**」：
  里程关联命中后，再按 `sqlCharges.is_dc` 同一条判据过滤直流
  （`charger_phases` 为空/0 且 `fast_charger_type ≠ 'ACSingleWireCAN'`，
  **不用** `fast_charger_brand`——本车 86.7% 是 `'<invalid>'`，判不出快慢充）。
- 新增字段 `charge_after_trip`（bool）：终点附近有**任何**充电记录（含交流慢充），
  把「是否充电」和「是否直流快充」拆成两个字段。
- 同步更新 `docs/data-dictionary.md`（§2 口径表）、`docs/api-spec.md`（/drives 字段）、
  `api/README.md`（设计约束 7/8）；`scripts/smoke.sh` 加一条冒烟断言。

### 影响

- `/api/v1/cars/{id}/drives` 最近 200 条里 `has_supercharger` 为 true：**0 → 52 条**；
  `charge_after_trip` 为 true：**0 → 55 条**（其中 3 条是交流慢充）。
- 字段名未变，App 侧不依赖改代码即可受益；新字段是纯增量，
  App 的 `Json { ignoreUnknownKeys = true }` 不会因未知字段崩。
- 实测校验：行程 #405（343.5 km）→「G210 车河镇 南丹县」13%→87% 49 分钟；
  行程 #392（315.2 km）→「合江荔乡服务区」8%→50% 16 分钟，与预期一致。
- 冒烟测试 14/14 通过。

### 回滚

`git revert` 本次提交后 `docker compose up -d api` 即恢复旧口径；
旧口径只是恒 false，不影响其他字段。

## 2026-10-24（车况提醒接入 Hermes，App 退回纯查看）

### 背景
App 之前只能在用户主动打开时工作——充电结束不会通知、胎压异常不会告警。
对「一台车 + 家人」的定位来说，家人不会主动打开一个查车况的 App。
用户决定：**提醒不放在 App 里，改由本机的 Hermes 推送到微信/飞书。**

### 改动 1：告警判定收进 API 层（唯一事实源）

- 新增 `api/alerts.go`：胎压阈值 + 越界判定 + 单胎离群判定 + 数据新鲜度
- `/cars/{id}/status` 新增 6 个字段：
  `tpms_alert` / `tpms_alert_wheels`(数组) / `tpms_alert_detail` / `tpms_min_bar` / `tpms_max_bar` /
  `data_age_hours` / `data_stale`
- **为什么收进服务端**：App 和推送脚本两边各写一份阈值必然漂移，
  会出现「App 说胎压正常但微信推了告警」。阈值本身也由 API 返回，
  连界面上那句「正常范围 X–Y bar」都不会两边不一致。
- 冒烟测试新增 1 项断言（告警字段存在性与类型），**13/13 通过**

### 改动 2：推送脚本 `scripts/watch-car.ps1`

- 轮询本机 API → 与上次状态比对 → 状态跳变时调 `hermes send`
- 事件：开始充电 / 充电完成 / 胎压异常 / 数据超 24 小时未更新
- **只在跳变时推**，且同一异常只推一次、恢复正常后重置
- 首轮（无历史状态）：充电类不推（那是状态不是事件），但现存问题（胎压/数据过期）照推一次
- 环境变量优先于 `.env`，临时改配置不用动文件

### 改动 3：App 退回纯查看

- 删除 `Notifier.kt` / `WatchWorker.kt` / `WatchState.kt` / `Tpms.kt` / `ic_notify.xml`
- 移除 `work-runtime-ktx` 依赖与 `POST_NOTIFICATIONS` 权限
- APK 从 18.19 MB 降到 **17.79 MB**
- 服务端已下发胎压告警判定结果（`tpms_alert_wheels` / `tpms_alert_detail`），
  但 **App 端当时并没有读**——胎压卡仍按本地阈值算。
  「App 改读服务端判定」是 **v0.8.0** 才真正落地的（见本文 v0.8.0 段）
- 车况页保留「数据新鲜度」提示——那是显示层的澄清，不是推送

### 改动 4：Windows 计划任务

- 任务名 `TeslaMate-CarWatch`，**每 5 分钟**执行一次
- 命令走 ASCII junction `C:\tm-ws`（避开中文路径在 cmd/Task Scheduler 的解析坑）
- 实测：自动调度触发过一次、手动触发过一次，`Last Result: 0`

### 发版

`APP_VERSION_CODE=14` / `APP_VERSION_NAME=0.7.0`，APK 已部署，`smoke.sh` **13/13 通过**。

**模拟器真机验收**（Android 15 x86_64，服务器地址 `http://10.0.2.2:8090`）：
- 胎压卡标题「轮胎胎压」无「· 建议检查」，四轮 3.00/2.90/2.90/2.90 全部正常灰色
- 阈值说明显示「正常范围 2.5–3.3 bar（冷胎）」——从服务端读取，非本地常量
- 车况页蓝色新鲜度提示正确渲染：「车辆已 37 小时没有上报数据……」
- 设置页已无「车况通知」卡片
- APK 内确认无 `work-runtime` 依赖、无 `WatchWorker`/`Notifier`、无 `POST_NOTIFICATIONS` 权限

### Hermes 接入的实测结论

| 通道 | 结果 |
|---|---|
| **feishu** | ✅ `Sent to feishu home channel`，可用，**设为默认** |
| weixin | ❌ `iLink sendmessage session not ready: the user must send the bot a message first` |

- 微信必须**先给机器人发一条消息**（或重新配对）才能主动推送；飞书无此限制
- 必须用 `hermes.exe`（`hermes.cmd` 是 batch shim，程序化调用会静默失败）
- 端到端实测：真实推送出一条「ⓘ 爱车 数据已 37 小时未更新」，飞书已收到
- 四类事件分支全部用 mock API 逐条验证过（含去重逻辑）

### 文档同步

`api-spec.md`（新字段）/ `data-dictionary.md`（新增 §3.5 告警判定口径）/
`手机客户端使用说明.md`（提醒章节改为「走 Hermes」）/ `operations.md`（新增 §10）/
`adr.md`（新增 #20 #21）/ `smoke.sh`（新增 1 项断言）

---

## 2026-10-24 晚（v0.7.0：UX 迭代第 1 轮 —— 7 位评审员驱动）

> 迭代方式：每轮 7 位「用户/专家」并行评审（3 位不同喜好的特斯拉车主 + 1 位苹果设计师
> + 1 位特斯拉设计师 + 1 位动效触觉专家 + 1 位信息架构可访问性专家），
> 评审意见**先于**实施落地。本轮共收到 7 份评审报告。

### A. 视觉底座重做（`ui/Theme.kt` 重写）

| 改动 | 依据 |
|---|---|
| **iOS 排版尺度**（Body 17 / Footnote 13 / LargeTitle 34…），全局开 `tnum` 等宽数字 | 三份报告同时指出数字刷新时左右抖动（每 15 秒一次，是慢性不适） |
| **深色改纯黑分层**（`#000000` / `#1C1C1E` / `#2C2C2E`） | 原方案是 5 级灰阶、每级 ΔL\* 仅 3.9~4.3，深色下"一片灰"；且 `InfoTile` 与父卡片 ΔL\* 只有 **1.4**——人眼不可辨，嵌套层次直接消失 |
| **消除双强调色**：`tertiary` 从青色改为中性灰蓝 | 原先 `primary` 红 + `tertiary` 青两个强调色打架；更严重的是**健康电量用蓝 = 充电中用蓝**，两种状态同色 |
| **颜色语义重定**：正常=能量蓝，<20%=琥珀，<10%=危险红，绿**只**表示充电中 | 特斯拉设计师 |
| 琥珀在浅色下改深一档（`#B26A00`） | 实测原 `#F5A623` 在浅色底上仅 **1.76:1**，违反 WCAG 1.4.11（需 ≥3:1） |
| 引入 **Haze** 真毛玻璃（`dev.chrisbanes.haze:haze-android:1.0.0`） | 所有者诉求「毛玻璃」；iOS 的 vibrancy 在 Android 无原生等价物 |

> **⚠️ v0.8.0 更正**：本轮「深色改纯黑分层」当时**只在页面底色上生效**——
> 卡片仍走 `glassSurface` 半透明叠色，深色下黑 @55% 叠 #000000 页底 = 仍是 #000000，
> 卡片与背景对比度 **1.00:1**，等于没分层（浅色同理只有 1.03:1）。
> 真正把卡片换成不透明实色（深 `#1C1C1E` / 浅 `#FFFFFF`）是 **v0.8.0** 的事。

### B. 新增特斯拉视觉母题

- **能量条**（`EnergyBar`）：横向圆角条 + 目标 SOC 刻度线，充电中转绿 —— 特斯拉最易识别的元素，此前完全缺失
- **俯视车 + 四轮胎压**（`VehicleTopView`）：自绘车轮廓 + 4 个轮位，异常轮实心 + 呼吸动画。
  **这是本 App 唯一能超越官方 Tesla App 的地方**（官方不展示四轮胎压）
- **电量环重做**：360° 全圆 → 270° 缺口弧（全圆像 loading spinner）+ 12 格刻度 + 数字跟随动画
  （**v0.8.0 改为 10 格 / 27°**：12 格 × 22.5° 与「每格 10%」自相矛盾，98% 只亮 9 格）

### C. 修 4 个确凿 bug

| Bug | 证据 |
|---|---|
| **按压反馈从未生效** | `pressScale` 的 `MutableInteractionSource` 从未传给任何手势 → `pressed` 恒 false（两位专家独立发现） |
| **启动转圈在左上角** | `Surface` 的 `contentAlignment` 默认 `TopStart` |
| **地图展开时按返回直接退出 App** | 缺 `BackHandler` |
| **强制更新时弹窗永久无法关闭** | `mandatory=true` 时 `onDismiss` 是空实现 → 交互死锁 |

**遗留（当时列在 bug 表里，当轮未修；第 1 条已在 v0.8.1 修掉）：**

- **地图错误文案泄露内网地址与车辆经纬度** —— `ui/TrackMap.kt` 当时仍输出
  `Text("地图加载失败\n$url")`，URL 里含 `points=lng,lat` 与服务器地址。
  **当轮未修**（修它要动 Kotlin）；**至 v0.8.0 复查时仍未修**；
  **v0.8.1 才真正修掉**（见本文 v0.8.1 段「改动 1」）。
- **列表最后一项被导航栏盖住且滚不到** —— v0.7.0 的修法是把底部 padding 硬编码成 24dp，
  没算导航栏高度。现状：**车况页**用 `navBarBottom()` 留出导航栏高度；
  **充电 / 行程列表页**靠 Scaffold 的 inset / `contentPadding` 避让（底部 32dp / 120dp）。

### D. 数据层修复（服务端）——**这一项影响最大**

实测发现 **TeslaMate 只在 API 响应包含该字段时才写对应列**，导致 `positions` 表里
续航/胎压/温度/海拔这些列**非空率只有 3.5%**（`est` 只有 0.1%）。
后果：续航经常显示「—」、**胎压卡整块不显示**、温度恒为「—」。

修复：`api/queries.go` 新增 `rx` CTE，在最近 500 条 position 里对每个稀疏列
取「最后一个非空值」，外层用 `COALESCE` 合并（字段顺序不变，handler 无需改）。

实测修复后：续航 427.47 ✓ 胎压 2.9/2.8/2.9/2.8 ✓ 车外 28.5°C ✓ 车内 22.4°C ✓

### E. 交互与触感

- 新增 `Haptics.kt` 触感体系（振动波形可精确控幅；Compose 1.7.6 只有 `LongPress`/`TextHandleMove` 两个常量，故直调平台常量 + `VibrationEffect`）
- Tab 切换去掉 12.5% 水平位移（iOS UITabBar 是无位移的）
  （**⚠️ v0.8.0 更正**：当时**并没有真的去掉**——代码注释写「无位移」，实现里仍是 16dp 位移；
  真正去掉水平位移、只留 140/120ms 交叉淡化是 **v0.8.0**）
- Tab 图标 `contentDescription = null`（原来和文字标签重复朗读「车况,车况」）
- 按压反馈补 opacity + 阴影收缩
- 「空调」为 null 时显示「未知」而不是骗人的「关闭」
- 「数据已 N 小时未更新」替代「车辆已离线」（后者与状态胶囊的「离线」术语碰撞）
- 位置卡去掉开发者口吻的「点击展开地图」，改箭头图标
- 「总里程」→「里程表」、「续航」→「预估续航」（改用 `est_range_km`，标定续航偏乐观）
  （**v0.8.0 再改**：实测 `est_battery_range_km` 全库仅 0.085% 有值，显示的其实是 EPA 标定值；
  v0.8.0 改为按实测 Wh/km 折算的「真实可达续航」）

### 验证

模拟器（Android 15）逐屏截图核对：无崩溃、深浅色双模式、胎压俯视图渲染正确、
续航不再为空、车况页底部不再被导航栏遮挡（列表页的避让见上方遗留）。`smoke.sh` **13/13 通过**。

**遗留**：图表仍无坐标轴、Y 轴各自归一化（趋势被吃掉）、Canvas 图表对屏幕阅读器 100% 不可达、
切 Tab 仍会闪骨架屏（需要 ViewModel 上提数据层）—— 留待第 2 轮。
（**v0.8.0 已全部处理**，见本文 v0.8.0 段。）

---

## 2026-10-16（晚间：App 两项改进 + 模拟器无法启动的根因）

### ✅ 已通过真机验收（2026-10-16 晚，真机 25042PN24C / Android 16）

用户接上手机后完成验收。**验证方法不靠肉眼感觉，用服务端 API 日志做客观证据。**

#### 改动 1 验证：轮询生命周期

| 时刻 (UTC) | 事件 | 观测 |
|---|---|---|
| 12:29:35 ~ 12:31:21 | 前台 | 7 次 `/status`，**精确 15 秒间隔** |
| **12:31:29** | 按 HOME | `topResumedActivity` 变为 MIUI launcher |
| 12:31:21 ~ 12:33:33 | **后台 137 秒** | **零请求**（本该出现的 12:31:36 没来） |
| **12:33:33** | 切回前台 | — |
| **12:33:34** | — | **1 秒内立即刷新** |
| 12:33:49 / 12:34:04 / 12:34:19 | — | 15 秒节奏恢复 |

结论：后台停止 ✅ / 回前台立即刷 ✅ / 节奏恢复 ✅

#### 改动 2 验证：中文错误文案

`docker compose stop api` 制造真实连接失败，App 顶部横幅实际显示：

> ⚠️ **连接超时，请确认手机和服务器在同一个网络**

同时**保留了最后一次成功的数据**（33% / 100503 km 仍在）——降级行为正确。
`start api` 后无需用户干预，下一轮轮询自动恢复，错误横幅消失。

---

### 🐛 修复：胎压告警 63% 误报（v0.6.0 已修）

验收截图里看到「轮胎胎压 · 建议检查」红色告警，查库确认**这是误报**：

```
当前四轮：左前 3.0  右前 3.0  左后 2.8  右后 2.9 bar
51434 个样本：最小 2.50 / 最大 3.30 / 平均 2.96 bar
```

`StatusScreen.kt` 里写死的正常范围是 **2.4–2.9 bar**，与本车实际工况不匹配：

| 阈值 | 告警触发率 |
|---|---|
| 上限 2.9（旧代码） | **63.03%** |
| 上限 3.0 | 22.4% |
| 上限 3.1 | 3.4% |
| 上限 3.2 | 0.4% |

**含义：六成的时间它都在喊「建议检查」，而车其实完全正常。**
一个 63% 时间误报的告警等于没有告警——用户会直接把它当噪音忽略，
连带把真正该响的漏气信号也一起忽略掉。这比没有这个功能更糟。

**修复**：改为 **2.5–3.3 bar**（用户指定），并提取为 `TPMS_MIN_BAR` / `TPMS_MAX_BAR` 常量，
注释里写清阈值的数据来源，换车只需改两个常量。

| 指标 | 旧（2.4–2.9） | 新（2.5–3.3） |
|---|---|---|
| 总告警率（全历史） | 63.03% | **1.62%** |
| 绝对范围越界 | 62.4% | 0.59% |
| 单胎离群（均值 −0.2 bar） | 1.62% | 1.62% |

误报率降到 **1/39**，而真正有价值的「单胎离群」检测**触发率一字未变**——
被砍掉的全是噪音，信号完整保留。

**顺带排除的疑虑**：担心左后轮系统性偏低（连续采样都是 2.8）会让告警常响，实测证伪：
左后 vs 其余三轮 30 天平均差仅 **−0.010 bar**（噪声级），左后为最低胎的占比 53.56%（≈随机），
近 7 天离群告警触发率 **0.04%**。2% 是合理的安全告警敏感度。

**真机验证**：左前/右前 3.00 已恢复为正常灰色，仅左后 2.80 因落在 0.2 bar 边界而单独标红——
这正是该检查该做的事（它捕获的确实是「单胎比其他三只低 0.2 bar」的真实信号）。

---

## 2026-10-16 v0.6.0（胎压阈值修正 + 后台轮询停止 + 中文错误提示）

| 变更 | 验证方式 | 结果 |
|---|---|---|
| 胎压阈值 2.4–2.9 → 2.5–3.3 | 51434 采样点统计 + 真机截图 | ✅ 误报 63.03% → 1.62% |
| 15 秒轮询跟随生命周期 | 服务端 API 日志（客观证据） | ✅ 后台 137 秒零请求，回前台 1 秒内刷新 |
| 网络错误中文文案 | `stop api` 制造真实故障 + 真机截图 | ✅ 显示中文横幅且保留旧数据 |

**发版**：`APP_VERSION_CODE=13` / `APP_VERSION_NAME=0.6.0`，APK 已部署，`smoke.sh` 12/12 通过。

**顺带修复**：`APP_APK_URL` 从硬编码 Tailscale IP `<tailscale IP>` 改为 MagicDNS 主机名
`<主机名>`——Tailscale IP 变更时不必再改配置（`TrackMap.kt` 里的兜底 URL 同理，见待办）。

---

### 改动 1：15 秒轮询跟随界面生命周期

- **问题**：`StatusScreen` 里 `LaunchedEffect { while(isActive) { load(); delay(15_000) } }`，
  而全项目 grep 不到任何 `Lifecycle` / `repeatOnLifecycle` / `DisposableEffect`。
  切 Tab 会停（`AnimatedContent` 移除旧内容），但**App 退到后台不会停**——
  Activity `onStop` 时 Compose 树仍存活，协程继续跑，后台白耗电白耗流量。
- **处理**：套 `lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED)`。
  前台跑、后台停、回来再跑，回到前台时立刻刷一次。

### 改动 2：网络错误翻译成中文（5 个页面全部受益）

- **问题**：`Api.get()` 把响应塞进 `IOException(msg)` 抛出，页面直接显示 `it.message`。
  于是 OkHttp 原文会原样甩给用户：`Failed to connect to /<局域网IP>x:8090`、`timeout`。
  本 App 定位是「一台车 + 家人」（ADR #17），家属看到这种报错等于看天书。
- **处理**：新增 `UserFacingException`，**在 `Api.get()` 内部统一分类**——
  因此 5 个页面自动受益，无需逐页改：

  | 场景 | 现在的文案 |
  |---|---|
  | 401 | 访问令牌无效，请到「设置」页重新填写 |
  | 403 / 404 / 5xx | 服务端中文优先，否则按状态码兜底 |
  | UnknownHost | 找不到服务器，请检查「设置」里的地址是否正确 |
  | SocketTimeout | 连接超时，请确认手机和服务器在同一个网络 |
  | ConnectException | 连不上服务器，请确认服务已启动、手机与它在同一网络 |
  | 其他 IOException | 网络异常，请稍后重试 |


### 模拟器无法启动的根因排查（9 次尝试，均单独验证非猜测）

| 变量 | 尝试 | 结果 |
|---|---|---|
| 旧 AVD `teslamate_test` | headless + swiftshader | qemu 起，0.2s CPU 挂死 |
| 陈旧锁文件 | 改名 `multiinstance.lock` 后重启 | qemu 立即退出 |
| 全新 AVD `tm_emu` | headless + swiftshader | 同样挂死 → **排除 AVD 状态** |
| GPU 后端 | `-gpu off` / `swiftshader_indirect` | 显式指定后确实切到 `swiftshader`（非 `lavapipe`），仍挂死 → **排除 GPU 后端** |
| 加速器 | `-accel whpx`（非法值）/ `on` / `off`（纯 TCG） | **纯软件模拟也是 0.2s CPU** → **排除虚拟化** |
| 路径 | AVD 挪到 ASCII 路径 `C:\avd` | 同样挂死 → **排除中文路径** |
| 交互提示 | `-no-metrics`（确实消除了 ACTION REQUIRED 横幅） | 卡点不变 → **排除 stdin 阻塞** |
| headless vs 窗口 | 去掉 `-no-window` | **100 秒内 adb 连上**，但进程随后消失 |

**共同现象**：所有失败都停在 `Windows Hypervisor Platform accelerator is operational` 之后，
qemu 占 220MB 内存但 CPU 恒为 0.2~0.7s，Windows 事件日志无任何崩溃记录。
`-accel off` 纯软件模拟同样是 0.2s CPU——**软件模拟必然烧 CPU，这证明它根本没进入 vCPU 循环**。

**结论**：环境层面 WHPX 自检通过、兼容性四项检查全 Ok（hypervisor / system / disk / 无需 GPU），
但 qemu 在初始化后、启动 vCPU 前被阻塞，且不产生任何错误输出。
**不是代码问题，也不像 GPU 后端问题；更像 WHPX 与 VBS 共存时的宿主侧阻塞**
（本机 `HypervisorPresent=True`、`VirtualizationBasedSecurityStatus=2`）。
此前 CHANGELOG 记的「SwiftShader Vulkan 卡住」是**误判**——SwiftShader 只是表象。

**待验证的下一步**（都需重启/停机窗口，未擅自执行）：
1. 关闭 VBS（内存完整性）后重试——当前最可疑变量
2. BIOS 关闭 AMD SVM 或调整 IOMMU
3. 装 `google_apis` 系统镜像（当前 `default` 镜像不支持 guest rendering）

**遗留物**：为排查创建了 `tm_emu` 和 `asciiemu` 两个 AVD（后者在 `C:\avd`），可自行删除。

### 复核更正：OneDrive 风险不成立

本日早些时候误判「`database/` 在 OneDrive 同步目录有损坏风险」并建议迁移。
用户指出本机并未启用 OneDrive，实测确认：

- `OneDrive.Sync.Service` 进程在跑，但**同步日志中 `teslamate` / `database` 零命中**
- 1388 个数据库文件属性**全部是 `Archive`**，零占位符
- Business1 账户配置最后写入停在 2025-12-11，Personal 停在 2026-01-07

**结论：`database/` 未被同步，迁移待办作废。**
**教训**：不能从文件夹名叫 `OneDrive` 就推断它在同步。当时自己的实测数据（零占位符）
已经在反驳这个假设，却找了个台阶绕了过去。中文路径 junction（`C:\tm-ws`）那条仍然有效，与 OneDrive 无关。

---

## 2026-10-16（自动备份上线 + 三个方向决策固化）

### P0：数据库每周自动备份

**问题**：10-12 建基线后一直没再备份，到 10-16 整整 4 天。而 `database/` 是本项目**唯一有状态资产**——误删 = 所有车况数据永久丢失（git 只存代码，不存数据）。期间 DB 已从 189MB 涨到 243MB。

**处理**：
1. 先手动补一份 `backups/db-20261016.sql`（193MB），校验文件尾为 `PostgreSQL database dump complete`，结构完整。
2. 新建 `scripts/backup-weekly.sh`：跑 `backup-db.sh` + 轮转 + 写日志。
   - **必须有日志**：计划任务的 stdout/stderr 默认被系统丢弃，失败时无处可查。
   - **前置检查**：先确认 `docker` 可用且 `teslamate-database-1` 在跑，不满足直接失败退出，避免刷一堆无意义错误。
3. 注册 Windows 计划任务 `TeslaMate-WeeklyDBBackup`，每周日 03:00，命令 `"C:\Program Files\Git\bin\bash.exe" -c "cd /c/tm-ws && bash scripts/backup-weekly.sh"`。
4. `operations.md` 新增 §9（现状 / 为什么走 junction / 轮转规则 / 常用操作 / 已知限制）。

**轮转的关键设计**：只匹配 `backups/db-[0-9]*.sql`，`db-baseline-*` 因 `db-` 后是字母 `b` 而**天然不匹配**，基线种子文件永不被轮转删除。保留最近 8 份（≈1.6GB），避开 OneDrive 无限堆积。改脚本时**别把这个 glob 放宽成 `db-*.sql`**。

**验证**：`schtasks /Run` 实测触发 → 日志确认完整跑通 pg_dump（193MB）→ `上次结果: 0`。baseline 保护逻辑用 `KEEP=1` 模拟验证，确认 baseline 不在删除清单内。

### P1：修 README 承诺的「5 分钟上手」实际跑不通

**问题**：README/CONTRIBUTING/architecture/docs 共 10 处写 `bash scripts/smoke.sh`，但**本机 `bash` 解析到 `C:\WINDOWS\system32\bash.exe`（WSL 垫片），而 WSL 没装 `/bin/bash`** → `execvpe(/bin/bash) failed: No such file or directory`。只有 `operations.md` 一处注明了「Git Bash」。照 README 走的人必定卡在第 2 步，也就是整个上手路径的第一道关。

**处理**：README 快速上手加显式警告 + PowerShell 替代调用；`CONTRIBUTING.md` §4 验证纪律加同等提示；README 30 分钟路径标注必须用 Git Bash。

**教训**：与 `operations.md` §8 的中文路径 junction 是**同一类 Windows 环境陷阱**，只是记在了另一份文档里。文档分裂本身就是缺陷——同类坑应该收在一处。

### 决策固化（用户 2026-10-16 确认）

| 决策 | 结论 | ADR |
|---|---|---|
| 数据库/Grafana 弱口令 | **维持现状不轮换**。已在 `operations.md` §5 标注：口令字面值已随该文档进入 git 历史，**不再构成秘密边界**；真正的边界是绝不把 8090/3000/4000 暴露公网 | #18 |
| 项目定位 | **长期单用户自用（一台车 + 家人）**。API 已天然按 `car_id` 分层，将来加车无需重构；但**当前刻意不提前抽象**多租户/限流/只读数据库角色——单用户场景下那全是无人使用的复杂度 | #17 |
| 备份周期 | **每 7 天**（非每月） | #19 |

### 本次复核结论（审计报告的验证）

对 10-15 基建审计的每条断言做了独立复核，全部属实：2 次提交 / 工作区 clean / 74 跟踪文件 / 7 个敏感路径 `tracked=0` / 真实 `API_TOKEN`(48位) 与 `AMAP_KEY`(32位) 在**全部 git 历史对象**中 HIT=0 / `smoke.sh` 用 Git Bash 实跑 12 过 0 败 / CHANGELOG 12 段 / `.gitignore` 无行尾注释。

补充扫描发现 `POSTGRES_PASSWORD` / `GF_SECURITY_ADMIN_PASSWORD` 在历史中各有 2 处命中，定位于 `operations.md:158` —— 该行本身在声明弱口令是已知风险，非密钥泄漏，但确实把口令字面值从「仅存在于被忽略的 `.env`」变成了「版本历史里明文可读」。已按用户决定保留原文并补充说明。

### 仍待处理

- `database/` 位于 OneDrive 同步目录，PGDATA 直接读写有损坏风险，**需停机窗口**迁移。
- Grafana 匿名 Admin 角色开放（局域网可接受）。
- `handlers.go` 有死代码：`showMarkers` 算完即丢弃（`_ = showMarkers`），`markers=0` 参数无效；`v.Set("paths", ...)` 重复调用两次（331 行与 338 行）。实测两种请求返回字节完全一致，无功能影响，仅影响代码整洁。

---


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
