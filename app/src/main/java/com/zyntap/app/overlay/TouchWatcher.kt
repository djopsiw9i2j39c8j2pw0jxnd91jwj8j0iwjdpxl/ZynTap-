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
import java.io.IOException

/** Một ngón chạm đã được đổi sang toạ độ pixel của màn hình hiện tại. */
interface TouchSink {
    /** [down] = true: ngón vừa chạm xuống tại ([x], [y]); false: ngón vừa nhả. [slot] định danh ngón. */
    fun onFinger(slot: Int, down: Boolean, x: Int, y: Int)

    /** Mất theo dõi (đứt ADB...): huỷ mọi nút đang giữ, KHÔNG coi là nhả (tránh chạy nhầm chế độ Thả). */
    fun onLost()
}

/** Thông số cảm ứng: node `/dev/input/eventN` + khoảng giá trị thô của trục X/Y. */
data class TouchDevice(
    val path: String,
    val maxX: Int,
    val maxY: Int,
    /** ABS_MT_SLOT max (số slot - 1); -1 = không biết / không có slot (giao thức A). */
    val maxSlot: Int = -1,
    /** ABS_MT_TRACKING_ID max (thường 65535). */
    val maxTrackingId: Int = 65535,
    /** ABS_MT_TOUCH_MAJOR max; -1 = máy không có trục này. */
    val touchMajorMax: Int = -1,
    /** ABS_MT_PRESSURE max; -1 = máy không có trục này. */
    val pressureMax: Int = -1,
)

/**
 * Máy trạng thái đa chạm kiểu B, ăn thẳng các `struct input_event` ĐÃ GIẢI MÃ NHỊ PHÂN
 * (type/code/value là số, không phải chữ như `getevent -l`).
 * Thuần Kotlin, không phụ thuộc Android -> test được.
 * Chỉ báo "ngón chạm xuống" và "ngón nhả" (toạ độ thô); không báo di chuyển.
 */
class InputEventParser(private val emit: (slot: Int, down: Boolean, rawX: Int, rawY: Int) -> Unit) {
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

