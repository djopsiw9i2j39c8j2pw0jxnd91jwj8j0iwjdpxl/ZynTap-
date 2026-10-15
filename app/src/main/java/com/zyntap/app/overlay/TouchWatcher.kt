package com.zyntap.app.overlay

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

/**
 * Thông số cảm ứng: node `/dev/input/eventN` + khoảng giá trị thô của trục X/Y,
 * và (nếu máy có) giá trị lớn nhất của áp lực / vùng chạm để đổi ngón thật sang đơn vị MotionEvent.
 */
data class TouchDevice(
    val path: String,
    val maxX: Int,
    val maxY: Int,
    val maxPressure: Int = 0,
    val maxMajor: Int = 0,
    val maxMinor: Int = 0,
    /** Tên thiết bị cảm ứng (để bộ tiêm chạm chọn ĐÚNG device-id của tấm cảm ứng thật). */
    val name: String = "",
)

/** Thông số của ngón thật gần nhất, đã đổi sang đơn vị MotionEvent (pressure/size 0..1, vùng chạm theo pixel). */
data class FingerProfile(
    val pressure: Float,
    val size: Float,
    val touchMajor: Float,
    val touchMinor: Float,
    val orientation: Float,
)

/** Nguồn "ngón gốc" cho bộ tiêm chạm: thông số để sao chép và pointer-id còn trống. */
interface FingerSource {
    /** Thông số ngón thật gần nhất; null nếu chưa thấy ngón nào / máy không báo. */
    val profile: FingerProfile?

    /** Pointer-id nhỏ nhất chưa có ngón thật nào dùng (Android cũng cấp id theo kiểu "nhỏ nhất còn trống"). */
    fun freePointerId(): Int

    /** Tên tấm cảm ứng thật đã dò được ("" = chưa biết). */
    val deviceName: String

    /** Tình trạng bộ theo dõi ngón thật (để chẩn đoán): chạy hay không, đã nhận bao nhiêu byte/ngón... */
    val stats: String

    /** Các ngón thật đang chạm, phẳng hoá: [id0, x0, y0, id1, x1, y1, ...] (pixel màn hình hiện tại, id tăng dần). */
    fun activePointers(): IntArray
}

/**
 * Máy trạng thái đa chạm kiểu B, ăn thẳng các `struct input_event` ĐÃ GIẢI MÃ NHỊ PHÂN
 * (type/code/value là số, không phải chữ như `getevent -l`).
 * Thuần Kotlin, không phụ thuộc Android -> test được.
 * Chỉ báo "ngón chạm xuống" và "ngón nhả" (toạ độ thô); không báo di chuyển.
 */
