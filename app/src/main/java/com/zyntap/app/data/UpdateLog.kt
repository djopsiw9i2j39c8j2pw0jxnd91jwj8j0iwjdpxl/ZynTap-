package com.zyntap.app.data

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ghi vào Debug log MỖI LẦN app được cập nhật (cài đè bản mới, kể cả cài lại cùng phiên bản).
 *
 * Nhớ phiên bản + thời điểm cài lần trước trong SharedPreferences; mỗi lần app/dịch vụ khởi động
 * hoặc nhận MY_PACKAGE_REPLACED thì so sánh, khác là ghi 1 dòng. Gọi nhiều lần vẫn chỉ ghi 1 lần.
 */
object UpdateLog {

    private const val PREFS = "zyntap_version"
    private const val KEY_CODE = "last_code"
    private const val KEY_NAME = "last_name"
    private const val KEY_UPDATED_AT = "last_update_time"
    private val lock = Any()

    fun check(context: Context) {
        runCatching {
            val app = context.applicationContext
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val code = PackageInfoCompat.getLongVersionCode(info)
            val name = info.versionName ?: "?"
            val installedAt = info.lastUpdateTime
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val fmt = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.US)

            synchronized(lock) {
                val oldCode = prefs.getLong(KEY_CODE, -1L)
                val oldName = prefs.getString(KEY_NAME, null)
                val oldAt = prefs.getLong(KEY_UPDATED_AT, -1L)
                if (oldCode == code && oldAt == installedAt) return // đã ghi rồi

                val at = fmt.format(Date(installedAt))
                when {
                    oldCode < 0 ->
                        DebugLog.i("Cập nhật", "Cài đặt ZynTap $name lần đầu (lúc $at)")
                    oldCode < code ->
                        DebugLog.i("Cập nhật", "Đã CẬP NHẬT app: ${oldName ?: "?"} → $name (lúc $at)")
                    oldCode > code ->
                        DebugLog.w("Cập nhật", "Đã HẠ phiên bản app: ${oldName ?: "?"} → $name (lúc $at)")
                    else ->
                        DebugLog.i("Cập nhật", "Đã cài đè lại cùng phiên bản $name (lúc $at)")
                }
                prefs.edit()
                    .putLong(KEY_CODE, code)
                    .putString(KEY_NAME, name)
                    .putLong(KEY_UPDATED_AT, installedAt)
                    .apply()
            }
        }
    }
}
