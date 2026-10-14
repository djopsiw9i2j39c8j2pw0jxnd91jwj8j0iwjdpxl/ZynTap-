package com.zyntap.app.overlay

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import com.zyntap.app.data.AdbSession
import com.zyntap.app.data.ZynAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/** Một ngón chạm đã được đổi sang toạ độ pixel của màn hình hiện tại. */
interface TouchSink {
    /** [down] = true: ngón vừa chạm xuống tại ([x], [y]); false: ngón vừa nhả. [slot] định danh ngón. */
    fun onFinger(slot: Int, down: Boolean, x: Int, y: Int)

    /** Mất theo dõi (đứt ADB...): huỷ mọi nút đang giữ, KHÔNG coi là nhả (tránh chạy nhầm chế độ Thả). */
    fun onLost()
}

/** Thông số cảm ứng đọc từ `getevent -lp`. */
data class TouchDevice(val path: String, val maxX: Int, val maxY: Int)

/**
 * Bộ phân tích đầu ra của `getevent -l` (giao thức đa chạm kiểu B).
 * Thuần Kotlin, không phụ thuộc Android -> test được.
 * Chỉ báo "ngón chạm xuống" và "ngón nhả" (toạ độ thô); không báo di chuyển.
 */
class GeteventParser(private val emit: (slot: Int, down: Boolean, rawX: Int, rawY: Int) -> Unit) {
    private class Slot {
        var active = false
        var wantDown = false
        var wantUp = false
        var x = 0
        var y = 0
        var hasX = false
        var hasY = false
    }

    private val slots = HashMap<Int, Slot>()
    private var current = 0

    private fun slot() = slots.getOrPut(current) { Slot() }

    fun feed(rawLine: String) {
        var line = rawLine.trim()
        if (line.isEmpty()) return
        // Nhiều thiết bị: dòng có dạng "/dev/input/event3: EV_ABS ..."
        if (line.startsWith("/dev/")) line = line.substringAfter(": ", "").trim()
        val t = line.split(WS).filter { it.isNotEmpty() }
        if (t.size < 3) return
        val code = t[1]
        // EV_KEY in ra chữ (DOWN/UP/REPEAT), còn lại là số hex 8 chữ số
        val value = when (t[2]) {
            "DOWN" -> 1
            "UP" -> 0
            "REPEAT" -> 2
            else -> hex(t[2]) ?: return
        }
        when (t[0]) {
            "EV_ABS" -> when (code) {
                "ABS_MT_SLOT" -> current = value
                "ABS_MT_TRACKING_ID" -> {
                    val s = slot()
                    if (value == -1) {
                        if (s.active || s.wantDown) s.wantUp = true
                        s.wantDown = false
                    } else if (!s.active) {
                        s.wantDown = true
                        s.wantUp = false
                        s.hasX = false
                        s.hasY = false
                    }
                }
                "ABS_MT_POSITION_X" -> slot().apply { x = value; hasX = true }
                "ABS_MT_POSITION_Y" -> slot().apply { y = value; hasY = true }
            }
            "EV_KEY" -> if (code == "BTN_TOUCH") {
                if (value == 0) {
                    slots.values.forEach { if (it.active || it.wantDown) { it.wantUp = true; it.wantDown = false } }
                } else if (slots.values.none { it.active || it.wantDown }) {
                    // Máy không báo tracking id: coi BTN_TOUCH là ngón ở slot 0
                    slots.getOrPut(0) { Slot() }.apply { wantDown = true; wantUp = false }
                }
            }
            "EV_SYN" -> if (code == "SYN_REPORT") flush()
        }
    }

    private fun flush() {
        for ((id, s) in slots.entries.sortedBy { it.key }) {
            if (s.wantDown && s.hasX && s.hasY) {
                s.wantDown = false
                s.active = true
                emit(id, true, s.x, s.y)
            }
            if (s.wantUp) {
                s.wantUp = false
                if (s.active) {
                    s.active = false
                    emit(id, false, s.x, s.y)
                }
            }
        }
    }

    /** Quên mọi ngón đang chạm (khi mất kết nối / dừng theo dõi). */
    fun reset() { slots.clear(); current = 0 }

