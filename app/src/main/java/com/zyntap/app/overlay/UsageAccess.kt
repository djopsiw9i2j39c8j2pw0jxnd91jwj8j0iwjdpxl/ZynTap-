package com.zyntap.app.overlay

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Bộ theo dõi app ở trước màn hình: mỗi lần gọi chỉ đọc các sự kiện MỚI từ lần trước (rất nhẹ). */
    class Tracker(private val context: Context) {
        private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        private var since = 0L
        /** Package đang ở trước màn hình (null = chưa rõ / đang chuyển). */
        var foreground: String? = null
            private set

        fun poll() {
            val now = System.currentTimeMillis()
            // Lần đầu đọc lùi 1 giờ để biết app đang ở trước từ trước đó; sau đó chỉ đọc phần mới
            val from = if (since == 0L) now - 3_600_000L else since - 1_000L
            val events = usm.queryEvents(from, now) ?: return
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                when (e.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> foreground = e.packageName
                    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED ->
                        if (e.packageName == foreground) foreground = null
                }
            }
            since = now
        }
    }
}
