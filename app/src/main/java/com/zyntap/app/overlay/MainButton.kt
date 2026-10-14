package com.zyntap.app.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Một nút main (nút tròn nổi trên màn hình). Mã nút luôn là "main<id>" (main1, main2, ...).
 *
 * [x], [y] là vị trí dạng tỉ lệ 0..1 trên màn hình (để đúng cả khi xoay màn hình).
 */
class MainButton(
    val id: Int,
    var name: String,
    var mode: Int = MODE_TAP,
    var alphaPct: Int = DEFAULT_ALPHA,
    var sizeDp: Int = DEFAULT_SIZE,
    var x: Float = 0.8f,
    var y: Float = 0.5f,
    var pointScript: String = "",
    /** Chạm xuyên: chạm vào nút vừa kích hoạt nút main, vừa lọt xuống app bên dưới. */
    var passThrough: Boolean = false,
) {
    val code: String get() = "main$id"

    companion object {
        const val MODE_TAP = 0      // nhấn
        const val MODE_HOLD = 1     // giữ
        const val MODE_RELEASE = 2  // thả
        val MODE_LABELS = listOf("Nhấn", "Giữ", "Thả")

        const val MIN_ALPHA = 10
        const val MAX_ALPHA = 100
        const val DEFAULT_ALPHA = 80
        const val MIN_SIZE = 32
        const val MAX_SIZE = 120
        const val DEFAULT_SIZE = 56
        const val MAX_NAME = 24

        fun modeLabel(mode: Int) = MODE_LABELS.getOrElse(mode) { MODE_LABELS[0] }
    }
}

/** Danh sách nút main đang có, lưu bền vào SharedPreferences (JSON). */
class MainButtonRepo(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val buttons: MutableList<MainButton> = load()

    /** Số nhỏ nhất chưa dùng: main1, main2, main3... */
    fun nextId(): Int {
        var n = 1
        while (buttons.any { it.id == n }) n++
        return n
    }

    /** Vị trí mặc định cho nút mới: lệch dần để các nút không chồng lên nhau. */
    fun defaultPosition(id: Int): Pair<Float, Float> {
        val k = (id - 1) % 5
        return (0.78f + 0.04f * (k % 2)) to (0.25f + 0.12f * k)
    }

    fun find(id: Int): MainButton? = buttons.firstOrNull { it.id == id }

    fun add(b: MainButton) {
        buttons.add(b)
        buttons.sortBy { it.id }
        save()
    }

    fun remove(id: Int) {
        buttons.removeAll { it.id == id }
        save()
    }

    fun save() {
        val arr = JSONArray()
        buttons.forEach { b ->
            arr.put(
                JSONObject()
                    .put("id", b.id)
                    .put("name", b.name)
                    .put("mode", b.mode)
                    .put("alpha", b.alphaPct)
                    .put("size", b.sizeDp)
                    .put("x", b.x.toDouble())
                    .put("y", b.y.toDouble())
                    .put("pointScript", b.pointScript)
                    .put("passThrough", b.passThrough),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun load(): MutableList<MainButton> {
        val out = mutableListOf<MainButton>()
        runCatching {
            val arr = JSONArray(prefs.getString(KEY, "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getInt("id")
                if (out.any { it.id == id }) continue
                out.add(
                    MainButton(
                        id = id,
                        name = o.optString("name", "Nút $id").ifBlank { "Nút $id" },
                        mode = o.optInt("mode", MainButton.MODE_TAP).coerceIn(0, MainButton.MODE_LABELS.lastIndex),
                        alphaPct = o.optInt("alpha", MainButton.DEFAULT_ALPHA)
                            .coerceIn(MainButton.MIN_ALPHA, MainButton.MAX_ALPHA),
                        sizeDp = o.optInt("size", MainButton.DEFAULT_SIZE)
                            .coerceIn(MainButton.MIN_SIZE, MainButton.MAX_SIZE),
                        pointScript = o.optString("pointScript", "").take(PointScriptParser.MAX_TEXT),
                        passThrough = o.optBoolean("passThrough", false),
                        x = o.optDouble("x", 0.8).toFloat().coerceIn(0f, 1f),
                        y = o.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
                    ),
                )
            }
        }
        out.sortBy { it.id }
        return out
    }

    private companion object {
        const val PREFS = "zyntap_main_buttons"
        const val KEY = "buttons"
    }
}
