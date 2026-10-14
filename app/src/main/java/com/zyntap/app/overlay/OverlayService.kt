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
import android.util.TypedValue
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
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zyntap.app.R
import com.zyntap.app.data.MainButton
import com.zyntap.app.data.TapExecutor
import com.zyntap.app.data.TapMode
import com.zyntap.app.data.TapStore
import kotlin.math.abs

/**
 * Menu nổi ZynTap: tab trái + card trượt ra từ mép trái, 3 tab chức năng chính:
 *  Tab 1 — Tạo nút chính: tạo/sửa các nút main (mode nhấn/giữ/thả, độ mờ, size).
 *  Tab 2 — Chỉnh điểm nhấn: chọn nút main rồi nhập chuỗi tọa độ + delay qua ghim kéo-thả.
 *  Tab 3 — Cài cấu hình: lưu/nạp/cập nhật/xoá các cấu hình đã tạo.
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
    private var solid = false
    private var solidAmount = 0f
    private var solidAnim: ValueAnimator? = null
    private var closing = false
    private var closeAnim: ValueAnimator? = null
    private var panelAnimating = false
    private var handleDragging = false
    private var handleAddedAt = 0L
    private var panelAddedAt = 0L
    private val handler = Handler(Looper.getMainLooper())

    // ---- dữ liệu nút main / tab -------------------------------------------
    private val buttons = mutableListOf<MainButton>()
    private var activeButtonId: String? = null
    private var currentTab = 0

    // Các vùng nội dung của 3 tab (được dựng lại mỗi lần đổi tab)
    private var tabHost: FrameLayout? = null

    // Giao diện nổi của các nút main (hình tròn kéo-thả) + ghim chọn điểm
    private val mainButtonViews = mutableMapOf<String, View>()
    private val mainButtonParams = mutableMapOf<String, WindowManager.LayoutParams>()
    private var editingButtons = false // đang ở trạng thái "chỉnh sửa" -> không chạy bấm
    private var pinView: View? = null
    private var pinParams: WindowManager.LayoutParams? = null
    private var pinTargetBtn: String? = null
    private var pinTargetXInput: EditText? = null
    private var pinTargetYInput: EditText? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buttons.clear()
        buttons.addAll(TapStore.loadButtons(this))
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> closePanel()
            ACTION_STOP -> {
                setWanted(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (intent == null && !wanted(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureHandle()
                restoreMainButtons()
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
        removePin()
        removeAllMainButtons()
        removeViews()
        TapExecutor.disconnect()
        super.onDestroy()
    }

    // ---- watchdog & vị trí tab mép trái (giữ nguyên như bản cũ) ------------

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

    private fun checkHealth() {
        val h = handleView
        if (h == null) { ensureHandle(); return }
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
    }

    private val prefs get() = getSharedPreferences(PREFS, MODE_PRIVATE)

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

    private fun displayCutoutCompat(): DisplayCutout? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { wm.currentWindowMetrics.windowInsets.displayCutout?.let { return it } }
        }
        return handleView?.rootWindowInsets?.displayCutout
    }

    private val safeLeft: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) displayCutoutCompat()?.safeInsetLeft ?: 0 else 0

    private val cardStart get() = maxOf(dp(PANEL_MARGIN_DP), safeLeft)
    private val travel get() = cardW + cardStart
    private val cardW get() = minOf(dp(PANEL_W_DP), screenW - cardStart - dp(PANEL_MARGIN_DP))
    private val cardH get() = minOf(dp(PANEL_H_DP), screenH - dp(PANEL_MARGIN_DP) * 2)
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
    }

    private var handleParams: WindowManager.LayoutParams? = null
    private var handleW = 0
    private var handleH = 0

    private fun placeHandleY(desired: Int): Int {
        val maxY = (screenH - handleH).coerceAtLeast(0)
        var y = desired.coerceIn(0, maxY)
        val rects = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            displayCutoutCompat()?.boundingRects.orEmpty() else emptyList()
        val pad = dp(6)
        repeat(2) {
            for (r in rects) {
                if (r.left >= handleW + pad || r.right <= 0) continue
                if (y + handleH <= r.top - pad || y >= r.bottom + pad) continue
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
        removePanel()
        handleView?.alpha = 1f
        relayoutHandle()
        handleView?.postDelayed({ relayoutHandle() }, 400)
    }

    private fun ensureHandle() {
        if (handleView != null) return
        if (!OverlayPermission.granted(this)) return
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
        var mode = 0
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
                        2 -> saveHandlePosition()
                        0 -> openPanel()
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
            handleView = null
            handleParams = null
            return
        }
        handle.post { relayoutHandle() }
    }

    // ---- menu card trượt ra -----------------------------------------------

    private fun ensurePanel() {
        if (panelView != null) return
        solid = prefs.getBoolean(KEY_SOLID, false)

        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply {
            // Cho phép lấy focus để gõ phím/ấn Enter nhập tọa độ-delay
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            alpha = 0f
        }
        val slop = dp(8)
        var sDownX = 0f
        var sDragging = false
        scrim.setOnTouchListener { _, e ->
            if (closing) return@setOnTouchListener true
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
        val card = FrameLayout(this).apply {
            background = bg
            isClickable = true
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

        val host = FrameLayout(this).apply {
            setPadding(dp(14), dp(44), dp(14), dp(14))
        }
        tabHost = host

        content.addView(
            buildSidebar { index ->
                currentTab = index
                renderTab()
            },
            LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.MATCH_PARENT),
        )
        content.addView(View(this).apply { setBackgroundColor(0xFF1E2B44.toInt()) },
            LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT))
        content.addView(
            host,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
        )

        card.addView(
            buildControls(),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { topMargin = dp(10); marginEnd = dp(10) },
        )
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
        renderTab()
    }

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
                textSize = 13.5f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { select(i, animate = true) }
            }
            tabs.add(t)
            list.addView(t, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, tabH,
            ).apply { if (i < tabNames.lastIndex) bottomMargin = tabGap })
        }
        col.addView(box)
        select(currentTab.coerceIn(0, tabNames.lastIndex), animate = false)
        return col
    }

    // ---- 3 nút góc phải ----------------------------------------------------

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
        eyeIcon?.let { icon ->
            icon.animate().cancel()
            icon.animate().scaleX(0.6f).scaleY(0.6f).alpha(0.3f).setDuration(90L).withEndAction {
                icon.setImageResource(if (solid) R.drawable.ic_eye_off else R.drawable.ic_eye)
                icon.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180L)
                    .setInterpolator(OvershootInterpolator(2f)).start()
            }.start()
        }
    }

    private fun applySolid(t: Float) {
        solidAmount = t
        cardBg?.setColor(Color.argb((CARD_ALPHA + (255 - CARD_ALPHA) * t).toInt(), 0x0D, 0x15, 0x24))
        panelView?.setBackgroundColor(Color.argb(scrimAlpha(t), 0, 0, 0))
    }

    private fun scrimAlpha(t: Float): Int = (SCRIM_ALPHA + (SCRIM_SOLID_ALPHA - SCRIM_ALPHA) * t).toInt()

    private fun buildConfirm(): View {
        val layer = FrameLayout(this).apply {
            background = roundedBg(0xB3050A15.toInt(), 18)
            isClickable = true
            visibility = View.GONE
            setOnClickListener { hideConfirm() }
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
            isClickable = true
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

    // ---- NỘI DUNG 3 TAB ----------------------------------------------------

    private fun renderTab() {
        val host = tabHost ?: return
        host.removeAllViews()
        when (currentTab) {
            0 -> host.addView(buildTabButtons(), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            1 -> host.addView(buildTabPoints(), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            2 -> host.addView(buildTabConfig(), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    // --- Tab 1: Tạo nút chính -----------------------------------------------

    private var selectedButtonForEdit: String? = null

    private fun buildTabButtons(): View {
        val root = TapViews.col(this)
        val scroll = ScrollView(this)
        val inner = TapViews.col(this)

        inner.addView(TapViews.label(this, "Settings nút main", 15f, TapViews.C_TEXT).apply {
            typeface = Typeface.DEFAULT_BOLD
        })
        inner.addView(TapViews.label(this, "Tạo và chỉnh các nút main (main1, main2,...)."))
        inner.addView(TapViews.spacer(this, 12))

        val btn = selectedButtonForEdit?.let { id -> buttons.firstOrNull { it.id == id } }

        if (btn != null) {
            inner.addView(TapViews.label(this, "Mã: ${btn.id}", 12.5f, TapViews.C_CYAN))
            inner.addView(TapViews.spacer(this, 6))

            // Tên
            inner.addView(TapViews.label(this, "Tên nút"))
            val nameInput = TapViews.editText(this, "Tên nút", number = false).apply {
                setText(btn.name)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
                ).apply { topMargin = dp(4) }
            }
            nameInput.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    btn.name = nameInput.text.toString().ifBlank { btn.id }
                    saveButtonsPersist()
                    refreshMainButtonLabel(btn)
                }
            }
            inner.addView(nameInput)
            inner.addView(TapViews.spacer(this, 10))

            // Mode dropdown
            inner.addView(TapViews.label(this, "Mode"))
            val modes = listOf(TapMode.TAP to "Nhấn", TapMode.HOLD to "Giữ", TapMode.RELEASE to "Thả")
            val modeSpinner = TapViews.makeSpinner(this, modes, { it.second }) { idx ->
                btn.mode = modes[idx].first
                saveButtonsPersist()
            }
            modeSpinner.setSelection(modes.indexOfFirst { it.first == btn.mode }.coerceAtLeast(0))
            inner.addView(modeSpinner, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
            ).apply { topMargin = dp(4) })
            inner.addView(TapViews.spacer(this, 10))

            // Alpha
            inner.addView(TapViews.sliderRow(this, "Độ mờ (%)", btn.alphaPct, 10, 100) { v ->
                btn.alphaPct = v
                saveButtonsPersist()
                applyMainButtonLook(btn)
            })
            inner.addView(TapViews.spacer(this, 6))

            // Size
            inner.addView(TapViews.sliderRow(this, "Size nút (dp)", btn.sizeDp, 36, 110) { v ->
                btn.sizeDp = v
                saveButtonsPersist()
                applyMainButtonLook(btn)
            })
            inner.addView(TapViews.spacer(this, 12))

            // Xoá nút
            val delBtn = TapViews.button(this, "Xoá nút này", TapViews.C_RED, Color.WHITE)
            delBtn.setOnClickListener {
                removeMainButton(btn.id)
                buttons.remove(btn)
                selectedButtonForEdit = null
                saveButtonsPersist()
                renderTab()
            }
            inner.addView(delBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))
            inner.addView(TapViews.spacer(this, 14))
        }

        // Nút tạo nút mới
        val createBtn = TapViews.button(this, "+  Tạo nút main mới", TapViews.C_BLUE, Color.WHITE)
        createBtn.setOnClickListener { createNewMainButton() }
        inner.addView(createBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44)))

        inner.addView(TapViews.spacer(this, 14))
        inner.addView(TapViews.label(this, "Thư mục nút (ấn vào thanh để chỉnh)"))
        inner.addView(TapViews.spacer(this, 6))

        if (buttons.isEmpty()) {
            inner.addView(TapViews.label(this, "— Chưa có nút nào —", 13f, TapViews.C_DIM))
        } else {
            buttons.forEach { b ->
                val sel = (selectedButtonForEdit == b.id)
                val row = TapViews.buttonRow(this, b, sel) {
                    // Ấn lần đầu -> mở settings; ấn lại chính nó -> thoát settings
                    selectedButtonForEdit = if (sel) null else b.id
                    editingButtons = selectedButtonForEdit != null
                    renderTab()
                    refreshAllMainButtonsEditState()
                }
                inner.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(40)
                ).apply { topMargin = dp(6) })
            }
        }

        scroll.addView(inner, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun createNewMainButton() {
        val id = TapStore.nextButtonId(this, buttons)
        val b = MainButton(id = id, name = id)
        buttons.add(b)
        saveButtonsPersist()
        selectedButtonForEdit = id
        editingButtons = true
        addMainButtonView(b)
        renderTab()
        refreshAllMainButtonsEditState()
    }

    // --- Tab 2: Chỉnh điểm nhấn ---------------------------------------------

    private var pointsSelectedId: String? = null

    private fun buildTabPoints(): View {
        val root = TapViews.col(this)

        // Dropdown chọn nút main
        root.addView(TapViews.label(this, "Nút main sẽ kết nối"))
        val list = buttons.toList()
        val labels = list.map { it.id + " — " + it.name }
        val spinner = TapViews.makeSpinner(this, list, { b -> b.id + " — " + b.name }) { idx ->
            pointsSelectedId = list.getOrNull(idx)?.id
            renderTab()
        }
        if (list.isEmpty()) spinner.isEnabled = false
        val startIdx = list.indexOfFirst { it.id == pointsSelectedId }.coerceAtLeast(0)
        if (list.isNotEmpty()) spinner.setSelection(startIdx)
        root.addView(spinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
        ).apply { topMargin = dp(4) })
        root.addView(TapViews.spacer(this, 10))

        val btn = (pointsSelectedId ?: list.firstOrNull()?.id)?.let { id -> buttons.firstOrNull { it.id == id } }
        if (btn == null) {
            root.addView(TapViews.label(this, "Hãy tạo một nút main trước ở tab 'Tạo nút chính'."))
            return root
        }
        pointsSelectedId = btn.id

        // Ô mã lệnh to
        root.addView(TapViews.label(this, "Chuỗi tọa độ + delay"))
        val script = TapViews.scriptBox(this, 160).apply { setText(btn.script) }
        script.setOnFocusChangeListener { _, has ->
            if (!has) { btn.script = script.text.toString(); saveButtonsPersist() }
        }
        root.addView(script, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        root.addView(TapViews.spacer(this, 10))

        // Hàng click point [x] [y]
        root.addView(TapViews.label(this, "click point"))
        val xIn = TapViews.coordInput(this) {
            // chạm lần đầu -> hiện ghim; nếu đã có ghim chọn cho cùng ô thì không tạo lại
            if (pinTargetXInput !== null && pinTargetYInput !== null && pinTargetBtn == btn.id) return@coordInput
            showPin(btn)
        }
        val yIn = TapViews.coordInput(this) {
            if (pinTargetXInput !== null && pinTargetYInput !== null && pinTargetBtn == btn.id) return@coordInput
            showPin(btn)
        }
        val coordRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        coordRow.addView(xIn, LinearLayout.LayoutParams(0, dp(42), 1f))
        coordRow.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        coordRow.addView(yIn, LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(coordRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })

        // Ấn Enter -> ghi dòng tọa độ vào ô script
        val commit = {
            val x = xIn.text.toString().trim().toIntOrNull()
            val y = yIn.text.toString().trim().toIntOrNull()
            if (x != null && y != null) {
                appendLine(script, "$x, $y")
                btn.script = script.text.toString()
                saveButtonsPersist()
                xIn.setText(""); yIn.setText("")
                removePin()
            }
        }
        xIn.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            if (enter) { commit(); true } else false
        }
        yIn.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            if (enter) { commit(); true } else false
        }

        root.addView(TapViews.spacer(this, 10))

        // Hàng Delay [nhập ms]
        root.addView(TapViews.label(this, "Delay (ms)"))
        val delayIn = TapViews.editText(this, "ví dụ 300").apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        delayIn.setOnEditorActionListener { _, actionId, event ->
            val enter = actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)
            if (enter) {
                val v = delayIn.text.toString().trim().toIntOrNull()
                if (v != null && v >= 0) {
                    appendLine(script, "${v}ms")
                    btn.script = script.text.toString()
                    saveButtonsPersist()
                    delayIn.setText("")
                }
                true
            } else false
        }
        root.addView(delayIn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
        ).apply { topMargin = dp(4) })

        return root
    }

    private fun appendLine(script: EditText, line: String) {
        val cur = script.text.toString()
        val sep = if (cur.isEmpty() || cur.endsWith("\n")) "" else "\n"
        script.append("$sep$line\n")
    }

    // --- Tab 3: Cài cấu hình -------------------------------------------------

    private var selectedConfig: String? = null

    private fun buildTabConfig(): View {
        val root = TapViews.col(this)

        root.addView(TapViews.label(this, "Cài cấu hình", 15f, TapViews.C_TEXT).apply {
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TapViews.label(this, "Lưu, nạp, cập nhật và xoá cấu hình các nút main."))
        root.addView(TapViews.spacer(this, 12))

        root.addView(TapViews.label(this, "Tên cấu hình mới"))
        val nameIn = TapViews.editText(this, "ví dụ: PUBG_main", number = false)
        root.addView(nameIn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(42)
        ).apply { topMargin = dp(4) })
        root.addView(TapViews.spacer(this, 8))

        val createBtn = TapViews.button(this, "Tạo cấu hình", TapViews.C_BLUE, Color.WHITE)
        root.addView(createBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(42)))

        root.addView(TapViews.spacer(this, 14))
        root.addView(TapViews.label(this, "Chọn cấu hình"))
        val names = TapStore.configNames(this)
        val spinner = TapViews.makeSpinner(this, names, { it }) { idx ->
            selectedConfig = names.getOrNull(idx)
        }
        if (names.isEmpty()) spinner.isEnabled = false
        if (names.isNotEmpty()) {
            val idx = names.indexOf(selectedConfig).takeIf { it >= 0 } ?: 0
            selectedConfig = names[idx]
            spinner.setSelection(idx)
        }
        root.addView(spinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
        ).apply { topMargin = dp(4) })

        root.addView(TapViews.spacer(this, 10))

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val loadBtn = TapViews.button(this, "Nạp", TapViews.C_SURFACE_HI)
        val updBtn = TapViews.button(this, "Cập nhật", TapViews.C_SURFACE_HI)
        val delBtn = TapViews.button(this, "Xoá", TapViews.C_RED, Color.WHITE)
        btnRow.addView(loadBtn, LinearLayout.LayoutParams(0, dp(42), 1f))
        btnRow.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        btnRow.addView(updBtn, LinearLayout.LayoutParams(0, dp(42), 1f))
        btnRow.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        btnRow.addView(delBtn, LinearLayout.LayoutParams(0, dp(42), 1f))
        root.addView(btnRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val status = TapViews.label(this, "", 12.5f, TapViews.C_CYAN)
        root.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })

        createBtn.setOnClickListener {
            val name = nameIn.text.toString().trim()
            if (name.isEmpty()) { status.text = "Nhập tên cấu hình đã nha."; return@setOnClickListener }
            TapStore.saveConfig(this, name, buttons)
            selectedConfig = name
            status.text = "Đã tạo cấu hình: $name"
            renderTab()
        }
        loadBtn.setOnClickListener {
            val n = selectedConfig ?: return@setOnClickListener
            val list = TapStore.loadConfig(this, n) ?: return@setOnClickListener
            removeAllMainButtons()
            buttons.clear(); buttons.addAll(list)
            saveButtonsPersist()
            list.forEach { addMainButtonView(it) }
            status.text = "Đã nạp: $n"
        }
        updBtn.setOnClickListener {
            val n = selectedConfig ?: return@setOnClickListener
            TapStore.saveConfig(this, n, buttons)
            status.text = "Đã cập nhật: $n"
        }
        delBtn.setOnClickListener {
            val n = selectedConfig ?: return@setOnClickListener
            TapStore.deleteConfig(this, n)
            selectedConfig = null
            status.text = "Đã xoá: $n"
            renderTab()
        }

        return root
    }

    // ---- NÚT MAIN NỔI (hình tròn, kéo/thả, bấm chạy) -----------------------

    private fun restoreMainButtons() {
        if (!OverlayPermission.granted(this)) return
        buttons.forEach { b -> addMainButtonView(b) }
    }

    private fun addMainButtonView(b: MainButton) {
        if (mainButtonViews.containsKey(b.id)) return
        val sizePx = dp(b.sizeDp)
        val lp = overlayParams(sizePx, sizePx,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS).apply {
            gravity = Gravity.START or Gravity.TOP
            x = if (b.x >= 0) b.x else (screenW - sizePx) / 2
            y = if (b.y >= 0) b.y else (screenH - sizePx) / 2
        }
        val v = FrameLayout(this).apply {
            background = circleBg(b)
            val tv = TextView(context).apply {
                text = b.name
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(tv, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            alpha = b.alphaPct / 100f
        }
        attachMainButtonTouch(v, lp, b)
        runCatching { wm.addView(v, lp) }.onFailure { return }
        mainButtonViews[b.id] = v
        mainButtonParams[b.id] = lp
    }

    private fun circleBg(b: MainButton): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0xCC1E3A66.toInt())
        setStroke(dp(2), 0xFF19D3FF.toInt())
    }

    private fun applyMainButtonLook(b: MainButton) {
        val v = mainButtonViews[b.id] ?: return
        v.alpha = b.alphaPct / 100f
        val lp = mainButtonParams[b.id] ?: return
        val size = dp(b.sizeDp)
        lp.width = size; lp.height = size
        (v as? FrameLayout)?.getChildAt(0)?.let { (it as TextView).text = b.name }
        runCatching { wm.updateViewLayout(v, lp) }
    }

    private fun refreshMainButtonLabel(b: MainButton) {
        val v = mainButtonViews[b.id] as? FrameLayout ?: return
        (v.getChildAt(0) as? TextView)?.text = b.name
    }

    private fun removeMainButton(id: String) {
        mainButtonViews.remove(id)?.let { runCatching { wm.removeView(it) } }
        mainButtonParams.remove(id)
    }

    private fun removeAllMainButtons() {
        mainButtonViews.values.forEach { runCatching { wm.removeView(it) } }
        mainButtonViews.clear()
        mainButtonParams.clear()
    }

    private fun refreshAllMainButtonsEditState() {
        // không đổi hình, chỉ dùng cờ editingButtons trong touch listener
    }

    private fun attachMainButtonTouch(v: View, lp: WindowManager.LayoutParams, b: MainButton) {
        val slop = dp(8)
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var dragging = false
        var downTime = 0L
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false
                    downTime = SystemClock.uptimeMillis()
                    if (editingButtons && b.mode == TapMode.HOLD) {
                        // không chạy khi đang edit
                    } else if (!editingButtons && b.mode == TapMode.HOLD) {
                        TapExecutor.startLoop(this, b.script)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        // chỉ cho kéo khi đang chỉnh sửa, hoặc giữ lâu > 400ms
                        if (editingButtons || SystemClock.uptimeMillis() - downTime > 400) {
                            dragging = true
                            if (!editingButtons && b.mode == TapMode.HOLD) TapExecutor.stopLoop()
                        }
                    }
                    if (dragging) {
                        lp.x = (startX + dx.toInt()).coerceIn(0, (screenW - lp.width).coerceAtLeast(0))
                        lp.y = (startY + dy.toInt()).coerceIn(0, (screenH - lp.height).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val wasDrag = dragging
                    dragging = false
                    if (wasDrag) {
                        b.x = lp.x; b.y = lp.y; saveButtonsPersist()
                    } else {
                        if (!editingButtons) {
                            when (b.mode) {
                                TapMode.TAP -> TapExecutor.runOnce(this, b.script)
                                TapMode.RELEASE -> TapExecutor.runOnce(this, b.script)
                                TapMode.HOLD -> TapExecutor.stopLoop()
                            }
                        } else if (!editingButtons.not() && b.mode == TapMode.HOLD) {
                            TapExecutor.stopLoop()
                        } else if (b.mode == TapMode.HOLD) {
                            TapExecutor.stopLoop()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    TapExecutor.stopLoop()
                    true
                }
                else -> false
            }
        }
    }

    private fun saveButtonsPersist() {
        TapStore.saveButtons(this, buttons)
    }

    // ---- GHIM CHỌN ĐIỂM (map pin) ------------------------------------------

    private fun showPin(b: MainButton) {
        removePin()
        val sizePx = dp(56)
        val lp = overlayParams(sizePx, sizePx,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS).apply {
            gravity = Gravity.START or Gravity.TOP
            x = (screenW - sizePx) / 2
            y = (screenH - sizePx) / 2
        }
        val pin = FrameLayout(this).apply {
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_map_pin)
                setColorFilter(0xFF19D3FF.toInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT))
            alpha = 0.95f
        }
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var dragging = false
        val slop = dp(6)
        pin.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        lp.x = (startX + dx.toInt()).coerceIn(0, (screenW - lp.width).coerceAtLeast(0))
                        lp.y = (startY + dy.toInt()).coerceIn(0, (screenH - lp.height).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(pin, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        // Chạm vào ghim -> chốt tọa độ (đầu ghim nằm ở giữa dưới)
                        val cx = lp.x + lp.width / 2
                        val cy = lp.y + lp.height - dp(4)
                        pinTargetXInput?.setText("$cx")
                        pinTargetYInput?.setText("$cy")
                        pinTargetXInput?.requestFocus()
                    }
                    true
                }
                else -> false
            }
        }
        runCatching { wm.addView(pin, lp) }.onFailure { return }
        pinView = pin
        pinParams = lp
        pinTargetBtn = b.id
        // tìm 2 EditText coord trong tab 2
        val host = tabHost ?: return
        val list = mutableListOf<EditText>()
        findEditTexts(host, list)
        pinTargetXInput = list.getOrNull(1) // index 1 = x (ô mã lệnh = 0)
        pinTargetYInput = list.getOrNull(2) // index 2 = y
    }

    private fun removePin() {
        pinView?.let { runCatching { wm.removeView(it) } }
        pinView = null
        pinParams = null
        pinTargetBtn = null
        pinTargetXInput = null
        pinTargetYInput = null
    }

    private fun findEditTexts(v: View, out: MutableList<EditText>) {
        if (v is EditText) { out.add(v); return }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findEditTexts(v.getChildAt(i), out)
        }
    }

    // ---- hiệu ứng đóng menu (giữ nguyên) -----------------------------------

    private fun playCloseAnimation() {
        val card = panelCard ?: return
        val scrim = panelView ?: return
        if (closing) return
        closing = true
        setWanted(this, false)
        handler.removeCallbacks(watchdog)
        solidAnim?.cancel()
        card.animate().cancel(); scrim.animate().cancel()
        val fadeMs = 160L
        listOfNotNull(panelContent, panelControls, confirmLayer).forEach {
            it.animate().cancel(); it.animate().alpha(0f).setDuration(fadeMs).start()
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
        scrim.addView(orb, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val dim0 = scrimAlpha(solidAmount)
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CloseOrbView.DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                orb.progress = t
                val dim = (dim0 * (1f - (t / 0.8f).coerceAtMost(1f))).toInt()
                scrim.setBackgroundColor(Color.argb(dim, 0, 0, 0))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finishClose()
            })
        }
        closeAnim = anim
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
            removePanel()
            ensureHandle()
            handleView?.alpha = 1f
            startWatchdog()
        } else {
            stopSelf()
        }
    }

    private fun beginPanelDrag() {
        if (closing) return
        panelCard?.animate()?.cancel()
        panelView?.animate()?.cancel()
        handleView?.animate()?.cancel()
        panelAnimating = false
        ensurePanel()
    }

    private fun finishPanelDrag(progress: Float) {
        if (progress > 0.6f) openPanel() else closePanel()
    }

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
                    if (dx < -touchSlop && abs(dx) > abs(dy)) {
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
                        if (dx < -touchSlop && abs(dx) > abs(e.rawY - downY)) {
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
        removePin()
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
                if (panelCard === card && card.translationX <= hiddenX + 1f) removePanel()
            }.start()
        scrim.animate().alpha(0f).setDuration(PANEL_ANIM_MS).start()
        handleView?.animate()?.alpha(1f)?.setDuration(PANEL_ANIM_MS)?.start()
    }

    private fun removePanel() {
        solidAnim?.cancel()
        removePin()
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
        panelCard = null
        panelContent = null
        panelControls = null
        cardBg = null
        confirmLayer = null
        confirmDialog = null
        eyeIcon = null
        tabHost = null
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
        private const val HANDLE_W_DP = 15
        private const val HANDLE_H_DP = 75
        private const val MIN_TOUCH_W_DP = 36
        private const val MIN_TOUCH_H_DP = 72
        private const val PANEL_ANIM_MS = 280L
        private const val TAB_ANIM_MS = 380L
        private val TAB_INTERPOLATOR get() = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)

        private const val PREFS = "zyntap_overlay"
        private const val KEY_WANTED = "wanted"
        private const val KEY_Y_RATIO = "handle_y_ratio"
        private const val KEY_SOLID = "solid_mode"
        private const val CARD_ALPHA = 0xF2
        private const val SCRIM_ALPHA = 0x66
        private const val SCRIM_SOLID_ALPHA = 0x66

        private const val WATCHDOG_MS = 2000L
        private const val ATTACH_GRACE_MS = 1500L

        private fun setWanted(context: Context, value: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_WANTED, value).apply()
        }

        fun wanted(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_WANTED, false)

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
