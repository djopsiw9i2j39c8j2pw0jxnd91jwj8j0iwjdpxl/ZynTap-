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
import com.zyntap.app.data.AppEntry

private const val COLUMNS = 4

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
            AppsCard(vm)
            Spacer(Modifier.height(18.dp))
            DebugLogCard(vm)
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
        // Gộp Wi-Fi + ADB thành MỘT trạng thái. Đếm số điều kiện đã bật (nối Wi-Fi, bật gỡ lỗi Wi-Fi,
        // đã ghép nối ADB): 0 = đỏ, 1-2 = vàng, đủ 3 = xanh.
        val ready = active && wifiConnected
        val onCount = (if (wifiConnected) 1 else 0) + (if (wifiAdbOn) 1 else 0) + (if (wasPaired) 1 else 0)
        StatusRow(
            dotColor = when {
                ready -> Zyn.Ok
                // Đỏ khi tắt hết: không Wi-Fi, không gỡ lỗi Wi-Fi, không dữ liệu di động
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

/** Chấm trạng thái giống menu nổi: chấm đặc + quầng mờ bên ngoài "thở" (thụt ra thụt vào). */
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
            // Đổi chữ: cũ trượt lên mờ dần, mới trượt từ dưới lên hiện dần
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
private fun AppsCard(vm: MainViewModel) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Zyn.Surface)
            .border(1.dp, Zyn.Border, shape)
            .animateContentSize()
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ứng dụng", color = Zyn.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (vm.editMode) "Ấn dấu trừ để bỏ app, dấu cộng để thêm" else "Ấn vào app để mở",
                    color = Zyn.TextDim,
                    fontSize = 13.sp,
                )
            }
            RoundIconButton(
                icon = if (vm.editMode) Lucide.Plus else Lucide.Pencil,
                description = if (vm.editMode) "Thêm ứng dụng" else "Chỉnh sửa danh sách",
                onClick = { if (vm.editMode) vm.openPicker() else vm.toggleEditMode() },
                size = 42.dp,
                accent = vm.editMode,
            )
        }
        Spacer(Modifier.height(16.dp))

        if (vm.pinned.isEmpty()) {
            EmptyApps(editMode = vm.editMode)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                vm.pinned.chunked(COLUMNS).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { app ->
                            AppTile(
                                app = app,
                                editMode = vm.editMode,
                                onOpen = { vm.launchApp(app) },
                                onRemove = { vm.remove(app.packageName) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyApps(editMode: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Chưa có ứng dụng nào", color = Zyn.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (editMode) "Ấn dấu + để chọn app muốn thêm" else "Ấn nút bút chì rồi dấu + để thêm app",
            color = Zyn.TextDim,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AppTile(
    app: AppEntry,
    editMode: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val badge by animateFloatAsState(if (editMode) 1f else 0f, label = "minus-badge")
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !editMode, onClick = onOpen)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp)) {
            Image(
                bitmap = app.icon,
                contentDescription = app.label,
                modifier = Modifier
                    .size(52.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(14.dp)),
            )
            if (badge > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-4).dp)
                        .graphicsLayer {
                            scaleX = badge
                            scaleY = badge
                            alpha = badge
                        }
                        .size(24.dp)
                        .background(Zyn.Danger, CircleShape)
                        .border(2.dp, Zyn.Surface, CircleShape)
                        .clip(CircleShape)
                        .clickable(enabled = editMode, onClick = onRemove),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.Minus, contentDescription = "Bỏ ${app.label}", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = app.label,
            color = Zyn.TextDim,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}
