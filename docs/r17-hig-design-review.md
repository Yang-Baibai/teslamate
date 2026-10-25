# r17 HIG 设计评审报告（Apple Human Interface Guidelines 视角）

> 评审人：Apple iOS/macOS 设计评审（只读评审，不改代码）
> 评审对象：`app-android/` 当前源码（`v0.25.0`，versionCode 42）@ commit 工作区
> 评审日期：2026-10-25
> 依据：Apple Human Interface Guidelines + Apple Design Resources（Material 3 / Compose 实现）
> 所有者审美硬约束：简洁（一屏一个主角数字）/ 高级（材质分层 + 1px 渐变描边 + 单光源彩色阴影）/ 毛玻璃（真 backdrop 模糊）/ 3D（rotationX/Y ≤ 2.5°，`cameraDistance = 3 × 元素宽(px)`，静态列表卡片 0°）/ iOS 式交互（Tab 无位移、push 30% 视差、按压 = 变暗 + 轻缩放、必配触感）

## 0. 评审方法与证据分级

| 分级 | 含义 | 本报告中的数量 |
|---|---|---|
| **P0** | 违反**硬性指标**且**用户可见**（对比度不足 / 200% 字号崩版 / 数据被裁 / 状态只靠颜色） | 6 |
| **P1** | 违反 **HIG 规范**或**所有者审美硬约束**（信息层级、动效类型、3D/毛玻璃用法、触感覆盖） | 12 |
| **P2** | 打磨项（token 体系一致性、字距、装饰冗余、触控余量） | 10 |

### 0.1 证据来源与版本声明（重要）

- 所有结论**以当前源码为准**，每条给出 `文件:行号` 与**实测当前值**。
- **截图证据的时效性**：`C:\tm-shots\r15\`（25 张）与 `r17\`（4 张）**落后于当前代码 1–6 个版本**。可验证的漂移点：
  - `r15/25-settings-2x.png` 显示「保存并测」「仅测试连」按钮横排被裁 → 该问题**已在 `SettingsScreen.kt:672-718`（v0.20.0）修掉**，当前为纵向排列 + `heightIn(min = 44.dp)`；
  - `r15/01-status-light.png` 显示保养提醒为红色 errorContainer → 当前 `StatusScreen.kt:397` 已改为中性 `NoticeBanner`；
  - `r17/02-charge-list.png` 芯片文案为「直流快充」→ 当前 `ChargesScreen.kt:393-397` 已改为「国标直流 / 特斯拉超充 / 交流慢充」三档。
- 因此本报告把截图当作**佐证**而非唯一依据；凡是截图与代码冲突的，一律以代码为准并在条目内注明。

### 0.2 结论摘要

这套代码的**底子已经很 Apple**：分组列表（`SettingRow` + 右箭头 + 值摘要）、`tnum` 全局排版、`cardDepth` 的 0.5dp 发丝线 + 单光源阴影、`pressable` 的「变暗 + 轻缩放」、`Tilt3DState` 的 `cameraDistance = 3 × 宽(px)` 推导、haze 源/效果的兄弟关系 —— 这些都是**正确且有推导过程的**。

问题集中在**三类系统性漂移**上：

1. **「已修好的东西在别处被复制了一份」** —— `Motion` token 收拢后，调用点仍写死旧数值（`Motion.kt` 的 `Shimmer`/`TpmsPulse`/`ChartGrow` 从未被引用）。
2. **「设计决定写在注释里，实现没跟上」** —— 注释写「禁用 Material ripple」，11 处交互仍用原生 `.clickable`；注释写「用户操作 → spring」，按压/折叠/push 全是 `tween`。
3. **「组件有 `fontScale` 感知，但预留空间没有」** —— 图表刻度带按 1.6× 预留，文字按真实 2.0× 绘制；电量环直径封顶 1.75×，数字按真实字号放大。

---

## 1. 设计 token 现状盘点

### 1.1 语义色板实测对比度（`Theme.kt`）

对比度按 WCAG 2.x 相对亮度公式实测（`L = 0.2126R + 0.7152G + 0.0722B`，线性化后计算），前景/背景十六进制均来自源码实际 token。

**浅色模式（`LightColors`，`Theme.kt:237-278`）**

| 前景 | 背景 | 实测比值 | 用途 | 判定（文字≥4.5 / 图形≥3.0） |
|---|---|---|---|---|
| `#1C1C1E` onSurface | `#F2F2F7` background | **14.00:1** | 正文 | ✅ |
| `#63636A` onSurfaceVariant | `#FFFFFF` 卡片面 | **5.96:1** | 次级文字 | ✅ |
| `#63636A` onSurfaceVariant | `#EBEBF0` 内凹块 | **5.02:1** | 次级文字 | ✅ |
| `#141C22` onTertiaryContainer | `#E2E7EB` tertiaryContainer | **13.83:1** | 中性横幅 | ✅ |
| `#410002` onErrorContainer | `#FFE1DE` errorContainer | **13.93:1** | 错误横幅 | ✅ |
| `#D70012` primary | `#FFFFFF` | **5.39:1** | 强调 / 选中态 | ✅ |
| `#1E9E62` chargeGreen | `#FFFFFF` | **3.43:1** | 仅图标/进度填充 | ✅ 图形；❌ 文字 |
| `#0F6B41` chargeGreenText | `#FFFFFF` | **6.56:1** | 压淡绿底的文字 | ✅ |
| `#9A5B00` warnAmber | `#FFFFFF` | **5.43:1** | 告警文字 | ✅ |
| `#9A5B00` warnAmber | `#EBEBF0` 内凹 | **4.57:1** | 告警文字 | ✅（余量仅 0.07） |
| `#D13438` danger | `#FFFFFF` | **4.93:1** | 危险文字 | ✅ |
| `#2F5FD0` energyBlue | `#FFFFFF` | **5.72:1** | 能量 / 快充 | ✅ |
| `#6B4FBF` energyViolet | `#FFFFFF` | **6.01:1** | 行驶耗电 | ✅ |
| `#77777D` chartTrack | `#FFFFFF` | **4.45:1** | 轨道 | ✅ |
| `#8C939B` chartBaseline | `#FFFFFF` | **3.11:1** | 参考线 | ✅ 图形（余量 0.11） |
| `#8E8E93` outline | `#FFFFFF` | **3.26:1** | 描边 | ✅ 图形 |
| `#E0E0E5` outlineVariant | `#F2F2F7` 页面底 | **1.18:1** | 时间轴竖线 | ❌ 几乎不可见 |
| `#FFFFFF` 白旋钮 | `#34C759` 开关开 | **2.22:1** | 开关旋钮边界 | ❌ **不达标** |

**深色模式（`DarkColors`，`Theme.kt:280-319`）**

| 前景 | 背景 | 实测比值 | 判定 |
|---|---|---|---|
| `#F2F2F7` onSurface | `#000000` | **18.82:1** | ✅ |
| `#98989F` onSurfaceVariant | `#000000` | **7.33:1** | ✅ |
| `#98989F` onSurfaceVariant | `#1C1C1E` 卡片面 | **5.94:1** | ✅ |
| `#98989F` onSurfaceVariant | `#2C2C2E` surfaceContainerHigh | **4.86:1** | ✅ |
| `#FFB4AE` onErrorContainer | `#3A1518` | **9.53:1** | ✅ |
| `#FF453A` primary | `#1C1C1E` | **4.99:1** | ✅ |
| `#FF6B6E` danger | `#1C1C1E` | **6.14:1** | ✅ |
| `#5B8DEF` energyBlue | `#000000` | **6.50:1** | ✅ |
| `#5FD69A` chargeGreenText | `#1C1C1E` | **9.38:1** | ✅ |
| `#F5A623` warnAmber | `#000000` | **10.36:1** | ✅ |
| `#A98BF0` energyViolet | `#1C1C1E` | **6.19:1** | ✅ |
| `#6E6E76` chartTrack / outline | `#1C1C1E` | **3.37:1** | ✅ 图形 |
| `#6E6E76` outline | `#000000` | **4.15:1** | ✅ |
| `#38383A` outlineVariant | `#1C1C1E` | **1.45:1** | ❌ 几乎不可见 |
| `#FFFFFF` 白旋钮 | `#34C759` 开关开 | **2.22:1** | ❌ **不达标**（深浅同缺） |

### 1.2 半透明叠加后的实测（`copy(alpha=)` 合成底色）

