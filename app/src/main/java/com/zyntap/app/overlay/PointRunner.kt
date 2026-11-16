package com.zyntap.app.overlay

import android.content.Context
import android.graphics.Rect
import com.zyntap.app.data.DebugLog
import com.zyntap.app.data.ZynAdbConnectionManager
import kotlinx.coroutines.*

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
    private var lastReport = 0L
    private var lastSkipLog = 0L

    private fun message(text: String, level: DebugLog.Level = DebugLog.Level.WARN, t: Throwable? = null) {
        when (level) {
            DebugLog.Level.INFO -> DebugLog.i("Click", text)
            DebugLog.Level.WARN -> DebugLog.w("Click", text, t)
            DebugLog.Level.ERROR -> DebugLog.e("Click", text, t)
        }
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastReport > 1500) { lastReport = now; report(text) }
    }

    fun touch(button: MainButton, down: Boolean): Boolean {
        
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
        val run = runs.remove(id)
        run?.job?.cancel()
        
        val pending = run?.job
        scope.launch {
            pending?.join()
            TouchWatcher.current?.stopScript(id)
        }
    }

    fun stopAll() {
        touches.clear()
        val pending = runs.values.mapNotNull { it.job }
        pending.forEach { it.cancel() }
        runs.clear()
        scope.launch {
            pending.forEach { it.join() }
            TouchWatcher.current?.stopAllScripts()
        }
    }

    private fun start(button: MainButton, repeat: Boolean): Boolean {
        
        if (runs[button.id]?.job?.isActive == true) return false
        val screen = bounds()
        val script = PointScriptParser.parse(button.pointScript, screen.width(), screen.height())
        if (!script.valid) {
            message(script.errors.firstOrNull() ?: "${button.code}: chưa có điểm nhấn ở tab 2")
            return false
        }
        if (script.allPoints.any { (x, y) -> targetBlocked(x, y) }) {
            message("Điểm nhấn đang nằm dưới menu đang mở hoặc nút mở menu. Hãy thu menu hoặc dời nút mở menu.")
            return false
        }
        
        val holdMs = if (repeat) TouchWatcher.TAP_HOLD_MS else TouchWatcher.TAP_HOLD_ONCE_MS
        val gapMs = if (!repeat && button.passThrough) TouchWatcher.TAP_GAP_AFTER_UP_MS else 0L
        val run = Run()
        runs[button.id] = run
        run.job = scope.launch {
            try {
                val manager = withContext(Dispatchers.IO) { ZynAdbConnectionManager.get(context) }
                if (!withContext(Dispatchers.IO) { manager.isConnected }) {
                    
                    message("ADB chưa kết nối. Hãy mở app và kết nối lại.", DebugLog.Level.ERROR)
                    return@launch
                }
                ensureActive()
                if (bounds() != screen) error("Màn hình đã đổi kích thước. Chọn lại tọa độ.")
                sendScript(button.id, repeat, holdMs, gapMs, encode(script))
            } catch (_: CancellationException) {
                
            } catch (e: Exception) {
                message("Không click được: ${e.message ?: "ADB đã ngắt"}", DebugLog.Level.ERROR, e)
            } finally {
                if (runs[button.id] === run) runs.remove(button.id)
            }
        }
        return true
    }

    private fun encode(script: PointScript): String = script.steps.joinToString(" ") { step ->
        when (step) {
            is PointStep.Tap -> "T:${step.x},${step.y}"
            is PointStep.Multi -> (if (step.points.size == 1) "T:" else "M:") + step.points.joinToString(";") { (x, y) -> "$x,$y" }
            is PointStep.Wait -> "W:${step.millis}"
        }
    }

    private suspend fun sendScript(id: Int, repeat: Boolean, holdMs: Long, gapMs: Long, ops: String) {
        currentCoroutineContext().ensureActive()
        val watcher = TouchWatcher.current
        if (watcher?.runScript(id, repeat, holdMs, gapMs, ops) == true) return

        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastSkipLog > 1000) {
            lastSkipLog = now
            DebugLog.w("Bơm", "Bỏ qua kịch bản click: ${if (watcher == null) "chưa có helper bơm cảm ứng" else "helper chưa bơm được (chưa READY / không grab được)"} — không có đường lui nào khác")
        }
        
        if (watcher != null && !watcher.proxyReady) watcher.requestReinject("kịch bản bị bỏ vì helper chưa sẵn sàng bơm")
    }

    fun close() {
        stopAll()
        scope.cancel()
    }
}
