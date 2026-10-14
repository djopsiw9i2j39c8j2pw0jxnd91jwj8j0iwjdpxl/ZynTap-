package com.zyntap.app.data

import android.content.Context

/** Lưu trạng thái liên kết ADB Wi-Fi (đã pair hay chưa, cổng kết nối gần nhất). */
object AdbLinkStore {
    private const val PREFS = "zyntap"
    private const val KEY_PAIRED = "adb_paired"
    private const val KEY_CONNECT_PORT = "adb_connect_port"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isPaired(context: Context): Boolean = prefs(context).getBoolean(KEY_PAIRED, false)

    fun setPaired(context: Context, paired: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAIRED, paired).apply()
    }

    fun saveConnectPort(context: Context, port: Int) {
        prefs(context).edit().putInt(KEY_CONNECT_PORT, port).apply()
    }

    fun connectPort(context: Context): Int = prefs(context).getInt(KEY_CONNECT_PORT, -1)
}
