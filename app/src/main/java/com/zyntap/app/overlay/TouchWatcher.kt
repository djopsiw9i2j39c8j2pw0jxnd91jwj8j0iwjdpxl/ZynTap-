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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException
import java.io.OutputStream
import java.net.URLDecoder
import java.net.URLEncoder

interface TouchSink {
     
    fun onFinger(slot: Int, down: Boolean, x: Int, y: Int)

    fun onLost()
}

data class TouchDevice(
    val path: String,
    val maxX: Int,
    val maxY: Int,
    val name: String = "",
    val maxPressure: Int = 0,
    val maxMajor: Int = 0,
)

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

class InputEventStream(private val parser: InputEventParser) {
    private var buf = ByteArray(4096)
    private var n = 0
    private var rec = 0 

    val recordSize get() = rec

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

    fun parseWmSize(output: String): Pair<Int, Int>? {
        val m = Regex("Physical size:\\s*(\\d+)x(\\d+)").find(output) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }
}

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

    @Volatile var stage: String = "chưa chạy"
        private set
    @Volatile private var proxyFails = 0
    @Volatile private var fastRetry = false
     
    @Volatile private var lastEndInjectBail = false
     
    private var injectBailStreak = 0
    @Volatile private var nextProxyAttemptAt = 0L
    @Volatile private var lastSessionHealthy = false
    @Volatile private var lastReinjectAt = 0L
    @Volatile private var reinjectGap = REINJECT_MIN_MS 

    @Volatile private var stageAt = SystemClock.elapsedRealtime()

    private class AdbHangException(msg: String) : IOException(msg)

    private class CmdSlowException(msg: String) : IOException(msg)

    @Volatile private var cleanSlowStreak = 0
    @Volatile private var lastReconnectAt = 0L

    private fun setStage(text: String, log: Boolean = true) {
        if (stage == text) return
        stage = text
        stageAt = SystemClock.elapsedRealtime()
        if (log) DebugLog.i("Bơm", text)
    }

    fun requestReinject(reason: String) {
        val now = SystemClock.elapsedRealtime()
        
        val existing = link
        if (existing != null && !existing.ready) return
        if (now < nextProxyAttemptAt || now - lastReinjectAt < reinjectGap) return
        lastReinjectAt = now
        DebugLog.w("Bơm", "Ép bơm lại helper: $reason (lần kế tiếp cách tối thiểu ${reinjectGap * 2 / 1000}s nếu vẫn lỗi)")
        reinjectGap = (reinjectGap * 2).coerceAtMost(REINJECT_MAX_MS)
        proxyFails = 0
        fastRetry = true

        val stuckMs = now - stageAt
        if (!proxyReady && running && stuckMs > STUCK_MS) {
            DebugLog.w("Bơm", "Vòng theo dõi kẹt ở \"$stage\" ${stuckMs / 1000}s -> huỷ, ngắt ADB cũ và chạy lại")
            restartLoop()
            return
        }
        val s = stream
        link?.plannedClose = true
        if (s != null) scope.launch { runCatching { s.close() } }
    }

    private fun restartLoop() {
        job?.cancel()
        val s = stream
        stream = null
        link = null
        scope.launch {
            if (s != null) runCatching { s.close() }
        }
        setStage("Khởi động lại vòng theo dõi cảm ứng")
        job = scope.launch { LOOP_OWNER.withLock { loop() } }
    }

    val running get() = job?.isActive == true

    val proxyReady get() = link?.injecting == true

    @Volatile private var zonesLine = "ZONE"

    fun setSwallowZones(zones: List<Triple<Int, Int, Int>>) {
        val line = if (zones.isEmpty()) "ZONE" else "ZONE " + zones.joinToString(" ") { "${it.first} ${it.second} ${it.third}" }
        if (line == zonesLine) return
        zonesLine = line
        val l = link ?: return
        scope.launch(Dispatchers.IO) { runCatching { l.send(line) } }
    }

    @Volatile private var assistLine = "ASSIST"

    fun setAssistZones(zones: List<List<Int>>) {
        val line = if (zones.isEmpty()) "ASSIST" else "ASSIST " + zones.joinToString(" ") { it.joinToString(" ") }
        if (line == assistLine) return
        assistLine = line
        val l = link ?: return
        scope.launch(Dispatchers.IO) { runCatching { l.send(line) } }
    }

    private class ProxyLink(val stream: AdbStream) {
        private val out: OutputStream = stream.openOutputStream()
        @Volatile var injecting = false
        @Volatile var closeAfter = 0L
         
        @Volatile var ready = false
        @Volatile var plannedClose = false

        fun send(line: String) {
            synchronized(out) {
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
        }
    }

    suspend fun runScript(id: Int, repeat: Boolean, holdMs: Long, gapMs: Long, ops: String): Boolean {
        val l = link ?: return false
        if (!l.injecting) return false
        try {
            val frame = screen()
            val rot = rotation()
            withContext(Dispatchers.IO) {
                l.send("ROT $rot ${frame.width()} ${frame.height()}\nRUN $id ${if (repeat) 1 else 0} $holdMs $gapMs $ops")
            }
        } catch (e: IOException) {
            DebugLog.w("Bơm", "Gửi kịch bản cho helper lỗi, bỏ lần chạy này", e)
            l.injecting = false
            requestReinject("gửi kịch bản cho helper lỗi (ADB đứt?)")
            return false
        }
        return true
    }

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
        job = scope.launch { LOOP_OWNER.withLock { loop() } }
    }

    fun stop() {
        if (job == null && stream == null) return
        setStage("đã dừng", log = false)
        if (current === this) current = null
        val oldJob = job
        val s = stream
        val l = link
        job = null
        stream = null
        link = null
        l?.plannedClose = true
        l?.closeAfter = SystemClock.elapsedRealtime() + 2300L

        oldJob?.cancel()
        scope.launch {
            runCatching { l?.send("STOPALL"); l?.send("QUIT") }
        }
        scope.launch {
            delay(2300) 
            runCatching { s?.close() }
            DebugLog.i("Dọn dẹp", "Đã yêu cầu STOPALL/QUIT và đóng phiên helper; watchdog là chốt khi app bị kill")
        }
    }

    private suspend fun loop() {
        var device: TouchDevice? = null
        var useShell = false 
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
                    
                    setStage("Chờ ADB nối để chạy helper bơm")
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReconnectAt >= RECONNECT_GAP_MS &&
                        com.zyntap.app.data.AdbLinkStore.isPaired(app) &&
                        com.zyntap.app.data.WifiState.isConnected(app) &&
                        com.zyntap.app.data.WifiAdbMonitor.isWifiAdbEnabled(app)
                    ) {
                        lastReconnectAt = now
                        DebugLog.i("ADB", "ADB đang ngắt -> tự nối lại")
                        com.zyntap.app.data.AdbSession.connect(app, attempts = 2)
                    }
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
                    if (!killStaleHelpers(manager)) {
                        setStage("Không xác nhận helper cũ đã thoát; không grab chồng", log = false)
                        delay(RETRY_MS)
                        continue
                    }
                    setStage("Đang chạy helper bơm trên ${dev.path} (chờ READY)")
                    val ok = runProxy(manager, dev)
                    proxyFails = if (ok) 0 else proxyFails + 1
                    val bailed = ok && lastEndInjectBail
                    injectBailStreak = if (bailed) (injectBailStreak + 1).coerceAtMost(30) else if (lastSessionHealthy) 0 else injectBailStreak
                    if (bailed) {
                        
                        DebugLog.w("Bơm", "Helper tự thoát vì hệ thống từ chối bơm cảm ứng (lần liên tiếp $injectBailStreak) — ADB vẫn nối, không phải mất mạng/ADB")
                        if (injectBailStreak == 1 || injectBailStreak == 3) {
                            val detail = runText(manager, "logcat -d -t 160 -s InputDispatcher InputReader 2>&1 | tail -c 5000")
                            DebugLog.w("Input hệ thống", detail.ifBlank { "Không lấy được log hệ thống; chưa biết lý do từ chối" })
                        }
                        setStage("Hệ thống từ chối bơm; đã nhả grab, chờ phục hồi có giãn nhịp", log = false)
                    } else if (ok) {
                        DebugLog.w("Bơm", "Helper đã dừng (mất ADB / bị đóng) -> chạy lại")
                        setStage("Helper dừng, chuẩn bị chạy lại", log = false)
                    }
                    if (!ok) {
                        DebugLog.e("Bơm", "Helper bơm cảm ứng thoát mà chưa READY (lần $proxyFails/$MAX_PROXY_FAILS) trên ${dev.path} — ROM chặn app_process/grab hoặc sai node cảm ứng")
                        if (proxyFails >= MAX_PROXY_FAILS) DebugLog.e("Bơm", "Bỏ helper, lùi về đọc thô `cat` (nút main vẫn nhận ngón nhưng KHÔNG click được vì không bơm chung luồng được)")
                    }
                    if (!ok) { device = null; clearCachedDevice() } 

                    val quick = fastRetry || (ok && lastSessionHealthy)
                    val wait = when {
                        
                        injectBailStreak > 0 -> minOf(RETRY_MS shl (injectBailStreak - 1).coerceAtMost(4), INJECT_BAIL_MAX_MS)
                        quick -> FAST_RETRY_MS
                        else -> RETRY_MS
                    }
                    nextProxyAttemptAt = SystemClock.elapsedRealtime() + wait
                    delay(wait)
                    nextProxyAttemptAt = 0L
                    fastRetry = false
                    continue
                }
                
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
                
                DebugLog.e("Bơm", "ADB treo: ${e.message} -> ngắt kết nối cũ để nối lại")
                setStage("ADB treo, đang nối lại")
                scope.launch { AdbSession.drop(app) }
                delay(RETRY_MS)
                continue
            } catch (e: Exception) {
                
                DebugLog.w("Cảm ứng", "Luồng đọc cảm ứng bị ngắt (nhận $received byte)", e)
            } finally {
                reaper?.cancel()
                parser.reset()
                main.post { sink.onLost() } 
                
                val s = catStream
                catStream = null
                if (s != null) { if (stream === s) stream = null; runCatching { s.close() } }
            }
            
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

    private suspend fun runProxy(manager: ZynAdbConnectionManager, dev: TouchDevice): Boolean = coroutineScope {
        if (!PATH_RE.matches(dev.path)) return@coroutineScope false
        val b = screen()
        val boot = runCatching { android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT) }.getOrDefault(-1)
        val cmd = "exec:CLASSPATH='${app.applicationInfo.sourceDir}' exec app_process /system/bin " +
            "com.zyntap.app.shell.InputProxy dev=${dev.path} maxx=${dev.maxX} maxy=${dev.maxY} " +
            "maxp=${dev.maxPressure} maxmaj=${dev.maxMajor} w=${b.width()} h=${b.height()} rot=${rotation()} " +
            "name=${URLEncoder.encode(dev.name, "UTF-8")} boot=$boot"
        val s = openTimed(manager, cmd)
        stream = s
        val l = ProxyLink(s)
        link = l
        var sawReady = false
        var unknownLines = 0
        var injectBailed = false
        var activeAt = 0L
        lastSessionHealthy = false
        lastEndInjectBail = false
        
        val readyGuard = launch(Dispatchers.IO) {
            delay(READY_TIMEOUT_MS)
            if (!sawReady) {
                DebugLog.e("Bơm", "Helper không báo READY sau ${READY_TIMEOUT_MS / 1000}s (treo hoặc app_process không chạy) — đóng và thử lại")
                runCatching { s.close() }
            }
        }
        
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
                        l.send("DEBUG " + if (DebugLog.enabled) "1" else "0")
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
                            line == "ACTIVE" -> {
                                if (activeAt == 0L) activeAt = SystemClock.elapsedRealtime()
                                DebugLog.i("Bơm", "Helper đã bơm thành công sự kiện đầu tiên (khác với chỉ READY)")
                            }
                            line.startsWith("F ") -> onFingerLine(line)
                            
                            line.startsWith("ERR ") -> DebugLog.w("Bơm", "Helper: ${line.substring(4).trim()}")
                            line.startsWith("READY") -> {
                                sawReady = true; l.ready = true; l.injecting = line.contains("inj=1")
                                runCatching { l.send(zonesLine) }
                                runCatching { l.send(assistLine) }
                                setStage("Helper READY: ${line.trim()}", log = false)
                                if (l.injecting) { DebugLog.i("Bơm", "Helper sẵn sàng: $line (chưa xác nhận sự kiện được hệ thống nhận)") }
                                else DebugLog.e("Bơm", "Helper chạy nhưng KHÔNG bơm được ($line) — grab cảm ứng thất bại hoặc không tạo được bộ phát")
                            }
                            line.startsWith("LOG ") -> {
                                
                                val msg = line.substring(4).trim()
                                val lower = msg.lowercase()
                                if (lower.contains("inject hỏng liên tiếp")) { injectBailed = true; l.injecting = false }
                                val bad = listOf("fail", "lỗi", "hỏng", "watchdog", "bad cmd", "read:", "inject:", "grab:", "dropped", "dựng lại")
                                    .any { lower.contains(it) }
                                if (bad) DebugLog.w("Bơm", "Helper báo: $msg")
                                else if (msg.startsWith("RECOVERED:") || msg.startsWith("Journal")) DebugLog.i("Bơm", msg)
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
            
            if (!injectBailed && !l.plannedClose && currentCoroutineContext().isActive) DebugLog.w("Bơm", "Luồng helper bị ngắt (ADB đứt?) — helper tự thoát và nhả cảm ứng", e)
        } finally {
            beat.cancel()
            readyGuard.cancel()
            l.injecting = false
            if (link === l) link = null
            withContext(kotlinx.coroutines.NonCancellable) {
                delay((l.closeAfter - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
                runCatching { s.close() }
            }
            if (stream === s) stream = null
            main.post { if (current == null || current === this@TouchWatcher) sink.onLost() }
            lastEndInjectBail = injectBailed
            lastSessionHealthy = activeAt != 0L && !injectBailed &&
                SystemClock.elapsedRealtime() - activeAt >= HEALTHY_LIVED_MS
            if (lastSessionHealthy) reinjectGap = REINJECT_MIN_MS
        }
        sawReady
    }

    private fun onFingerLine(line: String) {
        val t = line.split(' ')
        if (t.size < 5) return
        val slot = t[1].toIntOrNull() ?: return
        val down = t[2] == "1"
        val x = t[3].toIntOrNull() ?: return
        val y = t[4].toIntOrNull() ?: return
        main.post { sink.onFinger(slot, down, x, y) }
    }

    private suspend fun openTimed(manager: ZynAdbConnectionManager, cmd: String): AdbStream {
        val done = CompletableDeferred<AdbStream>()
        scope.launch {
            try {
                val s = manager.openStream(cmd)
                if (!done.complete(s)) runCatching { s.close() } 
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

    private suspend fun runText(manager: ZynAdbConnectionManager, cmd: String): String {
        val opened = ArrayList<AdbStream>()
        val work = CompletableDeferred<String>()
        val abandoned = AtomicBoolean(false)
        val streamOpened = AtomicBoolean(false)
        scope.launch {
            val sb = StringBuilder()
            try {
                for (prefix in arrayOf("exec:", "shell:")) {
                    val s = try { manager.openStream(prefix + cmd) } catch (_: Exception) { continue }
                    streamOpened.set(true)
                    val keep = synchronized(opened) {
                        if (abandoned.get()) false else { opened.add(s); true }
                    }
                    if (!keep) { runCatching { s.close() }; break }
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
            abandoned.set(true)
            closeAll(opened); throw e
        }
        if (r == null) {
            abandoned.set(true)
            closeAll(opened)
            if (streamOpened.get()) throw CmdSlowException("lệnh \"${cmd.take(40)}\" chạy chậm quá ${CMD_TIMEOUT_MS / 1000}s (ADB vẫn sống)")
            throw AdbHangException("lệnh \"${cmd.take(40)}\" không xong sau ${CMD_TIMEOUT_MS / 1000}s")
        }
        return r
    }

    private fun closeAll(list: MutableList<AdbStream>) {
        val copy = synchronized(list) { ArrayList(list) }
        scope.launch { copy.forEach { runCatching { it.close() } } }
    }

    private suspend fun killStaleHelpers(manager: ZynAdbConnectionManager): Boolean {

        val cmd = """
            cls='com.zyntap.app.shell.'InputProxy
            is_helper() { [ -r /proc/${'$'}1/cmdline ] && tr '\000' '\n' < /proc/${'$'}1/cmdline 2>/dev/null | grep -Fxq "${'$'}cls"; }
            rows=${'$'}(ps -A -o PID,ARGS) || { echo ZYN_CLEAN_FAIL; exit 1; }
            pids=${'$'}(printf '%s\n' "${'$'}rows" | awk -v cls="${'$'}cls" 'NR>1 {for(i=2;i<=NF;i++) if(${'$'}i==cls) {print ${'$'}1; break}}')
            for p in ${'$'}pids; do if is_helper "${'$'}p"; then kill -TERM "${'$'}p" 2>/dev/null; fi; done
            i=0
            while [ "${'$'}i" -lt 35 ]; do
              live=''
              for p in ${'$'}pids; do if is_helper "${'$'}p"; then live="${'$'}live ${'$'}p"; fi; done
              [ -z "${'$'}live" ] && break
              sleep 0.1
              i=${'$'}((i+1))
            done
            for p in ${'$'}pids; do if is_helper "${'$'}p"; then kill -KILL "${'$'}p" 2>/dev/null; fi; done
            sleep 0.1
            for p in ${'$'}pids; do if is_helper "${'$'}p"; then echo ZYN_CLEAN_FAIL; exit 1; fi; done
            echo ZYN_CLEAN_DONE
        """.trimIndent()
        val out = try {
            runText(manager, cmd)
        } catch (e: CmdSlowException) {
            
            cleanSlowStreak++
            DebugLog.w("Bơm", "Dọn helper cũ chậm (lần $cleanSlowStreak): ${e.message}")
            if (cleanSlowStreak >= 2) {
                DebugLog.w("Bơm", "Bỏ qua bước dọn helper cũ, chạy helper mới luôn")
                cleanSlowStreak = 0
                return true
            }
            return false
        }
        cleanSlowStreak = 0
        val ok = out.lineSequence().any { it.trim() == "ZYN_CLEAN_DONE" }
        if (!ok) DebugLog.w("Bơm", "Dọn helper cũ không xác nhận, hoãn helper mới: ${out.take(120)}")
        return ok
    }

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
        private val LOOP_OWNER = Mutex()
         
        @Volatile var current: TouchWatcher? = null

        private const val RETRY_MS = 3_000L
        private const val RECONNECT_GAP_MS = 6_000L
        private const val MAX_PROXY_FAILS = 3
        private const val FAST_RETRY_MS = 400L
        private const val INJECT_BAIL_MAX_MS = 30_000L
         
        private const val HEALTHY_LIVED_MS = 30_000L
        private const val READY_TIMEOUT_MS = 10_000L
        private const val REINJECT_MAX_MS = 120_000L
        private const val PROXY_RETRY_MS = 30_000L
        private const val REINJECT_MIN_MS = 8_000L
         
        private const val STUCK_MS = 15_000L
        private const val OPEN_TIMEOUT_MS = 8_000L
        private const val CMD_TIMEOUT_MS = 8_000L
         
        const val TAP_HOLD_MS = 0L
        const val TAP_HOLD_ONCE_MS = 0L
         
        const val TAP_GAP_AFTER_UP_MS = 30L
        private val PATH_RE = Regex("^/dev/input/event\\d+$")
    }
}
