package com.me.jiakao.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

val JiakaoTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.em),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.em),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.em),
        bodyLarge = bodyLarge.copy(lineHeight = 26.sp, letterSpacing = 0.em),
        bodyMedium = bodyMedium.copy(lineHeight = 22.sp, letterSpacing = 0.em),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}
