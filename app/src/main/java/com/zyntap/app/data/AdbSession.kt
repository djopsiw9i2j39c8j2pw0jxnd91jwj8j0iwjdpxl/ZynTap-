package com.zyntap.app.data

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Phiên ADB DÙNG CHUNG cho cả app + menu nổi (cùng một tiến trình).
 *
 * Trước đây chỉ có đoạn "kiểm tra còn ghép nối không" rồi ngắt luôn, và lúc bấm nút main
 * thì không ai nối cả -> luôn báo "chưa kết nối". Giờ:
 *  - [connect] nối khi cần (có khoá, có thử lại) và GIỮ kết nối sống.
 *  - [ensureInBackground] được menu nổi gọi định kỳ để tự nối lại (sau khi cập nhật app,
 *    bật lại gỡ lỗi Wi-Fi, đổi mạng...) mà không cần mở app / ghép nối lại.
 *  - Một lần nối lỗi thoáng qua KHÔNG còn làm app "quên" ghép nối.
 */
object AdbSession {

    sealed interface Link {
        data object Connected : Link
        /** Máy từ chối khoá liên tục -> gần như chắc chắn đã bị xoá khỏi "Thiết bị đã ghép nối". */
        data object PairingLost : Link
        data class Failed(val message: String) : Link
    }

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /**
     * Đảm bảo đã có kết nối ADB. Gọi từ coroutine (tự chuyển sang IO).
     * @param attempts số lần thử; chỉ khi TẤT CẢ lần thử đều bị máy từ chối khoá mới trả [Link.PairingLost].
     */
    suspend fun connect(context: Context, timeoutMs: Long = 8_000L, attempts: Int = 2): Link =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val manager = ZynAdbConnectionManager.get(app)
            if (manager.isConnected) return@withContext Link.Connected
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return@withContext Link.Failed("Cần Android 11 trở lên để dùng gỡ lỗi Wi-Fi.")
            }
            mutex.withLock {
                if (manager.isConnected) return@withLock Link.Connected
                var lock: WifiManager.MulticastLock? = null
                try {
                    lock = runCatching {
                        (app.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                            .createMulticastLock("zyntap_session").apply {
                                setReferenceCounted(false)
                                acquire()
                            }
                    }.getOrNull()

                    var rejected = 0
                    var lastError = "Không kết nối được ADB."
                    val total = attempts.coerceAtLeast(1)
                    for (i in 1..total) {
                        try {
                            if (manager.connectTls(app, timeoutMs)) {
                                DebugLog.i("ADB", "Đã kết nối ADB (lần $i/$total)")
                                return@withLock Link.Connected
                            }
                            lastError = "Không tìm thấy dịch vụ Gỡ lỗi Wi-Fi. Hãy bật Gỡ lỗi không dây (cùng mạng Wi-Fi)."
                        } catch (e: AdbPairingRequiredException) {
                            rejected++
                            lastError = "Máy chưa nhớ khoá ghép nối."
                        } catch (e: IOException) {
                            if (e.message == "Connection failed") {
                                rejected++
                                lastError = "Máy từ chối kết nối."
                            } else {
                                lastError = e.message ?: "Lỗi mạng khi nối ADB."
                            }
                        } catch (t: Throwable) {
                            lastError = t.message ?: t.javaClass.simpleName
                        }
                        DebugLog.w("ADB", "Lần nối $i/$total thất bại: $lastError")
                        // Dọn trạng thái dở dang rồi thử lại sau một nhịp ngắn
                        runCatching { manager.disconnect() }
                        if (i < total) delay(100)
                    }
                    if (rejected == total) {
                        DebugLog.e("ADB", "Máy từ chối mọi lần nối ($total/$total) — thường là đã quên khoá ghép nối, cần ghép lại bằng mã 6 số")
                        Link.PairingLost
                    } else {
                        DebugLog.e("ADB", "Không nối được ADB: $lastError")
                        Link.Failed(lastError)
                    }
                } finally {
                    runCatching { lock?.release() }
                }
            }
        }

    /** Ngắt kết nối cũ (đã chết) để lần sau nối mới. */
    fun drop(context: Context) {
        DebugLog.w("ADB", "Ngắt kết nối cũ (đã chết) để nối lại")
        runCatching { ZynAdbConnectionManager.get(context.applicationContext).disconnect() }
    }

}