    companion object {
        private val WS = Regex("\\s+")
        private fun hex(v: String): Int? = runCatching { java.lang.Long.parseLong(v, 16).toInt() }.getOrNull()

        /** Đọc `getevent -lp`, chọn thiết bị có ABS_MT_POSITION_X/Y đầu tiên. */
        fun parseDevice(output: String): TouchDevice? {
            var path: String? = null
            var mx = -1
            var my = -1
            val maxRe = Regex("max\\s+(\\d+)")
            fun done(): TouchDevice? =
                if (path != null && mx > 0 && my > 0) TouchDevice(path!!, mx, my) else null
            for (raw in output.lines()) {
                val line = raw.trim()
                if (line.startsWith("add device")) {
                    done()?.let { return it }
                    path = line.substringAfter(": ", "").trim().ifEmpty { null }
                    mx = -1; my = -1
                } else if (line.contains("ABS_MT_POSITION_X")) {
                    mx = maxRe.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                } else if (line.contains("ABS_MT_POSITION_Y")) {
                    my = maxRe.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                }
            }
            return done()
        }

        /**
         * Đổi toạ độ thô của tấm cảm ứng (theo chiều tự nhiên của máy) sang pixel màn hình
         * hiện tại, có tính xoay màn hình ([Surface.ROTATION_0] .. [Surface.ROTATION_270]).
         */
        fun toScreen(rawX: Int, rawY: Int, dev: TouchDevice, rotation: Int, w: Int, h: Int): Pair<Int, Int> {
            val nx = (rawX.toFloat() / dev.maxX).coerceIn(0f, 1f)
            val ny = (rawY.toFloat() / dev.maxY).coerceIn(0f, 1f)
            val (fx, fy) = when (rotation) {
                Surface.ROTATION_90 -> ny to (1f - nx)
                Surface.ROTATION_180 -> (1f - nx) to (1f - ny)
                Surface.ROTATION_270 -> (1f - ny) to nx
                else -> nx to ny
            }
            return (fx * w).toInt().coerceIn(0, (w - 1).coerceAtLeast(0)) to
                (fy * h).toInt().coerceIn(0, (h - 1).coerceAtLeast(0))
        }
    }
}

/**
 * Theo dõi chạm của TOÀN MÀN HÌNH qua ADB (`getevent`), kể cả khi cửa sổ nút main không nhận chạm
 * (FLAG_NOT_TOUCHABLE). Dùng cho "chạm xuyên": ngón chạm lọt thẳng xuống app bên dưới, còn app này
 * chỉ "nghe" xem ngón có đặt lên nút main nào không.
 *
 * Android không cho một cửa sổ vừa nhận chạm vừa để chạm lọt xuống dưới, và ACTION_OUTSIDE bị
 * che toạ độ từ Android 12, nên đây là cách đáng tin duy nhất khi đã có ADB không dây.
 */
class TouchWatcher(
    context: Context,
    private val screen: () -> Rect,
    private val sink: TouchSink,
    /** Báo trạng thái (tiếng Việt) lên luồng chính, để hiện cho người dùng biết vì sao chạm xuyên chưa chạy. */
    private val onStatus: (String) -> Unit = {},
) {
    private val app = context.applicationContext
    private val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var stream: AdbStream? = null

    private fun status(text: String) { main.post { onStatus(text) } }

    val running get() = job?.isActive == true

    @Suppress("DEPRECATION")
    private fun rotation(): Int = runCatching { wm.defaultDisplay.rotation }.getOrDefault(Surface.ROTATION_0)

    fun start() {
        if (running) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        val s = stream
        stream = null
        if (s != null) scope.launch { runCatching { s.close() } }
    }

    private suspend fun loop() {
        var device: TouchDevice? = null
        while (currentCoroutineContext().isActive) {
            val parser = GeteventParser { slot, down, rx, ry ->
                val d = device ?: return@GeteventParser
                val b = screen()
                val (x, y) = GeteventParser.toScreen(rx, ry, d, rotation(), b.width(), b.height())
                main.post { sink.onFinger(slot, down, x, y) }
            }
            try {
                val manager = ZynAdbConnectionManager.get(app)
                if (!manager.isConnected) {
                    // Nối lại ở nền (đã tự giãn nhịp), rồi thử lại sau
                    status("ADB chưa kết nối")
                    AdbSession.ensureInBackground(app)
                    delay(RETRY_MS)
                    continue
                }
                device = device ?: detect(manager)
                val dev = device
                if (dev == null) {
                    status("Không đọc được thiết bị cảm ứng (getevent -lp)")
                    delay(RETRY_MS * 3); continue
                }
                val s = manager.openStream("shell:getevent -l ${dev.path}")
                stream = s
                status("Đang nghe cảm ứng ${dev.path} (${dev.maxX}x${dev.maxY})")
                BufferedReader(InputStreamReader(s.openInputStream())).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        parser.feed(line)
                    }
                }
                status("Luồng getevent bị đóng, đang nối lại")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status("Lỗi đọc chạm: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                parser.reset()
                main.post { sink.onLost() } // huỷ các nút đang giữ, không để kẹt
                val s = stream
                stream = null
                if (s != null) runCatching { s.close() }
            }
            delay(RETRY_MS)
        }
    }

    private suspend fun detect(manager: ZynAdbConnectionManager): TouchDevice? = withContext(Dispatchers.IO) {
        val s = manager.openStream("shell:getevent -lp; echo ZYN_END")
        val sb = StringBuilder()
        try {
            BufferedReader(InputStreamReader(s.openInputStream())).use { r ->
                while (sb.length < 200_000) {
                    val line = r.readLine() ?: break
                    if (line.contains("ZYN_END")) break
                    sb.append(line).append('\n')
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { s.close() }
        }
        GeteventParser.parseDevice(sb.toString())
    }

    private companion object {
        const val RETRY_MS = 3_000L
    }
}
