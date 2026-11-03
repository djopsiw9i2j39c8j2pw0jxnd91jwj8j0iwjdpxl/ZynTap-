package com.nuliz.ads

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val KEY_ADS = "ads"
    private const val KEY_MALWARE = "malware"
    private const val KEY_NOTIFY = "notify_each_block"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.applicationContext.getSharedPreferences("nuliz", Context.MODE_PRIVATE)
        }
    }

    val ads get() = sp.getLong(KEY_ADS, 0)
    val malware get() = sp.getLong(KEY_MALWARE, 0)

    var notifyEachBlock: Boolean
        get() = sp.getBoolean(KEY_NOTIFY, true)
        set(value) = sp.edit().putBoolean(KEY_NOTIFY, value).apply()

    fun saveCounts(ads: Long, malware: Long) =
        sp.edit().putLong(KEY_ADS, ads).putLong(KEY_MALWARE, malware).apply()
}
