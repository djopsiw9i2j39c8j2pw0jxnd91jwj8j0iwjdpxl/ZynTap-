package com.zyntap.app

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat

/**
 * Thông báo nhắc nhập mã ghép nối. Có nút "Nhập mã" ngay trên thông báo:
 * bấm vào là gõ mã 6 số luôn, không cần mở app.
 */
object PairNotifier {
    private const val CHANNEL_ID = "zyntap_pair"
    private const val NOTIF_ID = 4201
    const val KEY_CODE = "pair_code"

    /** Trả về true nếu thông báo đã hiện được (đã được cấp quyền thông báo và được bật). */
    fun showEnterCode(context: Context): Boolean = post(
        context,
        title = "Nhập mã ghép nối",
        text = "Mở mã 6 số trong mục Ghép nối bằng mã, rồi bấm Nhập mã và gõ mã vào đây",
        withInput = true,
    )

    fun showBusy(context: Context) {
        post(context, "Đang ghép nối...", "Chờ một chút nhé", withInput = false)
    }

    fun showResult(context: Context, ok: Boolean, detail: String) {
        if (ok) {
            post(context, "Đã ghép nối thành công", "Bạn có thể đóng mục ghép nối trên máy", withInput = false)
        } else {
            post(context, "Ghép nối thất bại", "$detail. Bấm Nhập mã để thử lại", withInput = true)
        }
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun post(context: Context, title: String, text: String, withInput: Boolean): Boolean {
        if (!canNotify(context)) return false
        val nm = NotificationManagerCompat.from(context)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Nhắc nhập mã ghép nối")
                .build(),
        )

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!withInput)
            .setContentIntent(open)

        if (withInput) {
            // Ô gõ trực tiếp trên thông báo (inline reply); cần FLAG_MUTABLE để điền được mã
            val replyIntent = PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, PairReplyReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val remoteInput = RemoteInput.Builder(KEY_CODE).setLabel("Nhập 6 số").build()
            builder.addAction(
                NotificationCompat.Action.Builder(android.R.drawable.ic_menu_edit, "Nhập mã", replyIntent)
                    .addRemoteInput(remoteInput)
                    .build(),
            )
        }

        return runCatching { nm.notify(NOTIF_ID, builder.build()) }.isSuccess
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID)
    }
}
