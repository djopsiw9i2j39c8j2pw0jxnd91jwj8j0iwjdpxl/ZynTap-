package com.zyntap.app.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputFilter
import android.text.InputType
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kho các cấu hình macro đã lưu: tên -> ảnh chụp toàn bộ danh sách nút main (gồm kịch bản điểm nhấn,
 * vị trí, cỡ, chế độ...). Lưu bền vào SharedPreferences dạng JSON.
 */
class ConfigStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(): JSONObject = runCatching { JSONObject(prefs.getString(KEY, "{}") ?: "{}") }.getOrDefault(JSONObject())

    fun names(): List<String> = read().keys().asSequence().toList().sortedBy { it.lowercase() }

    /** Tên đã có (không phân biệt hoa thường), trả về đúng tên đang lưu; null nếu chưa có. */
    fun find(name: String): String? = names().firstOrNull { it.equals(name, ignoreCase = true) }

    fun get(name: String): String? = read().optString(name, "").ifEmpty { null }

    /** Số nút main trong cấu hình [name]. */
    fun count(name: String): Int = runCatching { JSONArray(get(name) ?: "[]").length() }.getOrDefault(0)

    fun put(name: String, json: String) {
        val o = read()
        o.put(name, json)
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    fun remove(name: String) {
        val o = read()
        o.remove(name)
        prefs.edit().putString(KEY, o.toString()).apply()
    }

    private companion object {
        const val PREFS = "zyntap_configs"
        const val KEY = "configs"
    }
}

/** Giới hạn + giá trị hiện tại của kích thước menu (đơn vị dp của UI nổi). */
data class PanelSizeInfo(
    val curW: Int, val curH: Int,
    val minW: Int, val minH: Int,
    val maxW: Int, val maxH: Int,
)

/**
 * Tab 3 — Cấu hình:
 *  - lưu / tải / cập nhật / xoá các cấu hình macro (toàn bộ nút main + kịch bản ở tab 1, 2);
 *  - chỉnh kích thước menu bằng hai ô nhập Rộng × Cao.
 */