| 元素 | 前景 | 合成底 | 实测 | 判定 |
|---|---|---|---|---|
| 时间轴充电节点圆底（页面底） | `#1E9E62` @18% → `#CCE3DC` | 13dp 图标 | **2.55:1** | ❌ **不达标** |
| 时间轴行程节点圆底（页面底） | `#2F5FD0` @16% → `#D3DAF1` | 13dp 图标 | **4.11:1** | ✅ |
| 快充芯片底（卡片白底） | `#2F5FD0` @16% → `#DEE5F7` | 13dp 图标 + 文字 | **4.54:1** | ✅（文字余量 0.04） |
| 绿 @18% 叠卡片白底 | `#D7EEE3` | — | **2.81:1** | ❌ |

### 1.3 排版 token（`Theme.kt:325-382`）

- ✅ **全部 15 个文字样式都带 `fontFeatureSettings = "tnum"`**（`Theme.kt:323` 定义 `Tnum`，逐项应用到 `displayLarge`…`labelSmall`）。
- ⚠️ **两处手写 `fontSize` 绕过了 typography**，其中一处缺 `tnum`：
  - `Widgets.kt:1473-1479` 电量环数字 `fontSize = 40.sp` —— 虽复用了 `style = displayLarge`（因此 `tnum` 继承生效），但**字距是手写的 `-1.5.sp`**，与 `BigStat` 的 `-0.4.sp` 不一致。
  - `Charts.kt` 共 **13 处** `TextStyle(fontSize = 9.sp / 10.sp / 11.sp)` 的 Canvas 内文字（`Charts.kt:100,116,311,502,567,838,900,1069,1117,1141,1158`）—— 这些是**图形内标签**，随 `fontScale` 放大，但**没有 `tnum`**（轴刻度数字如 `50`、`100` 会左右抖）。
- ⚠️ **`Canvas` 内 9sp 文字低于 iOS 最小 caption 尺寸**（iOS footnote 11pt / caption1 12pt）。

### 1.4 形状与材质 token

| 项 | 现状 | 判定 |
|---|---|---|
| 卡片圆角 | `RoundedCornerShape(20.dp)`（`Widgets.kt:553`） | ✅ 与 iOS grouped 一致 |
| 内凹块圆角 | `14.dp`（`Widgets.kt:1218`） | ✅ |
| 描边 | `0.5.dp` 渐变（`Widgets.kt:215-222`） | ✅ 正确的发丝线 |
| 单光源阴影 | 环境 `#0A000000`(4%) / 主光 `#1F000000`(12%)（`Theme.kt:157-158`） | ✅ 方向正确 |
| 深色分层 | 底 `#000000` / 卡 `#1C1C1E` / 内凹 `#0B0B0D` | ✅ |
| 毛玻璃档位 | Thin 16 / Bar 20 / Regular 24 / Thick 30 dp（`Widgets.kt:126-135`） | ✅ |
| **haze 源/效果关系** | 源 = `AppRoot.kt:462`（内容区 `AnimatedContent`）；效果 = `AppRoot.kt:343`（底栏 `glassSurface(realBlur=true)` → `Widgets.kt:157` `hazeChild`） | ✅ **兄弟节点，未违反 haze 限制** |
| **`Theme.kt:413` `hazeSource(state)`** | 全项目 **0 调用** | ⚠️ 死助手；若有人在 haze 源内部调用它，会直接造成父子关系违规 → 见 P2-9 |

### 1.5 缺口标记汇总

| # | 缺口 | 影响 |
|---|---|---|
| G1 | 开关「开」态白旋钮无 token、无对比度保障 | P0-1 |
| G2 | 充电节点图标用 `chargeGreen` 而非 `chargeGreenText` | P0-2 |
| G3 | 低电量（<20% / <10%）**没有专用 token + 文案/图标**，只有颜色 | P0-6 |
| G4 | 时间轴竖线用 `outlineVariant`（1.18:1），与「竖线串起整列」的视觉承诺不符 | P1-10 |
| G5 | `Motion` 里 8 个 token 从未被引用（`EaseOut`/`ChartGrow`/`ChartMorph`/`TabFadeIn`/`TabFadeOut`/`DataValue`/`TiltPress`/`TpmsPulse`/`Shimmer`），其中 3 个的**定义值与调用点实际值不同** | P1-2 |
| G6 | 无 `switchOn` / `switchOff` / `switchKnob` token，`#34C759` 硬编码在组件内 | P0-1 / P2-1 |
| G7 | Canvas 图表标签统一 9sp，无独立「图形内文字」排版 token | P2-8 |
| G8 | 没有「图表刻度在 200% 下如何让位」的 token（各处各自 `coerceAtMost(1.6f)`） | P0-5 |

---## 2. 逐页问题清单

格式：**`文件:行号` → 现状值 → 整改值 → 理由**。

---

### 2.1 P0 —— 违反硬性指标（6 条）

#### P0-1｜开关「开」态白旋钮对比度 2.22:1（图形 < 3:1）

- **位置**：`SettingsScreen.kt:1424`（`onColor = Color(0xFF34C759)`）、`SettingsScreen.kt:1459-1466`（27dp 白色旋钮 + `border(0.5.dp, Color(0x1A000000))`）
- **现状**：`#FFFFFF` 旋钮 on `#34C759` 轨道 = **2.22:1**。关闭态反而是 `#FFFFFF` on `#77777D` = **4.45:1** —— 即**「开」比「关」更不可读**，违反 WCAG 1.4.11（非文字图形 ≥3:1）。
- **整改**：
  - 新增 token `switchOn`（浅 `#2E9E4F` → 白旋钮 **3.43:1**；深沿用 `#34C759` 但旋钮改 `#F2F2F7` 描边）、`switchOff`（浅 `#8E8E93`、深 `#6E6E76`）。
  - 旋钮描边由 `Color(0x1A000000)` 改为 `accents.cardStroke`（浅 `#14000000` / 深 `#24FFFFFF`）。
  - `SettingsScreen.kt:1424` 的硬编码 `#34C759` 移入 `Theme.kt` 的 `AccentColors`。
- **理由**：开关是设置页最常见的控件；「开」态旋钮边界消失在绿色上，用户读到的是「一个没有旋钮的绿条」。

#### P0-2｜记录页时间轴「充电」节点图标 2.55:1（图形 < 3:1）

- **位置**：`RecordsScreen.kt:555-556`（`nodeBg = accents.chargeGreen.copy(alpha = 0.18f)`、`nodeTint = accents.chargeGreen`）、`RecordsScreen.kt:583`（13dp 图标）
- **现状**：合成底 `#CCE3DC`（页面底 `#F2F2F7` + 绿 18%），图标 `#1E9E62` = **2.55:1** < 3:1。
- **整改**：`nodeTint` 改用已存在但**只用在芯片上**的 `accents.chargeGreenText`（`Theme.kt:138`）：
  - 浅色 `#0F6B41` on `#CCE3DC` = **4.87:1** ✅
  - 深色 `#5FD69A` on 合成底 = **9.38:1** ✅（深色 `chargeGreen` `#2FBF71` 本身够，但统一用 text token 更一致）
  - `nodeBg` 保持 `chargeGreen @18%` 不变。
- **理由**：13dp 是全 App 最小的图标；2.55:1 意味着低视力/强光下完全读不出「这是充电不是行程」。行程节点（蓝 4.11:1）达标，说明**只有绿色这一支没跟上**。

#### P0-3｜200% 字号下电量环数字溢出圆环内圈

- **位置**：`Widgets.kt:1367`（`ringSize = (baseSizeDp * fontScale.coerceIn(1f, 1.75f)).dp`）、`Widgets.kt:1473-1479`（`fontSize = 40.sp`）、`StatusScreen.kt:543`（大字号分支 `baseSizeDp = 120`）
- **现状**：环直径在 `fontScale = 2.0` 时被**封顶在 1.75×**，而数字按**真实 `fontScale`** 放大（40sp → 80sp 等效）。
  - 大字号分支：环 = 120 × 1.75 = **210dp**，内圈直径 = 210 − 2×(15dp 描边 + 2dp 内缩) = **176dp**；`"100%"` 在 80sp 等效下宽约 **176dp** → 恰好等于内圈、实际已溢出。
  - 佐证：`C:\tm-shots\r15\21-status-2x.png` 中 `69%` 的 `%` 已贴到圆环内圈右缘。
