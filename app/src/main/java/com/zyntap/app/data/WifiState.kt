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
    @Volatile private var lastWifi: Boolean? = null
    @Volatile private var lastMobile: Boolean? = null

    private fun cm(context: Context) =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /**
     * Mạng thật sự thuộc [transport] và CHỈ thuộc transport đó (không phải VPN, không lai 4G+Wi-Fi),
     * để 4G/5G không bao giờ bị tính là Wi-Fi và ngược lại.
     */
    @Suppress("DEPRECATION")
    private fun anyNetwork(context: Context, transport: Int, exclude: Network? = null): Boolean = try {
        val cm = cm(context)
        val other = if (transport == NetworkCapabilities.TRANSPORT_WIFI)
            NetworkCapabilities.TRANSPORT_CELLULAR else NetworkCapabilities.TRANSPORT_WIFI
        cm.allNetworks.any { n ->
            if (n == exclude) return@any false
            val c = cm.getNetworkCapabilities(n) ?: return@any false
            c.hasTransport(transport) && !c.hasTransport(other) &&
                !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                cm.getNetworkInfo(n)?.isConnected != false &&
                // Nhiều máy giữ sẵn mạng di động chỉ để gọi/IMS/MMS dù đã TẮT dữ liệu: loại ra,
                // chỉ tính mạng thật sự ra Internet được
                (transport != NetworkCapabilities.TRANSPORT_CELLULAR ||
                    (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)))
        }
    } catch (t: Throwable) {
        false
    }

    /** Công tắc Wi-Fi của máy đang bật (tắt hẳn Wi-Fi thì chắc chắn không có kết nối Wi-Fi). */
    private fun wifiRadioOn(context: Context): Boolean = try {
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).isWifiEnabled
    } catch (t: Throwable) {
        true
    }

    /** Đang nối vào một mạng Wi-Fi (không tính dữ liệu di động). */
    fun isConnected(context: Context): Boolean =
        wifiRadioOn(context) && anyNetwork(context, NetworkCapabilities.TRANSPORT_WIFI)

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
                // Lúc onLost, mạng vừa mất có thể còn nằm trong allNetworks -> loại ra khi tính
                override fun onLost(network: Network) = publish(app, network)
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = publish(app)
            }
            val ok = runCatching {
                cm(app).registerNetworkCallback(
                    NetworkRequest.Builder().build(), // mọi loại mạng: Wi-Fi lẫn 4G/5G
                    cb,
                )
            }.onFailure { DebugLog.w("Wi-Fi", "Không theo dõi được trạng thái Wi-Fi", it) }.isSuccess
            if (ok) {
                callback = cb
                lastWifi = isConnected(app)
                lastMobile = hasMobile(app)
                DebugLog.i("Wi-Fi", describe(app))
            }
        }
    }

    private fun publish(context: Context, lost: Network? = null) {
        val wifi = wifiRadioOn(context) && anyNetwork(context, NetworkCapabilities.TRANSPORT_WIFI, lost)
        val mobile = anyNetwork(context, NetworkCapabilities.TRANSPORT_CELLULAR, lost)
        if (lastWifi == wifi && lastMobile == mobile) return
        val wifiChanged = lastWifi != wifi
        val mobileChanged = lastMobile != mobile
        lastWifi = wifi
        lastMobile = mobile
        if (wifiChanged) {
            if (wifi) DebugLog.i("Wi-Fi", "Đã kết nối Wi-Fi")
            else DebugLog.w("Wi-Fi", "Mất Wi-Fi — ${if (mobile) "đang dùng dữ liệu di động, ADB không dây sẽ không nối được" else "không có mạng"}")
        }
        if (mobileChanged) DebugLog.i("Mạng", "Dữ liệu di động: ${if (mobile) "đang có" else "đã tắt/mất"}")
        // Báo cho mọi nơi nghe khi Wi-Fi HOẶC dữ liệu di động đổi (người nghe tự đọc lại cả hai)
        listeners.forEach { runCatching { it(wifi) } }
    }
}
