package com.zyntap.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyntap.app.BuildConfig
import com.zyntap.app.MainViewModel


@Composable
fun HomeScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 8.dp, bottom = 16.dp),
        ) {
            StatusCard(
                active = vm.isActive,
                wasPaired = vm.isPaired,
                wifiAdbOn = vm.wifiAdbOn,
                wifiConnected = vm.wifiConnected,
                mobileData = vm.mobileData,
                onClick = vm::openActivate,
            )
            Spacer(Modifier.height(14.dp))
            ActivateButton(onClick = vm::openActivate)
            Spacer(Modifier.height(18.dp))
            OverlayControlCard(vm)
            Spacer(Modifier.height(18.dp))
            if (vm.debugEnabled) DebugLogCard(vm)
        }
        Text(
            text = "ZynTap  •  Phiên bản ${BuildConfig.VERSION_NAME}",
            color = Zyn.TextDim,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 14.dp),
        )
    }
}

@Composable
private fun StatusCard(
    active: Boolean,
    wasPaired: Boolean,
    wifiAdbOn: Boolean,
    wifiConnected: Boolean,
    mobileData: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Zyn.Surface)
            .border(1.dp, Zyn.Border, shape)
            .clickable(enabled = !active, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {

        val ready = active && wifiConnected
        val onCount = (if (wifiConnected) 1 else 0) + (if (wifiAdbOn) 1 else 0) + (if (wasPaired) 1 else 0)
        StatusRow(
            dotColor = when {
                ready -> Zyn.Ok
                
                !wifiConnected && !wifiAdbOn && !mobileData -> Zyn.Danger
                else -> Zyn.Warn
            },
            title = if (ready) "Sẵn sàng" else "Chưa sẵn sàng",
            text = when {
                ready -> "Đã kết nối Wi-Fi · ADB đã ghép nối"
                !wifiConnected && wasPaired ->
                    if (mobileData) "Đang dùng dữ liệu di động — nối Wi-Fi rồi bật lại gỡ lỗi Wi-Fi"
                    else "Chưa kết nối Wi-Fi — nối Wi-Fi rồi bật lại gỡ lỗi Wi-Fi"
                !wifiConnected ->
                    if (mobileData) "Chưa kết nối Wi-Fi — đang dùng dữ liệu di động"
                    else "Chưa kết nối Wi-Fi"
                wifiAdbOn -> "Đã nối Wi-Fi · Gỡ lỗi Wi-Fi đang bật, chưa ghép nối ADB"
                wasPaired -> "Đã nối Wi-Fi · Gỡ lỗi Wi-Fi đang tắt, bật lại để dùng"
                else -> "Đã nối Wi-Fi · Chưa ghép nối ADB"
            },
        )
    }
}

@Composable
private fun StatusDot(color: Color) {
    val c by animateColorAsState(color, animationSpec = tween(400), label = "status-dot")
    val breath = rememberInfiniteTransition(label = "status-breath")
    val k by breath.animateFloat(
        initialValue = 1f,
        targetValue = 0.72f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "halo-scale",
    )
    val a by breath.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "halo-alpha",
    )
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(20.dp)
                .graphicsLayer { scaleX = k; scaleY = k; alpha = a }
                .background(c.copy(alpha = 0.19f), CircleShape),
        )
        Box(Modifier.size(10.dp).background(c, CircleShape))
    }
}

@Composable
private fun StatusRow(dotColor: Color, title: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(dotColor)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            
            val swap = tween<Float>(260)
            AnimatedContent(
                targetState = title,
                transitionSpec = {
                    (fadeIn(swap) + slideInVertically(tween(260)) { it / 2 }) togetherWith
                        (fadeOut(tween(160)) + slideOutVertically(tween(260)) { -it / 2 })
                },
                label = "status-title",
            ) { t ->
                Text(t, color = Zyn.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            AnimatedContent(
                targetState = text,
                transitionSpec = {
                    (fadeIn(swap) + slideInVertically(tween(260)) { it / 2 }) togetherWith
                        (fadeOut(tween(160)) + slideOutVertically(tween(260)) { -it / 2 })
                },
                label = "status-text",
            ) { t ->
                Text(text = t, color = Zyn.TextDim, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ActivateButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(Color(0xFF1F5CF0), Color(0xFF12A0EC))))
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Kích hoạt chức năng app",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(Color.White.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Lucide.ChevronRight, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun OverlayControlCard(vm: MainViewModel) {
    val on = vm.overlayEnabled
    val shape = RoundedCornerShape(24.dp)
    val outline by animateColorAsState(if (on) Zyn.Cyan.copy(alpha = 0.60f) else Zyn.Border, tween(220), label = "overlay-outline")
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(if (on) Color(0xFF102B46) else Zyn.Surface, Zyn.Surface)))
            .border(1.dp, outline, shape)
            .padding(20.dp),
    ) {
        Text("KHÔNG GIAN ĐIỀU KHIỂN", color = Zyn.Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .toggleable(value = on, role = androidx.compose.ui.semantics.Role.Switch, onValueChange = vm::changeOverlayEnabled)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(52.dp).background(Zyn.Blue.copy(alpha = 0.16f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Image(painter = androidx.compose.ui.res.painterResource(com.zyntap.app.R.drawable.logo_transparent), contentDescription = null, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("UI nổi", color = Zyn.Text, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                Text(if (on) "Đang hiển thị" else "Đã ẩn hoàn toàn", color = if (on) Zyn.Cyan else Zyn.TextDim, fontSize = 12.sp)
            }
            androidx.compose.material3.Switch(
                checked = on, onCheckedChange = null,
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = Color.White, checkedTrackColor = Zyn.Blue,
                    checkedBorderColor = Zyn.Cyan.copy(alpha = 0.55f),
                    uncheckedThumbColor = Zyn.TextDim, uncheckedTrackColor = Zyn.SurfaceHi,
                    uncheckedBorderColor = Zyn.Border,
                ),
            )
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Zyn.Border))
        Spacer(Modifier.height(14.dp))
        Text(
            if (on) "Chuyển app thoải mái — UI nổi vẫn giữ nguyên. Dùng tay nắm ở cạnh màn hình để mở menu."
            else "Bật một lần để hiện UI trên các ứng dụng. Không chọn app, không theo dõi thoát app.",
            color = Zyn.TextDim, fontSize = 13.sp, lineHeight = 20.sp,
        )
        if (on && !vm.isActive) {
            Spacer(Modifier.height(10.dp))
            Text("UI đã bật. Ghép nối ADB để dùng các thao tác click.", color = Zyn.Warn, fontSize = 12.sp)
        }
    }
}
