package com.zyntap.app.data

import android.content.Context
import android.database.ContentObserver
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Theo dõi trạng thái "Gỡ lỗi Wi-Fi" (Settings.Global key `adb_wifi_enabled`).
 * Mỗi khi người dùng bật lại gỡ lỗi Wi-Fi, monitor sẽ dò cổng
 * `_adb-tls-connect._tcp` hiện tại qua mDNS và lưu lại — tương đương việc
 * "tự kết nối lại" vì cổng này đổi mỗi lần bật/tắt gỡ lỗi Wi-Fi.
 */
object WifiAdbMonitor {

    /** Key trong Settings.Global (ẩn khỏi SDK public nên dùng chuỗi). */
    private const val SETTING_ADB_WIFI = "adb_wifi_enabled"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: ContentObserver? = null

    @Volatile
    var onChanged: ((enabled: Boolean, connectPort: Int?) -> Unit)? = null

    fun start(context: Context) {
        if (observer != null) return
        val appContext = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                val enabled = isWifiAdbEnabled(appContext)
                if (enabled) {
                    scope.launch {
                        val port = runCatching { discoverConnectPort(appContext) }.getOrNull()
                        if (port != null && port > 0) {
                            AdbLinkStore.saveConnectPort(appContext, port)
                        }
                        onChanged?.invoke(true, port)
                    }
                } else {
                    onChanged?.invoke(false, null)
                }
            }
        }
        observer = obs
        appContext.contentResolver.registerContentObserver(
            Settings.Global.getUriFor(SETTING_ADB_WIFI),
            false,
            obs,
        )
        // Đồng bộ lần đầu
        obs.onChange(false)
    }

    fun stop(context: Context) {
        observer?.let { context.applicationContext.contentResolver.unregisterContentObserver(it) }
        observer = null
    }

    fun isWifiAdbEnabled(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, SETTING_ADB_WIFI, 0) == 1
    } catch (t: Throwable) {
        false
    }

    /** Dò cổng `_adb-tls-connect._tcp` hiện tại (cổng đổi mỗi lần bật lại gỡ lỗi Wi-Fi). */
    suspend fun discoverConnectPort(context: Context, timeoutMs: Long = 8_000L): Int? =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext null
            val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = wifi.createMulticastLock("zyntap_connect").apply {
                setReferenceCounted(true)
                acquire()
            }
            var result: Int? = null
            val deferred = CompletableDeferred<Int?>()
            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}
                override fun onDiscoveryStopped(regType: String) {}
                override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                    deferred.complete(null)
                }
                override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    runCatching {
                        nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                deferred.complete(null)
                            }
                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                deferred.complete(resolved.port.takeIf { it > 0 })
                            }
                        })
                    }.onFailure { deferred.complete(null) }
                }
            }
            try {
                nsd.discoverServices(
                    WifiAdbPairing.SERVICE_CONNECT,
                    NsdManager.PROTOCOL_DNS_SD,
                    discoveryListener,
                )
                result = withTimeout(timeoutMs) { deferred.await() }
            } catch (_: Throwable) {
                result = null
            } finally {
                runCatching { nsd.stopServiceDiscovery(discoveryListener) }
                runCatching { lock.release() }
            }
            result
        }
}