- **整改**（二选一，推荐 A）：
  - **A（推荐）**：数字字号由环直径推导，**不跟随 fontScale 放大**：
    `fontSize = ((ringSizeDp * 0.235f) / fontScale).sp`
    → 1× 时 168 × 0.235 = **39.5sp**（与现状 40sp 视觉一致）；2× 时 210 × 0.235 / 2 = **24.7sp**（等效 49.4dp），`"100%"` 宽约 109dp < 176dp ✅
  - **B**：环直径取消 1.75× 封顶，改为 `baseSizeDp * fontScale` 且大字号分支 `baseSizeDp = 168`；但 200% 时环将达 336dp（> 屏高 25%），与 `StatusScreen.kt:528` 的设计意图冲突。
  - 附带：`letterSpacing` 由 `-1.5.sp` 改为 **`-0.4.sp`**（与 `Widgets.kt:1264` 的 `BigStat` 统一；`-1.5sp` 在 40sp 上是 −3.75% 追踪，Apple 大字号数字用 ≈ −0.9%）。
- **理由**：电量环是车况页的主角；数字破环 = 主角破形。

#### P0-4｜200% 字号下 `InfoTile` 数值被截断（数据丢失）

- **位置**：`Widgets.kt:1230-1236`（`InfoTile` 的 value：`maxLines = 1, overflow = TextOverflow.Ellipsis`）
- **现状**：`InfoTile` 是车况页**承载数据**的组件（空调 / 车内温度 / 车外温度 / 功率 / 胎压）。值用 `titleMedium`（17sp）→ 200% 时 34sp 等效；半宽 `InfoTile` 内宽仅约 149dp，`"27.5°C"` 这类 6 字符串在 34sp 下超出 → 被省略号吃掉，**用户看不到真实读数**。
- **整改**：
  - `maxLines = 1` → **`maxLines = 2`**，`overflow = TextOverflow.Ellipsis` 保留；
  - `SectionCard` 内嵌时 `InfoTile` 的 `Column` 加 `heightIn(min = ...)` 无关紧要（自然撑高即可），关键是**不要限行**；
  - 同时把标签 `labelMedium`（`Widgets.kt:1228`）保持单行、允许 `maxLines = 1`（标签是分类名，截断可接受；**数值不可截断**）。
- **理由**：WCAG 1.4.4 要求放大后不丢内容；1.4.10 要求可调整而无需横向滚动。这里丢的是**用户唯一的读数**。

#### P0-5｜图表刻度预留带按 1.6× 计算，文字按真实 2.0× 绘制 → 刻度溢出/重叠

- **位置**：`Charts.kt:236`、`Charts.kt:403`、`Charts.kt:725`、`Charts.kt:977`
  （`val fs = LocalDensity.current.fontScale.coerceAtMost(1.6f)`，用于 `yLabelWidth` / `xLabelHeight` / `rightLabelWidth`）
- **现状**：预留带宽度按 **1.6×** 计算，但 Canvas 内文字用 `TextStyle(fontSize = 9.sp)`（`Charts.kt:100,116,311,…`），**随真实 `fontScale`（可达 2.0× 甚至 3.0×）放大**。2.0× 时文字比预留带大 25%，Y 轴标签被左边界裁掉、X 轴标签压进曲线。
  注：`Charts.kt:724` 的注释「1.6 是上限 —— 再大刻度就该换成自适应 Y 轴范围」说明作者知道有问题，但**只做了半步**（没把「换成自适应」实现）。
- **整改**：
  - 预留带与文字**必须用同一个系数**：`val fs = LocalDensity.current.fontScale`（去掉 `coerceAtMost`）；
  - 若 `fs > 1.6`，**减少刻度数量**而不是压缩文字：在 `Charts.kt` 的 `niceTicks` 调用处传入 `maxTicks = if (fs > 1.6f) 4 else 6`；
  - 图表内文字最小字号锁 **11sp**（见 P2-8），并加 `fontFeatureSettings = "tnum"`。
- **理由**：这是 200% 下最容易被用户直接看到「App 坏了」的破版点。

#### P0-6｜低电量状态只靠颜色表达（WCAG 1.4.1）

- **位置**：`Widgets.kt:1347-1353`（`SocRing` 配色：`soc >= 20 → energyBlue`、`>= 10 → warnAmber`、`else → danger`）、`Widgets.kt:1512-1518`（`EnergyBar` 同款语义）
- **现状**：电量跌破 20% / 10% 时，圆环与能量条**只换颜色**。环内数字是 `69%` 这种纯数值，**没有任何图标或文字**说「电量低 / 需要补电」。色觉障碍用户与灰度截图下，环从蓝变琥珀/红**无法被解读**。
  （对比：`LockStateChip`（`StatusScreen.kt:890-926`）做得正确 —— 图标 + 文字 + 颜色三重编码；`StateChip`（`Widgets.kt:1279-1304`）也有图标 + 文字。）
- **整改**：
  - `soc < 20` 时在环的**下方或右上角**加一枚状态胶囊：`Icon(Icons.Filled.BatteryAlerting, 14.dp)` + `Text("电量低", labelMedium)`，底色 `#FFF4E5` / 文字 `#9A5B00`（对比度 9.5:1）；
  - `soc < 10` 时改 `Icon(Icons.Filled.ErrorOutline, 14.dp)` + `Text("电量很低", labelMedium)`，底色 `#FFE1DE` / 文字 `#410002`（13.9:1）；
  - `EnergyBar` 同步加同一个状态胶囊（两处必须共用同一个 `BatteryStateChip` 组件，避免二次漂移）。
- **理由**：低电量是**要立刻行动**的状态，是这个 App 最需要被看见的一种信号；只靠颜色 = 对约 8% 男性最不可靠的通道。

---

### 2.2 P1 —— 违反 HIG / 所有者审美硬约束（12 条）

#### P1-1｜车况页有两个平级主角：环内 40sp vs 续航 44sp

- **位置**：`Widgets.kt:1261`（`BigStat` hero = 44sp）、`Widgets.kt:1475`（环内数字 = 40sp）、`StatusScreen.kt:538-553`（两者同屏）
- **现状**：车况页首屏同时出现 **189（44sp Bold）** 与 **69%（40sp Bold）**，字号只差 4sp（≈10%），两者字重、颜色（都是 `onSurface`）也相同 → 视觉重量几乎相等。上一轮评审把 `BigStat` 从 34sp 提到 44sp 是为了「主角级」，但**没有同步下调环内数字**，于是主角从 1 个变成 2 个。
- **整改**：
  - 续航（**真正的答案**，用户在评论里明确要的是「现在能开 X 公里」）→ 保持 **44sp / lineHeight 48sp / Bold / -0.4sp**；
  - 环内百分比 → **降为 28sp / lineHeight 32sp / Bold / -0.4sp**（从 40sp 降 30%），环直径不变；
  - 若坚持让环内数字也做主角，则反向把续航提到 **56sp / 60sp** 并把环降到 120dp —— **二者必须拉开 ≥ 25% 的字号差**，不能停在 4sp。
- **理由**：所有者硬约束「简洁：一屏一个主角数字，装饰宁删勿留」。

#### P1-2｜`Motion` token 收拢后，调用点仍写死旧数值（3 处值不一致 + 8 个死 token）

- **位置**：`Motion.kt:31-107`（定义）vs `Widgets.kt:414`（`tween(1200)`）、`Widgets.kt:1637`（`tween(1600)`）、`Charts.kt:57`（`tween(durationMillis = 520)`）、`AppRoot.kt:467`（`tween(140)`/`tween(120)`）
- **现状**：

| token（定义） | 定义值 | 调用点实际值 | 状态 |
|---|---|---|---|
| `Motion.Shimmer`（`Motion.kt:88`） | 1400 | `Widgets.kt:414` = **1200** | ❌ 不一致 |
| `Motion.TpmsPulse`（`Motion.kt:85`） | 2400 | `Widgets.kt:1637` = **1600** | ❌ 不一致（注释写明「原来 1600ms 是『喘气』不是『呼吸』」——**这个修复从未落地**） |
| `Motion.ChartGrow`（`Motion.kt:69`） | 520 | `Charts.kt:57` = 520 | ⚠️ 值同但未引用 |
| `Motion.ChartMorph`（`Motion.kt:72`） | 300 | — | ❌ 死 token |
| `Motion.TabFadeIn/TabFadeOut`（`Motion.kt:54-55`） | 140 / 120 | `AppRoot.kt:467` = 140 / 120 | ⚠️ 值同但未引用 |
| `Motion.EaseOut`（`Motion.kt:33`） | iOS ease-out | — | ❌ 死 token（见 P1-3） |
| `Motion.DataValue`（`Motion.kt:75`） | spring | — | ❌ 死 token |
| `Motion.TiltPress`（`Motion.kt:91`） | spring | — | ❌ 死 token |
| `Motion.Linear`（`Motion.kt:106`） | Linear | — | ❌ 死 token |

