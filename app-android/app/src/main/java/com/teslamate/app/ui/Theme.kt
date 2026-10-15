package com.teslamate.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 设计语言：Material 3（Telegram / Google Play 式）。
 * - 层级用 tonal surface 色调区分，不用阴影
 * - 主色特斯拉红仅做点缀（选中态、进度、强调）
 * - 深色模式对 OLED 友好（近黑底）
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFFC00016),          // 特斯拉红（tone 压暗，白底可读）
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD5),
    onPrimaryContainer = Color(0xFF410001),
    secondary = Color(0xFF3F5B62),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC2E8ED),
    onSecondaryContainer = Color(0xFF002024),
    tertiary = Color(0xFF00658F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFC7E7FF),
    onTertiaryContainer = Color(0xFF001E31),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAFAFA),        // Telegram 式干净底
    onBackground = Color(0xFF1A1C1D),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF1A1C1D),
    surfaceVariant = Color(0xFFEDEFF1),
    onSurfaceVariant = Color(0xFF41484C),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F5F7),
    surfaceContainer = Color(0xFFEEEFF2),  // 卡片主色
    surfaceContainerHigh = Color(0xFFE8EAED),
    surfaceContainerHighest = Color(0xFFE2E4E8),
    outline = Color(0xFF72787D),
    outlineVariant = Color(0xFFC1C7CC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4AB),           // 柔红，深底不刺眼
    onPrimary = Color(0xFF690002),
    primaryContainer = Color(0xFF93000B),
    onPrimaryContainer = Color(0xFFFFDAD5),
    secondary = Color(0xFFA3CDD3),
    onSecondary = Color(0xFF04363C),
    secondaryContainer = Color(0xFF224C53),
    onSecondaryContainer = Color(0xFFBFE9EF),
    tertiary = Color(0xFF8ACEFF),
    onTertiary = Color(0xFF00344F),
    tertiaryContainer = Color(0xFF004B70),
    onTertiaryContainer = Color(0xFFC7E7FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101214),        // 近黑
    onBackground = Color(0xFFE2E2E5),
    surface = Color(0xFF101214),
    onSurface = Color(0xFFE2E2E5),
    surfaceVariant = Color(0xFF41484C),
    onSurfaceVariant = Color(0xFFC1C7CC),
    surfaceContainerLowest = Color(0xFF0B0D0F),
    surfaceContainerLow = Color(0xFF181B1E),
    surfaceContainer = Color(0xFF1C1F22),  // 卡片主色
    surfaceContainerHigh = Color(0xFF26292D),
    surfaceContainerHighest = Color(0xFF313438),
    outline = Color(0xFF8B9196),
    outlineVariant = Color(0xFF41484C),
)

@Composable
fun TeslaMateTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
