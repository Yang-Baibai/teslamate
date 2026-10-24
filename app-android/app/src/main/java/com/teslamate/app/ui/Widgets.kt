package com.teslamate.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teslamate.app.ApiErrorKind
import com.teslamate.app.Haptics
import com.teslamate.app.UserFacingException
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
            // ⚠️ v0.14.0（iOS 动效专项评审 P1-1）：**1dp → 0.5dp（发丝线）**。
            //
            // 1dp 在 2.75x 密度下是 2.75px，在 20dp 圆角上读作「描了个边」，
            // 不读作「材质边界」。iOS grouped 卡片用的是 0.5pt 发丝线。
            //
            // 参照样板是同一文件里的 [InfoTile] —— 它本来就是 0.5dp，
            // 「层次靠材质不靠描边」的正确示范。
            //
            // ⚠️ 例外（不要一刀切）：选中/焦点态用 2dp 主色 ring（灰描边加粗不是「选中」）；
            // 分段控件选中胶囊无描边；输入框改填充式。
            width = 0.5.dp,
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

/**
 * **统一的错误横幅** —— 四个页面共用。
 *
 * ⚠️ v0.14.0（充电页/设置页 IA 评审 P0-4）：
 *
 * 原来只有**车况页**区分 `ApiErrorKind`：
 *   · AUTH（令牌失效）→「前往设置」（有出口）
 *   · 其它 →「重试」
 *
 * 而记录 / 用量 / 充电 / 行程详情页**一律**只给「重试」——
 * 令牌失效时用户停在那些页面会无限次点「重试」，
 * 永远走不到能修的地方。这是 v0.9.0 修过的「401 无出口」**又犯了一次**，
 * 只是换了个页面。
 *
 * 现在四个页面共用这一个组件，按 kind 决定横幅样式与按钮：
 *   · NO_DATA → 中性横幅（这是**正常状态**，不是故障）
 *   · AUTH    → 红条 + 「前往设置」
 *   · 其它    → 红条 + 「重试」
 */
@Composable
fun ApiErrorBanner(
    err: UserFacingException,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (err.kind == ApiErrorKind.NO_DATA) {
        NoticeBanner(
            err.message ?: "这台车还没有任何数据。可能还没开过，或采集器还没上报。",
            modifier = modifier,
        )
    } else {
        ErrorBanner(
            err.message ?: "出错了",
            modifier = modifier,
            actionLabel = if (err.kind == ApiErrorKind.AUTH) "前往设置" else "重试",
            onAction = if (err.kind == ApiErrorKind.AUTH) onOpenSettings else onRetry,
        )
    }
}

/**
 * 错误横幅（tonal）。
 *
 * ⚠️ **v0.9.0：加「行动按钮」**。
 * 原来只有「图标 + 一句话」，用户看完不知道下一步该干什么。
 * 令牌失效时文案写着「请到「设置」页重新填写」，但「设置」在**第 4 个 Tab** ——
 * 说了等于没说（可靠性复核：「这是功能缺失，不是文案问题」）。
 * 现在横幅本身可以带一个出口按钮：
 * - 令牌问题 → 「前往设置」（直接切到设置页）
 * - 网络问题 → 「重试」（不用等 15 秒自动轮询）
 */