- **整改**：把上表「调用点实际值」全部改为引用 token；`TpmsPulse` 的调用点必须改成 **2400ms**；`Shimmer` 改成 **1400ms**；删掉 `ChartMorph`/`DataValue`/`TiltPress`/`Linear` 四个无调用 token（或补上调用）。
- **理由**：`Motion.kt:15-30` 声明「全部收进这里，一条原则」—— 现状是**名义上的收拢**。下一个改时长的人会改 `Motion.kt` 而调用点不变，漂移必然复发。

#### P1-3｜全 App 用 Material 的 `FastOutSlowInEasing`，iOS ease-out 从未使用

- **位置**：`Motion.kt:33` 定义 `EaseOut = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)`（**0 处调用**）；实际生效的是 `FastOutSlowInEasing`（≈ `cubic-bezier(0.4, 0, 0.2, 1)`，Material 标准曲线），出现在 `Widgets.kt:496,505,897,951,954`、`StatusScreen.kt:789-790`、`Charts.kt:57`、`SettingsScreen.kt:1416,1421` 等处
- **现状**：这套 App 的自称是「苹果式系统质感」，但**所有位移/透明度过渡用的都是 Material 的曲线**。`FastOutSlowInEasing` 前段慢—中段快—末段慢，在 iOS 用户感知里偏「弹了一下」。
- **整改**：
  - 新增 `Motion.EaseOut = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)` **并真正启用**；
  - 新增 `Motion.PushCurve = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)`（iOS `UINavigationController` push 实际曲线）；
  - 把所有**页面/卡片/图表**的过渡 `FastOutSlowInEasing` → `Motion.EaseOut`；
  - `FastOutSlowInEasing` 只保留给「按压反馈」（`Motion.PressIn/PressOut`），因为按压要的是「立刻到位」。
- **理由**：曲线的观感差异大于时长差异；这是「一眼 Material / 一眼 Apple」最容易被忽略的开关。

#### P1-4｜用户直接操作引起的动画用了 `tween`（4 处用反）

- **位置与现状**：

| 触发源 | 位置 | 现状 | 应为 |
|---|---|---|---|
| 按压缩放 / 变暗 | `Widgets.kt:492-507`（`Motion.PressIn` = `tween(60)` / `Motion.PressOut` = `tween(200)`） | tween | **spring**（用户直接操作） |
| 折叠箭头旋转 | `Widgets.kt:895-899`（`tween(Motion.ChevronRotate)` = 240ms） | tween | **spring** |
| 折叠区展开高度 | `Widgets.kt:950-955`（`tween(Motion.ExpandHeight)` = 280ms） | tween | **spring** |
| 续航「怎么算的」展开 | `StatusScreen.kt:789-790`（`tween(220)` / `tween(180)`） | tween | **spring** |

- **整改**（`Motion.kt` 内新增/改写）：
  - `PressIn = spring<Float>(dampingRatio = 1f, stiffness = 1400f)`（无回弹，约 70ms 收敛 —— 按下必须即时）
  - `PressOut = spring<Float>(dampingRatio = 0.9f, stiffness = 700f)`（回弹本身就是能被眼睛追踪的运动）
  - `ChevronRotate = spring<Float>(dampingRatio = 1f, stiffness = 500f)`（约 230ms）
  - `ExpandHeight = spring<Float>(dampingRatio = 1f, stiffness = 380f)`（约 280ms，无回弹；高度动画不能有橡皮筋）
  - `StatusScreen.kt:789-790` 改用 `Motion.ExpandHeight` + `Motion.ExpandFadeIn/FadeOut`，**顺带消除与 `Widgets.kt:950-955` 的数值漂移**（220/160/180/120 vs 280/200/140）。
- **理由**：`Motion.kt:23-29` 自己写明了「用户直接操作引起的状态变化 → spring；系统自动发生的时间过程 → tween」，并解释了「spring 被打断时会自然接管速度，连点不会出现速度断裂」。**这 4 处正是规则的反例**，而且是用户手指直接按的路径。

#### P1-5｜push 转场给新旧两页都加了淡入淡出 → 30% 视差的层次感被抹掉

- **位置**：`PushTransition.kt:35-44`
- **现状**：
  - 进入页：`slideInHorizontally { it } + fadeIn(tween(200))`
  - 退场页：`fadeOut(tween(160)) + slideOutHorizontally { -it * 3 / 10 }`
  - iOS 的 push 是**层级**动画：旧页**不淡出**，只向左退 30% 并被新页左缘的投影压住；新页**也不淡入**，它本来就是不透明的。淡入淡出把「叠了一层」拍平成「换了一张」。
  - 时长 380ms 偏长（iOS ≈ 350ms），曲线是 Material 默认（见 P1-3）。
- **整改**：
  - 进入页：`slideInHorizontally(tween(350, easing = Motion.PushCurve)) { it }`，**去掉 `fadeIn`**；
  - 退场页：`slideOutHorizontally(tween(350, easing = Motion.PushCurve)) { -it * 3 / 10 }`，**去掉 `fadeOut`**；
  - 反向 pop 完全对称；
  - `Motion.Push` 由 **380 → 350**。
- **理由**：所有者硬约束「push 有 30% 视差」—— 视差本身是对的，但**叠加上淡出后，30% 位移读起来只是「两张图在滑动」**，层次线索（投影）失去参照物。

#### P1-6｜11 处交互仍是原生 `.clickable` → M3 ripple + 无「变暗 + 轻缩放」

- **位置**（全部为裸 `Modifier.clickable`，未设 `indication = null`）：

| # | 位置 | 元素 | 触感 |
|---|---|---|---|
| 1 | `Widgets.kt:355` | `NoticeBanner` 可点横幅 | ✅ 有 |
| 2 | `Widgets.kt:670` | `SegmentedTabs` 每一段 | ✅ 有 |
| 3 | `Widgets.kt:738` | `CarChip` 顶栏芯片 | ✅ 有 |
| 4 | `Widgets.kt:777` | `CarChip` 弹层车辆行 | ✅ 有 |
| 5 | `Widgets.kt:1132` | `SecondaryTitleBar` 返回 | ✅ 有 |
| 6 | `RoutePlan.kt:743` | 线路余量表摘要行 | ❌ 无 |
| 7 | `RoutePlan.kt:765` | 展开箭头 | ❌ 无 |
| 8 | `RoutePlan.kt:813` | 「收起」文字 | ❌ 无 |
| 9 | `SettingsScreen.kt:637` | 「显示令牌」整行 | ❌ 无 |
| 10 | `SettingsScreen.kt:787` | 设置页车辆行 | ❌ 无 |
| 11 | `StatusScreen.kt:772` / `1199` | 「怎么算的 ▸」/「基准与间隔」 | ✅ 有 |

- **现状**：`Widgets.kt:516-520` 的注释明确写着「**禁用 Material ripple**……iOS 的 list row highlight 是**纯变暗**，没有扩散水波纹」—— 但 `pressable` 之外的 11 处全部是裸 `clickable`，**M3 的 ripple 依然在跑**。这与 AppRoot 移除胶囊指示器的决定（`AppRoot.kt:427-436`）自相矛盾。
- **整改**：
  - 把 `pressable` 里的 `indication = null` + 「变暗 + 轻缩放」逻辑**提到一个 `Modifier.iOSPressable(onClick)` 扩展**，内部已含 `Haptics.tick(ctx)`；
  - 上表 11 处全部改为 `Modifier.iOSPressable { ... }`；
  - `Widgets.kt:670`（分段控件）与 `Widgets.kt:1132`（返回）也要有按压缩放（目前分段控件的选中块已经在滑，**行本身还应有 0.97× 的轻缩放**）；
  - `AppRoot.kt:388` 的 `NavigationBarItem` 换成自绘行（`Row` + `iOSPressable` + `Motion.IconPop`），彻底去掉 M3 交互。
- **理由**：所有者硬约束「按压 = 变暗 + 轻缩放、必配触感反馈」；水波纹是「一眼 Material」的最强信号。

#### P1-7｜6 处可点元素零触感

- **位置与整改**（补 `Haptics`）：

