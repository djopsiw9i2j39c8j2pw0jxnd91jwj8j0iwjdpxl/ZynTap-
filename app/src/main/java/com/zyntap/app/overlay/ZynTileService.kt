package com.zyntap.app.overlay

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.zyntap.app.MainActivity
import com.zyntap.app.data.DebugLog

class ZynTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { handleClick() } else handleClick()
    }

    private fun handleClick() {
        runCatching { doClick() }.onFailure {
            DebugLog.e("Nút nhanh", "Lỗi khi bấm nút trên bảng cài đặt nhanh", it)
        }
        refresh()
    }

    private fun isOn() = OverlayService.running && OverlayService.wanted(this)

    private fun doClick() {
        val target = OverlayService.lastTarget(this)

        if (isOn()) {
            DebugLog.i("Nút nhanh", "Tắt hẳn menu nổi từ bảng cài đặt nhanh")
            OverlayService.stop(this)
            
            qsTile?.let {
                it.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= 29) it.subtitle = "Đã tắt"
                it.updateTile()
            }
            return
        }

        if (!OverlayPermission.granted(this) || !UsageAccess.granted(this)) {
            DebugLog.w("Nút nhanh", "Thiếu quyền (vẽ đè / truy cập dữ liệu sử dụng) -> mở ZynTap để cấp quyền")
            Toast.makeText(this, "Cần cấp đủ quyền cho ZynTap", Toast.LENGTH_SHORT).show()
            launchAndCollapse(Intent(this, MainActivity::class.java))
            return
        }
        if (target == null) {
            DebugLog.w("Nút nhanh", "Chưa có app nào từng mở từ ZynTap -> mở ZynTap để chọn app")
            Toast.makeText(this, "Chưa có app nào đã mở. Hãy chọn app trong ZynTap trước.", Toast.LENGTH_LONG).show()
            launchAndCollapse(Intent(this, MainActivity::class.java))
            return
        }
        val launch = launchIntentFor(target)
        if (launch == null) {
            DebugLog.w("Nút nhanh", "Không mở được app $target (đã gỡ?) -> mở ZynTap")
            launchAndCollapse(Intent(this, MainActivity::class.java))
            return
        }

        if (isInApp(target)) {

            DebugLog.i("Nút nhanh", "Đang ở sẵn trong $target -> bật lại menu nổi (không mở lại app)")
            launchAndCollapse(launch)
        } else {
            DebugLog.i("Nút nhanh", "Chưa ở trong app đã chọn -> mở lại $target + bật menu nổi")
            launchAndCollapse(launch)
        }
        OverlayService.start(this, target)
    }

    private fun isInApp(pkg: String): Boolean = runCatching {
        val tracker = UsageAccess.Tracker(applicationContext)
        tracker.poll()
        tracker.isTargetResumed(pkg)
    }.onFailure { DebugLog.w("Nút nhanh", "Không đọc được app đang ở trước màn hình", it) }
        .getOrDefault(false)

    private fun launchIntentFor(pkg: String): Intent? =
        packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Suppress("DEPRECATION")
    private fun launchAndCollapse(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pi)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = OverlayService.running && OverlayService.wanted(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "ZynTap"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (on) "Đang bật" else "Đã tắt"
        }
        tile.updateTile()
    }
}
