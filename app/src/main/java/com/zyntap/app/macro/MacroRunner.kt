package com.zyntap.app.macro

import android.content.Context
import android.os.Build
import com.zyntap.app.data.ZynAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Chạy chuỗi điểm chạm bằng ADB Wi-Fi (`input tap x y`).
 * Giữ MỘT shell ADB mở sẵn để mỗi lần chạm không phải bắt tay lại.
 */
class MacroRunner(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val shellLock = Mutex()
    private var stream: AdbStream? = null
    private var out: OutputStream? = null

    /** Job đang chạy (nhấn: 1 lượt; giữ: lặp tới khi thả). */
    private var job: Job? = null

    /** Báo lỗi ra màn hình (vd. chưa ghép nối). */
    var onError: ((String) -> Unit)? = null

    val running: Boolean get() = job?.isActive == true

    /** Người dùng chạm xuống nút main. Nhấn: chạy 1 lượt; giữ: bắt đầu lặp; thả: chưa chạy. */
    fun onMainPressed(main: MainButton) {
        when (main.mode) {
            MainMode.TAP -> if (!running) start(main, loop = false)
            MainMode.HOLD -> if (!running) start(main, loop = true)
            MainMode.RELEASE -> Unit // chạy khi thả, xem onMainReleased
        }
    }

    fun onMainReleased(main: MainButton) {
        when (main.mode) {
            MainMode.HOLD -> stop()
            MainMode.RELEASE -> if (!running) start(main, loop = false)
            MainMode.TAP -> Unit
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun start(main: MainButton, loop: Boolean) {
        val steps = ScriptParser.parse(main.script)
        val (planned, trailing) = ScriptParser.plan(steps)
        if (planned.isEmpty()) {
            onError?.invoke("${main.name}: chưa có điểm chạm nào")
            return
        }
        job = scope.launch {
            var carry = 0L // delay cuối chuỗi, áp cho điểm đầu của lượt lặp kế tiếp
            do {
                for ((i, p) in planned.withIndex()) {
                    val wait = p.waitBeforeMs + if (i == 0) carry else 0L
                    if (wait > 0) delay(wait)
                    if (!isActive) return@launch
                    if (!tap(p.x, p.y)) {
                        onError?.invoke("Chưa kết nối gỡ lỗi Wi-Fi, không chạm được")
                        return@launch
                    }
                }
                carry = trailing
            } while (loop && isActive)
        }
    }

    /** Gửi lệnh chạm qua shell ADB; trả về false nếu không kết nối được. */
    private suspend fun tap(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        shellLock.withLock {
            if (!ensureShell()) return@withContext false
            runCatching {
                out!!.write("input tap $x $y\n".toByteArray())
                out!!.flush()
            }.onFailure {
                closeShell()
                return@withContext false
            }
            true
        }
    }

    private fun ensureShell(): Boolean {
        if (stream != null && out != null) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return runCatching {
            val manager = ZynAdbConnectionManager.get(appContext)
            if (!manager.connectTls(appContext, CONNECT_TIMEOUT_MS)) return false
            val s = manager.openStream("shell:")
            stream = s
            out = s.openOutputStream()
            // Đọc và bỏ qua đầu ra của shell để bộ đệm không bị đầy
            Thread {
                runCatching {
                    val input = s.openInputStream()
                    val buf = ByteArray(256)
                    while (input.read(buf) >= 0) Unit
                }
            }.apply { isDaemon = true }.start()
            true
        }.getOrElse {
            closeShell()
            false
        }
    }

    private fun closeShell() {
        runCatching { out?.close() }
        runCatching { stream?.close() }
        out = null
        stream = null
    }

    fun shutdown() {
        stop()
        scope.launch { shellLock.withLock { closeShell() } }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000L
    }
}
