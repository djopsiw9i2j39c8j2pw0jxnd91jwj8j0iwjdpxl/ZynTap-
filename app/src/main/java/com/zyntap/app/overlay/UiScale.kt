package com.zyntap.app.overlay

import android.content.res.Resources
import android.widget.TextView
import kotlin.math.min

/**
 * Hệ số co giãn chung cho TOÀN BỘ giao diện nổi (menu, nút main, ghim chọn điểm, dropdown, hộp thoại...).
 *
 * Mọi chỗ đổi dp -> px và sp -> px của lớp phủ đều đi qua đây, nên đổi một chỗ là cả UI theo.
 * Chuẩn: cạnh NGẮN của màn hình = [REF_DP] dp thì hệ số = 1. Máy nhỏ/DPI cao (cạnh ngắn ít dp) thu nhỏ,
 * máy lớn/tablet phóng nhẹ, kẹp trong [[MIN], [MAX]] để không vỡ bố cục. Dùng cạnh ngắn nên xoay màn hình
 * không làm kích thước đổi qua lại. Đổi "Kích thước hiển thị"/DPI trong Cài đặt sẽ tính lại theo cấu hình mới.
 */
object UiScale {
    private const val REF_DP = 400f
    private const val MIN = 0.8f
    private const val MAX = 1.3f

    fun factor(r: Resources): Float {
        val m = r.displayMetrics
        val shortDp = min(m.widthPixels, m.heightPixels) / m.density
        return (shortDp / REF_DP).coerceIn(MIN, MAX)
    }

    /** Mật độ điểm ảnh đã nhân hệ số: `dp * density(r)` = px đã co giãn. */
    fun density(r: Resources): Float = r.displayMetrics.density * factor(r)

    /** Cỡ chữ (sp) đã co giãn; vẫn tôn trọng cỡ chữ hệ thống vì đặt qua `textSize` (đơn vị sp). */
    fun sp(r: Resources, v: Float): Float = v * factor(r)
}

/** Đặt cỡ chữ (sp) có nhân hệ số co giãn của [UiScale]. Thay cho `textSize = v`. */
fun TextView.scaledTextSize(sp: Float) {
    textSize = UiScale.sp(resources, sp)
}
