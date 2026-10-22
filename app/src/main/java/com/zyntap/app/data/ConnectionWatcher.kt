package com.zyntap.app.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Theo dõi THEO SỰ KIỆN (không dò định kỳ) cho cả app lẫn menu nổi:
 *  - Mất Wi-Fi hoặc tắt Gỡ lỗi Wi-Fi  -> ngắt ngay kết nối ADB cũ (socket nửa sống sẽ khiến app tưởng vẫn "đã nối").
 *  - Có lại Wi-Fi + Gỡ lỗi Wi-Fi + đã ghép nối -> tự nối lại (thử vài lần, vì dịch vụ ADB cần vài giây để lên).
 */
object ConnectionWatcher {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false
    private var job: Job? = null

    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (started) return
            started = true
        }
        WifiState.start(app)
        WifiState.addListener { evaluate(app) }
        WifiAdbMonitor.addListener { _, _ -> evaluate(app) }
        WifiAdbMonitor.start(app)
        evaluate(app)
    }

    private fun evaluate(app: Context) {
        job?.cancel()
        job = scope.launch {
            delay(500) // gộp các sự kiện dồn dập (Wi-Fi + gỡ lỗi đổi gần như cùng lúc)
            val manager = ZynAdbConnectionManager.get(app)
            repeat(4) { attempt ->
                val wifi = WifiState.isConnected(app)
                val adbOn = WifiAdbMonitor.isWifiAdbEnabled(app)
                if (!wifi || !adbOn) {
                    if (manager.isConnected) {
                        DebugLog.w("ADB", "Mất Wi-Fi / tắt Gỡ lỗi Wi-Fi -> ngắt ADB")
                        AdbSession.drop(app)
                    }
                    return@launch
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@launch
                if (!AdbLinkStore.isPaired(app) || manager.isConnected) return@launch
                DebugLog.i("ADB", "Wi-Fi và Gỡ lỗi Wi-Fi đã bật -> tự nối ADB (lần ${attempt + 1}/4)")
                if (AdbSession.connect(app, attempts = 1) is AdbSession.Link.Connected) return@launch
                delay(2_000)
            }
        }
    }
}
