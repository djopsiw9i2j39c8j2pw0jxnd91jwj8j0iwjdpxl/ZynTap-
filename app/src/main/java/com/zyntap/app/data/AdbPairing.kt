package com.zyntap.app.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import io.github.muntashirakon.adb.AdbPairingRequiredException
import java.io.IOException
import java.net.InetAddress

sealed interface PairState {
    data object Idle : PairState
    data object Working : PairState
    data object Success : PairState
    data class Failed(val message: String) : PairState
}

interface AdbPairing {
    suspend fun pair(code: String): Result<Unit>
}

@RequiresApi(Build.VERSION_CODES.R)
class WifiAdbPairing(private val context: Context) : AdbPairing {

    override suspend fun pair(code: String): Result<Unit> = withContext(Dispatchers.IO) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        var lock: WifiManager.MulticastLock? = null
        try {
            lock = wifi.createMulticastLock("zyntap_pair").apply {
                setReferenceCounted(true)
                acquire()
            }
            
            val service = discoverService(nsd, SERVICE_PAIRING, PAIR_TIMEOUT_MS)
            val host = pickHost(service)
                ?: return@withContext Result.failure(
                    IllegalStateException("Không đọc được địa chỉ dịch vụ ghép nối."),
                )
            val port = service.port
            if (port <= 0) {
                return@withContext Result.failure(IllegalStateException("Không đọc được cổng ghép nối."))
            }

            val address = host.hostAddress
                ?: return@withContext Result.failure(
                    IllegalStateException("Địa chỉ dịch vụ ghép nối không hợp lệ."),
                )
            val manager = ZynAdbConnectionManager.get(context)
            manager.pair(address, port, code)

            runCatching {
                val conn = discoverService(nsd, SERVICE_CONNECT, CONNECT_TIMEOUT_MS)
                conn.port.takeIf { it > 0 }?.let { AdbLinkStore.saveConnectPort(context, it) }
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(
                IllegalStateException(
                    "Ghép nối thất bại: ${t.message ?: t.javaClass.simpleName}. " +
                        "Hãy chắc bảng mã ghép nối còn mở và nhập đúng 6 số.",
                    t,
                ),
            )
        } finally {
            runCatching { lock?.release() }
        }
    }

    private fun pickHost(info: NsdServiceInfo): InetAddress? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses?.firstOrNull()?.let { return it }
        }
        @Suppress("DEPRECATION")
        return info.host
    }

    private suspend fun discoverService(
        nsd: NsdManager,
        serviceType: String,
        timeoutMs: Long,
    ): NsdServiceInfo = withContext(Dispatchers.IO) {
        val deferred = CompletableDeferred<NsdServiceInfo>()
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(regType: String) {}
            override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                deferred.completeExceptionally(
                    IllegalStateException("Không dò được dịch vụ mDNS (lỗi $errorCode)."),
                )
            }
            override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                runCatching {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            deferred.completeExceptionally(
                                IllegalStateException("Resolve mDNS thất bại (lỗi $errorCode)."),
                            )
                        }
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            deferred.complete(resolved)
                        }
                    })
                }.onFailure { deferred.completeExceptionally(it) }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        try {
            withTimeout(timeoutMs) { deferred.await() }
        } finally {
            runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        }
    }

    companion object {
        const val SERVICE_PAIRING = "_adb-tls-pairing._tcp."
        const val SERVICE_CONNECT = "_adb-tls-connect._tcp."

        private const val PAIR_TIMEOUT_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
    }
}

@RequiresApi(Build.VERSION_CODES.R)
fun checkStillPaired(context: Context, timeoutMs: Long = 8_000L): Boolean? {
    val manager = ZynAdbConnectionManager.get(context)
    return try {
        val ok = manager.connectTls(context.applicationContext, timeoutMs)
        runCatching { manager.disconnect() }
        if (ok) true else null
    } catch (e: AdbPairingRequiredException) {
        false
    } catch (e: IOException) {
        
        if (e.message == "Connection failed") false else null
    } catch (t: Throwable) {
        null
    }
}

class PendingAdbPairing : AdbPairing {
    override suspend fun pair(code: String): Result<Unit> {
        kotlinx.coroutines.delay(700)
        return Result.failure(
            IllegalStateException("Cần Android 11 trở lên mới ghép nối gỡ lỗi Wi-Fi được."),
        )
    }
}
