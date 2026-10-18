package com.zyntap.app.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
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

    /** Độ đặc của thân nút, 0..100 (0 = vô hình). */
    var fillPct = 80
        set(v) { field = v; invalidate() }

    /** Sàn độ đặc đang áp dụng (menu mở = [MainButton.MENU_MIN_ALPHA], đóng = 0), để nút 0% vẫn thấy được lúc chỉnh. */
    var minPct = 0
        set(v) { if (field != v) { field = v; invalidate() } }

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

    // ---- Delay Mode: lớp tối + số đếm ngược ---------------------------------

    private var cdEnd = 0L      // uptimeMillis lúc hết đếm ngược (0 = không đếm)
    private var cdTotal = 1L

    /** Bắt đầu đếm ngược [totalMs]: nút phủ lớp tối, viền chuyển đỏ và hiện số giây còn lại. */
    fun startCountdown(totalMs: Long) {
        cdTotal = totalMs.coerceAtLeast(1L)
        cdEnd = SystemClock.uptimeMillis() + cdTotal
        invalidate()
    }

    fun stopCountdown() {
        if (cdEnd == 0L) return
        cdEnd = 0L
        invalidate()
    }

    private val d = UiScale.density(resources)
    /** Lớp tối phủ thân nút lúc delay: tối dần ra mép (vignette), alpha nhân theo độ mờ của nút. */
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var dimKey = 0f
    /** Chấm sáng nhỏ ở đầu cung đỏ. */
    private val delayHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFC2C2.toInt() }
    /** Viền đỏ đặc của Delay Mode: đè đúng lên viền trắng mỏng của nút. */
    private val delayEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        color = DELAY_RED
    }
    private val delayBox = RectF()
    private val cdText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        fontFeatureSettings = "tnum" // chữ số cùng bề ngang: số đếm không bị "nhảy" khi đổi
    }
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
        val a = maxOf(fillPct, minPct) / 100f
        val now = SystemClock.uptimeMillis()
        val counting = cdEnd != 0L && now < cdEnd
        if (cdEnd != 0L && !counting) {
            // Hết đếm ngược: nháy nhẹ cho biết đã bấm lại được
            cdEnd = 0L
            post { pulseReady() }
        }
        if (a <= 0f && !editing) {
            // 0% và menu đóng: nút vô hình hoàn toàn (vẫn nhận chạm), hiệu ứng delay cũng ẩn theo
            if (counting) postInvalidateDelayed(cdEnd - now + 16L)
            return
        }

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
        edge.alpha = if (a <= 0f) 0 else (255 * (0.2f + 0.4f * a)).toInt()
        canvas.drawCircle(cx, cy, r - edge.strokeWidth / 2f, edge)

        // tên nút, tự co cỡ chữ cho vừa trong vòng tròn
        val lay = fitLabel(r)
        if (lay != null) {
            val labelA = if (a <= 0f) 0f else 0.55f + 0.45f * a
            // Đang delay: tên nút mờ hẳn đi để số đếm ngược nổi bật
            text.alpha = (255 * labelA * (1f - 0.85f * delayVis(now))).toInt()
            canvas.save()
            canvas.translate(cx - lay.width / 2f, cy - lay.height / 2f)
            lay.draw(canvas)
            canvas.restore()
        }

        if (counting) drawCountdown(canvas, cx, cy, r, cdEnd - now, a)

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

    /** Mức hiện của hiệu ứng delay 0..1: hiện dần lúc bắt đầu, mờ dần trong khoảnh khắc cuối. */
    private fun delayVis(now: Long): Float {
        if (cdEnd == 0L) return 0f
        val remain = cdEnd - now
        val fin = ((cdTotal - remain) / 140f).coerceIn(0f, 1f)
        val fout = (remain / 140f).coerceIn(0f, 1f)
        return minOf(fin, fout)
    }

    private fun drawCountdown(canvas: Canvas, cx: Float, cy: Float, r: Float, remainMs: Long, a: Float) {
        // Toàn bộ hiệu ứng delay nhân theo độ đặc [a] của nút: nút càng mờ thì hiệu ứng càng mờ
        val k = a * delayVis(SystemClock.uptimeMillis())
        if (k <= 0f) { postInvalidateOnAnimation(); return }
        val edgeW = edge.strokeWidth

        // 1) Lớp tối trong viền, gradient tâm nhạt -> mép đậm hơn, hơi ngả đỏ
        if (dim.shader == null || dimKey != r) {
            dim.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(0xB805080F.toInt(), 0xD105080F.toInt(), 0xEB1A0609.toInt()),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP,
            )
            dimKey = r
        }
        dim.alpha = (255 * k).toInt()
        canvas.drawCircle(cx, cy, r - edgeW, dim)

        // 2) Viền đỏ đặc, cùng độ mỏng và cùng vị trí với viền trắng của nút (đè lên nó).
        //    Chạy từ đỉnh theo chiều kim đồng hồ, thu dần; phần đã hết trả lại viền trắng.
        val frac = (remainMs.toFloat() / cdTotal).coerceIn(0f, 1f)
        if (frac > 0f) {
            val ar = r - edgeW / 2f
            delayEdge.strokeWidth = edgeW
            delayEdge.alpha = (255 * k).toInt()
            delayBox.set(cx - ar, cy - ar, cx + ar, cy + ar)
            val sweep = 360f * frac
            canvas.drawArc(delayBox, -90f, sweep, false, delayEdge)

            // chấm sáng nhỏ ở đầu cung đang chạy lùi
            val ang = Math.toRadians((-90f + sweep).toDouble())
            delayHead.alpha = (235 * k).toInt()
            canvas.drawCircle(
                cx + ar * kotlin.math.cos(ang).toFloat(),
                cy + ar * kotlin.math.sin(ang).toFloat(),
                edgeW * 0.95f, delayHead,
            )
        }

        // 3) Số giây còn lại: từ 10s trở lên làm tròn lên số nguyên (nảy nhẹ mỗi khi đổi giây),
        //    dưới 10s hiện 1 chữ số thập phân; 3 giây cuối ngả dần sang đỏ.
        val sec = remainMs / 1000f
        val big = remainMs >= 10_000L
        val s = if (big) kotlin.math.ceil(sec.toDouble()).toInt().toString()
        else String.format(java.util.Locale.US, "%.1f", sec)
        val maxW = r * 1.25f
        var size = r * 0.75f
        cdText.textSize = size
        val w = cdText.measureText(s)
        if (w > maxW) { size *= maxW / w; cdText.textSize = size }

        var pop = 1f
        if (big) {
            val sinceTick = 1000L - (remainMs % 1000L).let { if (it == 0L) 1000L else it }
            if (sinceTick in 0..180) { val t = 1f - sinceTick / 180f; pop = 1f + 0.12f * t * t }
        }
        val warn = (1f - remainMs / 3000f).coerceIn(0f, 1f)
        cdText.color = blend(0xFFFFFFFF.toInt(), 0xFFFF7A7A.toInt(), warn)
        cdText.alpha = (255 * k).toInt()
        cdText.setShadowLayer(r * 0.10f, 0f, r * 0.03f, ((150 * k).toInt() shl 24))
        canvas.save()
        canvas.scale(pop, pop, cx, cy)
        canvas.drawText(s, cx, cy - (cdText.ascent() + cdText.descent()) / 2f, cdText)
        canvas.restore()

        postInvalidateOnAnimation()
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        fun ch(sh: Int) = (((from shr sh) and 0xFF) + ((((to shr sh) and 0xFF) - ((from shr sh) and 0xFF)) * t)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun pulseReady() {
        if (!isAttachedToWindow || pressedVisual) return
        animate().cancel()
        animate().scaleX(1.18f).scaleY(1.18f).setDuration(110L).withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(170L).start()
        }.start()
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
        const val DELAY_RED = 0xFFFF0000.toInt()
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

    /** false = lúc này nút không thật sự click được (menu đang mở / đang chỉnh...) nên Delay Mode không đếm ngược. */
    var canFire: (() -> Boolean)? = null

    private class Entry(val view: MainButtonView, val params: WindowManager.LayoutParams, var addedAt: Long)

    private val entries = HashMap<Int, Entry>()
    private var editingId: Int? = null

    // ---- Delay Mode ---------------------------------------------------------

    private val cooldownEnd = HashMap<Int, Long>()   // id nút -> uptimeMillis lúc hết khoá
    private val blockedDown = HashSet<Int>()          // nút có lần chạm xuống bị chặn (nhả ra cũng bỏ qua)

    private fun isBlocked(b: MainButton) =
        b.delayActive && editingId != b.id && (cooldownEnd[b.id] ?: 0L) > SystemClock.uptimeMillis()

    private fun startCooldown(b: MainButton) {
        if (canFire?.invoke() == false) return
        cooldownEnd[b.id] = SystemClock.uptimeMillis() + b.delayMs
        entries[b.id]?.view?.startCountdown(b.delayMs.toLong())
    }

    private fun clearCooldown(id: Int) {
        cooldownEnd.remove(id)
        blockedDown.remove(id)
        entries[id]?.view?.stopCountdown()
    }

    /** Ngón chạm xuống nút. false = đang đếm ngược (Delay Mode) -> chặn, không tính là click. */
    private fun fireDown(b: MainButton): Boolean {
        if (isBlocked(b)) { blockedDown.add(b.id); return false }
        blockedDown.remove(b.id)
        onAction?.invoke(b, true)
        if (b.delayActive && b.mode == MainButton.MODE_TAP) startCooldown(b) // Nhấn: khoá ngay sau click
        return true
    }

    /** Ngón nhả khỏi nút. */
    private fun fireUp(b: MainButton) {
        if (blockedDown.remove(b.id)) return // lần chạm này đã bị chặn từ lúc xuống
        onAction?.invoke(b, false)
        if (b.delayActive && b.mode == MainButton.MODE_RELEASE) startCooldown(b) // Thả: khoá sau khi thả
    }

    private fun notifyCancel(id: Int) {
        blockedDown.remove(id)
        onCancel?.invoke(id)
    }

    /** Ngón (slot input_event) đang đặt trên nút chạm xuyên: slot -> id nút. */
    private val watched = HashMap<Int, Int>()
    private val watcher = TouchWatcher(ctx, screenBounds, object : TouchSink {
        override fun onFinger(slot: Int, down: Boolean, x: Int, y: Int) = onWatchedFinger(slot, down, x, y)
        override fun onLost() = cancelWatched()
    })
    private val d = UiScale.density(ctx.resources)
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
    private fun isPassThrough(b: MainButton) = (b.passThrough || b.assistEnabled || overlapsTapPoint(b)) && editingId != b.id

    /**
     * Nút main đè lên BẤT KỲ điểm nhấn nào (của mọi nút) -> tự chạm xuyên (cờ đặt sẵn từ trước, không đổi lúc click),
     * nên click bơm vào luôn rơi xuống app bên dưới, không trúng chính ZynTap và không tốn thêm độ trễ.
     * Việc nhận ngón vẫn do bộ theo dõi cảm ứng (watcher) lo, giống chế độ chạm xuyên.
     */
    private var pointsKey = ""
    private var pointsCache: List<Pair<Int, Int>> = emptyList()
    private var pointsScreen = Rect()

    private fun allTapPoints(): List<Pair<Int, Int>> {
        val s = screenBounds()
        val key = repo.buttons.joinToString("\u0001") { it.pointScript }
        if (key == pointsKey && s == pointsScreen) return pointsCache
        pointsKey = key
        pointsScreen = Rect(s)
        pointsCache = repo.buttons.filter { it.pointScript.isNotBlank() }.flatMap { b ->
            PointScriptParser.parse(b.pointScript, s.width(), s.height()).steps
                .filterIsInstance<PointStep.Tap>().map { it.x to it.y }
        }
        return pointsCache
    }

    private fun overlapsTapPoint(b: MainButton): Boolean {
        val p = entries[b.id]?.params ?: return false
        return allTapPoints().any { (x, y) -> x >= p.x && x < p.x + p.width && y >= p.y && y < p.y + p.height }
    }

    /** Áp lại cờ chạm xuyên cho mọi nút (sau khi sửa script / đổi chỗ nút). */
    fun refreshTouchable() {
        entries.forEach { (id, e) ->
            val b = repo.find(id) ?: return@forEach
            val was = (e.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
            applyTouchable(b, e.params)
            if (was != isPassThrough(b)) runCatching { wm.updateViewLayout(e.view, e.params) }
        }
    }

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
        pushSwallowZones()
    }

    /**
     * Nút main CHỈ đè lên điểm nhấn (người dùng không bật "chạm xuyên"): cửa sổ nút vẫn phải NOT_TOUCHABLE để click
     * bơm rơi xuống app dưới nút, nhưng ngón thật đặt lên nút KHÔNG được lọt xuống theo. Helper nuốt ngón đặt trong
     * vùng tròn của các nút này (vẫn báo cho watcher để kích hoạt nút như bình thường).
     */
    private fun pushSwallowZones() {
        val zones = entries.mapNotNull { (id, e) ->
            val b = repo.find(id) ?: return@mapNotNull null
            if (b.passThrough || b.assistEnabled || !isPassThrough(b)) return@mapNotNull null
            Triple(e.params.x + e.params.width / 2, e.params.y + e.params.height / 2, dp(b.sizeDp) / 2)
        }
        watcher.setSwallowZones(zones)
        pushAssistZones()
    }

    // ---- trợ vuốt tâm ---------------------------------------------------------

    /**
     * Nút bật trợ vuốt là cửa sổ xuyên (NOT_TOUCHABLE) và KHÔNG nuốt ngón: ngón đặt trong nút đi thẳng xuống app dưới
     * như một cú vuốt bình thường, còn helper chỉ can thiệp (kéo/ghìm/buff) vào ngón NÀO chạm xuống trong vùng nút.
     * Menu mở / đang chỉnh nút thì tắt vùng để kéo nút và thao tác menu không bị méo.
     */
    private fun pushAssistZones() {
        val zones: List<List<Int>> = if (menuOpen) emptyList() else entries.mapNotNull { (id, e) ->
            val b = repo.find(id) ?: return@mapNotNull null
            if (!b.assistEnabled || editingId == id) return@mapNotNull null
            listOf(
                e.params.x + e.params.width / 2,
                e.params.y + e.params.height / 2,
                dp(b.sizeDp) / 2,
                b.assistMode,
                dp(b.assistSpeed),
                dp(b.assistLength),
                b.assistDelay,
                b.boostGain,
                dp(ASSIST_SLOP_DP),
            )
        }
        watcher.setAssistZones(zones)
    }

    private fun refreshWatcher() {
        // Có nút main là bật proxy: helper phải grab cảm ứng từ trước, vì lúc đang giữ ngón lên nút
        // (chế độ Giữ) mới grab thì hệ thống đã kẹt ngón thật. Cũng cần cho chạm xuyên.
        val need = OverlayPermission.granted(ctx) && repo.buttons.isNotEmpty()
        if (need) watcher.start() else { watcher.stop(); cancelWatched() }
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
        if (down) {
            if (watched.containsKey(slot)) return
            val id = buttonAt(x, y) ?: return
            val b = repo.find(id) ?: return
            watched[slot] = id
            entries[id]?.view?.pressedVisual = fireDown(b) // bị Delay Mode chặn thì không hiện hiệu ứng nhấn
        } else {
            val id = watched.remove(slot) ?: return
            entries[id]?.view?.pressedVisual = watched.containsValue(id)
            val b = repo.find(id) ?: return
            if (!watched.containsValue(id)) fireUp(b)
        }
    }

    /** Huỷ các nút đang giữ qua chạm xuyên (mất ADB, tắt tính năng, xoay màn hình...). */
    private fun cancelWatched() {
        val ids = watched.values.toSet()
        watched.clear()
        ids.forEach { id ->
            entries[id]?.view?.pressedVisual = false
            notifyCancel(id)
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
        place(b, p)
        val v = MainButtonView(ctx)
        bind(v, b)
        v.editing = b.id == editingId
        attachTouch(b.id, v, p)
        entries[b.id] = Entry(v, p, SystemClock.uptimeMillis()) // đăng ký trước để overlapsTapPoint đọc được vị trí
        applyTouchable(b, p)
        if (runCatching { wm.addView(v, p) }.isFailure) entries.remove(b.id)
    }

    /** Menu đang mở? Mở thì nút có độ mờ 0% vẫn hiện ở 20% để còn nhìn thấy mà chỉnh. */
    private var menuOpen = false

    fun setMenuOpen(open: Boolean) {
        menuOpen = open
        entries.values.forEach { it.view.minPct = if (open) MainButton.MENU_MIN_ALPHA else 0 }
        if (!open) refreshTouchable() // script điểm nhấn có thể vừa đổi
        pushAssistZones()
    }

    private fun bind(v: MainButtonView, b: MainButton) {
        v.label = b.name
        v.fillPct = b.alphaPct
        v.minPct = if (menuOpen) MainButton.MENU_MIN_ALPHA else 0
        v.ringPad = ringPad.toFloat()
    }

    private fun removeView(id: Int) {
        cooldownEnd.remove(id)
        notifyCancel(id)
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
        cooldownEnd.remove(id)
        notifyCancel(id)
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
        clearCooldown(b.id) // đổi cài đặt (kể cả thời gian delay) -> bắt đầu lại từ đầu
        notifyCancel(b.id)
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
            pushSwallowZones() // kích thước/vị trí vừa đổi
        }
    }

    /** Vào/ra trạng thái chỉnh sửa của một nút (null = không nút nào đang chỉnh). */
    fun setEditing(id: Int?) {
        if (id != null) { clearCooldown(id); notifyCancel(id) }
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
        refreshTouchable()
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
        entries.forEach { (id, e) -> e.view.pressedVisual = false; notifyCancel(id) }
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
                    v.pressedVisual = if (!editing) fireDown(b) else true
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
                        if (editing) { if (moved) { repo.save(); onDrag?.invoke(false) } } else fireUp(b)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    pointerId = -1
                    v.pressedVisual = false
                    if (editing && moved) { repo.save(); onDrag?.invoke(false) }
                    notifyCancel(id)
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
        /** Ngón vuốt ra khỏi chỗ chạm xuống quá chừng này (dp) thì trợ vuốt mới bắt đầu can thiệp. */
        const val ASSIST_SLOP_DP = 10
    }
}
