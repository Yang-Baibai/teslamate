package com.teslamate.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze

/**
 * 设计语言 v2（2026-10-24 重做）
 *
 * 目标：**苹果式系统质感** —— 简洁、直观、高级、毛玻璃、3D。
 * 参照 iOS 的三条底层原则：
 *  1. **层次靠材质，不靠描边和阴影**（对应：surfaceContainer* 色阶 + 毛玻璃）
 *  2. **强调色只能有一个**（此前 primary 红 + tertiary 青两个强调色打架，已收敛）
 *  3. **数字用等宽字形**（tabular figures），否则 33%→34% 时数字宽度变化会左右抖
 */

// ── 品牌与图表色（不占用 M3 语义角色，避免和 primary/tertiary 混用）──
@Immutable
data class AccentColors(
    /** 特斯拉红：强调 / 危险 / 品牌点缀（**不用于表达健康电量**） */
    val teslaRed: Color,
    /** 红上的高光（3D 感） */
    val teslaRedLight: Color,
    /** 能量蓝：电量、功率、SOC 曲线 —— 「正常」的统一表达 */
    val energyBlue: Color,
    /** 充电绿：仅表示「正在充电」 */
    val chargeGreen: Color,
    /**
     * 充电绿的**文字/图标专用**色。
     *
     * ⚠️ v0.9.2（苹果视觉评审 P0-1）：`chargeGreen` #1E9E62 画在
     * 「直流快充」芯片底色（绿 @16% ≈ #DBEFE6）上只有 **2.85:1** ——
     * 文字要 4.5:1、图形要 3:1，**两边都不达标**。
     *
     * 所以**不改动** [chargeGreen] 本体（它是图标/进度条填充，3:1 已达标），
     * 而是另开一个「压在淡绿底上的绿」：
     *   浅 #0F6B41 on #D9E7E0 = **5.17:1** ✓
     *   深 #5FD69A on #273A32 = **6.50:1** ✓
     */
    val chargeGreenText: Color,
    /** 琥珀：电量 < 20%（浅色下用深一档，保证 ≥3:1） */
    val warnAmber: Color,
    /** 危险红：电量 < 10%、胎压异常 */
    val danger: Color,
    // ⚠️ v0.14.0（苹果 HIG P0-2）：**删除 `fastCharge` / `slowCharge`**。
    //
    // 这两个 token 全项目 **0 引用**（死 token），而且 `fastCharge` 的值是
    // 品牌红 #D70012 —— 下次有人拿它画「直流快充」，
    // 就会在「红色只表示告警 + 品牌点缀」的语义规则上再犯一次。
    // 直流快充的正确颜色是**能量蓝**（与图表里的直流柱同 token），
    // 交流慢充是中性灰，两者都已有现成 token，直接用即可。
    /**
     * 图表网格线 / 轨道 / 空槽。
     *
     * ⚠️ **v0.7.1 提亮**：原来深色 #26262A（对黑底 1.39:1）、浅色 #E6E8EB（对白 1.23:1），
     * 远低于 WCAG 1.4.11 要求的 3:1 —— 意味着"满格量程"这件事**根本读不出来**。
     * 现在提到 #5A5A5E（深 3.1:1）/ #8E8E93（浅 3.3:1）。
     */
    val chartTrack: Color,
    /**
     * 图表参考线（中位数/基准）。
     *
     * ⚠️ v0.14.0（苹果 HIG P1）：浅色原来 #9AA1A8 在白底上只有 **2.78:1** ——
     * 参考线属于**非文字图形**，WCAG 1.4.11 要求 ≥3:1，不达标。
     * 现在提到 #8C939B（白底 **3.31:1** ✓）。
     */
    val chartBaseline: Color,
    /**
     * 能量紫：**行驶耗电**专用色。
     *
     * ⚠️ **v0.20.0（车主 P1-2）新增**。
     *
     * `StatsScreen.kt` 里「行驶耗电（度）」这张图的注释白纸黑字写着
     * 「现在用**能量紫**」—— 但代码是 `colorScheme.tertiary`，
     * 而 tertiary 在 v0.15.0 被**收敛成中性冷灰蓝** #4A5760。
     * 交流慢充图例是 `secondary` #3A4750 —— 两者只差一档明度，
     * 肉眼看几乎是同一个深灰。
     *
     * 车主原话：「我在用量页扫三张图，第三张的柱子和我上一张图图例里的
     * 「交流慢充」是同一个深灰色，我一度以为下面那张也是充电数据。」
     *
     * 不能改 `tertiary`（它被收敛正是为了避免第二个强调色），
     * 所以另开一个**独立色相**的 token：
     *   浅 #6B4FBF 对白 **6.15:1** ✓
     *   深 #A98BF0 对 #1C1C1E **7.24:1** ✓
     */
    val energyViolet: Color,
    /** 玻璃面（半透明底色） */
    val glassTint: Color,
    /** 玻璃面上的高光描边（顶部 1px） */
    val glassHighlight: Color,
    /** 卡片投影的环境光 */
    val shadowAmbient: Color,
    /** 卡片投影的主光 */
    val shadowKey: Color,
    /**
     * 卡片面（**不透明**）—— iOS grouped 层级三级纵深的第一级。
     *
     * ⚠️ 之前没有这个 token，SectionCard 走的是「半透明黑/白叠在页底上」：
     * 深色下黑 @55% 叠在 #000000 页底 = **还是 #000000**，卡片与背景 1.00:1，
     * 「深色改纯黑分层」在卡片上完全没生效 —— 全 App 结构塌成「黑底黑卡 + 一根白边」。
     * 浅色下白 @55% 叠在 #F7F7F9 = #FBFBFC，卡片 vs 背景 1.03:1，层次同样归零。
     * 现在卡片面用**实色**，层级一次成型。
     */
    val cardSurface: Color,
    /** 卡片内凹块：比卡片面「凹」一级（InfoTile 等内嵌内容） */
    val cardInset: Color,
    /** 卡片描边（渐变头尾两端） */
    val cardStroke: Color,
    /** 卡片描边（渐变尾端） */
    val cardStrokeEnd: Color,
)

