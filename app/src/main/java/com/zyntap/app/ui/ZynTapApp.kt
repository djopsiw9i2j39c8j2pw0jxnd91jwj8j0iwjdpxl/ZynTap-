package com.zyntap.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zyntap.app.MainViewModel
import com.zyntap.app.R
import com.zyntap.app.Screen
import kotlinx.coroutines.delay

private const val SLIDE_MS = 340

@Composable
fun ZynTapApp(vm: MainViewModel = viewModel()) {
    BackHandler(enabled = vm.screen == Screen.Pair || vm.editMode) { vm.back() }

    // Quay lại app thì cập nhật lại trạng thái quyền overlay
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

    Surface(modifier = Modifier.fillMaxSize(), color = Zyn.Bg, contentColor = Zyn.Text) {
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    // một vệt sáng xanh rất nhẹ ở góc trên cho đỡ phẳng
                    drawRect(
                        Brush.radialGradient(
                            colors = listOf(Zyn.Cyan.copy(alpha = 0.10f), Color.Transparent),
                            center = Offset(size.width * 0.15f, 0f),
                            radius = size.width * 0.9f,
                        ),
                    )
                },
        ) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                TopBar(vm)
                AnimatedContent(
                    targetState = vm.screen,
                    modifier = Modifier.weight(1f),
                    transitionSpec = { screenTransition(forward = targetState == Screen.Pair) },
                    label = "screen",
                ) { screen ->
                    when (screen) {
                        Screen.Home -> HomeScreen(vm)
                        Screen.Pair -> PairScreen(vm)
                    }
                }
            }

            AnimatedVisibility(
                visible = vm.messageVisible,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp),
                enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
                exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(180)),
            ) {
                val shape = RoundedCornerShape(14.dp)
                Text(
                    text = vm.message,
                    color = Zyn.Text,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .background(Zyn.SurfaceHi, shape)
                        .border(1.dp, Zyn.Border, shape)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                )
            }
        }
    }

    if (vm.pickerOpen) AppPickerSheet(vm)
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
private fun TopBar(vm: MainViewModel) {
    val showBack = vm.screen == Screen.Pair || vm.editMode
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.logo_transparent),
            contentDescription = "Logo ZynTap",
            modifier = Modifier
                .height(34.dp)
                .aspectRatio(313f / 256f),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "ZynTap",
            color = Zyn.Text,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp,
        )
        Spacer(Modifier.weight(1f))
        RoundIconButton(
            icon = if (showBack) Lucide.ChevronLeft else Lucide.Menu,
            description = if (showBack) "Quay lại" else "Menu",
            onClick = { if (showBack) vm.back() },
        )
    }
}
