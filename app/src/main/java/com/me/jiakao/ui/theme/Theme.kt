package com.me.jiakao.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.me.jiakao.data.ThemeMode

// 驾校蓝
private val BluePrimary = Color(0xFF0E5FD8)
private val BluePrimaryDark = Color(0xFFADC6FF)
private val BlueContainer = Color(0xFFD8E2FF)
private val BlueContainerDark = Color(0xFF0B4794)
// 交警橙
private val OrangeSecondary = Color(0xFFE8641B)
private val OrangeSecondaryDark = Color(0xFFFFB784)
private val OrangeContainer = Color(0xFFFFDCC7)
private val OrangeContainerDark = Color(0xFF6F3A00)
// 通行绿
private val GreenCorrect = Color(0xFF218A4B)
private val GreenCorrectDark = Color(0xFF7AD98F)
private val GreenContainer = Color(0xFFC4F1CE)
private val GreenContainerDark = Color(0xFF175327)

private val LightColors = lightColorScheme(
    primary = BluePrimary,
    onPrimary = Color.White,
    primaryContainer = BlueContainer,
    onPrimaryContainer = Color(0xFF001A41),
    secondary = OrangeSecondary,
    onSecondary = Color.White,
    secondaryContainer = OrangeContainer,
    onSecondaryContainer = Color(0xFF5B2606),
    tertiary = GreenCorrect,
    onTertiary = Color.White,
    tertiaryContainer = GreenContainer,
    onTertiaryContainer = Color(0xFF072711),
    background = Color(0xFFF7F9FE),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF7F9FE),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF43474E),
    outline = Color(0xFF74777F),
)

private val DarkColors = darkColorScheme(
    primary = BluePrimaryDark,
    onPrimary = Color(0xFF002E69),
    primaryContainer = BlueContainerDark,
    onPrimaryContainer = BlueContainer,
    secondary = OrangeSecondaryDark,
    onSecondary = Color(0xFF4A2000),
    secondaryContainer = OrangeContainerDark,
    onSecondaryContainer = OrangeContainer,
    tertiary = GreenCorrectDark,
    onTertiary = Color(0xFF00391A),
    tertiaryContainer = GreenContainerDark,
    onTertiaryContainer = GreenContainer,
    background = Color(0xFF10141B),
    onBackground = Color(0xFFE1E2E8),
    surface = Color(0xFF10141B),
    onSurface = Color(0xFFE1E2E8),
    surfaceVariant = Color(0xFF282C34),
    onSurfaceVariant = Color(0xFFC3C6CF),
    outline = Color(0xFF8D9199),
)

/**
 * 全局主题:浅色/深色/跟随系统;主色「驾校蓝」,点缀「交警橙」。
 * [fontScale] 为用户在设置中选择的字号缩放(1.0 / 1.15 / 1.3),
 * 叠加到系统 fontScale 上,全 App 文本随之缩放。
 */
@Composable
fun JiakaoTheme(
    themeMode: ThemeMode,
    fontScale: Float,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkColors else LightColors
    val current = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = current.density,
            fontScale = current.fontScale * fontScale,
        )
    ) {
        MaterialTheme(colorScheme = colors, typography = JiakaoTypography, content = content)
    }
}
