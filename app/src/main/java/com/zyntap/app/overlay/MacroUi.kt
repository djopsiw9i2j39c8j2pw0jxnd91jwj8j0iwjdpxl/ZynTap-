package com.zyntap.app.overlay

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Helper giao diện dùng chung cho các tab của menu nổi (View thuần, vì menu là cửa sổ overlay). */
internal class MacroUi(private val ctx: Context) {

    fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun vbox(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun hbox(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    fun text(value: String, sizeSp: Float = 13f, color: Int = TEXT, bold: Boolean = false) = TextView(ctx).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun section(title: String) = text(title, 14f, TEXT, bold = true).apply {
        setPadding(0, dp(10), 0, dp(4))
    }

    fun label(value: String) = text(value, 12.5f, DIM).apply {
        setPadding(0, dp(6), 0, dp(3))
    }

    /** Ô nhập một dòng. Mặc định KHÔNG lấy tiêu điểm: chỉ nhập khi được bật bằng [enableInput]. */
    fun field(hint: String, numeric: Boolean = false) = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(0xFF4F5F7A.toInt())
        setTextColor(TEXT)
        textSize = 14f
        isSingleLine = true
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_DONE
        background = rounded(SURFACE_HI, 10, BORDER)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        isFocusable = false
        isFocusableInTouchMode = false
        keepTouches()
    }

    /** Ô nhập nhiều dòng, cao cố định, kéo lên xuống được. */
    fun textArea(hint: String, heightDp: Int) = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(0xFF4F5F7A.toInt())
        setTextColor(TEXT)
        textSize = 13.5f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        gravity = Gravity.TOP or Gravity.START
        isVerticalScrollBarEnabled = true
        isScrollbarFadingEnabled = false
        isSingleLine = false
        minLines = 6
        maxLines = 1000
        background = rounded(SURFACE_HI, 10, BORDER)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
        keepTouches()
    }

    fun button(label: String, accent: Boolean = false, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (accent) 0xFFFFFFFF.toInt() else TEXT)
        background = if (accent) {
            GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.LEFT_RIGHT
                colors = intArrayOf(BLUE, CYAN)
                cornerRadius = dp(12).toFloat()
            }
        } else {
            rounded(SURFACE_HI, 12, BORDER)
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
        isClickable = true
        setOnClickListener { onClick() }
    }

    fun seekBar(max: Int, progress: Int, onChange: (Int) -> Unit) = SeekBar(ctx).apply {
        this.max = max
        this.progress = progress
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) onChange(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        keepTouches()
    }

    /** Hộp chọn kiểu dropdown: bấm vào để mở danh sách ngay bên dưới (không dùng popup để tránh lỗi cửa sổ). */
    inner class Dropdown : LinearLayout(ctx) {
        private val header = text("", 14f, TEXT).apply {
            background = rounded(SURFACE_HI, 10, BORDER)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        private val list = vbox().apply { visibility = GONE }
        var placeholder = "Chọn..."

        init {
            orientation = VERTICAL
            addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            header.isClickable = true
            header.setOnClickListener { list.visibility = if (list.visibility == VISIBLE) GONE else VISIBLE }
            header.keepTouches()
        }

        fun setItems(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
            header.text = labels.getOrNull(selected)?.let { "$it  ▾" } ?: "$placeholder  ▾"
            list.removeAllViews()
            labels.forEachIndexed { i, s ->
                val item = text(s, 13.5f, if (i == selected) CYAN else TEXT).apply {
                    setPadding(dp(12), dp(9), dp(12), dp(9))
                    isClickable = true
                    setOnClickListener {
                        list.visibility = GONE
                        onSelect(i)
                    }
                }
                item.keepTouches()
                list.addView(item, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(3)
                })
            }
        }
    }

    fun showKeyboard(v: View) {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        v.postDelayed({ imm?.showSoftInput(v, 0) }, 120L)
    }

    fun hideKeyboard(v: View) {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    companion object {
        const val TEXT = 0xFFE8EEFA.toInt()
        const val DIM = 0xFF8D9DBA.toInt()
        const val SURFACE_HI = 0xFF142036.toInt()
        const val BORDER = 0xFF1E2B44.toInt()
        const val CYAN = 0xFF19D3FF.toInt()
        const val BLUE = 0xFF2A6BFF.toInt()
    }
}

/**
 * Các ô tương tác nằm trong card kéo ngang để đóng menu: báo cho cha đừng cướp cử chỉ khi chạm vào ô này
 * (nếu không, kéo thanh trượt hay bôi đen chữ sang trái sẽ làm menu bị thu lại).
 */
internal fun View.keepTouches() {
    setOnTouchListener { v, e ->
        if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
        false
    }
}
