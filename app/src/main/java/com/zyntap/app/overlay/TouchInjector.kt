package com.zyntap.app.overlay

import android.content.Context
import android.os.SystemClock
import com.zyntap.app.data.ZynAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Phía app của bộ tiêm chạm: mở MỘT tiến trình [com.zyntap.app.shell.InjectServer] bằng quyền shell qua ADB
 * (chạy thẳng từ file APK của app, tự lấy đường dẫn APK) rồi gửi lệnh chạm qua luồng đó.
 *
 * So với `input tap` mỗi lần:
 *  - chạm đi bằng device-id riêng + pointer-id còn trống + thông số sao chép từ ngón thật
 *    nên KHÔNG làm đứt cử chỉ của ngón đang vuốt;
 *  - không phải dựng tiến trình shell mới cho mỗi lần chạm (nhanh hơn nhiều).
 * Không cần ghi vào /dev/input (shell không có quyền ghi).
 *
 * Khi không dùng được (máy không cho, tiến trình chết...) [tap] trả false để nơi gọi lùi về `input tap`.
 */
class TouchInjector(context: Context) {
    private val app = context.applicationContext
    private val timer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "zyn-inject-watchdog").apply { isDaemon = true }
    }

    @Volatile private var stream: AdbStream? = null
    private var writer: OutputStream? = null
    private var reader: BufferedReader? = null
    private var retryAt = 0L
    private var consecutiveErrors = 0
    private var sentDeviceName = ""

    /** Lý do gần nhất khiến bộ tiêm không dùng được (để báo người dùng). */
    @Volatile var lastError: String = ""
        private set

    /** Thông tin máy chủ báo lúc sẵn sàng: SDK, đường tiêm, tấm cảm ứng tự dò được. */
    @Volatile var info: String = ""
        private set

    /** Đã (từng) khởi động thành công trong lần chạy này của app. */
    @Volatile var everReady = false
        private set

    /** Chẩn đoán lần tiêm gần nhất (hiện trong thông báo chẩn đoán). */
    @Volatile var diag: String = ""
        private set
    @Volatile private var deviceInfo: String = ""
    private var tapCount = 0

    val isUp get() = stream != null

    /** Khởi động sẵn (gọi ở nền) để lần chạm đầu không phải chờ JVM khởi động. */
    @Synchronized
    fun warmUp(manager: ZynAdbConnectionManager) {
        runCatching { ensureStarted(manager) }
    }

    /**
     * Chạm một lần tại ([x], [y]). Chạy trên luồng IO, chặn đến khi xong (thường vài chục ms).
     *
     * Cách không làm đứt ngón thật: lần chạm được tiêm như MỘT NGÓN THÊM trong CÙNG cử chỉ của tấm
     * cảm ứng thật (cùng device-id, ACTION_POINTER_DOWN/UP kèm đủ toạ độ MỚI NHẤT của các ngón thật),
     * y hệt khi bạn đặt thêm một ngón thứ hai. Game thấy đó là đa chạm bình thường nên ngón đang xoay cam
     * không bị CANCEL / UP. Nếu không có ngón thật nào đang chạm thì chạm đơn ACTION_DOWN/UP như thường.
     *
     * @return true = đã tiêm; false = bộ tiêm không dùng được lúc này, nơi gọi dùng đường lui.
     */
    @Synchronized
    fun tap(manager: ZynAdbConnectionManager, x: Int, y: Int, source: FingerSource?): Boolean {
        repeat(2) { attempt ->
            if (!ensureStarted(manager)) return false
            var downSent = false
            try {
                syncDevice(source?.deviceName.orEmpty())
                val finger = source?.profile
                val before = source?.activePointers() ?: IntArray(0)
                val id = freeId(before)
                val downReply = request(buildCommand('D', x, y, id, finger, before), REPLY_TIMEOUT_MS)
                if (downReply != "OK") return fail(downReply)
                val t0 = SystemClock.elapsedRealtime()
                downSent = true
                try {
                    Thread.sleep(HOLD_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                // Toạ độ ngón thật lúc NHẢ phải lấy mới (ngón xoay cam đã di chuyển trong lúc giữ)
                val after = if (before.isEmpty()) IntArray(0) else without(source?.activePointers() ?: IntArray(0), id)
                val upReply = request(buildCommand('U', x, y, id, finger, after), REPLY_TIMEOUT_MS)
                if (upReply != "OK") return fail(upReply)
                consecutiveErrors = 0
                tapCount++
                diag = buildString {
                    append("Lần chạm #").append(tapCount).append(" tại ").append(x).append(',').append(y).append('\n')
                    append(if (before.isEmpty()) "Kiểu: chạm đơn (không thấy ngón thật nào)" else "Kiểu: ngón thứ hai, ${before.size / 3} ngón thật")
                    append("\nPointer-id ảo: ").append(id)
                    if (before.isNotEmpty()) {
                        append("\nNgón thật lúc DOWN: ").append(fmt(before))
                        append("\nNgón thật lúc UP: ").append(fmt(after))
                    }
                    append("\nGiữ thực tế: ").append(SystemClock.elapsedRealtime() - t0).append("ms")
                    append("\nTên cảm ứng app dò: ").append(source?.deviceName?.ifBlank { "(chưa dò được)" } ?: "(không có)")
                    append("\nServer: ").append(deviceInfo.ifBlank { "(chưa nhận N)" })
                    append("\nHồ sơ ngón: ").append(finger?.let { "p=%.2f size=%.2f maj=%.1f".format(Locale.US, it.pressure, it.size, it.touchMajor) } ?: "chưa có")
                    append("\n").append(info)
                }
                return true
            } catch (e: IOException) {
                // Tiến trình chết / ADB đứt: dọn rồi dựng lại. Đã gửi DOWN rồi thì KHÔNG tiêm lại (tránh chạm đúp);
                // server tự nhả ngón ảo khi luồng đóng.
                lastError = e.message ?: "mất kết nối bộ tiêm chạm"
                shutdown()
                if (downSent) return true
                if (attempt == 1) {
                    retryAt = SystemClock.elapsedRealtime() + BACKOFF_MS
                    return false
                }
            }
        }
        return false
    }

    private fun fmt(p: IntArray): String =
        if (p.isEmpty()) "-" else (p.indices step 3).joinToString(" ") { "id${p[it]}@${p[it + 1]},${p[it + 2]}" }

    /** Server còn sống nhưng hệ thống từ chối: đếm lỗi, quá nhiều thì tạm nghỉ để dùng đường lui. */
    private fun fail(reply: String): Boolean {
        lastError = reply.removePrefix("ERR").trim().ifBlank { "tiêm chạm bị từ chối" }
        if (++consecutiveErrors >= MAX_ERRORS) {
            shutdown()
            retryAt = SystemClock.elapsedRealtime() + BACKOFF_MS
        }
        return false
    }

    /** Báo cho server tên tấm cảm ứng thật để nó dùng ĐÚNG device-id đó (cùng cử chỉ với ngón thật). */
    private fun syncDevice(name: String) {
        if (name.isBlank() || name == sentDeviceName) return
        val reply = request("N " + name.replace('\n', ' '), REPLY_TIMEOUT_MS)
        if (reply.startsWith("OK")) {
            sentDeviceName = name
            deviceInfo = reply.removePrefix("OK").trim()
        }
    }

    /** Pointer-id nhỏ nhất chưa có ngón thật nào dùng. [pointers] dạng phẳng [id, x, y, ...]. */
    private fun freeId(pointers: IntArray): Int {
        var id = 0
        while (id < MAX_POINTER_ID) {
            var used = false
            for (i in pointers.indices step 3) if (pointers[i] == id) { used = true; break }
            if (!used) return id
            id++
        }
        return MAX_POINTER_ID
    }

    private fun without(pointers: IntArray, id: Int): IntArray {
        val out = ArrayList<Int>(pointers.size)
        for (i in pointers.indices step 3) if (pointers[i] != id) {
            out.add(pointers[i]); out.add(pointers[i + 1]); out.add(pointers[i + 2])
        }
        return out.toIntArray()
    }

    private fun buildCommand(op: Char, x: Int, y: Int, pointerId: Int, f: FingerProfile?, others: IntArray): String {
        // Chưa thấy ngón thật nào -> giá trị giống `input tap` (áp lực 1, size 1) để hành vi cũ không đổi
        val pressure = f?.pressure ?: 1f
        val size = f?.size ?: 1f
        val major = f?.touchMajor ?: 0f
        val minor = f?.touchMinor ?: 0f
        val orientation = f?.orientation ?: 0f
        val sb = StringBuilder(
            String.format(
                Locale.US, "%c %d %d %d %.3f %.3f %.2f %.2f %.3f %d",
                op, x, y, pointerId.coerceIn(0, MAX_POINTER_ID), pressure, size, major, minor, orientation, others.size / 3,
            ),
        )
        for (i in others.indices step 3) sb.append(' ').append(others[i]).append(' ').append(others[i + 1]).append(' ').append(others[i + 2])
        return sb.toString()
    }

    // ---- vòng đời tiến trình phụ ---------------------------------------------------------------

    private fun ensureStarted(manager: ZynAdbConnectionManager): Boolean {
        if (stream != null) return true
        val now = SystemClock.elapsedRealtime()
        if (now < retryAt) return false
        if (!manager.isConnected) {
            lastError = "ADB chưa kết nối"
            return false
        }
        // Tự tìm đường dẫn APK của chính app (shell đọc được file này, chạy thẳng bằng app_process)
        val apk = app.applicationInfo.sourceDir
        if (apk.isNullOrBlank() || '\'' in apk) {
            lastError = "Không tìm được đường dẫn APK của app"
            retryAt = now + BACKOFF_MS
            return false
        }
        val noise = StringBuilder()
        try {
            val s = manager.openStream(
                "exec:CLASSPATH='$apk' exec app_process /system/bin com.zyntap.app.shell.InjectServer",
            )
            stream = s
            writer = s.openOutputStream()
            reader = BufferedReader(InputStreamReader(s.openInputStream(), Charsets.UTF_8))
            val hello = readLine(START_TIMEOUT_MS) { it.startsWith("ZYN_") || run { keepNoise(noise, it); false } }
            if (hello.startsWith("ZYN_READY")) {
                info = hello.removePrefix("ZYN_READY").trim()
                consecutiveErrors = 0
                everReady = true
                lastError = ""
                return true
            }
            lastError = hello.removePrefix("ZYN_FAIL").trim().ifBlank { "tiến trình phụ không khởi động được" }
        } catch (e: IOException) {
            lastError = (noise.toString().ifBlank { e.message }).orEmpty().ifBlank { "không mở được tiến trình phụ" }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
        }
        shutdown()
        retryAt = SystemClock.elapsedRealtime() + BACKOFF_MS
        return false
    }

    private fun keepNoise(sb: StringBuilder, line: String) {
        if (sb.length < 200 && line.isNotBlank()) sb.append(line.trim()).append(' ')
    }

    /** Gửi một dòng lệnh và đọc dòng trả lời OK / ERR (bỏ qua các dòng nhiễu khác). */
    private fun request(command: String, timeoutMs: Long): String {
        val out = writer ?: throw IOException("bộ tiêm chạm chưa mở")
        out.write((command + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
        return readLine(timeoutMs) { it == "OK" || it.startsWith("OK ") || it.startsWith("ERR") }
    }

    /** Đọc đến dòng thoả [accept]; quá [timeoutMs] thì đóng luồng để gỡ chặn (ném IOException). */
    private fun readLine(timeoutMs: Long, accept: (String) -> Boolean): String {
        val s = stream ?: throw IOException("bộ tiêm chạm chưa mở")
        val r = reader ?: throw IOException("bộ tiêm chạm chưa mở")
        val watchdog = timer.schedule({ runCatching { s.close() } }, timeoutMs, TimeUnit.MILLISECONDS)
        try {
            var skipped = 0
            while (true) {
                val line = r.readLine() ?: throw IOException("tiến trình phụ đã đóng")
                val t = line.trim()
                if (accept(t)) return t
                if (++skipped > 200) throw IOException("tiến trình phụ trả dữ liệu lạ")
            }
        } finally {
            watchdog.cancel(false)
        }
    }

    private fun shutdown() {
        val s = stream
        val w = writer
        stream = null
        writer = null
        reader = null
        sentDeviceName = ""
        // Báo server thoát gọn; đóng luồng cũng làm nó thoát (EOF)
        runCatching { w?.write("Q\n".toByteArray()); w?.flush() }
        runCatching { s?.close() }
    }

    fun close() {
        shutdown()
        timer.shutdownNow()
    }

    private companion object {
        const val HOLD_MS = 30L          // giữ ngón 30ms mỗi lần chạm (đủ để game nhận ít nhất một khung hình)
        const val MAX_POINTER_ID = 30
        const val START_TIMEOUT_MS = 8_000L
        const val REPLY_TIMEOUT_MS = 3_000L
        const val MAX_ERRORS = 3
        const val BACKOFF_MS = 45_000L
    }
}
