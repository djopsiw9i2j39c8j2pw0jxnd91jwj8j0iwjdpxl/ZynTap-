package com.zyntap.app.overlay

import android.view.Surface
import com.zyntap.app.data.ZynAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Trạng thái cảm ứng dùng chung giữa [TouchWatcher] (đọc ngón thật) và [RawTouchInjector] (bơm ngón giả).
 *  - node `/dev/input/eventN` + khoảng toạ độ: dò MỘT lần, cả hai dùng chung;
 *  - kích thước bản ghi input_event (16/24 byte) do watcher nhận dạng từ luồng thật;
 *  - tập ngón vật lý đang chạm: để biết có được phép đụng BTN_TOUCH hay không;
 *  - slot của ngón giả: watcher bỏ qua slot này (không coi là ngón của người dùng).
 */
object TouchRegistry {
    @Volatile var device: TouchDevice? = null
    @Volatile var recordSize: Int = 0
    /** Watcher đang đọc luồng /dev/input -> [physicalDown] đáng tin. */
    @Volatile var watching: Boolean = false
    @Volatile private var injectedSlot = -1
    private val physical = ConcurrentHashMap.newKeySet<Int>()

    val physicalDown: Boolean get() = physical.isNotEmpty()

    fun setInjectedSlot(slot: Int) { injectedSlot = slot }
    fun isInjectedSlot(slot: Int) = slot == injectedSlot

    fun onPhysical(slot: Int, down: Boolean) {
        if (down) physical.add(slot) else physical.remove(slot)
    }

    fun clearPhysical() = physical.clear()

    /** Chạy 1 lệnh shell (không pty), đọc hết đầu ra chữ. Rỗng nếu lỗi. */
    suspend fun runText(manager: ZynAdbConnectionManager, cmd: String): String = withContext(Dispatchers.IO) {
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

    /** Tự tìm đường dẫn tấm cảm ứng: `getevent -lp`, lùi về `wm size` + `/proc/bus/input/devices`. */
    suspend fun detect(manager: ZynAdbConnectionManager): TouchDevice? {
        TouchDeviceFinder.parseGetevent(runText(manager, "getevent -lp"))?.let { return it }
        val size = TouchDeviceFinder.parseWmSize(runText(manager, "wm size")) ?: return null
        return TouchDeviceFinder.parseProc(runText(manager, "cat /proc/bus/input/devices"), size.first, size.second)
    }
}

/** Mã hoá `struct input_event` + đổi toạ độ pixel -> toạ độ thô của tấm cảm ứng. Thuần Kotlin, test được. */
object RawTouchEncoder {
    const val EV_SYN = 0x00
    const val EV_KEY = 0x01
    const val EV_ABS = 0x03
    const val SYN_REPORT = 0x00
    const val BTN_TOUCH = 0x14a
    const val ABS_MT_SLOT = 0x2f
    const val ABS_MT_TOUCH_MAJOR = 0x30
    const val ABS_MT_POSITION_X = 0x35
    const val ABS_MT_POSITION_Y = 0x36
    const val ABS_MT_TRACKING_ID = 0x39
    const val ABS_MT_PRESSURE = 0x3a

    /** Ngược của [InputEventParser.toScreen]: pixel màn hình hiện tại (đã xoay) -> toạ độ thô theo chiều tự nhiên. */
    fun toRaw(x: Int, y: Int, dev: TouchDevice, rotation: Int, w: Int, h: Int): Pair<Int, Int> {
        val fx = ((x + 0.5f) / w.coerceAtLeast(1)).coerceIn(0f, 1f)
        val fy = ((y + 0.5f) / h.coerceAtLeast(1)).coerceIn(0f, 1f)
        val (nx, ny) = when (rotation) {
            Surface.ROTATION_90 -> (1f - fy) to fx
            Surface.ROTATION_180 -> (1f - fx) to (1f - fy)
            Surface.ROTATION_270 -> fy to (1f - fx)
            else -> fx to fy
        }
        return (nx * dev.maxX).roundToInt().coerceIn(0, dev.maxX) to
            (ny * dev.maxY).roundToInt().coerceIn(0, dev.maxY)
    }

    private fun encode(recordSize: Int, events: List<IntArray>): ByteArray {
        val buf = ByteBuffer.allocate(events.size * recordSize).order(ByteOrder.LITTLE_ENDIAN)
        for (e in events) {
            // Timestamp để 0: kernel tự đóng dấu thời gian khi nhận qua evdev_write.
            if (recordSize == 24) { buf.putLong(0L); buf.putLong(0L) } else { buf.putInt(0); buf.putInt(0) }
            buf.putShort(e[0].toShort())
            buf.putShort(e[1].toShort())
            buf.putInt(e[2])
        }
        return buf.array()
    }

