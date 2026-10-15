package com.zyntap.app.overlay

import android.content.Context
import android.graphics.Typeface
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
    val header = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    header.addView(TextView(ctx).apply {
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
    header.addView(sub)
    page.addView(
        header,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginEnd = (128 * d).toInt(); bottomMargin = (10 * d).toInt() },
    )
    return sub
}
