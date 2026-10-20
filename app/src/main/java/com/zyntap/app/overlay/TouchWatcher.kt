package com.zyntap.app.overlay

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import com.zyntap.app.data.AdbSession
import com.zyntap.app.data.DebugLog
import com.zyntap.app.data.ZynAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.OutputStream
import java.net.URLDecoder
import java.net.URLEncoder

/** Một ngón chạm đã được đổi sang toạ độ pixel của màn hình hiện tại. */
interface TouchSink {
    /** [down] = true: ngón vừa chạm xuống tại ([x], [y]); false: ngón vừa nhả. [slot] định danh ngón. */
    fun onFinger(slot: Int, down: Boolean, x: Int, y: Int)

    /** Mất theo dõi (đứt ADB...): huỷ mọi nút đang giữ, KHÔNG coi là nhả (tránh chạy nhầm chế độ Thả). */
    fun onLost()
}

/**
 * Thông số cảm ứng: node `/dev/input/eventN` + khoảng giá trị thô của trục X/Y.
 * Phần còn lại giúp helper phát lại ngón thật cho đúng: tên thiết bị (tìm deviceId thật),
 * khoảng áp lực và khoảng độ lớn vùng chạm (0 = máy không báo trục đó). Click của app KHÔNG clone gì từ ngón thật.
 */
