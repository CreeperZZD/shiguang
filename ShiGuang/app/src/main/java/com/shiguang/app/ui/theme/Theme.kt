package com.shiguang.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 品牌色：陶土橘 × 奶油白 × 墨色
val Terracotta = Color(0xFFE2725B)
val Cream = Color(0xFFFFF8F0)
val Ink = Color(0xFF2D2A26)

private val LightColors = lightColorScheme(
    primary = Terracotta,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBD1),
    onPrimaryContainer = Color(0xFF3B0B02),
    secondary = Color(0xFF3F5E5A),
    onSecondary = Color.White,
    background = Cream,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF3E7DE),
    onSurfaceVariant = Color(0xFF5E564E),
    error = Color(0xFFB3261E),
)

// 深色：全面提亮次级文字，保证夜间可读性
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4A3),
    onPrimary = Color(0xFF5C1A0C),
    primaryContainer = Color(0xFF7D2E1D),
    onPrimaryContainer = Color(0xFFFFDBD1),
    secondary = Color(0xFFB4CCC6),
    onSecondary = Color(0xFF22302D),
    background = Color(0xFF191614),
    onBackground = Color(0xFFF3EAE1),
    surface = Color(0xFF231F1C),
    onSurface = Color(0xFFF3EAE1),
    surfaceVariant = Color(0xFF3E3733),
    onSurfaceVariant = Color(0xFFD6CBC0),
    error = Color(0xFFFFB4AB),
)

// 全局字号：整体调大约 1~2sp，便于手机阅读
private val AppTypography = Typography(
    displayMedium = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp),
    titleLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.Bold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 13.sp),
    labelSmall = TextStyle(fontSize = 13.sp, lineHeight = 17.sp),
)

@Composable
fun ShiGuangTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
