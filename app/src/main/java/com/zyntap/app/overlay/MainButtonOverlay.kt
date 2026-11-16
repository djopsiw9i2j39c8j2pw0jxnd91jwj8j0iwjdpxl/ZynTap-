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

class MainButtonView(context: Context) : View(context) {

    var label = ""
        set(v) { if (field != v) { field = v; labelKey = ""; invalidate() } }

    var fillPct = 80
        set(v) { if (field != v) { field = v; invalidate() } }

    var minPct = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    var ringPad = 0f
        set(v) { if (field != v) { field = v; invalidate() } }

    var editing = false
        set(v) {
            if (field == v) return
            field = v
            if (v && isAttachedToWindow) startSpin() else stopSpin()
            invalidate()
        }

    var pressedVisual = false
        set(v) {
            if (field == v) return
            field = v
            animate().cancel()
            animate().scaleX(if (v) 0.92f else 1f).scaleY(if (v) 0.92f else 1f).setDuration(110L).start()
            invalidate()
        }

    private var cdEnd = 0L      
    private var cdTotal = 1L

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
     
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var dimKey = 0f
     
    private val delayHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFC2C2.toInt() }
     
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
        fontFeatureSettings = "tnum" 
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
        stopSpin()
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
            
            cdEnd = 0L
            post { pulseReady() }
        }
        if (a <= 0f && !editing) {
            
            if (counting) postInvalidateDelayed(cdEnd - now + 16L)
            return
        }

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

        val lay = fitLabel(r)
        if (lay != null) {
            val labelA = if (a <= 0f) 0f else 0.55f + 0.45f * a
            
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

    private fun delayVis(now: Long): Float {
        if (cdEnd == 0L) return 0f
        val remain = cdEnd - now
        val fin = ((cdTotal - remain) / 140f).coerceIn(0f, 1f)
        val fout = (remain / 140f).coerceIn(0f, 1f)
        return minOf(fin, fout)
    }

    private fun drawCountdown(canvas: Canvas, cx: Float, cy: Float, r: Float, remainMs: Long, a: Float) {
        
        val k = a * delayVis(SystemClock.uptimeMillis())
        if (k <= 0f) { postInvalidateOnAnimation(); return }
        val edgeW = edge.strokeWidth

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

        val frac = (remainMs.toFloat() / cdTotal).coerceIn(0f, 1f)
        if (frac > 0f) {
            val ar = r - edgeW / 2f
            delayEdge.strokeWidth = edgeW
            delayEdge.alpha = (255 * k).toInt()
            delayBox.set(cx - ar, cy - ar, cx + ar, cy + ar)
            val sweep = 360f * frac
            canvas.drawArc(delayBox, -90f, sweep, false, delayEdge)

            val ang = Math.toRadians((-90f + sweep).toDouble())
            delayHead.alpha = (235 * k).toInt()
            canvas.drawCircle(
                cx + ar * kotlin.math.cos(ang).toFloat(),
                cy + ar * kotlin.math.sin(ang).toFloat(),
                edgeW * 0.95f, delayHead,
            )
        }

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
            
            val last = l.lineCount - 1
            return l.getEllipsisCount(last) == 0 && l.getLineEnd(last) >= t.length
        }

        text.textSize = 100f
        val w100 = text.measureText(t).coerceAtLeast(1f)
        val oneSize = minOf(maxSize, 100f * boxW / w100)

        var multiSize = 0f
        var size = maxSize
        while (size >= minSize) {
            if (fits(build(size, 3), 3)) { multiSize = size; break }
            size -= 0.5f * d
        }

        val result = when {
            oneSize >= minSize && (multiSize == 0f || oneSize >= multiSize * 0.72f) -> build(oneSize, 1)
            multiSize > 0f -> build(multiSize, 3)
            else -> build(minSize, 3) 
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

class MainButtonOverlay(
    private val ctx: Context,
    private val wm: WindowManager,
    private val repo: MainButtonRepo,
    private val screenBounds: () -> Rect,
    private val newParams: (width: Int, height: Int, flags: Int) -> WindowManager.LayoutParams,
) {
    var onAction: ((MainButton, Boolean) -> Unit)? = null
    var onCancel: ((Int) -> Unit)? = null

    var canFire: (() -> Boolean)? = null

    private class Entry(val view: MainButtonView, val params: WindowManager.LayoutParams, var addedAt: Long)

    private var closed = false
    private val entries = HashMap<Int, Entry>()
    private var editingId: Int? = null

    private val cooldownEnd = HashMap<Int, Long>()   
    private val blockedDown = HashSet<Int>()          

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

    private fun fireDown(b: MainButton): Boolean {
        if (isBlocked(b)) { blockedDown.add(b.id); return false }
        blockedDown.remove(b.id)
        onAction?.invoke(b, true)
        if (b.delayActive && b.mode == MainButton.MODE_TAP) startCooldown(b) 
        return true
    }

    private fun fireUp(b: MainButton) {
        if (blockedDown.remove(b.id)) return 
        onAction?.invoke(b, false)
        if (b.delayActive && b.mode == MainButton.MODE_RELEASE) startCooldown(b) 
    }

    private fun notifyCancel(id: Int) {
        blockedDown.remove(id)
        onCancel?.invoke(id)
    }

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

    fun ensureAll() {
        if (closed || !OverlayPermission.granted(ctx)) return
        val now = SystemClock.uptimeMillis()
        entries.entries.toList().forEach { (id, e) ->
            if (repo.find(id) == null) removeView(id)
            else if (now - e.addedAt > ATTACH_GRACE_MS && !e.view.isAttachedToWindow && !wm.isAwaitingRecovery(e.view)) removeView(id)
        }
        repo.buttons.forEach { if (!entries.containsKey(it.id)) addView(it) }
        refreshWatcher()
    }

    private fun isPassThrough(b: MainButton) = (b.passThrough || b.assistEnabled || overlapsTapPoint(b)) && editingId != b.id

    val interacting: Boolean get() = watched.isNotEmpty() || entries.values.any { it.view.isAttachedToWindow && it.view.pressedVisual }

    @Suppress("DEPRECATION")
    private fun rotation() = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation

    private var pointsKey = ""
    private var pointsCache: List<Pair<Int, Int>> = emptyList()
    private var pointsScreen = Rect()

    private fun allTapPoints(): List<Pair<Int, Int>> {
        val s = screenBounds()
        val key = rotation().toString() + repo.buttons.joinToString("\u0001") { "${it.pointWidth}:${it.pointHeight}:${it.pointRotation}:${it.pointScript}" }
        if (key == pointsKey && s == pointsScreen) return pointsCache
        pointsKey = key
        pointsScreen = Rect(s)
        pointsCache = repo.buttons.filter { it.pointScript.isNotBlank() }.flatMap { b ->
            b.scriptFor(s.width(), s.height(), rotation()).allPoints
        }
        return pointsCache
    }

    private fun overlapsTapPoint(b: MainButton): Boolean {
        val p = entries[b.id]?.params ?: return false
        return allTapPoints().any { (x, y) -> x >= p.x && x < p.x + p.width && y >= p.y && y < p.y + p.height }
    }

    fun refreshTouchable() {
        entries.forEach { (id, e) ->
            val b = repo.find(id) ?: return@forEach
            val was = (e.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
            applyTouchable(b, e.params)
            if (was != isPassThrough(b)) runCatching { wm.updateViewLayout(e.view, e.params) }
        }
    }

    private fun applyTouchable(b: MainButton, p: WindowManager.LayoutParams) {
        val through = isPassThrough(b)
        p.flags = if (through) p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        p.alpha = if (through) PASS_THROUGH_ALPHA else 1f
        pushSwallowZones()
    }

    private fun pushSwallowZones() {
        val zones = entries.mapNotNull { (id, e) ->
            val b = repo.find(id) ?: return@mapNotNull null
            if (b.passThrough || b.assistEnabled || !isPassThrough(b)) return@mapNotNull null
            Triple(e.params.x + e.params.width / 2, e.params.y + e.params.height / 2, dp(b.sizeDp) / 2)
        }
        watcher.setSwallowZones(zones)
        pushAssistZones()
    }

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

        val need = !closed && entries.isNotEmpty() && OverlayPermission.granted(ctx) && repo.buttons.isNotEmpty()
        if (need) watcher.start() else { watcher.stop(); cancelWatched() }
    }

    private fun buttonAt(x: Int, y: Int): Int? {
        
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
        if (closed) return
        if (down) {
            if (watched.containsKey(slot)) return
            val id = buttonAt(x, y) ?: return
            val b = repo.find(id) ?: return
            watched[slot] = id
            entries[id]?.view?.pressedVisual = fireDown(b) 
        } else {
            val id = watched.remove(slot) ?: return
            entries[id]?.view?.pressedVisual = watched.containsValue(id)
            val b = repo.find(id) ?: return
            if (!watched.containsValue(id)) fireUp(b)
        }
    }

    private fun cancelWatched() {
        val ids = watched.values.toSet()
        watched.clear()
        ids.forEach { id ->
            entries[id]?.view?.pressedVisual = false
            notifyCancel(id)
        }
    }

    private fun addView(b: MainButton) {
        if (closed) return
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
        entries[b.id] = Entry(v, p, SystemClock.uptimeMillis()) 
        applyTouchable(b, p)
        if (runCatching { wm.addView(v, p) }.isFailure) entries.remove(b.id)
    }

    private var menuOpen = false

    fun setMenuOpen(open: Boolean) {
        menuOpen = open
        entries.values.forEach { it.view.minPct = if (open) MainButton.MENU_MIN_ALPHA else 0 }
        if (!open) refreshTouchable() 
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
        e.view.animate().setListener(null).withEndAction(null).cancel()
        runCatching { wm.removeViewImmediate(e.view) }
            .onFailure { com.zyntap.app.data.DebugLog.e("Nút main", "Không gỡ được cửa sổ nút $id", it) }
    }

    fun onAdded(b: MainButton) {
        if (closed) return
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

    fun onChanged(b: MainButton) {
        clearCooldown(b.id) 
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
            pushSwallowZones() 
        }
    }

    fun setEditing(id: Int?) {
        if (editingId == id) return
        if (id != null) { clearCooldown(id); notifyCancel(id) }
        editingId = id
        entries.forEach { (k, e) ->
            e.view.editing = k == id
            val b = repo.find(k) ?: return@forEach
            val wasThrough = (e.params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0
            applyTouchable(b, e.params)
            
            if (wasThrough != isPassThrough(b) && k != id) runCatching { wm.updateViewLayout(e.view, e.params) }
        }
        if (id != null) bringToFront(id)
        else entries.forEach { (_, e) -> runCatching { wm.updateViewLayout(e.view, e.params) } }
    }

    private fun bringToFront(id: Int) {
        val e = entries[id] ?: return
        runCatching { wm.removeViewImmediate(e.view) }
        val ok = runCatching { wm.addView(e.view, e.params) }.isSuccess
        if (ok) e.addedAt = SystemClock.uptimeMillis() else entries.remove(id)
    }

    fun relayoutAll() {
        entries.forEach { (id, e) ->
            val b = repo.find(id) ?: return@forEach
            place(b, e.params)
            runCatching { wm.updateViewLayout(e.view, e.params) }
        }
        refreshTouchable()
    }

    fun fadeAll(to: Float, durationMs: Long) {
        entries.values.forEach { it.view.animate().alpha(to).setDuration(durationMs).start() }
    }

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

    fun close() {
        if (closed) return
        closed = true
        removeAll()
        onAction = null
        onCancel = null
        onDrag = null
        canFire = null
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
                            onDrag?.invoke(true) 
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
         
        const val ASSIST_SLOP_DP = 10
    }
}
