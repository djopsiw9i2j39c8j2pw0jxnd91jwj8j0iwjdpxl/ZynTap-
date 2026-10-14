package com.zyntap.app.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.DisplayCutout
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zyntap.app.R
import kotlin.math.abs

/**
 * Menu nổi kiểu G-Vortex:
 *  - Một nút tab nhỏ bám mép trái màn hình (kéo dọc để đổi vị trí).
 *  - Kéo nút sang phải (hoặc chạm vào nút) -> menu 620x480dp trượt ra từ mép trái.
 *  - Chạm ra ngoài menu -> menu thu lại về nút (có animation); kéo menu sang trái cũng thu vào.
 *  - Góc trên bên trái menu: logo ZynTap (không nền) + tên app.
 *  - Cột tab bên trái: "Tạo nút chính" (tạo/chỉnh các nút main tròn nổi), "Chỉnh điểm nhấn", "Cài cấu hình".
 *  - Góc trên bên phải menu: 3 nút  mắt (bật/tắt độ trong suốt có sẵn của menu) / dấu trừ (thu menu) /
 *    dấu X (hiện hộp xác nhận -> "Có" thì menu thu thành viên tròn logo rồi bay lên, tắt hẳn menu nổi).
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var handleView: View? = null
    private var panelView: FrameLayout? = null
    private var panelCard: View? = null
    private var panelContent: View? = null
    private var panelControls: View? = null
    private var cardBg: GradientDrawable? = null
    private var confirmLayer: View? = null
    private var confirmDialog: View? = null
    private var eyeIcon: ImageView? = null
    private var solid = false          // đang ở chế độ giao diện trong suốt
    private var solidAmount = 0f       // 0 = đặc, 1 = trong suốt hẳn (để chuyển mượt)
    private var solidAnim: ValueAnimator? = null
    private var closing = false        // đang chạy hiệu ứng đóng menu
    private var closeAnim: ValueAnimator? = null
    private var panelAnimating = false
    private var handleDragging = false
    private var handleAddedAt = 0L
    private var panelAddedAt = 0L

    // ---- nút main tròn + tab "Tạo nút chính" ----
    private lateinit var repo: MainButtonRepo
    private lateinit var circles: MainButtonOverlay
    private var panelParams: WindowManager.LayoutParams? = null
    private var dropdownLayer: DropdownLayer? = null
    private var promptLayer: TextPromptLayer? = null
    private var mainTab: MainButtonsTab? = null
    private var editLock = false     // đang chỉnh nút: không cho đóng menu nhầm
    private var editDim = 0f         // 0..1: nền tối phía sau nhạt đi khi đang chỉnh nút
    private var editDimAnim: ValueAnimator? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repo = MainButtonRepo(this)
        circles = MainButtonOverlay(
            ctx = this,
            wm = wm,
            repo = repo,
            screenBounds = { realBounds },
            newParams = { w, h, f -> overlayParams(w, h, f) },
        )
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> closePanel()
            ACTION_STOP -> {
                // Người dùng chủ động tắt -> không tự khôi phục nữa
                setWanted(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // intent == null: hệ thống tự dựng lại service sau khi bị kill
                if (intent == null && !wanted(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureHandle()
                circles.ensureAll()
                startWatchdog()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        closeAnim?.let { it.removeAllListeners(); it.cancel() }
        closeAnim = null
        solidAnim?.cancel()
        editDimAnim?.cancel()
        removeViews()
        super.onDestroy()
    }

    // ---- tự khôi phục khi menu nổi bị mất ----------------------------------

    private val watchdog = object : Runnable {
        override fun run() {
            runCatching { checkHealth() }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun startWatchdog() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    /** Kiểm tra định kỳ: nút/menu bị hệ thống gỡ mất, hoặc nút kẹt trạng thái ẩn -> dựng lại. */
    private fun checkHealth() {
        val h = handleView
        if (h == null) {
            ensureHandle()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - handleAddedAt > ATTACH_GRACE_MS && !h.isAttachedToWindow) {
            runCatching { wm.removeViewImmediate(h) }
            handleView = null
            handleParams = null
            ensureHandle()
            return
        }
        val panel = panelView
        if (panel != null && now - panelAddedAt > ATTACH_GRACE_MS && !panel.isAttachedToWindow) {
            removePanel()
        }
        if (panelView == null && !panelAnimating && !handleDragging) {
            if (h.alpha != 1f) h.alpha = 1f
            if (h.visibility != View.VISIBLE) h.visibility = View.VISIBLE
        }
        circles.ensureAll()
    }

    // ---- lưu / đọc vị trí nút ----------------------------------------------

    private val prefs get() = getSharedPreferences(PREFS, MODE_PRIVATE)

    /** Lưu vị trí dọc dạng tỉ lệ 0..1 để đúng cả khi xoay màn hình. */
    private fun saveHandlePosition() {
        val p = handleParams ?: return
        val range = (screenH - handleH).coerceAtLeast(1)
        prefs.edit().putFloat(KEY_Y_RATIO, (p.y.toFloat() / range).coerceIn(0f, 1f)).apply()
    }

    private fun savedHandleY(): Int {
        val ratio = prefs.getFloat(KEY_Y_RATIO, -1f)
        val range = (screenH - handleH).coerceAtLeast(0)
        return if (ratio in 0f..1f) (ratio * range).toInt() else range / 2
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
        runCatching {
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
    }

    // ---- kích thước / helper ----------------------------------------------

    /** Kích thước màn hình thật (gồm cả vùng thanh trạng thái / thanh điều hướng). */
    private val realBounds: Rect
        get() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { return wm.maximumWindowMetrics.bounds }
            }
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            return Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
    private val screenW get() = realBounds.width()
    private val screenH get() = realBounds.height()

    /** Thông tin tai thỏ / giọt nước / đục lỗ (null nếu máy không có). */
    private fun displayCutoutCompat(): DisplayCutout? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { wm.currentWindowMetrics.windowInsets.displayCutout?.let { return it } }
        }
        return handleView?.rootWindowInsets?.displayCutout
    }

    private val safeLeft: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) displayCutoutCompat()?.safeInsetLeft ?: 0 else 0

    /** Lề trái của card: không để card chui xuống dưới tai thỏ. */
    private val cardStart get() = maxOf(dp(PANEL_MARGIN_DP), safeLeft)
    private val travel get() = cardW + cardStart

    /** Menu 620x480dp, tự thu nhỏ nếu màn hình không đủ chỗ. */
    private val cardW get() = minOf(dp(PANEL_W_DP), screenW - cardStart - dp(PANEL_MARGIN_DP))
    private val cardH get() = minOf(dp(PANEL_H_DP), screenH - dp(PANEL_MARGIN_DP) * 2)
    private val cardMargin get() = dp(PANEL_MARGIN_DP)

    /** Quãng đường card phải trượt để ra khỏi màn hình hoàn toàn. */
    private val hiddenX get() = -travel.toFloat()

    private fun overlayParams(width: Int, height: Int, flags: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(width, height, type, flags, PixelFormat.TRANSLUCENT)
            .apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                // Cho cửa sổ lấn vào vùng tai thỏ/giọt nước -> nút sát hẳn mép màn hình
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
    }

    // ---- vị trí dọc của nút: sát mép trên/dưới, né tai thỏ -------------------

    private var handleParams: WindowManager.LayoutParams? = null
    private var handleW = 0
    private var handleH = 0

    /** y (tính từ mép trên màn hình) hợp lệ gần nhất với [desired]: trong màn hình và không đè lên cutout. */
    private fun placeHandleY(desired: Int): Int {
        val maxY = (screenH - handleH).coerceAtLeast(0)
        var y = desired.coerceIn(0, maxY)
        val rects = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            displayCutoutCompat()?.boundingRects.orEmpty() else emptyList()
        val pad = dp(6)
        repeat(2) {
            for (r in rects) {
                if (r.left >= handleW + pad || r.right <= 0) continue          // không nằm sát mép trái
                if (y + handleH <= r.top - pad || y >= r.bottom + pad) continue // không chồng nhau
                val candidates = listOfNotNull(
                    (r.top - pad - handleH).takeIf { it >= 0 },
                    (r.bottom + pad).takeIf { it <= maxY },
                )
                if (candidates.isNotEmpty()) y = candidates.minByOrNull { abs(it - y) }!!
            }
        }
        return y
    }

    private fun relayoutHandle() {
        val p = handleParams ?: return
        val v = handleView ?: return
        if (!handleDragging) p.y = placeHandleY(savedHandleY())
        runCatching { wm.updateViewLayout(v, p) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Xoay màn hình: bỏ menu đang mở, đặt lại nút (cutout đổi cạnh nên chờ hệ thống cập nhật)
        removePanel()
        handleView?.alpha = 1f
        relayoutHandle()
        circles.relayoutAll()
        handleView?.postDelayed({ relayoutHandle(); circles.relayoutAll() }, 400)
    }

    // ---- nút mở menu (tab bám mép trái) -----------------------------------

    private fun ensureHandle() {
        if (handleView != null) return
        if (!OverlayPermission.granted(this)) return
        // Vùng chạm luôn đủ to (>= 36x72dp) dù nút nhìn thấy nhỏ thế nào -> không bị mất tương tác
        val visW = dp(HANDLE_W_DP)
        val visH = dp(HANDLE_H_DP)
        val touchW = maxOf(visW, dp(MIN_TOUCH_W_DP))
        val touchH = maxOf(visH, dp(MIN_TOUCH_H_DP))
        handleW = touchW
        handleH = touchH
        val params = overlayParams(
            touchW, touchH,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = placeHandleY(savedHandleY())
        }
        handleParams = params

        val r = dp(14).toFloat()
        val iconSize = minOf(dp(18), (visW * 0.9f).toInt()).coerceAtLeast(dp(10))
        val handle = FrameLayout(this).apply {
            // Phần nhìn thấy: viên thuốc bám mép trái, nằm giữa vùng chạm
            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply {
                    setColor(0xE60D1524.toInt())
                    setStroke(dp(1), 0x551E2B44)
                    cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                }
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_chevrons_right)
                    setColorFilter(0xFFE8EEFA.toInt())
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
            }, FrameLayout.LayoutParams(visW, visH, Gravity.START or Gravity.CENTER_VERTICAL))
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
                    handleDragging = true
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
                        1 -> updatePanelDrag((dx / travel).coerceIn(0f, 1f))
                        2 -> {
                            params.y = placeHandleY(startY + dy.toInt())
                            runCatching { wm.updateViewLayout(handle, params) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handleDragging = false
                    when (mode) {
                        2 -> saveHandlePosition() // nhớ vị trí nút cho lần sau
                        0 -> openPanel() // chạm nhẹ -> menu trượt ra có animation
                        1 -> {
                            val p = ((e.rawX - downX) / travel).coerceIn(0f, 1f)
                            if (p > 0.25f) openPanel() else closePanel()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handleDragging = false
                    if (mode == 1) closePanel()
                    if (mode == 2) saveHandlePosition()
                    true
                }
                else -> false
            }
        }

        handleView = handle
        handleAddedAt = SystemClock.uptimeMillis()
        runCatching { wm.addView(handle, params) }.onFailure {
            // Gắn thất bại -> bỏ ghi nhận để watchdog thử dựng lại
            handleView = null
            handleParams = null
            return
        }
        // Sau khi gắn vào cửa sổ mới có thông tin cutout -> đặt lại vị trí cho chắc
        handle.post { relayoutHandle() }
    }

    // ---- menu 620x480 trượt ra từ mép trái --------------------------------

    /** Tạo cửa sổ menu (đang ẩn hoàn toàn bên trái) nếu chưa có. */
    private fun ensurePanel() {
        if (panelView != null) return
        solid = prefs.getBoolean(KEY_SOLID, false)

        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply {
            // Bàn phím (khi đổi tên nút) hiện lên đè phía dưới, không đẩy/ép menu
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        panelParams = params

        // Nền mờ toàn màn hình: chạm ra ngoài card -> thu menu lại
        val scrim = PanelRoot(this).apply {
            setBackgroundColor(0x66000000)
            alpha = 0f
        }
        // Chạm ra ngoài -> thu menu (có animation); kéo sang trái từ vùng ngoài cũng thu menu theo tay
        val slop = dp(8)
        var sDownX = 0f
        var sDragging = false
        scrim.setOnTouchListener { _, e ->
            // Đang chỉnh nút: bỏ qua chạm ngoài menu để không đóng nhầm
            if (closing || editLock) return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sDownX = e.rawX; sDragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sDownX
                    if (!sDragging && dx < -slop) { sDragging = true; beginPanelDrag() }
                    if (sDragging) updatePanelDrag((1f + dx / travel).coerceIn(0f, 1f))
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (sDragging) finishPanelDrag((1f + (e.rawX - sDownX) / travel).coerceIn(0f, 1f))
                    else closePanel()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { if (sDragging) openPanel(); true }
                else -> false
            }
        }

        val bg = GradientDrawable().apply {
            setColor(0xF20D1524.toInt())
            setStroke(dp(1), 0xFF1E2B44.toInt())
            cornerRadius = dp(18).toFloat()
        }
        // card = khung bo góc (nền + nút điều khiển + hộp xác nhận)
        // content = phần nội dung bên trong, nơi kéo sang trái để thu menu
        val card = FrameLayout(this).apply {
            background = bg
            isClickable = true // nuốt chạm bên trong card
            translationX = hiddenX
        }
        val content = DragCard(this).apply { orientation = LinearLayout.HORIZONTAL }
        card.addView(
            content,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        scrim.addView(
            card,
            FrameLayout.LayoutParams(cardW, cardH, Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { marginStart = cardStart },
        )

        // Hai lớp nổi dùng chung cho mọi tab: dropdown + hộp nhập chữ
        val dd = DropdownLayer(this)
        val pr = TextPromptLayer(this) { on -> setPanelFocusable(on) }
        dropdownLayer = dd
        promptLayer = pr
        val host = object : PanelHost {
            override val dropdown: DropdownLayer = dd
            override val prompt: TextPromptLayer = pr
            override fun setEditing(on: Boolean) = this@OverlayService.setEditing(on)
        }
        val tab1 = MainButtonsTab(this, repo, circles, host)
        mainTab = tab1

        val tabViews = listOf(
            tab1.build(),
            placeholderTab(tabNames[1]),
            placeholderTab(tabNames[2]),
        )
        val tabContainer = FrameLayout(this)
        tabViews.forEachIndexed { i, v ->
            tabContainer.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (i != 0) { v.visibility = View.GONE; v.alpha = 0f }
        }
        var shownTab = 0
        fun showTab(i: Int) {
            if (i == shownTab) return
            val oldIdx = shownTab
            val old = tabViews[oldIdx]
            val next = tabViews[i]
            if (oldIdx == 0) tab1.exitEdit()   // rời tab 1 -> thoát trạng thái chỉnh nút
            dd.dismissNow()
            pr.close()
            old.animate().cancel(); next.animate().cancel()
            old.animate().alpha(0f).setDuration(140L)
                .withEndAction { if (shownTab != oldIdx) old.visibility = View.GONE }.start()
            next.visibility = View.VISIBLE
            next.alpha = 0f
            next.animate().alpha(1f).setDuration(220L).start()
            shownTab = i
        }

        content.addView(
            buildSidebar { index -> showTab(index) },
            LinearLayout.LayoutParams(dp(SIDEBAR_W_DP), LinearLayout.LayoutParams.MATCH_PARENT),
        )
        content.addView(View(this).apply { setBackgroundColor(0xFF1E2B44.toInt()) },
            LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT))
        content.addView(
            tabContainer,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
        )

        // Góc trên bên phải: mắt / - / X
        card.addView(
            buildControls(),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { topMargin = dp(10); marginEnd = dp(10) },
        )
        // Dropdown nổi + hộp nhập chữ (ẩn sẵn, nằm trên nội dung và nút điều khiển)
        card.addView(dd, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(pr, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Hộp xác nhận đóng menu (ẩn sẵn, nằm trên cùng trong card)
        card.addView(
            buildConfirm(),
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        panelView = scrim
        panelCard = card
        panelContent = content
        cardBg = bg
        panelAddedAt = SystemClock.uptimeMillis()
        applySolid(if (solid) 1f else 0f)
        runCatching { wm.addView(scrim, params) }.onFailure { removePanel() }
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
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(22)))
        row.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(0xFFE8EEFA.toInt())
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(7) })
        return row
    }

    private val tabNames = listOf("Tạo nút chính", "Chỉnh điểm nhấn", "Cài cấu hình")

    /** Cột bên trái: logo + tên app ở trên cùng, bên dưới là các tab xếp dọc. */
    private fun buildSidebar(onSelect: (Int) -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(12)
            setPadding(p, p, p, p)
        }
        col.addView(buildHeader())
        col.addView(View(this), LinearLayout.LayoutParams(1, dp(14)))

        val tabH = dp(42)
        val tabGap = dp(6)
        val step = tabH + tabGap

        // Khung chứa tab: thanh sáng nằm dưới, trượt tới tab được chọn
        val box = FrameLayout(this)
        val indicator = View(this).apply {
            background = GradientDrawable().apply {
                setColor(0xFF1E3A66.toInt())
                cornerRadius = dp(10).toFloat()
            }
        }
        box.addView(indicator, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, tabH))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val colorOff = 0xFF8D9DBA.toInt()
        val colorOn = 0xFFFFFFFF.toInt()
        val tabs = mutableListOf<TextView>()
        var current = -1
        fun select(i: Int, animate: Boolean) {
            if (i == current) return
            val target = i * step.toFloat()
            indicator.animate().cancel()
            if (animate) {
                indicator.animate().translationY(target).setDuration(TAB_ANIM_MS)
                    .setInterpolator(TAB_INTERPOLATOR).start()
            } else {
                indicator.translationY = target
            }
            tabs.forEachIndexed { idx, t ->
                val to = if (idx == i) colorOn else colorOff
                if (animate) {
                    android.animation.ValueAnimator.ofObject(
                        android.animation.ArgbEvaluator(), t.currentTextColor, to,
                    ).apply {
                        duration = TAB_ANIM_MS
                        addUpdateListener { t.setTextColor(it.animatedValue as Int) }
                    }.start()
                } else {
                    t.setTextColor(to)
                }
            }
            current = i
            onSelect(i)
        }
        tabNames.forEachIndexed { i, name ->
            val t = TextView(this).apply {
                text = name
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(10), 0)
                setOnClickListener { select(i, animate = true) }
            }
            tabs.add(t)
            list.addView(t, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, tabH,
            ).apply { if (i < tabNames.lastIndex) bottomMargin = tabGap })
        }
        col.addView(box)
        select(0, animate = false)
        return col
    }

    // ---- 3 nút góc phải: mắt / - / X ---------------------------------------

    private fun buildControls(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val eye = iconButton(if (solid) R.drawable.ic_eye_off else R.drawable.ic_eye) { toggleSolid() }
        eyeIcon = eye
        val minus = iconButton(R.drawable.ic_minus) { closePanel() }
        val close = iconButton(R.drawable.ic_x) { showConfirm() }
        listOf(eye, minus, close).forEachIndexed { i, v ->
            bar.addView(v, LinearLayout.LayoutParams(dp(34), dp(34)).apply { if (i > 0) marginStart = dp(6) })
        }
        panelControls = bar
        return bar
    }

    private fun roundedBg(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun iconButton(res: Int, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(0xFFE8EEFA.toInt())
        scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = dp(8)
        setPadding(pad, pad, pad, pad)
        background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF), roundedBg(0x14FFFFFF, 10), roundedBg(Color.WHITE, 10),
        )
        isClickable = true
        setOnClickListener { if (!closing) onClick() }
    }

    /** Mắt: bật/tắt độ trong suốt có sẵn của menu (mắt mở = trong suốt như cũ, mắt gạch chéo = đặc). */
    private fun toggleSolid() {
        solid = !solid
        prefs.edit().putBoolean(KEY_SOLID, solid).apply()
        solidAnim?.cancel()
        solidAnim = ValueAnimator.ofFloat(solidAmount, if (solid) 1f else 0f).apply {
            duration = 260L
            interpolator = TAB_INTERPOLATOR
            addUpdateListener { applySolid(it.animatedValue as Float) }
            start()
        }
        // Đổi icon mắt mở <-> mắt gạch chéo, có nhún nhẹ
        eyeIcon?.let { icon ->
            icon.animate().cancel()
            icon.animate().scaleX(0.6f).scaleY(0.6f).alpha(0.3f).setDuration(90L).withEndAction {
                icon.setImageResource(if (solid) R.drawable.ic_eye_off else R.drawable.ic_eye)
                icon.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180L)
                    .setInterpolator(OvershootInterpolator(2f)).start()
            }.start()
        }
    }

    /**
     * t: 0 = giữ độ trong suốt có sẵn của menu (nền card 95% + nền tối mờ 40% nhìn xuyên thấy app phía sau),
     *    1 = tắt trong suốt (card đặc hẳn + nền phía sau gần như che kín).
     * Nút điều khiển luôn rõ để còn bấm bật lại.
     */
    private fun applySolid(t: Float) {
        solidAmount = t
        cardBg?.setColor(Color.argb((CARD_ALPHA + (255 - CARD_ALPHA) * t).toInt(), 0x0D, 0x15, 0x24))
        panelView?.setBackgroundColor(Color.argb(scrimAlpha(t), 0, 0, 0))
    }

    /** Độ đậm nền tối phía sau; nhạt đi khi đang chỉnh nút để nhìn rõ màn hình mà đặt vị trí. */
    private fun scrimAlpha(t: Float): Int =
        ((SCRIM_ALPHA + (SCRIM_SOLID_ALPHA - SCRIM_ALPHA) * t) * (1f - EDIT_DIM_REDUCE * editDim)).toInt()

    // ---- trạng thái chỉnh nút + bàn phím -----------------------------------

    /** Bật/tắt khoá đóng nhầm khi đang chỉnh một nút main. */
    private fun setEditing(on: Boolean) {
        if (editLock == on) return
        editLock = on
        editDimAnim?.cancel()
        editDimAnim = ValueAnimator.ofFloat(editDim, if (on) 1f else 0f).apply {
            duration = 260L
            interpolator = TAB_INTERPOLATOR
            addUpdateListener {
                editDim = it.animatedValue as Float
                if (!closing) applySolid(solidAmount)
            }
            start()
        }
    }

    /** Cho cửa sổ menu nhận/không nhận bàn phím (chỉ bật khi đang nhập chữ). */
    private fun setPanelFocusable(on: Boolean) {
        val p = panelParams ?: return
        val v = panelView ?: return
        p.flags = if (on) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** Gốc của cửa sổ menu: bắt phím Back để đóng hộp nhập chữ / dropdown trước. */
    private inner class PanelRoot(context: Context) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                val promptOpen = promptLayer?.isOpen == true
                val dropOpen = dropdownLayer?.isOpen == true
                if (promptOpen || dropOpen) {
                    if (event.action == KeyEvent.ACTION_UP) {
                        if (!(promptLayer?.handleBack() ?: false)) dropdownLayer?.dismiss()
                    }
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private fun placeholderTab(name: String): View = TextView(this).apply {
        text = "$name\nđang phát triển"
        setTextColor(0xFF8D9DBA.toInt())
        textSize = 15f
        gravity = Gravity.CENTER
    }

    // ---- hộp xác nhận đóng menu --------------------------------------------

    private fun buildConfirm(): View {
        val layer = FrameLayout(this).apply {
            background = roundedBg(0xB3050A15.toInt(), 18)
            isClickable = true // nuốt chạm, không cho thao tác xuyên xuống menu
            visibility = View.GONE
            setOnClickListener { hideConfirm() } // chạm vùng tối ngoài hộp = "Không"
        }
        val dlg = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(18)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                setColor(0xFF121C30.toInt())
                setStroke(dp(1), 0xFF1E2B44.toInt())
                cornerRadius = dp(16).toFloat()
            }
            isClickable = true // chạm trong hộp không bị tính là chạm ra ngoài
        }
        dlg.addView(TextView(this).apply {
            text = "Đóng menu?"
            setTextColor(0xFFE8EEFA.toInt())
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        })
        dlg.addView(TextView(this).apply {
            text = "Menu nổi sẽ được tắt hoàn toàn. Bạn có chắc muốn đóng không?"
            setTextColor(0xFF8D9DBA.toInt())
            textSize = 13.5f
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            dialogButton("Không", 0xFF1E2B44.toInt(), 0xFFE8EEFA.toInt()) { hideConfirm() },
            LinearLayout.LayoutParams(0, dp(40), 1f),
        )
        row.addView(
            dialogButton("Có", 0xFFEF5B5B.toInt(), 0xFFFFFFFF.toInt()) { playCloseAnimation() },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(10) },
        )
        dlg.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(16) })
        layer.addView(dlg, FrameLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        confirmLayer = layer
        confirmDialog = dlg
        return layer
    }

    private fun dialogButton(label: String, bgColor: Int, textColor: Int, onClick: () -> Unit) =
        TextView(this).apply {
            text = label
            setTextColor(textColor)
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), roundedBg(bgColor, 12), roundedBg(Color.WHITE, 12),
            )
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun showConfirm() {
        val layer = confirmLayer ?: return
        val dlg = confirmDialog ?: return
        if (closing) return
        layer.animate().cancel(); dlg.animate().cancel()
        layer.visibility = View.VISIBLE
        layer.alpha = 0f
        dlg.alpha = 0f; dlg.scaleX = 0.86f; dlg.scaleY = 0.86f
        layer.animate().alpha(1f).setDuration(180L).start()
        dlg.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260L)
            .setInterpolator(OvershootInterpolator(1.2f)).start()
    }

    private fun hideConfirm() {
        val layer = confirmLayer ?: return
        val dlg = confirmDialog ?: return
        layer.animate().cancel(); dlg.animate().cancel()
        dlg.animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(150L).start()
        layer.animate().alpha(0f).setDuration(150L)
            .withEndAction { layer.visibility = View.GONE }.start()
    }

    // ---- hiệu ứng đóng menu: thu thành viên tròn logo rồi bay lên ----------

    private fun playCloseAnimation() {
        val card = panelCard ?: return
        val scrim = panelView ?: return
        if (closing) return
        closing = true
        setWanted(this, false) // chủ động đóng -> không tự khôi phục
        handler.removeCallbacks(watchdog)
        solidAnim?.cancel()
        editDimAnim?.cancel()
        promptLayer?.close()
        circles.setEditing(null)
        circles.fadeAll(0f, 250L)   // các nút tròn mờ đi cùng lúc menu thu lại
        card.animate().cancel(); scrim.animate().cancel()

        // 1) Nội dung + nút mờ đi, chỉ còn lại khung card
        val fadeMs = 160L
        listOfNotNull(panelContent, panelControls, confirmLayer).forEach {
            it.animate().cancel()
            it.animate().alpha(0f).setDuration(fadeMs).start()
        }

        val orb = CloseOrbView(this, ContextCompat.getDrawable(this, R.drawable.logo_transparent)).apply {
            fromRect = RectF(
                card.left + card.translationX, card.top.toFloat(),
                card.right + card.translationX, card.bottom.toFloat(),
            )
            fromRadius = dp(18).toFloat()
            fromFillFactor = (CARD_ALPHA + (255 - CARD_ALPHA) * solidAmount) / CARD_ALPHA
            visibility = View.INVISIBLE
        }
        scrim.addView(
            orb,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )

        val dim0 = scrimAlpha(solidAmount)
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CloseOrbView.DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                orb.progress = t
                // nền tối mờ dần trong lúc viên tròn bay lên
                val dim = (dim0 * (1f - (t / 0.8f).coerceAtMost(1f))).toInt()
                scrim.setBackgroundColor(Color.argb(dim, 0, 0, 0))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finishClose()
            })
        }
        closeAnim = anim

        // 2) Hết mờ: ẩn card thật, thay bằng viên "orb" cùng hình dáng rồi bắt đầu thu nhỏ
        handler.postDelayed({
            if (closeAnim !== anim) return@postDelayed
            card.visibility = View.INVISIBLE
            orb.visibility = View.VISIBLE
            orb.progress = 0f
            anim.start()
        }, fadeMs)
    }

    private fun finishClose() {
        closeAnim = null
        closing = false
        if (wanted(this)) {
            // Trong lúc đang đóng, người dùng bật lại menu từ app -> dựng lại nút thay vì tắt
            removePanel()
            ensureHandle()
            handleView?.alpha = 1f
            startWatchdog()
        } else {
            stopSelf()
        }
    }

    // ---- điều khiển + animation -------------------------------------------

    private fun beginPanelDrag() {
        if (closing) return
        panelCard?.animate()?.cancel()
        panelView?.animate()?.cancel()
        handleView?.animate()?.cancel()
        panelAnimating = false
        ensurePanel()
    }

    /** Thả tay sau khi kéo: đủ xa thì mở hẳn, không thì thu lại. */
    private fun finishPanelDrag(progress: Float) {
        if (progress > 0.6f) openPanel() else closePanel()
    }

    /** Card menu: kéo ngang sang trái bằng bất kỳ chỗ nào trong card để thu menu vào. */
    private inner class DragCard(context: Context) : LinearLayout(context) {
        private val touchSlop = dp(8)
        private var downX = 0f
        private var downY = 0f
        private var dragging = false

        private fun progressFor(e: MotionEvent) =
            (1f + (e.rawX - downX) / travel).coerceIn(0f, 1f)

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false }
                MotionEvent.ACTION_MOVE -> if (!dragging) {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!editLock && dx < -touchSlop && abs(dx) > abs(dy)) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        beginPanelDrag()
                        updatePanelDrag(progressFor(e))
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) {
                        val dx = e.rawX - downX
                        if (!editLock && dx < -touchSlop && abs(dx) > abs(e.rawY - downY)) {
                            dragging = true; beginPanelDrag()
                        }
                    }
                    if (dragging) updatePanelDrag(progressFor(e))
                }
                MotionEvent.ACTION_UP -> if (dragging) { dragging = false; finishPanelDrag(progressFor(e)) }
                MotionEvent.ACTION_CANCEL -> if (dragging) { dragging = false; openPanel() }
            }
            return true
        }
    }

    /** progress 0 = thu hết, 1 = mở hết. */
    private fun updatePanelDrag(progress: Float) {
        val card = panelCard ?: return
        card.translationX = hiddenX * (1f - progress)
        panelView?.alpha = progress
        handleView?.alpha = 1f - progress
    }

    private fun openPanel() {
        if (closing) return
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
        if (closing) return
        val card = panelCard
        val scrim = panelView
        if (card == null || scrim == null) {
            handleView?.alpha = 1f
            return
        }
        mainTab?.exitEdit()
        dropdownLayer?.dismissNow()
        promptLayer?.close()
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
        solidAnim?.cancel()
        editDimAnim?.cancel()
        editDim = 0f
        editLock = false
        if (::circles.isInitialized) circles.setEditing(null)
        panelView?.let { runCatching { wm.removeView(it) } }
        panelParams = null
        dropdownLayer = null
        promptLayer = null
        mainTab = null
        panelView = null
        panelCard = null
        panelContent = null
        panelControls = null
        cardBg = null
        confirmLayer = null
        confirmDialog = null
        eyeIcon = null
    }

    private fun removeViews() {
        handleView?.let { runCatching { wm.removeView(it) } }
        handleView = null
        removePanel()
        if (::circles.isInitialized) circles.removeAll()
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
        private const val SIDEBAR_W_DP = 170
        private const val HANDLE_W_DP = 15
        private const val HANDLE_H_DP = 75
        private const val MIN_TOUCH_W_DP = 36
        private const val MIN_TOUCH_H_DP = 72
        private const val PANEL_ANIM_MS = 280L
        private const val TAB_ANIM_MS = 380L
        // Đường cong Material "standard": êm lúc bắt đầu, chậm dần mượt về cuối
        private val TAB_INTERPOLATOR get() = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)

        private const val PREFS = "zyntap_overlay"
        private const val KEY_WANTED = "wanted"
        private const val KEY_Y_RATIO = "handle_y_ratio"
        private const val KEY_SOLID = "solid_mode"
        // Độ đặc (0..255) của nền card và nền tối phía sau: mặc định (trong suốt) và khi tắt trong suốt
        private const val CARD_ALPHA = 0xF2
        private const val SCRIM_ALPHA = 0x66
        private const val EDIT_DIM_REDUCE = 0.7f // đang chỉnh nút: nền tối nhạt đi 70%
        private const val SCRIM_SOLID_ALPHA = 0x66 // = SCRIM_ALPHA: tắt trong suốt chỉ làm card đặc, không đậm thêm lớp đen

        private const val WATCHDOG_MS = 2000L
        private const val ATTACH_GRACE_MS = 1500L

        private fun setWanted(context: Context, value: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WANTED, value).apply()
        }

        /** Người dùng đang muốn bật menu nổi (chưa bấm tắt). */
        fun wanted(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WANTED, false)

        /** Bật lại menu nổi nếu trước đó đang bật mà bị mất (cập nhật app, bị kill...). */
        fun restoreIfWanted(context: Context) {
            if (wanted(context) && OverlayPermission.granted(context)) {
                runCatching { start(context) }
            }
        }

        fun start(context: Context) {
            setWanted(context, true)
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_SHOW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            setWanted(context, false)
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_STOP))
        }
    }
}
