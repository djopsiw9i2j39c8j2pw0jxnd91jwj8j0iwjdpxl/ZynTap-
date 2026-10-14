package com.zyntap.app.overlay

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zyntap.app.R

/**
 * Hệ thống dropdown CHUNG cho toàn bộ menu nổi.
 *
 * Một [DropdownLayer] phủ lên card menu (nằm trên nội dung). Bất kỳ ô chọn nào
 * ([field]) khi ấn vào sẽ gọi [show]: một bảng nổi bung ra (hiệu ứng đóng/mở) ngay
 * dưới ô — hoặc phía trên nếu dưới hết chỗ — có thể vuốt lên xuống để xem hết các lựa chọn.
 * Ấn ra ngoài bảng = đóng, không đổi gì. Chọn xong bảng tự thu lại.
 */
class DropdownLayer(context: Context) : FrameLayout(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private fun dpf(v: Float) = v * d

    private var popup: View? = null
    private var closing = false

    val isOpen: Boolean get() = popup != null

    init {
        visibility = View.GONE
        // Ấn vào vùng trống = đóng bảng
        setOnClickListener { dismiss() }
    }

    fun show(anchor: View, items: List<String>, selected: Int, onPick: (Int) -> Unit) {
        if (items.isEmpty()) return
        dismissNow()
        closing = false

        val itemH = dp(ITEM_H_DP)
        val pad = dp(4)
        val full = items.size * itemH + pad * 2
        val maxByCount = (ITEM_VISIBLE * itemH + itemH / 2 + pad * 2)   // lộ nửa hàng cuối để biết còn kéo được
        val gap = dp(6)
        val margin = dp(8)

        val a = IntArray(2)
        val l = IntArray(2)
        anchor.getLocationOnScreen(a)
        getLocationOnScreen(l)
        val ax = a[0] - l[0]
        val ay = a[1] - l[1]

        val spaceBelow = height - (ay + anchor.height) - gap - margin
        val spaceAbove = ay - gap - margin
        val wantH = minOf(full, maxByCount)
        val below = wantH <= spaceBelow || spaceBelow >= spaceAbove
        val room = if (below) spaceBelow else spaceAbove
        val popH = wantH.coerceAtMost(room.coerceAtLeast(itemH + pad * 2))
        val popW = maxOf(anchor.width, dp(150)).coerceAtMost(width - margin * 2)
        val left = ax.coerceIn(margin, (width - popW - margin).coerceAtLeast(margin))
        val top = if (below) ay + anchor.height + gap else ay - gap - popH

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad, 0, pad)
        }
        items.forEachIndexed { i, label ->
            list.addView(
                buildItem(label, i == selected) { pick(i, onPick) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, itemH),
            )
        }
        val scroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isFillViewport = false
            addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            background = GradientDrawable().apply {
                setColor(0xFF121C30.toInt())
                setStroke(dp(1), 0xFF2A3A5C.toInt())
                cornerRadius = dpf(14f)
            }
            clipToOutline = true
            elevation = dpf(8f)
        }
        // Chọn sẵn: cuộn để mục đang chọn nằm trong tầm nhìn
        scroll.post {
            val target = (selected * itemH + pad - popH / 2 + itemH / 2).coerceAtLeast(0)
            scroll.scrollTo(0, target)
        }

        addView(scroll, LayoutParams(popW, popH, Gravity.TOP or Gravity.START).apply {
            leftMargin = left
            topMargin = top
        })
        popup = scroll
        visibility = View.VISIBLE
        isClickable = true

        // Bung ra từ mép ô chọn
        scroll.pivotX = popW / 2f
        scroll.pivotY = if (below) 0f else popH.toFloat()
        scroll.alpha = 0f
        scroll.scaleX = 0.9f
        scroll.scaleY = 0.55f
        scroll.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190L)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    private fun pick(i: Int, onPick: (Int) -> Unit) {
        if (closing) return
        onPick(i)
        dismiss()
    }

    /** Đóng có hiệu ứng thu lại. */
    fun dismiss() {
        val p = popup ?: return
        if (closing) return
        closing = true
        p.animate().cancel()
        p.animate().alpha(0f).scaleX(0.92f).scaleY(0.6f).setDuration(130L)
            .withEndAction { if (popup === p) dismissNow() }.start()
    }

    /** Đóng ngay, không hiệu ứng. */
    fun dismissNow() {
        popup?.let { it.animate().cancel(); removeView(it) }
        popup = null
        closing = false
        visibility = View.GONE
        isClickable = false
    }

    private fun buildItem(label: String, selected: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(12), 0)
            background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                GradientDrawable().apply {
                    setColor(if (selected) 0x3319D3FF else 0x00000000)
                    cornerRadius = dpf(10f)
                },
                GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = dpf(10f) },
            )
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(TextView(context).apply {
            text = label
            textSize = 14.5f
            setTextColor(if (selected) 0xFF19D3FF.toInt() else 0xFFE8EEFA.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (selected) {
            row.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_check)
                setColorFilter(0xFF19D3FF.toInt())
            }, LinearLayout.LayoutParams(dp(18), dp(18)))
        }
        return row
    }

    private companion object {
        const val ITEM_H_DP = 44
        const val ITEM_VISIBLE = 4
    }
}

/**
 * Ô chọn dùng chung: hiện giá trị hiện tại + mũi tên. Ấn vào mở dropdown nổi của [layer].
 * Dùng cho mọi chỗ cần chọn 1 trong nhiều (chế độ nút, và các tab sau này).
 */
class DropdownField(
    context: Context,
    private val layer: DropdownLayer,
    private val items: List<String>,
    selected: Int,
    private val onSelect: (Int) -> Unit,
) : LinearLayout(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    var current: Int = selected
        private set

    private val valueText = TextView(context).apply {
        textSize = 14.5f
        setTextColor(0xFFE8EEFA.toInt())
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val arrow = ImageView(context).apply {
        setImageResource(R.drawable.ic_chevron_down)
        setColorFilter(0xFF8D9DBA.toInt())
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(12), 0)
        background = RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            GradientDrawable().apply {
                setColor(0xFF142036.toInt())
                setStroke(dp(1), 0xFF1E2B44.toInt())
                cornerRadius = dp(12).toFloat()
            },
            GradientDrawable().apply { setColor(0xFFFFFFFF.toInt()); cornerRadius = dp(12).toFloat() },
        )
        isClickable = true
        addView(valueText, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(arrow, LayoutParams(dp(18), dp(18)))
        valueText.text = items.getOrElse(selected) { "" }
        setOnClickListener { open() }
    }

    private fun open() {
        arrow.animate().cancel()
        arrow.animate().rotation(180f).setDuration(180L).start()
        layer.show(this, items, current) { i ->
            current = i
            valueText.text = items[i]
            onSelect(i)
        }
        // Mũi tên quay về khi bảng đóng
        postDelayed(object : Runnable {
            override fun run() {
                if (!isAttachedToWindow) return
                if (layer.isOpen) postDelayed(this, 120L)
                else arrow.animate().rotation(0f).setDuration(160L).start()
            }
        }, 200L)
    }
}
