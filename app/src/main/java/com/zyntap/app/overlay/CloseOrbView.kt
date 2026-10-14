package com.zyntap.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.roundToInt

/**
 * Hiệu ứng "đóng menu": card thu dần vào tâm thành 1 viên tròn có logo, nén lại lấy đà,
 * rồi bắn thẳng lên trên màn hình (kéo dài + vệt sáng phía sau + sóng xung kích).
 *
 * Toàn bộ vẽ bằng 1 View duy nhất, điều khiển bằng [progress] 0..1 (1 ValueAnimator tuyến tính
 * ở ngoài) -> mượt, không tạo object mỗi khung hình.
 *
 * Mốc thời gian (tỉ lệ trên tổng thời lượng):
 *   0.00–0.30  card  -> hình tròn (logo hiện dần)
 *   0.30–0.55  nén lại lấy đà, quầng sáng dâng lên
 *   0.55–1.00  bắn lên trên, ra khỏi màn hình
 */
class CloseOrbView(context: Context, logo: Drawable?) : View(context) {

    /** Hình chữ nhật của card lúc bắt đầu (toạ độ trong view này). */
    var fromRect = RectF()

    /** Bo góc của card lúc bắt đầu (px). */
    var fromRadius = 0f

    /** 1 = card đặc, <1 = card đang ở chế độ trong suốt (để chuyển màu liền mạch). */
    var fromFillFactor = 1f

    var progress = 0f
        set(value) {
            field = value
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val orbR = 42f * d                // bán kính viên tròn: 84dp

    private val logo: Drawable? = logo?.mutate()
    private val logoRatio: Float = logo?.let {
        val w = it.intrinsicWidth.toFloat()
        val h = it.intrinsicHeight.toFloat()
        if (w > 0 && h > 0) h / w else 1f
    } ?: 1f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    // Quầng sáng: gradient bán kính 1 ở gốc toạ độ, mỗi khung hình chỉ đổi matrix
    private val glowShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(withAlpha(CYAN, 0.55f), withAlpha(BLUE, 0.22f), withAlpha(BLUE, 0f)),
        floatArrayOf(0f, 0.55f, 1f),
        Shader.TileMode.CLAMP,
    )
    private val glowMatrix = Matrix()

    init {
        glowPaint.shader = glowShader
    }

    override fun onDraw(canvas: Canvas) {
        val t = progress.coerceIn(0f, 1f)
        val cx = fromRect.centerX()
        val cy = fromRect.centerY()

        if (t < P1_END) {
            // ---- 1) card -> hình tròn ------------------------------------------------
            val p = COLLAPSE.getInterpolation(t / P1_END)
            rect.set(
                lerp(fromRect.left, cx - orbR, p),
                lerp(fromRect.top, cy - orbR, p),
                lerp(fromRect.right, cx + orbR, p),
                lerp(fromRect.bottom, cy + orbR, p),
            )
            val radius = lerp(fromRadius, orbR, p)
            val glow = 0.4f * smooth(p, 0.6f, 1f)
            if (glow > 0f) drawGlow(canvas, cx, cy, orbR * 2.1f, glow)
            fill.color = withAlpha(BODY, lerp(CARD_ALPHA * fromFillFactor, 1f, p))
            canvas.drawRoundRect(rect, radius, radius, fill)
            stroke.strokeWidth = 1f * d
            stroke.color = lerpColor(BORDER, CYAN, glow)
            canvas.drawRoundRect(rect, radius, radius, stroke)
            drawLogo(canvas, cx, cy, 1f, smooth(p, 0.5f, 1f), lerp(0.55f, 1f, p))
            return
        }

        val post = ((t - P1_END) / (1f - P1_END)).coerceIn(0f, 1f)
        val a = (post / ANTICIPATE).coerceIn(0f, 1f)              // nén lấy đà
        val l = ((post - ANTICIPATE) / (1f - ANTICIPATE)).coerceIn(0f, 1f) // bắn lên
        val yEnd = -orbR * 2.6f

        // ---- 2) nén lại, quầng sáng dâng -------------------------------------------
        val base = 1f - 0.16f * EASE_OUT.getInterpolation(a)        // 1 -> 0.84
        var glow = lerp(0.4f, 1f, a)

        var oy = cy
        var sx = base
        var sy = base
        if (l > 0f) {
            // ---- 3) bắn lên ----------------------------------------------------------
            val stretch = smooth(l, 0f, 0.6f)
            sy = base + 0.66f * stretch                              // kéo dài theo chiều dọc
            sx = base - 0.12f * stretch                              // và hơi thon lại
            oy = lerp(cy, yEnd, EASE_IN.getInterpolation(l))
            glow = 1f - 0.45f * l

            // sóng xung kích toả ra tại điểm xuất phát
            val rl = (l / 0.42f).coerceIn(0f, 1f)
            if (rl < 1f) {
                val rr = lerp(orbR, orbR * 2.6f, EASE_OUT.getInterpolation(rl))
                stroke.strokeWidth = lerp(3f, 1f, rl) * d
                stroke.color = withAlpha(CYAN, 0.65f * (1f - rl))
                canvas.drawCircle(cx, cy, rr, stroke)
            }

            // vệt sáng phía sau (các bản sao mờ dần)
            for (i in TRAIL_N downTo 1) {
                val li = (l - i * 0.03f).coerceAtLeast(0f)
                val ty = lerp(cy, yEnd, EASE_IN.getInterpolation(li))
                val k = 1f - i / (TRAIL_N + 1f)
                trailPaint.color = withAlpha(CYAN, 0.26f * k * smooth(l, 0f, 0.15f))
                canvas.drawCircle(cx, ty, orbR * sx * (0.55f + 0.45f * k), trailPaint)
            }
        }

        drawGlow(canvas, cx, oy, orbR * (2.1f + 0.5f * glow), glow)

        // thân viên tròn (có thể bị kéo dài)
        canvas.save()
        canvas.scale(sx, sy, cx, oy)
        fill.color = withAlpha(BODY, 1f)
        canvas.drawCircle(cx, oy, orbR, fill)
        stroke.strokeWidth = lerp(1f, 2f, glow) * d
        stroke.color = lerpColor(BORDER, CYAN, glow)
        canvas.drawCircle(cx, oy, orbR, stroke)
        canvas.restore()

        // logo giữ nguyên tỉ lệ (không bị kéo méo)
        drawLogo(canvas, cx, oy, (sx + sy) / 2f * 0.92f + 0.08f * base, 1f, 1f)
    }

