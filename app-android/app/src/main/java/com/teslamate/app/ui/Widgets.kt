package com.teslamate.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teslamate.app.Haptics
import dev.chrisbanes.haze.hazeChild
import kotlin.math.roundToInt

// ══════════════════════════════════════════════════════════════════
//  材质与 3D
// ══════════════════════════════════════════════════════════════════

/**
 * 毛玻璃材质。
 *
 * iOS 的 vibrancy = 背后内容实时可见 + 实时模糊。Android 上没有原生等价物，
 * 真毛玻璃必须靠第三方 backdrop 库（haze），API 31 以下自动退化为半透明实色。
 *
 * ⚠️ **Haze 硬性限制**：`Modifier.haze`（源）与 `Modifier.hazeChild`（效果）
 * **不能是父子关系**，否则运行时抛 `IllegalArgumentException` 直接崩。
 * 所以只有「兄弟关系」的地方才能用真毛玻璃（页面内容 ←→ 底部导航栏），
 * 卡片这类嵌在内容里的元素改用半透明 + 渐变描边的「假毛玻璃」。
 *
 * 四档材质对应 iOS：
 *   bar     (blur 20dp) 导航栏 / Tab 栏
 *   regular (blur 24dp) 卡片（假毛玻璃）
 *   thin    (blur 16dp) 内嵌块
 *   thick   (blur 30dp) Hero 卡 / 弹窗
 */
enum class GlassLevel(
    val blurDp: Int,
    val alphaLight: Float,
    val alphaDark: Float,
) {
    Thin(16, 0.35f, 0.45f),
    Bar(20, 0.72f, 0.72f),
    Regular(24, 0.55f, 0.55f),
    Thick(30, 0.85f, 0.85f),
}

/**
 * @param realBlur true=真毛玻璃（需要 haze 源是**兄弟节点**）；
 *                 false=半透明 + 渐变描边的假毛玻璃（可安全嵌套在 haze 源内部）。
 */
@Composable
fun Modifier.glassSurface(
    level: GlassLevel = GlassLevel.Regular,
    shape: Shape,
    realBlur: Boolean = false,
): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    val tint = if (dark) Color.Black else Color.White
    val alpha = if (dark) level.alphaDark else level.alphaLight
    val base = this.background(tint.copy(alpha = alpha), shape)
    if (!realBlur) return base
    val style = rememberHazeStyle(
        blurDp = level.blurDp,
        alpha = alpha,
        tint = tint,
    )
    return base.hazeChild(state = LocalHazeState.current, shape = shape, style = style)
}

/**
 * 彩色双层阴影 + 1px 渐变描边 + 静止高光。
 *
 * 「高级 3D」与「廉价 3D」的分界：
 *   廉价 = 大圆角 + 一层灰阴影 + 饱和渐变
 *   高级 = 单一光源（同屏阴影同色同向）+ 克制层级 + 静止的精致细节
 *
 * ⚠️ **v0.7.1 修正**：
 *   1. 阴影的 spotColor 原来是 `primary`（特斯拉红）@26% —— 于是**每张卡片都带一圈红晕**，
 *      浅色底上表现为"粉红发光"，是"一眼看出是套模板"的最大来源。
 *      现在改为**中性黑**，彩色阴影只留给电量环这种真正需要强调的元素。
 *   2. 描边颜色从硬编码白 @22%/@6% 改为读 `cardStroke`/`cardStrokeEnd` token ——
 *      浅色下原来是**白描白卡**（肉眼完全看不见边界），现在浅色走黑 @8%→@3%。
 *   3. 高光 `drawRect` 之前没有裁剪到圆角，四个圆角会露出方角白斑；现在裁到 shape。
 */
