package com.zyntap.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Nút tròn có icon. Khi `icon` đổi (vd. menu -> mũi tên quay lại) icon sẽ
 * xoay chuyển mượt thay vì nhảy cái rụp.
 */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    accent: Boolean = false,
) {
    val background = if (accent) {
        Modifier.background(Brush.linearGradient(listOf(Zyn.Blue, Zyn.Cyan)), CircleShape)
    } else {
        Modifier
            .background(Zyn.SurfaceHi, CircleShape)
            .border(1.dp, Zyn.Border, CircleShape)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = icon,
            transitionSpec = {
                (fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.6f)) togetherWith
                    (fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.6f))
            },
            contentAlignment = Alignment.Center,
            label = "round-icon",
        ) { current ->
            Icon(
                imageVector = current,
                contentDescription = description,
                tint = if (accent) Color.White else Zyn.Text,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}
