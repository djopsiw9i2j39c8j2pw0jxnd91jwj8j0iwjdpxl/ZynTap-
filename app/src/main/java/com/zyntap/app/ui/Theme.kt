package com.zyntap.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Bảng màu của ZynTap, lấy theo tông xanh của logo. */
object Zyn {
    val Bg = Color(0xFF050A15)
    val Surface = Color(0xFF0D1524)
    val SurfaceHi = Color(0xFF142036)
    val Border = Color(0xFF1E2B44)
    val Cyan = Color(0xFF19D3FF)
    val Blue = Color(0xFF2A6BFF)
    val Text = Color(0xFFE8EEFA)
    val TextDim = Color(0xFF8D9DBA)
    val Ok = Color(0xFF34D399)
    val Warn = Color(0xFFF5B544)
    val Danger = Color(0xFFEF5B5B)
}

private val ZynColors = darkColorScheme(
    primary = Zyn.Cyan,
    onPrimary = Color(0xFF00141C),
    secondary = Zyn.Blue,
    background = Zyn.Bg,
    onBackground = Zyn.Text,
    surface = Zyn.Surface,
    onSurface = Zyn.Text,
    surfaceVariant = Zyn.SurfaceHi,
    onSurfaceVariant = Zyn.TextDim,
    outline = Zyn.Border,
    error = Zyn.Danger,
)

@Composable
fun ZynTapTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ZynColors, content = content)
}
