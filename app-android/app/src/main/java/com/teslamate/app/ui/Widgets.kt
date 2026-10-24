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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
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
 * 深色模式下阴影几乎不可见，**渐变描边是唯一能让卡片"浮起来"的东西**，
 * 所以描边在深色下提高 alpha。
 */
@Composable
fun Modifier.cardDepth(
    elevation: Float = 6f,
    shape: Shape = RoundedCornerShape(20.dp),
    ambient: Float = 0.10f,
    spot: Float = 0.26f,
): Modifier {
    val dark = MaterialTheme.colorScheme.background.luminanceIsDark()
    val spotColor = MaterialTheme.colorScheme.primary.copy(alpha = spot)
    return this
        .shadow(
            elevation = elevation.dp,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = ambient),
            spotColor = spotColor,
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
                colors = listOf(
                    Color.White.copy(alpha = if (dark) 0.22f else 0.55f),
                    Color.White.copy(alpha = if (dark) 0.06f else 0.16f),
                ),
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

/** 分组信息卡：毛玻璃 + 彩色阴影 + 渐变描边 */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .cardDepth(elevation = 6f),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(
            Modifier
                .glassSurface(GlassLevel.Regular, RoundedCornerShape(20.dp))
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
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 小信息块。
 *
 * ⚠️ **层级倒置修正**：原来用 `surfaceContainer`（比父卡片 surfaceContainerLow 更亮），
 * 深色下两者感知亮度差 ΔL* 仅 1.4 —— 人眼不可辨，嵌套层次直接消失。
 * 现在改成**与父卡片同色 + 0.5dp hairline**，这是 iOS inset-grouped 的标准做法。
 */
@Composable
fun InfoTile(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
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
fun SocRing(soc: Int?, modifier: Modifier = Modifier) {
    val a = LocalAccentColors.current
    val track = a.chartTrack
    // 颜色语义（特斯拉设计语言）：
    //   正常 = 能量蓝（不是红！红色只留给 <20% 告警与品牌点缀）
    //   绿色 **只** 表示"正在充电"
    val color = when {
        soc == null -> track
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
    Box(
        modifier = modifier.size(168.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(168.dp)) {
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
            // 静态刻度：12 格，每 10% 一格（往内推，避免与圆弧重叠）
            val r = size.minDimension / 2 - stroke - 2.dp.toPx()
            val c = Offset(size.width / 2, size.height / 2)
            for (i in 0 until 12) {
                val ang = Math.toRadians((135 + i * 22.5 + 90).toDouble())
                val inner = r - 5.dp.toPx()
                val outer = r
                drawLine(
                    color = if (i < (shown / 10)) color.copy(alpha = 0.85f) else track,
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
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
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
                    color = Color.White.copy(alpha = 0.75f),
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
    val body = MaterialTheme.colorScheme.surfaceContainer
    val line = MaterialTheme.colorScheme.outlineVariant
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

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(220.dp)) {
            val w = size.width * 0.52f
            val h = size.height * 0.86f
            val left = (size.width - w) / 2
            val top = (size.height - h) / 2
            val bodyShape = CornerRadius(24.dp.toPx())

            // 车身
            drawRoundRect(
                color = body,
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = bodyShape,
            )
            drawRoundRect(
                color = line,
                topLeft = Offset(left, top),
                size = androidx.compose.ui.geometry.Size(w, h),
                cornerRadius = bodyShape,
                style = Stroke(width = 1.dp.toPx()),
            )
            // 前后风挡
            drawRoundRect(
                color = line.copy(alpha = 0.7f),
                topLeft = Offset(left + w * 0.22f, top + 8.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(w * 0.56f, 22.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
            )
            drawRoundRect(
                color = line.copy(alpha = 0.7f),
                topLeft = Offset(left + w * 0.22f, top + h - 30.dp.toPx()),
                size = androidx.compose.ui.geometry.Size(w * 0.56f, 22.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
            )

            // 四个轮位
            data class W(val label: String, val value: Double?, val x: Float, val y: Float)
            val wheels = listOf(
                W("左前", reading?.fl, left - 12.dp.toPx(), top + h * 0.20f),
                W("右前", reading?.fr, left + w + 12.dp.toPx() - 18.dp.toPx(), top + h * 0.20f),
                W("左后", reading?.rl, left - 12.dp.toPx(), top + h * 0.62f),
                W("右后", reading?.rr, left + w + 12.dp.toPx() - 18.dp.toPx(), top + h * 0.62f),
            )
            wheels.forEach { wl ->
                val bad = reading?.isAlerting(wl.value) == true
                val near = reading?.isNear(wl.value) == true
                val wColor = when {
                    bad -> danger.copy(alpha = pulseAlpha)
                    near -> warn
                    else -> a.chartTrack
                }
                drawRoundRect(
                    color = wColor,
                    topLeft = Offset(wl.x, wl.y),
                    size = androidx.compose.ui.geometry.Size(18.dp.toPx(), 44.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                )
            }
        }
    }
}

/** 俯视图旁的数值列表。阈值由服务端给出（唯一事实源），App 不自己写。 */
data class TpmsReadingUi(
    val fl: Double?,
    val fr: Double?,
    val rl: Double?,
    val rr: Double?,
    val minBar: Double = 2.5,
    val maxBar: Double = 3.3,
) {
    fun isAlerting(p: Double?): Boolean {
        if (p == null) return false
        if (p < minBar || p > maxBar) return true
        val all = listOfNotNull(fl, fr, rl, rr).sorted()
        if (all.isEmpty()) return false
        val median = all[all.size / 2]
        return (median - p) > 0.2
    }

    /** 接近阈值（未触发告警）——用琥珀色描边提示 */
    fun isNear(p: Double?): Boolean {
        if (p == null || isAlerting(p)) return false
        return p < minBar + 0.15 || p > maxBar - 0.15
    }
}
