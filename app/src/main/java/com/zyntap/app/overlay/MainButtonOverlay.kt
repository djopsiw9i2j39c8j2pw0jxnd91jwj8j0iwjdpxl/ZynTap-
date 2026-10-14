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
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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

    /** Chữ hiện trên nút (tên nút). Tự thu nhỏ cho vừa lòng nút tròn. */
    var label = ""
        set(v) { if (field != v) { field = v; labelKey = ""; invalidate() } }

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
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.LEFT
        typeface = Typeface.DEFAULT_BOLD
    }
    private var labelKey = ""
    private var labelLayout: StaticLayout? = null
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

        // tên nút, tự co cỡ chữ cho vừa trong vòng tròn
        val lay = fitLabel(r)
        if (lay != null) {
            text.alpha = (255 * (0.55f + 0.45f * a)).toInt()
            canvas.save()
            canvas.translate(cx - lay.width / 2f, cy - lay.height / 2f)
            lay.draw(canvas)
            canvas.restore()
        }

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

    /**
     * Tìm cỡ chữ lớn nhất để [label] nằm gọn trong hình chữ nhật nội tiếp vòng tròn bán kính [r].
     * Ưu tiên 1 dòng; nếu 1 dòng quá nhỏ thì ngắt thành tối đa 3 dòng. Kết quả được nhớ lại,
     * nên vòng nét đứt xoay 60fps không phải tính lại.
     */
    private fun fitLabel(r: Float): StaticLayout? {
        val t = label.trim()
        if (t.isEmpty()) return null
        val key = "$t|${r.toInt()}"
        if (key == labelKey) return labelLayout
        labelKey = key

        val boxW = (r * 1.45f).toInt().coerceAtLeast(1)
        val boxH = r * 1.25f
        val maxSize = if (t.length <= 2) r * 0.85f else r * 0.62f
        val minSize = (5f * d).coerceAtMost(maxSize)

        fun build(size: Float, maxLines: Int): StaticLayout {
            text.textSize = size
            return StaticLayout.Builder.obtain(t, 0, t.length, text, boxW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(maxLines)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build()
        }
        fun fits(l: StaticLayout, maxLines: Int): Boolean {
            if (l.height > boxH || l.lineCount > maxLines) return false
            // không bị cắt "…" ở dòng cuối
            val last = l.lineCount - 1
            return l.getEllipsisCount(last) == 0 && l.getLineEnd(last) >= t.length
        }

        // 1 dòng: cỡ chữ theo bề ngang
        text.textSize = 100f
        val w100 = text.measureText(t).coerceAtLeast(1f)
        val oneSize = minOf(maxSize, 100f * boxW / w100)

        // nhiều dòng: giảm dần cỡ chữ đến khi vừa
        var multiSize = 0f
        var size = maxSize
        while (size >= minSize) {
            if (fits(build(size, 3), 3)) { multiSize = size; break }
            size -= 0.5f * d
        }

        val result = when {
            oneSize >= minSize && (multiSize == 0f || oneSize >= multiSize * 0.72f) -> build(oneSize, 1)
            multiSize > 0f -> build(multiSize, 3)
            else -> build(minSize, 3) // quá dài: cỡ nhỏ nhất, cắt "…"
        }
        labelLayout = result
        return result
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
    var onCancel: ((Int) -> Unit)? = null

    private class Entry(val view: MainButtonView, val params: WindowManager.LayoutParams, var addedAt: Long)

    private val entries = HashMap<Int, Entry>()
    private var editingId: Int? = null

    /** Ngón (slot getevent) đang đặt trên nút chạm xuyên: slot -> id nút. */
    private val watched = HashMap<Int, Int>()
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastStatus = ""

    /**
     * Chạm xuyên chỉ thật sự bật (cửa sổ nút -> NOT_TOUCHABLE) SAU KHI đã kiểm chứng: người dùng chạm vào nút
     * (lúc này nút vẫn nhận chạm bình thường) và ADB cũng báo đúng ngón chạm đó nằm trong nút.
     * Chưa kiểm chứng được thì nút main vẫn chạy bình thường, không bao giờ "chết".
     */
    private var passVerified = false
    private val viewDownAt = HashMap<Int, Long>()
    private val watchHitAt = HashMap<Int, Long>()

    private val watcher = TouchWatcher(ctx, screenBounds, object : TouchSink {
        override fun onFinger(slot: Int, down: Boolean, x: Int, y: Int) = onWatchedFinger(slot, down, x, y)
        override fun onLost() { cancelWatched(); setVerified(false) }
    }) { msg ->
        if (msg != lastStatus) {
            lastStatus = msg
            android.widget.Toast.makeText(ctx, "Chạm xuyên: $msg", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun setVerified(v: Boolean) {
        if (passVerified == v) return
        passVerified = v
        viewDownAt.clear(); watchHitAt.clear()
        if (v) android.widget.Toast.makeText(ctx, "Chạm xuyên: đã sẵn sàng", android.widget.Toast.LENGTH_SHORT).show()
        reapplyFlags()
    }

    private fun reapplyFlags() {
        entries.forEach { (id, e) ->
            val b = repo.find(id) ?: return@forEach
            applyTouchable(b, e.params)
            runCatching { wm.updateViewLayout(e.view, e.params) }
        }
    }

    private fun hitCircle(id: Int, x: Int, y: Int): Boolean {
        val e = entries[id] ?: return false
        val b = repo.find(id) ?: return false
        val dx = x - (e.params.x + e.params.width / 2f)
        val dy = y - (e.params.y + e.params.height / 2f)
        val r = dp(b.sizeDp) / 2f
        return dx * dx + dy * dy <= r * r
    }

    /** Nút nhận chạm thật (chưa xuyên) -> ghi lại để đối chiếu với toạ độ ADB. */
    private fun noteViewDown(id: Int) {
        val now = SystemClock.uptimeMillis()
        viewDownAt[id] = now
        checkVerify(id)
        uiHandler.postDelayed({
            if (!passVerified && viewDownAt[id] == now && watchHitAt[id].let { it == null || kotlin.math.abs(it - now) > VERIFY_MS }) {
                android.widget.Toast.makeText(
                    ctx, "Chạm xuyên: ADB chưa báo đúng toạ độ chạm, nút main chạy bình thường", android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }, VERIFY_MS + 300)
    }

    private fun checkVerify(id: Int) {
        val v = viewDownAt[id] ?: return
        val w = watchHitAt[id] ?: return
        if (kotlin.math.abs(v - w) <= VERIFY_MS) setVerified(true)
    }
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
        refreshWatcher()
    }

    // ---- chạm xuyên ---------------------------------------------------------

    /** Nút đang ở chế độ chạm xuyên (và không phải đang kéo chỉnh vị trí). */
    private fun isPassThrough(b: MainButton) = b.passThrough && passVerified && editingId != b.id

    /**
     * Cờ cửa sổ cho nút chạm xuyên: không nhận chạm (chạm lọt xuống app dưới).
     * Từ Android 12, chạm đi xuyên cửa sổ phủ của app khác bị CHẶN nếu cửa sổ đó đục hơn 0.8,
     * nên đặt alpha cửa sổ = 0.8 (đây là mức tối đa hệ thống cho phép).
     */
    private fun applyTouchable(b: MainButton, p: WindowManager.LayoutParams) {
        val through = isPassThrough(b)
        p.flags = if (through) p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        p.alpha = if (through) PASS_THROUGH_ALPHA else 1f
    }

    private fun refreshWatcher() {
        val need = OverlayPermission.granted(ctx) && repo.buttons.any { it.passThrough }
        if (need) watcher.start() else { watcher.stop(); cancelWatched(); setVerified(false) }
    }

    private fun buttonAt(x: Int, y: Int): Int? {
        // Nút thêm sau nằm trên: duyệt id lớn trước
        for (id in entries.keys.sortedDescending()) {
            val e = entries[id] ?: continue
            val b = repo.find(id) ?: continue
            if (!isPassThrough(b) || e.view.alpha < 0.05f) continue
            val cx = e.params.x + e.params.width / 2f
            val cy = e.params.y + e.params.height / 2f
            val r = dp(b.sizeDp) / 2f
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= r * r) return id
        }
        return null
    }

    private fun onWatchedFinger(slot: Int, down: Boolean, x: Int, y: Int) {
        if (!passVerified) {
            // Giai đoạn kiểm chứng: chỉ ghi nhận ngón chạm rơi trong nút chạm xuyên, không kích hoạt gì
            if (down) {
                val id = repo.buttons.firstOrNull { it.passThrough && editingId != it.id && hitCircle(it.id, x, y) }?.id
                if (id != null) { watchHitAt[id] = SystemClock.uptimeMillis(); checkVerify(id) }
            }
            return
        }
        if (down) {
            if (watched.containsKey(slot)) return
            val id = buttonAt(x, y) ?: return
            val b = repo.find(id) ?: return
            watched[slot] = id
            entries[id]?.view?.pressedVisual = true
            onAction?.invoke(b, true)
        } else {
            val id = watched.remove(slot) ?: return
            entries[id]?.view?.pressedVisual = watched.containsValue(id)
            val b = repo.find(id) ?: return
            if (!watched.containsValue(id)) onAction?.invoke(b, false)
        }
    }

    /** Huỷ các nút đang giữ qua chạm xuyên (mất ADB, tắt tính năng, xoay màn hình...). */
    private fun cancelWatched() {
        val ids = watched.values.toSet()
        watched.clear()
        ids.forEach { id ->
            entries[id]?.view?.pressedVisual = false
            onCancel?.invoke(id)
        }
    }

    private fun addView(b: MainButton) {
        val win = winSize(b)
        val p = newParams(
            win, win,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        applyTouchable(b, p)
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
        v.label = b.name
        v.fillPct = b.alphaPct
        v.ringPad = ringPad.toFloat()
    }

    private fun removeView(id: Int) {
        onCancel?.invoke(id)
        watched.entries.removeAll { it.value == id }
        val e = entries.remove(id) ?: return
        e.view.animate().cancel()
        runCatching { wm.removeViewImmediate(e.view) }
    }

    /** Nút mới được tạo. */
    fun onAdded(b: MainButton) {
        if (!entries.containsKey(b.id)) addView(b)
        refreshWatcher()
        entries[b.id]?.view?.let { v ->
            v.alpha = 0f
            v.scaleX = 0.5f
            v.scaleY = 0.5f
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260L).start()
        }
    }

    fun onRemoved(id: Int) {
        onCancel?.invoke(id)
        watched.entries.removeAll { it.value == id }
        if (editingId == id) editingId = null
        refreshWatcher()
        val e = entries[id] ?: return
        e.view.animate().cancel()
        e.view.animate().alpha(0f).scaleX(0.4f).scaleY(0.4f).setDuration(180L)
            .withEndAction { removeView(id); refreshWatcher() }.start()
    }

    /** Tên/độ mờ/size vừa đổi trong menu -> cập nhật ngay trên nút thật. */
    fun onChanged(b: MainButton) {
        onCancel?.invoke(b.id)
        watched.entries.removeAll { it.value == b.id }
        val e = entries[b.id] ?: return
        bind(e.view, b)
        val wasThrough = (e.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
        applyTouchable(b, e.params)
        if (wasThrough != isPassThrough(b)) runCatching { wm.updateViewLayout(e.view, e.params) }
        refreshWatcher()
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
        if (id != null) onCancel?.invoke(id)
        editingId = id
        entries.forEach { (k, e) ->
            e.view.editing = k == id
            val b = repo.find(k) ?: return@forEach
            val wasThrough = (e.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
            applyTouchable(b, e.params)
            // Đang chỉnh: nút phải nhận chạm để kéo được, dù bật chạm xuyên
            if (wasThrough != isPassThrough(b) && k != id) runCatching { wm.updateViewLayout(e.view, e.params) }
        }
        if (id != null) bringToFront(id)
        else entries.forEach { (_, e) -> runCatching { wm.updateViewLayout(e.view, e.params) } }
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

    /** Báo khi bắt đầu/thả kéo một nút main (để menu ẩn lúc kéo, hiện lại khi thả). */
    var onDrag: ((Boolean) -> Unit)? = null

    fun containsPoint(x: Int, y: Int): Boolean = entries.values.any { e ->
        val location = IntArray(2)
        e.view.getLocationOnScreen(location)
        x >= location[0] && x < location[0] + e.view.width &&
            y >= location[1] && y < location[1] + e.view.height
    }

    fun cancelTouches() {
        cancelWatched()
        entries.forEach { (id, e) -> e.view.pressedVisual = false; onCancel?.invoke(id) }
    }

    fun removeAll() {
        watcher.stop()
        cancelWatched()
        entries.keys.toList().forEach { removeView(it) }
    }

    private fun attachTouch(id: Int, v: MainButtonView, p: WindowManager.LayoutParams) {
        val slop = dp(4)
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var pointerId = -1
        v.setOnTouchListener { _, e ->
            val b = repo.find(id) ?: return@setOnTouchListener false
            val editing = editingId == id
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointerId = e.getPointerId(0)
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y
                    moved = false
                    v.pressedVisual = true
                    if (!editing && b.passThrough) noteViewDown(id)
                    if (!editing) onAction?.invoke(b, true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (editing) {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!moved && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                            moved = true
                            onDrag?.invoke(true) // bắt đầu kéo -> menu ẩn
                        }
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
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    if (pointerId == e.getPointerId(e.actionIndex)) {
                        pointerId = -1
                        v.pressedVisual = false
                        if (editing) { if (moved) { repo.save(); onDrag?.invoke(false) } } else onAction?.invoke(b, false)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pointerId = -1
                    v.pressedVisual = false
                    if (editing && moved) { repo.save(); onDrag?.invoke(false) }
                    onCancel?.invoke(id)
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> true
                else -> false
            }
        }
    }

    private companion object {
        const val RING_PAD_DP = 8
        const val ATTACH_GRACE_MS = 1500L
        const val PASS_THROUGH_ALPHA = 0.8f
        const val VERIFY_MS = 900L
    }
}
