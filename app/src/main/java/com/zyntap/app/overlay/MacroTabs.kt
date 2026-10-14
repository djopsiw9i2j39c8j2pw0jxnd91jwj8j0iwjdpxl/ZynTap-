package com.zyntap.app.overlay

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zyntap.app.macro.MainButton
import com.zyntap.app.macro.MainMode
import com.zyntap.app.macro.nextMainId

/** Những việc các tab cần từ dịch vụ overlay. */
interface MacroHost {
    /** Danh sách nút main đang dùng (sống, sửa trực tiếp). */
    fun mains(): MutableList<MainButton>
    /** Thay toàn bộ danh sách nút (khi load cấu hình). */
    fun replaceMains(list: List<MainButton>)
    /** Lưu lại + đồng bộ lại các nút nổi trên màn hình. */
    fun commitMains()

    fun editingId(): String?
    fun setEditing(id: String?)

    fun configNames(): List<String>
    fun loadConfig(name: String): List<MainButton>?
    fun saveConfig(name: String, mains: List<MainButton>)
    fun deleteConfig(name: String)

    fun startPicker(startX: Int, startY: Int, onPicked: (Int, Int) -> Unit)
    fun stopPicker()
    fun pickerVisible(): Boolean
    /** Bật/tắt nhận bàn phím cho cửa sổ menu. */
    fun setInputFocus(on: Boolean)
    fun screenCenter(): Pair<Int, Int>
    fun toast(msg: String)
}

/** Ba tab của menu: Tạo nút chính / Chỉnh điểm nhấn / Cài cấu hình. */
internal class MacroTabs(private val ctx: Context, private val host: MacroHost) {

    private val ui = MacroUi(ctx)
    private val dp = ui::dp

    // ---- tab 1: Tạo nút chính -------------------------------------------------

    private val settingsBox = ui.vbox()
    private val barsBox = ui.vbox()

    fun buildTab1(): View {
        val root = ui.vbox()
        root.addView(settingsBox)
        root.addView(ui.button("+ Tạo nút mới", accent = true) { createMain() }, lp(top = 12))
        root.addView(ui.section("Các nút đã tạo"))
        root.addView(barsBox)
        refreshTab1()
        return scroll(root)
    }

    /** Vẽ lại phần chỉnh sửa + danh sách thanh (gọi khi trạng thái chỉnh sửa đổi từ bên ngoài). */
    fun refreshTab1() {
        renderSettings()
        renderBars()
    }

    private fun renderSettings() {
        settingsBox.removeAllViews()
        val main = selectedMain()
        if (main == null) {
            settingsBox.addView(ui.text(
                "Chọn một thanh bên dưới để chỉnh, hoặc bấm “Tạo nút mới”.",
                13f, MacroUi.DIM,
            ).apply { setPadding(0, dp(6), 0, dp(6)) })
            return
        }
        settingsBox.addView(ui.section("Cài đặt nút ${main.id}"))

        settingsBox.addView(ui.label("Tên nút"))
        val nameField = ui.field("Tên nút").apply {
            setText(main.name)
            setOnFocusChangeListener { v, has -> onFieldFocus(v, has) }
            setOnClickListener { enableInput(this) }
            setOnEditorActionListener { v, _, _ ->
                v.clearFocus(); ui.hideKeyboard(v); true
            }
        }
        nameField.addTextChangedListener(simpleWatcher {
            main.name = it
            host.commitMains()
            renderBars()
        })
        settingsBox.addView(nameField)

        settingsBox.addView(ui.label("Chế độ kích hoạt"))
        val modeDrop = ui.Dropdown()
        val modes = MainMode.values()
        modeDrop.setItems(modes.map { it.label }, modes.indexOf(main.mode)) { i ->
            main.mode = modes[i]
            host.commitMains()
            renderBars()
        }
        settingsBox.addView(modeDrop)

        val opacityLabel = ui.text("", 12.5f, MacroUi.DIM)
        settingsBox.addView(ui.label("Độ mờ"))
        settingsBox.addView(opacityLabel)
        fun showOpacity() { opacityLabel.text = "${main.opacityPct}%" }
        showOpacity()
        settingsBox.addView(ui.seekBar(max = 80, progress = main.opacityPct - 20) { p ->
            main.opacityPct = p + 20
            showOpacity()
            host.commitMains()
            renderBars()
        })

        val sizeLabel = ui.text("", 12.5f, MacroUi.DIM)
        settingsBox.addView(ui.label("Kích thước"))
        settingsBox.addView(sizeLabel)
        fun showSize() { sizeLabel.text = "${main.sizeDp} dp" }
        showSize()
        settingsBox.addView(ui.seekBar(max = 56, progress = main.sizeDp - 40) { p ->
            main.sizeDp = p + 40
            showSize()
            host.commitMains()
        })

        settingsBox.addView(
            ui.button("Thoát chỉnh sửa") { host.setEditing(null); refreshTab1() },
            lp(top = 12),
        )
    }