    fun onEvent(type: Int, code: Int, value: Int) {
        when (type) {
            EV_ABS -> when (code) {
                ABS_MT_SLOT -> current = value.coerceIn(0, MAX_SLOTS)
                ABS_MT_TRACKING_ID -> {
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
                ABS_MT_POSITION_X -> slot().apply { x = value; hasX = true }
                ABS_MT_POSITION_Y -> slot().apply { y = value; hasY = true }
            }
            EV_KEY -> if (code == BTN_TOUCH) {
                if (value == 0) {
                    slots.values.forEach { if (it.active || it.wantDown) { it.wantUp = true; it.wantDown = false } }
                } else if (slots.values.none { it.active || it.wantDown }) {
                    // Máy không báo tracking id: coi BTN_TOUCH là ngón ở slot 0
                    slots.getOrPut(0) { Slot() }.apply { wantDown = true; wantUp = false }
                }
            }
            EV_SYN -> if (code == SYN_REPORT) flush()
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
        const val EV_SYN = 0x00
        const val EV_KEY = 0x01
        const val EV_ABS = 0x03
        const val SYN_REPORT = 0x00
        const val BTN_TOUCH = 0x14a
        const val ABS_MT_SLOT = 0x2f
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39
        private const val MAX_SLOTS = 63

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
 * Tách luồng byte THÔ của `cat /dev/input/eventN` thành từng `struct input_event` rồi đẩy cho [InputEventParser].
 *
 * Kernel 64-bit: `{ long sec; long usec; u16 type; u16 code; s32 value }` = 24 byte.
 * Kernel/tiến trình 32-bit: `{ s32 sec; s32 usec; ... }` = 16 byte.
 * Không biết trước máy nào -> tự nhận dạng từ 48 byte đầu (bội chung của 16 và 24): bản ghi hợp lệ
 * có `usec < 1.000.000` (và phần cao của sec/usec = 0 với dạng 24 byte) và `type <= 0x1f`.
 */
class InputEventStream(private val parser: InputEventParser) {
    private var buf = ByteArray(4096)
    private var n = 0
    private var rec = 0 // kích thước 1 bản ghi; 0 = chưa nhận dạng

    /** Kích thước bản ghi đã nhận dạng (16/24), 0 nếu chưa biết. */
    val recordSize get() = rec

    /** @throws IOException nếu dữ liệu không giống input_event dạng nào (sai node, bị biến dạng...). */
    fun feed(data: ByteArray, len: Int) {
        if (len <= 0) return
        if (n + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, n + len))
        System.arraycopy(data, 0, buf, n, len)
        n += len
        if (rec == 0) {
            if (n < PROBE) return
            rec = when {
                valid(24) -> 24
                valid(16) -> 16
                else -> throw IOException("Luồng /dev/input không phải input_event")
            }
        }
        var off = 0
        while (n - off >= rec) {
            decode(off)
            off += rec
        }
        if (off > 0) {
            System.arraycopy(buf, off, buf, 0, n - off)
            n -= off
        }
    }

    private fun u16(o: Int) = (buf[o].toInt() and 0xff) or ((buf[o + 1].toInt() and 0xff) shl 8)
    private fun u32(o: Int): Long = (u16(o).toLong()) or (u16(o + 2).toLong() shl 16)
    private fun s32(o: Int): Int = u32(o).toInt()

    private fun valid(size: Int): Boolean {
        var o = 0
        while (o + size <= PROBE) {
            val ok = if (size == 24) {
                u32(o + 4) == 0L && u32(o + 12) == 0L && u32(o + 8) < 1_000_000L && u16(o + 16) <= 0x1f
            } else {
                u32(o + 4) < 1_000_000L && u16(o + 8) <= 0x1f
            }
            if (!ok) return false
            o += size
        }
        return true
    }

    private fun decode(o: Int) {
        val t = if (rec == 24) o + 16 else o + 8
        parser.onEvent(u16(t), u16(t + 2), s32(t + 4))
    }

    private companion object {
        const val PROBE = 48
    }
}

/** Dò thiết bị cảm ứng từ đầu ra văn bản (chỉ chạy MỘT lần lúc dò, không phải luồng sự kiện). */
object TouchDeviceFinder {
    private class Cand(val path: String) {
        var name = ""
        var direct = false
        var mx = -1
        var my = -1
        var slotMax = -1
        var trackMax = -1
        var majorMax = -1
        var pressMax = -1
        fun score(): Int {
            var s = 0
            if (direct) s += 4
            if (NAME_OK.containsMatchIn(name)) s += 2
            if (NAME_BAD.containsMatchIn(name)) s -= 3
            return s
        }
    }

    private val NAME_OK = Regex("touch|screen|(^|[^a-z])ts([^a-z]|$)|fts|goodix|synaptics|atmel|himax|novatek|gt9", RegexOption.IGNORE_CASE)
    private val NAME_BAD = Regex("pen|stylus|finger.?print|fp_|key|button|hall|gpio", RegexOption.IGNORE_CASE)
    private val MAX_RE = Regex("max\\s+(\\d+)")

    /** Đọc `getevent -lp`, chọn tấm cảm ứng có ABS_MT_POSITION_X/Y (ưu tiên INPUT_PROP_DIRECT / tên "touch"). */
    fun parseGetevent(output: String): TouchDevice? {
        val all = ArrayList<Cand>()
        var cur: Cand? = null
        for (raw in output.lines()) {
            val line = raw.trim()
            if (line.startsWith("add device")) {
                val path = line.substringAfter(": ", "").trim()
                cur = if (path.startsWith("/dev/")) Cand(path).also { all.add(it) } else null
                continue
            }
            val c = cur ?: continue
            when {
                line.startsWith("name:") -> c.name = line.substringAfter(':').trim().trim('"')
                line.contains("INPUT_PROP_DIRECT") -> c.direct = true
                line.contains("ABS_MT_POSITION_X") ->
                    c.mx = MAX_RE.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                line.contains("ABS_MT_POSITION_Y") ->
                    c.my = MAX_RE.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                line.contains("ABS_MT_SLOT") ->
                    c.slotMax = MAX_RE.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                line.contains("ABS_MT_TRACKING_ID") ->
                    c.trackMax = MAX_RE.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                line.contains("ABS_MT_TOUCH_MAJOR") ->
                    c.majorMax = MAX_RE.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                line.contains("ABS_MT_PRESSURE") ->
                    c.pressMax = MAX_RE.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            }
        }
        val best = all.filter { it.mx > 0 && it.my > 0 }.maxByOrNull { it.score() } ?: return null
        return TouchDevice(
            best.path, best.mx, best.my,
            maxSlot = best.slotMax,
            maxTrackingId = if (best.trackMax > 0) best.trackMax else 65535,
            touchMajorMax = best.majorMax,
            pressureMax = best.pressMax,
        )
    }

    /**
     * Đường lui khi máy không có `getevent`: đọc `/proc/bus/input/devices` chọn node theo tên cảm ứng,
     * khoảng giá trị thô lấy tạm bằng kích thước vật lý màn hình (`wm size`) [screenW] x [screenH].
     */
    fun parseProc(output: String, screenW: Int, screenH: Int): TouchDevice? {
        if (screenW <= 0 || screenH <= 0) return null
        var best: Pair<Int, String>? = null
        for (block in output.split(Regex("\\n\\s*\\n"))) {
            val name = Regex("N:\\s*Name=\"([^\"]*)\"").find(block)?.groupValues?.get(1) ?: continue
            val node = Regex("\\bevent(\\d+)\\b").find(block)?.value ?: continue
            val c = Cand("/dev/input/$node").apply {
                this.name = name
                direct = block.contains("PROP=2") || block.contains("PROP=02")
            }
            val sc = c.score()
            if (sc >= 2 && (best == null || sc > best.first)) best = sc to c.path
        }
        val b = best ?: return null
        return TouchDevice(b.second, screenW - 1, screenH - 1)
    }

    /** Đọc "Physical size: 1080x2400" của `wm size`. */
    fun parseWmSize(output: String): Pair<Int, Int>? {
        val m = Regex("Physical size:\\s*(\\d+)x(\\d+)").find(output) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }
}

/**
 * Theo dõi chạm của TOÀN MÀN HÌNH qua ADB, kể cả khi cửa sổ nút main không nhận chạm
 * (FLAG_NOT_TOUCHABLE). Dùng cho "chạm xuyên": ngón chạm lọt thẳng xuống app bên dưới, còn app này
 * chỉ "nghe" xem ngón có đặt lên nút main nào không.
 *
 * Cách làm: mở một luồng ADB chạy với quyền shell (`exec:cat /dev/input/eventN`) rồi đọc THẲNG
 * các `struct input_event` dạng nhị phân, không qua `getevent` / không parse chữ. Dùng dịch vụ `exec:`
 * (không cấp pty) nên byte không bị đổi `\n` -> `\r\n`; nếu máy không hỗ trợ thì lùi về `shell:` kèm `stty raw`.
 *
 * Android không cho một cửa sổ vừa nhận chạm vừa để chạm lọt xuống dưới, và ACTION_OUTSIDE bị
 * che toạ độ từ Android 12, nên đây là cách đáng tin duy nhất khi đã có ADB không dây.
 */
class TouchWatcher(
    context: Context,
    private val screen: () -> Rect,
    private val sink: TouchSink,
) {
    private val app = context.applicationContext
    private val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var stream: AdbStream? = null

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
        var useShell = false // false: exec:cat (thô, không pty); true: shell:stty raw; cat
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val parser = InputEventParser { slot, down, rx, ry ->
                val d = device ?: return@InputEventParser
                // Ngón do chính app bơm vào (slot riêng): không phải ngón của người dùng -> bỏ qua,
                // để không kích hoạt nút main và không tính vào "đang có ngón thật chạm".
                if (TouchRegistry.isInjectedSlot(slot)) return@InputEventParser
                TouchRegistry.onPhysical(slot, down)
                val b = screen()
                val (x, y) = InputEventParser.toScreen(rx, ry, d, rotation(), b.width(), b.height())
                main.post { sink.onFinger(slot, down, x, y) }
            }
            val framer = InputEventStream(parser)
            var received = 0L
            try {
                val manager = ZynAdbConnectionManager.get(app)
                if (!manager.isConnected) {
                    // Nối lại ở nền (đã tự giãn nhịp), rồi thử lại sau
                    AdbSession.ensureInBackground(app)
                    delay(RETRY_MS)
                    continue
                }
                device = device ?: TouchRegistry.detect(manager)
                val dev = device
                if (dev == null) { delay(RETRY_MS * 3); continue }
                TouchRegistry.device = dev
                val cmd = if (useShell) "shell:stty raw -echo 2>/dev/null; exec cat ${dev.path}"
                else "exec:cat ${dev.path}"
                val s = manager.openStream(cmd)
                stream = s
                val input = s.openInputStream()
                TouchRegistry.watching = true
                val chunk = ByteArray(4096)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (n == 0) continue
                    received += n
                    framer.feed(chunk, n)
                    if (framer.recordSize != 0) TouchRegistry.recordSize = framer.recordSize
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // mất kết nối / luồng không phải input_event -> thử lại bên dưới
            } finally {
                parser.reset()
                TouchRegistry.watching = false
                TouchRegistry.clearPhysical()
                main.post { sink.onLost() } // huỷ các nút đang giữ, không để kẹt
                val s = stream
                stream = null
                if (s != null) runCatching { s.close() }
            }
            // Chưa nhận được byte nào / sai định dạng: đổi kiểu mở luồng, rồi dò lại thiết bị
            if (received == 0L || framer.recordSize == 0) {
                failures++
                if (failures % 2 == 0) useShell = !useShell
                if (failures % 4 == 0) device = null
            } else {
                failures = 0
            }
            delay(RETRY_MS)
        }
    }

    private companion object {
        const val RETRY_MS = 3_000L
    }
}