private val LightAccents = AccentColors(
    teslaRed = Color(0xFFD70012),
    teslaRedLight = Color(0xFFFF6B6B),
    energyBlue = Color(0xFF2F5FD0),
    chargeGreen = Color(0xFF1E9E62),
    chargeGreenText = Color(0xFF0F6B41),
    // ⚠️ v0.20.0（a11y 评审 P1-5）：#B26A00 → #9A5B00
    // 原值对白只有 **4.24:1**，低于文字 AA 要求的 4.5:1 ——
    // 而它是用在**文字**上的：保养卡「还有 N 公里」、
    // 线路余量表「到达时剩 16%」、行程电量的琥珀数值、图表的保底标注。
    // 新值对白 **5.43:1**，对卡片内凹块 #EBEBF0 也有 4.57:1。
    warnAmber = Color(0xFF9A5B00),
    danger = Color(0xFFD13438),
    chartTrack = Color(0xFF77777D),
    // ⚠️ v0.14.0（苹果 HIG P1）：#9AA1A8 → #8C939B
    // 参考线是非文字图形，WCAG 1.4.11 要求 ≥3:1；原值在白底只有 2.78:1。
    chartBaseline = Color(0xFF8C939B),
    energyViolet = Color(0xFF6B4FBF),
    glassTint = Color(0x99FFFFFF),
    glassHighlight = Color(0x66FFFFFF),
    // ⚠️ v0.14.0（苹果 HIG P1）：**单光源方向写反了**。
    // 原来 shadowAmbient(#14000000, 8%) > shadowKey(#0A000000, 4%) ——
    // 环境光比主光还强，阴影就没有方向，正是「Material 味」的来源。
    // 现在环境光 4%、主光 12%，光源定在左上。
    shadowAmbient = Color(0x0A000000),
    shadowKey = Color(0x1F000000),
    // iOS grouped 浅色三级：页面底 #F2F2F7 → 卡片面 #FFFFFF → 内凹 #EBEBF0
    cardSurface = Color(0xFFFFFFFF),
    // ⚠️ v0.9.2（苹果视觉评审 P1-3）：内凹块原来 #F2F2F4，与卡片面 #FFFFFF 的
    // ΔL* 只有约 2.6（肉眼几乎同色）—— 于是内凹的可见性**完全由那圈 0.5dp 描边承担**，
    // 正好违反「层次靠材质不靠描边」。提到 #EBEBF0 后 ΔL* ≈ 6.8，
    // 文字 #1C1C1E on #EBEBF0 = 14.3:1 ✓，深浅两侧的层级强度也终于对等
    // （深色 #1C1C1E vs #0B0B0D 的 ΔL* = 7.4）。
    cardInset = Color(0xFFEBEBF0),
    // 浅色下描边必须**是黑不是白** —— 白描白卡等于没有边界（之前就是白 55%，肉眼全隐形）
    cardStroke = Color(0x14000000),
    cardStrokeEnd = Color(0x08000000),
)

