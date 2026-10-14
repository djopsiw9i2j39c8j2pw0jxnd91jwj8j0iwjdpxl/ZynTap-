package com.zyntap.app.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Hộp nhập chữ nổi (đổi tên nút...). Nằm ở MÉP TRÊN card để bàn phím không che mất.
 *
 * Cửa sổ menu bình thường không nhận bàn phím (FLAG_NOT_FOCUSABLE) nên mỗi lần mở hộp này,
 * [onKeyboard] (true) sẽ được gọi để service cho cửa sổ nhận focus, và (false) khi đóng để trả lại.
 */
class TextPromptLayer(
    context: Context,
    private val onKeyboard: (Boolean) -> Unit,
) : FrameLayout(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private val titleText = TextView(context).apply {
        setTextColor(0xFFE8EEFA.toInt())
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
    }
    private val input = EditText(context).apply {
        setTextColor(0xFFE8EEFA.toInt())
        setHintTextColor(0xFF5E6E8C.toInt())
        textSize = 15f
        isSingleLine = true
        setPadding(dp(14), 0, dp(14), 0)
        background = GradientDrawable().apply {
            setColor(0xFF0A1220.toInt())
            setStroke(dp(1), 0xFF19D3FF.toInt())
            cornerRadius = dp(12).toFloat()
        }
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        setHighlightColor(0x5519D3FF)
    }
    private val dialog = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(16)
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            setColor(0xFF121C30.toInt())
            setStroke(dp(1), 0xFF2A3A5C.toInt())
            cornerRadius = dp(16).toFloat()
        }
        isClickable = true
    }

    private var onDone: ((String) -> Unit)? = null
    private var shown = false

    val isOpen: Boolean get() = shown

    init {
        visibility = View.GONE
        setBackgroundColor(0xB3050A15.toInt())
        isClickable = true
        setOnClickListener { cancel() }

        dialog.addView(titleText)
        dialog.addView(
            input,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(10) },
        )
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Huỷ", 0xFF1E2B44.toInt(), 0xFFE8EEFA.toInt()) { cancel() }, LinearLayout.LayoutParams(0, dp(40), 1f))
        row.addView(
            button("Xong", 0xFF2A6BFF.toInt(), 0xFFFFFFFF.toInt()) { confirm() },
            LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(10) },
        )
        dialog.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(14) },
        )
        addView(dialog, LayoutParams(dp(340), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = dp(14) })

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { confirm(); true } else false
        }
    }

    fun show(title: String, initial: String, maxLen: Int, onDone: (String) -> Unit) {
        if (shown) return
        shown = true
        this.onDone = onDone
        titleText.text = title
        input.filters = arrayOf(InputFilter.LengthFilter(maxLen))
        input.setText(initial)
        input.setSelection(input.text.length)

        visibility = View.VISIBLE
        alpha = 0f
        dialog.scaleX = 0.88f; dialog.scaleY = 0.88f
        animate().cancel(); dialog.animate().cancel()
        animate().alpha(1f).setDuration(160L).start()
        dialog.animate().scaleX(1f).scaleY(1f).setDuration(240L).setInterpolator(OvershootInterpolator(1.2f)).start()

        onKeyboard(true)               // cho cửa sổ nhận focus
        input.requestFocus()
        // Cửa sổ nhận focus bất đồng bộ -> thử hiện bàn phím vài lần
        postDelayed({ showIme() }, 120L)
        postDelayed({ showIme() }, 380L)
    }

    private fun showIme() {
        if (!shown) return
        input.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun confirm() {
        val text = input.text.toString().trim()
        val cb = onDone
        close()
        if (text.isNotEmpty()) cb?.invoke(text)
    }

    private fun cancel() = close()

    /** Phím Back khi hộp đang mở: đóng hộp. Trả về true nếu đã xử lý. */
    fun handleBack(): Boolean {
        if (!shown) return false
        close()
        return true
    }

    fun close() {
        if (!shown) return
        shown = false
        onDone = null
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        onKeyboard(false)
        animate().cancel()
        animate().alpha(0f).setDuration(140L).withEndAction { if (!shown) visibility = View.GONE }.start()
    }

    private fun button(label: String, bg: Int, fg: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label
        setTextColor(fg)
        textSize = 14.5f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), round(bg, 12), round(0xFFFFFFFF.toInt(), 12))
        isClickable = true
        setOnClickListener { onClick() }
    }
}