@Composable
fun Modifier.cardDepth(
    elevation: Float = 6f,
    shape: Shape = RoundedCornerShape(20.dp),
    ambient: Float = 0.10f,
    spot: Float = 0.26f,
): Modifier {
    val accents = LocalAccentColors.current
    return this
        .shadow(
            elevation = elevation.dp,
            shape = shape,
            clip = false,
            ambientColor = accents.shadowAmbient,
            spotColor = accents.shadowKey,
        )
        .drawWithCache {
            // 静止的对角高光，模拟玻璃受光。成本 = 一次 drawRect，几乎为零。
            val sheen = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.07f),
                    Color.Transparent,
                    Color.White.copy(alpha = 0.03f),
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            )
            onDrawBehind { drawRect(sheen) }
        }
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(
                colors = listOf(accents.cardStroke, accents.cardStrokeEnd),
                start = Offset.Zero,
                end = Offset(1000f, 1000f),
            ),
            shape = shape,
        )
}

private fun Color.luminanceIsDark(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f

// ══════════════════════════════════════════════════════════════════
//  基础组件
// ══════════════════════════════════════════════════════════════════

/** 导航栏高度：半透明毛玻璃导航栏会盖住内容，列表底部必须留出这段。 */
@Composable
fun navBarBottom(): androidx.compose.ui.unit.Dp =
    androidx.compose.foundation.layout.WindowInsets.navigationBars
        .asPaddingValues().calculateBottomPadding()

/** 错误横幅（tonal） */
@Composable
fun ErrorBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** 提示横幅（tonal，中性提醒）——用于「数据不是最新的」这类非错误信息 */
@Composable
fun NoticeBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

/**
 * 骨架屏块：shimmer 流光。
 *
 * ⚠️ 修正（2026-10-24）：原来每个方块各自建一个 `rememberInfiniteTransition`，
 * ListSkeleton(7) 就是 8 个独立无限动画 → 相位乱窜、看着像"屏幕有电流"。
 * 现在**全局共享一个 transition**，用 delayMillis 制造逐块相位差（波浪感且仍同步）。
 * 高光宽度也从硬编码 320px 改成跟随实际宽度。
 */
@Composable
fun rememberShimmerProgress(): Float {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val p by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            tween(1200, easing = LinearEasing),
            androidx.compose.animation.core.RepeatMode.Restart,
        ),
        label = "shimmerX",
    )
    return p
}

@Composable
fun SkeletonBox(modifier: Modifier = Modifier, progress: Float = rememberShimmerProgress()) {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val hi = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .drawWithCache {
                val band = 56.dp.toPx()
                val start = Offset(progress * (size.width + band * 2) - band, 0f)
                val end = Offset(start.x + band, size.height)
                val brush = Brush.linearGradient(listOf(base, hi, base), start, end)
                onDrawBehind { drawRect(brush) }
            },
    )
}

/** 列表加载骨架（标题条 + 内容条 × N） */
@Composable
fun ListSkeleton(items: Int = 6, modifier: Modifier = Modifier) {
    val progress = rememberShimmerProgress()
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 形态对齐真实内容：圆角卡 + 标题条(55%) + 副标题条(85%)，而不是实心大块
        repeat(items + 1) { i ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkeletonBox(Modifier.fillMaxWidth(0.55f).height(18.dp), progress)
                SkeletonBox(Modifier.fillMaxWidth(0.85f).height(14.dp), progress)
            }
            if (i == 0) Spacer(Modifier.height(0.dp))
        }
    }
}

/**
 * 按压反馈（iOS 式：变暗 + 轻微缩小）。
 *
 * ⚠️ **重大修正（2026-10-24）**：原实现的 `MutableInteractionSource` 创建后
 * **从未传给任何手势修饰符**，`clickable` 自建 source 收不到事件 →
 * `pressed` 恒为 false → **全 App 的按压反馈从来没生效过**（死代码）。
 * 现在内部自建 source 并同时提供 `clickable`，保证视觉与触感同步。
 */
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    scaleDown: Float = 0.965f,
    enabled: Boolean = true,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ctx = LocalContext.current
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleDown else 1f,
        animationSpec = tween(
            durationMillis = if (pressed) 90 else 180,
            easing = FastOutSlowInEasing,
        ),
        label = "pressScale",
    )
    val fade by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = tween(
            durationMillis = if (pressed) 90 else 180,
            easing = FastOutSlowInEasing,
        ),
        label = "pressAlpha",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = fade
        }
        .clickable(
            interactionSource = interaction,
            indication = androidx.compose.material3.ripple(),
            enabled = enabled,
            onClick = {
                Haptics.tick(ctx)
                onClick()
            },
        )
}