    private fun renderBars() {
        barsBox.removeAllViews()
        val mains = host.mains()
        if (mains.isEmpty()) {
            barsBox.addView(ui.text("Chưa có nút nào.", 13f, MacroUi.DIM))
            return
        }
        val editing = host.editingId()
        mains.forEach { m ->
            val selected = m.id == editing
            val bar = ui.text("${m.name}   ·   ${m.id}   ·   ${m.mode.label}", 13.5f,
                if (selected) MacroUi.CYAN else MacroUi.TEXT).apply {
                background = ui.rounded(
                    if (selected) 0xFF1E3A66.toInt() else MacroUi.SURFACE_HI,
                    10,
                    if (selected) MacroUi.CYAN else MacroUi.BORDER,
                )
                setPadding(dp(12), dp(11), dp(12), dp(11))
                isClickable = true
                setOnClickListener {
                    // Chạm lần đầu: vào chỉnh sửa (hiện nút tròn). Chạm lại: thoát chỉnh sửa.
                    host.setEditing(if (selected) null else m.id)
                    refreshTab1()
                }
            }
            barsBox.addView(bar, lp(top = 6))
        }
    }

    private fun selectedMain(): MainButton? {
        val id = host.editingId() ?: return null
        return host.mains().firstOrNull { it.id == id }
    }

    private fun createMain() {
        val all = host.mains()
        val id = nextMainId(all)
        val m = MainButton(id = id, name = id)
        all.add(m)
        host.commitMains()
        host.setEditing(id)
        refreshTab1()
        refreshTab2Items()
    }

    // ---- tab 2: Chỉnh điểm nhấn ----------------------------------------------

    private var tab2Main: MainButton? = null
    private val mainDrop = ui.Dropdown()
    private lateinit var scriptArea: EditText
    private lateinit var xField: EditText
    private lateinit var yField: EditText
    private lateinit var delayField: EditText
    private var syncingScript = false

