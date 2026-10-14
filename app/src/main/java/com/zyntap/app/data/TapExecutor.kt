package com.zyntap.app.data

import android.content.Context
import android.os.Build
import java.util.concurrent.Executors

/**
 * Chạy chuỗi điểm chạm bằng gỡ lỗi Wi-Fi (ADB) qua libadb-android:
 *  - Mỗi dòng "x, y"  -> input tap x y
 *  - Mỗi dòng "Nms"   -> chờ N mili-giây
 * Delay đứng TRƯỚC point thì chờ rồi mới bấm point đó; delay đứng SAU một point thì
 * point tiếp theo mới bị chờ (đúng kiểu đọc tuần tự từ trên xuống).
 */
object TapExecutor {

    sealed class Step {
        data class Tap(val x: Int, val y: Int) : Step()
        data class Wait(val ms: Long) : Step()
    }

    /** Tách chuỗi script thành danh sách bước Tap/Wait, bỏ qua dòng trống/không hợp lệ. */
    fun parse(script: String): List<Step> {
        val out = mutableListOf<Step>()
        script.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val delay = Regex("""(\d+)\s*ms""", RegexOption.IGNORE_CASE).matchEntire(line)
            if (delay != null) {
                out.add(Step.Wait(delay.groupValues[1].toLong()))
                return@forEach
            }
            val point = Regex("""(-?\d+)\s*,\s*(-?\d+)""").matchEntire(line)
            if (point != null) {
                out.add(Step.Tap(point.groupValues[1].toInt(), point.groupValues[2].toInt()))
            }
        }
        return out
    }

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var loopRunning = false

    @Volatile
    private var jobRunning = false

    @Volatile
    private var connected = false

    /** Mode Nhấn/Thả: chạy toàn bộ chuỗi đúng 1 lần. */
    fun runOnce(ctx: Context, script: String, onError: (String) -> Unit = {}) {
        if (jobRunning) return
        jobRunning = true
        val appCtx = ctx.applicationContext
        executor.execute {
            try {
                val mgr = connect(appCtx)
                    ?: throw IllegalStateException("Chưa kết nối được gỡ lỗi Wi-Fi")
                for (s in parse(script)) {
                    when (s) {
                        is Step.Tap -> tap(mgr, s.x, s.y)
                        is Step.Wait -> Thread.sleep(s.ms)
                    }
                }
            } catch (t: Throwable) {
                onError(t.message ?: "Lỗi chạy chuỗi chạm")
            } finally {
                jobRunning = false
            }
        }
    }

    /** Mode Giữ: lặp chuỗi liên tục tới khi stopLoop() được gọi (lúc thả ngón). */
    fun startLoop(ctx: Context, script: String, onError: (String) -> Unit = {}) {
        if (loopRunning) return
        loopRunning = true
        val appCtx = ctx.applicationContext
        executor.execute {
            try {
                val mgr = connect(appCtx)
                    ?: throw IllegalStateException("Chưa kết nối được gỡ lỗi Wi-Fi")
                val steps = parse(script)
                while (loopRunning) {
                    for (s in steps) {
                        if (!loopRunning) break
                        when (s) {
                            is Step.Tap -> tap(mgr, s.x, s.y)
                            is Step.Wait -> Thread.sleep(s.ms)
                        }
                    }
                }
            } catch (t: Throwable) {
                if (loopRunning) onError(t.message ?: "Lỗi chạy chuỗi chạm")
            } finally {
                loopRunning = false
            }
        }
    }

    fun stopLoop() {
        loopRunning = false
    }

    private fun connect(ctx: Context): ZynAdbConnectionManager? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val mgr = ZynAdbConnectionManager.get(ctx)
        if (!connected) {
            val ok = runCatching { mgr.connectTls(ctx, 8000L) }.getOrDefault(false)
            connected = ok
            if (!ok) return null
        }
        return mgr
    }

    private fun tap(mgr: ZynAdbConnectionManager, x: Int, y: Int) {
        runCatching {
            val stream = mgr.openStream("shell:input tap $x $y")
            runCatching { stream.close() }
        }.onFailure { connected = false } // lỗi -> lần sau kết nối lại
    }

    /** Ngắt trạng thái kết nối (gọi khi service dừng). */
    fun disconnect() {
        stopLoop()
        connected = false
    }
}