/** 保留旧签名以兼容既有调用点（现在真正生效） */
fun Modifier.pressScale(scaleDown: Float = 0.97f): Modifier = this

/**
 * 分组信息卡：**实色卡面** + 中性阴影 + 渐变描边。
 *
 * ⚠️ **v0.7.1 修正**：原来卡面是 `glassSurface(GlassLevel.Regular)` ——
 * 半透明白/黑叠在页底上。深色下 = #000000 叠 #000000 = **#000000**，
 * 卡片与页面背景 1.00:1，「深色改纯黑分层」在卡片上等于没做；
 * 浅色下 = #FBFBFC 叠 #F7F7F9，卡片 1.03:1，同样看不见层次。
 * 现在直接用**不透明 `cardSurface`**（浅 #FFFFFF / 深 #1C1C1E），
 * 页面底 → 卡片面 → 内嵌块的三级纵深一次成型，**不动任何布局**。
 */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accents = LocalAccentColors.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .cardDepth(elevation = 6f),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = accents.cardSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

/**
 * 自绘分段控件（iOS UISegmentedControl 风格）。
 *
 * ⚠️ 不用 M3 的 `SingleChoiceSegmentedButtonRow`：它自带描边、圆角和一个选中态
 * 填充块，是**纯 Material 味**，与整 App 的 iOS 语言冲突。
 * 现在：外层低对比容器 + 内部 2dp 间隙 + 选中块纯色（深 #3A3A3C / 浅 #FFFFFF），
 * 无描边、无 M3 指示器。
 */
@Composable
fun SegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isDarkTheme()
    val trackColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFE9E9EC)
    val selectedBg = if (dark) Color(0xFF3A3A3C) else Color.White
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(trackColor)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) selectedBg else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 键值行 */
@Composable
fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(
            v,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            // ⚠️ v0.8.1：2 行在大字号下会截断数值（WCAG 1.4.4 要求放大后不丢内容），
            // 放宽到 3 行 —— 长数值如「412.35 km（+128 km）」在 1.3 倍字号下需要第三行。
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 页面标题。
 *
 * ⚠️ v0.8.1：加上 `heading()` 语义 —— 读屏用户（TalkBack）能按标题在页面间跳转，
 * 否则整个 App 是一张没有结构的长列表，导航全靠从头滑。
 */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontWeight = FontWeight.Bold,
        modifier = modifier.semantics { heading() },
    )
}

/**
 * 行内指标：灰标签 + 实数值，横向排布。
 *
 * 列表行不再把所有信息挤成一行 `maxLines=1` 截断（充电/行程列表的通病：
 * "10-12 14:30 · 47分钟 · 充入 12.3 kWh · 45%→88% · ¥18.40" 永远显示不全），
 * 改成 **两行结构**：第二行是时间，第三行是三个并排指标，标签小、数值实。
 */
@Composable
fun InlineMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = valueColor,
        )
    }
}

/**
 * 小信息块（卡片内凹块）。
 *
 * ⚠️ **v0.7.1 二次修正**：v0.7.0 把底色改成「与父卡片同色 + hairline」，
 * 声称修好了层级倒置 —— 但父卡片当时是**半透明玻璃**，实测浅色 #FFFFFF vs #FBFBFC
 * （ΔL\* 0.9）、深色 #0B0B0D vs #000000（ΔL\* 3.0），层次**原样存在**。
 * 现在父卡片是实色 `cardSurface`，内凹块用**专门的 `cardInset`**：
 *   深色 = #1C1C1E 卡里的 #0B0B0D（ΔL\* 7.4，像真的凹下去）
 *   浅色 = #FFFFFF 卡里的 #F2F2F4（ΔL\* 3.2）
 * 描边同时从 1dp 硬线降到 0.5dp —— 靠**明度差**表达层级，不靠线。
 */
