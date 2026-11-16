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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

enum class StatKind(val key: String, val title: String, val desc: String) {
    FPS("fps", "FPS", "Chế độ thủ công không theo dõi game; FPS theo app không khả dụng, hiện --."),
    BATTERY("battery", "Pin", "Phần trăm pin; đổi màu khi đang sạc hoặc pin yếu."),
    TIME("time", "Giờ", "Giờ hiện tại của máy."),
    NET("net", "Dữ liệu mạng", "Tốc độ tải xuống / tải lên theo thời gian thực."),
}

class StatsSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("zyntap_stats", Context.MODE_PRIVATE)

    fun isOn(k: StatKind): Boolean = prefs.getBoolean("on_" + k.key, false)

    fun setOn(k: StatKind, v: Boolean) {
        prefs.edit().putBoolean("on_" + k.key, v).apply()
    }

    fun anyItem(): Boolean = StatKind.values().any { isOn(it) }

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", anyItem())
        set(v) {
            prefs.edit().putBoolean("enabled", v).apply()
        }

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
    b >= 10_000_000L -> String.format(Locale.US, "%dM", b / 1_000_000L)
    b >= 1_000_000L -> String.format(Locale.US, "%.1fM", b / 1_000_000.0)
    else -> String.format(Locale.US, "%dK", b / 1_000L)
}

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

internal class StatsBarView(ctx: Context, private val settings: StatsSettings) : LinearLayout(ctx) {
    private val d = UiScale.density(ctx.resources)
    private fun dp(n: Float): Int = (n * d).toInt()

    var showEmptyHint = false

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

