package com.zyntap.app.overlay

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.zyntap.app.data.DebugLog
import com.zyntap.app.data.ZynAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

// ============================================================================
//  Thanh thông số nổi: FPS / Pin / Giờ / Dữ liệu mạng.
//  - Menu đóng/thu: cửa sổ CHẠM XUYÊN, nhỏ gọn, không che thao tác.
//  - Menu đang mở: kéo thanh để đổi vị trí (menu mờ đi khi đang kéo, như kéo nút main).
// ============================================================================

/** Các mục có thể hiện trên thanh. Thứ tự ở đây = thứ tự trên thanh. */
enum class StatKind(val key: String, val title: String, val desc: String) {
    FPS("fps", "FPS", "Ước tính nhịp khung hình màn hình, lấy mẫu mỗi 0,5 giây; không phải FPS render riêng của game."),
    BATTERY("battery", "Pin", "Phần trăm pin; đổi màu khi đang sạc hoặc pin yếu."),
    TIME("time", "Giờ", "Giờ hiện tại của máy."),
    NET("net", "Dữ liệu mạng", "Tốc độ tải xuống / tải lên theo thời gian thực."),
}

/** Cấu hình lưu bền: mục nào đang bật + vị trí thanh (tỉ lệ 0..1 trên phần màn hình còn trống). */
class StatsSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("zyntap_stats", Context.MODE_PRIVATE)

    fun isOn(k: StatKind): Boolean = prefs.getBoolean("on_" + k.key, false)

    fun setOn(k: StatKind, v: Boolean) {
        prefs.edit().putBoolean("on_" + k.key, v).apply()
    }

    /** Có mục nào được chọn hiển thị không (bất kể công tắc tổng). */
    fun anyItem(): Boolean = StatKind.values().any { isOn(it) }

    /**
     * Công tắc TỔNG "Hiện thanh trạng thái". Bản cũ chưa có khóa này: nếu trước đó đã bật mục nào thì coi như đang bật.
     */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", anyItem())
        set(v) {
            prefs.edit().putBoolean("enabled", v).apply()
        }

    /** Thanh thật sự hiện trên màn hình: công tắc tổng bật VÀ có ít nhất 1 mục. */
    fun anyOn(): Boolean = enabled && anyItem()

    var posX: Float
        get() = prefs.getFloat("pos_x", 0.5f).coerceIn(0f, 1f)
        set(v) {
            prefs.edit().putFloat("pos_x", v.coerceIn(0f, 1f)).apply()
        }

    var posY: Float
        get() = prefs.getFloat("pos_y", 0f).coerceIn(0f, 1f)
        set(v) {
            prefs.edit().putFloat("pos_y", v.coerceIn(0f, 1f)).apply()
        }
}

/** Số liệu một nhịp cập nhật. -1 = chưa có. */
internal data class StatSnapshot(
    val fps: Int,
    val batteryPct: Int,
    val charging: Boolean,
    val time: String,
    val rxBps: Long,
    val txBps: Long,
)

private val C_OK = 0xFF34D399.toInt()
private val C_WARN = 0xFFF5B544.toInt()
private val C_BAD = 0xFFFF7A7A.toInt()
private val C_CYAN = 0xFF19D3FF.toInt()
private val C_FG = 0xFFE8EEFA.toInt()
private val C_MUTED = 0xFF8D9DBA.toInt()

/** Dạng gọn (1.2M / 340K / 0K) để thanh không phình khi bật nhiều mục. */
private fun fmtRate(b: Long): String = when {
    b < 0L -> "--"
    b >= 10_000_000L -> String.format(Locale.US, "%dM", b / 1_000_000L)
    b >= 1_000_000L -> String.format(Locale.US, "%.1fM", b / 1_000_000.0)
    else -> String.format(Locale.US, "%dK", b / 1_000L)
}

// ---- biểu tượng pin tự vẽ -------------------------------------------------------------------

internal class BatteryGlyph(context: Context) : View(context) {
    private val d = UiScale.density(context.resources)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val body = RectF()
    private val inner = RectF()
    private val nub = RectF()
    private val bolt = Path()

