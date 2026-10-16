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

    fun touch(button: MainButton, down: Boolean) {
        val action = if (down) touches.down(button.id, button.mode) else touches.up(button.id)
        when (action) {
            MainTouchGate.Action.ONCE -> start(button, false)
            MainTouchGate.Action.LOOP -> start(button, true)
            MainTouchGate.Action.STOP -> stop(button.id)
            MainTouchGate.Action.NONE -> Unit
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

    private fun start(button: MainButton, repeat: Boolean) {
        // A single physical press cannot enqueue an unbounded backlog.
        if (runs[button.id]?.job?.isActive == true) return
        val screen = bounds()
        val script = PointScriptParser.parse(button.pointScript, screen.width(), screen.height())
        if (!script.valid) {
            message(script.errors.firstOrNull() ?: "${button.code}: chưa có điểm nhấn ở tab 2")
            return
        }
        val taps = script.steps.filterIsInstance<PointStep.Tap>()
        if (taps.any { targetBlocked(it.x, it.y) }) {
            message("Điểm nhấn đang nằm dưới menu đang mở hoặc nút mở menu. Hãy thu menu hoặc dời nút mở menu.")
            return
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
    }

    private companion object {
        /** Chờ helper bơm sẵn sàng tối đa chừng này trước khi chịu lùi về `input tap`. */
        const val PROXY_WAIT_MS = 3_000L
        const val PROXY_POLL_MS = 50L
    }

    private suspend fun tap(manager: ZynAdbConnectionManager, run: Run, point: PointStep.Tap, holdMs: Long, gapMs: Long) {
        currentCoroutineContext().ensureActive()
        // Ưu tiên click qua helper: chèn vào CÙNG luồng chạm với ngón thật (helper đã grab cảm ứng),
        // nhờ vậy ngón thật đang vuốt/giữ không bị mất. Helper luôn tự nhả (UP) dù coroutine bị huỷ giữa chừng.
        val watcher = TouchWatcher.current
        // Helper đang khởi động / chạy lại (chưa READY): CHỜ nó thay vì bấm `input tap` ngay. `input tap` là một "thiết bị" khác
        // nên hệ thống huỷ ngón thật đang chạm rồi bỏ luôn phần còn lại của ngón đó (đúng triệu chứng "đơ ngón").
        if (watcher != null && watcher.proxyExpected && !watcher.proxyReady) {
            var waited = 0L
            while (waited < PROXY_WAIT_MS && watcher.proxyExpected && !watcher.proxyReady) {
                delay(PROXY_POLL_MS)
                waited += PROXY_POLL_MS
            }
        }
        if (watcher?.tap(point.x, point.y, holdMs, gapMs) == true) return
        // Helper chưa sẵn sàng / ROM không cho grab -> cách cũ.
        watcher?.requestReinject("click phải lùi về input tap (helper chưa READY / chưa grab)")
        DebugLog.w("Bơm", "Helper chưa bơm được (${if (TouchWatcher.current == null) "chưa chạy" else "chưa READY/không grab được"}) -> click bằng `input tap` (chậm hơn, không chung luồng với ngón thật)")
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
