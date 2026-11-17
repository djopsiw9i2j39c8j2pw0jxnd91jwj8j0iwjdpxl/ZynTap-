package com.zyntap.app.overlay

import android.content.Context

object UiAnim {
    private const val PREFS = "zyntap_runtime"
    private const val KEY = "ui_animations"

    @Volatile var enabled = true
        private set
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        enabled = prefs(context).getBoolean(KEY, true)
    }

    fun set(context: Context, value: Boolean) {
        loaded = true
        enabled = value
        prefs(context).edit().putBoolean(KEY, value).apply()
    }

    fun ms(duration: Long): Long = if (enabled) duration else 0L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
