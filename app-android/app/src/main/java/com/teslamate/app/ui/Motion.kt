package com.teslamate.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged

/**
 * **动效 token** —— 全 App 的动画规格集中在这里。
 *
 * ⚠️ v0.14.0（iOS 动效与材质专项评审）：
 * 原来每个控件各自写 `tween(220)` / `tween(160)`，
 * 于是「折叠区 220ms」「分段控件 220ms」「图表 520ms」这些数字散在十个文件里，
 * 互相之间没有任何关系 —— 整体节奏对不上，而且改一处漏一处。
 *
 * 现在全部收进这里，**一条原则**：
 *
 * > **用户直接操作引起的状态变化 → spring**
 * > **系统自动发生的时间过程 → tween**
 *
 * spring 被打断时会自然接管速度（连点不会出现速度断裂），
 * tween 被打断则会硬跳 —— 所以「滑过去」用 spring，「淡入」用 tween。
 */
object Motion {
    /** iOS 的标准 ease-out：起步快、收尾稳。 */
    val EaseOut: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** 按下：60ms 进去。要快 —— 按下的反馈必须**即时**，否则用户以为没点到。 */
    val PressIn = tween<Float>(60, easing = FastOutSlowInEasing)

    /** 松手：200ms 回去。要比按下慢 —— 回弹本身是能被眼睛追踪的运动。 */
    val PressOut = tween<Float>(200, easing = FastOutSlowInEasing)

    /** 分段控件选中块位移：无回弹（damping=1），约 230ms 收敛。 */
    val SelectMove = spring<Float>(
        dampingRatio = 1f,
        stiffness = 400f,
    )

    /** 分段控件文字色：180ms，与选中块同步启动。 */
    val SelectTextColor = tween<androidx.compose.ui.graphics.Color>(180, easing = FastOutSlowInEasing)

    /** 分段控件字重：180ms（数值型，用 Float 规格）。 */
    val SelectText = tween<Float>(180, easing = FastOutSlowInEasing)

    /** Tab 切换交叉淡入淡出（iOS UITabBarController：无位移）。 */
    const val TabFadeIn = 140
    const val TabFadeOut = 120

    /** push 推入 + 旧页 30% 视差。 */
    const val Push = 380

    /** 折叠区：高度 280ms，淡入 200ms（线性慢出），淡出 140ms。 */
    const val ExpandHeight = 280
    const val ExpandFadeIn = 200
    const val ExpandFadeOut = 140

    /** 折叠箭头旋转 0↔180°。 */
    const val ChevronRotate = 240

    /** 图表首次描边生长。 */
    const val ChartGrow = 520

    /** 切换周期时柱高过渡（原来只有布尔驱动的「首次生长」，切段是硬切）。 */
    const val ChartMorph = 300

    /** 主角数字滚动：无回弹。 */
    val DataValue = spring<Float>(
        dampingRatio = 1f,
        stiffness = 350f,
    )

    /** 刷新图标：700ms/圈匀速；结束后 200ms 平滑回正。 */
    const val RefreshSpin = 700
    const val RefreshSettle = 200

    /** 胎压呼吸：2400ms 线性往复（原来 1600ms 是「喘气」不是「呼吸」）。 */
    const val TpmsPulse = 2400

    /** 骨架屏微光。 */
    const val Shimmer = 1400

    // ⚠️ **v0.30.0（第 19 轮 HIG 评审 P2-5）：补齐没有 token 的写死时长。**
    //
    // 这一批都是「系统自动发生的时间过程」→ tween。
    // 原来它们各自写死（600 / 500 / 200 / 160 / 180），
    // 于是同一个 App 里有两套节奏 —— 改一处漏一处的老毛病。

    /** 电量环数值滚动：600ms（数值从旧值补间到新值）。 */
    const val RingValue = 600

    /** 能量条填充：500ms。 */
    const val EnergyBar = 500

    /** push 页面淡入 / 淡出。 */
    const val PushFade = 200
    const val PushFadeOut = 160