    var pct: Int = 100
        set(v) { if (v != field) { field = v; invalidate() } }
    var charging: Boolean = false
        set(v) { if (v != field) { field = v; invalidate() } }
    var tint: Int = C_OK
        set(v) { if (v != field) { field = v; invalidate() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((20f * d).toInt(), (11f * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val nubW = 2f * d
        val sw = 1.3f * d
        val bodyR = w - nubW
        body.set(sw / 2f, sw / 2f, bodyR - sw / 2f, h - sw / 2f)
        line.strokeWidth = sw
        line.color = (tint and 0x00FFFFFF) or (0xC8 shl 24)
        canvas.drawRoundRect(body, 2.6f * d, 2.6f * d, line)

        fill.color = tint
        nub.set(bodyR + 0.4f * d, h * 0.3f, w, h * 0.7f)
        canvas.drawRoundRect(nub, 1f * d, 1f * d, fill)

        val pad = sw * 1.7f
        inner.set(pad, pad, bodyR - pad, h - pad)
        val fw = inner.width() * pct.coerceIn(0, 100) / 100f
        if (fw > 0.5f) {
            canvas.drawRoundRect(inner.left, inner.top, inner.left + fw, inner.bottom, 1.2f * d, 1.2f * d, fill)
        }
        if (charging) {
            val cx = bodyR / 2f
            val cy = h / 2f
            bolt.reset()
            bolt.moveTo(cx + 1.3f * d, cy - 3.6f * d)
            bolt.lineTo(cx - 2.0f * d, cy + 0.7f * d)
            bolt.lineTo(cx - 0.2f * d, cy + 0.7f * d)
            bolt.lineTo(cx - 1.3f * d, cy + 3.6f * d)
            bolt.lineTo(cx + 2.0f * d, cy - 0.9f * d)
            bolt.lineTo(cx + 0.2f * d, cy - 0.9f * d)
            bolt.close()
            fill.color = 0xFF06101C.toInt()
            canvas.drawPath(bolt, fill)
        }
    }
}

/** Tay nắm 2x3 chấm: chỉ hiện khi menu mở, báo hiệu thanh kéo được. */
internal class GripGlyph(context: Context) : View(context) {
    private val d = UiScale.density(context.resources)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C_CYAN; style = Paint.Style.FILL }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((9f * d).toInt(), (14f * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val r = 1.3f * d
        for (cx in 0..1) for (cy in 0..2) {
            canvas.drawCircle(r + cx * 5f * d + 0.5f * d, r + cy * 5.2f * d + 0.5f * d, r, p)
        }
    }
}

// ---- thanh thông số (dùng cho cả cửa sổ nổi và ô xem trước trong tab Cài đặt) ----------------------

internal class StatsBarView(ctx: Context, private val settings: StatsSettings) : LinearLayout(ctx) {
    private val d = UiScale.density(ctx.resources)
    private fun dp(n: Float): Int = (n * d).toInt()

    /** Ô xem trước: khi chưa bật mục nào thì hiện dòng nhắc thay vì trống. */
    var showEmptyHint = false

    /** Đang ở chế độ kéo (menu mở): viền sáng + tay nắm. */
    var editing = false
        set(v) {
            if (v == field) return
            field = v
            paintBg()
            refreshKinds()
        }

    private val bg = GradientDrawable()

    private val fpsLabel = TextView(ctx).apply {
        text = "FPS"; scaledTextSize(8.5f); setTextColor(C_MUTED)
        setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.06f
    }
    private val fpsValue = TextView(ctx).apply {
        text = "--"; scaledTextSize(12f); setTextColor(C_MUTED)
        setTypeface(typeface, Typeface.BOLD); gravity = Gravity.START
        minWidth = (paint.measureText("000") + 1f).toInt()
    }
    private val batGlyph = BatteryGlyph(ctx)
    private val batText = TextView(ctx).apply {
        text = "--%"; scaledTextSize(13f); setTextColor(C_FG)
        setTypeface(typeface, Typeface.BOLD)
        minWidth = (paint.measureText("100%") + 1f).toInt()
    }
    private val timeText = TextView(ctx).apply {
        text = "--:--"; scaledTextSize(13.5f); setTextColor(C_FG)
        setTypeface(typeface, Typeface.BOLD)
    }
    private val netDown = TextView(ctx).apply {
        text = "↓--"; scaledTextSize(11f); setTextColor(C_CYAN)
        setTypeface(typeface, Typeface.BOLD); maxLines = 1
        minWidth = (paint.measureText("↓88.8M") + 1f).toInt()
    }
    private val netUp = TextView(ctx).apply {
        text = "↑--"; scaledTextSize(11f); setTextColor(C_MUTED)
        setTypeface(typeface, Typeface.BOLD); maxLines = 1
        minWidth = (paint.measureText("↑88.8M") + 1f).toInt()
    }
    private val grip = GripGlyph(ctx)
    private val hint = TextView(ctx).apply {
        text = "Chưa bật mục nào"; scaledTextSize(12f); setTextColor(C_MUTED)
    }

    private val cells = HashMap<StatKind, View>()
    private var lastSig = ""

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(13f), dp(6f), dp(13f), dp(6f))
        cells[StatKind.FPS] = row(fpsLabel to dp(5f), fpsValue to 0)
        cells[StatKind.BATTERY] = row(batGlyph to dp(6f), batText to 0)
        cells[StatKind.TIME] = row(timeText to 0)
        cells[StatKind.NET] = row(netDown to dp(5f), netUp to 0)
        paintBg()
        refreshKinds()
    }

    /** Trần bề rộng (px) — thanh không bao giờ rộng hơn vùng cho phép. 0 = không giới hạn. */
    var maxBarWidth = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = maxBarWidth
        if (cap > 0) {
            val mode = MeasureSpec.getMode(widthMeasureSpec)
            val size = MeasureSpec.getSize(widthMeasureSpec)
            val limit = if (mode == MeasureSpec.UNSPECIFIED) cap else minOf(size, cap)
            super.onMeasure(MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST), heightMeasureSpec)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun row(vararg items: Pair<View, Int>): View {
        val r = LinearLayout(context)
        r.orientation = HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        for ((v, gapEnd) in items) {
            val lp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = gapEnd
            r.addView(v, lp)
        }
        return r
    }

    private fun divider(): View {
        val v = View(context)
        v.setBackgroundColor(0x2EFFFFFF)
        return v
    }

    private fun paintBg() {
        bg.setColor(0xC2091120.toInt())
        bg.cornerRadius = dp(100f).toFloat()
        bg.setStroke(dp(if (editing) 1.5f else 1f), if (editing) 0xCC19D3FF.toInt() else 0x2EFFFFFF)
        background = bg
    }

    /** Dựng lại các ô theo mục đang bật. */
    fun refreshKinds() {
        // Gọi lặp lại (watchdog) mà không có gì đổi thì không dựng lại: tránh nháy / giật khi đang kéo
        val sg = StatKind.values().filter { settings.isOn(it) }.joinToString(",") { it.key } + "|" + editing + "|" + showEmptyHint
        if (sg == lastSig) return
        lastSig = sg
        removeAllViews()
        var n = 0
        val total = StatKind.values().count { settings.isOn(it) }
        val gapDp = if (total >= 3) 6f else 10f      // nhiều mục -> khít lại cho thanh không phình
        setPadding(dp(if (total >= 3) 10f else 13f), dp(6f), dp(if (total >= 3) 10f else 13f), dp(6f))
        if (editing) {
            val lp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(if (total >= 3) 6f else 9f)
            addView(grip, lp)
        }
        for (k in StatKind.values()) {
            if (!settings.isOn(k)) continue
            if (n > 0) {
                val lp = LayoutParams(dp(1f), dp(12f))
                lp.marginStart = dp(gapDp)
                lp.marginEnd = dp(gapDp)
                addView(divider(), lp)
            }
            val cell = cells[k] ?: continue
            (cell.parent as? ViewGroup)?.removeView(cell)
            addView(cell, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            n++
        }
        if (n == 0 && showEmptyHint) {
            (hint.parent as? ViewGroup)?.removeView(hint)
            addView(hint, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    fun update(s: StatSnapshot) {
        // FPS
        if (s.fps < 0) {
            fpsValue.text = "--"; fpsValue.setTextColor(C_MUTED)
        } else {
            fpsValue.text = s.fps.toString()
            fpsValue.setTextColor(if (s.fps >= 55) C_OK else if (s.fps >= 30) C_WARN else C_BAD)
        }
        // Pin
        if (s.batteryPct < 0) {
            batText.text = "--%"; batGlyph.pct = 0
        } else {
            batText.text = s.batteryPct.toString() + "%"
            batGlyph.pct = s.batteryPct
        }
        val bc = when {
            s.charging -> C_CYAN
            s.batteryPct in 0..15 -> C_BAD
            s.batteryPct in 0..30 -> C_WARN
            else -> C_OK
        }
        batGlyph.charging = s.charging
        batGlyph.tint = bc
        batText.setTextColor(if (s.charging || s.batteryPct in 0..30) bc else C_FG)
        // Giờ
        timeText.text = s.time
        // Mạng
        netDown.text = "↓" + fmtRate(s.rxBps)
        netUp.text = "↑" + fmtRate(s.txBps)
    }
}

// ---- cửa sổ nổi ----------------------------------------------------------------------------

class StatsBarOverlay(
    private val ctx: Context,
    private val wm: WindowManager,
    private val settings: StatsSettings,
    private val screenBounds: () -> Rect,
    private val newParams: (width: Int, height: Int, flags: Int) -> WindowManager.LayoutParams,
    private val targetPkg: () -> String?,
) {
    /** Báo đang kéo thanh (true) / thả (false): OverlayService dùng để làm mờ menu cho nhìn rõ chỗ đặt. */
    var onDrag: ((Boolean) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val d get() = UiScale.density(ctx.resources)

    @Volatile private var closed = false
    private var view: StatsBarView? = null
    private var params: WindowManager.LayoutParams? = null
    private var addedAt = 0L
    private var draggable = false
    private val previews = ArrayList<StatsBarView>()   // chỉ đụng ở luồng chính
    private var lastSnap: StatSnapshot? = null

    // ---- nhịp cập nhật 1 giây ----
    private var ticking = false
    private val tick = object : Runnable {
        override fun run() {
            if (closed) { ticking = false; return }
            try {
                refreshOnce()
            } catch (t: Throwable) {
                DebugLog.e("Thanh thông số", "Lỗi khi cập nhật số liệu", t)
            }
            if (view != null || previews.isNotEmpty()) main.postDelayed(this, 1000L) else ticking = false
        }
    }

    private fun startTicker() {
        if (closed || ticking) return
        ticking = true
        main.post(tick)
    }

    private fun stopTickerIfIdle() {
        if (view == null && previews.isEmpty()) {
            main.removeCallbacks(tick)
            ticking = false
            stopScreenFpsMeter()
        }
    }

    // ---- mạng / pin / giờ ----
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastNetAt = 0L

    private fun readBattery(): Pair<Int, Boolean> {
        val bi = runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
            ?: return Pair(-1, false)
        val level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Pair(pct, charging)
    }

    private fun refreshOnce() {
        val now = SystemClock.elapsedRealtime()
        var rxBps = -1L
        var txBps = -1L
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx >= 0L && tx >= 0L) {
            if (lastNetAt > 0L && lastRx >= 0L && lastTx >= 0L) {
                val dt = (now - lastNetAt).coerceAtLeast(1L)
                rxBps = ((rx - lastRx) * 1000L / dt).coerceAtLeast(0L)
                txBps = ((tx - lastTx) * 1000L / dt).coerceAtLeast(0L)
            }
            lastRx = rx; lastTx = tx; lastNetAt = now
        }
        val bat = readBattery()
        if (settings.isOn(StatKind.FPS)) {
            startScreenFpsMeter()
            requestFpsSample()
        } else {
            stopScreenFpsMeter()
            // Tắt FPS thì tắt luôn TimeStats của SurfaceFlinger (để bật sẽ tốn thêm CPU cho mọi game)
            if (timeStatsStarted.compareAndSet(true, false)) {
                fpsExecutor.execute {
                    runCatching {
                        val manager = ZynAdbConnectionManager.get(ctx.applicationContext)
                        if (manager.isConnected) runAdbText(manager, "dumpsys SurfaceFlinger --timestats -disable", 8_000)
                    }
                }
            }
        }
        val pattern = if (android.text.format.DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a"
        val time = SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
        val nowForFps = SystemClock.elapsedRealtime()
        val measuredGameFps = fpsValue.takeIf { it >= 0 && nowForFps - fpsUpdatedAt < 3500L }
        val measuredScreenFps = screenFpsValue.takeIf { it >= 0 && nowForFps - screenFpsUpdatedAt < 1500L }
        val snap = StatSnapshot(
            // Nếu ADB đọc được FPS game thì ưu tiên; nếu không, hiện nhịp VSync của màn hình.
            fps = if (settings.isOn(StatKind.FPS)) (measuredGameFps ?: measuredScreenFps ?: -1) else -1,
            batteryPct = bat.first, charging = bat.second,
            time = time, rxBps = rxBps, txBps = txBps,
        )
        lastSnap = snap
        view?.update(snap)
        for (p in previews) p.update(snap)
    }

    // ---- ô xem trước trong tab Cài đặt ----
    internal fun addPreview(v: StatsBarView) {
        if (closed) return
        if (!previews.contains(v)) previews.add(v)
        lastSnap?.let { v.update(it) }
        startTicker()
    }

    internal fun removePreview(v: StatsBarView) {
        previews.remove(v)
        stopTickerIfIdle()
    }

    // ---- FPS màn hình: đếm nhịp VSync theo cửa sổ 500 ms ----
    // Đây là tốc độ callback khung hình của màn hình/overlay, KHÔNG phải FPS render riêng của game.
    // Khi ADB đọc được FPS render của game thì số liệu game vẫn được ưu tiên.
    @Volatile private var screenFpsValue = -1
    @Volatile private var screenFpsUpdatedAt = 0L
    private var screenFrameCount = 0
    private var screenSampleStartedAt = 0L
    private var screenFpsRunning = false
    private val choreographer by lazy { Choreographer.getInstance() }
    private val screenFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!screenFpsRunning) return
            screenFrameCount++
            choreographer.postFrameCallback(this)
        }
    }
    private val screenFpsSample = object : Runnable {
        override fun run() {
            if (!screenFpsRunning) return
            val now = SystemClock.elapsedRealtime()
            val start = screenSampleStartedAt
            val elapsed = now - start
            if (start > 0L && elapsed >= 250L) {
                val measured = (screenFrameCount * 1000.0 / elapsed).toInt().coerceIn(0, 240)
                screenFpsValue = measured
                screenFpsUpdatedAt = now
                screenFrameCount = 0
                screenSampleStartedAt = now
            } else if (start == 0L) {
                screenSampleStartedAt = now
                screenFrameCount = 0
            }
            main.postDelayed(this, 500L)
        }
    }

    private fun startScreenFpsMeter() {
        if (closed || screenFpsRunning) return
        screenFpsRunning = true
        screenFrameCount = 0
        screenSampleStartedAt = SystemClock.elapsedRealtime()
        choreographer.postFrameCallback(screenFrameCallback)
        main.removeCallbacks(screenFpsSample)
        main.postDelayed(screenFpsSample, 500L)
    }

    private fun stopScreenFpsMeter() {
        if (!screenFpsRunning) return
        screenFpsRunning = false
        choreographer.removeFrameCallback(screenFrameCallback)
        main.removeCallbacks(screenFpsSample)
        screenFpsValue = -1
        screenFpsUpdatedAt = 0L
    }

    // ---- FPS game: SurfaceFlinger TimeStats (ưu tiên) + --latency dự phòng ----
    // Chỉ hiện số này nếu quyền ADB/ROM thực sự cung cấp dữ liệu lớp của game.
    @Volatile private var fpsValue = -1
    @Volatile private var fpsUpdatedAt = 0L
    private val fpsSampling = AtomicBoolean(false)
    private val timeStatsStarted = AtomicBoolean(false)
    private val fpsExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ZynTap-FPS").apply { isDaemon = true }
    }
    private var previousLayer = ""
    private var previousFrames = -1L
    private var previousSampleAt = 0L

    private data class LayerStats(val name: String, val frames: Long, val averageFps: Double)

    @Volatile private var lastFpsReqAt = 0L
    // Cache gói đang ở trước: `dumpsys window` rất nặng, không cần hỏi mỗi lần đo
    @Volatile private var cachedFocusPkg: String? = null
    @Volatile private var cachedFocusAt = 0L

    private fun requestFpsSample() {
        if (closed) return
        val nowReq = SystemClock.elapsedRealtime()
        if (nowReq - lastFpsReqAt < FPS_SAMPLE_GAP_MS) return
        if (!fpsSampling.compareAndSet(false, true)) return
        lastFpsReqAt = nowReq
        fpsExecutor.execute {
            try {
                if (closed) return@execute
                val manager = runCatching { ZynAdbConnectionManager.get(ctx.applicationContext) }.getOrNull()
                if (manager == null || !manager.isConnected) {
                    publishFps(-1)
                    previousLayer = ""; previousFrames = -1L; previousSampleAt = 0L
                    return@execute
                }

                val nowFocus = SystemClock.elapsedRealtime()
                if (cachedFocusPkg == null || nowFocus - cachedFocusAt > FOCUS_CACHE_MS) {
                    val focusOut = runCatching { runAdbText(manager, FOCUS_CMD, 16_000) }.getOrDefault("")
                    cachedFocusPkg = FOCUS_RE.find(focusOut)?.groupValues?.getOrNull(1)
                        ?.takeIf { PKG_RE.matches(it) && it != "com.zyntap.app" }
                    cachedFocusAt = nowFocus
                }
                val focusedPkg = cachedFocusPkg
                val pkg = focusedPkg ?: targetPkg()?.takeIf { PKG_RE.matches(it) }
                if (pkg.isNullOrBlank()) {
                    publishFps(-1)
                    previousLayer = ""; previousFrames = -1L; previousSampleAt = 0L
                    return@execute
                }

                // Bật bộ đếm một lần trong phiên; không clear mỗi giây vì sẽ mất mẫu để so sánh.
                if (timeStatsStarted.compareAndSet(false, true)) {
                    val enabled = runCatching {
                        runAdbText(manager, "dumpsys SurfaceFlinger --timestats -clear -enable", 8_000)
                    }.getOrDefault("")
                    DebugLog.i("FPS", "Đã yêu cầu SurfaceFlinger TimeStats (output=${enabled.length})")
                }

                val dump = runCatching {
                    runAdbText(manager, TIMESTATS_CMD, 300_000)
                }.getOrDefault("")
                val candidates = parseLayerStats(dump, pkg)
                val chosen = candidates.sortedWith(
                    compareByDescending<LayerStats> { layerPriority(it.name) }
                        .thenByDescending { it.frames }
                ).firstOrNull()

                if (chosen != null) {
                    val now = SystemClock.elapsedRealtime()
                    if (chosen.name == previousLayer && previousFrames >= 0L && chosen.frames >= previousFrames && previousSampleAt > 0L) {
                        val elapsed = (now - previousSampleAt).coerceAtLeast(1L)
                        val delta = chosen.frames - previousFrames
                        // Dùng số frame mới trong cửa sổ lấy mẫu, không dùng averageFPS tích lũy từ lúc mở game.
                        val measured = (delta * 1000.0 / elapsed).toInt().coerceIn(0, 240)
                        publishFps(measured)
                    } else {
                        publishFps(-1) // cần hai mẫu cùng layer mới tính được FPS thật
                    }
                    previousLayer = chosen.name
                    previousFrames = chosen.frames
                    previousSampleAt = now
                    return@execute
                }

                // Fallback cho ROM không xuất TimeStats: lấy timestamp present từ SurfaceFlinger --latency.
                val fallback = readLatencyFps(manager, pkg)
                if (fallback >= 0) publishFps(fallback) else publishFps(-1)
                previousLayer = ""; previousFrames = -1L; previousSampleAt = 0L
            } catch (t: Throwable) {
                DebugLog.w("FPS", "Không đọc được FPS thật: ${t.message ?: t.javaClass.simpleName}")
                publishFps(-1)
            } finally {
                fpsSampling.set(false)
            }
        }
    }

    private fun publishFps(value: Int) {
        fpsValue = value.coerceIn(-1, 240)
        fpsUpdatedAt = if (value >= 0) SystemClock.elapsedRealtime() else 0L
    }

    private fun parseLayerStats(dump: String, pkg: String): List<LayerStats> {
        if (dump.isBlank()) return emptyList()
        val starts = Regex("(?m)^\\s*layerName\\s*=\\s*(.*)$").findAll(dump).toList()
        if (starts.isEmpty()) return emptyList()
        val result = ArrayList<LayerStats>()
        for (i in starts.indices) {
            val match = starts[i]
            val end = if (i + 1 < starts.size) starts[i + 1].range.first else dump.length
            val block = dump.substring(match.range.first, end)
            val name = match.groupValues[1].trim()
            val packageName = Regex("(?m)^\\s*packageName\\s*=\\s*(.+)$").find(block)?.groupValues?.get(1)?.trim().orEmpty()
            if (!name.contains(pkg, ignoreCase = true) && !packageName.equals(pkg, ignoreCase = true)) continue
            if (name.contains("Background", ignoreCase = true) || name.contains("ZynTap", ignoreCase = true)) continue
            val frames = Regex("(?m)^\\s*totalFrames\\s*=\\s*(\\d+)").find(block)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val avg = Regex("(?m)^\\s*averageFPS\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)").find(block)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            result += LayerStats(name, frames, avg)
        }
        return result
    }

    private fun layerPriority(name: String): Int = when {
        name.contains("SurfaceView", ignoreCase = true) && name.contains("BLAST", ignoreCase = true) -> 3
        name.contains("SurfaceView", ignoreCase = true) -> 2
        name.contains("BLAST", ignoreCase = true) -> 1
        else -> 0
    }

    private fun readLatencyFps(manager: ZynAdbConnectionManager, pkg: String): Int {
        val list = runAdbText(manager, "dumpsys SurfaceFlinger --list 2>/dev/null", 120_000)
        val layers = list.lineSequence().map { it.trim() }
            .filter { it.contains(pkg) && !it.contains("Background", ignoreCase = true) && !it.contains("ZynTap", ignoreCase = true) }
            .toList()
        val layer = layers.firstOrNull { it.contains("SurfaceView", ignoreCase = true) && it.contains("BLAST", ignoreCase = true) }
            ?: layers.firstOrNull { it.contains("SurfaceView", ignoreCase = true) }
            ?: layers.firstOrNull { it.contains("BLAST", ignoreCase = true) }
            ?: layers.lastOrNull()
            ?: return -1
        val safeLayer = layer.replace("'", "'\\''")
        val latency = runAdbText(manager, "dumpsys SurfaceFlinger --latency '$safeLayer'", 60_000)
        val timestamps = latency.lineSequence().drop(1).mapNotNull { line ->
            val cols = line.trim().split(WS_RE)
            // SurfaceFlinger --latency: desiredPresentTime, actualPresentTime, frameReadyTime.
            cols.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
        }.distinct().sorted().toList()
        val newest = timestamps.lastOrNull() ?: return -1
        val count = timestamps.count { it >= newest - 1_000_000_000L }
        return if (count > 0) count.coerceIn(0, 240) else -1
    }

    /** Chạy shell ADB trên worker; giới hạn output để tránh giữ bộ nhớ quá lớn. */
    private fun runAdbText(manager: ZynAdbConnectionManager, command: String, maxChars: Int = 200_000): String {
        val stream = manager.openStream("shell:$command")
        return try {
            stream.openInputStream().bufferedReader().use { reader ->
                val out = StringBuilder()
                val buf = CharArray(8192)
                while (out.length < maxChars) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    out.append(buf, 0, minOf(n, maxChars - out.length))
                }
                out.toString()
            }
        } finally {
            runCatching { stream.close() }
        }
    }

    // ---- cửa sổ ----

    private fun flagsFor(drag: Boolean): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!drag) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE // chạm xuyên hoàn toàn
        return f
    }

    private fun margin(): Int = (6f * d).toInt()

    private fun place(p: WindowManager.LayoutParams, w: Int, h: Int) {
        val b = screenBounds()
        val m = margin()
        val spanX = (b.width() - w - 2 * m).coerceAtLeast(0)
        val spanY = (b.height() - h - 2 * m).coerceAtLeast(0)
        p.x = b.left + m + (spanX * settings.posX).toInt()
        p.y = b.top + m + (spanY * settings.posY).toInt()
    }

    private fun saveFractions(p: WindowManager.LayoutParams, w: Int, h: Int) {
        val b = screenBounds()
        val m = margin()
        val spanX = b.width() - w - 2 * m
        val spanY = b.height() - h - 2 * m
        settings.posX = if (spanX > 0) (p.x - b.left - m) / spanX.toFloat() else 0.5f
        settings.posY = if (spanY > 0) (p.y - b.top - m) / spanY.toFloat() else 0f
    }

    /** Dựng / cập nhật / gỡ cho khớp cấu hình hiện tại. Gọi lặp lại thoải mái. */
    fun ensure() {
        if (closed || !OverlayPermission.granted(ctx)) return
        if (!settings.anyOn()) { remove(); return }
        val old = view
        if (old != null && !old.isAttachedToWindow && SystemClock.uptimeMillis() - addedAt > 1500L) {
            runCatching { wm.removeViewImmediate(old) }
            live.removeAll { it.second === old }
            view = null; params = null
        }
        val cur = view
        val p = params
        if (cur == null || p == null) {
            sweepOrphans()
            val v = StatsBarView(ctx, settings)
            v.maxBarWidth = (screenBounds().width() - 2 * margin()).coerceAtLeast(0)
            v.editing = draggable
            v.alpha = if (hidden) 0f else 1f
            v.refreshKinds()
            lastSnap?.let { v.update(it) }
            val np = newParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, flagsFor(draggable),
            ).apply { gravity = Gravity.TOP or Gravity.START }
            v.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            place(np, v.measuredWidth, v.measuredHeight)
            attachDrag(v, np)
            v.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
                val w = r - l
                val h = b - t
                if (w != orr - ol || h != ob - ot) {
                    val pp = params
                    if (pp != null && view === v) {
                        place(pp, w, h)
                        runCatching { wm.updateViewLayout(v, pp) }
                    }
                }
            }
            addedAt = SystemClock.uptimeMillis()
            runCatching { wm.addView(v, np) }
                .onSuccess { view = v; params = np; live.add(wm to v) }
                .onFailure { DebugLog.e("Thanh thông số", "Không gắn được cửa sổ thanh thông số", it) }
        } else {
            cur.refreshKinds()
            if (cur.width > 0 && cur.height > 0) {
                val bx = p.x; val by = p.y
                place(p, cur.width, cur.height)
                if (bx != p.x || by != p.y) runCatching { wm.updateViewLayout(cur, p) }
            }
        }
        startTicker()
    }

    private fun attachDrag(v: StatsBarView, p: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moving = false
        val slop = (6f * d)
        v.setOnTouchListener { _, e ->
            if (!draggable) {
                false
            } else {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moving = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!moving && (abs(dx) > slop || abs(dy) > slop)) {
                            moving = true
                            onDrag?.invoke(true)
                        }
                        if (moving) {
                            val b = screenBounds()
                            val m = margin()
                            val minX = b.left + m
                            val maxX = (b.right - v.width - m).coerceAtLeast(minX)
                            val minY = b.top + m
                            val maxY = (b.bottom - v.height - m).coerceAtLeast(minY)
                            p.x = (startX + dx).toInt().coerceIn(minX, maxX)
                            p.y = (startY + dy).toInt().coerceIn(minY, maxY)
                            runCatching { wm.updateViewLayout(v, p) }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (moving) {
                            saveFractions(p, v.width, v.height)
                            onDrag?.invoke(false)
                        }
                        moving = false
                        true
                    }
                    else -> true
                }
            }
        }
    }

    /**
     * Menu mở = true: thanh nhận chạm để kéo (viền sáng + tay nắm). Menu đóng/thu = false: chạm xuyên hoàn toàn.
     * Việc đưa cửa sổ lên trên menu do [raise] lo (phải gọi SAU khi cửa sổ menu đã gắn).
     */
    fun setDraggable(on: Boolean) {
        if (draggable == on) return
        draggable = on
        val v = view
        val p = params
        if (v == null || p == null) return
        v.editing = on
        p.flags = flagsFor(on)
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** Đưa cửa sổ thanh lên trên cùng (trên cả menu) bằng cách gắn lại cửa sổ — chỉ khi đang ở chế độ kéo. */
    fun raise() {
        if (!draggable) return
        val v = view ?: return
        val p = params ?: return
        runCatching { wm.removeViewImmediate(v) }
        val ok = runCatching { wm.addView(v, p) }.isSuccess
        if (ok) {
            addedAt = SystemClock.uptimeMillis()
        } else {
            live.removeAll { it.second === v }
            view = null; params = null
        }
    }

    /** Ẩn theo các UI nổi khác (đóng menu, đang chọn điểm...). Giữ cả khi cửa sổ bị dựng lại. */
    @Volatile private var hidden = false

    fun fadeOut(ms: Long) { hidden = true; view?.animate()?.alpha(0f)?.setDuration(ms)?.start() }

    fun fadeTo(visible: Boolean, ms: Long) {
        hidden = !visible
        view?.animate()?.alpha(if (visible) 1f else 0f)?.setDuration(ms)?.start()
    }

    fun restore() { hidden = false; view?.animate()?.cancel(); view?.alpha = 1f }

    fun remove() {
        view?.let { detach(wm, it) }
        view = null; params = null
        sweepOrphans()
        stopTickerIfIdle()
    }

    /** Dịch vụ tắt: gỡ cửa sổ, dừng nhịp, dừng luồng đo. */
    fun close() {
        if (closed) return
        closed = true
        remove()
        main.removeCallbacksAndMessages(null)
        ticking = false
        previews.clear()
        // remove() may have seen an active preview. Explicitly stop Choreographer AFTER clearing it.
        stopScreenFpsMeter()
        onDrag = null
        // Queue behind an in-flight sample: it may enable TimeStats after close() starts.
        fpsExecutor.execute {
            if (timeStatsStarted.compareAndSet(true, false)) {
                runCatching {
                    val manager = ZynAdbConnectionManager.get(ctx.applicationContext)
                    if (manager.isConnected) runAdbText(manager, "dumpsys SurfaceFlinger --timestats -disable", 8_000)
                }
            }
        }
        fpsExecutor.shutdown() // orderly, non-blocking: queued cleanup still runs.
    }

    fun state(): String {
        val v = view
        val on = StatKind.values().filter { settings.isOn(it) }.joinToString(",") { it.key }
        val w = when {
            v == null -> "không có cửa sổ"
            v.isAttachedToWindow -> "đang hiện"
            else -> "cửa sổ chưa gắn"
        }
        return "tổng=${settings.enabled} · bật: ${if (on.isEmpty()) "không" else on} · $w · fps=$fpsValue (SurfaceFlinger)"
    }

    companion object {
        private val PKG_RE = Regex("^[A-Za-z0-9_.]+$")
        private val WS_RE = Regex("\\s+")
        private val RLS_RE = Regex("RequestedLayerState\\{[0-9a-fA-F]+\\s+(.+?)(?:\\s+parentId=.*|\\s+ROOT.*|\\}.*)?$")
        private val FOCUS_RE = Regex("([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+")
        /** Nhịp đo FPS tối thiểu (ms) — trước đây đo mỗi 1s làm SurfaceFlinger + đường ADB chung với cảm ứng bị nghẽn. */
        private const val FPS_SAMPLE_GAP_MS = 2000L
        private const val FOCUS_CACHE_MS = 5000L
        /** Chỉ giữ 4 trường parseLayerStats cần, lọc ngay trên máy để không kéo hàng trăm KB qua ADB mỗi lần. */
        private const val TIMESTATS_CMD =
            "dumpsys SurfaceFlinger --timestats -dump 2>/dev/null | " +
                "grep -E '^[[:space:]]*(layerName|packageName|totalFrames|averageFPS)[[:space:]]*='"
        private const val FOCUS_CMD =
            "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -n 2"

        /** In ra đúng 1 dòng: tên layer SurfaceFlinger của game (ưu tiên SurfaceView). */
        private const val LIST_CMD =
            "l=\$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F '@PKG@' | grep -v -F 'Background'); " +
                "s=\$(echo \"\$l\" | grep -F 'SurfaceView' | tail -1); " +
                "if [ -z \"\$s\" ]; then s=\$(echo \"\$l\" | grep -F 'BLAST' | tail -1); fi; " +
                "if [ -n \"\$s\" ]; then echo \"\$s\"; else echo \"\$l\" | tail -1; fi"

        private val live = java.util.Collections.synchronizedList(ArrayList<Pair<WindowManager, View>>())

        private fun detach(wm: WindowManager, v: View): Boolean {
            runCatching { v.animate().cancel() }
            var ok = runCatching { wm.removeViewImmediate(v) }.isSuccess
            if (!ok) ok = runCatching { wm.removeView(v) }.isSuccess
            val gone = ok || !v.isAttachedToWindow
            if (gone) live.removeAll { it.second === v }
            return gone
        }

        /** Gỡ mọi cửa sổ thanh còn sót (dịch vụ trước tắt giữa chừng). */
        fun sweepOrphans(): Int {
            val snapshot = synchronized(live) { live.toList() }
            var n = 0
            for ((w, v) in snapshot) if (detach(w, v)) n++
            return n
        }
    }
}