| 位置 | 元素 | 建议触感 |
|---|---|---|
| `RoutePlan.kt:743` | 线路余量表摘要行（**记录页首屏第一个可点元素**） | `Haptics.tick(ctx)` 30ms/8 |
| `RoutePlan.kt:765` | 展开箭头（22dp） | `Haptics.tick(ctx)` |
| `RoutePlan.kt:813` | 「收起」 | `Haptics.tick(ctx)` |
| `SettingsScreen.kt:637` | 「显示令牌」整行 | `Haptics.selection(ctx)` 45ms/10（状态切换） |
| `SettingsScreen.kt:787` | 设置页车辆行（单选） | `Haptics.selection(ctx)` |
| `StatsScreen.kt:700` | `PeriodSegmented`（死代码，见 P1-11） | 随删除一并移除 |

- **理由**：`Haptics.kt:62-66` 已经把语义分好（`tick` = 列表项按下，`selection` = 状态切换），但只有 9 处调用，**6 处漏了**。触感缺失对视力正常的用户也不是「无感」—— 它是动作完成的确认。

#### P1-8｜`ErrorBanner` 图标垂直居中，在 200% 下把一句话拦腰截断

- **位置**：`Widgets.kt:302`（`ErrorBanner`：`verticalAlignment = Alignment.CenterVertically`）vs `Widgets.kt:364`（`NoticeBanner`：`Alignment.Top`）
- **现状**：同一个文件里两个横幅的图标对齐**不一致**。`NoticeBanner` 在 v0.20.0 P1-7 已被改成顶端对齐并注明「2 倍字号下正好把一句话拦腰截断（『该在里 | 程表 99,000 公里』）」，**`ErrorBanner` 从未同步**。
- **佐证**：`C:\tm-shots\r15\21-status-2x.png` 中红色保养横幅正是 `ErrorBanner`（旧构建），图标落在三行文字的**第二行**，把「该在里程表 99,000 公里时做」切成两半。
- **整改**：`Widgets.kt:302` 改为 `verticalAlignment = Alignment.Top`，并复用 `NoticeBanner` 图标的 `padding(top = 2.dp).size(18.dp)`（`Widgets.kt:372-374`）以保证两个横幅的图标基线一致。
- **理由**：同一组件族里两个近似横幅的对齐规则不一致 = 下次还会漂。

#### P1-9｜`PushEdgeShadow` 纯黑 @32% → 深色模式下完全不可见

- **位置**：`PushTransition.kt:52-62`（`Color.Black.copy(alpha = 0.32f)`）
- **现状**：深色模式下页面底是 `#000000`，**黑阴影压在黑页上 = 0 对比**，push 时「两层」的层次线索完全消失（浅色下则清晰可见）。宽度 16dp 的水平渐变在深色下等于不存在。
- **整改**：
  - 改用 `accents.shadowKey`（浅 `#1F000000` / 深 `#66000000`）作为渐变起点色；
  - 并在深色模式追加一条 **1dp 的浅色高光线**：`Color.White.copy(alpha = 0.14f)` 贴在渐变右缘（对应 iOS 的「新页左缘发丝线」）；
  - 宽度 16dp → **12dp**（深色下高光线更克制）。
- **理由**：层次感是「高级」的来源；同一个转场在两种模式下表现完全不同 = 深色模式退化。

#### P1-10｜记录页时间轴竖线 1.18:1（深色 1.45:1）→ 几乎不可见

- **位置**：`RecordsScreen.kt:543`（`railColor = MaterialTheme.colorScheme.outlineVariant`）、`RecordsScreen.kt:605-615`（2dp 竖线）
- **现状**：浅色 `#E0E0E5` on 页面底 `#F2F2F7` = **1.18:1**；深色 `#38383A` on `#1C1C1E` = **1.45:1**。这条线的全部意义就是「把充电与行程串成一条时间线」（`RecordsScreen.kt:427-432` 的设计意图），却几乎看不见。
- **整改**：
  - `railColor` 改用 `accents.chartTrack` 的 40% 叠加：`accents.chartTrack.copy(alpha = 0.45f)` → 浅色合成 `#B9B9BD`（对页面底 **1.82:1**）、深色 `#454549`（**1.63:1**）—— 作为**装饰性**分隔线这已足够（装饰线不受 1.4.11 约束，但需可辨）；
  - 若希望它承担「时间线」这一信息功能，则应升到 **3:1**：`accents.chartTrack` 原色（浅 `#77777D` 对底 4.45:1 / 深 `#6E6E76` 对卡片 3.37:1）—— 推荐这个，代价是视觉变重，建议**线宽 2dp → 1.5dp** 平衡。
- **理由**：这是「记录」页的核心视觉隐喻，当前实现等于没画。

#### P1-11｜`PeriodSegmented` 是 `SegmentedTabs` 的死代码副本，回归 4 处已修缺陷

- **位置**：`StatsScreen.kt:676-714`（**全项目 0 处调用**，实测 `grep PeriodSegmented` 只命中定义行 676）
- **现状**：这份副本把 `SegmentedTabs`（`Widgets.kt:596-702`）在历轮评审中修好的 4 件事**全部退回**：
  1. 选中块**硬切**（`StatsScreen.kt:699` `background(if (selected) selectedBg else Color.Transparent)`）—— `Widgets.kt:614-617` 已改为「滑过去」的 spring 指示块；
  2. 触控目标 **38dp**（`StatsScreen.kt:701` `padding(vertical = 9.dp)` + labelLarge 20sp）—— `Widgets.kt:674-678` 已改为 **44dp**；
  3. **无 `semantics { selected = }`** —— 读屏用户听不出哪一段被选中（`Widgets.kt:681-685` 已有）；
  4. **无 `maxLines = 1`** —— 200% 下标签折行并与选中胶囊贴死（`Widgets.kt:691-693` 已有）。
- **整改**：**直接删除 `StatsScreen.kt:675-714`**（用量页已在 `StatsScreen.kt:240` 使用正确的 `SegmentedTabs`）。
- **理由**：它现在无害，但它是**地雷** —— 下一个「用量页也要分段」的改动很可能直接复用它，把 4 个已修缺陷一次性带回来。

#### P1-12｜电量环 600ms 与能量条 500ms 不同步 → 同一个数用两种节奏到终点

- **位置**：`Widgets.kt:1356`（`tween(durationMillis = 600, easing = FastOutSlowInEasing)`，`label = "socRing"`）vs `Widgets.kt:1522`（`tween(500, easing = FastOutSlowInEasing)`，`label = "energyBar"`）
- **现状**：两者画的是**同一个 SOC 值**，却用不同时长（600 / 500）+ 不同起始帧（环是整数取整，能量条是浮点比例）。刷新数据时，环里的数字与能量条填充**先后到达终点**，读起来像两个数。
- **整改**：统一为 **500ms / `Motion.EaseOut`**，并在 `Motion.kt` 新增 `Motion.DataFill = tween(500, easing = Motion.EaseOut)` 供两处共用。
- **理由**：同一事实的两种视觉表达必须同频到达；这是最典型的「一眼看出是拼起来的」廉价感。

---

### 2.3 P2 —— 打磨项（10 条）

