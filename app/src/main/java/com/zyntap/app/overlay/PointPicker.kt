package com.zyntap.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import com.zyntap.app.R
import kotlin.math.abs

/**
 * Ghim chọn tọa độ: một icon map-pin nền trong suốt nổi trên màn hình.
 *  - Kéo thả ghim tới điểm muốn chạm.
 *  - Chạm ghim một lần (không kéo) -> [onPicked] với tọa độ mũi ghim (đáy icon).
 * Cửa sổ chỉ bằng kích thước icon nên phần còn lại của màn hình vẫn chạm được bình thường.
 */
class PointPicker(
    private val context: Context,
    private val wm: WindowManager,
) {
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    val showing: Boolean get() = view != null

    /** Mở ghim tại (startX, startY) — toạ độ màn hình, là đáy/mũi ghim. */
    fun show(startX: Int, startY: Int, onPicked: (x: Int, y: Int) -> Unit) {
        hide()
        val size = dp(40)
        val pin = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99050A15.toInt())
                setStroke(dp(1), 0xFF19D3FF.toInt())
            }
            addView(
                ImageView(context).apply {
                    setImageResource(R.drawable.ic_map_pin)
                    setColorFilter(0xFF19D3FF.toInt())
                },
                FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER),
            )
        }
        val p = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            // Mũi ghim nằm ở đáy icon: đặt đáy icon đúng tại (startX, startY)
            x = startX - size / 2
            y = startY - size
        }
        pin.setOnTouchListener(dragListener(p, onPicked))
        view = pin
        params = p
        runCatching { wm.addView(pin, p) }.onFailure {
            view = null
            params = null
        }
    }

    /** Gỡ ghim khỏi màn hình (đã chọn xong, hoặc người dùng chuyển sang nhập tay). */
    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        params = null
    }

    private fun dragListener(p: WindowManager.LayoutParams, onPicked: (Int, Int) -> Unit): View.OnTouchListener {
        val slop = dp(8)
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        return View.OnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = p.x; startY = p.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // Mũi ghim = giữa đáy icon
                        val tipX = p.x + p.width / 2
                        val tipY = p.y + p.height
                        onPicked(tipX, tipY)
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()
}