// ============================================================================
//  Tab "Cài đặt"
// ============================================================================

class SettingsTab(
    private val ctx: Context,
    private val settings: StatsSettings,
    private val dropdown: DropdownLayer,
    private val overlay: StatsBarOverlay,
    /** Bật/tắt mục -> dựng/gỡ thanh ngoài màn hình. */
    private val onChanged: () -> Unit,
) {
    private val d = UiScale.density(ctx.resources)
    private fun dp(n: Int) = (n * d).toInt()

    private var preview: StatsBarView? = null
    private var binding = false
    private lateinit var toggleSwitch: Switch
    private lateinit var summary: TextView
    private lateinit var pickerHolder: LinearLayout
    private lateinit var previewBox: FrameLayout

    private fun round(color: Int, stroke: Int = 0xFF24344E.toInt(), r: Int = 12) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); setStroke(dp(1), stroke)
    }

    private fun refreshSummary() {
        val on = StatKind.values().filter { settings.isOn(it) }
        summary.text = when {
            !settings.enabled -> "Thanh trạng thái đang tắt."
            on.isEmpty() -> "Chưa chọn mục nào."
            else -> "Đang hiện: " + on.joinToString(" · ") { it.title }
        }
        summary.setTextColor(if (settings.anyOn()) C_CYAN else C_MUTED)
        // Công tắc tổng tắt -> xem trước mờ + danh sách mục mờ đi (vẫn chọn được sẵn)
        previewBox.alpha = if (settings.enabled) 1f else 0.45f
        pickerHolder.alpha = if (settings.enabled) 1f else 0.6f
    }

    fun build(): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), 0)
        }
        addTabHeader(ctx, root, "Cài đặt", "Thanh thông số nổi trên màn hình")
        val page = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(14))
        }

        // ---- ô xem trước (thanh thật, số liệu thật) ----
        val box = FrameLayout(ctx).apply { background = round(0xFF0A1220.toInt()) }
        previewBox = box
        val pv = StatsBarView(ctx, settings).apply { showEmptyHint = true; refreshKinds() }
        preview = pv
        box.addView(pv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ))
        // Ô xem trước không được rộng hơn khung chứa
        box.addOnLayoutChangeListener { _, l, _, r, _, ol, _, orr, _ ->
            val w = r - l
            if (w != orr - ol && w > 0) {
                pv.maxBarWidth = w - dp(16)
                pv.requestLayout()
            }
        }
        page.addView(box, LinearLayout.LayoutParams(-1, dp(84)))
        overlay.addPreview(pv)

        // ---- MỘT công tắc tổng: hiện thanh trạng thái ----
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            text = "Hiện thanh trạng thái"
            setTextColor(C_FG); scaledTextSize(14.5f); setTypeface(typeface, Typeface.BOLD)
        })
        texts.addView(TextView(ctx).apply {
            text = "Bật để hiện thanh nổi, rồi chọn các mục muốn hiển thị bên dưới."
            setTextColor(C_MUTED); scaledTextSize(11.5f)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        toggleSwitch = Switch(ctx).apply {
            thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(C_CYAN, 0xFF2A3A5C.toInt()),
            )
            isChecked = settings.enabled
            setOnCheckedChangeListener { _, on ->
                if (!binding) {
                    settings.enabled = on
                    pv.refreshKinds()
                    refreshSummary()
                    onChanged()
                }
            }
        }
        row.addView(toggleSwitch, LinearLayout.LayoutParams(-2, -2))
        row.setOnClickListener { toggleSwitch.toggle() }
        page.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // ---- dropdown CHỌN NHIỀU mục ----
        pickerHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        pickerHolder.addView(TextView(ctx).apply {
            text = "Mục hiển thị"; setTextColor(C_MUTED); scaledTextSize(12f)
            setPadding(dp(2), dp(12), 0, dp(5))
        })
        val kinds = StatKind.values().toList()
        pickerHolder.addView(
            MultiDropdownField(
                ctx, dropdown, kinds.map { it.title },
                BooleanArray(kinds.size) { settings.isOn(kinds[it]) },
            ) { st ->
                for (i in kinds.indices) settings.setOn(kinds[i], st[i])
                pv.refreshKinds()
                refreshSummary()
                onChanged()
            },
            LinearLayout.LayoutParams(-1, dp(46)),
        )
        page.addView(pickerHolder, LinearLayout.LayoutParams(-1, -2))

        summary = TextView(ctx).apply { scaledTextSize(12f) }
        page.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        page.addView(TextView(ctx).apply {
            text = "Mở menu để kéo thanh tới chỗ bạn muốn. Khi menu thu lại, thanh chạm xuyên " +
                "nên không cản thao tác trong game.\n" +
                "FPS cần ADB Wi-Fi đang kết nối và game đang mở ở trước màn hình."
            setTextColor(C_MUTED); scaledTextSize(11f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        refreshSummary()
        root.addView(ScrollView(ctx).apply {
            isFillViewport = true; isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(page, ViewGroup.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    /** Menu bị gỡ: bỏ ô xem trước khỏi nhịp cập nhật. */
    fun dispose() {
        preview?.let { overlay.removePreview(it) }
        preview = null
    }
}
