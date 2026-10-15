package com.zyntap.app

import android.Manifest
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
import com.zyntap.app.overlay.OverlayPermission
import com.zyntap.app.overlay.OverlayService
import com.zyntap.app.overlay.UsageAccess
import com.zyntap.app.ui.ZynTapApp
import com.zyntap.app.ui.ZynTapTheme

class MainActivity : ComponentActivity() {

    /**
     * Bước xin quyền: 0 = thông báo, 1 = hiển thị trên ứng dụng khác, 2 = truy cập dữ liệu sử dụng.
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
    }

    private fun requestNextPermission() {
        while (permissionStep < 3) {
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
        }
    }
}