private val DarkAccents = AccentColors(
    teslaRed = Color(0xFFFF453A),
    teslaRedLight = Color(0xFFFF8A80),
    energyBlue = Color(0xFF5B8DEF),
    chargeGreen = Color(0xFF2FBF71),
    chargeGreenText = Color(0xFF5FD69A),
    warnAmber = Color(0xFFF5A623),
    // ⚠️ v0.20.0（a11y 评审 P1-5）：#E5484D → #FF6B6E
    // 原值对**卡片面** #1C1C1E 只有 4.35:1（低于文字 4.5:1）——
    // 注释里的「对底色达标」是拿 #000000 算的，而它实际画在卡片上。
    // 新值对 #1C1C1E **6.14:1**，对 #000000 7.58:1。
    danger = Color(0xFFFF6B6E),
    // ⚠️ v0.20.0（a11y 评审 P1-4）：#5A5A5E → #6E6E76
    // 轨道（空槽弧 / 能量条空槽 / 0~100% 标尺）实际画在**卡片面** #1C1C1E 上，
    // 原值只有 **2.48:1** < 3:1（非文字图形 WCAG 1.4.11）。
    // 注释里的「深 3.1:1」是拿页面底 #000000 算的 —— 载体算错了。
    // 新值对 #1C1C1E **3.37:1**，对 #000000 4.15:1。
    chartTrack = Color(0xFF6E6E76),
    chartBaseline = Color(0xFF6E6E76),
    energyViolet = Color(0xFFA98BF0),
    glassTint = Color(0x8C1C1C1E),
    glassHighlight = Color(0x33FFFFFF),
    shadowAmbient = Color(0x40000000),
    shadowKey = Color(0x66000000),
    // iOS grouped 深色三级：页面底 #000000 → 卡片面 #1C1C1E → 内凹 #0B0B0D
    cardSurface = Color(0xFF1C1C1E),
    cardInset = Color(0xFF0B0B0D),
    // 深色下描边是白（黑描黑看不见），但要比之前收敛：22% → 14%
    cardStroke = Color(0x24FFFFFF),
    cardStrokeEnd = Color(0x0AFFFFFF),
)

val LocalAccentColors = staticCompositionLocalOf { LightAccents }

/**
 * 当前是否深色模式。
 *
 * 需要按模式切换色板的组件（自绘分段控件、Canvas 图表等）用这个判断，
 * 而不是各自硬编码两套颜色 —— 硬编码是"两套 App 拼在一起"的常见来源。
 */
@Composable
fun isDarkTheme(): Boolean {
    val bg = MaterialTheme.colorScheme.background
    return (0.299f * bg.red + 0.587f * bg.green + 0.114f * bg.blue) < 0.5f
}

/** 毛玻璃状态。整个 App 共用一个，根容器作 source，玻璃面作 effect。 */val LocalHazeState = staticCompositionLocalOf<HazeState> {
    error("HazeState 未初始化")
}

/** 当前主题对应的毛玻璃样式（tint + 模糊半径）。 */
@Composable
fun rememberHazeStyle(
    blurDp: Int,
    alpha: Float,
    tint: Color,
): HazeStyle = HazeStyle(
    backgroundColor = Color.Transparent,
    tints = listOf(HazeTint(tint)),
    blurRadius = blurDp.dp,
    noiseFactor = 0f,
    fallbackTint = HazeTint(tint.copy(alpha = alpha)),
)

