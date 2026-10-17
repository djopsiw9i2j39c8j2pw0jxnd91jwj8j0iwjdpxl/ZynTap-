package com.zyntap.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Trạng thái Wi-Fi THẬT của máy: có đang nối vào mạng Wi-Fi hay không.
 *
 * Khác với "Gỡ lỗi Wi-Fi" (ADB không dây) và khác với "có mạng": máy đang dùng 4G mà không nối Wi-Fi
 * thì [isConnected] vẫn là false. Dò theo transport của từng mạng nên không bị 4G làm nhiễu
 * (kể cả khi Wi-Fi và 4G cùng bật).
 */
object WifiState {

    private var callback: ConnectivityManager.NetworkCallback? = null
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    @Volatile private var last: Boolean? = null

    private fun cm(context: Context) =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    @Suppress("DEPRECATION")
    private fun anyNetwork(context: Context, transport: Int): Boolean = try {
        val cm = cm(context)
        cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(transport) == true }
    } catch (t: Throwable) {
        false
    }

    /** Đang nối vào một mạng Wi-Fi (không tính dữ liệu di động). */
    fun isConnected(context: Context): Boolean = anyNetwork(context, NetworkCapabilities.TRANSPORT_WIFI)

    /** Có dữ liệu di động (4G/5G) đang hoạt động. */
    fun hasMobile(context: Context): Boolean = anyNetwork(context, NetworkCapabilities.TRANSPORT_CELLULAR)

    /** Mô tả ngắn để ghi log / báo cáo. */
    fun describe(context: Context): String {
        val wifi = isConnected(context)
        val mobile = hasMobile(context)
        return "Wi-Fi: ${if (wifi) "đã kết nối" else "chưa kết nối"} · Dữ liệu di động: ${if (mobile) "đang có" else "không"}"
    }

    fun addListener(l: (Boolean) -> Unit) { listeners.add(l) }
    fun removeListener(l: (Boolean) -> Unit) { listeners.remove(l) }

    /** Bắt đầu theo dõi đổi Wi-Fi và ghi log mỗi lần nối / mất. Gọi nhiều lần vẫn an toàn. */
    fun start(context: Context) {
        synchronized(this) {
            if (callback != null) return
            val app = context.applicationContext
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = publish(app)
                override fun onLost(network: Network) = publish(app)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = publish(app)
            }
            val ok = runCatching {
                cm(app).registerNetworkCallback(
                    NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                    cb,
                )
            }.onFailure { DebugLog.w("Wi-Fi", "Không theo dõi được trạng thái Wi-Fi", it) }.isSuccess
            if (ok) {
                callback = cb
                last = isConnected(app)
                DebugLog.i("Wi-Fi", describe(app))
            }
        }
    }

    private fun publish(context: Context) {
        val now = isConnected(context)
        if (last == now) return
        last = now
        if (now) DebugLog.i("Wi-Fi", "Đã kết nối Wi-Fi")
        else DebugLog.w("Wi-Fi", "Mất Wi-Fi — ${if (hasMobile(context)) "đang dùng dữ liệu di động, ADB không dây sẽ không nối được" else "không có mạng"}")
        listeners.forEach { runCatching { it(now) } }
    }
}