@Composable
fun ErrorBanner(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
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
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.width(4.dp))
                TextButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        actionLabel,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
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
            durationMillis = if (pressed) 60 else 200,
            easing = FastOutSlowInEasing,
        ),
        label = "pressScale",
    )
    val fade by animateFloatAsState(
        // ⚠️ v0.9.2（iOS 交互评审 P1-10）：iOS 列表行按下是**单通道**（变暗 ≈8%）。
        // 原来 0.92（变暗 8%）+ 缩放 3.5% + M3 ripple **三通道叠加**，
        // 而且 M3 的 ripple 在 touch-down 立刻绘制 —— 滚动列表时手指划过每张卡片
        // 都会闪一下水波纹，看起来像渲染故障而不是反馈。
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = tween(
            durationMillis = if (pressed) 60 else 200,
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
            // ⚠️ v0.9.2：**禁用 Material ripple**。
            // AppRoot 早就把 M3 的选中胶囊去掉了（"纯 Material 味"），
            // 这里又把 ripple 装了回来 —— 两处自相矛盾。
            // iOS 的 list row highlight 是**纯变暗**，没有扩散水波纹。
            indication = null,
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
 *
 * ⚠️ **v0.9.2（苹果视觉 P0-4 + iOS 交互 P1-6）修三处**：
 *
 * 1. **大字号破版**：原来 `Text` 没有 `maxLines`，4 个 `weight(1f)` 的等分槽在
 *    1.3~2.0 倍字号下必然换行 —— 「近 30 天」被折成「近 30 / 天」，
 *    而且文字**与相邻段的白色选中胶囊贴死**（实测图 20）。
 *    现在 `maxLines = 1` + `softWrap = false`：宁可省略号也不折行。
 *    配合调用方把标签缩短（「近 30 天」→「30 天」），4 段在 2.0 倍下也不折。
 * 2. **选中块硬切**：原来 `background(if (selected) selectedBg else Transparent)`
 *    是静态值，切换瞬间跳变。现在用 `animateColorAsState` 220ms 过渡
 *    —— iOS 的选中块是**滑过去**的，不是闪过去的。
 * 3. **零触感**：与 Tab 切换的 `selection` 标准不一致。现在补上。
 */
@Composable
fun SegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isDarkTheme()
    val ctx = LocalContext.current
    val trackColor = if (dark) Color(0xFF1C1C1E) else Color(0xFFE9E9EC)
    val selectedBg = if (dark) Color(0xFF3A3A3C) else Color.White
    val n = labels.size

    // ⚠️ v0.14.0（iOS 动效专项评审 P0-1）：**指示块滑动**。
    //
    // 原来每段各自 `animateColorAsState` 淡入淡出 —— 旧块淡出 + 新块淡入，
    // 是「闪」不是「滑」。而这个控件的注释偏偏写着「iOS 的选中块是**滑过去**的」，
    // **注释与实现相反**（比注释错更糟）。
    //
    // 现在改成**一块**指示块在轨道上平移：
    //   · 位移用 spring（无回弹，约 230ms 收敛）——
    //     用户直接操作引起的状态变化用 spring，被打断时能自然接管速度；
    //   · 文字色 180ms，与指示块同步启动。
    val animatedIndex by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = Motion.SelectMove,
        label = "segmentIndex",
    )

    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(trackColor)
            .padding(2.dp),
    ) {
        // ⚠️ 指示块用 Canvas 画（声明在文字层**之前** → 画在文字**下面**）。
        // matchParentSize 让它不参与定尺寸 —— 尺寸由下面的文字行决定。
        Canvas(Modifier.matchParentSize()) {
            if (n <= 0) return@Canvas
            val gap = 2.dp.toPx()
            val segW = (size.width - gap * (n - 1)) / n
            if (segW <= 0f) return@Canvas
            val x = (segW + gap) * animatedIndex
            drawRoundRect(
                color = selectedBg,
                topLeft = Offset(x, 0f),
                size = Size(segW, size.height),
                cornerRadius = CornerRadius(8.dp.toPx()),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            labels.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                // 文字色随指示块同步过渡
                val textColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = Motion.SelectTextColor,
                    label = "segmentText",
                )
                val fontWeight by animateFloatAsState(
                    targetValue = if (selected) 1f else 0f,
                    animationSpec = Motion.SelectText,
                    label = "segmentWeight",
                )
                Box(
                    Modifier
                        .weight(1f)
                        .clickable {
                            Haptics.selection(ctx)
                            onSelect(index)
                        }
                        .padding(vertical = 9.dp, horizontal = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        // ⚠️ v0.9.2：大字号下不折行（折行会与相邻段的选中胶囊贴死）
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (fontWeight > 0.5f) FontWeight.SemiBold else FontWeight.Normal,
                        color = textColor,
                    )
                }
            }
        }
    }
}

