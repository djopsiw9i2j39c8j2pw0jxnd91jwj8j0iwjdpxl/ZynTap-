package com.zyntap.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.zyntap.app.macro.MacroRunner
import com.zyntap.app.macro.MainButton
import kotlin.math.abs

/**
 * Quản lý các nút main hình tròn nổi (mỗi nút là một cửa sổ overlay riêng).
 *  - Bình thường: chạm/giữ/thả theo chế độ của nút -> [MacroRunner].
 *  - Đang chỉnh sửa (nút đang được chọn ở tab "Tạo nút chính"): chỉ kéo thả, không kích hoạt,
 *    và viền sáng để nhận biết.
 */
class MainButtonManager(
    private val context: Context,
    private val wm: WindowManager,
    private val runner: MacroRunner,
) {
    /** Gọi khi vị trí nút đổi (để lưu lại). */
    var onPositionChanged: ((MainButton) -> Unit)? = null

    private val views = LinkedHashMap<String, View>()
    private val params = HashMap<String, WindowManager.LayoutParams>()
    private var mains: List<MainButton> = emptyList()
    private var editingId: String? = null

    /** Đồng bộ danh sách nút: thêm nút mới, cập nhật giao diện nút cũ, bỏ nút đã xoá. */
    fun sync(list: List<MainButton>, editing: String?) {
        mains = list
        editingId = editing
        val ids = list.map { it.id }.toSet()
        views.keys.toList().filterNot { it in ids }.forEach { removeView(it) }
        list.forEachIndexed { index, main ->
            val existing = views[main.id]
            if (existing == null) addView(main, index) else restyle(existing, main)
        }
    }

    fun hideAll() {
        views.keys.toList().forEach { removeView(it) }
    }

    /**
     * Đưa các nút lên trên cùng (sau menu). Gọi khi bắt đầu chỉnh sửa để nút tròn
     * không bị menu che và vẫn kéo thả được.
     */
    fun bringToFront() {
        for ((id, view) in views.toList()) {
            val p = params[id] ?: continue
            runCatching { wm.removeView(view) }
            runCatching { wm.addView(view, p) }
        }
    }

    fun isEditing(id: String) = editingId == id

    // ---- tạo / cập nhật giao diện -----------------------------------------

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun addView(main: MainButton, index: Int) {
        val size = dp(main.sizeDp)
        val view = FrameLayout(context).apply {
            background = circle(main)
            val label = TextView(context).apply {
                text = main.name
                setTextColor(Color.WHITE)
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 1
                tag = "label"
            }
            addView(label, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val p = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.START or Gravity.TOP }
        val (x, y) = defaultPosition(main, index, size)
        p.x = x
        p.y = y
        view.setOnTouchListener(touchListener(main.id))
        params[main.id] = p
        views[main.id] = view
        runCatching { wm.addView(view, p) }.onFailure {
            views.remove(main.id)
            params.remove(main.id)
        }
    }

    private fun restyle(view: View, main: MainButton) {
        val size = dp(main.sizeDp)
        view.background = circle(main)
        (view.findViewWithTag<TextView>("label"))?.text = main.name
        val p = params[main.id] ?: return
        if (p.width != size) {
            p.width = size
            p.height = size
            runCatching { wm.updateViewLayout(view, p) }
        }
    }

    /** Viền sáng + nền theo độ mờ đã chọn. Nút đang chỉnh sửa có viền cyan dày. */
    private fun circle(main: MainButton): GradientDrawable {
        val alpha = (main.opacityPct.coerceIn(20, 100) * 255 / 100)
        val editing = main.id == editingId
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(alpha, 0x2A, 0x6B, 0xFF))
            setStroke(
                if (editing) dp(3) else dp(1),
                if (editing) Color.argb(255, 0x19, 0xD3, 0xFF) else Color.argb(alpha, 0xFF, 0xFF, 0xFF),
            )
        }
    }

    private fun defaultPosition(main: MainButton, index: Int, size: Int): Pair<Int, Int> {
        if (main.posX >= 0 && main.posY >= 0) return clamp(main.posX, main.posY, size)
        return clamp(dp(12), dp(220) + index * (size + dp(12)), size)
    }

    private fun clamp(x: Int, y: Int, size: Int): Pair<Int, Int> {
        val b = screenBounds()
        return x.coerceIn(0, (b.width() - size).coerceAtLeast(0)) to
            y.coerceIn(0, (b.height() - size).coerceAtLeast(0))
    }

    private fun screenBounds(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { return wm.maximumWindowMetrics.bounds }
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    // ---- chạm -------------------------------------------------------------

    private fun touchListener(id: String): View.OnTouchListener {
        val slop = dp(6)
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        return View.OnTouchListener { v, e ->
            val main = mains.firstOrNull { it.id == id } ?: return@OnTouchListener false
            val p = params[id] ?: return@OnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX
                    downRawY = e.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    if (!isEditing(id)) runner.onMainPressed(main)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!isEditing(id)) return@OnTouchListener true
                    val dx = e.rawX - downRawX
                    val dy = e.rawY - downRawY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                    if (moved) {
                        val b = screenBounds()
                        p.x = (startX + dx.toInt()).coerceIn(0, (b.width() - p.width).coerceAtLeast(0))
                        p.y = (startY + dy.toInt()).coerceIn(0, (b.height() - p.height).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isEditing(id)) {
                        // Chạm nút đang chỉnh sửa không làm gì: chỉ kéo thả để đặt vị trí
                        if (moved) {
                            main.posX = p.x
                            main.posY = p.y
                            onPositionChanged?.invoke(main)
                        }
                    } else {
                        runner.onMainReleased(main)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (!isEditing(id)) runner.onMainReleased(main)
                    true
                }
                else -> false
            }
        }
    }

    private fun removeView(id: String) {
        views.remove(id)?.let { runCatching { wm.removeView(it) } }
        params.remove(id)
    }
}