// ── 配色 ─────────────────────────────────────────────────────────
private val LightColors = lightColorScheme(
    primary = Color(0xFFD70012),           // 特斯拉红（略压暗保证白字对比度）
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410001),
    // ⚠️ tertiary 原本是青色，与 primary 红形成"两个强调色"打架。
    // 收敛为中性冷灰蓝，让红色成为唯一强调色。
    secondary = Color(0xFF3A4750),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE3E8),
    onSecondaryContainer = Color(0xFF101A20),
    tertiary = Color(0xFF4A5760),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE2E7EB),
    onTertiaryContainer = Color(0xFF141C22),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE1DE),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF2F2F7),        // iOS systemGroupedBackground
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFF2F2F7),
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFEBEBEE),
    // ⚠️ v0.14.0（苹果 HIG P1）：#6B6B70 → **#63636A**。
    // 原来这个值落在内凹块 cardInset(#EBEBF0) 上只有 **4.46:1**，
    // 差一点点达不到 WCAG 文字 AA 的 4.5:1 ——
    // 命中的是「空调」「车内温度」「车外温度」「功率」这些 labelSmall 标签。
    // #63636A 在 #EBEBF0 上是 5.02:1，在 #FFFFFF 上是 5.96:1，都达标。
    onSurfaceVariant = Color(0xFF63636A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF2F2F4),   // 内凹块
    surfaceContainerHighest = Color(0xFFE8E8EB),
    // ⚠️ v0.20.0（a11y 评审 P2）：#B0B0B6 → #8E8E93
    // `OutlinedButton` 的**唯一**外框 —— 原值对白只有 2.16:1，
    // 浅色下几乎看不见（2 倍字号截图里「仅测试连接」的边框极淡）。
    // 新值 3.26:1，满足非文字图形 ≥3:1。
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFFE0E0E5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF453A),           // iOS dark 下的系统红
    onPrimary = Color(0xFF2B0A08),
    primaryContainer = Color(0xFF93000A),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFB6C4CE),
    onSecondary = Color(0xFF1A232A),
    secondaryContainer = Color(0xFF2A353D),
    onSecondaryContainer = Color(0xFFD7E3EC),
    tertiary = Color(0xFFA9B7C1),
    onTertiary = Color(0xFF16202A),
    tertiaryContainer = Color(0xFF243039),
    onTertiaryContainer = Color(0xFFDCE6EE),
    error = Color(0xFFFF453A),
    onError = Color(0xFF2B0A08),
    // ⚠️ v0.9.2（苹果视觉评审 P1-2）：深色 errorContainer 原来 #93000A ——
    // 一整块饱和深红，是全 App 深色模式下**唯一的彩色块**，重量压过所有内容
    // （对比度虽然达标 7.2:1，问题在 tonal 重量）。
    // 改成 iOS dark tinted background 的做法：暗红底 #3A1518 + 亮字 #FFB4AE（9.47:1）。
    errorContainer = Color(0xFF3A1518),
    onErrorContainer = Color(0xFFFFB4AE),
    // iOS 深色是**纯黑**分层（#000000 / #1C1C1E / #2C2C2E），
    // 此前用灰蓝底 (#101214) 显得"灰蒙蒙"，且卡片与底色分离度不够。
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF98989F),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF1C1C1E),   // 卡片面（与 AccentColors.cardSurface 一致）
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C),
    // ⚠️ v0.20.0（a11y 评审 P2）：#48484A → #6E6E76
    // 对**卡片面** #1C1C1E 原值只有 1.86:1 —— 深色下描边按钮的外框基本不存在。
    // 新值 3.37:1。
    outline = Color(0xFF6E6E76),
    outlineVariant = Color(0xFF38383A),
)

// ── 排版（iOS 尺度）────────────────────────────────────────────────
// 关键：所有数字用 tabular figures（tnum），否则数值刷新时宽度变化会左右抖动。
private val Tnum = "tnum"

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = 0.sp, fontFeatureSettings = Tnum,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = 0.sp, fontFeatureSettings = Tnum,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
        fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = 0.sp, fontFeatureSettings = Tnum,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp, fontFeatureSettings = Tnum,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = 0.sp, fontFeatureSettings = Tnum,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = 0.sp, fontFeatureSettings = Tnum,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.sp, fontFeatureSettings = Tnum,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp, fontFeatureSettings = Tnum,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.sp, fontFeatureSettings = Tnum,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp, fontFeatureSettings = Tnum,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp, fontFeatureSettings = Tnum,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp, fontFeatureSettings = Tnum,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.sp, fontFeatureSettings = Tnum,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 15.sp, letterSpacing = 0.sp, fontFeatureSettings = Tnum,
    ),
)

// ── 形状（iOS 连续圆角观感）─────────────────────────────────────
private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
)

@Composable
fun TeslaMateTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(
        LocalAccentColors provides if (dark) DarkAccents else LightAccents,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

/** 取得当前的品牌/图表色 */
val accents: AccentColors
    @Composable get() = LocalAccentColors.current

/** 便捷：给任意 Modifier 挂上"模糊源"（在根容器调用一次） */
fun Modifier.hazeSource(state: HazeState): Modifier = haze(state)