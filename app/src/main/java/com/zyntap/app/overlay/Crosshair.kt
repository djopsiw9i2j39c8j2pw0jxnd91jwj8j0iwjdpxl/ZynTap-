package com.zyntap.app.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.zyntap.app.data.DebugLog
import kotlin.math.max
import kotlin.math.min

// ============================================================================
//  Tâm ảo + vòng quanh tâm: luôn nằm giữa màn hình, CHẠM XUYÊN hoàn toàn.
// ============================================================================

/** Bảng màu dùng chung cho tâm và vòng. */
internal val CROSS_COLORS = intArrayOf(
    0xFF19D3FF.toInt(), // xanh lơ
    0xFFFFFFFF.toInt(), // trắng
    0xFFFF4D5E.toInt(), // đỏ
    0xFF3DFF8A.toInt(), // xanh lá
    0xFFFFE14D.toInt(), // vàng
    0xFFFF5BD1.toInt(), // hồng
)

internal val CROSS_STYLES = listOf(
    "Chữ thập", "Chấm tròn", "Chữ thập chấm", "Góc ngắm", "Chữ T", "Kim cương",
)

/** Ảnh chụp cấu hình (đơn vị px) để vẽ. */
internal data class CrossSpec(
    val dotOn: Boolean,
    val style: Int,
    val sizePx: Float,
    val alphaPct: Int,
    val color: Int,
    val ringOn: Boolean,
    val ringPx: Float,       // đường kính vòng
    val ringThickPx: Float,
    val ringAlphaPct: Int,
    val ringColor: Int,
)

/** Cấu hình lưu bền (SharedPreferences). */
class CrosshairSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("zyntap_crosshair", Context.MODE_PRIVATE)

    private fun int(key: String, def: Int) = prefs.getInt(key, def)
    private fun put(key: String, v: Int) = prefs.edit().putInt(key, v).apply()

    var dotOn: Boolean
        get() = prefs.getBoolean("dot_on", false)
        set(v) = prefs.edit().putBoolean("dot_on", v).apply()
    var style: Int
        get() = int("style", 0).coerceIn(0, CROSS_STYLES.lastIndex)
        set(v) = put("style", v)
    var sizeDp: Int
        get() = int("size", 36).coerceIn(SIZE_MIN, SIZE_MAX)
        set(v) = put("size", v.coerceIn(SIZE_MIN, SIZE_MAX))
    var alphaPct: Int
        get() = int("alpha", 90).coerceIn(ALPHA_MIN, 100)
        set(v) = put("alpha", v.coerceIn(ALPHA_MIN, 100))
    var color: Int
        get() = int("color", 0).coerceIn(0, CROSS_COLORS.lastIndex)
        set(v) = put("color", v)

    var ringOn: Boolean
        get() = prefs.getBoolean("ring_on", false)
        set(v) = prefs.edit().putBoolean("ring_on", v).apply()
    var ringSizeDp: Int
        get() = int("ring_size", 90).coerceIn(RING_MIN, RING_MAX)
        set(v) = put("ring_size", v.coerceIn(RING_MIN, RING_MAX))
    var ringThickDp: Int
        get() = int("ring_thick", 2).coerceIn(THICK_MIN, THICK_MAX)
        set(v) = put("ring_thick", v.coerceIn(THICK_MIN, THICK_MAX))
    var ringAlphaPct: Int
        get() = int("ring_alpha", 70).coerceIn(ALPHA_MIN, 100)
        set(v) = put("ring_alpha", v.coerceIn(ALPHA_MIN, 100))
    var ringColor: Int
        get() = int("ring_color", 0).coerceIn(0, CROSS_COLORS.lastIndex)
        set(v) = put("ring_color", v)

    /** [d] = mật độ đã co giãn (UiScale.density). */
    internal fun spec(d: Float) = CrossSpec(
        dotOn = dotOn, style = style, sizePx = sizeDp * d, alphaPct = alphaPct,
        color = CROSS_COLORS[color],
        ringOn = ringOn, ringPx = ringSizeDp * d, ringThickPx = ringThickDp * d,
        ringAlphaPct = ringAlphaPct, ringColor = CROSS_COLORS[ringColor],
    )

    companion object {
        const val SIZE_MIN = 8
        const val SIZE_MAX = 120
        const val RING_MIN = 20
        const val RING_MAX = 400
        const val THICK_MIN = 1
        const val THICK_MAX = 12
        const val ALPHA_MIN = 10
    }
}

