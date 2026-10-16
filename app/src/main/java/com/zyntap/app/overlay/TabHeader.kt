package com.zyntap.app.overlay

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Khung tiêu đề DÙNG CHUNG cho cả 4 tab của menu nổi (Tâm ảo / Nút chính / Điểm nhấn / Cấu hình):
 * dòng tên tab đậm + dòng phụ nhỏ bên dưới, chừa chỗ bên phải cho 3 nút mắt / - / X của menu.
 * Cùng một khuôn nên các tab có phần đầu giống hệt nhau và nội dung bắt đầu cùng một độ cao.
 *
 * @return TextView của dòng phụ (tab 1 dùng để cập nhật số nút).
 */
internal fun addTabHeader(ctx: Context, page: LinearLayout, title: String, subtitle: String): TextView {
    val d = UiScale.density(ctx.resources)
    // Khối tiêu đề cao cố định HEADER_H_DP: cùng độ cao với khối logo ở cột trái -> đường kẻ hai bên thẳng hàng
    val titleBox = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }
    titleBox.addView(TextView(ctx).apply {
        text = title
        setTextColor(0xFFE8EEFA.toInt())
        scaledTextSize(17f)
        setTypeface(typeface, Typeface.BOLD)
    })
    val sub = TextView(ctx).apply {
        text = subtitle
        setTextColor(0xFF8D9DBA.toInt())
        scaledTextSize(12f)
    }
    titleBox.addView(sub)
    val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    wrap.addView(
        titleBox,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (HEADER_H_DP * d).toInt())
            .apply { marginEnd = (128 * d).toInt() },
    )
    // Không còn đường kẻ dưới tiêu đề; bù 1dp vào lề dưới để nội dung giữ nguyên độ cao
    page.addView(
        wrap,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = (11 * d).toInt() },
    )
    return sub
}

/** Chiều cao (dp) khối tiêu đề: dùng chung cho khối logo bên cột trái và tiêu đề tab bên phải. */
internal const val HEADER_H_DP = 38

/** Đường kẻ gradient: xanh cyan đậm ở trái, mờ dần thành trong suốt về phải. */
internal fun accentDivider(ctx: Context): View = View(ctx).apply {
    background = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0xFF1E9BFF.toInt(), 0x551E9BFF, 0x001E9BFF),
    )
}
