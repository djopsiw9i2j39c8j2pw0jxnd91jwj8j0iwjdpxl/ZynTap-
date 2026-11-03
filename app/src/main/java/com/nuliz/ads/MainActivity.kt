package com.nuliz.ads

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.nuliz.ads.ui.NulizRoot
import com.nuliz.ads.ui.NulizTheme

class MainActivity : ComponentActivity() {

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) AdBlockVpnService.start(this)
    }

    // Thông báo là tuỳ chọn: dù từ chối, VPN vẫn chạy bình thường.
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestVpn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent { NulizTheme { NulizRoot(onToggle = ::toggle) } }
    }

    private fun toggle() {
        if (Stats.state.value.running) AdBlockVpnService.stop(this) else enable()
    }

    private fun enable() {
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestVpn()
        }
    }

    private fun requestVpn() {
        val consent = VpnService.prepare(this)
        if (consent == null) AdBlockVpnService.start(this) else vpnPermission.launch(consent)
    }
}
