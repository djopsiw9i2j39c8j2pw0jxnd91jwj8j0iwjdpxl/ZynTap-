package com.zyntap.app.overlay

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings

/** Quyền "Truy cập dữ liệu sử dụng": cho biết app nào đang ở trước màn hình, không cần ADB. */
object UsageAccess {

    fun granted(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Mở danh sách "Truy cập dữ liệu sử dụng" (bạn chạm vào ZynTap rồi bật công tắc).
     * Không gắn `package:` vì một số máy (Samsung One UI) lại nhảy sang trang "Thông tin ứng dụng"
     * thay vì trang bật quyền.
     */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openSettings(context: Context) {
        runCatching { context.startActivity(settingsIntent(context)) }
    }

    /**
     * Bộ theo dõi app ở trước màn hình: mỗi lần gọi chỉ đọc các sự kiện MỚI từ lần trước (rất nhẹ).
     *
     * "Đã thoát app" chỉ được kết luận khi app đích đã bị STOP (không còn thấy được; PAUSE thôi thì
     * chưa đủ vì popup quyền quay video, hộp thoại Wi-Fi, popup app khác... chỉ làm app PAUSE) VÀ có
     * một app thật khác (launcher, app khác) đang ở trước. Các gói hệ thống chỉ chen ngang
     * (SystemUI, panel Wi-Fi của Cài đặt, bàn phím...) không được tính là "app khác".
     * Tắt màn hình làm app STOP nhưng không có app khác nào lên trước nên cũng không bị tính là thoát.
     */
    class Tracker(private val context: Context) {
        private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        private var since = 0L
        private val resumed = HashSet<String>()   // các gói đang có activity RESUMED
        private val stopped = HashSet<String>()   // các gói mà sự kiện activity gần nhất là STOPPED

        fun poll() {
            val now = System.currentTimeMillis()
            // Lần đầu đọc lùi 1 giờ để biết app đang ở trước từ trước đó; sau đó chỉ đọc phần mới
            val from = if (since == 0L) now - 3_600_000L else since - 1_000L
            val events = usm.queryEvents(from, now) ?: return
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                val pkg = e.packageName ?: continue
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> { resumed.add(pkg); stopped.remove(pkg) }
                    UsageEvents.Event.ACTIVITY_PAUSED -> resumed.remove(pkg)
                    UsageEvents.Event.ACTIVITY_STOPPED -> { resumed.remove(pkg); stopped.add(pkg) }
                }
            }
            since = now
        }

        /** App đích đang ở trước (hoặc chỉ bị popup/hộp thoại che nên chưa tính là đã thoát). */
        fun isTargetResumed(pkg: String) = pkg in resumed

        /** Đã thật sự rời khỏi [pkg]: nó đã STOP và có app thật khác đang ở trước. */
        fun hasLeft(pkg: String): Boolean {
            if (pkg in resumed || pkg !in stopped) return false
            return resumed.any { it != pkg && it != context.packageName && !isTransient(it) }
        }

        private fun isTransient(pkg: String): Boolean =
            pkg == "android" ||
                pkg == "com.android.systemui" ||
                pkg == "com.android.settings.intelligence" ||
                pkg.startsWith("com.android.settings") && pkg.contains("panel") ||
                pkg.contains("inputmethod") || pkg.contains("keyboard") ||
                pkg == "com.android.permissioncontroller" ||
                pkg == "com.google.android.permissioncontroller" ||
                pkg == "com.android.captiveportallogin" ||
                pkg == "com.google.android.networkstack.tethering" ||
                pkg == "com.android.networkstack" ||
                pkg == "com.google.android.gms"
    }
}
