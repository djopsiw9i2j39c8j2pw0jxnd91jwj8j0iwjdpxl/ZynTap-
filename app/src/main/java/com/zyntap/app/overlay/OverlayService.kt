package com.zyntap.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.zyntap.app.R
import kotlin.math.abs

/**
 * Menu nổi kiểu G-Vortex:
 *  - Một nút tab nhỏ bám mép trái màn hình (kéo dọc để đổi vị trí).
 *  - Kéo nút sang phải (hoặc chạm vào nút) -> menu 620x480dp trượt ra từ mép trái.
 *  - Chạm ra ngoài menu -> menu thu lại về nút.
 *  - Góc trên bên trái menu: logo ZynTap (không nền) + tên app.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var handleView: View? = null
    private var panelView: FrameLayout? = null
    private var panelCard: View? = null
    private var panelAnimating = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> closePanel()
            ACTION_STOP -> stopSelf()
            else -> {
                ensureHandle()
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

    // ---- kích thước / helper ----------------------------------------------

    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    /** Menu 620x480dp, tự thu nhỏ nếu màn hình không đủ chỗ. */
    private val cardW get() = minOf(dp(PANEL_W_DP), screenW - dp(PANEL_MARGIN_DP) * 2)
    private val cardH get() = minOf(dp(PANEL_H_DP), screenH - dp(PANEL_MARGIN_DP) * 2)
    private val cardMargin get() = dp(PANEL_MARGIN_DP)

    /** Quãng đường card phải trượt để ra khỏi màn hình hoàn toàn. */
    private val hiddenX get() = -(cardW + cardMargin).toFloat()

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

    // ---- nút mở menu (tab bám mép trái) -----------------------------------

    private fun ensureHandle() {
        if (handleView != null) return
        val w = dp(HANDLE_W_DP)
        val h = dp(HANDLE_H_DP)
        val logo = dp(26)
        val params = overlayParams(
            w, h,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply { x = 0; y = 0 }

        val r = dp(14).toFloat()
        val handle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(0xE60D1524.toInt())
                setStroke(dp(1), 0x551E2B44)
                // bo tròn phía bên phải, sát mép trái màn hình
                cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.logo_transparent)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(logo, logo, Gravity.END or Gravity.CENTER_VERTICAL)
                .apply { marginEnd = dp(8) })
            // Vuốt từ mép trái là cử chỉ Back của hệ thống -> loại vùng nút ra khỏi cử chỉ đó
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                    v.systemGestureExclusionRects = listOf(Rect(0, 0, v.width, v.height))
                }
            }
        }

        val slop = dp(8)
        var downX = 0f
        var downY = 0f
        var startY = 0
        var mode = 0 // 0 chưa rõ, 1 kéo menu ra, 2 kéo đổi vị trí dọc
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startY = params.y; mode = 0
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (mode == 0) {
                        if (abs(dx) > slop || abs(dy) > slop) {
                            mode = if (dx > 0 && abs(dx) >= abs(dy)) 1 else 2
                            if (mode == 1) beginPanelDrag()
                        }
                    }
                    when (mode) {
                        1 -> updatePanelDrag((dx / (cardW + cardMargin)).coerceIn(0f, 1f))
                        2 -> {
                            val limit = screenH / 2 - h
                            params.y = (startY + dy.toInt()).coerceIn(-limit, limit)
                            runCatching { wm.updateViewLayout(handle, params) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    when (mode) {
                        0 -> openPanel()
                        1 -> {
                            val p = ((e.rawX - downX) / (cardW + cardMargin)).coerceIn(0f, 1f)
                            if (p > 0.25f) openPanel() else closePanel()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (mode == 1) closePanel()
                    true
                }
                else -> false
            }
        }

        handleView = handle
        runCatching { wm.addView(handle, params) }
    }

    // ---- menu 620x480 trượt ra từ mép trái --------------------------------

    /** Tạo cửa sổ menu (đang ẩn hoàn toàn bên trái) nếu chưa có. */
    private fun ensurePanel() {
        if (panelView != null) return

        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )

        // Nền mờ toàn màn hình: chạm ra ngoài card -> thu menu lại
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            alpha = 0f
            setOnClickListener { closePanel() }
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(0xF20D1524.toInt())
                setStroke(dp(1), 0xFF1E2B44.toInt())
                cornerRadius = dp(18).toFloat()
            }
            isClickable = true // nuốt chạm bên trong card
            translationX = hiddenX
        }
        scrim.addView(
            card,
            FrameLayout.LayoutParams(cardW, cardH, Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { marginStart = cardMargin },
        )

        val contentText = TextView(this).apply {
            setTextColor(0xFF8D9DBA.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
        }
        card.addView(
            buildSidebar { index -> contentText.text = "Tab ${index + 1} — đang phát triển" },
            LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.MATCH_PARENT),
        )
        card.addView(View(this).apply { setBackgroundColor(0xFF1E2B44.toInt()) },
            LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT))
        card.addView(
            contentText,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
        )

        panelView = scrim
        panelCard = card
        runCatching { wm.addView(scrim, params) }
    }

    /** Góc trên bên trái: logo không nền + tên app. */
    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo_transparent)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)))
        row.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(0xFFE8EEFA.toInt())
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(10) })
        return row
    }

    private val tabNames = listOf("Tab 1", "Tab 2")

    /** Cột bên trái: logo + tên app ở trên cùng, bên dưới là các tab xếp dọc. */
    private fun buildSidebar(onSelect: (Int) -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(12)
            setPadding(p, p, p, p)
        }
        col.addView(buildHeader())
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(14)))

        val tabs = mutableListOf<TextView>()
        fun select(i: Int) {
            tabs.forEachIndexed { idx, t ->
                (t.background as GradientDrawable).setColor(if (idx == i) 0xFF1E3A66.toInt() else 0x00000000)
                t.setTextColor(if (idx == i) 0xFFFFFFFF.toInt() else 0xFF8D9DBA.toInt())
            }
            onSelect(i)
        }
        tabNames.forEachIndexed { i, name ->
            val t = TextView(this).apply {
                text = name
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat() }
                setOnClickListener { select(i) }
            }
            tabs.add(t)
            col.addView(t, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42),
            ).apply { bottomMargin = dp(6) })
        }
        select(0)
        return col
    }

    // ---- điều khiển + animation -------------------------------------------

    private fun beginPanelDrag() {
        panelCard?.animate()?.cancel()
        panelView?.animate()?.cancel()
        panelAnimating = false
        ensurePanel()
    }

    /** progress 0 = thu hết, 1 = mở hết. */
    private fun updatePanelDrag(progress: Float) {
        val card = panelCard ?: return
        card.translationX = hiddenX * (1f - progress)
        panelView?.alpha = progress
        handleView?.alpha = 1f - progress
    }

    private fun openPanel() {
        ensurePanel()
        val card = panelCard ?: return
        val scrim = panelView ?: return
        card.animate().cancel(); scrim.animate().cancel()
        panelAnimating = true
        // post: đợi cửa sổ menu gắn xong rồi mới chạy animation trượt, nếu không sẽ bị "nhảy" thẳng
        scrim.post {
            card.animate().translationX(0f).setDuration(PANEL_ANIM_MS)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .withEndAction { panelAnimating = false }.start()
            scrim.animate().alpha(1f).setDuration(PANEL_ANIM_MS).start()
            handleView?.animate()?.alpha(0f)?.setDuration(PANEL_ANIM_MS)?.start()
        }
    }

    private fun closePanel() {
        val card = panelCard
        val scrim = panelView
        if (card == null || scrim == null) {
            handleView?.alpha = 1f
            return
        }
        card.animate().cancel(); scrim.animate().cancel()
        panelAnimating = true
        card.animate().translationX(hiddenX).setDuration(PANEL_ANIM_MS)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .withEndAction {
                panelAnimating = false
                // Nếu giữa chừng người dùng mở lại thì không gỡ menu
                if (panelCard === card && card.translationX <= hiddenX + 1f) removePanel()
            }.start()
        scrim.animate().alpha(0f).setDuration(PANEL_ANIM_MS).start()
        handleView?.animate()?.alpha(1f)?.setDuration(PANEL_ANIM_MS)?.start()
    }

    private fun removePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
        panelCard = null
    }

    private fun removeViews() {
        handleView?.let { runCatching { wm.removeView(it) } }
        handleView = null
        removePanel()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_SHOW = "com.zyntap.app.overlay.SHOW"
        const val ACTION_HIDE = "com.zyntap.app.overlay.HIDE"
        const val ACTION_STOP = "com.zyntap.app.overlay.STOP"
        private const val NOTIF_ID = 1001
        private const val PANEL_W_DP = 620
        private const val PANEL_H_DP = 480
        private const val PANEL_MARGIN_DP = 8
        private const val HANDLE_W_DP = 48
        private const val HANDLE_H_DP = 64
        private const val PANEL_ANIM_MS = 280L

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
