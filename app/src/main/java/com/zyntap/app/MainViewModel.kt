package com.zyntap.app

import android.app.Application
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zyntap.app.data.AdbLinkStore
import com.zyntap.app.data.AdbPairing
import com.zyntap.app.data.AdbSession
import com.zyntap.app.data.AppEntry
import com.zyntap.app.data.AppRepository
import com.zyntap.app.data.PairState
import com.zyntap.app.data.PendingAdbPairing
import com.zyntap.app.data.ConnectionWatcher
import com.zyntap.app.data.WifiAdbMonitor
import com.zyntap.app.data.WifiAdbPairing
import com.zyntap.app.data.WifiState
import com.zyntap.app.overlay.OverlayPermission
import com.zyntap.app.overlay.OverlayService
import com.zyntap.app.overlay.UsageAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { Home, Pair }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    var settingsOpen by mutableStateOf(false)
    var debugEnabled by mutableStateOf(com.zyntap.app.data.DebugLog.run { init(app); enabled })
        private set
    fun setDebugLogging(value: Boolean) {
        com.zyntap.app.data.DebugLog.setEnabled(value)
        debugEnabled = value
    }
    private val repo = AppRepository(app)
    private val pairing: AdbPairing =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) WifiAdbPairing(app) else PendingAdbPairing()

    var screen by mutableStateOf(Screen.Home)
        private set
    var editMode by mutableStateOf(false)
        private set
    var pinned by mutableStateOf<List<AppEntry>>(emptyList())
        private set

    var pickerOpen by mutableStateOf(false)
        private set
    var allApps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var loadingApps by mutableStateOf(false)
        private set

    var code by mutableStateOf("")
        private set
    var pairState by mutableStateOf<PairState>(PairState.Idle)
        private set

    var message by mutableStateOf("")
        private set
    var messageVisible by mutableStateOf(false)
        private set

    var overlayGranted by mutableStateOf(OverlayPermission.granted(app))
        private set

    var wifiAdbOn by mutableStateOf(WifiAdbMonitor.isWifiAdbEnabled(app))
        private set

    var wifiConnected by mutableStateOf(WifiState.isConnected(app))
        private set

    var mobileData by mutableStateOf(WifiState.hasMobile(app))
        private set

    var pairedPersisted by mutableStateOf(AdbLinkStore.isPaired(app))
        private set

    val isPaired: Boolean get() = pairState is PairState.Success || pairedPersisted

    val isActive: Boolean get() = isPaired && wifiAdbOn

    private val wifiListener: (Boolean) -> Unit = { connected ->
        val a = getApplication<Application>()
        wifiConnected = connected
        mobileData = WifiState.hasMobile(a)
        wifiAdbOn = WifiAdbMonitor.isWifiAdbEnabled(a)
    }

    private val adbListener: (Boolean, Int?) -> Unit = { enabled, _ ->
        viewModelScope.launch {
            wifiAdbOn = enabled
            if (enabled) verifyPairing()
        }
    }

    init {
        viewModelScope.launch {
            pinned = withContext(Dispatchers.Default) {
                repo.loadPinned().mapNotNull { repo.resolve(it) }
            }
        }

        WifiAdbMonitor.addListener(adbListener)
        WifiAdbMonitor.start(app)
        
        WifiState.addListener(wifiListener)
        WifiState.start(app)
        ConnectionWatcher.start(app)
        
        if (WifiAdbMonitor.isWifiAdbEnabled(app)) verifyPairing()

    }

    override fun onCleared() {
        WifiState.removeListener(wifiListener)
        WifiAdbMonitor.removeListener(adbListener)
        super.onCleared()
    }

    private var verifying = false

    fun verifyPairing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (verifying || !isPaired || pairState is PairState.Working) return
        verifying = true
        val app = getApplication<Application>()
        viewModelScope.launch {

            val link = AdbSession.connect(app, attempts = 3)
            if (link is AdbSession.Link.PairingLost) {
                AdbLinkStore.setPaired(app, false)
                pairedPersisted = false
                pairState = PairState.Idle
            }
            verifying = false
        }
    }

    fun refreshPairing() {
        pairedPersisted = AdbLinkStore.isPaired(getApplication<Application>())
        wifiAdbOn = WifiAdbMonitor.isWifiAdbEnabled(getApplication<Application>())
        wifiConnected = WifiState.isConnected(getApplication<Application>())
        mobileData = WifiState.hasMobile(getApplication<Application>())
    }

    fun refreshOverlayPermission() {
        overlayGranted = OverlayPermission.granted(getApplication<Application>())
    }

    fun openActivate() {
        editMode = false
        screen = Screen.Pair
        
        if (!isActive && !PairNotifier.showEnterCode(getApplication())) {
            show("Nhập mã 6 số đang hiện trong mục Ghép nối bằng mã")
        }
    }

    fun back() {
        when {
            pickerOpen -> pickerOpen = false
            screen == Screen.Pair -> screen = Screen.Home
            editMode -> editMode = false
        }
    }

    fun toggleEditMode() {
        editMode = !editMode
    }

    fun openPicker() {
        pickerOpen = true
        if (allApps.isEmpty()) {
            viewModelScope.launch {
                loadingApps = true
                allApps = withContext(Dispatchers.Default) { repo.launchable() }
                loadingApps = false
            }
        }
    }

    fun closePicker() {
        pickerOpen = false
    }

    fun isPinned(packageName: String): Boolean = pinned.any { it.packageName == packageName }

    fun toggle(entry: AppEntry) {
        if (isPinned(entry.packageName)) remove(entry.packageName) else add(entry)
    }

    private fun add(entry: AppEntry) {
        pinned = pinned + entry
        repo.savePinned(pinned.map { it.packageName })
    }

    fun remove(packageName: String) {
        pinned = pinned.filterNot { it.packageName == packageName }
        repo.savePinned(pinned.map { it.packageName })
    }

    fun launchApp(entry: AppEntry) {
        val ctx = getApplication<Application>()
        
        if (!OverlayPermission.granted(ctx)) {
            show("Cần cấp quyền hiển thị trên ứng dụng khác để mở ${entry.label}")
            OverlayPermission.openSettings(ctx)
            return
        }
        if (!UsageAccess.granted(ctx)) {
            show("Cần bật Truy cập dữ liệu sử dụng cho ZynTap để mở ${entry.label}")
            UsageAccess.openSettings(ctx)
            return
        }
        if (!repo.launch(entry.packageName)) {
            show("Không mở được ${entry.label}")
            return
        }
        
        OverlayService.start(ctx, entry.packageName)
    }

    fun requestOverlayPermission() {
        OverlayPermission.openSettings(getApplication<Application>())
    }

    fun onCode(value: String) {
        code = value.filter { it.isDigit() }.take(6)
        if (pairState is PairState.Failed) pairState = PairState.Idle
    }

    fun submitPair() {
        if (code.length != 6 || pairState is PairState.Working || pairState is PairState.Success) return
        pairState = PairState.Working
        viewModelScope.launch {
            val result = pairing.pair(code)
            if (result.isSuccess) {
                pairState = PairState.Success
                AdbLinkStore.setPaired(getApplication<Application>(), true)
                PairNotifier.cancel(getApplication())
                pairedPersisted = true
                verifyPairing() 
                delay(900)
                screen = Screen.Home
            } else {
                pairState = PairState.Failed(result.exceptionOrNull()?.message ?: "Ghép nối thất bại")
            }
        }
    }

    fun show(text: String) {
        message = text
        messageVisible = true
    }

    fun hideMessage() {
        messageVisible = false
    }
}
