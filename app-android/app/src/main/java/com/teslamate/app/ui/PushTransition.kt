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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
 * 新页左缘投影。放在新页内容**之上**，随新页一起滑入，
 * 于是滑动过程中旧页上会出现一道移动的阴影 —— 层级关系因此可见。
 */
@Composable
fun PushEdgeShadow(modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(16.dp)
            .fillMaxHeight()
            .background(
                Brush.horizontalGradient(
                    listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent)
                )
            )
    )
}
