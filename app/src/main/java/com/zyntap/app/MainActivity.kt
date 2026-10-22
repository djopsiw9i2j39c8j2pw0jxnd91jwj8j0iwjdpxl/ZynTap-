package com.zyntap.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.zyntap.app.data.DebugLog
import com.zyntap.app.overlay.CrosshairOverlay
import com.zyntap.app.overlay.BatteryExemption
import com.zyntap.app.overlay.OverlayPermission
import com.zyntap.app.overlay.OverlayService
import com.zyntap.app.overlay.UsageAccess
import com.zyntap.app.overlay.ZynTileService
import com.zyntap.app.ui.ZynTapApp
import com.zyntap.app.ui.ZynTapTheme

class MainActivity : ComponentActivity() {

    /**
     * Bước xin quyền: 0 = thông báo, 1 = hiển thị trên ứng dụng khác, 2 = truy cập dữ liệu sử dụng, 3 = bỏ tối ưu pin (không bị hệ thống kill khi chơi game).
     * Mỗi bước chỉ thử đúng 1 lần mỗi lần mở app, nên không bị hỏi lặp khi người dùng từ chối.
     */
    private var permissionStep = 0

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { requestNextPermission() }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { requestNextPermission() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(this)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            ZynTapTheme {
                ZynTapApp()
            }
        }
        // Mở app là xin lần lượt từng quyền cần dùng (không cần bấm nút)
        if (savedInstanceState == null) requestNextPermission()
    }

    override fun onStart() {
        super.onStart()
        // Menu nổi đang bật mà bị mất (bị kill, cập nhật app...) -> bật lại khi mở app
        OverlayService.restoreIfWanted(this)
        // Menu đã tắt mà vẫn còn tâm ảo / vòng kẹt trên màn hình (lần tắt trước bị lỗi giữa chừng) -> gỡ ngay
        if (!OverlayService.wanted(this)) runCatching { CrosshairOverlay.sweepOrphans() }
    }

    private fun requestNextPermission() {
        while (permissionStep < 5) {
            val step = permissionStep++
            if (step == 0 &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
            if (step == 1 && !OverlayPermission.granted(this)) {
                overlayLauncher.launch(OverlayPermission.settingsIntent(this))
                return
            }
            if (step == 2 && !UsageAccess.granted(this)) {
                // Để menu nổi tự đóng khi thoát app đã mở
                overlayLauncher.launch(UsageAccess.settingsIntent(this))
                return
            }
            if (step == 3 && !BatteryExemption.granted(this)) {
                // Không có quyền này, game nặng + chế độ tiết kiệm pin dễ khiến hệ thống kill menu nổi
                for (i in BatteryExemption.requestIntents(this)) {
                    if (runCatching { overlayLauncher.launch(i) }.isSuccess) return
                }
                DebugLog.w("Pin", "Không mở được màn hình bỏ tối ưu pin")
            }
            if (step == 4) offerQuickSettingsTile()
        }
    }

    /** Android 13+: hỏi thêm nút ZynTap vào bảng cài đặt nhanh (chỉ hỏi 1 lần; có thể tự thêm bằng nút bút chì). */
    private fun offerQuickSettingsTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val prefs = getSharedPreferences("zyntap", MODE_PRIVATE)
        if (prefs.getBoolean("tile_asked", false)) return
        prefs.edit().putBoolean("tile_asked", true).apply()
        runCatching {
            val sbm = getSystemService(android.app.StatusBarManager::class.java)
            sbm.requestAddTileService(
                ComponentName(this, ZynTileService::class.java),
                "ZynTap",
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tab_pointer),
                mainExecutor,
            ) { result -> DebugLog.i("Nút nhanh", "Yêu cầu thêm nút vào bảng cài đặt nhanh: kết quả $result") }
        }.onFailure { DebugLog.w("Nút nhanh", "Không hỏi thêm được nút vào bảng cài đặt nhanh", it) }
    }
}
