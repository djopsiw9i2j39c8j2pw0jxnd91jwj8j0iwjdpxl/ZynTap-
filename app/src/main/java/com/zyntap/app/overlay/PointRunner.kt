package com.zyntap.app.overlay

import android.content.Context
import android.graphics.Rect
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

    /** Bộ tiêm chạm riêng (clone thông số ngón gốc, không đè lên cử chỉ của ngón thật). */
    private val injector = TouchInjector(context)

    /** Nguồn "ngón gốc" (thông số + pointer-id trống); gán sau khi dựng. Null = dùng giá trị mặc định. */
    var fingers: FingerSource? = null

    private var announcedReady = false
    private var lastFallbackNote = 0L

    /** Khởi động sẵn tiến trình tiêm ở nền để lần nhấn đầu không bị trễ. Gọi thoải mái, tự bỏ qua nếu chưa cần. */
    fun warmUp() {
        if (injector.isUp) return
        scope.launch(Dispatchers.IO) {
            val manager = ZynAdbConnectionManager.get(context)
            if (!manager.isConnected) return@launch
            injector.warmUp(manager)
        }
    }

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
                                            tap(manager, run, step)
                                        } catch (e: java.io.IOException) {
                                            // Bị huỷ chủ động (nhả ngón/xoay màn hình) thì không nối lại
                                            ensureActive()
                                            // Kết nối chết giữa chừng -> nối lại 1 lần rồi thử lại bước này
                                            AdbSession.drop(context)
                                            val link = AdbSession.connect(context, attempts = 1)
                                            if (link !is AdbSession.Link.Connected) {
                                                throw IllegalStateException("ADB đã ngắt, đang nối lại. Thử lại sau giây lát.")
                                            }
                                            ensureActive()
                                            tap(manager, run, step)
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

    private suspend fun tap(manager: ZynAdbConnectionManager, run: Run, point: PointStep.Tap) {
        currentCoroutineContext().ensureActive()
        // Ưu tiên bộ tiêm riêng: device-id riêng + pointer-id trống + thông số clone từ ngón gốc,
        // nên không làm đứt ngón thật đang vuốt. Không dùng được thì lùi về `input tap` như trước.
        val source = fingers
        if (injector.tap(manager, point.x, point.y, source?.freePointerId() ?: 0, source?.profile)) {
            if (!announcedReady) {
                announcedReady = true
                scope.launch { message("Tiêm chạm riêng: BẬT (${injector.info})") }
            }
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFallbackNote > 60_000L) {
            lastFallbackNote = now
            val why = injector.lastError.ifBlank { "không rõ lý do" }
            scope.launch { message("Không bật được tiêm chạm riêng ($why). Đang dùng click cũ, có thể làm đứt ngón vuốt.") }
        }
        legacyTap(manager, run, point)
    }

    /** Đường lui: `input tap` (dùng chung device-id với ngón thật nên có thể làm đứt cử chỉ đang vuốt). */
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
        scope.cancel()
        timer.shutdownNow()
        injector.close()
    }
}