/** View vẽ tâm + vòng. Dùng cho cả cửa sổ nổi, ô xem trước và các ô chọn kiểu. */
internal class CrosshairView(context: Context) : View(context) {

    var spec: CrossSpec? = null
        set(v) { field = v; invalidate() }

    /** true: co nhỏ cho vừa khung (ô xem trước / ô chọn kiểu). */
    var fit = false

    /** Chữ hiện khi không có gì để vẽ (chỉ dùng ở ô xem trước). */
    var emptyHint: String? = null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF596D8D.toInt(); textAlign = Paint.Align.CENTER
        textSize = 12f * UiScale.density(context.resources)
    }
    private val path = Path()
    private val d = UiScale.density(context.resources)

    override fun onDraw(canvas: Canvas) {
        val s = spec ?: return
        val cx = width / 2f
        val cy = height / 2f
        val drawDot = s.dotOn
        val drawRing = s.ringOn
        if (!drawDot && !drawRing) {
            emptyHint?.let { canvas.drawText(it, cx, cy + hint.textSize / 3f, hint) }
            return
        }
        var scale = 1f
        if (fit) {
            val extent = max(if (drawDot) s.sizePx else 0f, if (drawRing) s.ringPx + s.ringThickPx else 0f)
            val room = min(width, height) - 6f * d
            if (extent > 0f) scale = min(1f, room / extent)
        }
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        if (drawRing) {
            val r = (s.ringPx - s.ringThickPx) / 2f
            stroked(canvas, s.ringColor, s.ringAlphaPct, s.ringThickPx) { c, p -> c.drawCircle(0f, 0f, r, p) }
        }
        if (drawDot) drawStyle(canvas, s)
        canvas.restore()
    }

    /** Vẽ một nét: lớp viền tối mỏng bên dưới cho dễ thấy trên nền sáng, rồi nét màu. */
    private fun stroked(c: Canvas, color: Int, alphaPct: Int, w: Float, shape: (Canvas, Paint) -> Unit) {
        val a = (alphaPct * 255 / 100).coerceIn(0, 255)
        val edge = max(1f * d, w * 0.5f)
        line.strokeWidth = w + edge
        line.color = Color.argb(a * 3 / 5, 0, 0, 0)
        shape(c, line)
        line.strokeWidth = w
        line.color = (color and 0x00FFFFFF) or (a shl 24)
        shape(c, line)
    }

    private fun solid(c: Canvas, color: Int, alphaPct: Int, x: Float, y: Float, r: Float) {
        val a = (alphaPct * 255 / 100).coerceIn(0, 255)
        fill.color = Color.argb(a * 3 / 5, 0, 0, 0)
        c.drawCircle(x, y, r + max(1f * d, r * 0.35f), fill)
        fill.color = (color and 0x00FFFFFF) or (a shl 24)
        c.drawCircle(x, y, r, fill)
    }

    private fun drawStyle(c: Canvas, s: CrossSpec) {
        val half = s.sizePx / 2f
        val w = (s.sizePx * 0.06f).coerceIn(1.5f * d, 4f * d)
        val col = s.color
        val a = s.alphaPct
        when (s.style) {
            0 -> { // chữ thập có khoảng hở ở giữa
                val gap = half * 0.28f
                stroked(c, col, a, w) { cv, p ->
                    cv.drawLine(-half, 0f, -gap, 0f, p); cv.drawLine(gap, 0f, half, 0f, p)
                    cv.drawLine(0f, -half, 0f, -gap, p); cv.drawLine(0f, gap, 0f, half, p)
                }
            }
            1 -> { // chấm tròn + vòng mảnh
                solid(c, col, a, 0f, 0f, max(half * 0.17f, w))
                stroked(c, col, a, w) { cv, p -> cv.drawCircle(0f, 0f, half * 0.62f, p) }
            }
            2 -> { // chữ thập + chấm giữa
                val gap = half * 0.36f
                stroked(c, col, a, w) { cv, p ->
                    cv.drawLine(-half, 0f, -gap, 0f, p); cv.drawLine(gap, 0f, half, 0f, p)
                    cv.drawLine(0f, -half, 0f, -gap, p); cv.drawLine(0f, gap, 0f, half, p)
                }
                solid(c, col, a, 0f, 0f, max(half * 0.09f, w * 0.8f))
            }
            3 -> { // góc ngắm: 4 góc vuông + chấm giữa
                val o = half * 0.92f
                val arm = half * 0.38f
                stroked(c, col, a, w) { cv, p ->
                    path.reset()
                    path.moveTo(-o, -o + arm); path.lineTo(-o, -o); path.lineTo(-o + arm, -o)
                    path.moveTo(o - arm, -o); path.lineTo(o, -o); path.lineTo(o, -o + arm)
                    path.moveTo(o, o - arm); path.lineTo(o, o); path.lineTo(o - arm, o)
                    path.moveTo(-o + arm, o); path.lineTo(-o, o); path.lineTo(-o, o - arm)
                    cv.drawPath(path, p)
                }
                solid(c, col, a, 0f, 0f, max(half * 0.07f, w * 0.75f))
            }
            4 -> { // chữ T: không có vạch trên
                val gap = half * 0.22f
                stroked(c, col, a, w) { cv, p ->
                    cv.drawLine(-half, 0f, -gap, 0f, p); cv.drawLine(gap, 0f, half, 0f, p)
                    cv.drawLine(0f, gap, 0f, half, p)
                }
                solid(c, col, a, 0f, 0f, max(half * 0.07f, w * 0.75f))
            }
            else -> { // kim cương + chấm giữa
                val r = half * 0.85f
                stroked(c, col, a, w) { cv, p ->
                    path.reset()
                    path.moveTo(0f, -r); path.lineTo(r, 0f); path.lineTo(0f, r); path.lineTo(-r, 0f); path.close()
                    cv.drawPath(path, p)
                }
                solid(c, col, a, 0f, 0f, max(half * 0.09f, w * 0.8f))
            }
        }
    }
}

