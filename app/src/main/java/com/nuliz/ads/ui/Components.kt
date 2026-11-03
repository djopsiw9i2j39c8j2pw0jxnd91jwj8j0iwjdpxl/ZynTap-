package com.nuliz.ads.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuliz.ads.BlockEvent
import com.nuliz.ads.Category
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val C = NulizColors
private val CardShape = RoundedCornerShape(24.dp)
private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

private val Category.color get() = if (this == Category.ADS) C.Violet else C.Danger

/** Nút nguồn: tắt = xám tối, bật = gradient tím + vòng sóng lan toả. */
@Composable
fun PowerButton(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val progress by animateFloatAsState(if (active) 1f else 0f, tween(600), label = "power")
    // Chỉ chạy animation vô hạn khi đang bật để không tốn pin lúc tắt.
    val wave: State<Float> = if (active) {
        rememberInfiniteTransition(label = "wave")
            .animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "w")
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.94f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )

    Box(
        modifier
            .size(240.dp)
            .scale(scale)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2
            val body = r * 0.56f

            repeat(2) { i ->
                val t = (wave.value + i * 0.5f) % 1f
                drawCircle(
                    C.Violet.copy(alpha = (1f - t) * 0.35f * progress),
                    radius = r * (0.62f + 0.38f * t),
                    style = Stroke(2.dp.toPx()),
                )
            }
            drawCircle(Brush.radialGradient(listOf(C.Violet.copy(alpha = 0.40f * progress), Color.Transparent), c, r), r)

            drawCircle(C.SurfaceHigh, body)
            drawCircle(
                Brush.linearGradient(listOf(C.Violet, C.Indigo), Offset(c.x - body, c.y - body), Offset(c.x + body, c.y + body)),
                body,
                alpha = progress,
            )
            drawCircle(lerp(C.Outline, C.Violet, progress), body, style = Stroke(2.dp.toPx()))

            // biểu tượng nguồn: cung 300° + gạch đứng
            val icon = lerp(C.Muted, Color.White, progress)
            val ir = body * 0.38f
            val stroke = Stroke(6.dp.toPx(), cap = StrokeCap.Round)
            drawArc(icon, -60f, 300f, false, Offset(c.x - ir, c.y - ir), Size(ir * 2, ir * 2), style = stroke)
            drawLine(icon, Offset(c.x, c.y - ir * 1.15f), Offset(c.x, c.y - ir * 0.1f), stroke.width, StrokeCap.Round)
        }
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Brush.verticalGradient(listOf(C.SurfaceHigh, C.Surface)))
            .border(1.dp, C.Outline, CardShape)
            .padding(18.dp),
        content = content,
    )
}

/** Số lớn chạy dần lên mỗi khi có quảng cáo mới bị chặn. */
@Composable
fun CounterCard(total: Long, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(total.toFloat(), tween(700, easing = FastOutSlowInEasing), label = "count")
    Panel(modifier) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                NumberFormat.getIntegerInstance().format(shown.toLong()),
                style = TextStyle(brush = C.Brand, fontSize = 60.sp, fontWeight = FontWeight.Black),
            )
            Text("quảng cáo & mối đe doạ đã chặn", color = C.Muted, fontSize = 13.sp)
        }
    }
}

@Composable
fun StatTile(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(C.Surface)
            .border(1.dp, C.Outline, RoundedCornerShape(20.dp))
            .padding(14.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.size(10.dp))
        Text(value, color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = C.Muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .clip(CircleShape)
            .border(1.dp, C.Outline, CircleShape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = C.Violet,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
fun EventRow(event: BlockEvent, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(event.category.color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                event.domain,
                color = C.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${event.category.label} • ${timeFormat.format(Date(event.time))}", color = C.Muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun Hairline() = Box(Modifier.fillMaxWidth().height(1.dp).background(C.Outline))

@Composable
fun TabBar(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .clip(shape)
            .background(C.Surface)
            .border(1.dp, C.Outline, shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val color by animateColorAsState(if (on) Color.White else C.Muted, label = "tab")
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .then(if (on) Modifier.background(C.Brand) else Modifier)
                    .clickable { onSelect(i) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
