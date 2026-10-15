package com.zyntap.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zyntap.app.BuildConfig
import com.zyntap.app.MainViewModel
import com.zyntap.app.data.DebugLog
import com.zyntap.app.data.ZynAdbConnectionManager
import com.zyntap.app.overlay.OverlayPermission
import com.zyntap.app.overlay.OverlayService
import com.zyntap.app.overlay.TouchWatcher
import com.zyntap.app.overlay.UsageAccess
import kotlinx.coroutines.delay

/**
 * Ô Debug log ngay trong app chính: xem lại lỗi kể cả khi menu nổi đã mất (thoát app / dịch vụ tắt).
 * Dùng chung [DebugLog] với tab "Debug log" của menu nổi (cùng tiến trình) và đọc cả log lưu trong file
 * của các lần chạy trước, nên lỗi/crash trước đó vẫn còn để xem.
 */
@Composable
fun DebugLogCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(22.dp)

    var tick by remember { mutableStateOf(0) }
    var errorsOnly by remember { mutableStateOf(false) }
    var clearArmed by remember { mutableStateOf(false) }

    // Có log mới -> vẽ lại (DebugLog đã gộp thông báo, gọi ở luồng chính)
    DisposableEffect(Unit) {
        val l: () -> Unit = { tick++ }
        DebugLog.addListener(l)
        onDispose { DebugLog.removeListener(l) }
    }
    LaunchedEffect(clearArmed) {
        if (clearArmed) { delay(2500); clearArmed = false }
    }

    val all = remember(tick) { DebugLog.snapshot(false) }
    val list = remember(tick, errorsOnly) {
        if (errorsOnly) all.filter { it.level != DebugLog.Level.INFO } else all
    }
    val errs = all.count { it.level == DebugLog.Level.ERROR }
    val warns = all.count { it.level == DebugLog.Level.WARN }

    val listState = rememberLazyListState()
    // Có dòng mới thì tự cuộn xuống cuối (trừ khi đang vuốt tay)
    LaunchedEffect(list.size, list.lastOrNull()?.count, errorsOnly) {
        if (list.isNotEmpty() && !listState.isScrollInProgress) listState.scrollToItem(list.lastIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Zyn.Surface)
            .border(1.dp, Zyn.Border, shape)
            .padding(18.dp),
    ) {
        Text("Debug log", color = Zyn.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(
            text = "${all.size} dòng · $errs lỗi · $warns cảnh báo",
            color = if (errs > 0) Zyn.Danger else Zyn.TextDim,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(12.dp))

        Row(Modifier.fillMaxWidth()) {
            LogChip("Chỉ lỗi", selected = errorsOnly, modifier = Modifier.weight(1f)) { errorsOnly = !errorsOnly }
            Spacer(Modifier.width(8.dp))
            LogChip("Kiểm tra", modifier = Modifier.weight(1f)) {
                DebugLog.i("Kiểm tra", statusText(ctx, vm).lines().joinToString(" | "))
            }
            Spacer(Modifier.width(8.dp))
            LogChip("Sao chép", primary = true, modifier = Modifier.weight(1f)) {
                val text = DebugLog.report(statusText(ctx, vm), errorsOnly)
                val ok = runCatching {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("ZynTap debug log", text))
                }.isSuccess
                Toast.makeText(ctx, if (ok) "Đã sao chép log — dán cho mình nhé" else "Không sao chép được", Toast.LENGTH_SHORT).show()
            }
            Spacer(Modifier.width(8.dp))
            LogChip(
                if (clearArmed) "Chạm lại" else "Xóa",
                danger = true,
                modifier = Modifier.weight(1f),
            ) {
                if (clearArmed) { DebugLog.clear(); clearArmed = false; tick++ }
                else clearArmed = true
            }
        }
        Spacer(Modifier.height(12.dp))

        val boxShape = RoundedCornerShape(12.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .clip(boxShape)
                .background(LogBoxBg)
                .border(1.dp, Zyn.Border, boxShape),
        ) {
            if (list.isEmpty()) {
                Text(
                    text = if (errorsOnly) "Không có lỗi hay cảnh báo nào. Tốt!" else "Chưa có log.",
                    color = Zyn.TextDim,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                    items(list) { e ->
                        Text(
                            text = DebugLog.line(e),
                            color = when (e.level) {
                                DebugLog.Level.ERROR -> Zyn.Danger
                                DebugLog.Level.WARN -> Zyn.Warn
                                DebugLog.Level.INFO -> Zyn.TextDim
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            fontWeight = if (e.level == DebugLog.Level.ERROR) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Log vẫn còn sau khi menu nổi tắt. Bấm Sao chép rồi gửi mình để fix.",
            color = Zyn.TextDim,
            fontSize = 12.sp,
        )
    }
}

private val LogBoxBg = androidx.compose.ui.graphics.Color(0xFF0A1220)

@Composable
private fun LogChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    primary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val bg = when {
        selected -> Zyn.Cyan
        primary -> androidx.compose.ui.graphics.Color(0xFF1E3A66)
        else -> Zyn.SurfaceHi
    }
    val fg = when {
        selected -> androidx.compose.ui.graphics.Color(0xFF04121C)
        primary -> androidx.compose.ui.graphics.Color.White
        danger -> Zyn.Danger
        else -> Zyn.Text
    }
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (selected) Zyn.Cyan else Zyn.Border, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Vài dòng trạng thái hiện tại (đầu báo cáo + nút "Kiểm tra"). */
private fun statusText(ctx: Context, vm: MainViewModel): String {
    fun yn(b: Boolean) = if (b) "có" else "không"
    val linked = runCatching { ZynAdbConnectionManager.get(ctx.applicationContext).isConnected }.getOrDefault(false)
    val w = TouchWatcher.current
    return buildString {
        append("ZynTap ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n")
        append("Gỡ lỗi Wi-Fi bật: ${yn(vm.wifiAdbOn)} · Đã ghép nối: ${yn(vm.isPaired)} · ADB đang nối: ${yn(linked)}\n")
        append("Menu nổi đang chạy: ${yn(OverlayService.wanted(ctx))} · Helper bơm: ${if (w == null) "chưa chạy" else if (w.proxyReady) "sẵn sàng" else "chưa bơm được"}\n")
        append("Quyền vẽ đè: ${yn(OverlayPermission.granted(ctx))} · Truy cập dữ liệu sử dụng: ${yn(UsageAccess.granted(ctx))}")
    }
}
