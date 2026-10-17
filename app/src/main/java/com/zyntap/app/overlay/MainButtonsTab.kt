package com.zyntap.app.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.zyntap.app.R

/** Những thứ Tab cần từ cửa sổ menu (do OverlayService cung cấp). */
interface PanelHost {
    /** Lớp dropdown nổi dùng chung. */
    val dropdown: DropdownLayer

    /** Hộp nhập chữ nổi dùng chung. */
    val prompt: TextPromptLayer

    /**
     * Bật/tắt "trạng thái chỉnh sửa": khi bật, ấn ra ngoài menu hoặc vuốt menu sang trái
     * sẽ KHÔNG đóng menu (để kéo thả nút tròn thoải mái, không đóng nhầm).
     */
    fun setEditing(on: Boolean)
}

/** Khung co giãn theo [fraction] (0..1): nội dung giữ nguyên kích thước thật, chỉ bị cắt dần -> hiệu ứng trượt ra/vào. */
private class RevealLayout(context: Context) : FrameLayout(context) {
    var fraction = 1f
        set(v) {
            field = v.coerceIn(0f, 1f)
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val child = getChildAt(0)
        if (child == null) {
            setMeasuredDimension(w, 0)
            return
        }
        child.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        setMeasuredDimension(w, (child.measuredHeight * fraction).toInt())
    }
}

/**
 * Tab 1 — "Tạo nút chính".
 *
 *  - Danh sách các nút main đã tạo, mỗi nút là 1 thanh. Ấn thanh -> bảng chỉnh trượt ra phía dưới thanh
 *    (tên, chế độ, độ mờ, kích thước) và vào "trạng thái chỉnh sửa": nút tròn trên màn hình kéo thả được,
 *    menu không bị đóng nhầm. Ấn thanh lần nữa -> thoát chỉnh sửa.
 *  - Nút "Tạo nút mới" ở dưới cùng mở 1 bảng cài đặt riêng (trượt vào từ bên phải).
 */
