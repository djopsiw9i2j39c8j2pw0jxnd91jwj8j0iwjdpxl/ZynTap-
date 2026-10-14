package com.zyntap.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.IBinder
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.zyntap.app.R
import com.zyntap.app.data.AppRepository
import kotlin.math.abs

/**
 * Service vẽ menu nổi (icon + tên app đã chọn) lên trên ứng dụng khác.
 * Cách đóng/mở học từ G-Vortex:
 *  - Một "tay cầm" nhỏ bám mép trái màn hình.
 *  - Vuốt tay cầm sang phải (hoặc chạm vào tay cầm) để mở menu.
 *  - Chạm ra ngoài vùng menu để đóng menu (tay cầm vẫn nằm đó để mở lại).
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var handleView: View? = null
    private var panelView: View? = null
    private var repo: AppRepository? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repo = AppRepository(this)
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> showPanel(false)
            ACTION_STOP -> stopSelf()
            else -> {
                ensureHandle()
                showPanel(true)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeViews()
        super.onDestroy()
    }

    // ---- notification -------------------------------------------------------

    private fun startForegroundWithNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "zyntap_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "ZynTap menu nổi", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val openIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("ZynTap đang chạy")
            .setContentText("Menu nổi sẵn sàng trên ứng dụng khác")
            .setContentIntent(openIntent)
            .addAction(0, "Tắt", stopIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---- view nổi ----------------------------------------------------------

    private fun overlayParams(width: Int, height: Int, flags: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(width, height, type, flags, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
    }

    /** Tay cầm nhỏ bám mép trái: vuốt ra / chạm để mở menu. */
    private fun ensureHandle() {
        if (handleView != null) return
        val size = dp(44)
        val params = overlayParams(
            size, size,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply { x = 0; y = 0 }

        val handle = ImageView(this).apply {
            setImageResource(R.drawable.logo_transparent)
            setBackgroundColor(0x33000000)
            alpha = 0.85f
            val p = dp(6)
            setPadding(p, p, p, p)
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(handle, params) }
                    }
                    // Vuốt sang phải đủ xa -> mở menu (kiểu G-Vortex)
                    if (event.rawX - downX > dp(72)) showPanel(true)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // Chạm nhẹ -> mở menu
                        showPanel(true)
                    } else if (params.x < dp(120)) {
                        // Thả gần mép trái thì hút về mép
                        params.x = 0
                        runCatching { wm.updateViewLayout(handle, params) }
                    }
                    true
                }
                else -> false
            }
        }

        handleView = handle
        runCatching { wm.addView(handle, params) }
    }

    /** Menu nổi: danh sách app đã chọn (icon + tên). Chạm ra ngoài để đóng. */
    private fun showPanel(show: Boolean) {
        if (!show) {
            panelView?.let { runCatching { wm.removeView(it) } }
            panelView = null
            return
        }
        if (panelView != null) return

        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        )

        // Nền mờ toàn màn hình: chạm vùng ngoài panel -> đóng menu
        val scrim = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x22000000)
            gravity = Gravity.CENTER_VERTICAL
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE60D1524.toInt())
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            isClickable = true
            isFocusable = true
        }
        val panelWidth = (resources.displayMetrics.widthPixels * 0.36f)
            .toInt()
            .coerceIn(dp(220), dp(320))
        panel.layoutParams = LinearLayout.LayoutParams(
            panelWidth,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            marginStart = dp(8)
        }

        // Tiêu đề
        panel.addView(TextView(this).apply {
            text = "ZynTap"
            setTextColor(0xFFE8EEFA.toInt())
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        })
        panel.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1),
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(10)
            }
            setBackgroundColor(0xFF1E2B44.toInt())
        })

        // Danh sách app đã ghim: icon + tên
        val pinned = repo?.loadPinned().orEmpty()
        if (pinned.isEmpty()) {
            panel.addView(TextView(this).apply {
                text = "Chưa có app nào được chọn"
                setTextColor(0xFF8D9DBA.toInt())
                textSize = 14f
            })
        } else {
            pinned.forEach { pkg ->
                panel.addView(appRow(pkg))
            }
        }

        scrim.addView(panel)
        // Chạm vùng tối (ngoài panel) -> đóng menu
        scrim.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_OUTSIDE) {
                val rect = Rect()
                panel.getGlobalVisibleRect(rect)
                if (!rect.contains(event.x.toInt(), event.y.toInt())) {
                    showPanel(false)
                    return@setOnTouchListener true
                }
            }
            false
        }

        panelView = scrim
        runCatching { wm.addView(scrim, params) }
    }

    private fun appRow(pkg: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pv = dp(10)
            setPadding(0, pv, 0, pv)
        }
        val icon: Drawable? = try {
            packageManager.getApplicationIcon(pkg)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        row.addView(ImageView(this).apply {
            setImageDrawable(icon)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        })
        row.addView(TextView(this).apply {
            text = repo?.resolve(pkg)?.label ?: pkg
            setTextColor(0xFFE8EEFA.toInt())
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            ).apply { marginStart = dp(12) }
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.setOnClickListener {
            val launch = packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (launch != null) {
                runCatching { startActivity(launch) }
                showPanel(false)
            }
        }
        return row
    }

    private fun removeViews() {
        handleView?.let { runCatching { wm.removeView(it) } }
        handleView = null
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_SHOW = "com.zyntap.app.overlay.SHOW"
        const val ACTION_HIDE = "com.zyntap.app.overlay.HIDE"
        const val ACTION_STOP = "com.zyntap.app.overlay.STOP"
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_SHOW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_STOP))
        }
    }
}
