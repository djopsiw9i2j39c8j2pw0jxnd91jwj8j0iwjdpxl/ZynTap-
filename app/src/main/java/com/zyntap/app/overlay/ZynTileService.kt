package com.zyntap.app.overlay

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.zyntap.app.MainActivity
import com.zyntap.app.data.DebugLog

/**
 * Nút trên bảng cài đặt nhanh (Quick Settings) của máy:
 *  - Menu nổi CHƯA chạy (chưa ở trong app đã chọn)  -> mở lại app đã mở gần nhất + bật menu nổi.
 *  - Menu nổi ĐANG chạy (đang ở trong app đã chọn)  -> chỉ bật/tắt bảng menu, không mở thêm gì.
 * Thiếu quyền thì mở ZynTap để cấp quyền.
 */
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

    private fun doClick() {
        if (!OverlayPermission.granted(this) || !UsageAccess.granted(this)) {
            DebugLog.w("Nút nhanh", "Thiếu quyền (vẽ đè / truy cập dữ liệu sử dụng) -> mở ZynTap để cấp quyền")
            Toast.makeText(this, "Cần cấp đủ quyền cho ZynTap", Toast.LENGTH_SHORT).show()
            launchAndCollapse(Intent(this, MainActivity::class.java))
            return
        }
        val target = OverlayService.lastTarget(this)

        if (OverlayService.running && OverlayService.wanted(this)) {
            // Đang trong app đã chọn: chỉ bật/tắt bảng menu
            DebugLog.i("Nút nhanh", "Bật/tắt bảng menu (đang trong app ${target ?: "?"})")
            OverlayService.togglePanel(this)
            // Đưa app về trước (nó vốn đang ở trước) chỉ để bảng cài đặt nhanh tự đóng lại
            target?.let { launchIntentFor(it) }?.let { launchAndCollapse(it) }
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
        DebugLog.i("Nút nhanh", "Chưa ở trong app nào -> mở lại app gần nhất $target + bật menu nổi")
        launchAndCollapse(launch)
        OverlayService.start(this, target)
    }

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
            tile.subtitle = if (on) "Bật/tắt menu" else {
                val pkg = OverlayService.lastTarget(this)
                val name = pkg?.let { runCatching {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString()
                }.getOrNull() }
                name ?: "Mở app gần nhất"
            }
        }
        tile.updateTile()
    }
}