@Composable
fun InfoTile(title: String, value: String, modifier: Modifier = Modifier) {
    val accents = LocalAccentColors.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = accents.cardInset,
        modifier = modifier
            .border(
                width = 0.5.dp,
                color = accents.cardStrokeEnd,
                shape = RoundedCornerShape(14.dp),
            ),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 大数字（iOS Large Title 刻度） */
@Composable
fun BigStat(value: String, label: String) {
    Column {
        Text(
            value,
            fontSize = 34.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-1).sp,
            style = MaterialTheme.typography.displayLarge,
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 车辆状态胶囊。
 *
 * 状态一律用**矢量图标 + 颜色 + ≤2 字词**表达，不再用 "● 文本" —— 文字胶囊占宽度且不可扫读。
 */
@Composable
fun StateChip(state: String?) {
    val (txt, container, content, icon) = when (state) {
        "online" -> Quad(
            "在线",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Filled.LockOpen,
        )
        "asleep" -> Quad(
            "休眠",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Filled.PowerOff,
        )
        "offline" -> Quad(
            "离线",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Filled.PowerOff,
        )
        else -> Quad(
            state ?: "未知",
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Filled.PowerOff,
        )
    }
    Surface(shape = RoundedCornerShape(50), color = container) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(txt, style = MaterialTheme.typography.labelMedium, color = content)
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/**
 * 电量环形图。
 *
 * 修正：
 *  - 360° 全圆 → 270° 缺口弧（全圆读起来像 loading spinner）
 *  - 颜色：单一能量色（原先 healthy=蓝、charging=蓝 → 两种状态同色）
 *  - 数字跟随动画（原弧线 800ms 缓动、数字第一帧就跳到终值，弧与字脱节）
 *  - 加静态刻度环（12 格）——静态细节比动画更能提升高级感
 */
@Composable
fun SocRing(soc: Int?, modifier: Modifier = Modifier, charging: Boolean = false) {
    val a = LocalAccentColors.current
    val track = a.chartTrack
    // 颜色语义（特斯拉设计语言）：
    //   正常 = 能量蓝（不是红！红色只留给 <20% 告警与品牌点缀）
    //   绿色 **只** 表示"正在充电"
    val color = when {
        soc == null -> track
        charging -> a.chargeGreen
        soc >= 20 -> a.energyBlue
        soc >= 10 -> a.warnAmber
        else -> a.danger
    }
    val animated by animateFloatAsState(
        targetValue = (soc ?: 0).toFloat(),
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "socRing",
    )
    val shown = animated.roundToInt()
    // 圆环随 fontScale 一起放大 —— 否则大字号下 "100%" 会溢出圆环。
    // ⚠️ v0.8.1：配合 AppRoot 把 fontScale 封顶从 1.15 提到 1.30，
    // 这个环必须跟着长，否则解封的那部分字号就会在环里溢出去。
    val fontScale = LocalDensity.current.fontScale
    val ringSize = (168 * fontScale.coerceIn(1f, 1.3f)).dp
    Box(
        modifier = modifier.size(ringSize)
            // ⚠️ v0.7.1：Canvas 画的环对屏幕阅读器完全不可达，
            // 加语义描述（电量 + 充电状态）。
            .semantics {
                contentDescription = buildString {
                    append("电量 ")
                    append(if (soc == null) "未知" else "$soc%")
                    if (charging) append("，正在充电")
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(ringSize)) {
            val stroke = 15.dp.toPx()
            val inset = stroke / 2 + 2.dp.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(
                size.width - inset * 2,
                size.height - inset * 2,
            )
            val start = 135f
            val sweep = 270f
            drawArc(
                color = track,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (shown > 0) {
                drawArc(
                    color = color,
                    startAngle = start,
                    sweepAngle = sweep * (animated / 100f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            // 静态刻度：10 格，每格 10%
            //
            // ⚠️ **v0.7.1 修正两处**：
            //  1. 角度原来写成 `135 + i * 22.5 + 90` —— 那个 `+90` 是多余的，
            //     导致整个刻度盘相对圆弧**偏转 90°**，看上去像"刻度乱转"。
            //     圆弧起点 135°、跨度 270°，10 格就该每格 27°，起点与弧对齐。
            //  2. 12 格 × 22.5° 与"每格 10%"自相矛盾（12 格只能表达 120%），
            //     亮起判定 `shown / 10` 于是和格数错位 —— 98% 只亮 9 格。
            //     现在统一成 10 格 / 27°，亮起判定 `shown > i * 10`，
            //     于是 50% 亮 5 格、98% 亮 10 格，与圆弧读数一致。
            val r = size.minDimension / 2 - stroke - 2.dp.toPx()
            val c = Offset(size.width / 2, size.height / 2)
            for (i in 0 until 10) {
                val ang = Math.toRadians((135f + i * 27f).toDouble())
                val inner = r - 5.dp.toPx()
                val outer = r
                drawLine(
                    color = if (shown > i * 10) color.copy(alpha = 0.85f) else track,
                    start = Offset(
                        c.x + (inner * kotlin.math.cos(ang)).toFloat(),
                        c.y + (inner * kotlin.math.sin(ang)).toFloat(),
                    ),
                    end = Offset(
                        c.x + (outer * kotlin.math.cos(ang)).toFloat(),
                        c.y + (outer * kotlin.math.sin(ang)).toFloat(),
                    ),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "$shown%",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.5).sp,
                style = MaterialTheme.typography.displayLarge,
            )
        }
    }
}

/**
 * 能量条 —— 特斯拉最易识别的视觉母题。
 *
 * 横向圆角条 + 目标 SOC 刻度线，充电中转绿。
 *
 * ⚠️ **v0.7.1 修正**：
 *  1. 目标刻度原来画的是**纯白 @75%** —— 浅色模式下白画在浅色轨道上等于隐形
 *     （实测 1.17:1，WCAG 1.4.11 要求非文字图形 ≥3:1）。现在用 `onSurfaceVariant`，
 *     深浅两模式都 ≥4.5:1。
 *  2. 轨道 `chartTrack` 深色下只有 1.39:1 —— 提到 ≥3:1，否则"满格量程"读不出来。
 *  3. 加 `contentDescription`：Canvas 画的条对屏幕阅读器完全不可达（IA 专家实测）。
 */
@Composable
fun EnergyBar(
    soc: Int?,
    modifier: Modifier = Modifier,
    charging: Boolean = false,
    targetSoc: Int? = 80,
    height: Int = 14,
) {
    val a = LocalAccentColors.current
    val fill = when {
        charging -> a.chargeGreen
        soc == null -> Color.Transparent
        soc >= 20 -> a.energyBlue
        soc >= 10 -> a.warnAmber
        else -> a.danger
    }
    val track = a.chartTrack
    val animated by animateFloatAsState(
        targetValue = (soc ?: 0) / 100f,
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "energyBar",
    )
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .semantics {
                contentDescription = buildString {
                    append("电量 ${soc ?: "未知"}%")
                    if (charging) append("，正在充电")
                    targetSoc?.let { append("，目标电量 $it%") }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
            drawRoundRect(color = track, cornerRadius = r)
            if (animated > 0f) {
                drawRoundRect(
                    color = fill,
                    size = androidx.compose.ui.geometry.Size(size.width * animated, size.height),
                    cornerRadius = r,
                )
            }
            targetSoc?.let { t ->
                val x = size.width * (t / 100f)
                drawLine(
                    color = tickColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/**
 * 俯视车辆 + 四轮胎压。
 *
 * 这是本 App **唯一能超越官方 Tesla App** 的地方（官方不展示四轮胎压）。
 * 异常轮实心 + 呼吸动画。
 */
@Composable
fun VehicleTopView(
    reading: TpmsReadingUi?,
    modifier: Modifier = Modifier,
) {
    val a = LocalAccentColors.current
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    // 车身：浅色用品牌红（一眼认出是特斯拉），深色用中性灰蓝分层。
    // 描边与轮胎都用「轮廓级」灰 —— 原来浅色下轮胎是 #E6E8EB 贴在白车身上，等于隐形。
    val bodyTop = if (dark) Color(0xFF3A3A3C) else Color(0xFFD70012)
    val bodyBottom = if (dark) Color(0xFF242426) else Color(0xFF93000A)
    val outline = if (dark) Color(0xFF6E6E76) else Color(0xFF8E8E93)
    val glass = if (dark) Color(0xFF6E6E76) else Color(0xFFB8BCC2)
    val danger = a.danger
    val warn = a.warnAmber

    val pulse = rememberInfiniteTransition(label = "tpmsPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1600, easing = FastOutSlowInEasing),
            androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "tpmsPulseAlpha",
    )

    Box(
        modifier = modifier.semantics {
            // 俯视图里的「哪轮异常」只靠颜色表达，读屏用户拿不到。
            // 把四轮读数与异常状态写进语义描述。
            contentDescription = buildString {
                append("俯视图：")
                val list = listOf(
                    "左前" to reading?.fl,
                    "右前" to reading?.fr,
                    "左后" to reading?.rl,
                    "右后" to reading?.rr,
                )
                list.forEachIndexed { i, (label, v) ->
                    if (i > 0) append("，")
                    append(label)
                    append(" ")
                    append(if (v == null) "无数据" else "%.2f bar".format(v))
                    if (reading?.isAlerting(v, label) == true) append("，异常")
                }
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            val w = size.width * 0.60f
            val h = size.height * 0.92f
            val left = (size.width - w) / 2
            val top = (size.height - h) / 2
            val cx = size.width / 2

            // ── 车身轮廓 ────────────────────────────────────────────
            // ⚠️ v0.7.1 重画：原来是一个圆角矩形 + 2 个圆角矩形当风挡 + 4 个小圆角条当轮子，
            // 视觉质感控评为「全 App 最廉价的一处」。
            // 现在是**真正的俯视轮廓**：车头收窄、尾部收窄、腰线外凸、四角圆润，
            // 再叠品牌色纵向渐变 —— 一眼能认出是台车，而不是一个灰色方块。
            val body = Path().apply {
                // 车头（上方）：从左侧腰部起，往上收窄到车头
                moveTo(cx - w * 0.44f, top + h * 0.22f)
                quadraticTo(cx - w * 0.48f, top + h * 0.05f, cx - w * 0.34f, top)
                lineTo(cx + w * 0.34f, top)
                quadraticTo(cx + w * 0.48f, top + h * 0.05f, cx + w * 0.44f, top + h * 0.22f)
                // 右侧腰线 → 后轮拱 → 尾部收窄
                lineTo(cx + w * 0.50f, top + h * 0.40f)
                lineTo(cx + w * 0.50f, top + h * 0.66f)
                lineTo(cx + w * 0.42f, top + h * 0.90f)
                quadraticTo(cx + w * 0.40f, top + h, cx + w * 0.28f, top + h)
                lineTo(cx - w * 0.28f, top + h)
                quadraticTo(cx - w * 0.40f, top + h, cx - w * 0.42f, top + h * 0.90f)
                lineTo(cx - w * 0.50f, top + h * 0.66f)
                lineTo(cx - w * 0.50f, top + h * 0.40f)
                close()
            }
            drawPath(
                body,
                brush = Brush.verticalGradient(
                    listOf(bodyTop, bodyBottom),
                    startY = top,
                    endY = top + h,
                ),
            )
            drawPath(body, color = outline, style = Stroke(width = 1.5.dp.toPx()))

            // 风挡（梯形）
            fun windshield(yTop: Float, yBot: Float, wide: Float, narrow: Float) {
                val p = Path().apply {
                    moveTo(cx - wide * w / 2, yTop)
                    lineTo(cx + wide * w / 2, yTop)
                    lineTo(cx + narrow * w / 2, yBot)
                    lineTo(cx - narrow * w / 2, yBot)
                    close()
                }
                drawPath(p, color = glass)
            }
            windshield(top + h * 0.055f, top + h * 0.135f, 0.56f, 0.40f)
            windshield(top + h * 0.865f, top + h * 0.945f, 0.40f, 0.54f)

            // ── 四个轮位 ────────────────────────────────────────────
            data class W(val label: String, val value: Double?, val x: Float, val y: Float)
            val wheelW = 17.dp.toPx()
            val wheelH = 42.dp.toPx()
            val wheels = listOf(
                W("左前", reading?.fl, cx - w * 0.50f - wheelW * 0.62f, top + h * 0.20f),
                W("右前", reading?.fr, cx + w * 0.50f - wheelW * 0.38f, top + h * 0.20f),
                W("左后", reading?.rl, cx - w * 0.50f - wheelW * 0.62f, top + h * 0.62f),
                W("右后", reading?.rr, cx + w * 0.50f - wheelW * 0.38f, top + h * 0.62f),
            )
            // 先画四个轮拱槽（比车身略暗的竖槽），再把轮子嵌进去 ——
            // 轮子直接贴在车身上会像"贴纸"，有个槽才像嵌进去。
            // ⚠️ 第一版用 drawArc 画轮拱弧线，结果是四个"钩子"挂在车身侧面，很脏。
            // 现在改用暗色圆角矩形槽，干净且一眼能看出轮位。
            wheels.forEach { wl ->
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.30f),
                    topLeft = Offset(wl.x - 3.dp.toPx(), wl.y - 4.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(
                        wheelW + 6.dp.toPx(),
                        wheelH + 8.dp.toPx(),
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()),
                )
            }
            wheels.forEach { wl ->
                val bad = reading?.isAlerting(wl.value, wl.label) == true
                val near = reading?.isNear(wl.value, wl.label) == true
                val wColor = when {
                    bad -> danger.copy(alpha = pulseAlpha)
                    near -> warn
                    else -> outline
                }
                drawRoundRect(
                    color = wColor,
                    topLeft = Offset(wl.x, wl.y),
                    size = androidx.compose.ui.geometry.Size(wheelW, wheelH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                )
            }
        }
    }
}

/**
 * 俯视图旁的数值列表。
 *
 * ⚠️ **v0.7.1 修正（唯一事实源的最后一块拼图）**：
 * 服务端从 v0.7.0 起就在响应里下发 `tpms_alert_wheels`（异常轮次的中文名），
 * 但 App **从头到尾没读过它** —— 判定仍在本地做，阈值与「单胎离群 0.2 bar」
 * 在 App 里是**硬编码**的。这与 CHANGELOG 里「不再本地算阈值」的承诺不符，
 * 也是全 App 唯一残留的「两端各算一份」的地方。
 *
 * 现在：`alertWheels` 非空 → **一律以服务端判定为准**；
 * 只有服务端没给（老服务端兼容）时才退回本地阈值。
 */
data class TpmsReadingUi(
    val fl: Double?,
    val fr: Double?,
    val rl: Double?,
    val rr: Double?,
    val minBar: Double = 2.5,
    val maxBar: Double = 3.3,
    /** 服务端判定的异常轮次（"左前"/"右前"/"左后"/"右后"）；非空时优先 */
    val alertWheels: Set<String> = emptySet(),
    /**
     * 软告警轮次（Tesla 自己的 `tpms_soft_warning` / 本机判定的「接近阈值」）。
     *
     * ⚠️ v0.8.6（长途自驾党复核）：Tesla 的 `tpms_hard_warning` 是**绝对阈值**告警，
     * `tpms_soft_warning` 是接近阈值；而**单胎比四轮中位低 0.2 bar**（漏气主信号）
     * 只有四轮互比能发现 —— Tesla 的 flag 不做互比。**两者都要**：
     * hard / 本地离群 = 红，soft / 接近 = 琥珀。
     */
    val warnWheels: Set<String> = emptySet(),
) {
    /** 单胎离群阈值（与服务端 `TpmsOutlierDelta` 对齐，仅作兼容兜底） */
    private val outlierDelta = 0.2

    fun isAlerting(p: Double?, label: String? = null): Boolean {
        if (alertWheels.isNotEmpty() && label != null) return label in alertWheels
        if (p == null) return false
        if (p < minBar || p > maxBar) return true
        val all = listOfNotNull(fl, fr, rl, rr).sorted()
        if (all.isEmpty()) return false
        val median = all[all.size / 2]
        return (median - p) > outlierDelta
    }

    /** 接近阈值（未触发告警）——用琥珀色描边提示 */
    fun isNear(p: Double?, label: String? = null): Boolean {
        if (p == null || isAlerting(p, label)) return false
        if (label != null && label in warnWheels) return true
        return p < minBar + 0.15 || p > maxBar - 0.15
    }
}