    fun refreshKinds() {
        
        val sg = StatKind.values().filter { settings.isOn(it) }.joinToString(",") { it.key } + "|" + editing + "|" + showEmptyHint
        if (sg == lastSig) return
        lastSig = sg
        removeAllViews()
        var n = 0
        val total = StatKind.values().count { settings.isOn(it) }
        val gapDp = if (total >= 3) 6f else 10f      
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

    private fun TextView.textIfChanged(value: String) {
        if (text.toString() != value) text = value
    }

    fun update(s: StatSnapshot) {
        if (settings.isOn(StatKind.FPS)) {
            fpsValue.textIfChanged(if (s.fps < 0) "--" else s.fps.toString())
            val color = when { s.fps < 0 -> C_MUTED; s.fps >= 55 -> C_OK; s.fps >= 30 -> C_WARN; else -> C_BAD }
            if (fpsValue.currentTextColor != color) fpsValue.setTextColor(color)
        }
        if (settings.isOn(StatKind.BATTERY)) {
            batText.textIfChanged(if (s.batteryPct < 0) "--%" else "${s.batteryPct}%")
            batGlyph.pct = s.batteryPct.coerceAtLeast(0)
            val bc = when {
                s.charging -> C_CYAN
                s.batteryPct in 0..15 -> C_BAD
                s.batteryPct in 0..30 -> C_WARN
                else -> C_OK
            }
            batGlyph.charging = s.charging
            batGlyph.tint = bc
            val color = if (s.charging || s.batteryPct in 0..30) bc else C_FG
            if (batText.currentTextColor != color) batText.setTextColor(color)
        }
        if (settings.isOn(StatKind.TIME)) timeText.textIfChanged(s.time)
        if (settings.isOn(StatKind.NET)) {
            netDown.textIfChanged("↓" + fmtRate(s.rxBps))
            netUp.textIfChanged("↑" + fmtRate(s.txBps))
        }
    }

}

class StatsBarOverlay(
    private val ctx: Context,
    private val wm: WindowManager,
    private val settings: StatsSettings,
    private val screenBounds: () -> Rect,
    private val newParams: (width: Int, height: Int, flags: Int) -> WindowManager.LayoutParams,
) {
     
    var onDrag: ((Boolean) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val d get() = UiScale.density(ctx.resources)

    @Volatile private var closed = false
    private var view: StatsBarView? = null
    private var params: WindowManager.LayoutParams? = null
    private var addedAt = 0L
    private var draggable = false
    private val previews = ArrayList<StatsBarView>()   
    private var lastSnap: StatSnapshot? = null

    private var ticking = false
    private fun startTicker() { ticking = !closed }
    internal fun pulse() {
        if (!closed && ticking && (view != null || previews.isNotEmpty())) {
            runCatching { refreshOnce() }.onFailure { DebugLog.e("Thanh thông số", "Lỗi cập nhật", it) }
        }
    }
    private fun stopTickerIfIdle() {
        if (view == null && previews.isEmpty()) ticking = false
    }

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

    private var timeFormatter: SimpleDateFormat? = null
    private var timeFormatKey = ""

    private fun refreshOnce() {
        val now = SystemClock.elapsedRealtime()
        var rxBps = -1L
        var txBps = -1L
        val needNet = settings.isOn(StatKind.NET)
        val rx = if (needNet) TrafficStats.getTotalRxBytes() else -1L
        val tx = if (needNet) TrafficStats.getTotalTxBytes() else -1L
        if (!needNet) { lastRx = -1L; lastTx = -1L; lastNetAt = 0L }
        if (rx >= 0L && tx >= 0L) {
            if (lastNetAt > 0L && lastRx >= 0L && lastTx >= 0L) {
                val dt = (now - lastNetAt).coerceAtLeast(1L)
                rxBps = ((rx - lastRx) * 1000L / dt).coerceAtLeast(0L)
                txBps = ((tx - lastTx) * 1000L / dt).coerceAtLeast(0L)
            }
            lastRx = rx; lastTx = tx; lastNetAt = now
        }
        val bat = if (settings.isOn(StatKind.BATTERY)) readBattery() else (-1 to false)
        val time = if (settings.isOn(StatKind.TIME)) {
            val pattern = if (android.text.format.DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a"
            val locale = Locale.getDefault()
            val key = "$pattern|$locale|${java.util.TimeZone.getDefault().id}"
            if (timeFormatter == null || key != timeFormatKey) {
                timeFormatKey = key
                timeFormatter = SimpleDateFormat(pattern, locale)
            }
            timeFormatter!!.format(Date())
        } else ""
        val snap = StatSnapshot(
            
            fps = -1, // No target application is observed or sampled in manual mode.
            batteryPct = bat.first, charging = bat.second,
            time = time, rxBps = rxBps, txBps = txBps,
        )
        lastSnap = snap
        view?.update(snap)
        for (p in previews) p.update(snap)
    }

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

    private fun flagsFor(drag: Boolean): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!drag) f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE 
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

    fun ensure() {
        if (closed || !OverlayPermission.granted(ctx)) return
        if (!settings.anyOn()) { remove(); return }
        val old = view
        if (old != null && !old.isAttachedToWindow && SystemClock.uptimeMillis() - addedAt > 1500L && !wm.isAwaitingRecovery(old)) {
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

    fun close() {
        if (closed) return
        closed = true
        remove()
        main.removeCallbacksAndMessages(null)
        ticking = false
        previews.clear()
        onDrag = null
    }

    fun state(): String {
        val v = view
        val on = StatKind.values().filter { settings.isOn(it) }.joinToString(",") { it.key }
        val w = when {
            v == null -> "không có cửa sổ"
            v.isAttachedToWindow -> "đang hiện"
            else -> "cửa sổ chưa gắn"
        }
        return "tổng=${settings.enabled} · bật: ${if (on.isEmpty()) "không" else on} · $w · fps=-- (không theo dõi app)"
    }

    companion object {

        private val live = java.util.Collections.synchronizedList(ArrayList<Pair<WindowManager, View>>())

        private fun detach(wm: WindowManager, v: View): Boolean {
            runCatching { v.animate().cancel() }
            var ok = runCatching { wm.removeViewImmediate(v) }.isSuccess
            if (!ok) ok = runCatching { wm.removeView(v) }.isSuccess
            val gone = ok || !v.isAttachedToWindow
            if (gone) live.removeAll { it.second === v }
            return gone
        }

        fun sweepOrphans(): Int {
            val snapshot = synchronized(live) { live.toList() }
            var n = 0
            for ((w, v) in snapshot) if (detach(w, v)) n++
            return n
        }
    }
}

class SettingsTab(
    private val ctx: Context,
    private val settings: StatsSettings,
    private val dropdown: DropdownLayer,
    private val overlay: StatsBarOverlay,
     
    private val onChanged: () -> Unit,
) {
    private val d = UiScale.density(ctx.resources)
    private fun dp(n: Int) = (n * d).toInt()

    private var preview: StatsBarView? = null
    private var previewActive = false
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

        val box = FrameLayout(ctx).apply { background = round(0xFF0A1220.toInt()) }
        previewBox = box
        val pv = StatsBarView(ctx, settings).apply { showEmptyHint = true; refreshKinds() }
        preview = pv
        box.addView(pv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ))
        
        box.addOnLayoutChangeListener { _, l, _, r, _, ol, _, orr, _ ->
            val w = r - l
            if (w != orr - ol && w > 0) {
                pv.maxBarWidth = w - dp(16)
                pv.requestLayout()
            }
        }
        page.addView(box, LinearLayout.LayoutParams(-1, dp(84)))

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

    fun enter() {
        if (previewActive) return
        val pv = preview ?: return
        previewActive = true
        overlay.addPreview(pv)
    }

    fun exit() {
        if (!previewActive) return
        previewActive = false
        preview?.let { overlay.removePreview(it) }
    }

    fun dispose() {
        exit()
        preview = null
    }
}
