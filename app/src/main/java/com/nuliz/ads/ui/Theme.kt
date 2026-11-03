package com.nuliz.ads.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Bảng màu lấy từ logo: tím neon → chàm, trên nền đen. */
object NulizColors {
    val Background = Color(0xFF050508)
    val Surface = Color(0xFF0F0F17)
    val SurfaceHigh = Color(0xFF181824)
    val Outline = Color(0xFF26263A)
    val Text = Color(0xFFF5F3FF)
    val Muted = Color(0xFF8E8BAA)
    val Violet = Color(0xFFB44CFF)
    val Indigo = Color(0xFF5B3BFF)
    val Danger = Color(0xFFFF5C7A)

    val Brand: Brush = Brush.verticalGradient(listOf(Violet, Indigo))
}

private val scheme = darkColorScheme(
    primary = NulizColors.Violet,
    onPrimary = Color.White,
    background = NulizColors.Background,
    onBackground = NulizColors.Text,
    surface = NulizColors.Surface,
    onSurface = NulizColors.Text,
    surfaceVariant = NulizColors.SurfaceHigh,
    onSurfaceVariant = NulizColors.Muted,
    outline = NulizColors.Outline,
    error = NulizColors.Danger,
)

@Composable
fun NulizTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