/** Cửa sổ nổi giữa màn hình. FLAG_NOT_TOUCHABLE: mọi cú chạm đi xuyên qua xuống app bên dưới. */
class CrosshairOverlay(
    private val ctx: Context,
    private val wm: WindowManager,
    private val settings: CrosshairSettings,
    private val screenBounds: () -> Rect,
    private val newParams: (width: Int, height: Int, flags: Int) -> WindowManager.LayoutParams,
) {
    private var view: CrosshairView? = null
    private var params: WindowManager.LayoutParams? = null
    private var addedAt = 0L
    private val d get() = UiScale.density(ctx.resources)

    private fun windowSize(s: CrossSpec): Int {
        val extent = max(if (s.dotOn) s.sizePx else 0f, if (s.ringOn) s.ringPx + s.ringThickPx else 0f)
        return (extent + 8f * d).toInt().coerceAtLeast(1)
    }

    private fun place(p: WindowManager.LayoutParams) {
        val b = screenBounds()
        p.x = b.centerX() - p.width / 2
        p.y = b.centerY() - p.height / 2
    }

    /** Dựng / cập nhật / gỡ cho khớp cấu hình hiện tại. Gọi lặp lại thoải mái. */
    fun ensure() {
        if (!OverlayPermission.granted(ctx)) return
        val s = settings.spec(d)
        if (!s.dotOn && !s.ringOn) { remove(); return }
        val v = view
        if (v != null && !v.isAttachedToWindow && SystemClock.uptimeMillis() - addedAt > 1500L) {
            runCatching { wm.removeViewImmediate(v) }
            view = null; params = null
        }
        val cur = view
        val p = params
        if (cur == null || p == null) {
            // Đang dựng mới: gỡ mọi cửa sổ tâm cũ còn sót (do dịch vụ trước tắt giữa chừng) để không bị chồng/kẹt
            sweepOrphans()
            val size = windowSize(s)
            val np = newParams(
                size, size,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            place(np)
            val nv = CrosshairView(ctx).apply { spec = s; isClickable = false; isFocusable = false }
            addedAt = SystemClock.uptimeMillis()
            runCatching { wm.addView(nv, np) }
                .onSuccess { view = nv; params = np; live.add(wm to nv) }
                .onFailure { DebugLog.e("Tâm ảo", "Không gắn được cửa sổ tâm ảo", it) }
        } else {
            cur.spec = s
            val size = windowSize(s)
            val before = Triple(p.width, p.x, p.y)
            p.width = size; p.height = size
            place(p)
            if (before != Triple(p.width, p.x, p.y)) runCatching { wm.updateViewLayout(cur, p) }
        }
    }

    fun fadeOut(ms: Long) { view?.animate()?.alpha(0f)?.setDuration(ms)?.start() }

    fun restore() { view?.animate()?.cancel(); view?.alpha = 1f }

    /** Gỡ tâm khỏi màn hình cho chắc: thử removeViewImmediate, lỗi thì removeView, rồi quét cả cửa sổ cũ còn sót. */
    fun remove() {
        view?.let { detach(wm, it) }
        view = null; params = null
        sweepOrphans()
    }

    /** Mô tả ngắn cho tab Debug log. */
    fun state(): String {
        val v = view
        val s = settings.spec(d)
        val cfg = "tâm ${if (s.dotOn) "bật" else "tắt"}, vòng ${if (s.ringOn) "bật" else "tắt"}"
        val on = when {
            v == null -> "không có cửa sổ"
            v.isAttachedToWindow -> "đang hiện (alpha ${"%.1f".format(v.alpha)})"
            else -> "cửa sổ chưa gắn"
        }
        return "$cfg · $on · cửa sổ tâm đang đăng ký: ${live.size}"
    }

    companion object {
        /** Mọi cửa sổ tâm đã gắn vào màn hình (kèm WindowManager đã gắn nó) — để gỡ nốt nếu chủ của nó đã chết. */
        private val live = java.util.Collections.synchronizedList(ArrayList<Pair<WindowManager, View>>())

        private fun detach(wm: WindowManager, v: View): Boolean {
            runCatching { v.animate().cancel() }
            var err: Throwable? = null
            var ok = runCatching { wm.removeViewImmediate(v) }.onFailure { err = it }.isSuccess
            if (!ok) ok = runCatching { wm.removeView(v) }.onFailure { err = it }.isSuccess
            // Lỗi "view not attached" nghĩa là nó đã không còn trên màn hình -> coi như xong
            val gone = ok || !v.isAttachedToWindow
            if (gone) live.removeAll { it.second === v }
            else DebugLog.e("Tâm ảo", "Không gỡ được cửa sổ tâm khỏi màn hình (sẽ thử lại)", err)
            return gone
        }

        /**
         * Gỡ mọi cửa sổ tâm trừ [keep]. Gọi khi dựng mới / tắt tâm / tắt dịch vụ / mở app,
         * nên tâm không thể kẹt lại trên màn hình dù lần tắt trước bị lỗi giữa chừng.
         * @return số cửa sổ sót đã gỡ
         */
        fun sweepOrphans(keep: View? = null): Int {
            val snapshot = synchronized(live) { live.toList() }
            var n = 0
            for ((w, v) in snapshot) {
                if (v === keep) continue
                if (detach(w, v)) n++
            }
            if (n > 0) DebugLog.i("Tâm ảo", "Đã gỡ $n cửa sổ tâm còn sót trên màn hình")
            return n
        }
    }
}

// ============================================================================
//  Tab "Cài tâm ảo"
// ============================================================================

class CrosshairTab(
    private val ctx: Context,
    private val settings: CrosshairSettings,
    /** Cấu hình vừa đổi -> cập nhật cửa sổ tâm ngoài màn hình. */
    private val onChanged: () -> Unit,
) {
    private val d = UiScale.density(ctx.resources)
    private fun dp(n: Int) = (n * d).toInt()
    private val fg = 0xFFE8EEFA.toInt()
    private val muted = 0xFF8D9DBA.toInt()
    private val cyan = 0xFF19D3FF.toInt()

    private lateinit var preview: CrosshairView
    private val styleTiles = mutableListOf<Pair<CrosshairView, View>>()
    private lateinit var dotGroup: LinearLayout
    private lateinit var ringGroup: LinearLayout

    private fun round(color: Int, stroke: Int = 0xFF24344E.toInt(), r: Int = 12) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); setStroke(dp(1), stroke)
    }

    private fun changed() {
        refreshPreview()
        onChanged()
    }

    private fun refreshPreview() {
        if (!::preview.isInitialized) return
        preview.spec = settings.spec(d)
        styleTiles.forEachIndexed { i, (v, frame) ->
            v.spec = settings.spec(d).copy(
                dotOn = true, ringOn = false, style = i, sizePx = dp(30).toFloat(), alphaPct = 100,
            )
            frame.background = round(
                if (i == settings.style) 0xFF123049.toInt() else 0xFF0A1220.toInt(),
                if (i == settings.style) cyan else 0xFF24344E.toInt(),
            )
        }
        dotGroup.alpha = if (settings.dotOn) 1f else 0.45f
        ringGroup.alpha = if (settings.ringOn) 1f else 0.45f
    }

    fun build(): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), 0)
        }
        addTabHeader(ctx, root, "Cài tâm ảo", "Tâm và vòng tròn giữa màn hình, chạm xuyên")
        val page = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(14))
        }

        // ---- ô xem trước ----
        preview = CrosshairView(ctx).apply {
            fit = true
            emptyHint = "Đang tắt cả tâm và vòng"
            background = round(0xFF0A1220.toInt())
        }
        page.addView(preview, LinearLayout.LayoutParams(-1, dp(120)))

        // ---- nhóm TÂM ----
        page.addView(toggleRow("Tâm ảo", "Hiện tâm ở giữa màn hình.", settings.dotOn) {
            settings.dotOn = it; changed()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        dotGroup = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        dotGroup.addView(sectionLabel("Kiểu tâm"))
        dotGroup.addView(styleGrid())
        dotGroup.addView(slider("Kích thước", CrosshairSettings.SIZE_MIN, CrosshairSettings.SIZE_MAX, settings.sizeDp, " dp") {
            settings.sizeDp = it; changed()
        })
        dotGroup.addView(slider("Độ mờ", CrosshairSettings.ALPHA_MIN, 100, settings.alphaPct, "%") {
            settings.alphaPct = it; changed()
        })
        dotGroup.addView(sectionLabel("Màu tâm"))
        dotGroup.addView(swatches(settings.color) { settings.color = it; changed() })
        page.addView(dotGroup)

        page.addView(divider())

        // ---- nhóm VÒNG ----
        page.addView(toggleRow("Vòng quanh tâm", "Hiện vòng tròn bao quanh tâm.", settings.ringOn) {
            settings.ringOn = it; changed()
        })
        ringGroup = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        ringGroup.addView(slider("Kích thước vòng", CrosshairSettings.RING_MIN, CrosshairSettings.RING_MAX, settings.ringSizeDp, " dp") {
            settings.ringSizeDp = it; changed()
        })
        ringGroup.addView(slider("Độ dày nét", CrosshairSettings.THICK_MIN, CrosshairSettings.THICK_MAX, settings.ringThickDp, " dp") {
            settings.ringThickDp = it; changed()
        })
        ringGroup.addView(slider("Độ mờ", CrosshairSettings.ALPHA_MIN, 100, settings.ringAlphaPct, "%") {
            settings.ringAlphaPct = it; changed()
        })
        ringGroup.addView(sectionLabel("Màu vòng"))
        ringGroup.addView(swatches(settings.ringColor) { settings.ringColor = it; changed() })
        page.addView(ringGroup)

        page.addView(TextView(ctx).apply {
            text = "Tâm và vòng chạm xuyên: không chặn thao tác bên dưới."
            setTextColor(muted); scaledTextSize(11f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        refreshPreview()
        root.addView(ScrollView(ctx).apply {
            isFillViewport = true; isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(page, ViewGroup.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    // ---- thành phần giao diện ----

    private fun sectionLabel(text: String) = TextView(ctx).apply {
        this.text = text; setTextColor(muted); scaledTextSize(12f)
        setPadding(dp(2), dp(12), 0, dp(5))
    }

    private fun divider() = View(ctx).apply { setBackgroundColor(0xFF1E2B44.toInt()) }.also {
        it.layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(14); bottomMargin = dp(6) }
    }

    private fun toggleRow(title: String, desc: String, checked: Boolean, onToggle: (Boolean) -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            text = title; setTextColor(fg); scaledTextSize(14.5f); setTypeface(typeface, Typeface.BOLD)
        })
        texts.addView(TextView(ctx).apply { text = desc; setTextColor(muted); scaledTextSize(11.5f) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        val sw = Switch(ctx).apply {
            isChecked = checked
            thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(cyan, 0xFF2A3A5C.toInt()),
            )
            setOnCheckedChangeListener { _, on -> onToggle(on) }
        }
        row.addView(sw, LinearLayout.LayoutParams(-2, -2))
        row.setOnClickListener { sw.toggle() }
        return row
    }

    private fun styleGrid(): View {
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        styleTiles.clear()
        val perRow = 3
        CROSS_STYLES.indices.chunked(perRow).forEachIndexed { rowIdx, idxs ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            idxs.forEachIndexed { col, i ->
                val frame = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(2), dp(4), dp(2), dp(4)); isClickable = true
                    setOnClickListener { settings.style = i; changed() }
                }
                val v = CrosshairView(ctx).apply { fit = true }
                frame.addView(v, LinearLayout.LayoutParams(-1, dp(44)))
                frame.addView(TextView(ctx).apply {
                    text = CROSS_STYLES[i]; setTextColor(muted); scaledTextSize(10.5f)
                    gravity = Gravity.CENTER; maxLines = 1
                })
                styleTiles.add(v to frame)
                row.addView(frame, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (col > 0) marginStart = dp(6)
                })
            }
            // Hàng cuối thiếu ô: chèn ô trống cho thẳng cột
            repeat(perRow - idxs.size) { row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(6) }) }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { if (rowIdx > 0) topMargin = dp(6) })
        }
        return grid
    }

    private fun swatches(selected: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val dots = mutableListOf<View>()
        fun paint(sel: Int) = dots.forEachIndexed { i, v ->
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CROSS_COLORS[i])
                setStroke(dp(if (i == sel) 3 else 1), if (i == sel) 0xFFFFFFFF.toInt() else 0xFF24344E.toInt())
            }
        }
        CROSS_COLORS.indices.forEach { i ->
            val v = View(ctx).apply {
                isClickable = true
                setOnClickListener { paint(i); onPick(i) }
            }
            dots.add(v)
            row.addView(v, LinearLayout.LayoutParams(dp(30), dp(30)).apply { if (i > 0) marginStart = dp(10) })
        }
        paint(selected)
        return row
    }

    private fun slider(label: String, min: Int, max: Int, value: Int, unit: String, onValue: (Int) -> Unit): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(2), dp(12), dp(2), dp(2))
        }
        head.addView(TextView(ctx).apply { text = label; setTextColor(muted); scaledTextSize(12f) },
            LinearLayout.LayoutParams(0, -2, 1f))
        val valueText = TextView(ctx).apply {
            text = "$value$unit"; setTextColor(fg); scaledTextSize(12.5f); setTypeface(typeface, Typeface.BOLD)
        }
        head.addView(valueText)
        box.addView(head)
        val bar = SeekBar(ctx).apply {
            this.min = min; this.max = max; progress = value
            progressTintList = ColorStateList.valueOf(cyan)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF2A3A5C.toInt())
            thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            // Kéo thanh trượt sang trái không được làm menu thu lại
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    valueText.text = "$p$unit"
                    if (fromUser) onValue(p)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        box.addView(bar, LinearLayout.LayoutParams(-1, dp(32)))
        return box
    }
}