    /** Một khung "ngón chạm xuống" ở [slot], toàn bộ gói trong MỘT lần ghi (kết thúc bằng SYN_REPORT). */
    fun down(
        recordSize: Int, dev: TouchDevice, slot: Int, trackingId: Int,
        rawX: Int, rawY: Int, btnTouch: Boolean,
    ): ByteArray {
        val ev = ArrayList<IntArray>(9)
        ev += intArrayOf(EV_ABS, ABS_MT_SLOT, slot)
        ev += intArrayOf(EV_ABS, ABS_MT_TRACKING_ID, trackingId)
        ev += intArrayOf(EV_ABS, ABS_MT_POSITION_X, rawX)
        ev += intArrayOf(EV_ABS, ABS_MT_POSITION_Y, rawY)
        if (dev.touchMajorMax > 0) ev += intArrayOf(EV_ABS, ABS_MT_TOUCH_MAJOR, (dev.touchMajorMax / 10).coerceAtLeast(1))
        if (dev.pressureMax > 0) ev += intArrayOf(EV_ABS, ABS_MT_PRESSURE, (dev.pressureMax / 4).coerceAtLeast(1))
        if (btnTouch) ev += intArrayOf(EV_KEY, BTN_TOUCH, 1)
        ev += intArrayOf(EV_SYN, SYN_REPORT, 0)
        return encode(recordSize, ev)
    }

    /** Khung "ngón nhả" ở [slot]. Nhả slot chưa chạm thì lõi input tự bỏ qua -> gọi dư vẫn an toàn. */
    fun up(recordSize: Int, slot: Int, btnTouch: Boolean): ByteArray {
        val ev = ArrayList<IntArray>(4)
        ev += intArrayOf(EV_ABS, ABS_MT_SLOT, slot)
        ev += intArrayOf(EV_ABS, ABS_MT_TRACKING_ID, -1)
        if (btnTouch) ev += intArrayOf(EV_KEY, BTN_TOUCH, 0)
        ev += intArrayOf(EV_SYN, SYN_REPORT, 0)
        return encode(recordSize, ev)
    }
}

/**
 * Bơm "ngón thật" vào tấm cảm ứng bằng cách GHI `struct input_event` thẳng vào `/dev/input/eventN`
 * (quyền shell qua ADB), ở một slot đa chạm RIÊNG (slot cao nhất của máy) với tracking id riêng.
 *
 * Vì sao hết mất vuốt: `input tap` đi đường InputManager nên hệ thống coi là thiết bị khác và hay phát
 * ACTION_CANCEL cho cử chỉ đang diễn ra. Ở đây ngón giả là một contact nữa của CHÍNH tấm cảm ứng, nằm
 * song song với ngón thật (slot thấp), nên cả hai cùng sống.
 *
 * Một luồng `cat > eventN` được giữ mở suốt phiên (không spawn tiến trình cho mỗi lần chạm).
 */
class RawTouchInjector {
    sealed interface Prep {
        data object Ready : Prep
        /** Máy không hỗ trợ đường này (không đa chạm kiểu B / không ghi được node): dùng `input tap`. */
        data class Unsupported(val reason: String) : Prep
        /** Lỗi thoáng qua (chưa dò xong, ADB chập chờn): lần này lùi `input tap`, lần sau thử lại. */
        data class Transient(val reason: String) : Prep
    }

    private class Writer(val stream: AdbStream, val out: OutputStream)

    private sealed interface Open {
        class Ok(val writer: Writer) : Open
        data object Denied : Open
        data object Failed : Open
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var writer: Writer? = null
    @Volatile private var dev: TouchDevice? = null
    @Volatile private var recordSize = 0
    @Volatile private var unsupported: String? = null
    @Volatile private var btnSentDown = false
    private var trackingCounter = 0

    /** Dò thiết bị + mở luồng ghi nếu chưa có. */
    suspend fun prepare(manager: ZynAdbConnectionManager): Prep = lock.withLock {
        unsupported?.let { return Prep.Unsupported(it) }
        if (writer != null && dev != null && recordSize != 0) return Prep.Ready

        val d = TouchRegistry.device ?: TouchRegistry.detect(manager)
            ?: return Prep.Transient("Chưa dò được tấm cảm ứng")
        TouchRegistry.device = d
        if (!NODE_RE.matches(d.path)) return markUnsupported("Đường dẫn cảm ứng lạ: ${d.path}")
        if (d.maxSlot < 1) return markUnsupported("Tấm cảm ứng không có đa chạm kiểu B (ABS_MT_SLOT)")

        val rec = TouchRegistry.recordSize.takeIf { it == 16 || it == 24 } ?: probeRecordSize(manager)
        val w = when (val r = openWriter(manager, d.path)) {
            is Open.Ok -> r.writer
            Open.Denied -> return markUnsupported("Shell không có quyền ghi ${d.path}")
            Open.Failed -> return Prep.Transient("Không mở được luồng ghi ADB")
        }

        dev = d
        recordSize = rec
        writer = w
        TouchRegistry.setInjectedSlot(d.maxSlot)
        // Dọn ngón giả có thể còn kẹt từ phiên trước (app bị kill giữa lúc đang chạm).
        runCatching { write(w, RawTouchEncoder.up(rec, d.maxSlot, false)) }
        Prep.Ready
    }

    private fun markUnsupported(reason: String): Prep {
        unsupported = reason
        return Prep.Unsupported(reason)
    }

