package com.zyntap.app.overlay

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
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zyntap.app.R
import com.zyntap.app.data.AdbLinkStore
import com.zyntap.app.data.DebugLog
import com.zyntap.app.data.WifiAdbMonitor
import com.zyntap.app.data.WifiState
import com.zyntap.app.data.ZynAdbConnectionManager
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var wm: OverlayWindowManager
    @Volatile private var stopping = false
    private var handleView: View? = null
    private var panelView: FrameLayout? = null
    private var panelCard: View? = null
    private var panelContent: View? = null
    private var panelControls: View? = null
    private var cardBg: GradientDrawable? = null
    private var eyeIcon: ImageView? = null
    private var solid = false
    private var solidAmount = 0f
    private var solidAnim: ValueAnimator? = null
    @Volatile private var closing = false
    private var panelAnimating = false
    private var panelClosing = false
    private var panelTransition = 0L
    private val menuGesture = MenuGestureGate()
    private var lastHealthAt = 0L
    private var lastStatusAt = 0L
    private var applyDebugSetting: (() -> Unit)? = null
    private val runtimePrefs by lazy { getSharedPreferences("zyntap_runtime", MODE_PRIVATE) }
    private val runtimeListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "debug_enabled") handler.post { if (!stopping) applyDebugSetting?.invoke() }
    }
    private var handleDragging = false
    private var handleAddedAt = 0L
    private var panelAddedAt = 0L
    private var panelGhost = false
    private var uiDensitySig = 0f
    private var ghostAmt = 0f
    private var ghostAnim: ValueAnimator? = null
    private var panelHidden = false
    private var shownTabIdx = 0

    private lateinit var repo: MainButtonRepo
    private lateinit var circles: MainButtonOverlay
    private var panelParams: WindowManager.LayoutParams? = null
    private var dropdownLayer: DropdownLayer? = null
    private var promptLayer: TextPromptLayer? = null
    private var mainTab: MainButtonsTab? = null
    private var pointsTab: PointsTab? = null
    private var configTab: ConfigTab? = null
    private var debugTab: DebugTab? = null
    private lateinit var configStore: ConfigStore
    private lateinit var crosshairSettings: CrosshairSettings
    private lateinit var crosshair: CrosshairOverlay
    private lateinit var statsSettings: StatsSettings
    private lateinit var stats: StatsBarOverlay
    private var settingsTab: SettingsTab? = null
    private lateinit var pointPicker: PointPickerOverlay
    private lateinit var pointRunner: PointRunner
    private var inputActive = false
    private var editLock = false
    private var editDim = 0f
    private var editDimAnim: ValueAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val displayManager by lazy { getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager }
    private var geometryKey = ""
    private val displayRefresh = Runnable { applyDisplayGeometry() }
    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != android.view.Display.DEFAULT_DISPLAY || stopping) return
            handler.removeCallbacks(displayRefresh)
            handler.postDelayed(displayRefresh, 80L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        UiAnim.init(this)
        runtimePrefs.registerOnSharedPreferenceChangeListener(runtimeListener)
        DebugLog.i("Dịch vụ", "Menu nổi bắt đầu chạy")
        running = true
        WifiState.start(this)
        com.zyntap.app.data.ConnectionWatcher.start(this)
        wm = OverlayWindowManager(getSystemService(Context.WINDOW_SERVICE) as WindowManager, canRecover = { canRevive() })

        runCatching { CrosshairOverlay.sweepOrphans() }
        runCatching { StatsBarOverlay.sweepOrphans() }
        uiDensitySig = UiScale.density(resources)
        repo = MainButtonRepo(this)
        repo.bindLegacyCoordinates(screenW, screenH, displayRotation())
        configStore = ConfigStore(this)
        circles = MainButtonOverlay(
            ctx = this,
            wm = wm,
            repo = repo,
            screenBounds = { realBounds },
            newParams = { w, h, f -> overlayParams(w, h, f) },
        )
        crosshairSettings = CrosshairSettings(this)
        crosshair = CrosshairOverlay(this, wm, crosshairSettings, { realBounds }) { w, h, f -> overlayParams(w, h, f) }
        statsSettings = StatsSettings(this)
        stats = StatsBarOverlay(this, wm, statsSettings, { realBounds }, { w, h, f -> overlayParams(w, h, f) })
        stats.onDrag = { dragging -> setPanelGhost(dragging) }
        pointRunner = PointRunner(this, { Rect(realBounds) }, { displayRotation() }, { x, y ->
            panelBlocksInput() || handleContainsPoint(x, y)
        }) { text -> android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show() }
        pointPicker = PointPickerOverlay(this, wm, { realBounds },
            { w, h, f -> overlayParams(w, h, f) },
            { picking ->
                if (stopping) return@PointPickerOverlay
                if (picking) { pointRunner.stopAll(); circles.cancelTouches() }

                editLock = picking
                if (!picking) setPanelGhost(false)
                circles.fadeAll(if (picking) 0f else 1f, 100)
                if (::stats.isInitialized) stats.fadeTo(!picking, 100)
            },
            { dragging -> setPanelGhost(dragging) },
        )
        circles.onDrag = { dragging -> setPanelGhost(dragging) }
        circles.onAction = { button, down ->
            if (!stopping && !closing && !pointPicker.isShowing && !editLock && !inputActive) pointRunner.touch(button, down)
            else { pointRunner.cancelTouch(button.id); false }
        }
        circles.onCancel = { id -> pointRunner.cancelTouch(id) }
        geometryKey = displayGeometryKey()
        displayManager.registerDisplayListener(displayListener, handler)
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (stopping) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_HIDE -> closePanel()
            ACTION_STOP -> {
                if (wanted(this)) return START_STICKY
                shutdownOverlays()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {

                if (!wanted(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                ensureHandle()
                circles.ensureAll()
                crosshair.ensure()
                stats.ensure()
                startWatchdog()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!stopping && wanted(this)) {
            DebugLog.w("Dịch vụ", "ZynTap bị vuốt khỏi đa nhiệm -> giữ menu nổi chạy")
            runCatching { restoreIfWanted(applicationContext) }
        }
    }

    override fun onDestroy() {

        shutdownOverlays()
        runtimePrefs.unregisterOnSharedPreferenceChangeListener(runtimeListener)
        running = false
        super.onDestroy()
    }

    private fun shutdownOverlays() {
        if (stopping) return
        stopping = true
        displayManager.unregisterDisplayListener(displayListener)
        closing = true
        handler.removeCallbacksAndMessages(null)

        safely("chủ cửa sổ") { if (::wm.isInitialized) wm.close() }
        safely("hiệu ứng") {
            solidAnim?.cancel()
            editDimAnim?.cancel()
            ghostAnim?.cancel()
        }
        safely("ô chọn điểm") { if (::pointPicker.isInitialized) pointPicker.dispose() }
        safely("menu + tay nắm") { removeViews() }
        safely("nút main") { if (::circles.isInitialized) circles.close() }
        safely("tâm ảo") { if (::crosshair.isInitialized) crosshair.close() }
        safely("thanh thông số") { if (::stats.isInitialized) stats.close() }
        safely("bộ chạy điểm nhấn") { if (::pointRunner.isInitialized) pointRunner.close() }
        running = false
        if (::wm.isInitialized && wm.pendingCount > 0) {
            DebugLog.w("Dọn dẹp", "Đã chặn dựng UI; còn ${wm.pendingCount} cửa sổ đang chờ thử gỡ lại")
        } else {
            DebugLog.i("Dịch vụ", "Đã gỡ toàn bộ cửa sổ nổi; đã chặn callback dựng lại UI")
        }
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { DebugLog.e("Dọn dẹp", "Lỗi khi gỡ $what", t) }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (stopping || closing) return
            val now = SystemClock.uptimeMillis()
            safely("khôi phục cửa sổ") { wm.recoverDetached() }
            if (now - lastHealthAt >= HEALTH_MS) {
                lastHealthAt = now
                runCatching { checkHealth() }.onFailure { DebugLog.e("Watchdog", "checkHealth lỗi", it) }
            }
            if (::stats.isInitialized) stats.pulse()
            if (panelView != null && !panelHidden && now - lastStatusAt >= STATUS_MS) {
                lastStatusAt = now
                runCatching { refreshStatus() }
            }
            if (!stopping && !closing) handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun startWatchdog() {
        if (stopping || closing) return
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    private fun interactionBusy(): Boolean = stopping || closing || handleDragging || menuGesture.tracking ||
        panelAnimating || panelClosing || panelGhost || editLock || inputActive ||
        (::pointPicker.isInitialized && pointPicker.isShowing) ||
        (::circles.isInitialized && circles.interacting) ||
        (panelView?.isAttachedToWindow == true && SystemClock.uptimeMillis() < interactionUntil)

    private val power by lazy { getSystemService(Context.POWER_SERVICE) as android.os.PowerManager }

    private fun canRevive(): Boolean =
        !stopping && !closing && wanted(this) && power.isInteractive && OverlayPermission.granted(this)

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::wm.isInitialized) wm.requestSweep(250L)
    }

    private var interactionUntil = 0L
    private fun noteInteraction(event: MotionEvent) {
        val ended = event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
        interactionUntil = SystemClock.uptimeMillis() + if (ended) 400L else INTERACTION_LEASE_MS
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = wm.defaultDisplay.rotation

    private fun checkHealth() {
        if (stopping || closing || !wanted(this)) return
        safely("tâm ảo") { crosshair.ensure() }
        safely("thanh thông số") { stats.ensure() }
        safely("nút main") { circles.ensureAll() }
        if (interactionBusy()) return
        safely("tay nắm") {
            val h = handleView
            if (h == null) {
                ensureHandle()
            } else {
                val now = SystemClock.uptimeMillis()
                if (now - handleAddedAt > ATTACH_GRACE_MS && !h.isAttachedToWindow && !wm.owns(h)) {
                    runCatching { wm.removeViewImmediate(h) }
                    handleView = null
                    handleParams = null
                    ensureHandle()
                } else if (!handleDragging && handleParams?.let { abs(it.y - placeHandleY(savedHandleY())) > dp(2) } == true) {
                    relayoutHandle()
                } else if (h.isAttachedToWindow && (panelView == null || panelHidden) && !panelAnimating && !handleDragging) {
                    if (h.alpha != 1f) h.alpha = 1f
                    if (h.visibility != View.VISIBLE) h.visibility = View.VISIBLE
                }
            }
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
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("ZynTap đang chạy")
            .setContentText("UI nổi đang bật · Chạm để mở công tắc ZynTap")
            .setContentIntent(openIntent)
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
        }.onFailure { DebugLog.e("Dịch vụ", "Không chạy được dịch vụ nền (thông báo)", it) }
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

    private fun cutoutRects(): List<Rect> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        val out = ArrayList<Rect>()
        fun add(c: DisplayCutout?) { c?.boundingRects?.forEach { if (!it.isEmpty && out.none { o -> o == it }) out.add(Rect(it)) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                add(dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.cutout)
            }
            runCatching { add(wm.currentWindowMetrics.windowInsets.displayCutout) }
        }
        if (out.isEmpty()) {

            val c = runCatching { handleView?.rootWindowInsets?.displayCutout }.getOrNull()
            val ox = handleParams?.x ?: 0
            val oy = handleParams?.y ?: 0
            c?.boundingRects?.forEach { if (!it.isEmpty) out.add(Rect(it).apply { offset(ox, oy) }) }
        }
        return out
    }

    private fun displayCutoutCompat(): DisplayCutout? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
                dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.cutout?.let { return it }
            }
            runCatching { wm.currentWindowMetrics.windowInsets.displayCutout?.let { return it } }
        }
        return handleView?.rootWindowInsets?.displayCutout
    }

    private val safeLeft: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) displayCutoutCompat()?.safeInsetLeft ?: 0 else 0

    private val cardStart get() = maxOf(dp(PANEL_MARGIN_DP), safeLeft)
    private val travel get() = cardW + cardStart

    private val cardW get(): Int {
        val avail = screenW - cardStart - dp(PANEL_MARGIN_DP)
        val custom = prefs.getInt(KEY_PANEL_W, 0)
        return if (custom > 0) minOf(dp(custom), avail)
        else minOf(dp(PANEL_W_DP), avail, (screenW * PANEL_MAX_W_FRAC).toInt())
    }
    private val cardH get(): Int {
        val avail = screenH - dp(PANEL_MARGIN_DP) * 2
        val custom = prefs.getInt(KEY_PANEL_H, 0)
        return if (custom > 0) minOf(dp(custom), avail)
        else minOf(dp(PANEL_H_DP), avail, (screenH * PANEL_MAX_H_FRAC).toInt())
    }

    private fun panelSizeInfo(): PanelSizeInfo {
        val d = UiScale.density(resources)
        val maxW = ((screenW - cardStart - dp(PANEL_MARGIN_DP)) / d).toInt()
        val maxH = ((screenH - dp(PANEL_MARGIN_DP) * 2) / d).toInt()
        return PanelSizeInfo(
            curW = (cardW / d).toInt(), curH = (cardH / d).toInt(),
            minW = minOf(PANEL_MIN_W_DP, maxW), minH = minOf(PANEL_MIN_H_DP, maxH),
            maxW = maxW, maxH = maxH,
        )
    }

    private fun rebuildPanel() {
        if (stopping || closing) return
        val tab = shownTabIdx
        prefs.edit().putInt(KEY_TAB, tab).apply()
        removePanel()
        ensurePanel()
        panelCard?.translationX = 0f
        panelView?.alpha = 1f
        handleView?.alpha = 0f
    }
    private val cardMargin get() = dp(PANEL_MARGIN_DP)

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
        val rects = cutoutRects()
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

    private fun displayGeometryKey() = "$screenW:$screenH:${displayRotation()}:${UiScale.density(resources)}"

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.removeCallbacks(displayRefresh)
        handler.postDelayed(displayRefresh, 80L)
    }

    private fun applyDisplayGeometry() {
        if (stopping || closing) return
        val key = displayGeometryKey()
        if (geometryKey == key) return
        geometryKey = key
        interactionUntil = 0L
        pointRunner.stopAll()
        circles.cancelTouches()

        removePanel()
        val density = UiScale.density(resources)
        if (kotlin.math.abs(density - uiDensitySig) > 0.001f) {
            uiDensitySig = density
            handleView?.let { runCatching { wm.removeViewImmediate(it) } }
            handleView = null
            handleParams = null
            circles.removeAll()
            ensureHandle()
            circles.ensureAll()
            crosshair.remove()
            stats.remove()
        }
        handleView?.alpha = 1f
        relayoutDisplayWindows()
        handler.removeCallbacks(rotationRelayout)
        handler.postDelayed(rotationRelayout, 200L)
    }

    private fun relayoutDisplayWindows() {
        relayoutHandle()
        circles.relayoutAll()
        crosshair.ensure()
        stats.ensure()
    }

    private val rotationRelayout = Runnable {
        if (!stopping && !closing) relayoutDisplayWindows()
    }

    private fun ensureHandle() {
        if (stopping || handleView != null) return
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
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                if (!menuGesture.down(SystemClock.uptimeMillis(), panelClosing || panelAnimating || stopping || closing)) return@setOnTouchListener true
            } else if (!menuGesture.tracking) return@setOnTouchListener true
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
                    menuGesture.finish()
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
                    menuGesture.finish()
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
            DebugLog.e("Giao diện", "Không gắn được nút mở menu (tay nắm)", it)

            handleView = null
            handleParams = null
            return
        }

        handle.setOnApplyWindowInsetsListener { v, insets ->
            v.post { if (!stopping && !closing) relayoutHandle() }
            insets
        }

        handle.post { relayoutHandle() }
    }

    private fun ensurePanel() {
        if (stopping) return
        if (panelView != null) { revealPanel(); setMenuOpenAll(true); return }
        solid = prefs.getBoolean(KEY_SOLID, false)

        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply {

            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        panelParams = params

        val scrim = PanelRoot(this).apply {
            setBackgroundColor(0x66000000)
            alpha = 0f
        }

        val slop = dp(8)
        var sDownX = 0f
        var sDragging = false
        scrim.setOnTouchListener { _, e ->

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
        val tab2 = PointsTab(this, repo, host, pointPicker, { realBounds },
            { on -> setPanelFocusable(on) }, { pointRunner.stopAll(); circles.cancelTouches() })
        pointsTab = tab2
        val tab3 = ConfigTab(
            this, repo, configStore, host,
            { on -> setPanelFocusable(on) },
            { pointRunner.stopAll(); circles.cancelTouches() },
            { name ->
                repo.bindLegacyCoordinates(screenW, screenH, displayRotation())
                circles.removeAll(); circles.ensureAll()
                handler.post { rebuildPanel() }
                android.widget.Toast.makeText(this, "Đã nạp cấu hình \"$name\"", android.widget.Toast.LENGTH_SHORT).show()
            },
            { panelSizeInfo() },
            { w, h ->
                prefs.edit().putInt(KEY_PANEL_W, w).putInt(KEY_PANEL_H, h).apply()
                handler.post { rebuildPanel() }
                android.widget.Toast.makeText(
                    this,
                    if (w == 0) "Đã trả menu về kích thước mặc định" else "Đã đặt menu $w × $h dp",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            },
        )
        configTab = tab3
        val tab0 = CrosshairTab(this, crosshairSettings) { crosshair.ensure() }
        val tab4 = DebugTab(this) { debugInfo() }
        debugTab = tab4
        val tab5 = SettingsTab(this, statsSettings, dd, stats) { stats.ensure() }
        settingsTab = tab5

        val tabBuilders: List<() -> View> = listOf(
            { tab0.build() }, { tab1.build() }, { tab2.build() },
            { tab3.build() }, { tab4.build() }, { tab5.build() },
        )
        val tabViews = arrayOfNulls<View>(tabBuilders.size)

        val startTab = prefs.getInt(KEY_TAB, 1).coerceIn(0, tabViews.lastIndex).let { if (it == 4 && !DebugLog.enabled) 1 else it }
        val tabContainer = FrameLayout(this)
        fun viewForTab(index: Int): View = tabViews[index] ?: tabBuilders[index]().also { v ->
            tabViews[index] = v
            v.visibility = View.GONE
            tabContainer.addView(v, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        viewForTab(startTab).visibility = View.VISIBLE
        if (startTab == 2) tab2.enter()
        if (startTab == 3) tab3.enter()
        if (startTab == 4) tab4.enter()
        if (startTab == 5) tab5.enter()
        var shownTab = startTab
        shownTabIdx = startTab
        fun showTab(i: Int) {
            if (i == shownTab || (i == 4 && !DebugLog.enabled)) return
            val oldIdx = shownTab
            val old = viewForTab(oldIdx)
            val next = viewForTab(i)
            if (oldIdx == 1) tab1.exitEdit()
            if (oldIdx == 2) tab2.exit()
            if (oldIdx == 3) tab3.exit()
            if (oldIdx == 4) tab4.exit()
            if (oldIdx == 5) tab5.exit()
            dd.dismissNow()
            pr.close()
            if (i == 2) tab2.enter()
            if (i == 3) tab3.enter()
            if (i == 4) tab4.enter()
            if (i == 5) tab5.enter()
            old.animate().cancel(); next.animate().cancel()
            old.visibility = View.GONE
            next.alpha = 1f
            next.visibility = View.VISIBLE
            shownTab = i
            shownTabIdx = i
            prefs.edit().putInt(KEY_TAB, i).apply()
        }

        var sidebar: View? = null
        fun updateSidebar() {
            val previous = sidebar
            val fresh = buildSidebar(shownTab) { index -> showTab(index) }
            if (previous != null) content.removeView(previous)
            sidebar = fresh
            content.addView(fresh, 0, LinearLayout.LayoutParams(dp(SIDEBAR_W_DP), -1))
        }
        applyDebugSetting = {
            if (!DebugLog.enabled && shownTab == 4) showTab(1)
            if (!DebugLog.enabled) debugTab?.exit()
            stopHaloBreath()
            updateSidebar()
            lastStatusKey = -1
            refreshStatus()
        }
        content.addView(
            buildSidebar(startTab) { index -> showTab(index) }.also { sidebar = it },
            LinearLayout.LayoutParams(dp(SIDEBAR_W_DP), LinearLayout.LayoutParams.MATCH_PARENT),
        )
        content.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x001E2B44, 0xFF2A3D63.toInt(), 0xFF2A3D63.toInt(), 0x001E2B44),
            )
        }, LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT))
        content.addView(
            tabContainer,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
        )

        card.addView(
            buildControls(),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            ).apply { topMargin = dp(10); marginEnd = dp(10) },
        )

        card.addView(dd, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(pr, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        panelView = scrim
        panelCard = card
        panelContent = content
        cardBg = bg
        panelAddedAt = SystemClock.uptimeMillis()
        applySolid(if (solid) 1f else 0f)
        setMenuOpenAll(true)
        runCatching { wm.addView(scrim, params) }.onFailure {
            DebugLog.e("Giao diện", "Không gắn được cửa sổ menu", it)
            removePanel()
        }
        stats.raise()
        startStatusTick()
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logoBox = FrameLayout(this)
        logoBox.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setGradientType(GradientDrawable.RADIAL_GRADIENT)
                setGradientRadius(dp(22).toFloat())
                setColors(intArrayOf(0x401E9BFF, 0x001E9BFF))
            }
        }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        logoBox.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo_transparent)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(26), Gravity.CENTER))
        row.addView(logoBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(HEADER_H_DP)))

        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        names.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(0xFFE8EEFA.toInt())
            scaledTextSize(15f)
            setTypeface(typeface, Typeface.BOLD)
        })
        names.addView(TextView(this).apply {
            text = if (ver.isNullOrBlank()) "Macro Pro" else "Macro Pro · v$ver"
            setTextColor(0xFF5F7396.toInt())
            scaledTextSize(10f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(names, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginStart = dp(2) })
        return row
    }

    private val tabNames = listOf("Cài tâm ảo", "Tạo nút chính", "Chỉnh điểm nhấn", "Cài cấu hình", "Debug log", "Cài đặt")

    private val tabOrder = intArrayOf(4, 1, 2, 0, 5, 3)
    private val tabIcons = intArrayOf(
        R.drawable.ic_tab_crosshair, R.drawable.ic_tab_pointer, R.drawable.ic_tab_focus,
        R.drawable.ic_tab_sliders, R.drawable.ic_tab_terminal, R.drawable.ic_tab_settings,
    )

    private fun buildSidebar(initialTab: Int, onSelect: (Int) -> Unit): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            setPadding(dp(6), dp(14), dp(12), dp(12))

            clipToPadding = false
        }
        col.addView(buildHeader())

        col.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))

        val tabGap = dp(4)
        val tabH = ((cardH - dp(132) - tabGap * (tabNames.size - 1)) / tabNames.size).coerceIn(dp(28), dp(42))
        val step = tabH + tabGap

        val box = FrameLayout(this)
        val indicator = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF22457A.toInt(), 0xFF17294A.toInt()),
            ).apply {
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), 0x661E9BFF)
            }

            addView(View(context).apply {
                background = GradientDrawable().apply {
                    setColor(0xFF1E9BFF.toInt())
                    cornerRadius = dp(2).toFloat()
                }
            }, FrameLayout.LayoutParams(dp(3), dp(18), Gravity.START or Gravity.CENTER_VERTICAL)
                .apply { marginStart = dp(4) })
        }
        box.addView(indicator, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, tabH))

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        val colorOff = 0xFF8D9DBA.toInt()
        val colorOn = 0xFFFFFFFF.toInt()
        val tabs = mutableListOf<TextView>()
        val visibleOrder = tabOrder.filter { it != 4 || DebugLog.enabled }
        var current = -1
        fun select(i: Int, animate: Boolean) {
            if (i == current) return
            val target = i * step.toFloat()
            indicator.animate().cancel()
            if (animate) {
                indicator.animate().translationY(target).setDuration(UiAnim.ms(TAB_ANIM_MS))
                    .setInterpolator(TAB_INTERPOLATOR).start()
            } else {
                indicator.translationY = target
            }

            fun paint(t: TextView, c: Int) {
                t.setTextColor(c)
                t.compoundDrawablesRelative[0]?.setTint(c)
            }
            tabs.forEachIndexed { idx, t ->
                val to = if (idx == i) colorOn else colorOff
                if (animate) {
                    android.animation.ValueAnimator.ofObject(
                        android.animation.ArgbEvaluator(), t.currentTextColor, to,
                    ).apply {
                        duration = UiAnim.ms(TAB_ANIM_MS)
                        addUpdateListener { paint(t, it.animatedValue as Int) }
                    }.start()
                } else {
                    paint(t, to)
                }
            }
            current = i
            onSelect(visibleOrder[i])
        }
        visibleOrder.forEachIndexed { i, logical ->
            val name = tabNames[logical]
            val t = TextView(this).apply {
                text = name
                scaledTextSize(13f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(6), 0)
                compoundDrawablePadding = dp(8)
                androidx.core.content.ContextCompat.getDrawable(this@OverlayService, tabIcons[logical])?.mutate()?.let { ic ->
                    ic.setBounds(0, 0, dp(18), dp(18))
                    setCompoundDrawablesRelative(ic, null, null, null)
                }
                setOnClickListener { select(i, animate = true) }
            }
            tabs.add(t)
            list.addView(t, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, tabH,
            ).apply { if (i < tabNames.lastIndex) bottomMargin = tabGap })
        }

        col.addView(box, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(7) })

        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))

        col.addView(buildStatus(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(5); marginEnd = -dp(5) })
        select(visibleOrder.indexOf(initialTab).coerceAtLeast(0), animate = false)
        return col
    }

    private var statusWifiDot: View? = null
    private var statusWifiHalo: View? = null
    private var statusWifiText: TextView? = null
    private var statusLinkDot: View? = null
    private var statusLinkHalo: View? = null
    private var statusLinkText: TextView? = null
    private var wifiBreath: android.animation.ObjectAnimator? = null
    private var linkBreath: android.animation.ObjectAnimator? = null
    private var statusPulsing = false
    private var lastStatusKey = -1

    private fun buildStatus(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = GradientDrawable().apply {
                setColor(0x12FFFFFF)
                cornerRadius = dp(10).toFloat()
                setStroke(1, 0x1AFFFFFF)
            }
        }
        class Row(val view: View, val halo: View, val dot: View, val text: TextView)
        fun row(): Row {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val holder = FrameLayout(this)
            val halo = View(this)
            val dot = View(this)
            holder.addView(halo, FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER))
            holder.addView(dot, FrameLayout.LayoutParams(dp(7), dp(7), Gravity.CENTER))
            val tv = TextView(this).apply {
                scaledTextSize(11.5f); maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            r.addView(holder, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(7) })
            r.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            return Row(r, halo, dot, tv)
        }
        val wifi = row()
        val link = row()
        box.addView(wifi.view)
        box.addView(link.view, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) })
        statusWifiDot = wifi.dot; statusWifiHalo = wifi.halo; statusWifiText = wifi.text
        statusLinkDot = link.dot; statusLinkHalo = link.halo; statusLinkText = link.text
        lastStatusKey = -1
        refreshStatus()
        stopHaloBreath()
        startHaloBreath()
        return box
    }

    private fun refreshStatus() {
        val wifiOn = WifiAdbMonitor.isWifiAdbEnabled(this)
        val wifiNet = WifiState.isConnected(this)
        val paired = AdbLinkStore.isPaired(this)
        val linked = runCatching { ZynAdbConnectionManager.get(applicationContext).isConnected }.getOrDefault(false)

        val key = (if (wifiOn) 1 else 0) or (if (paired) 2 else 0) or (if (linked) 4 else 0) or (if (wifiNet) 8 else 0)
        if (key == lastStatusKey) return
        lastStatusKey = key
        val ok = 0xFF34D399.toInt()
        val warn = 0xFFF5B544.toInt()
        val bad = 0xFFEF5B5B.toInt()
        fun circle(c: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c) }
        fun paint(dot: View?, halo: View?, tv: TextView?, c: Int, label: String, value: String) {
            dot?.background = circle(c)
            halo?.background = circle((c and 0x00FFFFFF) or 0x30000000)
            tv ?: return
            val sp = android.text.SpannableStringBuilder("$label  ")
            sp.setSpan(android.text.style.ForegroundColorSpan(0xFF8D9DBA.toInt()),
                0, sp.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val from = sp.length
            sp.append(value)
            sp.setSpan(android.text.style.ForegroundColorSpan(c),
                from, sp.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(android.text.style.StyleSpan(Typeface.BOLD),
                from, sp.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            tv.text = sp
        }

        paint(statusWifiDot, statusWifiHalo, statusWifiText,
            if (wifiNet) ok else bad, "Wi-Fi", if (wifiNet) "Đã nối" else "Chưa nối")

        val pulse: Boolean
        val (c, t) = when {
            linked -> { pulse = false; ok to "Đã nối" }
            !paired -> { pulse = false; bad to "Chưa ghép" }
            !wifiNet -> { pulse = false; bad to "Mất Wi-Fi" }
            !wifiOn -> { pulse = false; bad to "Tắt gỡ lỗi" }
            else -> { pulse = true; warn to "Nối lại…" }
        }
        paint(statusLinkDot, statusLinkHalo, statusLinkText, c, "ADB", t)
        setStatusPulse(pulse)
    }

    private fun setStatusPulse(on: Boolean) {
        statusPulsing = on
        linkBreath?.duration = if (on) BREATH_FAST_MS else BREATH_MS
    }

    private fun startHaloBreath() { stopHaloBreath() }

    private fun stopHaloBreath() {
        wifiBreath?.cancel(); wifiBreath = null
        linkBreath?.cancel(); linkBreath = null
        for (h in listOf(statusWifiHalo, statusLinkHalo)) { h?.alpha = 1f; h?.scaleX = 1f; h?.scaleY = 1f }
    }

    private fun startStatusTick() {
        lastStatusAt = SystemClock.uptimeMillis()
        refreshStatus()
    }

    private fun buildControls(): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val eye = iconButton(if (solid) R.drawable.ic_eye_off else R.drawable.ic_eye) { toggleSolid() }
        eyeIcon = eye
        val minus = iconButton(R.drawable.ic_minus) { closePanel() }
        val close = iconButton(R.drawable.ic_x) { closePanel() }.apply { contentDescription = "Thu menu · Tắt UI trong app ZynTap" }
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
            duration = UiAnim.ms(260L)
            interpolator = TAB_INTERPOLATOR
            addUpdateListener { applySolid(it.animatedValue as Float) }
            start()
        }

        eyeIcon?.let { icon ->
            icon.animate().cancel()
            icon.animate().scaleX(0.6f).scaleY(0.6f).alpha(0.3f).setDuration(UiAnim.ms(90L)).withEndAction {
                icon.setImageResource(if (solid) R.drawable.ic_eye_off else R.drawable.ic_eye)
                icon.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(UiAnim.ms(180L))
                    .setInterpolator(OvershootInterpolator(2f)).start()
            }.start()
        }
    }

    private fun applySolid(t: Float) {
        solidAmount = t
        cardBg?.setColor(Color.argb((CARD_ALPHA + (255 - CARD_ALPHA) * t).toInt(), 0x0D, 0x15, 0x24))
        panelView?.setBackgroundColor(Color.argb((scrimAlpha(t) * (1f - ghostAmt)).toInt().coerceIn(0, 255), 0, 0, 0))
    }

    private fun setPanelGhost(on: Boolean) {
        if (stopping) return
        if (panelGhost == on) return
        panelGhost = on
        if (stopping || closing) return
        panelControls?.animate()?.cancel()

        ghostAnim?.cancel()
        ghostAnim = ValueAnimator.ofFloat(ghostAmt, if (on) 1f else 0f).apply {
            duration = UiAnim.ms(if (on) 220L else 280L)
            interpolator = if (on) AccelerateDecelerateInterpolator() else TAB_INTERPOLATOR
            addUpdateListener {
                ghostAmt = it.animatedValue as Float
                applyGhost()
            }
            start()
        }
    }

    private fun applyGhost() {
        val g = ghostAmt
        panelCard?.let {
            it.alpha = 1f - g
            val sc = 1f - GHOST_SHRINK * g
            it.pivotX = it.width * 0.5f
            it.pivotY = it.height * 0.5f
            it.scaleX = sc; it.scaleY = sc
        }
        applySolid(solidAmount)
    }

    private fun resetGhost() {
        ghostAnim?.cancel()
        ghostAmt = 0f
        panelGhost = false
        panelCard?.let { it.alpha = 1f; it.scaleX = 1f; it.scaleY = 1f }
    }

    private fun scrimAlpha(t: Float): Int =
        ((SCRIM_ALPHA + (SCRIM_SOLID_ALPHA - SCRIM_ALPHA) * t) * (1f - EDIT_DIM_REDUCE * editDim)).toInt()

    private fun setEditing(on: Boolean) {
        if (stopping) return
        if (on) { pointRunner.stopAll(); circles.cancelTouches() }
        if (editLock == on) return
        editLock = on
        editDimAnim?.cancel()
        editDimAnim = ValueAnimator.ofFloat(editDim, if (on) 1f else 0f).apply {
            duration = UiAnim.ms(260L)
            interpolator = TAB_INTERPOLATOR
            addUpdateListener {
                editDim = it.animatedValue as Float
                if (!closing) applySolid(solidAmount)
            }
            start()
        }
    }

    private fun panelBlocksInput(): Boolean = panelView != null && !panelHidden

    private fun handleContainsPoint(x: Int, y: Int): Boolean {
        val handle = handleView ?: return false
        val location = IntArray(2)
        handle.getLocationOnScreen(location)
        return x >= location[0] && x < location[0] + handle.width &&
            y >= location[1] && y < location[1] + handle.height
    }

    private fun setPanelFocusable(on: Boolean) {
        inputActive = on
        if (on) pointRunner.stopAll()
        val p = panelParams ?: return
        val v = panelView ?: return
        p.flags = if (on) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        runCatching { wm.updateViewLayout(v, p) }
    }

    private inner class PanelRoot(context: Context) : FrameLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            noteInteraction(event)
            return super.dispatchTouchEvent(event)
        }

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
            if (event.keyCode == KeyEvent.KEYCODE_BACK && inputActive) {
                if (event.action == KeyEvent.ACTION_UP) pointsTab?.leaveInput()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    private fun placeholderTab(name: String): View = TextView(this).apply {
        text = "$name\nđang phát triển"
        setTextColor(0xFF8D9DBA.toInt())
        scaledTextSize(15f)
        gravity = Gravity.CENTER
    }

    private fun beginPanelDrag() {
        if (stopping || closing) return
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

    private fun updatePanelDrag(progress: Float) {
        val card = panelCard ?: return
        card.translationX = hiddenX * (1f - progress)
        panelView?.alpha = progress
        handleView?.alpha = 1f - progress
    }

    private fun openPanel() {
        if (stopping || closing || panelClosing) return
        val transition = ++panelTransition
        ensurePanel()
        val card = panelCard ?: return
        val scrim = panelView ?: return
        card.animate().cancel(); scrim.animate().cancel()
        panelAnimating = true

        scrim.post {
            if (stopping || closing || panelClosing || panelTransition != transition || panelView !== scrim) return@post
            card.animate().translationX(0f).setDuration(UiAnim.ms(PANEL_ANIM_MS))
                .setInterpolator(DecelerateInterpolator(1.6f))
                .withEndAction { panelAnimating = false }.start()
            scrim.animate().alpha(1f).setDuration(UiAnim.ms(PANEL_ANIM_MS)).start()
            handleView?.animate()?.alpha(0f)?.setDuration(UiAnim.ms(PANEL_ANIM_MS))?.start()
        }
    }

    private fun closePanel() {
        if (stopping || closing || panelClosing) return
        panelTransition++
        menuGesture.blockUntil(SystemClock.uptimeMillis() + UiAnim.ms(PANEL_ANIM_MS) + 350L)
        val card = panelCard
        val scrim = panelView
        if (card == null || scrim == null || panelHidden) {
            handleView?.alpha = 1f
            return
        }
        panelClosing = true
        settingsTab?.exit()
        debugTab?.exit()
        setMenuOpenAll(false)
        mainTab?.exitEdit()
        pointsTab?.exit()
        configTab?.exit()
        dropdownLayer?.dismissNow()
        promptLayer?.close()
        card.animate().cancel(); scrim.animate().cancel()
        panelAnimating = true
        card.animate().translationX(hiddenX).setDuration(UiAnim.ms(PANEL_ANIM_MS))
            .setInterpolator(DecelerateInterpolator(1.4f))
            .withEndAction {
                panelAnimating = false

                if (panelCard === card && card.translationX <= hiddenX + 1f) hidePanel()
                panelClosing = false
            }.start()
        scrim.animate().alpha(0f).setDuration(UiAnim.ms(PANEL_ANIM_MS)).start()
        handleView?.animate()?.alpha(1f)?.setDuration(UiAnim.ms(PANEL_ANIM_MS))?.start()
    }

    private fun resetPanelState() {
        interactionUntil = 0L
        pointsTab?.exit()
        configTab?.exit()
        pointPicker.close()
        promptLayer?.close()
        dropdownLayer?.dismissNow()
        inputActive = false
        resetGhost()
        solidAnim?.cancel()
        editDimAnim?.cancel()
        editDim = 0f
        editLock = false
        if (::circles.isInitialized) circles.setEditing(null)
    }

    private fun hidePanel() {
        val v = panelView ?: return
        val p = panelParams
        resetPanelState()
        panelHidden = true
        debugTab?.exit()
        settingsTab?.exit()
        if (::circles.isInitialized) setMenuOpenAll(false)
        stopHaloBreath()
        v.visibility = View.INVISIBLE
        if (p != null) {

            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

            p.alpha = 0f
            runCatching { wm.updateViewLayout(v, p) }
        }
    }

    private fun revealPanel() {
        if (!panelHidden) return
        val v = panelView ?: return
        val p = panelParams
        panelHidden = false
        if (p != null) {
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            p.alpha = 1f
            runCatching { wm.updateViewLayout(v, p) }
        }
        v.visibility = View.VISIBLE
        applySolid(solidAmount)
        startStatusTick()
        startHaloBreath()
        if (shownTabIdx == 2) pointsTab?.enter()
        if (shownTabIdx == 3) configTab?.enter()
        if (shownTabIdx == 4) debugTab?.enter()
        if (shownTabIdx == 5) settingsTab?.enter()
    }

    private fun removePanel() {
        panelTransition++
        panelClosing = false
        panelAnimating = false
        applyDebugSetting = null
        stopHaloBreath(); statusPulsing = false
        statusWifiDot = null; statusWifiHalo = null; statusWifiText = null
        statusLinkDot = null; statusLinkHalo = null; statusLinkText = null
        lastStatusKey = -1
        panelHidden = false
        if (::circles.isInitialized) setMenuOpenAll(false)
        resetGhost()
        pointsTab?.exit()
        configTab?.exit()
        debugTab?.dispose()
        debugTab = null
        settingsTab?.dispose()
        settingsTab = null
        pointPicker.close()
        promptLayer?.close()
        inputActive = false
        solidAnim?.cancel()
        editDimAnim?.cancel()
        editDim = 0f
        editLock = false
        if (::circles.isInitialized) circles.setEditing(null)
        panelView?.let {
            it.animate().setListener(null).withEndAction(null).cancel()
            runCatching { wm.removeViewImmediate(it) }
        }
        panelParams = null
        dropdownLayer = null
        promptLayer = null
        mainTab = null
        pointsTab = null
        configTab = null
        panelView = null
        panelCard = null
        panelContent = null
        panelControls = null
        cardBg = null
        eyeIcon = null
    }

    private fun debugInfo(): String {
        val ver = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        val wifiOn = runCatching { WifiAdbMonitor.isWifiAdbEnabled(this) }.getOrDefault(false)
        val wifiNet = runCatching { WifiState.isConnected(this) }.getOrDefault(false)
        val mobile = runCatching { WifiState.hasMobile(this) }.getOrDefault(false)
        val paired = runCatching { AdbLinkStore.isPaired(this) }.getOrDefault(false)
        val linked = runCatching { ZynAdbConnectionManager.get(applicationContext).isConnected }.getOrDefault(false)
        val w = TouchWatcher.current
        fun yn(b: Boolean) = if (b) "có" else "không"
        return buildString {
            append("ZynTap $ver · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Wi-Fi đã kết nối: ${yn(wifiNet)} · Dữ liệu di động: ${yn(mobile)}\n")
            append("Gỡ lỗi Wi-Fi bật: ${yn(wifiOn)} · Đã ghép nối: ${yn(paired)} · ADB đang nối: ${yn(linked)}\n")
            append("Helper bơm cảm ứng: ${if (w == null) "chưa chạy" else if (w.proxyReady) "sẵn sàng (bơm được)" else "đang chạy nhưng chưa bơm được (click bị bỏ, không có input tap)"}\n")
            append("Vòng helper: ${w?.stage ?: "chưa chạy"}\n")
            append("Quyền vẽ đè: ${yn(OverlayPermission.granted(this@OverlayService))}\n")
            append("Tâm ảo: ${if (::crosshair.isInitialized) crosshair.state() else "?"}\n")
            append("Thanh thông số: ${if (::stats.isInitialized) stats.state() else "?"}\n")
            append("UI nổi: điều khiển thủ công · Không theo dõi ứng dụng")
        }
    }

    private fun removeViews() {
        handleView?.let { runCatching { wm.removeViewImmediate(it) } }
        handleView = null
        handleParams = null
        removePanel()
        if (::circles.isInitialized) circles.removeAll()
        if (::crosshair.isInitialized) crosshair.remove()
        if (::stats.isInitialized) stats.remove()
    }

    private fun dp(v: Int): Int = (v * UiScale.density(resources)).toInt()

    private fun setMenuOpenAll(open: Boolean) {
        circles.setMenuOpen(open)
        if (::stats.isInitialized) stats.setDraggable(open)
    }

    companion object {
        const val ACTION_SHOW = "com.zyntap.app.overlay.SHOW"
        const val ACTION_HIDE = "com.zyntap.app.overlay.HIDE"
        const val ACTION_STOP = "com.zyntap.app.overlay.STOP"

        @Volatile var running = false
            private set

        private const val NOTIF_ID = 1001
        private const val GHOST_SHRINK = 0.06f
        private const val PANEL_MAX_W_FRAC = 0.94f
        private const val PANEL_MAX_H_FRAC = 0.80f
        private const val KEY_PANEL_W = "panel_w_dp"
        private const val KEY_PANEL_H = "panel_h_dp"
        private const val PANEL_MIN_W_DP = 360
        private const val PANEL_MIN_H_DP = 260
        private const val PANEL_W_DP = 620
        private const val PANEL_H_DP = 440
        private const val PANEL_MARGIN_DP = 8
        private const val SIDEBAR_W_DP = 170
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
        private const val KEY_TAB = "active_tab_v2"

        private const val CARD_ALPHA = 0xF2
        private const val SCRIM_ALPHA = 0x66
        private const val EDIT_DIM_REDUCE = 0.7f
        private const val SCRIM_SOLID_ALPHA = 0x66

        private const val STATUS_MS = 1500L
        private const val BREATH_MS = 1600L
        private const val BREATH_FAST_MS = 800L
        private const val WATCHDOG_MS = 1000L
        private const val HEALTH_MS = 5000L
        private const val INTERACTION_LEASE_MS = 5000L
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

        fun setEnabled(context: Context, enabled: Boolean): Boolean {
            if (enabled && !OverlayPermission.granted(context)) return false
            val old = wanted(context)
            setWanted(context, enabled)
            return runCatching {
                if (enabled) start(context)
                else context.stopService(Intent(context, OverlayService::class.java))
                true
            }.getOrElse {
                setWanted(context, old)
                DebugLog.e("UI nổi", "Không đổi được trạng thái dịch vụ", it)
                false
            }
        }

        private fun start(context: Context) {
            if (!wanted(context)) return
            val intent = Intent(context, OverlayService::class.java).setAction(ACTION_SHOW)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
