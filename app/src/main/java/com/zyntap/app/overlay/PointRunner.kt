package com.zyntap.app.overlay

import android.content.Context
import android.graphics.Rect
import com.zyntap.app.data.AdbSession
import com.zyntap.app.data.DebugLog
import com.zyntap.app.data.ZynAdbConnectionManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Service-owned scheduler: main-thread state, cancellable delays.
 * Click chỉ đi qua helper [TouchWatcher] (ngón ảo chung luồng với ngón thật). KHÔNG có `input tap`: click lỗi thì bỏ.
 */
class PointRunner(
    private val context: Context,
    private val bounds: () -> Rect,
    private val targetBlocked: (Int, Int) -> Boolean,
    private val report: (String) -> Unit,
) {
    private class Run {
        var job: Job? = null
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runs = mutableMapOf<Int, Run>()
    private val touches = MainTouchGate()
    private val injectionLock = Mutex()
    private var lastReport = 0L
    private var lastSkipLog = 0L

    /** Ghi vào Debug log (luôn ghi) rồi báo Toast cho người dùng (giãn nhịp để không spam). */
    private fun message(text: String, level: DebugLog.Level = DebugLog.Level.WARN, t: Throwable? = null) {
        when (level) {
            DebugLog.Level.INFO -> DebugLog.i("Click", text)
            DebugLog.Level.WARN -> DebugLog.w("Click", text, t)
            DebugLog.Level.ERROR -> DebugLog.e("Click", text, t)
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastReport > 1500) { lastReport = now; report(text) }
    }

    /** @return true nếu lần chạm này vừa khởi chạy 1 lần click (Nhấn/Thả) — Delay Mode dựa vào đây để biết có khoá nút hay không. */
    fun touch(button: MainButton, down: Boolean): Boolean {
        // Trợ vuốt tâm do helper can thiệp thẳng vào ngón thật vuốt từ trong nút ra: nút không chạy click/script.
        if (button.assistEnabled) return false
        val action = if (down) touches.down(button.id, button.mode) else touches.up(button.id)
        return when (action) {
            MainTouchGate.Action.ONCE -> start(button, false)
            MainTouchGate.Action.LOOP -> { start(button, true); false }
            MainTouchGate.Action.STOP -> { stop(button.id); false }
            MainTouchGate.Action.NONE -> false
        }
    }

    fun cancelTouch(id: Int) {
        touches.cancel(id)
        stop(id)
    }

    fun stop(id: Int) {
        val run = runs.remove(id) ?: return
        run.job?.cancel()
    }

    fun stopAll() {
        touches.clear()
        runs.keys.toList().forEach(::stop)
    }

    private fun start(button: MainButton, repeat: Boolean): Boolean {
        // A single physical press cannot enqueue an unbounded backlog.
        if (runs[button.id]?.job?.isActive == true) return false
        val screen = bounds()
        val script = PointScriptParser.parse(button.pointScript, screen.width(), screen.height())
        if (!script.valid) {
            message(script.errors.firstOrNull() ?: "${button.code}: chưa có điểm nhấn ở tab 2")
            return false
        }
        val taps = script.steps.filterIsInstance<PointStep.Tap>()
        if (taps.any { targetBlocked(it.x, it.y) }) {
            message("Điểm nhấn đang nằm dưới menu đang mở hoặc nút mở menu. Hãy thu menu hoặc dời nút mở menu.")
            return false
        }
        // Click 1 lần: giữ lâu hơn. Chạm xuyên: chờ ngón thật nhả đủ lâu rồi mới click (tránh game nuốt click đầu).
        val holdMs = if (repeat) TouchWatcher.TAP_HOLD_MS else TouchWatcher.TAP_HOLD_ONCE_MS
        val gapMs = if (!repeat && button.passThrough) TouchWatcher.TAP_GAP_AFTER_UP_MS else 0L
        val run = Run()
        runs[button.id] = run
        run.job = scope.launch {
            try {
                val manager = withContext(Dispatchers.IO) { ZynAdbConnectionManager.get(context) }
                if (!withContext(Dispatchers.IO) { manager.isConnected }) {
                    // Chưa có kết nối sống -> tự nối (không bắt người dùng mở app nối tay)
                    message("Đang kết nối ADB...", DebugLog.Level.INFO)
                    when (val link = AdbSession.connect(context)) {
                        is AdbSession.Link.Connected -> Unit
                        is AdbSession.Link.PairingLost -> {
                            message("Máy đã quên khoá ghép nối. Mở app ghép nối lại bằng mã 6 số.", DebugLog.Level.ERROR)
                            return@launch
                        }
                        is AdbSession.Link.Failed -> {
                            message(link.message, DebugLog.Level.ERROR)
                            return@launch
                        }
                    }
                }
                do {
                    for (step in script.steps) {
                        ensureActive()
                        if (bounds() != screen) error("Màn hình đã đổi kích thước. Chọn lại tọa độ.")
                        when (step) {
                            is PointStep.Wait -> delay(step.millis)
                            is PointStep.Tap -> {
                                if (targetBlocked(step.x, step.y)) error("Điểm nhấn trùng cửa sổ ZynTap")
                                injectionLock.withLock {
                                    ensureActive()
                                    tap(step, holdMs, gapMs)
                                }
                            }
                        }
                    }
                    // A loop without delays still yields; never busy-spins on the UI thread.
                    if (repeat) delay(16)
                } while (repeat && isActive)
            } catch (_: CancellationException) {
                // UP / CANCEL / rotation / service teardown.
            } catch (e: Exception) {
                message("Không click được: ${e.message ?: "ADB đã ngắt"}", DebugLog.Level.ERROR, e)
            } finally {
                if (runs[button.id] === run) runs.remove(button.id)
            }
        }
        return true
    }

    /**
     * Một click qua helper (ngón ảo chạy song song với ngón thật trong cùng một luồng chạm).
     * Lỗi / helper chưa sẵn sàng thì CHỈ BỎ click này: tuyệt đối không có `input tap` hay đường lui nào khác
     * (nó là luồng chạm riêng, chen vào là hệ thống huỷ luôn ngón thật đang đè).
     */
    private suspend fun tap(point: PointStep.Tap, holdMs: Long, gapMs: Long) {
        currentCoroutineContext().ensureActive()
        val watcher = TouchWatcher.current
        if (watcher?.tap(point.x, point.y, holdMs, gapMs) == true) return

        // Click bị bỏ. Giãn nhịp log để vòng Giữ (click liên tục) không làm ngập Debug log.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSkipLog > 1000) {
            lastSkipLog = now
            DebugLog.w("Bơm", "Bỏ qua click (${point.x},${point.y}): ${if (watcher == null) "chưa có helper bơm cảm ứng" else "helper chưa bơm được (chưa READY / không grab được)"} — không có đường lui nào khác")
        }
        // Helper đang khởi động lại / chưa bơm được: ép bơm lại (đã tự giãn nhịp bên trong) để click sau còn có đường đi.
        if (watcher != null && !watcher.proxyReady) watcher.requestReinject("click bị bỏ vì helper chưa sẵn sàng bơm")
        // Không quay vòng nóng khi click liên tục bị bỏ.
        delay(SKIP_PAUSE_MS)
    }

    private companion object {
        /** Nghỉ khi một click bị bỏ, để vòng Giữ không chạy nóng. */
        const val SKIP_PAUSE_MS = 50L
    }

    fun close() {
        stopAll()
        scope.cancel()
    }
}
