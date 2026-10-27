package com.teslamate.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * iOS 式 push 转场（充电详情 / 行程详情共用）。
 *
 * 旧实现是「新页全宽推入 + 旧页淡出并滑走 10%」，观感像网页跳转：旧内容直接消失。
 * iOS 的 push 是**层级**动画 —— 新页推入时旧页**留在下面**，以 30% 视差向左退让，
 * 新页左缘带一道投影把两层分开。观感是「叠了一层」，不是「换了一张」。
 *
 * 反向（pop）时旧页退场 30% 视差、新页从 -30% 归位，与 forward 完全对称。
 */
fun <T> pushDetailTransition(
    scope: AnimatedContentTransitionScope<T>,
): ContentTransform {
    val forward = scope.targetState != null
    return if (forward) {
        (slideInHorizontally(animationSpec = tween(Motion.Push)) { it } + fadeIn(tween(Motion.PushFade)))
            .togetherWith(
                fadeOut(tween(Motion.PushFadeOut)) + slideOutHorizontally(animationSpec = tween(Motion.Push)) { -it * 3 / 10 }
            )
    } else {
        (fadeIn(tween(Motion.PushFade)) + slideInHorizontally(animationSpec = tween(Motion.Push)) { -it * 3 / 10 })
            .togetherWith(
                slideOutHorizontally(animationSpec = tween(Motion.Push)) { it } + fadeOut(tween(Motion.PushFadeOut))
            )
    }
}

/**
 * **转场是否正在进行中** —— 供边缘投影决定「该不该出现」。
 *
 * ⚠️ **v0.71.0（苹果设计总监复审）**：这条是**修「页面最左侧一条黑灰渐变」的关键**。
 *
 * ## 原来错在哪
 *
 * 原实现把投影画成 `AnimatedContent` 内容里的一个**常驻**节点
 * （`if (sel != null) PushEdgeShadow(...)`）——
 * 它的设计意图是「滑动过程中，让旧页上出现一道移动的阴影，让层级关系可见」，
 * 但它**只在动画期间才有意义**，动画结束后它就**永久钉在屏幕左缘**：
 *
 *   - 像素实测（1440×3200 @3.75x）：左缘一条 **60px 宽**（= 16dp）的暗带，
 *     x=0 处亮度 165、x=60 处回到背景 242 ⇒ 正是 `Black @32%` 压浅色底；
 *   - 纵向完全均匀 ⇒ 它是**硬边黑带**，不是真阴影；
 *   - 三个页面（行程详情 / 充电记录 / 充电详情）全部中招 ⇒ 用户读作「渲染 bug」。
 *
 * ## iOS 怎么做
 *
 * `UINavigationController` 推入时，被推的 VC 左缘**确实**有阴影，
 * 但那是因为**它下面还有旧 VC**（旧 VC 只退到 -30%，左缘仍露出）。
 * 推完之后新 VC **铺满全屏**，它的左缘就是**屏幕边缘** ——
 * **屏幕上不该有任何东西**。iOS 从来不画「常驻的屏幕左缘黑边」。
 *
 * ## 做法
 *
 * 阴影的可见性由**父层**持有的 [rememberPushTransitionActive] 决定，
 * 只在 push / pop 转场进行中的 [Motion.Push] 毫秒内为 true。
 * 转场结束 ⇒ 投影消失 ⇒ 屏幕左缘干干净净。
 *
 * ⚠️ **为什么状态要放在父层**：`PushEdgeShadow` 位于 `AnimatedContent` 的内容 lambda 内，
 * push 时该内容是**全新的一次组合** —— 组件内部的 `remember` 会重新初始化，
 * 自己管自己的计时就会「第一次进来永远是 false」。
 */
@Composable
fun rememberPushTransitionActive(key: Any?): Boolean {
    var active by remember { mutableStateOf(false) }
    // 首次组合不算「转场」—— 否则 App 一打开就会闪一道投影。
    var primed by remember { mutableStateOf(false) }
    LaunchedEffect(key) {
        if (!primed) {
            primed = true
            return@LaunchedEffect
        }
        active = true
        delay(Motion.Push.toLong())
        active = false
    }
    return active
}

/**
 * 新页左缘投影 —— **只在转场期间**出现（见 [rememberPushTransitionActive]）。
 *
 * ⚠️ **v0.71.0**：同时把它从「硬边黑带」改成**柔和的软阴影**：
 *
 * | | 原来 | 现在 |
 * |---|---|---|
 * | 宽度 | 16dp | 20dp（更宽 ⇒ 读作柔光，不是黑带） |
 * | 强度 | `Black @32%` 线性 | 峰值 `Black @20%`，四段衰减 |
 * | 形态 | 线性黑→透明 | 中心浓、两端淡，逼近真实模糊阴影 |
 *
 * 真阴影是**模糊**的 —— 离物体越远越淡、边界没有硬切口。
 * 原来的线性渐变在边缘有一个明显的硬起点，所以读作「贴了一条黑胶带」。
 */
@Composable
fun PushEdgeShadow(modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(20.dp)
            .fillMaxHeight()
            .background(
                Brush.horizontalGradient(
                    // ⚠️ 四段而不是两段：模拟模糊衰减的「近浓远淡」，
                    // 两段线性渐变在 x=0 处会有可见的硬切口。
                    0.00f to Color.Black.copy(alpha = 0.20f),
                    0.30f to Color.Black.copy(alpha = 0.09f),
                    0.65f to Color.Black.copy(alpha = 0.03f),
                    1.00f to Color.Transparent,
                )
            )
    )
}
