package com.moa.calendar.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF242A3D)
val Muted = Color(0xFF778095)
val Accent = Color(0xFF5965D8)
val CanvasColor = Color(0xFFF8F9FC)
val LineColor = Color(0xFFEDF0F5)
val AccentWash = Color(0xFFEEF0FF)

@Composable fun MoaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = Accent, onPrimary = Color.White, background = CanvasColor,
            surface = Color.White, onSurface = Ink, onBackground = Ink, surfaceVariant = AccentWash,
            onSurfaceVariant = Muted, outline = Color(0xFFD7DBE8)),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
            titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 22.sp, fontWeight = FontWeight.Bold),
            titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp),
            labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
        ), content = content,
    )
}
