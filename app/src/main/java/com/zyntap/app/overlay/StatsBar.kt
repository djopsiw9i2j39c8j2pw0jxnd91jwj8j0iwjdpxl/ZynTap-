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
import kotlin.math.abs

// ============================================================================
//  Thanh thông số nổi: FPS / Pin / Giờ / Dữ liệu mạng.
//  - Menu đóng/thu: cửa sổ CHẠM XUYÊN, nhỏ gọn, không che thao tác.
//  - Menu đang mở: kéo thanh để đổi vị trí (menu mờ đi khi đang kéo, như kéo nút main).
// ============================================================================

/** Các mục có thể hiện trên thanh. Thứ tự ở đây = thứ tự trên thanh. */
enum class StatKind(val key: String, val title: String, val desc: String) {
    FPS("fps", "FPS", "Số khung hình/giây của game đang mở (đo qua ADB Wi-Fi)."),
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

    fun anyOn(): Boolean = StatKind.values().any { isOn(it) }

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

private fun fmtRate(b: Long): String = when {
    b < 0L -> "--"
    b >= 1_000_000L -> String.format(Locale.US, "%.1f MB/s", b / 1_000_000.0)
    b >= 1_000L -> String.format(Locale.US, "%d KB/s", b / 1_000L)
    else -> "$b B/s"
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
        text = "FPS"; scaledTextSize(9f); setTextColor(C_MUTED)
        setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.06f
    }
    private val fpsValue = TextView(ctx).apply {
        text = "--"; scaledTextSize(14f); setTextColor(C_MUTED)
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
        text = "↓ --"; scaledTextSize(11.5f); setTextColor(C_CYAN)
        setTypeface(typeface, Typeface.BOLD)
        minWidth = (paint.measureText("↓ 88.8 MB/s") + 1f).toInt()
    }
    private val netUp = TextView(ctx).apply {
        text = "↑ --"; scaledTextSize(11.5f); setTextColor(C_MUTED)
        setTypeface(typeface, Typeface.BOLD)
        minWidth = (paint.measureText("↑ 88.8 MB/s") + 1f).toInt()
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
        cells[StatKind.NET] = row(netDown to dp(8f), netUp to 0)
        paintBg()
        refreshKinds()
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
        if (editing) {
            val lp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(9f)
            addView(grip, lp)
        }
        for (k in StatKind.values()) {
            if (!settings.isOn(k)) continue
            if (n > 0) {
                val lp = LayoutParams(dp(1f), dp(12f))
                lp.marginStart = dp(10f)
                lp.marginEnd = dp(10f)
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
        netDown.text = "↓ " + fmtRate(s.rxBps)
        netUp.text = "↑ " + fmtRate(s.txBps)
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
            try {
                refreshOnce()
            } catch (t: Throwable) {
                DebugLog.e("Thanh thông số", "Lỗi khi cập nhật số liệu", t)
            }
            if (view != null || previews.isNotEmpty()) main.postDelayed(this, 1000L) else ticking = false
        }
    }

    private fun startTicker() {
        if (ticking) return
        ticking = true
        main.post(tick)
    }

    private fun stopTickerIfIdle() {
        if (view == null && previews.isEmpty()) {
            main.removeCallbacks(tick)
            ticking = false
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
        val pattern = if (android.text.format.DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a"
        val time = SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
        if (settings.isOn(StatKind.FPS)) requestFps()
        val snap = StatSnapshot(
            fps = if (settings.isOn(StatKind.FPS)) fpsValue else -1,
            batteryPct = bat.first, charging = bat.second,
            time = time, rxBps = rxBps, txBps = txBps,
        )
        lastSnap = snap
        view?.update(snap)
        for (p in previews) p.update(snap)
    }

    // ---- ô xem trước trong tab Cài đặt ----
    internal fun addPreview(v: StatsBarView) {
        if (!previews.contains(v)) previews.add(v)
        lastSnap?.let { v.update(it) }
        startTicker()
    }

    internal fun removePreview(v: StatsBarView) {
        previews.remove(v)
        stopTickerIfIdle()
    }

    // ---- đo FPS (luồng nền) ----
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "zyn-fps").apply { isDaemon = true } }
    @Volatile private var fpsBusy = false
    @Volatile private var fpsValue = -1
    private var layerName: String? = null     // chỉ đụng ở luồng io
    private var layerPkg: String? = null
    private var layerAt = 0L
    private var lastNewest = 0L
    private var stall = 0
    private var lastFpsErrAt = 0L

    private fun requestFps() {
        if (fpsBusy) return
        val pkg = targetPkg()
        if (pkg == null || !PKG_RE.matches(pkg)) { fpsValue = -1; return }
        fpsBusy = true
        try {
            io.execute {
                try {
                    fpsValue = measureFps(pkg)
                } catch (t: Throwable) {
                    fpsValue = -1
                    val n = SystemClock.elapsedRealtime()
                    if (n - lastFpsErrAt > 30_000L) {
                        lastFpsErrAt = n
                        DebugLog.w("Thanh thông số", "Đo FPS lỗi", t)
                    }
                } finally {
                    fpsBusy = false
                }
            }
        } catch (t: Throwable) {
            fpsBusy = false
        }
    }

    /**
     * FPS của game = tốc độ SurfaceFlinger thực sự hiển thị khung hình của layer game.
     * `dumpsys SurfaceFlinger --latency <layer>` trả về tối đa 127 khung gần nhất: mỗi dòng 3 mốc thời gian (ns),
     * cột 2 = lúc khung thực sự lên màn hình. Đếm số khung trong 1 giây cuối.
     * @return số FPS (>= 0) hoặc -1 nếu chưa đo được (chưa nối ADB, chưa tìm thấy layer...)
     */
    private fun measureFps(pkg: String): Int {
        val mgr = ZynAdbConnectionManager.get(ctx.applicationContext)
        if (!mgr.isConnected) return -1
        val now = SystemClock.elapsedRealtime()
        if (layerPkg != pkg) { layerName = null; layerPkg = pkg }
        if (layerName == null || now - layerAt > 30_000L) {
            val out = runAdb(mgr, LIST_CMD.replace("@PKG@", pkg), 3500L) ?: return -1
            val name = out.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            if (name == null || name.contains('\'')) {
                layerName = null
                layerAt = now - 25_000L // thử dò lại sau ~5 giây
                return -1
            }
            layerName = name
            layerAt = now
        }
        val layer = layerName ?: return -1
        val lat = runAdb(mgr, "dumpsys SurfaceFlinger --latency '$layer'", 2500L) ?: return -1

        val ts = ArrayList<Long>()
        val lines = lat.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        for (ln in lines.drop(1)) {
            val parts = ln.split(WS_RE)
            if (parts.size < 3) continue
            val a = parts[1].toLongOrNull() ?: continue
            if (a > 0L && a < Long.MAX_VALUE / 2) ts.add(a)
        }
        if (ts.size < 2) {
            layerName = null // layer có thể đã đổi (đổi cảnh, mở lại game) -> dò lại lần sau
            return -1
        }
        ts.sort()
        val newest = ts[ts.size - 1]
        if (newest == lastNewest) stall++ else stall = 0
        lastNewest = newest
        if (stall >= 2) return 0 // game đứng hình / không vẽ nữa
        val firstIdx = ts.indexOfFirst { it >= newest - 1_000_000_000L }
        val cnt = ts.size - firstIdx
        if (cnt < 2) return 0
        val span = (newest - ts[firstIdx]).coerceAtLeast(1L)
        val fps = (cnt - 1) * 1_000_000_000.0 / span
        return Math.round(fps).toInt().coerceIn(0, 999)
    }

    private fun runAdb(mgr: ZynAdbConnectionManager, cmd: String, timeoutMs: Long): String? {
        val sb = StringBuilder()
        val holder = arrayOfNulls<AdbStream>(1)
        val done = CountDownLatch(1)
        val worker = Thread({
            try {
                val s = mgr.openStream("exec:$cmd")
                holder[0] = s
                s.openInputStream().bufferedReader().use { r ->
                    val cb = CharArray(4096)
                    while (true) {
                        val n = r.read(cb)
                        if (n < 0) break
                        synchronized(sb) { if (sb.length < 200_000) sb.append(cb, 0, n) }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                done.countDown()
            }
        }, "zyn-fps-adb")
        worker.isDaemon = true
        worker.start()
        val ok = done.await(timeoutMs, TimeUnit.MILLISECONDS)
        runCatching { holder[0]?.close() }
        if (!ok) return null
        return synchronized(sb) { sb.toString() }
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
        if (!OverlayPermission.granted(ctx)) return
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
            v.editing = draggable
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

    fun fadeOut(ms: Long) { view?.animate()?.alpha(0f)?.setDuration(ms)?.start() }

    fun restore() { view?.animate()?.cancel(); view?.alpha = 1f }

    fun remove() {
        view?.let { detach(wm, it) }
        view = null; params = null
        sweepOrphans()
        stopTickerIfIdle()
    }

    /** Dịch vụ tắt: gỡ cửa sổ, dừng nhịp, dừng luồng đo. */
    fun close() {
        remove()
        main.removeCallbacksAndMessages(null)
        ticking = false
        previews.clear()
        runCatching { io.shutdownNow() }
    }

    fun state(): String {
        val v = view
        val on = StatKind.values().filter { settings.isOn(it) }.joinToString(",") { it.key }
        val w = when {
            v == null -> "không có cửa sổ"
            v.isAttachedToWindow -> "đang hiện"
            else -> "cửa sổ chưa gắn"
        }
        return "bật: ${if (on.isEmpty()) "không" else on} · $w · fps=$fpsValue"
    }

    companion object {
        private val PKG_RE = Regex("^[A-Za-z0-9_.]+$")
        private val WS_RE = Regex("\\s+")

        /** In ra đúng 1 dòng: tên layer SurfaceFlinger của game (ưu tiên SurfaceView). */
        private const val LIST_CMD =
            "l=\$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F '@PKG@' | grep -v -F 'Background'); " +
                "s=\$(echo \"\$l\" | grep -F 'SurfaceView' | tail -1); " +
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
    private var selected = StatKind.FPS
    private var binding = false
    private lateinit var toggleTitle: TextView
    private lateinit var toggleDesc: TextView
    private lateinit var toggleSwitch: Switch
    private lateinit var summary: TextView

    private fun round(color: Int, stroke: Int = 0xFF24344E.toInt(), r: Int = 12) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); setStroke(dp(1), stroke)
    }

    private fun refreshToggle() {
        binding = true
        toggleTitle.text = "Hiện " + selected.title
        toggleDesc.text = selected.desc
        toggleSwitch.isChecked = settings.isOn(selected)
        binding = false
    }

    private fun refreshSummary() {
        val on = StatKind.values().filter { settings.isOn(it) }
        summary.text = if (on.isEmpty()) "Chưa bật mục nào." else "Đang bật: " + on.joinToString(" · ") { it.title }
        summary.setTextColor(if (on.isEmpty()) C_MUTED else C_CYAN)
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
        val pv = StatsBarView(ctx, settings).apply { showEmptyHint = true; refreshKinds() }
        preview = pv
        box.addView(pv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ))
        page.addView(box, LinearLayout.LayoutParams(-1, dp(84)))
        overlay.addPreview(pv)

        // ---- dropdown chọn mục ----
        page.addView(TextView(ctx).apply {
            text = "Chọn mục"; setTextColor(C_MUTED); scaledTextSize(12f)
            setPadding(dp(2), dp(14), 0, dp(5))
        })
        val kinds = StatKind.values().toList()
        page.addView(
            DropdownField(ctx, dropdown, kinds.map { it.title }, kinds.indexOf(selected)) { i ->
                selected = kinds[i]
                refreshToggle()
            },
            LinearLayout.LayoutParams(-1, dp(46)),
        )

        // ---- công tắc cho mục đang chọn ----
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        toggleTitle = TextView(ctx).apply {
            setTextColor(C_FG); scaledTextSize(14.5f); setTypeface(typeface, Typeface.BOLD)
        }
        toggleDesc = TextView(ctx).apply { setTextColor(C_MUTED); scaledTextSize(11.5f) }
        texts.addView(toggleTitle)
        texts.addView(toggleDesc)
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        toggleSwitch = Switch(ctx).apply {
            thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(C_CYAN, 0xFF2A3A5C.toInt()),
            )
            setOnCheckedChangeListener { _, on ->
                if (!binding) {
                    settings.setOn(selected, on)
                    pv.refreshKinds()
                    refreshSummary()
                    onChanged()
                }
            }
        }
        row.addView(toggleSwitch, LinearLayout.LayoutParams(-2, -2))
        row.setOnClickListener { toggleSwitch.toggle() }
        page.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        summary = TextView(ctx).apply { scaledTextSize(12f) }
        page.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        page.addView(TextView(ctx).apply {
            text = "Mở menu để kéo thanh tới chỗ bạn muốn. Khi menu thu lại, thanh chạm xuyên " +
                "nên không cản thao tác trong game.\n" +
                "FPS cần ADB Wi-Fi đang kết nối và game được mở từ ZynTap."
            setTextColor(C_MUTED); scaledTextSize(11f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        refreshToggle()
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
