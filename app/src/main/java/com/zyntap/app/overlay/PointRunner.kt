package com.zyntap.app.overlay

import android.content.Context
import android.graphics.Rect
import android.view.Surface
import android.view.WindowManager
import com.zyntap.app.data.AdbSession
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
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    // Ngón thật: ghi input_event vào slot riêng của /dev/input/eventN -> không làm mất vuốt của người dùng.
    private val injector = RawTouchInjector()
    private var fallbackNoticeShown = false
    private var okNoticeShown = false

    @Suppress("DEPRECATION")
    private fun rotation(): Int = runCatching { wm.defaultDisplay.rotation }.getOrDefault(Surface.ROTATION_0)

    private fun message(text: String) {
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
            message("Điểm nhấn đang trùng nút main/menu. Hãy thu menu hoặc đổi vị trí nút.")
            return
        }
        val run = Run()
        runs[button.id] = run
        run.job = scope.launch {
            try {
                val manager = withContext(Dispatchers.IO) { ZynAdbConnectionManager.get(context) }
                if (!withContext(Dispatchers.IO) { manager.isConnected }) {
                    // Chưa có kết nối sống -> tự nối (không bắt người dùng mở app nối tay)
                    message("Đang kết nối ADB...")
                    when (val link = AdbSession.connect(context)) {
                        is AdbSession.Link.Connected -> Unit
                        is AdbSession.Link.PairingLost -> {
                            message("Máy đã quên khoá ghép nối. Mở app ghép nối lại bằng mã 6 số.")
                            return@launch
                        }
                        is AdbSession.Link.Failed -> {
                            message(link.message)
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
                                            tap(manager, run, step, screen)
                                        } catch (e: java.io.IOException) {
                                            // Bị huỷ chủ động (nhả ngón/xoay màn hình) thì không nối lại
                                            ensureActive()
                                            // Kết nối chết giữa chừng -> nối lại 1 lần rồi thử lại bước này
                                            AdbSession.drop(context)
                                            injector.reset()
                                            val link = AdbSession.connect(context, attempts = 1)
                                            if (link !is AdbSession.Link.Connected) {
                                                throw IllegalStateException("ADB đã ngắt, đang nối lại. Thử lại sau giây lát.")
                                            }
                                            ensureActive()
                                            tap(manager, run, step, screen)
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
                message("Không click được: ${e.message ?: "ADB đã ngắt"}")
            } finally {
                val stream = run.stream
                run.stream = null
                if (stream != null) withContext(NonCancellable + Dispatchers.IO) { runCatching { stream.close() } }
                if (runs[button.id] === run) runs.remove(button.id)
            }
        }
    }

    /**
     * Ưu tiên chạm "ngón thật" (ghi thẳng vào tấm cảm ứng, slot riêng). Nếu máy không cho
     * (không đa chạm kiểu B / shell không ghi được /dev/input) thì lùi về `input tap` như cũ.
     */
    private suspend fun tap(manager: ZynAdbConnectionManager, run: Run, point: PointStep.Tap, screen: Rect) {
        currentCoroutineContext().ensureActive()
        when (val prep = injector.prepare(manager)) {
            RawTouchInjector.Prep.Ready -> {
                try {
                    injector.tap(point.x, point.y, rotation(), screen.width(), screen.height())
                    if (!okNoticeShown && injector.verified) {
                        okNoticeShown = true
                        withContext(Dispatchers.Main.immediate) { message("Chạm thật OK: ${injector.summary}") }
                    }
                    return
                } catch (e: RawTouchInjector.Ignored) {
                    if (!fallbackNoticeShown) {
                        fallbackNoticeShown = true
                        withContext(Dispatchers.Main.immediate) {
                            message("Chạm thật không được (${e.message}; ${injector.summary}). Dùng input tap.")
                        }
                    }
                    // rơi xuống legacyTap bên dưới cho bước này
                }
            }
            is RawTouchInjector.Prep.Unsupported -> if (!fallbackNoticeShown) {
                fallbackNoticeShown = true
                withContext(Dispatchers.Main.immediate) {
                    message("Không chạm thật được (${prep.reason}). Dùng input tap, có thể làm mất vuốt.")
                }
            }
            is RawTouchInjector.Prep.Transient -> Unit // lần này lùi input tap, lần sau thử lại đường thật
        }
        legacyTap(manager, run, point)
    }

    private suspend fun legacyTap(manager: ZynAdbConnectionManager, run: Run, point: PointStep.Tap) {
        currentCoroutineContext().ensureActive()
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
        injector.close()
        scope.cancel()
        timer.shutdownNow()
    }
}
