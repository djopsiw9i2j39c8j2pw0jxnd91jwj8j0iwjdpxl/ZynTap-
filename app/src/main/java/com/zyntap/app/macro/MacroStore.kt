package com.zyntap.app.macro

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lưu danh sách nút main và các cấu hình đã đặt tên (SharedPreferences dạng JSON).
 * Một cấu hình = ảnh chụp toàn bộ nút main tại thời điểm lưu.
 */
class MacroStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("zyntap_macro", Context.MODE_PRIVATE)

    fun loadMains(): MutableList<MainButton> = decodeMains(prefs.getString(KEY_MAINS, null))

    fun saveMains(mains: List<MainButton>) {
        prefs.edit().putString(KEY_MAINS, encodeMains(mains)).apply()
    }

    /** Tên cấu hình theo thứ tự tạo. */
    fun configNames(): List<String> {
        val arr = runCatching { JSONArray(prefs.getString(KEY_CONFIG_ORDER, "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun loadConfig(name: String): MutableList<MainButton>? {
        val raw = prefs.getString(configKey(name), null) ?: return null
        return decodeMains(raw)
    }

    /** Tạo mới hoặc ghi đè cấu hình cùng tên. */
    fun saveConfig(name: String, mains: List<MainButton>) {
        val names = configNames().toMutableList()
        if (name !in names) names += name
        prefs.edit()
            .putString(configKey(name), encodeMains(mains))
            .putString(KEY_CONFIG_ORDER, JSONArray(names).toString())
            .apply()
    }

    fun deleteConfig(name: String) {
        val names = configNames().filterNot { it == name }
        prefs.edit()
            .remove(configKey(name))
            .putString(KEY_CONFIG_ORDER, JSONArray(names).toString())
            .apply()
    }

    private fun configKey(name: String) = "config:$name"

    private fun encodeMains(mains: List<MainButton>): String {
        val arr = JSONArray()
        mains.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("mode", m.mode.name)
                put("opacityPct", m.opacityPct)
                put("sizeDp", m.sizeDp)
                put("posX", m.posX)
                put("posY", m.posY)
                put("script", m.script)
            })
        }
        return arr.toString()
    }

    private fun decodeMains(raw: String?): MutableList<MainButton> {
        val out = mutableListOf<MainButton>()
        if (raw.isNullOrBlank()) return out
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += MainButton(
                id = o.optString("id"),
                name = o.optString("name"),
                mode = MainMode.fromName(o.optString("mode")),
                opacityPct = o.optInt("opacityPct", 85),
                sizeDp = o.optInt("sizeDp", 56),
                posX = o.optInt("posX", -1),
                posY = o.optInt("posY", -1),
                script = o.optString("script"),
            )
        }
        return out
    }

    private companion object {
        const val KEY_MAINS = "mains"
        const val KEY_CONFIG_ORDER = "config_order"
    }
}

/** Mã nút tiếp theo: main1, main2, ... (lấy số lớn nhất hiện có + 1). */
fun nextMainId(existing: List<MainButton>): String {
    val max = existing.mapNotNull { it.id.removePrefix("main").toIntOrNull() }.maxOrNull() ?: 0
    return "main${max + 1}"
}