class ConfigTab(
    private val ctx: Context,
    private val repo: MainButtonRepo,
    private val store: ConfigStore,
    private val host: PanelHost,
    private val keyboard: (Boolean) -> Unit,
    private val stopRuns: () -> Unit,
    /** Đã nạp cấu hình [name] vào repo: dịch vụ dựng lại nút + menu. */
    private val onLoaded: (name: String) -> Unit,
    private val sizeInfo: () -> PanelSizeInfo,
    /** w/h = 0: trả về kích thước mặc định. */
    private val onApplySize: (w: Int, h: Int) -> Unit,
) {
    private val d = UiScale.density(ctx.resources)
    private fun dp(n: Int) = (n * d).toInt()
    private val fg = 0xFFE8EEFA.toInt()
    private val muted = 0xFF8D9DBA.toInt()
    private val cyan = 0xFF19D3FF.toInt()
    private val danger = 0xFFFFA69E.toInt()

    private lateinit var nameInput: EditText
    private lateinit var dropdownField: TextView
    private lateinit var status: TextView
    private lateinit var deleteBtn: TextView
    private lateinit var wInput: EditText
    private lateinit var hInput: EditText
    private lateinit var sizeHint: TextView
    private var selected: String? = null
    private var visible = false
    private var deleteArmed = false
    private val disarm = Runnable { disarmDelete() }

    private fun round(color: Int, stroke: Int = 0xFF24344E.toInt()) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(12).toFloat(); setStroke(dp(1), stroke)
    }

    private fun label(text: String, size: Float = 12f) = TextView(ctx).apply {
        this.text = text; scaledTextSize(size); setTextColor(muted); gravity = Gravity.CENTER_VERTICAL
    }

    private fun row() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    }

    private fun input(hint: String, number: Boolean, maxLen: Int) = EditText(ctx).apply {
        this.hint = hint; scaledTextSize(14f)
        setTextColor(fg); setHintTextColor(0xFF596D8D.toInt())
        background = round(0xFF0A1220.toInt()); setPadding(dp(12), dp(6), dp(10), dp(6))
        setSingleLine(true)
        inputType = if (number) InputType.TYPE_CLASS_NUMBER
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        filters = arrayOf(InputFilter.LengthFilter(maxLen))
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        setSelectAllOnFocus(number)
        setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) { disarmDelete(); keyboard(true) }
            if (event.actionMasked == MotionEvent.ACTION_UP) focus(v as EditText)
            false
        }
        setOnFocusChangeListener { v, focus ->
            if (focus) { keyboard(true); v.background = round(0xFF0A1220.toInt(), cyan) }
            else v.background = round(0xFF0A1220.toInt())
        }
    }

    private fun button(text: String, bg: Int, fgColor: Int, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text; scaledTextSize(13f); setTextColor(fgColor)
        setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        maxLines = 1
        background = RippleDrawable(ColorStateList.valueOf(0x3319D3FF), round(bg), round(-1))
        isClickable = true
        setOnClickListener { leaveInput(); onClick() }
    }

    fun build(): View {
        val page = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(12))
            isFocusableInTouchMode = true
        }
        page.addView(label("Cấu hình", 17f).apply {
            setTextColor(fg); setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(-1, dp(30)).apply { marginEnd = dp(126) })

        // ---- Lưu macro ----
        page.addView(label("LƯU MACRO (tab 1 + tab 2)", 10.5f), LinearLayout.LayoutParams(-1, dp(25)).apply { topMargin = dp(4) })
        nameInput = input("Tên cấu hình mới", number = false, maxLen = MAX_NAME).apply {
            setOnEditorActionListener { _, action, event ->
                if (isEnter(action, event)) { leaveInput(); create(); true } else false
            }
        }
        page.addView(nameInput, LinearLayout.LayoutParams(-1, dp(42)))

        dropdownField = TextView(ctx).apply {
            scaledTextSize(14f); setTextColor(fg); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = RippleDrawable(ColorStateList.valueOf(0x2219D3FF), round(0xFF142036.toInt()), round(-1))
            setOnClickListener { leaveInput(); disarmDelete(); openDropdown() }
        }
        page.addView(dropdownField, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(8) })

        val actions = row()
        deleteBtn = button("Xóa", 0xFF3A1A22.toInt(), danger) { delete() }
        actions.addView(button("Tạo", 0xFF0F3B4C.toInt(), cyan) { create() }, LinearLayout.LayoutParams(0, dp(40), 1f))
        actions.addView(button("Load", 0xFF142036.toInt(), fg) { load() }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(6) })
        actions.addView(button("Cập nhật", 0xFF142036.toInt(), fg) { update() }, LinearLayout.LayoutParams(0, dp(40), 1.25f).apply { marginStart = dp(6) })
        actions.addView(deleteBtn, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(6) })
        page.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        status = label("", 10.5f).apply { maxLines = 2 }
        page.addView(status, LinearLayout.LayoutParams(-1, dp(34)).apply { topMargin = dp(2) })

        // ---- Kích thước menu ----
        page.addView(View(ctx).apply { setBackgroundColor(0xFF1E2B44.toInt()) },
            LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(6); bottomMargin = dp(8) })
        page.addView(label("KÍCH THƯỚC MENU (dp)", 10.5f), LinearLayout.LayoutParams(-1, dp(25)))
        wInput = input("Rộng", number = true, maxLen = 4)
        hInput = input("Cao", number = true, maxLen = 4)
        val sizeRow = row()
        sizeRow.addView(wInput, LinearLayout.LayoutParams(0, dp(42), 1f))
        sizeRow.addView(label("×", 16f).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(26), dp(42)))
        sizeRow.addView(hInput, LinearLayout.LayoutParams(0, dp(42), 1f))
        page.addView(sizeRow)
        sizeHint = label("", 10.5f).apply { maxLines = 2 }
        page.addView(sizeHint, LinearLayout.LayoutParams(-1, dp(34)).apply { topMargin = dp(2) })
        val sizeActions = row()
        sizeActions.addView(button("Áp dụng", 0xFF0F3B4C.toInt(), cyan) { applySize() }, LinearLayout.LayoutParams(0, dp(40), 1f))
        sizeActions.addView(button("Mặc định", 0xFF142036.toInt(), fg) { onApplySize(0, 0) }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(6) })
        page.addView(sizeActions)

        refreshDropdown()
        refreshSizeFields()
        showStatus("Lưu cả nút main + kịch bản để dùng lại", false)
        return ScrollView(ctx).apply {
            isFillViewport = true; isVerticalScrollBarEnabled = false
            addView(page, ViewGroup.LayoutParams(-1, -2))
        }
    }

    // ---- cấu hình macro ----

    private fun openDropdown() {
        val names = store.names()
        if (names.isEmpty()) { showStatus("Chưa có cấu hình nào — nhập tên rồi bấm Tạo", true); return }
        val items = names.map { "$it  ·  ${store.count(it)} main" }
        host.dropdown.show(dropdownField, items, names.indexOf(selected)) { i ->
            selected = names[i]
            nameInput.setText(names[i])
            refreshDropdown()
            showStatus("Đã chọn \"${names[i]}\" · bấm Load để nạp", false)
        }
    }

    private fun refreshDropdown() {
        val s = selected?.takeIf { store.get(it) != null }
        selected = s
        dropdownField.text = (s ?: "Chọn cấu hình đã lưu") + "     ▾"
        dropdownField.setTextColor(if (s != null) fg else muted)
    }

    private fun create() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) { showStatus("Nhập tên cấu hình trước", true); return }
        val existing = store.find(name)
        if (existing != null) {
            selected = existing
            refreshDropdown()
            showStatus("\"$existing\" đã có — dùng Cập nhật để ghi đè, không tạo trùng", true)
            return
        }
        store.put(name, repo.exportJson())
        selected = name
        refreshDropdown()
        showStatus("Đã tạo \"$name\" (${repo.buttons.size} main)", false)
    }

    private fun load() {
        val name = selected
        if (name == null) { showStatus("Chọn một cấu hình trong danh sách trước", true); return }
        val json = store.get(name)
        if (json == null) { refreshDropdown(); showStatus("Cấu hình không còn tồn tại", true); return }
        stopRuns()
        if (!repo.replaceFromJson(json)) { showStatus("Cấu hình bị hỏng, không nạp được", true); return }
        onLoaded(name)
    }

    private fun update() {
        val name = selected
        if (name == null) { showStatus("Chọn cấu hình cần cập nhật trong danh sách", true); return }
        store.put(name, repo.exportJson())
        refreshDropdown()
        showStatus("Đã cập nhật \"$name\" (${repo.buttons.size} main)", false)
    }

    private fun delete() {
        val name = selected
        if (name == null) { showStatus("Chọn cấu hình cần xóa trong danh sách", true); return }
        if (!deleteArmed) {
            // Bấm lần 2 trong 3 giây mới xóa thật, tránh bấm nhầm
            deleteArmed = true
            deleteBtn.text = "Chắc?"
            deleteBtn.postDelayed(disarm, 3000)
            showStatus("Bấm Xóa lần nữa để xóa \"$name\"", true)
            return
        }
        disarmDelete()
        store.remove(name)
        selected = null
        refreshDropdown()
        showStatus("Đã xóa \"$name\"", false)
    }

    private fun disarmDelete() {
        if (!::deleteBtn.isInitialized) return
        deleteBtn.removeCallbacks(disarm)
        deleteArmed = false
        deleteBtn.text = "Xóa"
    }

    // ---- kích thước menu ----

    private fun refreshSizeFields() {
        val s = sizeInfo()
        wInput.setText(s.curW.toString())
        hInput.setText(s.curH.toString())
        sizeHint.text = "Rộng ${s.minW}–${s.maxW} · Cao ${s.minH}–${s.maxH} (tối đa theo màn hình)"
        sizeHint.setTextColor(muted)
    }

    private fun applySize() {
        val s = sizeInfo()
        val w = wInput.text.toString().toIntOrNull()
        val h = hInput.text.toString().toIntOrNull()
        if (w == null || h == null) {
            sizeHint.text = "Nhập đủ Rộng và Cao (số nguyên)"; sizeHint.setTextColor(danger); return
        }
        onApplySize(w.coerceIn(s.minW, s.maxW), h.coerceIn(s.minH, s.maxH))
    }

    // ---- chung ----

    private fun isEnter(action: Int, event: KeyEvent?) =
        action == EditorInfo.IME_ACTION_DONE || (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)

    private fun focus(input: EditText) {
        keyboard(true)
        input.requestFocus()
        input.postDelayed({
            if (visible && input.hasFocus() && input.isAttachedToWindow) {
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 180)
    }

    private fun showStatus(text: String, error: Boolean) {
        status.text = text
        status.setTextColor(if (error) danger else cyan)
    }

    fun enter() {
        visible = true
        if (::dropdownField.isInitialized) { refreshDropdown(); refreshSizeFields() }
    }

    fun leaveInput() {
        if (!::nameInput.isInitialized) return
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(nameInput.windowToken, 0)
        listOf(nameInput, wInput, hInput).forEach { it.clearFocus() }
        keyboard(false)
    }

    fun exit() {
        visible = false
        disarmDelete()
        leaveInput()
    }

    private companion object {
        const val MAX_NAME = 30
    }
}
