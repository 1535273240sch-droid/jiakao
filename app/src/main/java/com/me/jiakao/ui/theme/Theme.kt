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

// 羊皮卷国风典雅配色
// 朱砂印章红 (Primary)
private val CinnabarPrimary = Color(0xFFA93226)
private val CinnabarPrimaryDark = Color(0xFFE57373)
private val CinnabarContainer = Color(0xFFFBE4E1)
private val CinnabarContainerDark = Color(0xFF681A1B)

// 沉香琥珀金 (Secondary)
private val AmberSecondary = Color(0xFFB57C2B)
private val AmberSecondaryDark = Color(0xFFE8BD77)
private val AmberContainer = Color(0xFFF7ECDB)
private val AmberContainerDark = Color(0xFF5F4100)

// 碧玉翠竹绿 (Tertiary / 正确选项)
private val JadeCorrect = Color(0xFF2E7D4E)
private val JadeCorrectDark = Color(0xFF81C784)
private val JadeContainer = Color(0xFFD7F1E1)
private val JadeContainerDark = Color(0xFF134E2A)

private val LightColors = lightColorScheme(
    primary = CinnabarPrimary,
    onPrimary = Color.White,
    primaryContainer = CinnabarContainer,
    onPrimaryContainer = Color(0xFF4C0E0F),
    secondary = AmberSecondary,
    onSecondary = Color.White,
    secondaryContainer = AmberContainer,
    onSecondaryContainer = Color(0xFF422800),
    tertiary = JadeCorrect,
    onTertiary = Color.White,
    tertiaryContainer = JadeContainer,
    onTertiaryContainer = Color(0xFF00391A),
    background = Color(0xFFF9F6EE),       // 经典暖调羊皮纸原色
    onBackground = Color(0xFF261C14),     // 焦墨黑
    surface = Color(0xFFFBF8F2),          // 卷轴白绢面
    onSurface = Color(0xFF261C14),
    surfaceVariant = Color(0xFFEBE3D3),   // 宣纸微灰色
    onSurfaceVariant = Color(0xFF5D5448), // 松烟灰
    outline = Color(0xFFCFBFAB),          // 绢帛微框
)

private val DarkColors = darkColorScheme(
    primary = CinnabarPrimaryDark,
    onPrimary = Color(0xFF531113),
    primaryContainer = CinnabarContainerDark,
    onPrimaryContainer = Color(0xFFFFDAD7),
    secondary = AmberSecondaryDark,
    onSecondary = Color(0xFF422C00),
    secondaryContainer = AmberContainerDark,
    onSecondaryContainer = Color(0xFFFFDF9E),
    tertiary = JadeCorrectDark,
    onTertiary = Color(0xFF00381B),
    tertiaryContainer = JadeContainerDark,
    onTertiaryContainer = Color(0xFFA1F2BC),
    background = Color(0xFF191715),       // 夜读沉香玄木
    onBackground = Color(0xFFEDE6DA),     // 暖白米纸字
    surface = Color(0xFF201D1A),
    onSurface = Color(0xFFEDE6DA),
    surfaceVariant = Color(0xFF332D27),
    onSurfaceVariant = Color(0xFFC7BCAD),
    outline = Color(0xFF5C5246),
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