    /** 开关滑块位移与轨道变色：180ms（与分段控件的文字色同步）。 */
    const val SwitchToggle = 180

    /** 按下瞬间的 3D 倾斜：spring，约 130ms。 */
    val TiltPress = spring<Float>(
        dampingRatio = 1f,
        stiffness = 1200f,
    )

    /** Tab 图标选中微动。 */
    val IconPop = spring<Float>(
        dampingRatio = 0.8f,
        stiffness = 900f,
    )

    /** 淡入（线性慢出，比默认 ease-in 更「稳」）。 */
    val FadeIn = tween<Float>(ExpandFadeIn, easing = LinearOutSlowInEasing)

    /** 匀速（旋转、微光）。 */
    val Linear = LinearEasing
}

/**
 * 3D 倾斜的状态容器：记住自身宽度，把 `cameraDistance` 设成 **3 × 宽（px）**。
 *
 * 用法：
 * ```
 * val tilt = remember { Tilt3DState() }
 * Box(Modifier.tilt3DState(tilt, rotationY = if (pressed) -1.5f else 0f)) { ... }
 * ```
 *
 * ⚠️ v0.14.0（iOS 动效与材质专项评审 P0-3）：
 * **cameraDistance 的规格必须自己算，不能拍脑袋。**
 *
 * 常见误解是「cameraDistance = 12~16dp」—— 那是错的，而且错得很离谱：
 *
 * - `cameraDistance` 的单位是**像素**，不是 dp（AOSP `GraphicsLayer.kt`：
 *   "The distance is expressed in pixels"）。
 * - Compose 的**默认值是 8.0f 像素** —— 不显式设置 = 相机贴在屏幕表面。
 * - 相机离得越近，透视越夸张。卡片宽 330dp（2.75x 密度 → 907px）、
 *   旋转 2.5° 时近边的 z 偏移是 `453.75 × sin(2.5°) = 19.79px`：
 *
 * | cameraDistance | 近边缩放 | 远边缩放 | 结论 |
 * |---|---|---|---|
 * | 8px（Compose 默认） | 近边跑到相机**背后** | — | 破形 |
 * | 44px（16dp） | **1.817** | 0.690 | 近边放大 82%，灾难性鱼眼 |
 * | 907px（= 宽） | 1.0223 | 0.9787 | 2.2%，勉强可辨 |
 * | **2722px（= 3×宽）** | **1.0073** | 0.9928 | ±0.73%，合格 |
 *
 * 写成比例就**与密度、与屏幕宽都无关**：`z/d = sinθ / (2k)`，k=3 时 z/d = 0.73%。
 *
 * 所以唯一规则是：
 *
 * > **cameraDistance = 3 × 元素宽度（px）**
 *
 * 用 [onSizeChanged] 量出真实宽度再写进去 —— 绝不用 dp 常量（会随密度漂移），
 * 也绝不用框架默认值（8px）。
 *
 * ⚠️ 另外：**静态列表卡片一律不要给几何旋转** ——
 * 361dp 宽的卡片转 2.5°，右边缘相对左边缘下沉 15.7dp，
 * 一屏卡片的左右边界全部不平行，读作「歪了」而不是「高级」。
 * 3D 只用在**按下瞬间**和**单个主角元素**上。
 */
class Tilt3DState {
    var widthPx: Float = 0f

    /** 3 × 宽（px）—— 见 [Motion.kt] 顶部的推导。 */
    val cameraDistance: Float get() = (widthPx * 3f).coerceAtLeast(1f)
}

/** 与 [Tilt3DState] 配套的 Modifier：自动写入正确的 cameraDistance。 */
fun Modifier.tilt3DState(
    state: Tilt3DState,
    rotationX: Float = 0f,
    rotationY: Float = 0f,
): Modifier = this
    .onSizeChanged { state.widthPx = it.width.toFloat() }
    .graphicsLayer {
        cameraDistance = state.cameraDistance
        this.rotationX = rotationX
        this.rotationY = rotationY
    }
