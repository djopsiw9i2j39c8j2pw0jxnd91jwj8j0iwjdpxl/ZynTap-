package com.nuliz.ads

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.NumberFormat

object Notifier {
    const val ID_SERVICE = 1
    private const val ID_BLOCK = 2
    private const val CH_SERVICE = "protection"
    private const val CH_BLOCK = "blocked"
    private const val MIN_INTERVAL_MS = 700L // tránh spam khi một trang bắn hàng chục quảng cáo cùng lúc
    private const val AUTO_DISMISS_MS = 2_500L

    @Volatile
    private var lastPost = 0L

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Trạng thái bảo vệ", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_BLOCK, "Quảng cáo bị chặn", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
    }

    /** Thông báo cố định của foreground service, kèm bộ đếm tổng. */
    fun service(context: Context, total: Long): Notification {
        val stop = PendingIntent.getService(
            context, 0,
            Intent(context, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Nuliz Ads đang bảo vệ bạn")
            .setContentText("Đã chặn ${NumberFormat.getIntegerInstance().format(total)} quảng cáo & mối đe doạ")
            .setContentIntent(openApp(context))
            .addAction(0, "Tắt", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    /** Mỗi lần chặn: cập nhật bộ đếm và (nếu bật) bật thông báo nhanh kèm tên miền vừa bị chặn. */
    @SuppressLint("MissingPermission")
    fun blocked(context: Context, event: BlockEvent, total: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPost < MIN_INTERVAL_MS) return
        lastPost = now

        val nm = NotificationManagerCompat.from(context)
        nm.notify(ID_SERVICE, service(context, total))
        if (!Prefs.notifyEachBlock) return
        nm.notify(
            ID_BLOCK,
            NotificationCompat.Builder(context, CH_BLOCK)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Đã chặn ${event.category.label.lowercase()}")
                .setContentText(event.domain)
                .setContentIntent(openApp(context))
                .setTimeoutAfter(AUTO_DISMISS_MS)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
        )
    }

    fun clearBlocked(context: Context) = NotificationManagerCompat.from(context).cancel(ID_BLOCK)

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