    private fun drawGlow(canvas: Canvas, cx: Float, cy: Float, r: Float, strength: Float) {
        glowMatrix.setScale(r, r)
        glowMatrix.postTranslate(cx, cy)
        glowShader.setLocalMatrix(glowMatrix)
        glowPaint.alpha = (255 * strength.coerceIn(0f, 1f)).roundToInt()
        canvas.drawCircle(cx, cy, r, glowPaint)
    }

    private fun drawLogo(canvas: Canvas, cx: Float, cy: Float, scale: Float, alpha: Float, pop: Float) {
        val dr = logo ?: return
        if (alpha <= 0f) return
        val w = orbR * 2f * 0.56f
        val h = w * logoRatio
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale * pop, scale * pop)
        dr.setBounds((-w / 2).toInt(), (-h / 2).toInt(), (w / 2).toInt(), (h / 2).toInt())
        dr.alpha = (255 * alpha.coerceIn(0f, 1f)).roundToInt()
        dr.draw(canvas)
        canvas.restore()
    }

    // ---- tiện ích -------------------------------------------------------------

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun smooth(x: Float, e0: Float, e1: Float): Float {
        val v = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return v * v * (3f - 2f * v)
    }

    private fun lerpColor(c0: Int, c1: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb(
            lerp(Color.alpha(c0).toFloat(), Color.alpha(c1).toFloat(), k).roundToInt(),
            lerp(Color.red(c0).toFloat(), Color.red(c1).toFloat(), k).roundToInt(),
            lerp(Color.green(c0).toFloat(), Color.green(c1).toFloat(), k).roundToInt(),
            lerp(Color.blue(c0).toFloat(), Color.blue(c1).toFloat(), k).roundToInt(),
        )
    }

    private fun withAlpha(color: Int, a: Float): Int =
        (color and 0x00FFFFFF) or ((255 * a.coerceIn(0f, 1f)).roundToInt() shl 24)

    companion object {
        /** Tổng thời lượng hiệu ứng (ms). Muốn chậm/nhanh hơn thì chỉnh số này. */
        const val DURATION_MS = 1900L

        private const val P1_END = 0.30f
        private const val ANTICIPATE = 0.2f
        private const val TRAIL_N = 7

        private const val BODY = 0xFF0D1524.toInt()
        private const val BORDER = 0xFF1E2B44.toInt()
        private const val CYAN = 0xFF19D3FF.toInt()
        private const val BLUE = 0xFF2A6BFF.toInt()
        private const val CARD_ALPHA = 0xF2 / 255f

        private val COLLAPSE = PathInterpolator(0.65f, 0f, 0.25f, 1f)
        private val EASE_OUT = PathInterpolator(0.22f, 1f, 0.36f, 1f)
        private val EASE_IN = PathInterpolator(0.55f, 0f, 0.9f, 0.45f)
    }
}