| # | 位置 | 现状 | 整改 | 理由 |
|---|---|---|---|---|
| P2-1 | `Widgets.kt:1477` | 环内数字 `letterSpacing = (-1.5).sp`（40sp 上 −3.75%） | **`-0.4.sp`**（与 `Widgets.kt:1264` 统一） | Apple 大字号数字追踪 ≈ −0.9% |
| P2-2 | `Widgets.kt:1266` | `BigStat` value `maxLines = 1` **且无 `overflow`** | 补 `overflow = TextOverflow.Ellipsis` | 无 overflow 时超长值被**硬裁**，连省略号都没有 |
| P2-3 | `Widgets.kt:1512-1518` + `StatusScreen.kt:588-592` | 能量条与电量环**表达同一个 SOC**（环给 69%，条也给 69%） | 条只保留「目标刻度」语义：填充段画 **soc→target 的差值**并用 40% 底纹表示已充区间；或直接删除能量条 | 装饰宁删勿留；「环给数字、条给目标」在实现上没做到 |
| P2-4 | `ChargeListPage.kt:95-106` | 主角 **1737** 之下第一行是「直流 **1685** 度 · 65 次」，与主角重复 97% | 副行只留场次：「直流 **65** 次 · 交流 **4** 次」；度数差（1685 vs 52）下沉到第二行 | 主角必须是**别处拿不到**的数 |
| P2-5 | `AppRoot.kt:410-414` | Tab 选中图标 `scale 1.08` + `Motion.IconPop`（`dampingRatio = 0.8f` → **有回弹**） | `scale 1.04` + `spring(dampingRatio = 1f, stiffness = 900f)`（无回弹） | iOS Tab 选中只变色；回弹弹簧读作「玩具感」 |
| P2-6 | `Widgets.kt:913` / `Widgets.kt:742` | 折叠行触控目标 **43dp**（14+14+15）；`CarChip` 芯片 **28dp**（6+6+16） | 折叠行 `padding(vertical = 15.dp)` → **45dp**；`CarChip` 芯片 `padding(vertical = 10.dp)` → **36dp**，并给芯片外层加 `minimumInteractiveComponentSize` | WCAG 2.5.8（AA）要 24dp ✓，但 iOS HIG 与 Apple Design Resources 的推荐值是 44pt |
| P2-7 | `DrivesScreen.kt:541` | 地图按钮 `Row(Modifier.height(44.dp))` **硬约束** | 改 `heightIn(min = 44.dp)` | 200% 时 labelLarge 行高 40dp，44dp 只剩 2dp 余量 |
| P2-8 | `Charts.kt:100,116,311,502,567,838,900,1069,1117,1141,1158`、`Widgets.kt:1532,1585` | Canvas 内文字统一 **9.sp**（部分 10/11sp），低于 iOS 最小 caption | 统一 **11.sp** + `fontFeatureSettings = "tnum"`；同时把图表内的**数值刻度**加 `tnum` | 9sp 在 1× 下已经偏小；轴刻度数字变化时左右抖 |
| P2-9 | `Theme.kt:413` | `Modifier.hazeSource(state)` 全项目 0 调用 | **删除**（或加注释：仅限根容器，不得在 `haze` 源内部使用） | 它是唯一能造成 haze 父子违规的 API；留着就等着被误用 |
| P2-10 | `AppRoot.kt:388-437` | `NavigationBarItem` 仍是 M3 组件（`indicatorColor = Transparent` 只去掉胶囊，**ripple 仍在**） | 换成自绘 `Row`：图标 + 文字 + `iOSPressable`，无 ripple | 与 P1-6 同源；底栏是全 App 使用频次最高的表面 |

---## 3. 动效规格表（逐处）

**判定原则**（`Motion.kt:23-29` 已写明，本次评审沿用）：
- 用户**直接操作**引起的状态变化 → **spring**（可被打断时自然接管速度）
- 系统**自动发生**的时间过程 → **tween**（电量变化、图表生长、骨架屏、旋转）
- **3D 几何旋转只用于「按下瞬间」与「单个主角元素」**；静态列表卡片一律 0°
- `cameraDistance = 3 × 元素宽度(px)`（**单位是像素**，`Motion.kt:118-148` 的推导正确，本轮无异议）

| # | 触发源 | 位置 | 类型 | 时长 | 曲线 | 3D | 触感 | 整改建议 |
|---|---|---|---|---|---|---|---|---|
| M1 | 按下（变暗 + 缩放） | `Widgets.kt:492-507` | ❌ tween | 60 / 200ms | `FastOutSlowInEasing` | 无 | `tick` 30ms/8 | 改 **spring**：`PressIn = spring(dampingRatio=1f, stiffness=1400f)`、`PressOut = spring(dampingRatio=0.9f, stiffness=700f)`（P1-4） |
| M2 | 分段控件指示块滑动 | `Widgets.kt:618-622` | ✅ spring | ~230ms | `damping=1f, stiffness=400f` | 无 | `selection` 45ms/10 | ✅ 保持 |
| M3 | 分段控件文字色/字重 | `Widgets.kt:653-666` | ✅ tween | 180ms | `FastOutSlowInEasing` | 无 | — | 曲线改 `Motion.EaseOut`（P1-3） |
| M4 | Tab 切换（页面交叉淡入淡出） | `AppRoot.kt:463-469` | ✅ tween | 140 / 120ms | 默认（Material） | 无 | `selection` | 数值正确但**未引用 `Motion.TabFadeIn/TabFadeOut`**（P1-2）；曲线改 `Motion.EaseOut`；**保持无位移** ✅ |
| M5 | Tab 图标选中微动 | `AppRoot.kt:410-414` | ✅ spring | — | `damping=0.8f`（**有回弹**） | 无 | — | `scale 1.08 → 1.04`、`damping → 1f`（P2-5） |
| M6 | push 推入 | `PushTransition.kt:35` | ❌ tween | 380ms | 默认 | 无 | — | **350ms + `Motion.PushCurve`**；去掉 `fadeIn`（P1-5） |
| M7 | push 旧页 30% 视差 | `PushTransition.kt:37` | ✅ tween | 380ms | 默认 | 无 | — | 350ms + `PushCurve`；去掉 `fadeOut`（P1-5） |
| M8 | push 新页左缘投影 | `PushTransition.kt:52-62` | ❌ 静态 | — | — | 无 | — | 深色下不可见 → 用 `accents.shadowKey` + 1dp 高光线（P1-9） |
| M9 | 折叠区展开/收起 | `Widgets.kt:948-955` | ❌ tween | 280 / 200 / 140ms | `FastOutSlowInEasing` | 无 | `tick` | 高度改 **spring** `damping=1f, stiffness=380f`（P1-4） |
| M10 | 折叠箭头旋转 0↔180° | `Widgets.kt:895-899` | ❌ tween | 240ms | `FastOutSlowInEasing` | 无 | `tick` | 改 **spring** `damping=1f, stiffness=500f`（P1-4） |
| M11 | 续航「怎么算的」展开 | `StatusScreen.kt:789-790` | ❌ tween | 220 / 160 / 180 / 120ms | `FastOutSlowInEasing` | 无 | `tick` | 改用 `Motion.ExpandHeight/ExpandFadeIn/ExpandFadeOut`，消除数值漂移（P1-4） |
| M12 | 开关滑块位移 | `SettingsScreen.kt:1411-1418` | ❌ tween | 180ms | 默认 | 无 | `selection` | 用户操作 → 改 **spring** `damping=1f, stiffness=600f`（≈180ms 收敛） |
| M13 | 开关轨道色 | `SettingsScreen.kt:1419-1423` | ✅ tween | 180ms | 默认 | 无 | — | 与 M12 同步启动即可；曲线改 `Motion.EaseOut` |
| M14 | 电量环弧线 + 数字 | `Widgets.kt:1354-1358` | ✅ tween | 600ms | `FastOutSlowInEasing` | 无（**正确：环不给几何旋转**，改用 2D 伪 3D 高光/暗弧 ✅） | — | 改 **500ms + `Motion.EaseOut`**，与 M15 同频（P1-12） |
| M15 | 能量条填充 | `Widgets.kt:1520-1524` | ✅ tween | 500ms | `FastOutSlowInEasing` | 无 | — | 与 M14 统一为 500ms + `Motion.EaseOut`（P1-12） |
| M16 | 图表首次描边生长 | `Charts.kt:57` | ✅ tween | 520ms | `FastOutSlowInEasing` | 无 | — | 数值正确但**未引用 `Motion.ChartGrow`**（P1-2）；曲线改 `Motion.EaseOut` |
| M17 | 刷新图标旋转 | `StatusScreen.kt:236-254` | ✅ tween | 700ms/圈 + 200ms 回正 | `LinearEasing` / `FastOutSlowInEasing` | 无 | `tick` | ✅ 正确（先 `snapTo` 再续跑，消除角度跳变） |
| M18 | 骨架屏流光 | `Widgets.kt:408-420` | ✅ tween | **1200ms** | `LinearEasing` | 无 | — | 改为引用 `Motion.Shimmer` = **1400ms**（P1-2）；共享同一 transition ✅ 正确 |
| M19 | 胎压呼吸 | `Widgets.kt:1632-1641` | ✅ tween | **1600ms** | `FastOutSlowInEasing` | 无 | — | 改为引用 `Motion.TpmsPulse` = **2400ms**（P1-2）——注释里说的「1600 是喘气不是呼吸」**至今未落地** |
| M20 | 地图浮标按下 3D 前倾 | `DrivesScreen.kt:523-537` | ✅ | — | — | ✅ `rotationX = -2f`（≤2.5° ✅）、`cameraDistance = 3 × 宽(px)` ✅ | `tick` | ✅ **全 App 唯一的 3D 用法，位置正确**（按下瞬间 + 单个浮动物）。静态列表卡片 0° ✅ |
| M21 | 主角数字滚动 | `Motion.kt:75-78` | spring | — | — | — | — | ❌ **死 token**：`BigStat` 完全没有数字滚动；建议接入 `Motion.DataValue`（spring `damping=1f, stiffness=350f`）让续航数字随刷新滚动 |
| M22 | 线路余量表展开 / 收起 | `RecordsScreen.kt:466-485` | ❌ 硬切 | 0 | — | 无 | ❌ 无 | 用 `AnimatedVisibility` + `Motion.ExpandHeight`（spring）+ `Haptics.tick`（P1-4 / P1-7） |

