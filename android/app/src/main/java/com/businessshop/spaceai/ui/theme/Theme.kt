package com.businessshop.spaceai.ui.theme

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

val Brand = Color(0xFF2F5BFF)
val BrandSoft = Color(0xFF5B8CFF)
val RiskLow = Color(0xFF1E9E6A)
val RiskMedium = Color(0xFFE29A12)
val RiskHigh = Color(0xFFD64545)

private val Light = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E9FF),
    onPrimaryContainer = Color(0xFF0B1B5C),
    secondary = Color(0xFF4E5B7A),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF111521),
    surface = Color.White,
    onSurface = Color(0xFF111521),
    surfaceVariant = Color(0xFFECEEF5),
    onSurfaceVariant = Color(0xFF545B6E),
    outline = Color(0xFFC7CBD8),
    error = RiskHigh,
)

private val Dark = darkColorScheme(
    primary = BrandSoft,
    onPrimary = Color(0xFF07123D),
    primaryContainer = Color(0xFF1A2A6B),
    onPrimaryContainer = Color(0xFFDDE4FF),
    secondary = Color(0xFFB5BFDA),
    background = Color(0xFF0B0F1C),
    onBackground = Color(0xFFE8EBF4),
    surface = Color(0xFF141a2c),
    onSurface = Color(0xFFE8EBF4),
    surfaceVariant = Color(0xFF1E2538),
    onSurfaceVariant = Color(0xFFA9B0C4),
    outline = Color(0xFF3A4259),
    error = Color(0xFFFF7A7A),
)

private val Type = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
)

@Composable
fun SpaceTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
}
