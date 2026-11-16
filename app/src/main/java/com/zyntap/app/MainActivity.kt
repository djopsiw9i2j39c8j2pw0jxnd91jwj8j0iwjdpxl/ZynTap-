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
import com.zyntap.app.data.DebugLog
import com.zyntap.app.overlay.CrosshairOverlay
import com.zyntap.app.overlay.BatteryExemption
import com.zyntap.app.overlay.OverlayPermission
import com.zyntap.app.overlay.OverlayService
import com.zyntap.app.ui.ZynTapApp
import com.zyntap.app.ui.ZynTapTheme

class MainActivity : ComponentActivity() {

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
        
        if (savedInstanceState == null) requestNextPermission()
    }

    override fun onStart() {
        super.onStart()
        
        OverlayService.restoreIfWanted(this)
        
        if (!OverlayService.wanted(this)) runCatching { CrosshairOverlay.sweepOrphans() }
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
            if (step == 2 && !BatteryExemption.granted(this)) {
                
                for (i in BatteryExemption.requestIntents(this)) {
                    if (runCatching { overlayLauncher.launch(i) }.isSuccess) return
                }
                DebugLog.w("Pin", "Không mở được màn hình bỏ tối ưu pin")
            }
        }
    }
}
