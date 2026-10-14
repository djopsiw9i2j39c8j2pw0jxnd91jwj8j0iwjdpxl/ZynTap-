package com.zyntap.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.zyntap.app.data.MainButton
import com.zyntap.app.data.TapMode
import kotlin.math.max
import kotlin.math.roundToInt

/** Bộ dựng giao diện 3 tab của menu nổi (View thuần, không dùng XML). */
object TapViews {

    const val C_TEXT = 0xFFE8EEFA.toInt()
    const val C_DIM = 0xFF8D9DBA.toInt()
    const val C_SURFACE = 0xFF0D1524.toInt()
    const val C_SURFACE_HI = 0xFF142036.toInt()
    const val C_BORDER = 0xFF1E2B44.toInt()
    const val C_CYAN = 0xFF19D3FF.toInt()
    const val C_BLUE = 0xFF2A6BFF.toInt()
    const val C_RED = 0xFFEF5B5B.toInt()

    fun dp(v: View, x: Int): Int = (x * v.resources.displayMetrics.density).toInt()

    fun label(ctx: Context, text: String, size: Float = 13.5f, color: Int = C_DIM): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        }

    fun editText(ctx: Context, hint: String, number: Boolean = true): EditText = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(0x668D9DBA)
        setTextColor(C_TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setSingleLine(true)
        inputType = if (number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        background = GradientDrawable().apply {
            setColor(C_SURFACE)
            setStroke(2, C_BORDER)
            cornerRadius = 24f
        }
        val p = 28
        setPadding(p, 0, p, 0)
    }

    fun button(ctx: Context, text: String, bgColor: Int = C_SURFACE_HI, textColor: Int = C_TEXT): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                GradientDrawable().apply { setColor(bgColor); cornerRadius = 24f },
                GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 24f },
            )
            isClickable = true
        }

    /** Mục trong menu trượt xuống: nền bo góc, chữ trắng. */
    private fun spinnerItem(ctx: Context): TextView = TextView(ctx).apply {
        setTextColor(C_TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
        gravity = Gravity.CENTER_VERTICAL
        val p = (ctx.resources.displayMetrics.density * 14).toInt()
        setPadding(p, 0, p, 0)
        background = ColorDrawable(Color.TRANSPARENT)
    }

    fun <T> makeSpinner(
        ctx: Context,
        items: List<T>,
        labelOf: (T) -> String,
        onSelected: (Int) -> Unit,
    ): Spinner {
        val spinner = Spinner(ctx)
        val adapter = object : ArrayAdapter<T>(ctx, 0, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val t = (convertView as? TextView) ?: spinnerItem(ctx)
                t.text = if (count == 0) "" else labelOf(getItem(position)!!)
                return t
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val t = (convertView as? TextView) ?: spinnerItem(ctx)
                t.text = labelOf(getItem(position)!!)
                t.setMinHeight((ctx.resources.displayMetrics.density * 44).toInt())
                return t
            }
        }
        spinner.adapter = adapter
        spinner.background = GradientDrawable().apply {
            setColor(C_SURFACE)
            setStroke(2, C_BORDER)
            cornerRadius = 24f
        }
        spinner.setPopupBackgroundDrawable(GradientDrawable().apply {
            setColor(C_SURFACE_HI)
            setStroke(2, C_BORDER)
            cornerRadius = 24f
        })
        spinner.dropDownVerticalOffset = (ctx.resources.displayMetrics.density * 8).toInt()
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onSelected(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        return spinner
    }

    /** Ô mã lệnh to, cuộn dọc, hiển thị chuỗi điểm + delay đã nhập. */
    fun scriptBox(ctx: Context, heightDp: Int = 150): EditText {
        val d = ctx.resources.displayMetrics.density
        return EditText(ctx).apply {
            setTextColor(C_TEXT)
            setHintTextColor(0x668D9DBA)
            hint = "Tọa độ sẽ hiện ở đây (vd:\n828, 191\n300ms\n...)"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            minHeight = (heightDp * d).toInt()
            maxHeight = (heightDp * d).toInt()
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setHorizontallyScrolling(false)
            // Ô nằm trong ScrollView của tab: khi ô còn cuộn được thì kéo trong ô là cuộn nội dung ô
            setOnTouchListener { v, ev ->
                val inner = v.canScrollVertically(1) || v.canScrollVertically(-1)
                v.parent?.requestDisallowInterceptTouchEvent(
                    inner && ev.actionMasked != android.view.MotionEvent.ACTION_UP &&
                        ev.actionMasked != android.view.MotionEvent.ACTION_CANCEL,
                )
                false
            }
            background = GradientDrawable().apply {
                setColor(C_SURFACE)
                setStroke(2, C_BORDER)
                cornerRadius = 28f
            }
            val p = (14 * d).toInt()
            setPadding(p, p, p, p)
        }
    }

    /** Ô nhập tọa độ nhỏ trong "click point [x] [y]" — chạm là mở bộ chọn điểm ghim. */
    fun coordInput(ctx: Context, onTap: () -> Boolean): EditText {
        val e = EditText(ctx).apply {
            hint = "999"
            setHintTextColor(0x668D9DBA)
            setTextColor(C_CYAN)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = GradientDrawable().apply {
                setColor(C_SURFACE)
                setStroke(2, C_BORDER)
                cornerRadius = 20f
            }
            gravity = Gravity.CENTER
        }
        // onTap trả true (chưa có ghim -> vừa hiện ghim) thì nuốt cú chạm đó, chưa mở bàn phím.
        // Trả false (đã có ghim) thì để EditText nhận focus để tự gõ/sửa.
        var consumed = false
        e.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> consumed = onTap()
            }
            val result = consumed
            if (event.actionMasked == android.view.MotionEvent.ACTION_UP ||
                event.actionMasked == android.view.MotionEvent.ACTION_CANCEL
            ) consumed = false
            result
        }
        return e
    }

    /** Thanh item nút main trong danh sách (Tab 1). */
    fun buttonRow(
        ctx: Context,
        button: MainButton,
        selected: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(ctx).apply {
        text = if (button.name == button.id) button.id else "${button.id}  •  ${button.name}"
        setTextColor(if (selected) Color.WHITE else C_TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        background = GradientDrawable().apply {
            setColor(if (selected) C_BLUE else C_SURFACE_HI)
            setStroke(2, if (selected) C_CYAN else C_BORDER)
            cornerRadius = 24f
        }
        val p = (ctx.resources.displayMetrics.density * 14).toInt()
        setPadding(p, 0, p, 0)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Icon ghim (map-pin) kích thước dp tuỳ ý, tint trắng. */
    fun pinIcon(ctx: Context, res: Int, sizeDp: Int, tint: Int = Color.WHITE): ImageView {
        val d = ctx.resources.displayMetrics.density
        return ImageView(ctx).apply {
            setImageResource(res)
            setColorFilter(tint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = ViewGroup.LayoutParams((sizeDp * d).toInt(), (sizeDp * d).toInt())
        }
    }

    /** Căn giữa popup gần view, không tràn màn hình. */
    fun showNear(anchor: View, popup: PopupWindow) {
        popup.showAtLocation(anchor, Gravity.CENTER, 0, 0)
    }

    fun lpW(w: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT)

    fun lpMW(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    fun lpWeight(w: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w)

    fun TextView.bold(): TextView {
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        return this
    }

    /** Con số từ EditText, null nếu trống/không hợp lệ. */
    fun EditText.intOrNull(): Int? = text.toString().trim().toIntOrNull()

    /** SeekBar đổi size (dp) hoặc alpha (%) với nhãn kèm theo. */
    fun sliderRow(
        ctx: Context,
        labelText: String,
        value: Int,
        min: Int,
        maxV: Int,
        onChange: (Int) -> Unit,
    ): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val name = label(ctx, labelText, 13.5f, C_DIM)
        val valText = label(ctx, "$value", 13.5f, C_TEXT).bold()
        head.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(valText)
        col.addView(head)
        val seek = SeekBar(ctx).apply {
            this.max = maxV - min
            progress = (value - min).coerceIn(0, maxV - min)
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = min + progress
                valText.text = "$v"
                onChange(v)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        col.addView(seek, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return col
    }

    /** Khung cuộn ngang cho hàng nút main. */
    fun hScroll(ctx: Context, content: View): HorizontalScrollView =
        HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

    fun vScroll(ctx: Context, content: View): ScrollView =
        ScrollView(ctx).apply {
            isScrollbarFadingEnabled = false
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }

    fun row(ctx: Context, vararg views: Pair<View, LinearLayout.LayoutParams>): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            views.forEach { (v, lp) -> addView(v, lp) }
        }

    fun spacer(ctx: Context, dpH: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, (dpH * ctx.resources.displayMetrics.density).toInt())
    }

    fun col(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }

    /** Số âm/dương đều ok (tọa độ luôn dương nhưng cứ hỗ trợ). */
    fun parseInt(s: String): Int? = s.trim().toIntOrNull()

    fun maxInt(a: Int, b: Int): Int = max(a, b)

    fun Float.toI(): Int = roundToInt()
}