### 3.1 3D 与毛玻璃专项结论

- ✅ **3D 用法正确**：全项目仅 `DrivesScreen.kt:534-537` 一处几何旋转，用于「按下瞬间的地图浮动物」，`rotationX = -2f` 在 ≤2.5° 内，`cameraDistance` 由 `Tilt3DState` 强制为 **3 × 自身宽（px）**（`Motion.kt:150-168`）。静态列表卡片**无任何几何旋转** ✅。电量环改用 2D 伪 3D（白 @18% 高光弧 + 黑 @10% 暗弧，`Widgets.kt:1410-1437`）✅ 正确取舍。
- ✅ **haze 用法正确**：`Modifier.haze`（源）挂在 `AppRoot.kt:462` 的内容区 `AnimatedContent` 上，`hazeChild`（效果）挂在 `AppRoot.kt:343` 的底栏 `glassSurface(realBlur = true)` 上 —— 二者是 `Scaffold` 布局内的**兄弟节点**，未违反 `haze`/`hazeChild` 不可父子约束 ✅。
- ⚠️ **唯一隐患**：`Theme.kt:413` 的 `Modifier.hazeSource(state)` 死助手（0 调用），若被人在 haze 源内部使用会直接触发运行时异常 → P2-9 建议删除。
- ⚠️ **假毛玻璃的代价**：`Widgets.kt:141-158` 的 `glassSurface(realBlur = false)` 走「半透明 + 渐变描边」路线（卡片走这条路，材质分层由 `cardSurface`/`cardInset` 实色承担 ✅）。这符合所有者「层次靠材质不靠描边」的要求，但**深色下卡片实色 `#1C1C1E` 与底 `#000000` 只有 1.23:1**，层级主要靠 0.5dp 白描边撑住 —— 建议在深色下把 `cardStroke` 由 `#24FFFFFF` 提到 **`#2EFFFFFF`**（并同步 `cardStrokeEnd` 提到 `#12FFFFFF`）。

---

## 4. 200% 字号下的破坏点清单

**判定口径**：WCAG 1.4.4（放大 200% 不丢内容、不重叠）+ 1.4.10（可调整而无需双向滚动）+ 所有者「必须支持 200%」。
`AppRoot.kt:183-198` 已**完全放开 fontScale**（不再覆盖 `LocalDensity`）—— 所以下列问题现在**真的会发生在用户设备上**。

| # | 位置 | 现状 | 200% 下的现象 | 整改 |
|---|---|---|---|---|
| B1 | `Widgets.kt:1367` + `1473-1479` | 环直径封顶 1.75×，数字按真实 fontScale 放大 | 「100%」撑破圆环内圈 | 数字字号由环直径推导（P0-3） |
| B2 | `Widgets.kt:1230-1236` | `InfoTile` value `maxLines = 1` | 温度/功率读数被省略号吃掉 → **数据丢失** | `maxLines = 2`（P0-4） |
| B3 | `Charts.kt:236/403/725/977` | 刻度带按 `coerceAtMost(1.6f)`，文字按真实 fontScale | Y 轴标签被左边界裁掉；X 轴标签压进曲线 | 预留带与文字同系数 + 减少刻度数（P0-5） |
| B4 | `Widgets.kt:1266` | `BigStat` value `maxLines = 1` **无 overflow** | 主角数值（如 `1,737`）被硬裁，连省略号都没有 | 补 `overflow = Ellipsis`（P2-2） |
| B5 | `Widgets.kt:302` | `ErrorBanner` 图标垂直居中 | 三行横幅文字被图标拦腰切断 | 改 `Alignment.Top`（P1-8） |
| B6 | `Widgets.kt:688-693`（`SegmentedTabs`） | 已修 ✅（`maxLines=1` + `softWrap=false` + 44dp） | — | ✅ 保持；但 `StatsScreen.kt:676-714` 的副本**未修** → 删除（P1-11） |
| B7 | `StatusScreen.kt:530-555` | 大字号分支切上下排 + `baseSizeDp = 120` | 环 210dp + 上方主角与说明 → **整个环落到折叠线以下**，首屏看不到电量 | 大字号分支的 `baseSizeDp` 提到 **132dp**，并把「留 20% 保底还能开 X 公里」（`StatusScreen.kt:707-713`）在 `fontScale ≥ 1.5` 时**下沉到环之后** |
| B8 | `Widgets.kt:913` | 折叠行 `padding(vertical = 14.dp)` | 文字行高 15sp×2 = 30dp，行高 58dp —— 尚可 | ✅ 保持；触控目标补到 45dp（P2-6） |
| B9 | `DrivesScreen.kt:541` | 地图按钮 `Row(.height(44.dp))` 硬约束 | 文字行高 20sp×2 = 40dp，**只剩 2dp 余量**，再加内边距即裁切 | 改 `heightIn(min = 44.dp)`（P2-7） |
| B10 | `Widgets.kt:1666` | `VehicleTopView` 的 Canvas **固定 200.dp** | 俯视图内的胎压读数（Canvas 内绘制）放大后与车身边缘重叠 | Canvas 高度改为 `200.dp` → `200.dp + (fontScale - 1) × 60.dp`，或把四个读数移出 Canvas 改用 `Row` + `Text` |
| B11 | `Widgets.kt:1532/1585` | 能量条「目标 80%」标签 9sp | 18sp 等效，但**条带高度不随之增加** → 标签压到条上 | 已按实测文字高度算 `labelBandDp` ✅ **正确**；但字号需从 9sp 提到 11sp（P2-8） |
| B12 | `SettingsScreen.kt:672-718` | 按钮**纵向**排列 + `heightIn(min = 44.dp)` | ✅ 200% 下不再裁字（v0.20.0 已修） | ✅ 保持 —— `r15/25-settings-2x.png` 的「仅测试连」是**旧构建** |

**汇总**：200% 下**仍会崩的 5 处** = B1、B2、B3、B4、B5；**B7 属首屏可用性问题**；其余已合规。

---

## 5. 统一 token 建议表（可直接落地）

### 5.1 色彩

**浅色**

| 语义 | 当前 | 建议 | 实测比值 | 备注 |
|---|---|---|---|---|
| 页面底 background | `#F2F2F7` | `#F2F2F7` 保持 | — | iOS systemGroupedBackground ✅ |
| 卡片面 cardSurface | `#FFFFFF` | `#FFFFFF` 保持 | — | ✅ |
| 内凹块 cardInset | `#EBEBF0` | `#EBEBF0` 保持 | 14.32:1 vs `#1C1C1E` | ✅ |
| 描边 cardStroke / cardStrokeEnd | `#14000000` / `#08000000` | 保持 | — | ✅ |
| 强调 primary | `#D70012` | `#D70012` 保持 | 5.39:1 | ✅ |
| 能量蓝 energyBlue | `#2F5FD0` | `#2F5FD0` 保持 | 5.72:1 | ✅ |
| 充电绿 chargeGreen | `#1E9E62` | `#1E9E62` 保持（**仅图形**） | 3.43:1 | 文字禁用 |
| 充电绿文字 chargeGreenText | `#0F6B41` | **`#0F6B41`** 扩用到**所有淡绿底上的图标/文字**（含时间轴节点） | 6.56:1 / 淡绿底 4.87:1 | P0-2 |
| 琥珀 warnAmber | `#9A5B00` | `#9A5B00` 保持 | 5.43:1 | ✅ |
| 危险 danger | `#D13438` | `#D13438` 保持 | 4.93:1 | ✅ |
| 能量紫 energyViolet | `#6B4FBF` | `#6B4FBF` 保持 | 6.01:1 | ✅ |
| 图表轨道 chartTrack | `#77777D` | `#77777D` 保持 | 4.45:1 | ✅ |
| 参考线 chartBaseline | `#8C939B` | `#8C939B` 保持 | 3.11:1 | 图形 ✅ |
| **开关开 switchOn** | *（无 token，硬编码 `#34C759`）* | **新增 `#2E9E4F`** | 白旋钮 **3.43:1** | P0-1 |
| **开关关 switchOff** | *（借用 chartTrack）* | **新增 `#8E8E93`** | 白旋钮 **3.26:1** | P0-1 |
| **开关旋钮 switchKnob** | *（硬编码 `Color.White`）* | **新增 `#FFFFFF` / 深 `#F2F2F7`**，描边 `cardStroke` | — | P0-1 |
| **低电量底 lowBatteryContainer** | *（无）* | **新增 `#FFF4E5`**，文字 `#9A5B00` | 9.5:1 | P0-6 |
| **极低电量底 criticalContainer** | *（复用 errorContainer）* | **新增 `#FFE1DE`**，文字 `#410002` | 13.93:1 | P0-6 |