class InputEventParser(
    /** Gọi ngay trước khi báo "ngón chạm xuống": giá trị thô áp lực / trục lớn / trục nhỏ (-1 = máy không báo). */
    private val onDownProfile: (pressure: Int, major: Int, minor: Int) -> Unit = { _, _, _ -> },
    /** Ngón đang chạm vừa di chuyển tới toạ độ thô mới (báo sau khi đã báo "chạm xuống"). */
    private val onMove: (slot: Int, rawX: Int, rawY: Int) -> Unit = { _, _, _ -> },
    private val emit: (slot: Int, down: Boolean, rawX: Int, rawY: Int) -> Unit,
) {
    private class Slot {
        var pressure = -1
        var major = -1
        var minor = -1
        var active = false
        var wantDown = false
        var wantUp = false
        var x = 0
        var y = 0
        var hasX = false
        var hasY = false
        var moved = false
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
                        s.pressure = -1
                        s.major = -1
                        s.minor = -1
                    }
                }
                ABS_MT_PRESSURE -> slot().pressure = value
                ABS_MT_TOUCH_MAJOR -> slot().major = value
                ABS_MT_TOUCH_MINOR -> slot().minor = value
                ABS_MT_POSITION_X -> slot().apply { x = value; hasX = true; moved = true }
                ABS_MT_POSITION_Y -> slot().apply { y = value; hasY = true; moved = true }
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
                s.moved = false
                onDownProfile(s.pressure, s.major, s.minor)
                emit(id, true, s.x, s.y)
            }
            if (s.wantUp) {
                s.wantUp = false
                if (s.active) {
                    s.active = false
                    emit(id, false, s.x, s.y)
                }
                s.moved = false
            }
            if (s.active && s.moved && s.hasX && s.hasY) {
                s.moved = false
                onMove(id, s.x, s.y)
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
        const val ABS_MT_TOUCH_MAJOR = 0x30
        const val ABS_MT_TOUCH_MINOR = 0x31
        const val ABS_MT_PRESSURE = 0x3a
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
        var pmax = 0
        var majmax = 0
        var minmax = 0
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
                line.contains("ABS_MT_PRESSURE") ->
                    c.pmax = MAX_RE.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                line.contains("ABS_MT_TOUCH_MAJOR") ->
                    c.majmax = MAX_RE.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                line.contains("ABS_MT_TOUCH_MINOR") ->
                    c.minmax = MAX_RE.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
        }
        val best = all.filter { it.mx > 0 && it.my > 0 }.maxByOrNull { it.score() } ?: return null
        return TouchDevice(best.path, best.mx, best.my, best.pmax, best.majmax, best.minmax, best.name)
    }

    /**
     * Đường lui khi máy không có `getevent`: đọc `/proc/bus/input/devices` chọn node theo tên cảm ứng,
     * khoảng giá trị thô lấy tạm bằng kích thước vật lý màn hình (`wm size`) [screenW] x [screenH].
     */
    fun parseProc(output: String, screenW: Int, screenH: Int): TouchDevice? {
        if (screenW <= 0 || screenH <= 0) return null
        var best: Triple<Int, String, String>? = null
        for (block in output.split(Regex("\\n\\s*\\n"))) {
            val name = Regex("N:\\s*Name=\"([^\"]*)\"").find(block)?.groupValues?.get(1) ?: continue
            val node = Regex("\\bevent(\\d+)\\b").find(block)?.value ?: continue
            val c = Cand("/dev/input/$node").apply {
                this.name = name
                direct = block.contains("PROP=2") || block.contains("PROP=02")
            }
            val sc = c.score()
            if (sc >= 2 && (best == null || sc > best.first)) best = Triple(sc, c.path, name)
        }
        val b = best ?: return null
        return TouchDevice(b.second, screenW - 1, screenH - 1, name = b.third)
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
) : FingerSource {
    private val app = context.applicationContext
    private val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var stream: AdbStream? = null

    val running get() = job?.isActive == true

    // ---- "ngón gốc": thông số + pointer-id đang dùng (cho bộ tiêm chạm) ----------------------

    @Volatile
    override var profile: FingerProfile? = null
        private set

    private val idLock = Any()
    private val slotIds = HashMap<Int, Int>()
    private val slotPos = HashMap<Int, IntArray>() // slot -> [x, y] (pixel màn hình)
    private var usedIds = 0 // bit n = pointer-id n đang có ngón thật

    @Volatile
    override var deviceName: String = ""
        private set

    @Volatile private var bytesTotal = 0L
    @Volatile private var downsTotal = 0
    @Volatile private var lastEventAt = 0L
    @Volatile private var restarts = 0

    override val stats: String
        get() {
            val age = if (lastEventAt == 0L) "chưa có" else "${SystemClock.uptimeMillis() - lastEventAt}ms trước"
            return "chạy=$running byte=$bytesTotal ngón-chạm-xuống=$downsTotal sự-kiện-cuối=$age " +
                "đang-chạm=${activePointers().size / 3} kết-nối-lại=$restarts"
        }

    private fun lowestFree(): Int {
        var id = 0
        while (id < MAX_POINTER_ID && ((usedIds shr id) and 1) == 1) id++
        return id
    }

    private fun claimId(slot: Int) {
        synchronized(idLock) {
            if (slotIds.containsKey(slot)) return
            val id = lowestFree()
            usedIds = usedIds or (1 shl id)
            slotIds[slot] = id
        }
    }

    private fun releaseId(slot: Int) {
        synchronized(idLock) {
            slotPos.remove(slot)
            val id = slotIds.remove(slot) ?: return
            usedIds = usedIds and (1 shl id).inv()
        }
    }

    private fun clearIds() {
        synchronized(idLock) { slotIds.clear(); slotPos.clear(); usedIds = 0 }
    }

    private fun setPos(slot: Int, x: Int, y: Int) {
        synchronized(idLock) {
            if (!slotIds.containsKey(slot)) return
            val p = slotPos.getOrPut(slot) { IntArray(2) }
            p[0] = x
            p[1] = y
        }
    }

    override fun freePointerId(): Int = synchronized(idLock) { lowestFree() }

    override fun activePointers(): IntArray = synchronized(idLock) {
        val list = slotIds.entries.mapNotNull { (slot, id) ->
            slotPos[slot]?.let { intArrayOf(id, it[0], it[1]) }
        }.sortedBy { it[0] }
        val out = IntArray(list.size * 3)
        list.forEachIndexed { i, t -> out[i * 3] = t[0]; out[i * 3 + 1] = t[1]; out[i * 3 + 2] = t[2] }
        out
    }

    /** Đổi giá trị thô của ngón vừa chạm xuống sang đơn vị MotionEvent rồi nhớ lại làm "ngón gốc". */
    private fun noteProfile(dev: TouchDevice, rawPressure: Int, rawMajor: Int, rawMinor: Int) {
        val hasPressure = dev.maxPressure > 0 && rawPressure > 0
        val hasMajor = dev.maxMajor > 0 && rawMajor > 0
        if (!hasPressure && !hasMajor) return // máy không báo gì -> giữ mặc định của bộ tiêm
        val w = screen().width().coerceAtLeast(1)
        val px = w.toFloat() / (dev.maxX + 1).coerceAtLeast(1) // trục lớn/nhỏ cùng đơn vị với trục X
        val major = if (hasMajor) (rawMajor * px).coerceAtLeast(1f) else 0f
        val minor = when {
            dev.maxMinor > 0 && rawMinor > 0 -> (rawMinor * px).coerceAtLeast(1f)
            else -> major
        }
        profile = FingerProfile(
            pressure = if (hasPressure) (rawPressure.toFloat() / dev.maxPressure).coerceIn(0.05f, 1f) else 1f,
            size = if (hasMajor) (rawMajor.toFloat() / dev.maxMajor).coerceIn(0f, 1f) else 1f,
            touchMajor = major,
            touchMinor = minor,
            orientation = 0f,
        )
    }

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
            val parser = InputEventParser(
                onDownProfile = { p, maj, min -> device?.let { noteProfile(it, p, maj, min) } },
                onMove = { slot, rx, ry ->
                    val d = device
                    if (d != null) {
                        val b = screen()
                        val (x, y) = InputEventParser.toScreen(rx, ry, d, rotation(), b.width(), b.height())
                        setPos(slot, x, y)
                        lastEventAt = SystemClock.uptimeMillis()
                    }
                },
            ) { slot, down, rx, ry ->
                val d = device ?: return@InputEventParser
                val b = screen()
                val (x, y) = InputEventParser.toScreen(rx, ry, d, rotation(), b.width(), b.height())
                if (down) { claimId(slot); setPos(slot, x, y); downsTotal++ } else releaseId(slot)
                lastEventAt = SystemClock.uptimeMillis()
                main.post { sink.onFinger(slot, down, x, y) }
            }
            val framer = InputEventStream(parser)
            var received = 0L
            restarts++
            try {
                val manager = ZynAdbConnectionManager.get(app)
                if (!manager.isConnected) {
                    // Nối lại ở nền (đã tự giãn nhịp), rồi thử lại sau
                    AdbSession.ensureInBackground(app)
                    delay(RETRY_MS)
                    continue
                }
                device = device ?: detect(manager)
                val dev = device
                if (dev == null) { delay(RETRY_MS * 3); continue }
                deviceName = dev.name
                val cmd = if (useShell) "shell:stty raw -echo 2>/dev/null; exec cat ${dev.path}"
                else "exec:cat ${dev.path}"
                val s = manager.openStream(cmd)
                stream = s
                val input = s.openInputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (n == 0) continue
                    received += n
                    bytesTotal += n
                    framer.feed(chunk, n)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // mất kết nối / luồng không phải input_event -> thử lại bên dưới
            } finally {
                parser.reset()
                clearIds()
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

    /** Chạy 1 lệnh shell (không pty), đọc hết đầu ra chữ. Rỗng nếu lỗi. */
    private suspend fun runText(manager: ZynAdbConnectionManager, cmd: String): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        for (prefix in arrayOf("exec:", "shell:")) {
            val s = try { manager.openStream(prefix + cmd) } catch (_: Exception) { continue }
            try {
                s.openInputStream().bufferedReader().use { r ->
                    val cb = CharArray(4096)
                    while (sb.length < 400_000) {
                        val n = r.read(cb)
                        if (n < 0) break
                        sb.append(cb, 0, n)
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { s.close() }
            }
            if (sb.isNotBlank()) break
        }
        sb.toString()
    }

    private suspend fun detect(manager: ZynAdbConnectionManager): TouchDevice? {
        TouchDeviceFinder.parseGetevent(runText(manager, "getevent -lp"))?.let { return it }
        val size = TouchDeviceFinder.parseWmSize(runText(manager, "wm size")) ?: return null
        return TouchDeviceFinder.parseProc(runText(manager, "cat /proc/bus/input/devices"), size.first, size.second)
    }

    private companion object {
        const val RETRY_MS = 3_000L
        const val MAX_POINTER_ID = 30
    }
}
