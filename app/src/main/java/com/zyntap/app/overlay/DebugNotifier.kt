package com.zyntap.app.overlay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zyntap.app.R

/**
 * Thông báo riêng của app để xem chẩn đoán / lỗi ĐẦY ĐỦ (Toast bị cắt chữ và biến mất rất nhanh).
 * Kéo thanh thông báo xuống rồi chạm vào thông báo để mở rộng, đọc hết nội dung.
 * Mỗi [id] là một thông báo riêng, đăng lại cùng id thì cập nhật tại chỗ (không dồn đống).
 * Chưa cấp quyền thông báo thì lùi về Toast.
 */
object DebugNotifier {
    private const val CHANNEL_ID = "zyntap_debug"
    const val ID_MESSAGE = 7001
    const val ID_DIAG = 7002

    private val main = Handler(Looper.getMainLooper())

    fun post(context: Context, title: String, text: String, id: Int) {
        val app = context.applicationContext
        val allowed = (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(app).areNotificationsEnabled()
        val ok = allowed && runCatching {
            val nm = NotificationManagerCompat.from(app)
            nm.createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("ZynTap chẩn đoán")
                    .build(),
            )
            val n = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text.lineSequence().firstOrNull().orEmpty())
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setAutoCancel(true)
                .build()
            nm.notify(id, n)
        }.isSuccess
        if (!ok) main.post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
    }
}