    fun buildTab2(): View {
        val root = ui.vbox()
        root.addView(ui.section("Nút main"))
        root.addView(mainDrop)

        root.addView(ui.section("Điểm chạm và delay"))
        root.addView(ui.text(
            "Mỗi dòng: “x, y” là một điểm chạm; “300ms” là thời gian chờ trước điểm kế tiếp.",
            12f, MacroUi.DIM,
        ))
        scriptArea = ui.textArea("111, 111\n300ms\n112, 112", heightDp = 170).apply {
            addTextChangedListener(simpleWatcher { text ->
                if (syncingScript) return@simpleWatcher
                tab2Main?.let { it.script = text; host.commitMains() }
            })
        }
        root.addView(scriptArea)

        root.addView(ui.label("Click point"))
        val row = ui.hbox()
        row.addView(ui.text("x", 13f, MacroUi.DIM), lp(end = 6))
        xField = ui.field("x", numeric = true).apply { setupPointField(this) }
        row.addView(xField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(ui.text("  y", 13f, MacroUi.DIM), lp(start = 8, end = 6))
        yField = ui.field("y", numeric = true).apply { setupPointField(this) }
        row.addView(yField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        root.addView(ui.label("Delay"))
        delayField = ui.field("ms", numeric = true).apply {
            setOnClickListener { enableInput(this) }
            setOnFocusChangeListener { v, has -> onFieldFocus(v, has) }
            setOnEditorActionListener { v, _, _ ->
                val ms = (v as EditText).text.toString().trim().toLongOrNull()
                if (ms != null) {
                    appendLine("${ms}ms")
                    v.setText("")
                }
                v.clearFocus(); ui.hideKeyboard(v); true
            }
        }
        root.addView(delayField)

        refreshTab2Items()
        return scroll(root)
    }

    private fun setupPointField(f: EditText) {
        f.setOnClickListener {
            if (host.pickerVisible()) {
                // Chạm lần 2 vào ô khi đang ghim -> bỏ ghim, cho nhập tay
                host.stopPicker()
                enableInput(f)
            } else {
                val (cx, cy) = host.screenCenter()
                val sx = xField.text.toString().toIntOrNull() ?: cx
                val sy = yField.text.toString().toIntOrNull() ?: cy
                host.startPicker(sx, sy) { px, py ->
                    xField.setText(px.toString())
                    yField.setText(py.toString())
                    enableInput(xField) // hiện bàn phím để bấm Enter ghi điểm vào ô
                }
            }
        }
        f.setOnFocusChangeListener { v, has -> onFieldFocus(v, has) }
        f.setOnEditorActionListener { v, _, _ ->
            commitPoint()
            v.clearFocus(); ui.hideKeyboard(v); true
        }
    }

    /** Ghi cặp x, y đang có vào cuối ô điểm chạm rồi xoá hai ô nhập. */
    private fun commitPoint() {
        val x = xField.text.toString().trim().toIntOrNull()
        val y = yField.text.toString().trim().toIntOrNull()
        if (x == null || y == null) {
            host.toast("Nhập đủ x và y")
            return
        }
        appendLine("$x, $y")
        xField.setText("")
        yField.setText("")
    }

    private fun appendLine(line: String) {
        val main = tab2Main ?: run {
            host.toast("Chưa có nút main để ghi điểm")
            return
        }
        val current = scriptArea.text.toString().trimEnd()
        val next = if (current.isEmpty()) line else "$current\n$line"
        main.script = next
        syncingScript = true
        scriptArea.setText(next)
        scriptArea.setSelection(next.length)
        syncingScript = false
        host.commitMains()
    }

    /** Cập nhật dropdown chọn nút (gọi khi danh sách nút đổi). */
    fun refreshTab2Items() {
        val mains = host.mains()
        if (tab2Main !in mains) tab2Main = mains.firstOrNull()
        val selectedIdx = mains.indexOf(tab2Main)
        mainDrop.placeholder = "Chưa có nút"
        mainDrop.setItems(mains.map { "${it.name} (${it.id})" }, selectedIdx) { i ->
            tab2Main = mains[i]
            syncingScript = true
            scriptArea.setText(mains[i].script)
            syncingScript = false
        }
        if (::scriptArea.isInitialized) {
            syncingScript = true
            scriptArea.setText(tab2Main?.script.orEmpty())
            syncingScript = false
        }
    }

    // ---- tab 3: Cài cấu hình -------------------------------------------------

    private var selectedConfig: String? = null
    private val configDrop = ui.Dropdown()

    fun buildTab3(): View {
        val root = ui.vbox()
        root.addView(ui.section("Tạo cấu hình"))
        root.addView(ui.text(
            "Đặt tên rồi bấm Tạo: cấu hình lưu toàn bộ nút main hiện có.",
            12f, MacroUi.DIM,
        ))
        val nameField = ui.field("Tên cấu hình").apply {
            setOnClickListener { enableInput(this) }
            setOnFocusChangeListener { v, has -> onFieldFocus(v, has) }
        }
        val createRow = ui.hbox()
        createRow.addView(nameField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        createRow.addView(ui.button("Tạo") {
            val name = nameField.text.toString().trim()
            if (name.isEmpty()) {
                host.toast("Nhập tên cấu hình trước")
                return@button
            }
            host.saveConfig(name, host.mains().map { it.copy() })
            selectedConfig = name
            nameField.setText("")
            nameField.clearFocus()
            ui.hideKeyboard(nameField)
            refreshTab3()
            host.toast("Đã tạo cấu hình “$name”")
        }, LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        root.addView(createRow, lp(top = 6))

        root.addView(ui.section("Chọn cấu hình"))
        root.addView(configDrop)

        val actions = ui.hbox()
        actions.addView(ui.button("Load") {
            val name = selectedConfig ?: return@button noConfig()
            val loaded = host.loadConfig(name) ?: return@button
            host.replaceMains(loaded)
            host.setEditing(null)
            refreshTab1()
            refreshTab2Items()
            host.toast("Đã load “$name”")
        }, btnLp())
        actions.addView(ui.button("Cập nhật") {
            val name = selectedConfig ?: return@button noConfig()
            host.saveConfig(name, host.mains().map { it.copy() })
            host.toast("Đã cập nhật “$name”")
        }, btnLp(start = 8))
        actions.addView(ui.button("Xóa") {
            val name = selectedConfig ?: return@button noConfig()
            host.deleteConfig(name)
            selectedConfig = null
            refreshTab3()
            host.toast("Đã xóa “$name”")
        }, btnLp(start = 8))
        root.addView(actions, lp(top = 10))

        refreshTab3()
        return scroll(root)
    }

    fun refreshTab3() {
        val names = host.configNames()
        if (selectedConfig !in names) selectedConfig = names.firstOrNull()
        configDrop.placeholder = "Chưa có cấu hình"
        configDrop.setItems(names, names.indexOf(selectedConfig)) { i -> selectedConfig = names[i] }
    }

    private fun noConfig() = host.toast("Chưa chọn cấu hình")

    // ---- tiện ích chung ---------------------------------------------------------

    private fun scroll(child: View) = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false
        addView(child, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun lp(top: Int = 0, start: Int = 0, end: Int = 0) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top); marginStart = dp(start); marginEnd = dp(end)
        }

    private fun btnLp(start: Int = 0) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(start) }

    /** Cho phép ô nhập nhận tiêu điểm (để mở bàn phím). Chỉ gọi khi người dùng chạm lần 2 hoặc chọn ghim xong. */
    private fun enableInput(f: EditText) {
        f.isFocusable = true
        f.isFocusableInTouchMode = true
        f.requestFocus()
        host.setInputFocus(true)
        ui.showKeyboard(f)
    }

    /** Ô mất tiêu điểm thì trả lại trạng thái không nhận bàn phím (menu không giữ focus, app bên dưới vẫn dùng được). */
    private fun onFieldFocus(v: View, hasFocus: Boolean) {
        if (hasFocus) return
        (v as? EditText)?.let { it.isFocusable = false; it.isFocusableInTouchMode = false }
        host.setInputFocus(false)
    }

    private fun simpleWatcher(onText: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = onText(s?.toString().orEmpty())
    }
}