class MainButtonsTab(
    private val ctx: Context,
    private val repo: MainButtonRepo,
    private val overlay: MainButtonOverlay,
    private val host: PanelHost,
) {
    private val d = UiScale.density(ctx.resources)
    private fun dp(v: Int) = (v * d).toInt()
    private fun round(color: Int, r: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }
    private fun ripple(content: android.graphics.drawable.Drawable, r: Int) = RippleDrawable(
        ColorStateList.valueOf(0x33FFFFFF), content, round(0xFFFFFFFF.toInt(), r),
    )

    private lateinit var root: FrameLayout
    private lateinit var scroll: ScrollView
    private lateinit var listBox: LinearLayout
    private lateinit var emptyView: TextView
    private lateinit var countText: TextView
    private var createPage: View? = null
    private var createClosing = false

    private val bars = LinkedHashMap<Int, Bar>()
    private var expandedId: Int? = null

    // ---- dựng giao diện ----------------------------------------------------

    fun build(): View {
        root = FrameLayout(ctx)

        val page = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
        }

        // Tiêu đề (khung chung với 2 tab kia, chừa chỗ cho 3 nút mắt / - / X của menu ở góc phải)
        countText = addTabHeader(ctx, page, "Nút chính", "")

        // Danh sách thanh nút
        listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        emptyView = TextView(ctx).apply {
            text = "Chưa có nút nào\nẤn “Tạo nút mới” ở dưới để bắt đầu"
            setTextColor(0xFF8D9DBA.toInt())
            scaledTextSize(13.5f)
            gravity = Gravity.CENTER
            setPadding(0, dp(40), 0, dp(40))
        }
        listBox.addView(emptyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(listBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        page.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Nút tạo nút mới (cố định ở dưới)
        page.addView(
            gradientButton("Tạo nút mới", R.drawable.ic_plus) { openCreate() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(10) },
        )

        root.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        repo.buttons.forEach { addBar(it, animate = false) }
        refreshHeader()
        return root
    }

    private fun refreshHeader() {
        val n = repo.buttons.size
        countText.text = if (n == 0) "Chưa có nút" else "$n nút  •  ấn vào thanh để chỉnh"
        emptyView.visibility = if (n == 0) View.VISIBLE else View.GONE
    }

    private fun gradientButton(label: String, icon: Int, onClick: () -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = ripple(
                GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0xFF1F5CF0.toInt(), 0xFF12A0EC.toInt()),
                ).apply { cornerRadius = dp(14).toFloat() },
                14,
            )
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(ImageView(ctx).apply {
            setImageResource(icon)
            setColorFilter(0xFFFFFFFF.toInt())
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        row.addView(TextView(ctx).apply {
            text = label
            setTextColor(0xFFFFFFFF.toInt())
            scaledTextSize(15f)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        return row
    }

    // ---- thanh nút ---------------------------------------------------------

    private fun subtitle(b: MainButton) =
        "${b.code}  •  ${if (b.assistEnabled) MainButton.ASSIST_LABELS[b.assistMode] else MainButton.modeLabel(b.mode)}  •  ${b.sizeDp}dp  •  ${b.alphaPct}%"

    private inner class Bar(val b: MainButton) {
        val slot = RevealLayout(ctx)
        private val cardBg = round(0xFF142036.toInt(), 16, 0xFF1E2B44.toInt())
        private val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg
        }
        private val orb = TextView(ctx).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            scaledTextSize(14f)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF2A6BFF.toInt(), 0xFF19D3FF.toInt()),
            ).apply { shape = GradientDrawable.OVAL }
        }
        private val title = TextView(ctx).apply {
            setTextColor(0xFFE8EEFA.toInt())
            scaledTextSize(15f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        private val sub = TextView(ctx).apply {
            setTextColor(0xFF8D9DBA.toInt())
            scaledTextSize(12f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        private val chevron = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_chevron_down)
            setColorFilter(0xFF8D9DBA.toInt())
        }
        private val reveal = RevealLayout(ctx).apply { fraction = 0f; alpha = 0f }
        private var anim: ValueAnimator? = null
        private var armedDelete = false

        init {
            val head = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), 0, dp(12), 0)
                background = ripple(round(0x00000000, 16), 16)
                isClickable = true
                setOnClickListener { toggle(b.id) }
            }
            head.addView(orb, LinearLayout.LayoutParams(dp(34), dp(34)))
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(title)
            texts.addView(sub)
            head.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12); marginEnd = dp(8)
            })
            head.addView(chevron, LinearLayout.LayoutParams(dp(20), dp(20)))
            container.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)))

            reveal.addView(buildBody(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            container.addView(reveal, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            // wrapper chừa khoảng cách giữa các thanh
            val wrapper = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(8))
            }
            wrapper.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            slot.addView(wrapper, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            refresh()
        }

        fun refresh() {
            orb.text = b.id.toString()
            title.text = b.name
            sub.text = subtitle(b)
        }

        private fun buildBody(): View {
            val body = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(2), dp(14), dp(14))
            }
            body.addView(View(ctx).apply { setBackgroundColor(0xFF1E2B44.toInt()) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { bottomMargin = dp(10) })
            body.addView(TextView(ctx).apply {
                text = "Đang chỉnh sửa — kéo nút tròn trên màn hình để đặt vị trí. Ấn lại thanh này để thoát."
                setTextColor(0xFF19D3FF.toInt())
                scaledTextSize(12f)
            })
            body.addView(
                buildForm(b, onLive = { overlay.onChanged(b); refresh() }, onCommit = { repo.save() }),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) },
            )

            val del = TextView(ctx).apply {
                text = "Xoá nút"
                setTextColor(0xFFEF5B5B.toInt())
                scaledTextSize(14f)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = ripple(round(0x22EF5B5B, 12, 0x55EF5B5B), 12)
                isClickable = true
            }
            del.setOnClickListener {
                if (!armedDelete) {
                    armedDelete = true
                    del.text = "Ấn lần nữa để xoá"
                    del.postDelayed({ armedDelete = false; del.text = "Xoá nút" }, 2500L)
                } else {
                    armedDelete = false
                    deleteBar(b)
                }
            }
            body.addView(del, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(42)).apply { topMargin = dp(14) })
            return body
        }

        fun setExpanded(on: Boolean, animate: Boolean) {
            anim?.cancel()
            val from = reveal.fraction
            val to = if (on) 1f else 0f
            cardBg.setStroke(dp(1), if (on) 0xAA19D3FF.toInt() else 0xFF1E2B44.toInt())
            fun setFrac(f: Float) {
                reveal.fraction = f
                reveal.alpha = f
                chevron.rotation = 180f * f
            }
            if (!animate) { setFrac(to); return }
            anim = ValueAnimator.ofFloat(from, to).apply {
                duration = 340L
                interpolator = EASE
                addUpdateListener { setFrac(it.animatedValue as Float) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (on && expandedId == b.id) {
                            // Mở xong -> cuộn để thanh nằm gọn trên đầu danh sách
                            scroll.post { scroll.smoothScrollTo(0, (slot.top - dp(2)).coerceAtLeast(0)) }
                        }
                    }
                })
                start()
            }
        }

        fun animateIn() {
            slot.fraction = 0f
            slot.alpha = 0f
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 320L
                interpolator = EASE
                addUpdateListener {
                    val f = it.animatedValue as Float
                    slot.fraction = f
                    slot.alpha = f
                }
                start()
            }
        }

        fun animateOut(onEnd: () -> Unit) {
            anim?.cancel()
            ValueAnimator.ofFloat(slot.fraction, 0f).apply {
                duration = 260L
                interpolator = EASE
                addUpdateListener {
                    val f = it.animatedValue as Float
                    slot.fraction = f
                    slot.alpha = f
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = onEnd()
                })
                start()
            }
        }
    }

    private fun addBar(b: MainButton, animate: Boolean) {
        val bar = Bar(b)
        bars[b.id] = bar
        // giữ thứ tự theo mã nút
        val idx = repo.buttons.sortedBy { it.id }.indexOfFirst { it.id == b.id }.coerceAtLeast(0)
        listBox.addView(
            bar.slot,
            1 + idx.coerceAtMost(listBox.childCount - 1),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        if (animate) {
            bar.animateIn()
            scroll.post { scroll.smoothScrollTo(0, listBox.height) }
        }
    }

    private fun deleteBar(b: MainButton) {
        val bar = bars.remove(b.id) ?: return
        if (expandedId == b.id) {
            expandedId = null
            overlay.setEditing(null)
            host.setEditing(false)
        }
        repo.remove(b.id)
        overlay.onRemoved(b.id)
        bar.animateOut {
            listBox.removeView(bar.slot)
            refreshHeader()
        }
    }

    // ---- vào / ra trạng thái chỉnh sửa -------------------------------------

    private fun toggle(id: Int) {
        host.dropdown.dismissNow()
        if (expandedId == id) {
            exitEdit()
            return
        }
        expandedId?.let { bars[it]?.setExpanded(false, true) }
        expandedId = id
        bars[id]?.setExpanded(true, true)
        overlay.setEditing(id)
        host.setEditing(true)
    }

    /** Thoát chỉnh sửa (ấn lại thanh, đổi tab, hoặc sắp tạo nút mới). */
    fun exitEdit() {
        expandedId?.let { bars[it]?.setExpanded(false, true) }
        expandedId = null
        overlay.setEditing(null)
        host.setEditing(false)
    }

    // ---- form dùng chung (bảng chỉnh trong thanh + bảng tạo nút) ------------

    /**
     * Tên / Chế độ (dropdown) / Độ mờ / Kích thước. Sửa thẳng vào [b].
     * [onLive] gọi mỗi khi giá trị đổi (cập nhật tức thì), [onCommit] gọi khi chốt (để lưu).
     */
    private fun buildForm(b: MainButton, onLive: () -> Unit, onCommit: () -> Unit): LinearLayout {
        val form = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        // Tên
        form.addView(fieldLabel("Tên nút"))
        val nameRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(12), 0)
            background = ripple(round(0xFF142036.toInt(), 12, 0xFF1E2B44.toInt()), 12)
            isClickable = true
        }
        val nameText = TextView(ctx).apply {
            text = b.name
            setTextColor(0xFFE8EEFA.toInt())
            scaledTextSize(14.5f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        nameRow.addView(nameText, LinearLayout.LayoutParams(0, wrap, 1f))
        nameRow.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_pencil)
            setColorFilter(0xFF8D9DBA.toInt())
        }, LinearLayout.LayoutParams(dp(16), dp(16)))
        nameRow.setOnClickListener {
            host.prompt.show("Tên nút ${b.code}", b.name, MainButton.MAX_NAME) { v ->
                b.name = v
                nameText.text = v
                onLive()
                onCommit()
            }
        }
        form.addView(nameRow, LinearLayout.LayoutParams(match, dp(46)))

        // Chế độ (dropdown nổi dùng chung)
        form.addView(fieldLabel("Chế độ"))
        form.addView(
            DropdownField(ctx, host.dropdown, MainButton.MODE_LABELS, b.mode) { i ->
                b.mode = i
                onLive()
                onCommit()
            },
            LinearLayout.LayoutParams(match, dp(46)),
        )

        // Chạm xuyên
        form.addView(passThroughRow(b, onChange = { onLive(); onCommit() }))

        // Trợ vuốt tâm: toggle; các mục khác chỉ hiện khi bật
        val assistBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val pullBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val boostBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun syncAssistVisibility() {
            assistBox.visibility = if (b.assistEnabled) View.VISIBLE else View.GONE
            pullBox.visibility = if (b.assistMode != MainButton.ASSIST_BOOST) View.VISIBLE else View.GONE
            boostBox.visibility = if (b.assistMode == MainButton.ASSIST_BOOST) View.VISIBLE else View.GONE
        }
        form.addView(switchRow("Bật trợ vuốt tâm",
            "Giữ nút để tự vuốt, hoặc buff lực vuốt ngón thật.", b.assistEnabled) { on ->
            b.assistEnabled = on
            syncAssistVisibility()
            onLive(); onCommit()
        })
        assistBox.addView(fieldLabel("Chế độ trợ vuốt"))
        assistBox.addView(
            DropdownField(ctx, host.dropdown, MainButton.ASSIST_LABELS, b.assistMode) { i ->
                b.assistMode = i
                syncAssistVisibility()
                onLive(); onCommit()
            },
            LinearLayout.LayoutParams(match, dp(46)),
        )
        pullBox.addView(slider("Tốc độ kéo", MainButton.MIN_ASSIST_SPEED, MainButton.MAX_ASSIST_SPEED, b.assistSpeed, " dp/s",
            onValue = { b.assistSpeed = it; onLive() }, onStop = onCommit))
        pullBox.addView(slider("Quãng mỗi lần vuốt", MainButton.MIN_ASSIST_LENGTH, MainButton.MAX_ASSIST_LENGTH, b.assistLength, " dp",
            onValue = { b.assistLength = it; onLive() }, onStop = onCommit))
        pullBox.addView(slider("Delay mỗi lần kéo", MainButton.MIN_ASSIST_DELAY, MainButton.MAX_ASSIST_DELAY, b.assistDelay, " ms",
            onValue = { b.assistDelay = it; onLive() }, onStop = onCommit))
        boostBox.addView(slider("Lực buff (x100%)", MainButton.MIN_BOOST_GAIN, MainButton.MAX_BOOST_GAIN, b.boostGain, "%",
            onValue = { b.boostGain = it; onLive() }, onStop = onCommit))
        boostBox.addView(switchRow("Chỉ buff khi giữ nút",
            "Tắt: buff luôn bật cho mọi ngón mới chạm xuống.", b.boostHoldOnly) { on ->
            b.boostHoldOnly = on
            onLive(); onCommit()
        })
        assistBox.addView(pullBox, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
        assistBox.addView(boostBox, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
        form.addView(assistBox, LinearLayout.LayoutParams(match, ViewGroup.LayoutParams.WRAP_CONTENT))
        syncAssistVisibility()

        // Độ mờ
        form.addView(slider("Độ mờ", MainButton.MIN_ALPHA, MainButton.MAX_ALPHA, b.alphaPct, "%",
            onValue = { b.alphaPct = it; onLive() }, onStop = onCommit))
        // Kích thước
        form.addView(slider("Kích thước", MainButton.MIN_SIZE, MainButton.MAX_SIZE, b.sizeDp, " dp",
            onValue = { b.sizeDp = it; onLive() }, onStop = onCommit))
        return form
    }

    /** Công tắc "Chạm xuyên nút main": bật thì chạm vào nút vẫn lọt xuống app/màn hình bên dưới. */
    private fun passThroughRow(b: MainButton, onChange: () -> Unit): View =
        switchRow("Chạm xuyên nút", "Giúp chạm các tính năng bên dưới nút nổi cùng lúc.", b.passThrough) { on ->
            b.passThrough = on
            onChange()
        }

    /** Một dòng có tiêu đề + mô tả + công tắc. */
    private fun switchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(8))
            background = round(0xFF142036.toInt(), 12, 0xFF1E2B44.toInt())
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(ctx).apply {
            text = title
            setTextColor(0xFFE8EEFA.toInt())
            scaledTextSize(14.5f)
        })
        texts.addView(TextView(ctx).apply {
            text = desc
            setTextColor(0xFF8D9DBA.toInt())
            scaledTextSize(11.5f)
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        val sw = Switch(ctx).apply {
            isChecked = checked
            thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0xFF19D3FF.toInt(), 0xFF2A3A5C.toInt()),
            )
            setOnCheckedChangeListener { _, on -> onChange(on) }
        }
        row.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.setOnClickListener { sw.toggle() }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setPadding(0, dp(12), 0, 0)
        }
    }

    private fun fieldLabel(text: String) = TextView(ctx).apply {
        this.text = text
        setTextColor(0xFF8D9DBA.toInt())
        scaledTextSize(12f)
        setPadding(dp(2), dp(12), 0, dp(5))
    }

    private fun slider(
        label: String, min: Int, max: Int, value: Int, unit: String,
        onValue: (Int) -> Unit, onStop: () -> Unit,
    ): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(12), dp(2), dp(2))
        }
        head.addView(TextView(ctx).apply {
            text = label
            setTextColor(0xFF8D9DBA.toInt())
            scaledTextSize(12f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueText = TextView(ctx).apply {
            text = "$value$unit"
            setTextColor(0xFFE8EEFA.toInt())
            scaledTextSize(12.5f)
            setTypeface(typeface, Typeface.BOLD)
        }
        head.addView(valueText)
        box.addView(head)

        val bar = SeekBar(ctx).apply {
            this.min = min
            this.max = max
            progress = value
            progressTintList = ColorStateList.valueOf(0xFF19D3FF.toInt())
            progressBackgroundTintList = ColorStateList.valueOf(0xFF2A3A5C.toInt())
            thumbTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            // Kéo thanh trượt sang trái không được làm menu thu lại
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    valueText.text = "$p$unit"
                    if (fromUser) onValue(p)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) = onStop()
            })
        }
        box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
        return box
    }

    // ---- bảng "Tạo nút mới" (riêng) -----------------------------------------

    private fun openCreate() {
        if (createPage != null) return
        if (expandedId != null) exitEdit()
        host.dropdown.dismissNow()

        val id = repo.nextId()
        val (px, py) = repo.defaultPosition(id)
        val draft = MainButton(id = id, name = "Nút $id", x = px, y = py)

        val page = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D1524.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(12))
            isClickable = true // nuốt chạm, không xuyên xuống danh sách
        }

        // Thanh trên: quay lại + tiêu đề (chừa chỗ cho mắt / - / X)
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_chevron_left)
            setColorFilter(0xFFE8EEFA.toInt())
            val p = dp(8)
            setPadding(p, p, p, p)
            background = ripple(round(0x14FFFFFF, 12), 12)
            isClickable = true
            setOnClickListener { closeCreate() }
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        val titles = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(TextView(ctx).apply {
            text = "Tạo nút mới"
            setTextColor(0xFFE8EEFA.toInt())
            scaledTextSize(17f)
            setTypeface(typeface, Typeface.BOLD)
        })
        titles.addView(TextView(ctx).apply {
            text = "Mã nút: ${draft.code}"
            setTextColor(0xFF8D9DBA.toInt())
            scaledTextSize(12f)
        })
        top.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
        page.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = dp(128); bottomMargin = dp(6) })

        // Xem trước nút + form
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val previewBox = FrameLayout(ctx).apply {
            background = round(0xFF0A1220.toInt(), 14, 0xFF1E2B44.toInt())
        }
        val preview = MainButtonView(ctx).apply {
            label = draft.name
            fillPct = draft.alphaPct
            minPct = MainButton.MENU_MIN_ALPHA // xem trước luôn thấy được, kể cả khi chọn 0%
            ringPad = 0f
        }
        fun syncPreview() {
            preview.label = draft.name
            preview.fillPct = draft.alphaPct
            val lp = preview.layoutParams
            val s = dp(draft.sizeDp)
            if (lp.width != s) { lp.width = s; lp.height = s; preview.layoutParams = lp }
        }
        previewBox.addView(preview, FrameLayout.LayoutParams(dp(draft.sizeDp), dp(draft.sizeDp), Gravity.CENTER))
        content.addView(previewBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(128)).apply { topMargin = dp(4) })
        content.addView(
            buildForm(draft, onLive = { syncPreview() }, onCommit = {}),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val sv = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        page.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        page.addView(
            gradientButton("Tạo nút ${draft.code}", R.drawable.ic_check) {
                if (createClosing) return@gradientButton
                repo.add(draft)
                overlay.onAdded(draft)
                addBar(draft, animate = true)
                refreshHeader()
                closeCreate()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(10) },
        )

        createPage = page
        createClosing = false
        root.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        page.alpha = 0f
        page.post {
            page.translationX = page.width * 0.35f
            page.animate().translationX(0f).alpha(1f).setDuration(300L)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
    }

    private fun closeCreate() {
        val page = createPage ?: return
        if (createClosing) return
        createClosing = true
        host.dropdown.dismissNow()
        host.prompt.close()
        page.animate().cancel()
        page.animate().translationX(page.width * 0.35f).alpha(0f).setDuration(230L)
            .setInterpolator(DecelerateInterpolator(1.4f))
            .withEndAction {
                root.removeView(page)
                if (createPage === page) createPage = null
                createClosing = false
            }.start()
    }

    private companion object {
        val EASE = PathInterpolator(0.4f, 0f, 0.2f, 1f)
    }
}