data class TouchDevice(
    val path: String,
    val maxX: Int,
    val maxY: Int,
    val name: String = "",
    val maxPressure: Int = 0,
    val maxMajor: Int = 0,
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
        var mp = 0
        var mmaj = 0
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
                    c.mp = MAX_RE.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                line.contains("ABS_MT_TOUCH_MAJOR") ->
                    c.mmaj = MAX_RE.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
        }
        val best = all.filter { it.mx > 0 && it.my > 0 }.maxByOrNull { it.score() } ?: return null
        return TouchDevice(best.path, best.mx, best.my, best.name, best.mp, best.mmaj)
    }

    /**
     * Đường lui khi máy không có `getevent`: đọc `/proc/bus/input/devices` chọn node theo tên cảm ứng,
     * khoảng giá trị thô lấy tạm bằng kích thước vật lý màn hình (`wm size`) [screenW] x [screenH].
     */
    fun parseProc(output: String, screenW: Int, screenH: Int): TouchDevice? {
        if (screenW <= 0 || screenH <= 0) return null
        var best: Pair<Int, String>? = null
        var bestName = ""
        for (block in output.split(Regex("\\n\\s*\\n"))) {
            val name = Regex("N:\\s*Name=\"([^\"]*)\"").find(block)?.groupValues?.get(1) ?: continue
            val node = Regex("\\bevent(\\d+)\\b").find(block)?.value ?: continue
            val c = Cand("/dev/input/$node").apply {
                this.name = name
                direct = block.contains("PROP=2") || block.contains("PROP=02")
            }
            val sc = c.score()
            if (sc >= 2 && (best == null || sc > best.first)) { best = sc to c.path; bestName = name }
        }
        val b = best ?: return null
        return TouchDevice(b.second, screenW - 1, screenH - 1, bestName)
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
 *
 * CHẾ ĐỘ PROXY (mặc định): thay vì `cat`, chạy helper [com.zyntap.app.shell.InputProxy] (app_process,
 * quyền shell). Helper `EVIOCGRAB` tấm cảm ứng rồi phát lại ngón thật + click của app thành MỘT luồng
 * chạm duy nhất: click của app là một ngón ảo chạy song song với ngón thật, ngón nào chạm trước đứng trước,
 * không ai phải nhường ai, không clone thông số từ ngón thật, mã pointer do helper tự cấp.
 *
 * KHÔNG CÓ `input tap`: click chỉ đi qua helper. Helper chưa sẵn sàng / bơm lỗi thì click đó bị bỏ, không có đường lui.
 * Nếu helper không chạy được 3 lần liền thì chỉ lùi về đọc thô `cat` để nút main vẫn biết ngón đặt ở đâu (chưa click được).
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
    @Volatile private var link: ProxyLink? = null

    /** Bước vòng theo dõi đang làm — hiện trong Debug log để biết nó đang kẹt ở đâu. */
    @Volatile var stage: String = "chưa chạy"
        private set
    @Volatile private var proxyFails = 0
    @Volatile private var fastRetry = false
    /** Lần chạy helper vừa rồi kết thúc vì CHÍNH helper tự thoát sau khi hệ thống từ chối bơm (không phải ADB đứt). */
    @Volatile private var lastEndInjectBail = false
    /** Số lần liên tiếp helper tự thoát vì bị từ chối bơm (để giãn nhịp chạy lại). */
    private var injectBailStreak = 0
    @Volatile private var lastReinjectAt = 0L
    @Volatile private var reinjectGap = REINJECT_MIN_MS // giãn dần nếu bơm lại mà vẫn lỗi, về mức đầu khi bơm được

    @Volatile private var stageAt = SystemClock.elapsedRealtime()

    /** Dò cảm ứng / mở luồng ADB bị treo quá lâu (adbd không trả lời). */
    private class AdbHangException(msg: String) : IOException(msg)

    private fun setStage(text: String, log: Boolean = true) {
        if (stage == text) return
        stage = text
        stageAt = SystemClock.elapsedRealtime()
        if (log) DebugLog.i("Bơm", text)
    }

    /**
     * Ép chạy lại helper bơm (lỗi -> bơm lại -> lặp). Gọi khi click bị bỏ vì helper chưa sẵn sàng hoặc ADB gửi lỗi.
     * Đóng luồng helper hiện tại, xoá bộ đếm thất bại và thử lại gần như ngay. Có giãn nhịp để không bơm lại liên tục.
     */
    fun requestReinject(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReinjectAt < reinjectGap) return
        lastReinjectAt = now
        DebugLog.w("Bơm", "Ép bơm lại helper: $reason (lần kế tiếp cách tối thiểu ${reinjectGap * 2 / 1000}s nếu vẫn lỗi)")
        reinjectGap = (reinjectGap * 2).coerceAtMost(REINJECT_MAX_MS)
        proxyFails = 0
        fastRetry = true
        // Vòng theo dõi đang đứng im ở một bước (vd. dò getevent bị treo) mà không có luồng nào để đóng
        // -> đóng luồng cũng vô ích. Huỷ hẳn vòng, ngắt ADB cũ rồi chạy lại từ đầu.
        val stuckMs = now - stageAt
        if (!proxyReady && running && stuckMs > STUCK_MS) {
            DebugLog.w("Bơm", "Vòng theo dõi kẹt ở \"$stage\" ${stuckMs / 1000}s -> huỷ, ngắt ADB cũ và chạy lại")
            restartLoop()
            return
        }
        val s = stream
        if (s != null) scope.launch { runCatching { s.close() } }
    }

    private fun restartLoop() {
        job?.cancel()
        val s = stream
        stream = null
        link = null
        scope.launch {
            if (s != null) runCatching { s.close() }
            AdbSession.drop(app) // ngắt kết nối ADB nghi đã chết để lần sau nối mới
        }
        setStage("Khởi động lại vòng theo dõi cảm ứng")
        job = scope.launch { loop() }
    }

    val running get() = job?.isActive == true

    /** Helper đã grab cảm ứng và đang phát lại luồng chạm (click của app đi qua [tap]). */
    val proxyReady get() = link?.injecting == true

    /**
     * Vùng tròn (x, y, bán kính — pixel) mà ngón thật đặt xuống thì helper chỉ BÁO cho app chứ không phát xuống
     * hệ thống: nút main chỉ đè lên điểm nhấn (không bật chạm xuyên) vẫn nhận ngón như nút thường, không để ngón lọt
     * xuống app dưới. Nhớ lại để gửi lại khi helper (khởi động lại) báo READY.
     */
    @Volatile private var zonesLine = "ZONE"

    fun setSwallowZones(zones: List<Triple<Int, Int, Int>>) {
        val line = if (zones.isEmpty()) "ZONE" else "ZONE " + zones.joinToString(" ") { "${it.first} ${it.second} ${it.third}" }
        if (line == zonesLine) return
        zonesLine = line
        val l = link ?: return
        scope.launch(Dispatchers.IO) { runCatching { l.send(line) } }
    }

    /**
     * Vùng trợ vuốt tâm (nút main bật trợ vuốt): mỗi vùng "x y r mode speedPxS maxPx delayMs gainPct slopPx".
     * Chỉ ngón thật chạm xuống TRONG vùng mới bị helper can thiệp (kéo/ghìm/buff). Nhớ lại để gửi lại khi helper báo READY.
     */
    @Volatile private var assistLine = "ASSIST"

    fun setAssistZones(zones: List<List<Int>>) {
        val line = if (zones.isEmpty()) "ASSIST" else "ASSIST " + zones.joinToString(" ") { it.joinToString(" ") }
        if (line == assistLine) return
        assistLine = line
        val l = link ?: return
        scope.launch(Dispatchers.IO) { runCatching { l.send(line) } }
    }

    /** Kết nối tới helper: chỉ gửi lệnh một chiều (click không có số thứ tự, không chờ phản hồi). */
    private class ProxyLink(val stream: AdbStream) {
        private val out: OutputStream = stream.openOutputStream()
        @Volatile var injecting = false
        /** Helper đã báo READY (dù có bơm được hay không). */
        @Volatile var ready = false

        fun send(line: String) {
            synchronized(out) {
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }
    }

    /**
     * Chạy cả kịch bản [ops] (đã mã hoá: `T:x,y` · `M:x,y;x,y` · `W:ms`) TRONG helper bằng MỘT lệnh duy nhất.
     * Click nối click không còn đi vòng qua ADB nên giữa các click không có độ trễ nào ngoài `W` do người dùng đặt.
     * Bắn rồi quên; lỗi thì CHỈ BỎ (không `input tap`, không đường lui).
     * @return true nếu đã gửi cho helper; false nếu bị bỏ (helper chưa sẵn sàng / thụ động / ADB lỗi).
     */
    suspend fun runScript(id: Int, repeat: Boolean, holdMs: Long, gapMs: Long, ops: String): Boolean {
        val l = link ?: return false
        if (!l.injecting) return false
        try {
            withContext(Dispatchers.IO) { l.send("RUN $id ${if (repeat) 1 else 0} $holdMs $gapMs $ops") }
        } catch (e: IOException) {
            DebugLog.w("Bơm", "Gửi kịch bản cho helper lỗi, bỏ lần chạy này", e)
            requestReinject("gửi kịch bản cho helper lỗi (ADB đứt?)")
            return false
        }
        return true
    }

    /** Dừng kịch bản của nút [id] (nhả nút Giữ, mở menu...). Không chờ, không phản hồi. */
    fun stopScript(id: Int) {
        val l = link ?: return
        scope.launch(Dispatchers.IO) { runCatching { l.send("STOP $id") } }
    }

    fun stopAllScripts() {
        val l = link ?: return
        scope.launch(Dispatchers.IO) { runCatching { l.send("STOPALL") } }
    }

    @Suppress("DEPRECATION")
    private fun rotation(): Int = runCatching { wm.defaultDisplay.rotation }.getOrDefault(Surface.ROTATION_0)

    fun start() {
        if (running) return
        current = this
        proxyFails = 0
        setStage("Khởi động vòng theo dõi cảm ứng")
        job = scope.launch { loop() }
    }

    fun stop() {
        setStage("đã dừng", log = false)
        if (current === this) current = null
        job?.cancel()
        job = null
        val s = stream
        val l = link
        stream = null
        link = null
        if (s != null) scope.launch {
            // Bảo helper thoát gọn (nhả grab, huỷ ngón giả) rồi mới đóng luồng. Không chỉ dựa vào EOF:
            // nếu helper còn sống sót sang lần mở app sau thì nó giữ grab cảm ứng làm lần sau lỗi.
            if (l != null) {
                runCatching { l.send("QUIT") }
                delay(200)
            }
            runCatching { s.close() }
        }
    }

    private suspend fun loop() {
        var device: TouchDevice? = null
        var useShell = false // false: exec:cat (thô, không pty); true: shell:stty raw; cat
        var failures = 0
        var catStream: AdbStream? = null
        while (currentCoroutineContext().isActive) {
            val parser = InputEventParser { slot, down, rx, ry ->
                val d = device ?: return@InputEventParser
                val b = screen()
                val (x, y) = InputEventParser.toScreen(rx, ry, d, rotation(), b.width(), b.height())
                main.post { sink.onFinger(slot, down, x, y) }
            }
            val framer = InputEventStream(parser)
            var received = 0L
            var reaper: Job? = null
            try {
                val manager = ZynAdbConnectionManager.get(app)
                if (!manager.isConnected) {
                    // Nối lại ở nền (đã tự giãn nhịp), rồi thử lại sau
                    setStage("Chờ ADB nối để chạy helper bơm")
                    AdbSession.ensureInBackground(app)
                    delay(RETRY_MS)
                    continue
                }
                if (device == null) {
                    device = loadCachedDevice()
                    device?.let { DebugLog.i("Cảm ứng", "Dùng thiết bị cảm ứng đã nhớ: ${it.path} \"${it.name}\" (bỏ qua getevent)") }
                }
                if (device == null) {
                    setStage("Đang dò thiết bị cảm ứng (getevent)")
                    device = detect(manager)
                    device?.let {
                        saveCachedDevice(it)
                        DebugLog.i("Cảm ứng", "Thiết bị cảm ứng: ${it.path} \"${it.name}\" max=${it.maxX}x${it.maxY} áp lực=${it.maxPressure}")
                    }
                }
                val dev = device
                if (dev == null) {
                    DebugLog.e("Cảm ứng", "Không dò được thiết bị cảm ứng (getevent/wm size/proc đều không ra) -> chạm xuyên + bơm không hoạt động")
                    delay(RETRY_MS * 3); continue
                }
                if (proxyFails < MAX_PROXY_FAILS) {
                    killStaleHelpers(manager)
                    setStage("Đang chạy helper bơm trên ${dev.path} (chờ READY)")
                    val startedAt = SystemClock.elapsedRealtime()
                    val ok = runProxy(manager, dev)
                    val lived = SystemClock.elapsedRealtime() - startedAt
                    proxyFails = if (ok) 0 else proxyFails + 1
                    val bailed = ok && lastEndInjectBail
                    injectBailStreak = if (bailed && lived < HEALTHY_LIVED_MS) injectBailStreak + 1 else 0
                    if (bailed) {
                        // ADB vẫn sống: hệ thống từ chối nhận cảm ứng bơm vào. Chạy lại dồn dập vô ích và làm cảm ứng chớp tắt.
                        DebugLog.w("Bơm", "Helper tự thoát vì hệ thống từ chối bơm cảm ứng (lần liên tiếp $injectBailStreak) — ADB vẫn nối, không phải mất mạng/ADB")
                        setStage("Hệ thống từ chối bơm cảm ứng, chờ rồi thử lại", log = false)
                    } else if (ok) {
                        DebugLog.w("Bơm", "Helper đã dừng (mất ADB / bị đóng) -> chạy lại")
                        setStage("Helper dừng, chuẩn bị chạy lại", log = false)
                    }
                    if (!ok) {
                        DebugLog.e("Bơm", "Helper bơm cảm ứng thoát mà chưa READY (lần $proxyFails/$MAX_PROXY_FAILS) trên ${dev.path} — ROM chặn app_process/grab hoặc sai node cảm ứng")
                        if (proxyFails >= MAX_PROXY_FAILS) DebugLog.e("Bơm", "Bỏ helper, lùi về đọc thô `cat` (nút main vẫn nhận ngón nhưng KHÔNG click được vì không bơm chung luồng được)")
                    }
                    if (!ok) { device = null; clearCachedDevice() } // lần sau dò lại đường dẫn cảm ứng
                    // Helper đang chạy ổn định mà rớt (ADB chớp, bị kill...): chạy lại gần như ngay. Trong lúc không có helper,
                    // ngón đang đè (vuốt cam) không ai phát lại nên bị đơ; chờ thêm RETRY_MS (3s) chỉ kéo dài cú đơ đó.
                    // Helper chết nhanh sau khi lên (chập chờn liên tục) thì vẫn giãn nhịp như cũ để không bơm lại dồn dập.
                    val quick = fastRetry || (ok && lived > HEALTHY_LIVED_MS)
                    val wait = when {
                        // Bị từ chối bơm liên tiếp: giãn dần 3s, 6s, 12s, 24s, tối đa 30s
                        injectBailStreak > 0 -> minOf(RETRY_MS shl (injectBailStreak - 1).coerceAtMost(4), INJECT_BAIL_MAX_MS)
                        quick -> FAST_RETRY_MS
                        else -> RETRY_MS
                    }
                    delay(wait)
                    fastRetry = false
                    continue
                }
                // Lùi về đọc thô: chỉ tạm thời. Sau PROXY_RETRY_MS tự đóng luồng và thử bơm lại helper (lặp).
                setStage("Đọc thô bằng cat (helper không bơm được, chưa click được) — sẽ thử bơm lại sau ${PROXY_RETRY_MS / 1000}s")
                val cmd = if (useShell) "shell:stty raw -echo 2>/dev/null; exec cat ${dev.path}"
                else "exec:cat ${dev.path}"
                val s = openTimed(manager, cmd)
                stream = s
                catStream = s
                reaper = scope.launch {
                    delay(PROXY_RETRY_MS)
                    proxyFails = 0
                    DebugLog.i("Bơm", "Thử bơm lại helper sau khoảng lùi về cat")
                    runCatching { s.close() }
                }
                val input = s.openInputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (n == 0) continue
                    received += n
                    framer.feed(chunk, n)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AdbHangException) {
                // adbd không trả lời (kết nối nửa sống nửa chết) -> bỏ kết nối này, nối lại
                DebugLog.e("Bơm", "ADB treo: ${e.message} -> ngắt kết nối cũ để nối lại")
                setStage("ADB treo, đang nối lại")
                scope.launch { AdbSession.drop(app) }
                delay(RETRY_MS)
                continue
            } catch (e: Exception) {
                // mất kết nối / luồng không phải input_event -> thử lại bên dưới
                DebugLog.w("Cảm ứng", "Luồng đọc cảm ứng bị ngắt (nhận $received byte)", e)
            } finally {
                reaper?.cancel()
                parser.reset()
                main.post { sink.onLost() } // huỷ các nút đang giữ, không để kẹt
                // Chỉ đóng luồng CỦA MÌNH (vòng cũ bị huỷ thức dậy muộn không được đóng luồng của vòng mới)
                val s = catStream
                catStream = null
                if (s != null) { if (stream === s) stream = null; runCatching { s.close() } }
            }
            // Chưa nhận được byte nào / sai định dạng: đổi kiểu mở luồng, rồi dò lại thiết bị
            if (received == 0L || framer.recordSize == 0) {
                failures++
                if (failures % 2 == 0) useShell = !useShell
                if (failures % 4 == 0) device = null
            } else {
                failures = 0
            }
            delay(if (fastRetry) FAST_RETRY_MS else RETRY_MS)
            fastRetry = false
        }
    }

    /**
     * Chạy helper và đọc dòng trạng thái của nó cho tới khi luồng đóng.
     * @return true nếu helper đã báo READY (khởi động được), false nếu chết ngay (ROM chặn, sai node...).
     */
    private suspend fun runProxy(manager: ZynAdbConnectionManager, dev: TouchDevice): Boolean = coroutineScope {
        if (!PATH_RE.matches(dev.path)) return@coroutineScope false
        val b = screen()
        val cmd = "exec:CLASSPATH='${app.applicationInfo.sourceDir}' exec app_process /system/bin " +
            "com.zyntap.app.shell.InputProxy dev=${dev.path} maxx=${dev.maxX} maxy=${dev.maxY} " +
            "maxp=${dev.maxPressure} maxmaj=${dev.maxMajor} w=${b.width()} h=${b.height()} rot=${rotation()} " +
            "name=${URLEncoder.encode(dev.name, "UTF-8")}"
        val s = openTimed(manager, cmd)
        stream = s
        val l = ProxyLink(s)
        link = l
        var sawReady = false
        var unknownLines = 0
        var injectBailed = false
        lastEndInjectBail = false
        // Helper không báo READY trong thời hạn (treo / app_process không chạy) -> đóng luồng để vòng thử lại, không đứng im
        val readyGuard = launch(Dispatchers.IO) {
            delay(READY_TIMEOUT_MS)
            if (!sawReady) {
                DebugLog.e("Bơm", "Helper không báo READY sau ${READY_TIMEOUT_MS / 1000}s (treo hoặc app_process không chạy) — đóng và thử lại")
                runCatching { s.close() }
            }
        }
        // Nhịp tim (helper tự thoát + nhả grab nếu mất nhịp) kèm báo đổi xoay màn hình
        val beat = launch(Dispatchers.IO) {
            var lastW = b.width()
            var lastH = b.height()
            var lastRot = rotation()
            while (isActive) {
                delay(250)
                val nb = screen()
                val nr = rotation()
                try {
                    if (nr != lastRot || nb.width() != lastW || nb.height() != lastH) {
                        lastRot = nr; lastW = nb.width(); lastH = nb.height()
                        l.send("ROT $nr $lastW $lastH")
                    } else {
                        l.send("PING")
                    }
                } catch (_: IOException) {
                    break
                }
            }
        }
        try {
            withContext(Dispatchers.IO) {
                s.openInputStream().bufferedReader(Charsets.UTF_8).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        when {
                            line.startsWith("F ") -> onFingerLine(line)
                            // Click lỗi: helper chỉ bỏ click đó rồi báo một dòng (đã giãn nhịp phía helper), không có số thứ tự.
                            line.startsWith("ERR ") -> DebugLog.w("Bơm", "Helper: ${line.substring(4).trim()}")
                            line.startsWith("READY") -> {
                                sawReady = true; l.ready = true; l.injecting = line.contains("inj=1")
                                runCatching { l.send(zonesLine) }
                                runCatching { l.send(assistLine) }
                                setStage("Helper READY: ${line.trim()}", log = false)
                                if (l.injecting) { reinjectGap = REINJECT_MIN_MS; DebugLog.i("Bơm", "Helper sẵn sàng: $line") }
                                else DebugLog.e("Bơm", "Helper chạy nhưng KHÔNG bơm được ($line) — grab cảm ứng thất bại hoặc không tạo được bộ phát")
                            }
                            line.startsWith("LOG ") -> {
                                // Helper báo cả thông tin bình thường lẫn sự cố thật. Chỉ dòng có dấu hiệu sự cố mới là cảnh báo, còn lại là log thường.
                                val msg = line.substring(4).trim()
                                val lower = msg.lowercase()
                                if (lower.contains("inject hỏng liên tiếp")) injectBailed = true
                                val bad = listOf("fail", "lỗi", "hỏng", "watchdog", "bad cmd", "read:", "inject:", "grab:", "dropped", "dựng lại")
                                    .any { lower.contains(it) }
                                if (bad) DebugLog.w("Bơm", "Helper báo: $msg") else DebugLog.i("Bơm", "Helper báo: $msg")
                            }
                            line.startsWith("FAIL") -> { DebugLog.e("Bơm", "Helper báo lỗi: ${line.trim()}"); break }
                            else -> if (unknownLines++ < 5) DebugLog.w("Bơm", "Helper nói: ${line.take(200)}")
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Helper tự thoát sau khi bị từ chối bơm thì luồng đóng là HỆ QUẢ, không phải ADB đứt
            if (!injectBailed) DebugLog.w("Bơm", "Luồng helper bị ngắt (ADB đứt?) — helper tự thoát và nhả cảm ứng", e)
        } finally {
            beat.cancel()
            readyGuard.cancel()
            l.injecting = false
            if (link === l) link = null
            runCatching { s.close() }
            if (stream === s) stream = null
            main.post { sink.onLost() }
            lastEndInjectBail = injectBailed
        }
        sawReady
    }

    /** "F slot 1|0 x y" (toạ độ pixel, helper đã tính xoay) -> [TouchSink]. */
    private fun onFingerLine(line: String) {
        val t = line.split(' ')
        if (t.size < 5) return
        val slot = t[1].toIntOrNull() ?: return
        val down = t[2] == "1"
        val x = t[3].toIntOrNull() ?: return
        val y = t[4].toIntOrNull() ?: return
        main.post { sink.onFinger(slot, down, x, y) }
    }

    /**
     * Mở luồng ADB có thời hạn. `openStream` của libadb chờ adbd trả lời không giới hạn: nếu kết nối đã
     * nửa chết thì treo vĩnh viễn (đây chính là chỗ kẹt "Đang dò thiết bị cảm ứng" ở lần mở app thứ 2).
     */
    private suspend fun openTimed(manager: ZynAdbConnectionManager, cmd: String): AdbStream {
        val done = CompletableDeferred<AdbStream>()
        scope.launch {
            try {
                val s = manager.openStream(cmd)
                if (!done.complete(s)) runCatching { s.close() } // đến muộn: không ai cần nữa
            } catch (t: Throwable) {
                done.completeExceptionally(t)
            }
        }
        try {
            val s = withTimeoutOrNull(OPEN_TIMEOUT_MS) { done.await() }
            if (s != null) return s
        } catch (e: CancellationException) {
            done.completeExceptionally(e)
            throw e
        }
        done.completeExceptionally(AdbHangException("mở luồng quá ${OPEN_TIMEOUT_MS / 1000}s"))
        throw AdbHangException("mở luồng \"${cmd.take(40)}\" quá ${OPEN_TIMEOUT_MS / 1000}s")
    }

    /**
     * Chạy 1 lệnh shell (không pty), đọc hết đầu ra chữ. Rỗng nếu lỗi.
     * @throws AdbHangException nếu lệnh không xong trong [CMD_TIMEOUT_MS] (adbd treo).
     */
    private suspend fun runText(manager: ZynAdbConnectionManager, cmd: String): String {
        val opened = ArrayList<AdbStream>()
        val work = CompletableDeferred<String>()
        scope.launch {
            val sb = StringBuilder()
            try {
                for (prefix in arrayOf("exec:", "shell:")) {
                    val s = try { manager.openStream(prefix + cmd) } catch (_: Exception) { continue }
                    synchronized(opened) { opened.add(s) }
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
            } finally {
                work.complete(sb.toString())
            }
        }
        val r = try {
            withTimeoutOrNull(CMD_TIMEOUT_MS) { work.await() }
        } catch (e: CancellationException) {
            closeAll(opened); throw e
        }
        if (r == null) {
            closeAll(opened)
            throw AdbHangException("lệnh \"${cmd.take(40)}\" không xong sau ${CMD_TIMEOUT_MS / 1000}s")
        }
        return r
    }

    private fun closeAll(list: MutableList<AdbStream>) {
        val copy = synchronized(list) { ArrayList(list) }
        scope.launch { copy.forEach { runCatching { it.close() } } }
    }

    /**
     * Diệt helper cũ còn sống sót (app bị tắt/kill khi helper đang chạy, ADB đứt không báo...). Helper cũ
     * giữ EVIOCGRAB nên helper mới không grab được -> không click được. Mẫu `[c]om...` để
     * lệnh `sh -c` của chính nó không tự khớp mẫu.
     */
    private suspend fun killStaleHelpers(manager: ZynAdbConnectionManager) {
        val pat = "[c]om.zyntap.app.shell.InputProxy"
        val out = runText(manager, "pkill -f '$pat'; sleep 0.3; pkill -9 -f '$pat'; echo ZYN_CLEAN_DONE")
        if (!out.contains("ZYN_CLEAN_DONE")) DebugLog.w("Bơm", "Dọn helper cũ không xác nhận: ${out.take(120)}")
    }

    // ---- nhớ thiết bị cảm ứng để lần mở sau khỏi chạy getevent (bước từng bị treo) -------------

    private val prefs get() = app.getSharedPreferences("zyntap_touch", Context.MODE_PRIVATE)

    private fun saveCachedDevice(d: TouchDevice) {
        runCatching {
            prefs.edit().putString(
                "dev",
                listOf(d.path, d.maxX, d.maxY, URLEncoder.encode(d.name, "UTF-8"), d.maxPressure, d.maxMajor).joinToString("|"),
            ).apply()
        }
    }

    private fun clearCachedDevice() { runCatching { prefs.edit().remove("dev").apply() } }

    private fun loadCachedDevice(): TouchDevice? = runCatching {
        val t = (prefs.getString("dev", null) ?: return null).split('|')
        if (t.size < 6 || !PATH_RE.matches(t[0])) return null
        TouchDevice(
            t[0], t[1].toIntOrNull() ?: return null, t[2].toIntOrNull() ?: return null,
            URLDecoder.decode(t[3], "UTF-8"), t[4].toIntOrNull() ?: 0, t[5].toIntOrNull() ?: 0,
        )
    }.getOrNull()

    private suspend fun detect(manager: ZynAdbConnectionManager): TouchDevice? {
        TouchDeviceFinder.parseGetevent(runText(manager, "getevent -lp"))?.let { return it }
        val size = TouchDeviceFinder.parseWmSize(runText(manager, "wm size")) ?: return null
        return TouchDeviceFinder.parseProc(runText(manager, "cat /proc/bus/input/devices"), size.first, size.second)
    }

    companion object {
        /** Watcher đang chạy (để PointRunner gửi click qua proxy). */
        @Volatile var current: TouchWatcher? = null

        private const val RETRY_MS = 3_000L
        private const val MAX_PROXY_FAILS = 3
        private const val FAST_RETRY_MS = 400L
        private const val INJECT_BAIL_MAX_MS = 30_000L
        /** Helper sống quá chừng này mới coi là "đang chạy ổn" (rớt thì chạy lại nhanh). */
        private const val HEALTHY_LIVED_MS = 8_000L
        private const val READY_TIMEOUT_MS = 10_000L
        private const val REINJECT_MAX_MS = 120_000L
        private const val PROXY_RETRY_MS = 30_000L
        private const val REINJECT_MIN_MS = 8_000L
        /** Một bước (dò cảm ứng, mở luồng...) đứng im quá lâu thì coi là kẹt. */
        private const val STUCK_MS = 15_000L
        private const val OPEN_TIMEOUT_MS = 8_000L
        private const val CMD_TIMEOUT_MS = 8_000L
        /** Giữ mỗi click: 0 = DOWN rồi UP liền nhau, không chờ (tốc độ tối đa, giữa các click không có delay). */
        const val TAP_HOLD_MS = 0L
        const val TAP_HOLD_ONCE_MS = 0L
        /** Chỉ dùng khi bật "Chạm xuyên" + chế độ Thả: chờ ngón thật nhả xong mới click (tránh game nuốt click đầu). */
        const val TAP_GAP_AFTER_UP_MS = 30L
        private val PATH_RE = Regex("^/dev/input/event\\d+$")
    }
}
