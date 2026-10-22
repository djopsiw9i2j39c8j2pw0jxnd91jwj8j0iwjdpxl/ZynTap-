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

    /** Hộp thoại hệ thống "Cho phép ZynTap chạy nền?"; nếu máy không có thì mở danh sách tối ưu pin. */
    fun requestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        if (direct.resolveActivity(context.packageManager) != null) return direct
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        return if (list.resolveActivity(context.packageManager) != null) list else null
    }
}
