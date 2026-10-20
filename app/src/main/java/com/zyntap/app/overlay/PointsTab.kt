package com.zyntap.app.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Per-main, ordered script editor. Text is persisted, never executed as shell. */
class PointsTab(
    private val ctx: Context,
    private val repo: MainButtonRepo,
    private val host: PanelHost,
    private val picker: PointPickerOverlay,
    private val bounds: () -> Rect,
    private val keyboard: (Boolean) -> Unit,
    private val stopRuns: () -> Unit,
) {
    private val d = UiScale.density(ctx.resources)
    private fun dp(n: Int) = (n * d).toInt()
    private val fg = 0xFFE8EEFA.toInt()
    private val muted = 0xFF8D9DBA.toInt()
    private val cyan = 0xFF19D3FF.toInt()
    private lateinit var editor: EditText
    private lateinit var mainField: TextView
    private lateinit var xField: EditText
    private lateinit var yField: EditText
    private lateinit var delayField: EditText
    private lateinit var multiXField: EditText
    private lateinit var multiYField: EditText
    /** Các điểm của dòng Multiclick đang soạn (chưa ghi vào kịch bản tới khi chạm Xong). */
    private val multiPending = mutableListOf<Pair<Int, Int>>()
    private lateinit var status: TextView
    private var selectedId: Int? = null
    private var loading = false
    private var manualArmed = false
    private var visible = false
    private fun selected() = selectedId?.let(repo::find)
    private fun round(color: Int, stroke: Int = 0xFF24344E.toInt()) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(12).toFloat(); setStroke(dp(1), stroke)
    }
    private fun label(text: String, size: Float = 12f) = TextView(ctx).apply {
        this.text = text; scaledTextSize(size); setTextColor(muted); gravity = Gravity.CENTER_VERTICAL
    }
    private fun row() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    }
    private fun field(hint: String) = EditText(ctx).apply {
        this.hint = hint; scaledTextSize(14f)
        setTextColor(fg); setHintTextColor(0xFF596D8D.toInt())
        background = round(0xFF0A1220.toInt()); setPadding(dp(12), dp(6), dp(10), dp(6))
        setSingleLine(true); inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(10))
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        setSelectAllOnFocus(true)
        setOnFocusChangeListener { v, focus ->
            if (focus) { keyboard(true); v.background = round(0xFF0A1220.toInt(), cyan) }
            else v.background = round(0xFF0A1220.toInt())
        }
    }

    fun build(): View {
        // Khung tiêu đề CỐ ĐỊNH (không cuộn theo nội dung), phần dưới mới là vùng cuộn
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), 0)
        }
        val page = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(12))
            isFocusableInTouchMode = true
        }
        addTabHeader(ctx, root, "Điểm nhấn", "Kịch bản click cho từng nút main")
        mainField = TextView(ctx).apply {
            scaledTextSize(14f); setTextColor(fg); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = RippleDrawable(ColorStateList.valueOf(0x2219D3FF), round(0xFF142036.toInt()), round(-1))
            setOnClickListener {
                leaveInput()
                val list = repo.buttons.toList()
                if (list.isEmpty()) { showStatus("Tạo nút main ở tab 1 trước", true); return@setOnClickListener }
                host.dropdown.show(this, list.map { "${it.code}  ·  ${it.name}" }, list.indexOfFirst { it.id == selectedId }) { index -> select(list[index].id) }
            }
        }
        page.addView(mainField, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(6) })
        val caption = row()
        caption.addView(label("KỊCH BẢN", 10.5f), LinearLayout.LayoutParams(0, dp(25), 1f))
        caption.addView(label("Tự động lưu", 10.5f).apply { setTextColor(cyan) })
        page.addView(caption)
        editor = EditText(ctx).apply {
            hint = "828, 191\n300ms\nmulti 18,181 | 400,500"
            setTextColor(0xFFC3EAFE.toInt()); setHintTextColor(0xFF405675.toInt())
            scaledTextSize(13.5f); typeface = Typeface.MONOSPACE; gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            filters = arrayOf(InputFilter.LengthFilter(PointScriptParser.MAX_TEXT))
            background = round(0xFF080F1C.toInt()); setPadding(dp(12), dp(10), dp(12), dp(10))
            minHeight = dp(130); isVerticalScrollBarEnabled = true
            setHorizontallyScrolling(false)
            setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) { picker.close(); keyboard(true) }
                if (event.actionMasked == MotionEvent.ACTION_UP) focus(this, false)
                v.parent.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP && event.actionMasked != MotionEvent.ACTION_CANCEL)
                false
            }
            setOnFocusChangeListener { _, focus -> if (focus) keyboard(true) }
        }
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!loading) { selected()?.let { it.pointScript = s.toString(); repo.save() }; validate() }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        page.addView(editor, LinearLayout.LayoutParams(-1, dp(150), 1f))
        status = label("", 10.5f).apply { maxLines = 2 }
        page.addView(status, LinearLayout.LayoutParams(-1, dp(30)))
        buildInputs(page)
        page.addView(label("Chạm X/Y → kéo ghim → chạm ghim → Enter.\nDelay chờ tại dòng đó, trước điểm tiếp theo.\nMulticlick: Enter từng điểm rồi Xong → click tất cả CÙNG LÚC.", 10.5f), LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(6) })
        root.addView(ScrollView(ctx).apply {
            isFillViewport = true; isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(page, ViewGroup.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun buildInputs(page: LinearLayout) {
        val pointRow = row()
        pointRow.addView(label("Click point"), LinearLayout.LayoutParams(dp(78), dp(42)))
        xField = field("X"); yField = field("Y")
        pointRow.addView(xField, LinearLayout.LayoutParams(0, dp(42), 1f))
        pointRow.addView(yField, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) })
        wirePointFields(xField, yField) { appendPoint() }
        page.addView(pointRow)

        // Multiclick point: nhập từng điểm như Click point, rồi chạm "Xong" để ghi MỘT dòng click TẤT CẢ các điểm CÙNG LÚC
        val multiRow = row()
        multiRow.addView(label("Multiclick"), LinearLayout.LayoutParams(dp(78), dp(42)))
        multiXField = field("X"); multiYField = field("Y")
        multiRow.addView(multiXField, LinearLayout.LayoutParams(0, dp(42), 1f))
        multiRow.addView(multiYField, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) })
        val done = TextView(ctx).apply {
            text = "Xong"; scaledTextSize(12.5f); setTextColor(cyan); gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = RippleDrawable(ColorStateList.valueOf(0x2219D3FF), round(0xFF142036.toInt(), cyan), round(-1))
            setOnClickListener { commitMulti() }
        }
        multiRow.addView(done, LinearLayout.LayoutParams(dp(54), dp(42)).apply { marginStart = dp(8) })
        wirePointFields(multiXField, multiYField) { addMultiPoint() }
        page.addView(multiRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val delayRow = row()
        delayRow.addView(label("Delay"), LinearLayout.LayoutParams(dp(78), dp(42)))
        delayField = field("Nhập delay (ms)").apply {
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) { picker.close(); keyboard(true) }
                if (event.actionMasked == MotionEvent.ACTION_UP) focus(this, false)
                false
            }
            setOnEditorActionListener { _, action, event ->
                if (isEnter(action, event)) { appendDelay(); true } else false
            }
        }
        delayRow.addView(delayField, LinearLayout.LayoutParams(0, dp(42), 1f))
        delayRow.addView(label("ms"), LinearLayout.LayoutParams(dp(30), dp(42)).apply { marginStart = dp(8) })
        page.addView(delayRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    /** Cặp ô X/Y: chạm lần đầu mở ghim chọn điểm, chạm lần sau mở bàn phím; Enter gọi [onEnter]. */
    private fun wirePointFields(xf: EditText, yf: EditText, onEnter: () -> Unit) {
        listOf(xf, yf).forEach { input ->
            input.setOnTouchListener { _, event ->
                if (!manualArmed) {
                    if (event.actionMasked == MotionEvent.ACTION_UP && selected() != null) {
                        manualArmed = true
                        leaveInput()
                        picker.show(xf.text.toString().toIntOrNull(), yf.text.toString().toIntOrNull()) { x, y ->
                            if (visible) {
                                xf.setText(x.toString()); yf.setText(y.toString())
                                showStatus("Đã chọn ($x, $y) · Enter để thêm", false)
                                focus(input)
                            }
                        }
                    }
                    true
                } else {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) { picker.close(); keyboard(true) }
                    if (event.actionMasked == MotionEvent.ACTION_UP) focus(input, false)
                    false
                }
            }
            input.setOnEditorActionListener { _, action, event ->
                if (isEnter(action, event)) { onEnter(); true } else false
            }
        }
    }

    private fun addMultiPoint() {
        val x = multiXField.text.toString().toIntOrNull()
        val y = multiYField.text.toString().toIntOrNull()
        val b = bounds()
        if (x == null || y == null || x !in 0 until b.width() || y !in 0 until b.height()) {
            showStatus("X: 0..${b.width()-1} · Y: 0..${b.height()-1}", true); return
        }
        if (multiPending.size >= PointScriptParser.MAX_MULTI) {
            showStatus("Multiclick tối đa ${PointScriptParser.MAX_MULTI} điểm · chạm Xong", true); return
        }
        multiPending += x to y
        multiXField.setText(""); multiYField.setText("")
        manualArmed = false
        leaveInput()
        showStatus("Multiclick: ${multiPending.size} điểm · thêm điểm khác hoặc chạm Xong", false)
    }

    private fun commitMulti() {
        if (selected() == null) return
        // Điểm đang nằm trong ô X/Y mà chưa Enter thì tính luôn.
        val px = multiXField.text.toString().toIntOrNull()
        val py = multiYField.text.toString().toIntOrNull()
        if (px != null && py != null) addMultiPoint()
        if (multiPending.size < 2) {
            showStatus("Multiclick cần ít nhất 2 điểm (nhập X/Y rồi Enter)", true); return
        }
        append("multi " + multiPending.joinToString(" | ") { "${it.first},${it.second}" })
        multiPending.clear()
        manualArmed = false
        leaveInput()
    }

    private fun isEnter(action: Int, event: KeyEvent?) =
        action == EditorInfo.IME_ACTION_DONE || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)

    private fun focus(input: EditText, moveToEnd: Boolean = true) {
        keyboard(true)
        input.requestFocus()
        input.postDelayed({
            if (visible && input.hasFocus() && input.isAttachedToWindow) {
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                if (moveToEnd) input.setSelection(input.length())
            }
        }, 180)
    }

    private fun appendPoint() {
        val x = xField.text.toString().toIntOrNull()
        val y = yField.text.toString().toIntOrNull()
        val b = bounds()
        if (x == null || y == null || x !in 0 until b.width() || y !in 0 until b.height()) {
            showStatus("X: 0..${b.width()-1} · Y: 0..${b.height()-1}", true); return
        }
        append("$x, $y")
        manualArmed = false
        leaveInput()
    }

    private fun appendDelay() {
        val ms = delayField.text.toString().toLongOrNull()
        if (ms == null || ms !in 0..PointScriptParser.MAX_DELAY) {
            showStatus("Delay cần nằm trong 0..${PointScriptParser.MAX_DELAY}ms", true); return
        }
        append("${ms}ms")
        delayField.selectAll()
    }

    private fun append(line: String) {
        if (selected() == null) return
        val old = editor.text.toString().trimEnd()
        val next = if (old.isBlank()) "$line\n" else "$old\n$line\n"
        if (next.length > PointScriptParser.MAX_TEXT) { showStatus("Kịch bản đã đầy", true); return }
        editor.setText(next)
        editor.setSelection(editor.length())
        editor.post { editor.scrollTo(0, ((editor.layout?.height ?: 0) - editor.height + editor.totalPaddingTop + editor.totalPaddingBottom).coerceAtLeast(0)) }
        editor.animate().cancel(); editor.alpha = 0.65f
        editor.animate().alpha(1f).setDuration(200).start()
    }

    private fun showStatus(text: String, error: Boolean) {
        status.text = text
        status.setTextColor(if (error) 0xFFFFA69E.toInt() else cyan)
    }

    private fun validate() {
        val b = bounds()
        val result = PointScriptParser.parse(editor.text.toString(), b.width(), b.height())
        val error = result.errors.firstOrNull()
        showStatus(error ?: "${result.tapCount} điểm · ${result.steps.count { it is PointStep.Wait }} delay · Đã lưu", error != null)
    }

    private fun select(id: Int?) {
        picker.close(); selectedId = id; manualArmed = false
        loading = true
        editor.setText(selected()?.pointScript.orEmpty())
        xField.setText(""); yField.setText(""); delayField.setText("")
        multiXField.setText(""); multiYField.setText(""); multiPending.clear()
        loading = false
        val button = selected()
        mainField.text = button?.let { "${it.code}  ·  ${it.name}     ▾" } ?: "Chưa có main · Tạo ở tab 1"
        listOf(editor, xField, yField, delayField, multiXField, multiYField).forEach { it.isEnabled = button != null }
        if (button == null) showStatus("Mỗi main có kịch bản riêng", false) else validate()
    }

    fun enter() {
        visible = true; stopRuns()
        select(selectedId?.takeIf { repo.find(it) != null } ?: repo.buttons.firstOrNull()?.id)
    }

    fun leaveInput() {
        if (!::editor.isInitialized) return
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editor.windowToken, 0)
        listOf(editor, xField, yField, delayField, multiXField, multiYField).forEach { it.clearFocus() }
        keyboard(false)
    }

    fun exit() {
        visible = false; picker.close(); leaveInput()
    }
}