**深色**

| 语义 | 当前 | 建议 | 实测比值 |
|---|---|---|---|
| 页面底 / 卡片面 / 内凹 | `#000000` / `#1C1C1E` / `#0B0B0D` | 保持 | ✅ |
| 卡片描边 cardStroke / cardStrokeEnd | `#24FFFFFF` / `#0AFFFFFF` | **`#2EFFFFFF` / `#12FFFFFF`** | 深色下卡片与底只有 1.23:1，描边承担层级 |
| 强调 primary | `#FF453A` | 保持 | 4.99:1 on `#1C1C1E` ✅ |
| 能量蓝 energyBlue | `#5B8DEF` | 保持 | 6.50:1 ✅ |
| 充电绿文字 chargeGreenText | `#5FD69A` | 保持，扩用到时间轴节点 | 9.38:1 ✅ |
| 琥珀 / 危险 | `#F5A623` / `#FF6B6E` | 保持 | 10.36:1 / 6.14:1 ✅ |
| 开关开 switchOn | `#34C759` | **`#2FA84F`**（白旋钮 3.31:1） | P0-1 |
| 开关关 switchOff | `#6E6E76` | 保持 | 白旋钮 5.05:1 ✅ |

### 5.2 字号 / 行高 / 字距

| 角色 | 字号 | 行高 | 字重 | 字距 | tnum | 用途 |
|---|---|---|---|---|---|---|
| **Hero 主角** | 44sp | 48sp | Bold | −0.4sp | ✅ | 续航、充入度数、里程 |
| **次主角（环内）** | **28sp** | 32sp | Bold | −0.4sp | ✅ | 电量百分比（**由 40sp 下调**，P1-1） |
| 页面大标题 | 34sp | 41sp | Bold | 0 | ✅ | 「车况」「记录」 |
| 卡片标题 | 20sp | 25sp | SemiBold | 0 | ✅ | — |
| 行内小标题 | 17sp | 22sp | SemiBold | 0 | ✅ | — |
| 正文 | 17sp | 22sp | Regular | 0 | ✅ | — |
| 次级正文 | 15sp | 20sp | Regular | 0 | ✅ | — |
| 脚注 | 13sp | 18sp | Regular | 0 | ✅ | — |
| 标签 | 12–15sp | 15–20sp | Medium | 0 | ✅ | — |
| **图形内文字** | **11sp**（原 9sp） | — | Medium | 0 | **✅ 新增** | 图表刻度 / 能量条目标标签（P2-8） |

> **规则**：所有数字（正文、标签、图形内刻度）一律 `fontFeatureSettings = "tnum"`。`BigStat`、电量环、Canvas 刻度三处是历史上最容易漏的地方。

### 5.3 间距与圆角

| 项 | 值 |
|---|---|
| 页面水平边距 | 16dp |
| 卡片内边距 | 16dp（`SectionCard`）/ 14dp（`InfoTile`）/ 12dp（`SettingRow` 竖向） |
| 卡片间距 | 12dp（页面纵向）/ 8dp（列表项） |
| 分段控件内边距 | 上下 12dp、左右 4dp（**触控目标 ≥44dp**） |
| 触控目标下限 | **44dp**（iOS HIG）；下限 24dp 为 WCAG 2.5.8 底线 |
| 卡片圆角 | 20dp |
| 内凹块圆角 | 14dp |
| 芯片圆角 | 8dp（标签）/ 14dp（车辆芯片）/ 50%（状态胶囊） |
| 描边 | 0.5dp 渐变（发丝线）；选中/焦点态 2dp 主色 ring |

### 5.4 动效时长与曲线

| token | 值 | 曲线 | 用途 |
|---|---|---|---|
| `Motion.EaseOut` | — | `CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)` | **页面/卡片/图表过渡的统一曲线（当前 0 调用，必须启用）** |
| `Motion.PushCurve` | 350ms | `CubicBezierEasing(0.32f, 0.72f, 0f, 1f)` | push / pop |
| `Motion.PressIn` | ~70ms | `spring(damping=1f, stiffness=1400f)` | 按下 |
| `Motion.PressOut` | ~180ms | `spring(damping=0.9f, stiffness=700f)` | 松手 |
| `Motion.SelectMove` | ~230ms | `spring(damping=1f, stiffness=400f)` | 分段控件指示块 |
| `Motion.ExpandHeight` | ~280ms | `spring(damping=1f, stiffness=380f)` | 展开/收起高度 |
| `Motion.ChevronRotate` | ~230ms | `spring(damping=1f, stiffness=500f)` | 折叠箭头 |
| `Motion.DataFill` | 500ms | `tween(Motion.EaseOut)` | 电量环 + 能量条（**同一数值同频**） |
| `Motion.ChartGrow` | 520ms | `tween(Motion.EaseOut)` | 图表生长 |
| `Motion.TabFadeIn / TabFadeOut` | 140 / 120ms | `tween(Motion.EaseOut)` | Tab 交叉淡入淡出（**无位移**） |
| `Motion.TpmsPulse` | 2400ms | `tween(EaseOut)` | 胎压呼吸（**当前调用点是 1600ms**） |
| `Motion.Shimmer` | 1400ms | `LinearEasing` | 骨架屏（**当前调用点是 1200ms**） |
| `Motion.RefreshSpin / Settle` | 700 / 200ms | `Linear` / `tween(EaseOut)` | 刷新图标 ✅ 已正确 |
| `Motion.IconPop` | — | `spring(damping=1f, stiffness=900f)` | Tab 图标（scale 1.04，无回弹） |

### 5.5 3D 规格（不可协商）

```
rotationX / rotationY  ≤ 2.5°        （当前唯一用法：DrivesScreen.kt:536，-2°）
cameraDistance         = 3 × 元素宽度(px)   （Tilt3DState，Motion.kt:154）
静态列表卡片            = 0°
3D 使用范围             = 仅「按下瞬间」+「单个主角元素」
```

---

## 6. 落地顺序建议

| 批次 | 内容 | 理由 |
|---|---|---|
| **第一批（硬指标）** | P0-1、P0-2、P0-6（三处对比度/颜色语义） | 改动小、无布局影响，直接消掉 3 条 WCAG 失败 |
| **第二批（200%）** | P0-3、P0-4、P0-5 + B7 | 集中在 `Widgets.kt` 与 `Charts.kt` 的 `fontScale` 处理，可一次性重构 |
| **第三批（交互一致性）** | P1-6（11 处改 `iOSPressable`）+ P1-7（6 处补触感）+ P1-2（token 归位） | 这三项同源，一次扫一遍代码最省事 |
| **第四批（动效曲线）** | P1-3、P1-4、P1-5、P1-12 | 统一曲线与 spring/tween 归类，改动集中在 `Motion.kt` 与 8 个调用点 |
| **第五批（层级打磨）** | P1-1、P1-8、P1-9、P1-10、P1-11 + P2 全部 | 视觉层，可与真机截图对照迭代 |

---

## 7. 已知不确定项

1. **截图时效**：`r15/`、`r17/` 落后当前代码 1–6 个版本（见 §0.1）。本报告中凡以截图为唯一依据的结论，均已标注代码位置并以代码为准；**建议落地后重新截取 r18 全量截图**（含 1× / 2× × 深浅 × 4 Tab × 2 个二级页）复核。
2. **真机对比度**：本报告的对比度均为**色值数学计算**，未考虑实际渲染中的半透明叠加、阴影、渐变与图片叠加（例如 `glassSurface` 的 `alphaLight/alphaDark` 会改变实际背景）。深色下 `glassSurface(Bar, alpha=0.72)` 的底栏区域需在真机截图上二次测量。
3. **`Charts.kt` 的 13 处 Canvas 文字**在 200% 下的真实溢出量依赖具体 `niceTicks` 输出，报告给出的是**结构性结论**（预留带与文字不同系数），具体溢出像素需真机核对。
4. **`app-android/` 工作区镜像与 git 跟踪源码是否一致**未做校验；本报告的行号以当前工作区文件为准。
5. **`docs/adr.md` 的既有决策**未逐条比对，若其中已就某项（例如 push 时长、曲线）另有约定，应以 ADR 为准并同步更新本报告。