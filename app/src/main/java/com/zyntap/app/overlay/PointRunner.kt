package com.zyntap.app.overlay

import android.content.Context
import android.graphics.Rect
import com.zyntap.app.data.AdbSession
import com.zyntap.app.data.DebugLog
import com.zyntap.app.data.ZynAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Service-owned scheduler: main-thread state, cancellable delays, bounded shell reads on IO. */
class PointRunner(
    private val context: Context,
    private val bounds: () -> Rect,
    private val targetBlocked: (Int, Int) -> Boolean,
    private val report: (String) -> Unit,
) {
    private class Run {
        var job: Job? = null
        @Volatile var stream: AdbStream? = null
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runs = mutableMapOf<Int, Run>()
    private val touches = MainTouchGate()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val injectionLock = Mutex()
    private var lastReport = 0L

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
        val stream = run.stream
        run.stream = null
        if (stream != null) scope.launch(NonCancellable + Dispatchers.IO) { runCatching { stream.close() } }
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
                                    withContext(Dispatchers.IO) {
                                        try {
                                            tap(manager, run, step, holdMs, gapMs)
                                        } catch (e: java.io.IOException) {
                                            // Bị huỷ chủ động (nhả ngón/xoay màn hình) thì không nối lại
                                            ensureActive()
                                            DebugLog.w("Bơm", "Click (${step.x},${step.y}) lỗi IO, ADB có thể đã đứt -> nối lại rồi thử lại", e)
                                            // Kết nối chết giữa chừng -> nối lại 1 lần rồi thử lại bước này
                                            AdbSession.drop(context)
                                            val link = AdbSession.connect(context, attempts = 1)
                                            if (link !is AdbSession.Link.Connected) {
                                                DebugLog.e("Bơm", "Nối lại ADB sau khi đứt thất bại: ${(link as? AdbSession.Link.Failed)?.message ?: link}")
                                                throw IllegalStateException("ADB đã ngắt, đang nối lại. Thử lại sau giây lát.")
                                            }
                                            ensureActive()
                                            tap(manager, run, step, holdMs, gapMs)
                                        }
                                    }
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
                val stream = run.stream
                run.stream = null
                if (stream != null) withContext(NonCancellable + Dispatchers.IO) { runCatching { stream.close() } }
                if (runs[button.id] === run) runs.remove(button.id)
            }
        }
        return true
    }

    private companion object {
        /** Chờ helper READY tối đa chừng này trước khi bỏ click (không dùng `input tap` khi helper còn sống). */
        const val HELPER_WAIT_MS = 1500L
    }

    private suspend fun tap(manager: ZynAdbConnectionManager, run: Run, point: PointStep.Tap, holdMs: Long, gapMs: Long) {
        currentCoroutineContext().ensureActive()
        // Ưu tiên click qua helper: chèn vào CÙNG luồng chạm với ngón thật (helper đã grab cảm ứng),
        // nhờ vậy ngón thật đang vuốt/giữ không bị mất. Helper luôn tự nhả (UP) dù coroutine bị huỷ giữa chừng.
        var helperError: String? = null
        val watcher = TouchWatcher.current
        try {
            if (watcher?.tap(point.x, point.y, holdMs, gapMs) == true) return
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            helperError = e.message ?: "helper lỗi"
        }

        // Once a click has been sent to a live helper and that attempt errors, its input state is
        // uncertain until the helper is restarted. Never switch that same attempt to `input tap`:
        // it creates a second, independent touch stream and can cancel a finger the helper is relaying.
        // Treat helper errors as a fail-closed condition, request a clean helper restart, and let the
        // next user action retry. A missing/passive helper may still use the legacy fallback below.
        if (watcher != null && (helperError != null || !watcher.inputTapAllowed())) {
            if (helperError == null) {
                val ready = withTimeoutOrNull(HELPER_WAIT_MS) {
                    while (!watcher.proxyReady) delay(40)
                    true
                } == true
                if (ready) {
                    try {
                        if (watcher.tap(point.x, point.y, holdMs, gapMs)) return
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IllegalStateException) {
                        helperError = e.message ?: "helper lỗi"
                    }
                }
            }
            if (helperError != null) {
                watcher.requestReinject("helper click lỗi; không chuyển sang luồng input tap riêng")
            }
            DebugLog.w("Bơm", "Bỏ qua click (${point.x},${point.y}) vì helper chưa bơm được${helperError?.let { " ($it)" } ?: ""} — không chuyển sang `input tap` khi trạng thái cảm ứng chưa chắc chắn")
            return
        }

        // Tới đây: helper thụ động (ROM không cho grab) / đã bỏ helper hẳn / chưa có TouchWatcher -> không còn cách nào khác.
        if (helperError != null) {
            DebugLog.w("Bơm", "Click qua helper lỗi ($helperError) -> click lại bằng `input tap` để không bị xịt")
        } else {
            TouchWatcher.current?.requestReinject("click phải lùi về input tap (helper chưa READY / chưa grab)")
            DebugLog.w("Bơm", "Helper chưa bơm được (${if (TouchWatcher.current == null) "chưa chạy" else "chưa READY/không grab được"}) -> click bằng `input tap` (chậm hơn, không chung luồng với ngón thật)")
        }
        // Only validated integer coordinates enter the command. Never execute editor text.
        val stream = manager.openStream("shell:input tap ${point.x} ${point.y}; echo ZYN_EXIT:\$?")
        run.stream = stream
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        var timeout: java.util.concurrent.ScheduledFuture<*>? = null
        try {
            timeout = timer.schedule({ timedOut.set(true); runCatching { stream.close() } }, 8, TimeUnit.SECONDS)
            currentCoroutineContext().ensureActive()
            val output = StringBuilder()
            stream.openInputStream().use { input ->
                val buffer = ByteArray(1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = try { input.read(buffer) } catch (error: java.io.IOException) {
                        // libadb may signal normal remote EOF with IOException after the success marker.
                        if (output.contains("ZYN_EXIT:0")) break else throw error
                    }
                    if (count < 0) break
                    if (output.length < 8192) output.append(String(buffer, 0, count, Charsets.UTF_8))
                    if (output.contains("ZYN_EXIT:0")) break
                }
            }
            check(!timedOut.get()) { "ADB phản hồi quá lâu" }
            check(output.contains("ZYN_EXIT:0")) { output.toString().take(180).ifBlank { "ADB không xác nhận click" } }
        } finally {
            timeout?.cancel(false)
            runCatching { stream.close() }
            if (run.stream === stream) run.stream = null
        }
    }

    fun close() {
        stopAll()
        scope.cancel()
        timer.shutdownNow()
    }
}
