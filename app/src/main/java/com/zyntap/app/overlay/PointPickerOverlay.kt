package com.zyntap.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs

/** A small transparent window, not a screen-sized touch blocker. The pin TIP is the coordinate. */
class PointPickerOverlay(
    private val ctx: Context,
    private val wm: WindowManager,
    private val bounds: () -> Rect,
    private val params: (Int, Int, Int) -> WindowManager.LayoutParams,
    private val onVisibility: (Boolean) -> Unit,
    private val onDrag: (Boolean) -> Unit = {},
) {
    private val density = UiScale.density(ctx.resources)
    private fun dp(n: Int) = (n * density).toInt()
    private var pin: Pin? = null
    private var cancelView: View? = null
    private var callback: ((Int, Int) -> Unit)? = null
    val isShowing get() = pin != null

    fun show(x: Int?, y: Int?, onPick: (Int, Int) -> Unit) {
        close()
        callback = onPick
        val screen = bounds()
        val size = dp(PIN_WIN_DP)
        val p = params(size, size,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = ((x ?: (screen.width() / 2)) - dp(TIP_X_DP)).coerceIn(-dp(TIP_X_DP), screen.width() - dp(TIP_X_DP) - 1)
            this.y = ((y ?: (screen.height() / 2)) - dp(TIP_Y_DP)).coerceIn(-dp(TIP_Y_DP), screen.height() - dp(TIP_Y_DP) - 1)
        }
        val view = Pin(ctx)
        view.contentDescription = "Kéo ghim, thả rồi chạm một lần để chọn tọa độ tại mũi ghim"
        pin = view
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var dragging = false
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = p.x; startY = p.y; dragging = false
                    view.animate().alpha(0.8f).setDuration(100).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; onDrag(true) }
                    if (dragging) {
                        val b = bounds()
                        p.x = (startX + dx).toInt().coerceIn(-dp(TIP_X_DP), b.width() - dp(TIP_X_DP) - 1)
                        p.y = (startY + dy).toInt().coerceIn(-dp(TIP_Y_DP), b.height() - dp(TIP_Y_DP) - 1)
                        runCatching { wm.updateViewLayout(view, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.animate().alpha(1f).setDuration(140).start()
                    if (dragging) onDrag(false)
                    if (!dragging) {
                        val location = IntArray(2)
                        view.getLocationOnScreen(location)
                        val b = bounds()
                        val px = (location[0] + dp(TIP_X_DP)).coerceIn(0, b.width() - 1)
                        val py = (location[1] + dp(TIP_Y_DP)).coerceIn(0, b.height() - 1)
                        val cb = callback
                        close()
                        cb?.invoke(px, py)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) onDrag(false)
                    view.animate().alpha(1f).setDuration(100).start(); true
                }
                else -> true
            }
        }
        val cancel = TextView(ctx).apply {
            text = "✕  Hủy chọn điểm"
            contentDescription = "Hủy chọn điểm"
            scaledTextSize(13f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE6121C30.toInt()); cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0xFF2A3A5C.toInt())
            }
            setOnClickListener { close() }
        }
        val cp = params(dp(160), dp(38), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            this.y = dp(14)
        }
        cancelView = cancel
        try {
            onVisibility(true)
            wm.addView(view, p)
            wm.addView(cancel, cp)
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(160).start()
        } catch (_: Exception) { close() }
    }

    fun close() {
        val active = pin != null
        pin?.let { it.animate().cancel(); runCatching { wm.removeViewImmediate(it) } }
        cancelView?.let { runCatching { wm.removeViewImmediate(it) } }
        pin = null; cancelView = null; callback = null
        if (active) onVisibility(false)
    }

    private class Pin(ctx: Context) : View(ctx) {
        private val d = UiScale.density(resources)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF19D3FF.toInt(); style = Paint.Style.STROKE
            strokeWidth = 2.4f * d; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val path = Path().apply {
            // Same silhouette as the supplied Lucide map-pin, scaled from its 24x24 path.
            moveTo(20f, 10f)
            cubicTo(20f, 14.993f, 14.461f, 20.193f, 12.601f, 21.799f)
            cubicTo(12.25f, 22.07f, 11.75f, 22.07f, 11.399f, 21.799f)
            cubicTo(9.539f, 20.193f, 4f, 14.993f, 4f, 10f)
            cubicTo(4f, -0.667f, 20f, -0.667f, 20f, 10f)
            close()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Ghim nhỏ gọn: mũi ghim (12, 21.8 trong hệ 24x24) đặt đúng tại (TIP_X_DP, TIP_Y_DP) của cửa sổ.
            canvas.save()
            canvas.translate((TIP_X_DP - 12f * PIN_SCALE) * d, (TIP_Y_DP - 21.8f * PIN_SCALE) * d)
            canvas.scale(PIN_SCALE * d, PIN_SCALE * d)
            stroke.strokeWidth = 1.9f
            canvas.drawPath(path, stroke)
            canvas.drawCircle(12f, 10f, 3f, stroke)
            canvas.restore()
        }
    }

    private companion object {
        /** Cửa sổ ghim (dp) — trước là 72, giờ nhỏ lại. */
        const val PIN_WIN_DP = 44
        /** Vị trí mũi ghim trong cửa sổ (dp): giữa ngang, gần đáy. Đây chính là toạ độ chọn. */
        const val TIP_X_DP = 22
        const val TIP_Y_DP = 36
        /** Tỉ lệ vẽ ghim (trước là 2x) — 1.25x => ghim cao ~25dp. */
        const val PIN_SCALE = 1.25f
    }
}
