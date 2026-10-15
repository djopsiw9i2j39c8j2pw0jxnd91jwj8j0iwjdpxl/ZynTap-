package com.zyntap.app.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.zyntap.app.data.ZynAdbConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Theo dõi app đang hiện ở phía trước, KHÔNG cần quyền mới: dùng luôn kết nối ADB Wi-Fi (quyền shell)
 * chạy `dumpsys activity activities` rồi đọc dòng "ResumedActivity".
 *
 * Dùng Activity đang resumed (không dùng cửa sổ có focus) nên các cửa sổ nổi / bàn phím của chính
 * ZynTap không làm lệch kết quả. Tắt màn hình thì tạm nghỉ không dò. Chưa nối ADB hoặc đọc lỗi thì
 * không báo gì (bên nhận coi như "chưa biết", giữ nguyên trạng thái hiện tại).
 */
class ForegroundWatcher(
    context: Context,
    private val onResumed: (Set<String>) -> Unit,
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            while (isActive) {
                var wait = POLL_MS
                if (power.isInteractive) {
                    val found = runCatching { query() }.getOrNull()
                    if (!found.isNullOrEmpty()) main.post { onResumed(found) }
                } else {
                    wait = IDLE_MS
                }
                delay(wait)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Các package đang resumed (có thể nhiều nếu chia đôi màn hình / nhiều màn hình). Null nếu chưa biết. */
    private fun query(): Set<String>? {
        val manager = ZynAdbConnectionManager.get(app)
        if (!manager.isConnected) return null
        var text = readAll(manager, "exec:$CMD")
        if (text.isBlank()) text = readAll(manager, "shell:$CMD")
        val set = parse(text)
        return if (set.isEmpty()) null else set
    }

    /** Chạy một lệnh, đọc hết đầu ra; quá [READ_TIMEOUT_MS] thì đóng luồng để khỏi kẹt. */
    private fun readAll(manager: ZynAdbConnectionManager, cmd: String): String {
        val stream = try { manager.openStream(cmd) } catch (_: Exception) { return "" }
        val closer = scope.launch {
            delay(READ_TIMEOUT_MS)
            runCatching { stream.close() }
        }
        val sb = StringBuilder()
        try {
            stream.openInputStream().bufferedReader().use { r ->
                val buf = CharArray(4096)
                while (sb.length < 200_000) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
            }
        } catch (_: Exception) {
        } finally {
            closer.cancel()
            runCatching { stream.close() }
        }
        return sb.toString()
    }

    companion object {
        // Android 10+: "topResumedActivity=ActivityRecord{... u0 com.foo/.Main t12}"
        // Android 11-: "mResumedActivity: ActivityRecord{... u0 com.foo/.Main t12}"
        private const val CMD = "dumpsys activity activities | grep ResumedActivity"
        private val RECORD = Regex("""ActivityRecord\{[0-9a-fA-F]+ u\d+ ([A-Za-z0-9_.]+)/""")

        private const val POLL_MS = 800L
        private const val IDLE_MS = 3_000L
        private const val READ_TIMEOUT_MS = 3_000L

        /** Lấy các package resumed; dòng "topResumedActivity" được xếp lên đầu. */
        fun parse(text: String): Set<String> {
            val top = LinkedHashSet<String>()
            val rest = LinkedHashSet<String>()
            for (line in text.lineSequence()) {
                if (line.contains("LastResumed")) continue // bản ghi cũ, không phải hiện tại
                val pkg = RECORD.find(line)?.groupValues?.get(1) ?: continue
                if (line.contains("topResumedActivity")) top.add(pkg) else rest.add(pkg)
            }
            return LinkedHashSet<String>().apply { addAll(top); addAll(rest) }
        }
    }
}