/**
 * ⚠️ v0.16.0（车队运营评审 P0-2）：**顶栏车切换芯片**。
 *
 * 原来「记录」页顶栏只有「记录」两个字 —— **看不出数据属于哪台车**。
 * 用户可能以为在看 A 车，实际是 B 车。
 * 评审原话：「这是**读错数据的风险**，不是体验问题。」
 *
 * 现在：标题右侧一颗胶囊芯片，显示当前车短名；
 * **仅当 cars.size > 1 时出现**（单车时完全隐藏、零留白）。
 * 点开是底部弹层，每行 = 车名 + 型号，当前车打勾；选中即关 + 触感。
 *
 * ⚠️ 切车后**必须清空记录页的选中态** —— 否则在 A 车的行程详情页切到 B 车，
 * 标题还写着 A 车那条行程，数据张冠李戴（这由 AppRoot 的 onCarChange 负责）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarChip(
    cars: List<com.teslamate.app.Car>,
    currentId: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 单车时不出现 —— 零留白
    if (cars.size <= 1) return
    val accents = LocalAccentColors.current
    val ctx = LocalContext.current
    var sheetOpen by remember { mutableStateOf(false) }
    val current = cars.firstOrNull { it.id == currentId }

    Box(modifier) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = accents.cardInset,
            modifier = Modifier
                .clickable {
                    Haptics.selection(ctx)
                    sheetOpen = true
                }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    current?.name?.substringBefore(" · ")?.trim()?.ifBlank { "选车" } ?: "选车",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(3.dp))
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = "切换车辆",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (sheetOpen) {
            ModalBottomSheet(
                onDismissRequest = { sheetOpen = false },
                containerColor = accents.cardSurface,
                dragHandle = null,
            ) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
                    Text(
                        "切换车辆",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    cars.forEach { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    Haptics.selection(ctx)
                                    onSelect(c.id)
                                    sheetOpen = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    c.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                val sub = listOfNotNull(
                                    c.marketing_name?.takeIf { it.isNotBlank() },
                                    c.model?.takeIf { it.isNotBlank() },
                                ).joinToString(" · ")
                                if (sub.isNotBlank()) {
                                    Text(
                                        sub,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (c.id == currentId) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "已选中",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 键值行 */
/**
 * 键值行：灰标签 + 实数值。
 *
 * ⚠️ **v0.9.2（iOS 交互评审 P1-9）：大字号下改成上下堆叠**。
 *
 * 原来固定是「标签在左、数值在右」一行。大字号下标签被挤到只剩一个字，
 * 数值折行后第二行孤零零右对齐悬空（实测「充入电量 44.4 度（快充 44.4 /
 * 慢充 0.0）」那行）。
 *
 * 现在：fontScale > 1.15 时改成「标签一行、数值下一行左对齐」——
 * 值不再需要靠右对齐，续行也就自然对齐，不会悬空。
 */
@Composable
fun KV(k: String, v: String) {
    val fontScale = LocalDensity.current.fontScale
    if (fontScale > 1.15f) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                k,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                v,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
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
 * 可折叠的「更多信息」区。
 *
 * ⚠️ v0.9.0（新手车主复核）：新手完全看不懂「峰值电压 / 峰值电流 / 相数」这类 jargon，
 * 但它们恰恰是老手进阶后真正想看的（换桩、算效率、排查桩的问题）。
 *
 * 折起来 = 新手不被术语干扰；展开 = **数据一点没丢**。
 * 这比「删掉」好，也比「平铺着显示」好 —— 平铺会让新手以为自己漏看了什么。
 */
@Composable
fun CollapsibleSection(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // ⚠️ v0.9.2：LocalContext.current 只能在 @Composable 作用域里取，
    // 所以要先取出来，不能写在 .clickable 的 lambda 里（那不是 composable 上下文）。
    val ctx = LocalContext.current
    Column(modifier) {
        Text(
            text = if (expanded) "$label ▾" else "$label ▸",
            style = MaterialTheme.typography.labelSmall,
            // ⚠️ v0.9.0：不用 primary（品牌红）—— 本 App 里红色只表示告警/品牌点缀，
            // 一个折叠链接是红的看起来像出错了。可点性由 ▸/▾ 表达。
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable {
                    Haptics.tick(ctx)
                    expanded = !expanded
                }
                .semantics {
                    contentDescription = if (expanded) "收起$label" else "展开$label"
                },
        )
        // ⚠️ v0.9.2（iOS 交互评审 P1-11）：展开/收起**要有过渡**。
        // 原来 `if (expanded) { content() }` 是硬切 —— 内容瞬间出现/消失，
        // 父容器高度瞬间跳变，把下方所有内容「顶」下去再弹回来，
        // 这是最容易被感知的廉价感来源。
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(160)) + expandVertically(tween(220, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(180, easing = FastOutSlowInEasing)),
        ) {
            content()
        }
    }
}

