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
import com.zyntap.app.ui.ZynTapApp
import com.zyntap.app.ui.ZynTapTheme

class MainActivity : ComponentActivity() {

    /**
     * Bước xin quyền: 0 = thông báo, 1 = hiển thị trên ứng dụng khác.
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

    private fun requestNextPermission() {
        while (permissionStep < 2) {
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
        }
    }
}
