package com.zyntap.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator

/**
 * Nút tròn "main" vẽ bằng 1 View duy nhất.
 * - Độ mờ [fillPct] chỉ áp dụng cho phần thân; vòng viền chỉnh sửa luôn rõ để còn tìm thấy nút.
 * - [editing]: hiện vòng nét đứt xoay quanh nút (đang ở trạng thái chỉnh sửa, kéo được).
 */
class MainButtonView(context: Context) : View(context) {

    var number = 1
        set(v) { field = v; invalidate() }

    /** Độ đặc của thân nút, 0..100. */
    var fillPct = 80
        set(v) { field = v; invalidate() }

    /** Khoảng chừa quanh nút (px) cho vòng viền chỉnh sửa. */
    var ringPad = 0f
        set(v) { field = v; invalidate() }

    var editing = false
        set(v) {
            if (field == v) return
            field = v
            if (v) startSpin() else stopSpin()
            invalidate()
        }

    /** Đang bị chạm (hiệu ứng nhấn) hoặc đang được nhấc lên để kéo. */
    var pressedVisual = false
        set(v) {
            if (field == v) return
            field = v
            animate().cancel()
            animate().scaleX(if (v) 0.92f else 1f).scaleY(if (v) 0.92f else 1f).setDuration(110L).start()
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
        color = CYAN
        pathEffect = DashPathEffect(floatArrayOf(7f * d, 6f * d), 0f)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private var spin = 0f
    private var spinner: ValueAnimator? = null
    private var shaderKey = 0f

    private fun startSpin() {
        spinner?.cancel()
        spinner = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 7000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { spin = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopSpin() {
        spinner?.cancel()
        spinner = null
    }

    override fun onDetachedFromWindow() {
        spinner?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (editing && spinner == null) startSpin()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - ringPad
        if (r <= 0f) return
        val a = fillPct / 100f

        // thân nút: gradient xanh, độ đặc theo [fillPct]
        val key = r
        if (fill.shader == null || shaderKey != key) {
            fill.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, BLUE, CYAN, Shader.TileMode.CLAMP)
            shaderKey = key
        }
        val bodyA = if (pressedVisual) minOf(1f, a + 0.2f) else a
        fill.alpha = (255 * bodyA).toInt()
        canvas.drawCircle(cx, cy, r, fill)

        edge.color = 0xFFFFFFFF.toInt()
        edge.alpha = (255 * (0.2f + 0.4f * a)).toInt()
        canvas.drawCircle(cx, cy, r - edge.strokeWidth / 2f, edge)

        // số thứ tự nút (main1 -> "1")
        text.textSize = r * 0.85f
        text.alpha = (255 * (0.45f + 0.55f * a)).toInt()
        val ty = cy - (text.descent() + text.ascent()) / 2f
        canvas.drawText(number.toString(), cx, ty, text)

        if (editing) {
            val rr = r + ringPad * 0.55f
            glow.color = CYAN
            glow.alpha = 38
            canvas.drawCircle(cx, cy, rr, glow)
            canvas.save()
            canvas.rotate(spin, cx, cy)
            canvas.drawCircle(cx, cy, rr, ring)
            canvas.restore()
        }
    }

    private companion object {
        const val CYAN = 0xFF19D3FF.toInt()
        const val BLUE = 0xFF2A6BFF.toInt()
    }
}

/**
 * Quản lý các nút tròn main nổi trên màn hình (mỗi nút 1 cửa sổ overlay nhỏ).
 *
 *  - Bình thường: nút hiện ra, chạm vào có hiệu ứng nhấn; sự kiện báo qua [onAction]
 *    (true = vừa chạm xuống, false = vừa nhả) — chỗ để gắn chức năng bấm thật theo từng chế độ sau này.
 *  - Trạng thái chỉnh sửa ([setEditing]): nút được đưa lên trên cùng, có vòng nét đứt, kéo thả tự do.
 */
class MainButtonOverlay(
    private val ctx: Context,
    private val wm: WindowManager,
    private val repo: MainButtonRepo,
    private val screenBounds: () -> Rect,
    private val newParams: (width: Int, height: Int, flags: Int) -> WindowManager.LayoutParams,
) {
    var onAction: ((MainButton, Boolean) -> Unit)? = null

    private class Entry(val view: MainButtonView, val params: WindowManager.LayoutParams, var addedAt: Long)

    private val entries = HashMap<Int, Entry>()
    private var editingId: Int? = null
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val ringPad get() = dp(RING_PAD_DP)

    private fun winSize(b: MainButton) = dp(b.sizeDp) + ringPad * 2

    private fun place(b: MainButton, p: WindowManager.LayoutParams) {
        val s = screenBounds()
        val maxX = (s.width() - p.width).coerceAtLeast(0)
        val maxY = (s.height() - p.height).coerceAtLeast(0)
        p.x = (b.x * maxX).toInt().coerceIn(0, maxX)
        p.y = (b.y * maxY).toInt().coerceIn(0, maxY)
    }

    /** Thêm nút còn thiếu, dựng lại nút bị hệ thống gỡ mất, bỏ nút đã xoá. Gọi lặp lại thoải mái. */
    fun ensureAll() {
        if (!OverlayPermission.granted(ctx)) return
        val now = SystemClock.uptimeMillis()
        entries.entries.toList().forEach { (id, e) ->
            if (repo.find(id) == null) removeView(id)
            else if (now - e.addedAt > ATTACH_GRACE_MS && !e.view.isAttachedToWindow) removeView(id)
        }
        repo.buttons.forEach { if (!entries.containsKey(it.id)) addView(it) }
    }

    private fun addView(b: MainButton) {
        val win = winSize(b)
        val p = newParams(
            win, win,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        place(b, p)
        val v = MainButtonView(ctx)
        bind(v, b)
        v.editing = b.id == editingId
        attachTouch(b.id, v, p)
        runCatching { wm.addView(v, p) }.onSuccess {
            entries[b.id] = Entry(v, p, SystemClock.uptimeMillis())
        }
    }

    private fun bind(v: MainButtonView, b: MainButton) {
        v.number = b.id
        v.fillPct = b.alphaPct
        v.ringPad = ringPad.toFloat()
    }

    private fun removeView(id: Int) {
        val e = entries.remove(id) ?: return
        e.view.animate().cancel()
        runCatching { wm.removeViewImmediate(e.view) }
    }

    /** Nút mới được tạo. */
    fun onAdded(b: MainButton) {
        if (!entries.containsKey(b.id)) addView(b)
        entries[b.id]?.view?.let { v ->
            v.alpha = 0f
            v.scaleX = 0.5f
            v.scaleY = 0.5f
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260L).start()
        }
    }

    fun onRemoved(id: Int) {
        if (editingId == id) editingId = null
        val e = entries[id] ?: return
        e.view.animate().cancel()
        e.view.animate().alpha(0f).scaleX(0.4f).scaleY(0.4f).setDuration(180L)
            .withEndAction { removeView(id) }.start()
    }

    /** Tên/độ mờ/size vừa đổi trong menu -> cập nhật ngay trên nút thật. */
    fun onChanged(b: MainButton) {
        val e = entries[b.id] ?: return
        bind(e.view, b)
        val win = winSize(b)
        if (e.params.width != win) {
            // giữ nguyên tâm nút khi đổi size
            val cx = e.params.x + e.params.width / 2
            val cy = e.params.y + e.params.height / 2
            e.params.width = win
            e.params.height = win
            val s = screenBounds()
            val maxX = (s.width() - win).coerceAtLeast(0)
            val maxY = (s.height() - win).coerceAtLeast(0)
            e.params.x = (cx - win / 2).coerceIn(0, maxX)
            e.params.y = (cy - win / 2).coerceIn(0, maxY)
            b.x = if (maxX > 0) e.params.x / maxX.toFloat() else 0.5f
            b.y = if (maxY > 0) e.params.y / maxY.toFloat() else 0.5f
            runCatching { wm.updateViewLayout(e.view, e.params) }
        }
    }

    /** Vào/ra trạng thái chỉnh sửa của một nút (null = không nút nào đang chỉnh). */
    fun setEditing(id: Int?) {
        editingId = id
        entries.forEach { (k, e) -> e.view.editing = k == id }
        if (id != null) bringToFront(id)
    }

    /** Đưa cửa sổ nút lên trên cùng (trên cả menu) bằng cách gắn lại cửa sổ. */
    private fun bringToFront(id: Int) {
        val e = entries[id] ?: return
        runCatching { wm.removeViewImmediate(e.view) }
        val ok = runCatching { wm.addView(e.view, e.params) }.isSuccess
        if (ok) e.addedAt = SystemClock.uptimeMillis() else entries.remove(id)
    }

    /** Đặt lại vị trí tất cả nút (sau khi xoay màn hình). */
    fun relayoutAll() {
        entries.forEach { (id, e) ->
            val b = repo.find(id) ?: return@forEach
            place(b, e.params)
            runCatching { wm.updateViewLayout(e.view, e.params) }
        }
    }

    /** Làm mờ/hiện lại toàn bộ nút (dùng khi đóng menu nổi). */
    fun fadeAll(to: Float, durationMs: Long) {
        entries.values.forEach { it.view.animate().alpha(to).setDuration(durationMs).start() }
    }

    fun removeAll() {
        entries.keys.toList().forEach { removeView(it) }
    }

    private fun attachTouch(id: Int, v: MainButtonView, p: WindowManager.LayoutParams) {
        val slop = dp(4)
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        v.setOnTouchListener { _, e ->
            val b = repo.find(id) ?: return@setOnTouchListener false
            val editing = editingId == id
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y
                    moved = false
                    v.pressedVisual = true
                    if (!editing) onAction?.invoke(b, true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (editing) {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!moved && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) moved = true
                        if (moved) {
                            val s = screenBounds()
                            val maxX = (s.width() - p.width).coerceAtLeast(0)
                            val maxY = (s.height() - p.height).coerceAtLeast(0)
                            p.x = (startX + dx).toInt().coerceIn(0, maxX)
                            p.y = (startY + dy).toInt().coerceIn(0, maxY)
                            b.x = if (maxX > 0) p.x / maxX.toFloat() else 0.5f
                            b.y = if (maxY > 0) p.y / maxY.toFloat() else 0.5f
                            runCatching { wm.updateViewLayout(v, p) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.pressedVisual = false
                    if (editing) { if (moved) repo.save() } else onAction?.invoke(b, false)
                    true
                }
                else -> false
            }
        }
    }

    private companion object {
        const val RING_PAD_DP = 8
        const val ATTACH_GRACE_MS = 1500L
    }
}