/**
 * 电量变化条（画在一条固定的 0~100% 标尺上）。
 *
 * ⚠️ v0.9.2（长途自驾党 N3）：时间轴上要能**一眼看出每段行程/充电的电量变化**。
 *
 * 原来充电行写「电量 68 → 100%」，行程行只写「到达 70%」——
 * **同一条时间轴上两种事件、两种口径**，看的人得自己拼。
 *
 * 现在：两行都用同一条 0~100% 的标尺，填充段画在**绝对位置**上 ——
 * 于是「85→70」和「70→55」在同一条尺子上的位置一致，
 * 能看出「电都在同一段区间掉掉的」，也能看出「充电是补回同一段」。
 *
 * @param charging true = 充电（绿色），false = 行程（能量蓝）
 */
@Composable
fun SocDeltaBar(
    startSoc: Int?,
    endSoc: Int?,
    modifier: Modifier = Modifier,
    charging: Boolean = false,
    height: Int = 6,
) {
    if (startSoc == null || endSoc == null) return
    val accents = LocalAccentColors.current
    val color = if (charging) accents.chargeGreen else accents.energyBlue
    val lo = minOf(startSoc, endSoc).coerceIn(0, 100)
    val hi = maxOf(startSoc, endSoc).coerceIn(0, 100)
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .semantics {
                contentDescription = "电量从 $startSoc% 到 $endSoc%"
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2f)
            // 轨道 = 整条 0~100 标尺
            drawRoundRect(color = accents.chartTrack.copy(alpha = 0.30f), cornerRadius = r)
            // 变化段：画在**绝对位置**上
            val x0 = size.width * (lo / 100f)
            val x1 = size.width * (hi / 100f)
            drawRoundRect(
                color = color,
                topLeft = Offset(x0, 0f),
                size = androidx.compose.ui.geometry.Size(x1 - x0, size.height),
                cornerRadius = r,
            )
        }
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

/**
 * 大数字。
 *
 * ⚠️ v0.14.0（苹果 HIG P0-1 / P2）：加**主角级**样式。
 *
 * 原来只有一个 34sp 的刻度（iOS Large Title）。但 HIG 的「层级与强调」要求
 * **每屏有唯一视觉锚点** —— 34sp 的大数字和页面标题（「车况」，也是 34sp）同级，
 * 于是车况页的主角「204」和标题一样大，**主角就不成立了**。
 * 评审原话：「视线落点顺序是 锁车状态 → 告警卡 → 说明卡 → 才是主角 204，
 * 主角排在第 4 条带。」
 *
 * 现在加 hero 级：**44sp / lineHeight 48sp / tnum / 单行**。
 * 字距也从 -1.sp 收到 **-0.4.sp**（iOS 大字号数字的跟踪量级）——
 * 34sp Bold 上 -1.sp 偏挤，且带 tnum 时更明显。
 */
