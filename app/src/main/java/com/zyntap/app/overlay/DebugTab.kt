package com.zyntap.app.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.zyntap.app.data.DebugLog

/**
 * Tab 5 — Debug log: ô in log các lỗi của app (bơm/click, ADB, helper cảm ứng, tâm ảo, crash...).
 * Bấm "Sao chép" rồi dán cho mình để fix: báo cáo đã kèm sẵn thông tin máy + trạng thái ADB.
 *
 * @param info trả về vài dòng trạng thái hiện tại (ADB, helper, quyền...) — dùng cho nút "Kiểm tra" và báo cáo.
 */
class DebugTab(
    private val ctx: Context,
    private val info: () -> String,
) {
    private val d = UiScale.density(ctx.resources)
    private fun dp(n: Int) = (n * d).toInt()
    private val fg = 0xFFE8EEFA.toInt()
    private val muted = 0xFF8D9DBA.toInt()
    private val cyan = 0xFF19D3FF.toInt()
    private val warn = 0xFFF5B544.toInt()
    private val bad = 0xFFFF7A7A.toInt()

    private val handler = Handler(Looper.getMainLooper())
    private var errorsOnly = false
    private var clearArmed = false
    private val disarm = Runnable { setClearArmed(false) }

    private lateinit var scroll: ScrollView
    private lateinit var logText: TextView
    private lateinit var countText: TextView
    private lateinit var filterBtn: TextView
    private lateinit var clearBtn: TextView
    private var root: View? = null
    private var listener: (() -> Unit)? = null

    private fun round(color: Int, stroke: Int = 0xFF24344E.toInt(), r: Int = 12) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(r).toFloat(); setStroke(dp(1), stroke)
    }

    private fun button(text: String, bg: Int, fgColor: Int, onClick: () -> Unit) = TextView(ctx).apply {
        this.text = text; scaledTextSize(12.5f); setTextColor(fgColor)
        setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        maxLines = 1
        setPadding(dp(8), 0, dp(8), 0)
        background = RippleDrawable(ColorStateList.valueOf(0x3319D3FF), round(bg), round(-1))
        isClickable = true
        setOnClickListener { onClick() }
    }

    fun build(): View {
        val page = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        addTabHeader(ctx, page, "Debug log", "Lỗi của app — Sao chép rồi gửi cho mình để fix")

        // ---- hàng nút ----
        val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        filterBtn = button("Chỉ lỗi", 0xFF142036.toInt(), fg) {
            errorsOnly = !errorsOnly
            paintFilter()
            refresh(forceBottom = true)
        }
        val diagBtn = button("Kiểm tra", 0xFF142036.toInt(), fg) { diagnose() }
        val copyBtn = button("Sao chép", 0xFF1E3A66.toInt(), 0xFFFFFFFF.toInt()) { copyAll() }
        clearBtn = button("Xóa", 0xFF142036.toInt(), bad) {
            if (clearArmed) { DebugLog.clear(); setClearArmed(false); refresh(forceBottom = true) }
            else setClearArmed(true)
        }
        listOf(filterBtn, diagBtn, copyBtn, clearBtn).forEachIndexed { i, b ->
            bar.addView(b, LinearLayout.LayoutParams(0, dp(36), 1f).apply { if (i > 0) marginStart = dp(6) })
        }
        page.addView(bar, LinearLayout.LayoutParams(-1, -2))

        countText = TextView(ctx).apply { setTextColor(muted); scaledTextSize(11f) }
        page.addView(countText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(4) })

        // ---- ô in log (chữ kiểu code, tự xuống dòng, cuộn dọc) ----
        logText = TextView(ctx).apply {
            typeface = Typeface.MONOSPACE
            scaledTextSize(10.5f)
            setTextColor(muted)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setLineSpacing(0f, 1.1f)
        }
        scroll = ScrollView(ctx).apply {
            background = round(0xFF0A1220.toInt())
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = true
            addView(logText, ViewGroup.LayoutParams(-1, -2))
            // Vuốt dọc trong ô log không được làm menu thu lại
            setOnTouchListener { v, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        paintFilter()
        refresh(forceBottom = true)
        root = page
        // Đăng ký nghe log mới; gỡ ở dispose() khi menu bị gỡ
        val l: () -> Unit = { if (root?.isShown == true) refresh(forceBottom = false) }
        listener = l
        DebugLog.addListener(l)
        return page
    }

    /** Mở tab: vẽ lại ngay và cuộn xuống dòng mới nhất. */
    fun enter() { if (root != null) refresh(forceBottom = true) }

    /** Rời tab. */
    fun exit() { setClearArmed(false) }

    /** Menu bị gỡ: bỏ nghe log để không giữ view cũ. */
    fun dispose() {
        listener?.let { DebugLog.removeListener(it) }
        listener = null
        handler.removeCallbacks(disarm)
        root = null
    }

    private fun setClearArmed(on: Boolean) {
        clearArmed = on
        handler.removeCallbacks(disarm)
        if (!::clearBtn.isInitialized) return
        clearBtn.text = if (on) "Chạm lại để xóa" else "Xóa"
        clearBtn.scaledTextSize(if (on) 11f else 12.5f)
        if (on) handler.postDelayed(disarm, 2500L)
    }

    private fun paintFilter() {
        filterBtn.setTextColor(if (errorsOnly) 0xFF04121C.toInt() else fg)
        filterBtn.background = RippleDrawable(
            ColorStateList.valueOf(0x3319D3FF),
            round(if (errorsOnly) cyan else 0xFF142036.toInt(), if (errorsOnly) cyan else 0xFF24344E.toInt()),
            round(-1),
        )
    }

    private fun refresh(forceBottom: Boolean) {
        if (!::logText.isInitialized) return
        val all = DebugLog.snapshot(false)
        val list = if (errorsOnly) all.filter { it.level != DebugLog.Level.INFO } else all
        val errs = all.count { it.level == DebugLog.Level.ERROR }
        val warns = all.count { it.level == DebugLog.Level.WARN }

        countText.text = "${all.size} dòng · $errs lỗi · $warns cảnh báo"
        countText.setTextColor(if (errs > 0) bad else muted)

        // Đang xem cuối log thì tự theo dòng mới; đang đọc ở giữa thì giữ nguyên chỗ đọc
        val child = scroll.getChildAt(0)
        val atBottom = child == null || scroll.scrollY + scroll.height >= child.height - dp(24)

        if (list.isEmpty()) {
            logText.text = if (errorsOnly) "Không có lỗi hay cảnh báo nào. Tốt!" else "Chưa có log."
            logText.setTextColor(muted)
        } else {
            val sb = SpannableStringBuilder()
            list.forEach { e ->
                val color = when (e.level) {
                    DebugLog.Level.ERROR -> bad
                    DebugLog.Level.WARN -> warn
                    DebugLog.Level.INFO -> muted
                }
                val start = sb.length
                sb.append(DebugLog.line(e)).append('\n')
                sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (e.level == DebugLog.Level.ERROR) {
                    // In đậm phần đầu "giờ + E/nhãn" cho dễ nhìn
                    val head = sb.indexOf(":", start + 8).let { if (it < 0) start else it }
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, head.coerceAtLeast(start), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            logText.setTextColor(muted)
            logText.text = sb
        }
        if (forceBottom || atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copyAll() {
        val text = DebugLog.report(runCatching { info() }.getOrDefault("(không đọc được trạng thái)"), errorsOnly)
        val ok = runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ZynTap debug log", text))
        }.isSuccess
        Toast.makeText(ctx, if (ok) "Đã sao chép log — dán cho mình nhé" else "Không sao chép được", Toast.LENGTH_SHORT).show()
    }

    /** Ghi một dòng trạng thái hiện tại vào log (ADB, helper bơm, quyền...). */
    private fun diagnose() {
        DebugLog.i("Kiểm tra", runCatching { info() }.getOrElse { "Lỗi khi đọc trạng thái: $it" }.lines().joinToString(" | "))
        refresh(forceBottom = true)
    }
}
