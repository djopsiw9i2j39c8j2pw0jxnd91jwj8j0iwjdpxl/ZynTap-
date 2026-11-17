package com.zyntap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zyntap.app.BuildConfig
import com.zyntap.app.MainViewModel
import com.zyntap.app.R
import com.zyntap.app.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SLIDE_MS = 340

@Composable
fun ZynTapApp(vm: MainViewModel = viewModel()) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    BackHandler(enabled = drawer.isOpen || vm.screen == Screen.Pair) {
        if (drawer.isOpen) scope.launch { drawer.close() } else vm.back()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                vm.refreshOverlayPermission()
                vm.refreshPairing()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(vm.messageVisible, vm.message) {
        if (vm.messageVisible) {
            delay(2600)
            vm.hideMessage()
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = vm.screen == Screen.Home || drawer.isOpen,
            scrimColor = Color.Black.copy(alpha = 0.62f),
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    SettingsFrame(vm, onClose = { scope.launch { drawer.close() } }, onPair = {
                        scope.launch { drawer.close(); vm.openActivate() }
                    })
                }
            },
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Surface(Modifier.fillMaxSize(), color = Zyn.Bg, contentColor = Zyn.Text) {
                    Box(Modifier.fillMaxSize().drawBehind {
                        drawRect(Brush.radialGradient(
                            listOf(Zyn.Cyan.copy(alpha = 0.10f), Color.Transparent),
                            center = Offset(size.width * 0.15f, 0f), radius = size.width * 0.9f,
                        ))
                    }) {
                        Column(Modifier.fillMaxSize().statusBarsPadding()) {
                            TopBar(vm, onMenu = { scope.launch { drawer.open() } })
                            AnimatedContent(
                                targetState = vm.screen, modifier = Modifier.weight(1f),
                                transitionSpec = { screenTransition(targetState == Screen.Pair) }, label = "screen",
                            ) { screen ->
                                when (screen) {
                                    Screen.Home -> HomeScreen(vm)
                                    Screen.Pair -> PairScreen(vm)
                                }
                            }
                        }
                        AnimatedVisibility(
                            visible = vm.messageVisible,
                            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                            enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
                            exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
                        ) {
                            Text(vm.message, color = Zyn.Text, fontSize = 14.sp,
                                modifier = Modifier.background(Zyn.SurfaceHi, RoundedCornerShape(14.dp))
                                    .border(1.dp, Zyn.Border, RoundedCornerShape(14.dp))
                                    .padding(horizontal = 18.dp, vertical = 12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsFrame(vm: MainViewModel, onClose: () -> Unit, onPair: () -> Unit) {
    ModalDrawerSheet(
        modifier = Modifier.fillMaxHeight().widthIn(max = 320.dp).fillMaxWidth(0.86f),
        drawerShape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp),
        drawerContainerColor = Zyn.Bg,
        drawerContentColor = Zyn.Text,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.logo_transparent), null, Modifier.size(40.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("ZynTap", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("KHÔNG GIAN CỦA BẠN", color = Zyn.Cyan, fontSize = 9.sp, letterSpacing = 1.sp)
                }
                RoundIconButton(Lucide.ChevronRight, "Đóng menu", onClose, size = 40.dp)
            }
            HorizontalDivider(color = Zyn.Border)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("ĐIỀU KHIỂN", color = Zyn.TextDim, fontSize = 11.sp, letterSpacing = 1.6.sp)
                Surface(color = Zyn.SurfaceHi, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Zyn.Border)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(if (vm.overlayEnabled) "UI nổi đang bật" else "UI nổi đang tắt", color = if (vm.overlayEnabled) Zyn.Cyan else Zyn.Text, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text("Một công tắc duy nhất ở màn hình chính. Không phụ thuộc ứng dụng đang mở.", color = Zyn.TextDim, fontSize = 12.sp, lineHeight = 19.sp)
                    }
                }
                OutlinedButton(onClick = onPair, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text("Kết nối & ghép nối ADB", color = Zyn.Cyan)
                }
                HorizontalDivider(color = Zyn.Border)
                Text("CÀI ĐẶT", color = Zyn.TextDim, fontSize = 11.sp, letterSpacing = 1.6.sp)
                SettingRow("Nhật ký gỡ lỗi", "Tắt để ngừng thu và ghi log.", vm.debugEnabled, vm::setDebugLogging)
                SettingRow("Hiệu ứng UI nổi", "Tắt để giảm lag: menu, nút main và hộp thoại đổi trạng thái ngay, không chuyển động.", vm.uiAnimations, vm::setUiAnimations)
            }
            HorizontalDivider(color = Zyn.Border)
            Text("ZynTap · ${BuildConfig.VERSION_NAME}\n${BuildConfig.APPLICATION_ID}", color = Zyn.TextDim, fontSize = 11.sp, lineHeight = 18.sp,
                modifier = Modifier.navigationBarsPadding().padding(vertical = 18.dp))
        }
    }
}

@Composable
private fun SettingRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(desc, color = Zyn.TextDim, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

private fun screenTransition(forward: Boolean): ContentTransform {
    val spec = tween<IntOffset>(SLIDE_MS, easing = FastOutSlowInEasing)
    return if (forward) {
        (slideInHorizontally(spec) { it } + fadeIn(tween(SLIDE_MS))) togetherWith
            (slideOutHorizontally(spec) { -it / 3 } + fadeOut(tween(220)))
    } else {
        (slideInHorizontally(spec) { -it / 3 } + fadeIn(tween(SLIDE_MS))) togetherWith
            (slideOutHorizontally(spec) { it } + fadeOut(tween(220)))
    }
}

@Composable
private fun TopBar(vm: MainViewModel, onMenu: () -> Unit) {
    val showBack = vm.screen == Screen.Pair
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showBack) {
            RoundIconButton(Lucide.ChevronLeft, "Quay lại", { vm.back() })
            Spacer(Modifier.width(14.dp))
        }
        Image(painterResource(R.drawable.logo_transparent), "Logo ZynTap", Modifier.height(34.dp).aspectRatio(313f / 256f))
        Spacer(Modifier.width(10.dp))
        Text("ZynTap", color = Zyn.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
        Spacer(Modifier.weight(1f))
        if (!showBack) RoundIconButton(Lucide.Menu, "Mở khung điều hướng", onMenu, accent = true)
    }
}
