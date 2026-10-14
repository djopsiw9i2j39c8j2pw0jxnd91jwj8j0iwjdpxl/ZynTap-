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
import com.zyntap.app.data.AppEntry
import com.zyntap.app.data.AppRepository
import com.zyntap.app.data.PairState
import com.zyntap.app.data.PendingAdbPairing
import com.zyntap.app.data.WifiAdbMonitor
import com.zyntap.app.data.WifiAdbPairing
import com.zyntap.app.data.checkStillPaired
import com.zyntap.app.overlay.OverlayPermission
import com.zyntap.app.overlay.OverlayService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { Home, Pair }

class MainViewModel(app: Application) : AndroidViewModel(app) {

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

    /** Quyền vẽ đè lên app khác (menu nổi). */
    var overlayGranted by mutableStateOf(OverlayPermission.granted(app))
        private set

    /** Trạng thái "gỡ lỗi Wi-Fi đang bật" trên máy. */
    var wifiAdbOn by mutableStateOf(WifiAdbMonitor.isWifiAdbEnabled(app))
        private set

    /** Đã ghép nối thành công (trong phiên hoặc từ lần trước). */
    var pairedPersisted by mutableStateOf(AdbLinkStore.isPaired(app))
        private set

    /** Từng ghép nối thành công (khoá đã được máy ghi nhận). */
    val isPaired: Boolean get() = pairState is PairState.Success || pairedPersisted

    /** Đang dùng được: đã ghép nối VÀ gỡ lỗi Wi-Fi đang bật. */
    val isActive: Boolean get() = isPaired && wifiAdbOn

    init {
        viewModelScope.launch {
            pinned = withContext(Dispatchers.Default) {
                repo.loadPinned().mapNotNull { repo.resolve(it) }
            }
        }
        // Theo dõi bật/tắt gỡ lỗi Wi-Fi (tắt Wi-Fi thì hệ thống cũng tự tắt gỡ lỗi Wi-Fi).
        // Chỉ cập nhật trạng thái bật/tắt — "đã ghép nối" chỉ được đặt khi ghép nối thật thành công.
        WifiAdbMonitor.onChanged = { enabled, _ ->
            wifiAdbOn = enabled
            if (enabled) verifyPairing()
        }
        WifiAdbMonitor.start(app)
        // Nếu gỡ lỗi Wi-Fi đang bật sẵn thì dò cổng kết nối ngay
        if (WifiAdbMonitor.isWifiAdbEnabled(app)) {
            viewModelScope.launch {
                val port = WifiAdbMonitor.discoverConnectPort(app)
                if (port != null) {
                    AdbLinkStore.saveConnectPort(app, port)
                }
                verifyPairing()
            }
        }
    }

    private var verifying = false

    /** Hỏi máy xem còn nhớ khoá đã ghép nối không; nếu máy đã quên thì bỏ trạng thái "đã ghép nối". */
    fun verifyPairing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (verifying || !isPaired || pairState is PairState.Working) return
        verifying = true
        val app = getApplication<Application>()
        viewModelScope.launch {
            val still = withContext(Dispatchers.IO) { checkStillPaired(app) }
            if (still == false) {
                AdbLinkStore.setPaired(app, false)
                pairedPersisted = false
                pairState = PairState.Idle
            }
            verifying = false
        }
    }

    /** Đọc lại trạng thái ghép nối (có thể vừa được ghép nối từ thông báo khi app ở nền). */
    fun refreshPairing() {
        pairedPersisted = AdbLinkStore.isPaired(getApplication<Application>())
        wifiAdbOn = WifiAdbMonitor.isWifiAdbEnabled(getApplication<Application>())
    }

    fun refreshOverlayPermission() {
        overlayGranted = OverlayPermission.granted(getApplication<Application>())
    }

    // ---- điều hướng -------------------------------------------------------
    fun openActivate() {
        editMode = false
        screen = Screen.Pair
        // Gửi thông báo nhắc nhập mã; nếu máy chưa cho phép thông báo thì nhắc bằng dòng chữ trong app
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

    // ---- danh sách app ----------------------------------------------------
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
        if (!repo.launch(entry.packageName)) {
            show("Không mở được ${entry.label}")
            return
        }
        // Mở app đã chọn -> vẽ menu nổi (icon + tên app) lên trên app đó
        if (OverlayPermission.granted(ctx)) {
            OverlayService.start(ctx)
        } else {
            show("Cấp quyền hiển thị trên ứng dụng khác để dùng menu nổi")
        }
    }

    fun requestOverlayPermission() {
        OverlayPermission.openSettings(getApplication<Application>())
    }

    // ---- ghép nối ---------------------------------------------------------
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
                delay(900)
                screen = Screen.Home
            } else {
                pairState = PairState.Failed(result.exceptionOrNull()?.message ?: "Ghép nối thất bại")
            }
        }
    }

    // ---- thông báo nhỏ ----------------------------------------------------
    fun show(text: String) {
        message = text
        messageVisible = true
    }

    fun hideMessage() {
        messageVisible = false
    }
}