    /** 64-bit: 24 byte/bản ghi; 32-bit: 16 byte. Chỉ dùng khi watcher chưa nhận dạng được từ luồng thật. */
    private suspend fun probeRecordSize(manager: ZynAdbConnectionManager): Int {
        val bits = TouchRegistry.runText(manager, "getconf LONG_BIT").trim().toIntOrNull()
        return if (bits == 32) 16 else 24
    }

    private suspend fun openWriter(manager: ZynAdbConnectionManager, path: String): Open =
        withContext(Dispatchers.IO) {
            // [path] đã khớp NODE_RE (/dev/input/eventN) nên đưa thẳng vào lệnh là an toàn.
            val cmd = "exec:if [ -w $path ]; then echo ZYN_W_OK; exec cat > $path; else echo ZYN_W_NO; fi"
            val s = try { manager.openStream(cmd) } catch (_: Exception) { return@withContext Open.Failed }
            // Chờ dòng xác nhận; quá hạn thì đóng luồng để gỡ read đang treo.
            val guard = scope.launch { delay(OPEN_TIMEOUT_MS); runCatching { s.close() } }
            val line = try {
                s.openInputStream().bufferedReader().readLine()
            } catch (_: Exception) { null } finally { guard.cancel() }
            when (line?.trim()) {
                "ZYN_W_OK" -> Open.Ok(Writer(s, s.openOutputStream()))
                "ZYN_W_NO" -> { runCatching { s.close() }; Open.Denied }
                else -> { runCatching { s.close() }; Open.Failed }
            }
        }

    private fun write(w: Writer, bytes: ByteArray) {
        w.out.write(bytes)
        w.out.flush()
    }

    /**
     * Một cú chạm thật tại ([x], [y]) pixel: xuống -> giữ [holdMs] -> nhả.
     * Luôn nhả trong `finally` (kể cả khi bị huỷ) để ngón giả không bao giờ kẹt.
     * @throws IOException khi luồng ghi chết -> bên gọi nối lại ADB rồi thử lại.
     */
    suspend fun tap(x: Int, y: Int, rotation: Int, w: Int, h: Int, holdMs: Long = HOLD_MS) {
        val d = dev ?: throw IOException("Injector chưa sẵn sàng")
        val rec = recordSize
        val (rx, ry) = RawTouchEncoder.toRaw(x, y, d, rotation, w, h)
        val tid = nextTrackingId(d)
        // Chỉ đụng BTN_TOUCH khi chắc chắn không có ngón thật đang chạm (nếu có, nó đã bằng 1 rồi).
        val btn = !TouchRegistry.watching || !TouchRegistry.physicalDown
        var downSent = false
        try {
            withContext(Dispatchers.IO) {
                lock.withLock {
                    val wr = writer ?: throw IOException("Luồng ghi đã đóng")
                    try {
                        write(wr, RawTouchEncoder.down(rec, d, d.maxSlot, tid, rx, ry, btn))
                        downSent = true
                        btnSentDown = btn
                    } catch (e: IOException) {
                        invalidateLocked()
                        throw e
                    }
                }
            }
            delay(holdMs)
        } finally {
            // Đã gửi "xuống" thì BẮT BUỘC gửi "nhả", kể cả khi bị huỷ ngay giữa chừng.
            if (downSent) withContext(NonCancellable + Dispatchers.IO) { release(d, rec) }
        }
    }

    private suspend fun release(d: TouchDevice, rec: Int) = lock.withLock {
        val wr = writer ?: return@withLock
        val btn = btnSentDown && (!TouchRegistry.watching || !TouchRegistry.physicalDown)
        btnSentDown = false
        try {
            write(wr, RawTouchEncoder.up(rec, d.maxSlot, btn))
        } catch (_: IOException) {
            invalidateLocked()
        }
    }

    private fun nextTrackingId(d: TouchDevice): Int {
        // Dải id cao, ít khả năng trùng với bộ đếm tăng dần từ 0 của driver cảm ứng.
        val span = minOf(512, d.maxTrackingId / 2).coerceAtLeast(1)
        val base = d.maxTrackingId - span
        trackingCounter = (trackingCounter + 1) % span
        return base + trackingCounter
    }

    /** Bỏ luồng ghi (ADB vừa nối lại / chết). Lần [prepare] sau sẽ mở luồng mới. */
    suspend fun reset() = lock.withLock { invalidateLocked() }

    private fun invalidateLocked() {
        val w = writer
        writer = null
        if (w != null) runCatching { w.stream.close() }
    }

    /** Nhả ngón giả (nếu đang chạm) rồi đóng luồng. Gọi khi tắt dịch vụ. */
    fun close() {
        val d = dev
        val rec = recordSize
        scope.launch(NonCancellable) {
            lock.withLock {
                val wr = writer
                if (wr != null && d != null && rec != 0) {
                    runCatching { write(wr, RawTouchEncoder.up(rec, d.maxSlot, false)) }
                }
                invalidateLocked()
            }
        }
    }

    private companion object {
        val NODE_RE = Regex("^/dev/input/event\\d{1,3}$")
        const val OPEN_TIMEOUT_MS = 3_000L
        const val HOLD_MS = 30L
    }
}