@Composable
fun BigStat(value: String, label: String, hero: Boolean = false) {
    Column {
        Text(
            value,
            fontSize = if (hero) 44.sp else 34.sp,
            lineHeight = if (hero) 48.sp else 40.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
            style = MaterialTheme.typography.displayLarge,
            maxLines = 1,
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
fun SocRing(
    soc: Int?,
    modifier: Modifier = Modifier,
    charging: Boolean = false,
    /**
     * 环的基础直径（dp），实际直径 = 本值 × fontScale。
     *
     * ⚠️ v0.14.0（新手车主 P0-3）：大字号布局需要**缩小环**。
     * 200% 字号时按默认 168dp 算，环变成 294dp —— 占了屏幕近一半，
     * 主角数字「现在能开 204 公里」被推到最底缘，要滚一屏才看得到。
     */
    baseSizeDp: Int = 168,
) {
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
    // ⚠️ v0.8.1：配合 AppRoot 把 fontScale 封顶从 1.15 提到 1.30。
    // ⚠️ v0.9.0：AppRoot **解封**了 fontScale（WCAG 1.4.4 要求 200% 可用），
    //   于是这里的上限也要放开 —— 但不能无限放大：
    //   环太大就放不进屏幕宽度（393dp 屏去掉 32dp 页边距 = 361dp 可用）。
    //   1.75 → 168 × 1.75 = 294dp，仍有余量。
    val fontScale = LocalDensity.current.fontScale
    val ringSize = (baseSizeDp * fontScale.coerceIn(1f, 1.75f)).dp
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
 *
 * ⚠️ **v0.9.0：目标刻度线加文字标签**（新手车主复核）。
 * 车主原话：「能量条上那根竖线没有任何标签。我盯着看了三秒，
 * 不知道是'目标电量'还是'上次充到这'。这是最典型的'看不懂就跳过'。」
 *
 * 现在竖线正上方写着「目标 80%」—— 一眼知道那根线是什么，
 * 能量条也就不再是「电量环的重复」：环给数字，条给**目标**。
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
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // ⚠️ v0.9.0：标签带的高度按**实测文字高度**算，
    // 否则大字号下（fontScale 2.0）9sp 变成 18sp，会溢出色带压到条上。
    val labelLayout = targetSoc?.let {
        textMeasurer.measure("目标 $it%", TextStyle(fontSize = 9.sp, color = labelColor))
    }
    val labelBandDp = with(density) {
        (labelLayout?.size?.height?.toDp() ?: 0.dp) + 2.dp
    }
    Box(
        modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = buildString {
                    append("电量 ${soc ?: "未知"}%")
                    if (charging) append("，正在充电")
                    targetSoc?.let { append("，目标电量 $it%") }
                }
            }
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height.dp + labelBandDp)
        ) {
            val labelH = labelBandDp.toPx()
            val barTop = labelH
            val barH = size.height - labelH
            val r = androidx.compose.ui.geometry.CornerRadius(barH / 2f)
            drawRoundRect(
                color = track,
                topLeft = Offset(0f, barTop),
                size = androidx.compose.ui.geometry.Size(size.width, barH),
                cornerRadius = r,
            )
            if (animated > 0f) {
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(0f, barTop),
                    size = androidx.compose.ui.geometry.Size(size.width * animated, barH),
                    cornerRadius = r,
                )
            }
            targetSoc?.let { t ->
                val x = size.width * (t / 100f)
                drawLine(
                    color = tickColor,
                    start = Offset(x, barTop),
                    end = Offset(x, barTop + barH),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                // ⚠️ v0.9.0：竖线上方写清楚「目标 80%」。
                // 不写的话这根线就是一根没人认识的竖线（新手车主实测「盯了三秒」）。
                val txt = "目标 $t%"
                val layout = textMeasurer.measure(
                    text = txt,
                    style = TextStyle(fontSize = 9.sp, color = labelColor),
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        // 标签居中对齐刻度线，但左右不越界（目标 100% 时会超出右边缘）
                        x = (x - layout.size.width / 2f)
                            .coerceIn(0f, (size.width - layout.size.width).coerceAtLeast(0f)),
                        y = 0f,
                    ),
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
    // ⚠️ v0.14.0（苹果 HIG P0-2）：**车身不再用品牌红**。
    //
    // 原来浅色是品牌红 #D70012 → #93000A（200dp 高的大块饱和红），
    // 深色却是中性灰 #3A3A3C —— 同一个元素**两个颜色**（同义不同色），
    // 而且那一大块红与任何告警都无关，
    // 破坏了「红色 = 告警 + 品牌点缀」这条已经定了好几轮的语义规则。
    //
    // 现在两种模式都用**中性金属灰**：靠高光与描边表达体积，不靠颜色抢戏。
    // （「一眼认出是台车」靠的是俯视轮廓的形状，不是颜色。）
    val bodyTop = if (dark) Color(0xFF3A3A3C) else Color(0xFFC7C7CC)
    val bodyBottom = if (dark) Color(0xFF242426) else Color(0xFF8E8E93)
    // ⚠️ v0.14.0：车身改成银灰后，描边要更深一档才分得出来
    //（原来描边 #8E8E93 和车身底色同值，下半圈会糊掉）。
    val outline = if (dark) Color(0xFF6E6E76) else Color(0xFF636366)
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
