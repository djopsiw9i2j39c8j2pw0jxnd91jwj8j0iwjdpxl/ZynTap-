package com.zyntap.app.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Quyền "Bỏ tối ưu pin": hệ thống không kill ZynTap nền khi đang chơi game nặng / bật tiết kiệm pin. */
object BatteryExemption {
    fun granted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Các intent thử lần lượt: hộp thoại "Cho phép chạy nền?", rồi danh sách tối ưu pin, rồi trang thông tin app.
     * KHÔNG dùng resolveActivity: Android 11+ che các activity của Cài đặt nếu thiếu <queries>, nên nó trả về null sai.
     */
    fun requestIntents(context: Context): List<Intent> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        return listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
        )
    }
}
