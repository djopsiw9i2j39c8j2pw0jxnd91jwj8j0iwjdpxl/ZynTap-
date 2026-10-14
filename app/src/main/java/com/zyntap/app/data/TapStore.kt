package com.zyntap.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Chế độ bấm của nút main: nhấn (chạm phát chạy) / giữ (lặp tới khi thả) / thả (giữ rồi thả mới chạy). */
enum class TapMode { TAP, HOLD, RELEASE }

/** Một nút main nổi trên màn hình. */
data class MainButton(
    var id: String,
    var name: String,
    var mode: TapMode = TapMode.TAP,
    var alphaPct: Int = 100,
    var sizeDp: Int = 56,
    var x: Int = -1,
    var y: Int = -1,
    var script: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("mode", mode.name)
        put("alpha", alphaPct)
        put("size", sizeDp)
        put("x", x)
        put("y", y)
        put("script", script)
    }

    companion object {
        fun fromJson(o: JSONObject) = MainButton(
            id = o.optString("id"),
            name = o.optString("name"),
            mode = runCatching { TapMode.valueOf(o.optString("mode")) }.getOrDefault(TapMode.TAP),
            alphaPct = o.optInt("alpha", 100),
            sizeDp = o.optInt("size", 56),
            x = o.optInt("x", -1),
            y = o.optInt("y", -1),
            script = o.optString("script"),
        )
    }
}

/** Lưu nút main + các cấu hình (config) vào SharedPreferences dạng JSON. */
object TapStore {
    private const val PREFS = "zyntap_taps"
    private const val KEY_BUTTONS = "buttons"
    private const val KEY_CONFIGS = "configs"
    private const val KEY_COUNTER = "counter"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- nút main -------------------------------------------------------------

    fun loadButtons(ctx: Context): MutableList<MainButton> {
        val raw = prefs(ctx).getString(KEY_BUTTONS, "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        val out = mutableListOf<MainButton>()
        for (i in 0 until arr.length()) {
            runCatching { out.add(MainButton.fromJson(arr.getJSONObject(i))) }
        }
        return out
    }

    fun saveButtons(ctx: Context, buttons: List<MainButton>) {
        val arr = JSONArray()
        buttons.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY_BUTTONS, arr.toString()).apply()
    }

    /** Tạo id dạng main1, main2, ... không trùng với nút đã có. */
    fun nextButtonId(ctx: Context, existing: List<MainButton>): String {
        var n = prefs(ctx).getInt(KEY_COUNTER, 0)
        var id: String
        do {
            n += 1
            id = "main$n"
        } while (existing.any { it.id == id })
        prefs(ctx).edit().putInt(KEY_COUNTER, n).apply()
        return id
    }

    // ---- cấu hình --------------------------------------------------------------

    fun configNames(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY_CONFIGS, "{}") ?: "{}"
        val obj = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        return obj.keys().asSequence().toList().sorted()
    }

    fun saveConfig(ctx: Context, name: String, buttons: List<MainButton>) {
        val raw = prefs(ctx).getString(KEY_CONFIGS, "{}") ?: "{}"
        val obj = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        val arr = JSONArray()
        buttons.forEach { arr.put(it.toJson()) }
        obj.put(name, arr)
        prefs(ctx).edit().putString(KEY_CONFIGS, obj.toString()).apply()
    }

    fun loadConfig(ctx: Context, name: String): List<MainButton>? {
        val raw = prefs(ctx).getString(KEY_CONFIGS, "{}") ?: "{}"
        val obj = runCatching { JSONObject(raw) }.getOrElse { return null }
        val arr = obj.optJSONArray(name) ?: return null
        val out = mutableListOf<MainButton>()
        for (i in 0 until arr.length()) {
            runCatching { out.add(MainButton.fromJson(arr.getJSONObject(i))) }
        }
        return out
    }

    fun deleteConfig(ctx: Context, name: String) {
        val raw = prefs(ctx).getString(KEY_CONFIGS, "{}") ?: "{}"
        val obj = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        obj.remove(name)
        prefs(ctx).edit().putString(KEY_CONFIGS, obj.toString()).apply()
    }
}
