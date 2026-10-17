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
    /** Trợ vuốt tâm: bật thì nút main chạy chế độ trợ vuốt thay vì script điểm nhấn. */
    var assistEnabled: Boolean = false,
    var assistMode: Int = ASSIST_PULL_UP,
    /** Tốc độ kéo (dp/giây). */
    var assistSpeed: Int = DEFAULT_ASSIST_SPEED,
    /** Quãng mỗi lần vuốt (dp). */
    var assistLength: Int = DEFAULT_ASSIST_LENGTH,
    /** Nghỉ giữa hai lần vuốt (ms). */
    var assistDelay: Int = DEFAULT_ASSIST_DELAY,
    /** Buff lực vuốt: hệ số nhân quãng vuốt của ngón thật, tính theo % (200 = x2). */
    var boostGain: Int = DEFAULT_BOOST_GAIN,
    /** Buff lực vuốt chỉ hoạt động khi đang giữ nút main này. */
    var boostHoldOnly: Boolean = false,
) {
    val code: String get() = "main$id"

    /** Kéo tâm / ghìm tâm đang bật: nút main tự vuốt liên tục khi giữ. */
    val assistPull: Boolean get() = assistEnabled && assistMode != ASSIST_BOOST
    /** Buff lực vuốt đang bật. */
    val assistBoost: Boolean get() = assistEnabled && assistMode == ASSIST_BOOST

    companion object {
        const val MODE_TAP = 0      // nhấn
        const val MODE_HOLD = 1     // giữ
        const val MODE_RELEASE = 2  // thả
        val MODE_LABELS = listOf("Nhấn", "Giữ", "Thả")

        const val MIN_ALPHA = 0
        const val MAX_ALPHA = 100
        const val DEFAULT_ALPHA = 80
        /** Khi menu đang mở, nút có độ mờ thấp hơn mức này vẫn được vẽ ở mức này để còn thấy mà chỉnh. */
        const val MENU_MIN_ALPHA = 20
        const val MIN_SIZE = 32
        const val MAX_SIZE = 120
        const val DEFAULT_SIZE = 56
        const val MAX_NAME = 24

        const val ASSIST_PULL_UP = 0    // kéo tâm: vuốt lên
        const val ASSIST_HOLD_DOWN = 1  // ghìm tâm: vuốt xuống
        const val ASSIST_BOOST = 2      // buff lực vuốt
        val ASSIST_LABELS = listOf("Kéo tâm", "Ghìm tâm", "Buff lực vuốt")
        const val MIN_ASSIST_SPEED = 200
        const val MAX_ASSIST_SPEED = 5000
        const val DEFAULT_ASSIST_SPEED = 1200
        const val MIN_ASSIST_LENGTH = 20
        const val MAX_ASSIST_LENGTH = 500
        const val DEFAULT_ASSIST_LENGTH = 120
        const val MIN_ASSIST_DELAY = 0
        const val MAX_ASSIST_DELAY = 1000
        const val DEFAULT_ASSIST_DELAY = 30
        const val MIN_BOOST_GAIN = 100
        const val MAX_BOOST_GAIN = 500
        const val DEFAULT_BOOST_GAIN = 200

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
        prefs.edit().putString(KEY, toArray().toString()).apply()
    }

    private fun toArray(): JSONArray {
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
                    .put("passThrough", b.passThrough)
                    .put("assistEnabled", b.assistEnabled)
                    .put("assistMode", b.assistMode)
                    .put("assistSpeed", b.assistSpeed)
                    .put("assistLength", b.assistLength)
                    .put("assistDelay", b.assistDelay)
                    .put("boostGain", b.boostGain)
                    .put("boostHoldOnly", b.boostHoldOnly),
            )
        }
        return arr
    }

    /** Toàn bộ danh sách nút main hiện tại dạng JSON (dùng để lưu thành cấu hình). */
    fun exportJson(): String = toArray().toString()

    /** Thay toàn bộ nút main bằng [json]; false (và giữ nguyên) nếu JSON hỏng. */
    fun replaceFromJson(json: String): Boolean {
        val list = parse(json) ?: return false
        buttons.clear()
        buttons.addAll(list)
        save()
        return true
    }

    private fun load(): MutableList<MainButton> = parse(prefs.getString(KEY, "[]") ?: "[]") ?: mutableListOf()

    private fun parse(json: String): MutableList<MainButton>? {
        val out = mutableListOf<MainButton>()
        val ok = runCatching {
            val arr = JSONArray(json)
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
                        assistEnabled = o.optBoolean("assistEnabled", false),
                        assistMode = o.optInt("assistMode", MainButton.ASSIST_PULL_UP)
                            .coerceIn(0, MainButton.ASSIST_LABELS.lastIndex),
                        assistSpeed = o.optInt("assistSpeed", MainButton.DEFAULT_ASSIST_SPEED)
                            .coerceIn(MainButton.MIN_ASSIST_SPEED, MainButton.MAX_ASSIST_SPEED),
                        assistLength = o.optInt("assistLength", MainButton.DEFAULT_ASSIST_LENGTH)
                            .coerceIn(MainButton.MIN_ASSIST_LENGTH, MainButton.MAX_ASSIST_LENGTH),
                        assistDelay = o.optInt("assistDelay", MainButton.DEFAULT_ASSIST_DELAY)
                            .coerceIn(MainButton.MIN_ASSIST_DELAY, MainButton.MAX_ASSIST_DELAY),
                        boostGain = o.optInt("boostGain", MainButton.DEFAULT_BOOST_GAIN)
                            .coerceIn(MainButton.MIN_BOOST_GAIN, MainButton.MAX_BOOST_GAIN),
                        boostHoldOnly = o.optBoolean("boostHoldOnly", false),
                        x = o.optDouble("x", 0.8).toFloat().coerceIn(0f, 1f),
                        y = o.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f),
                    ),
                )
            }
        }.isSuccess
        if (!ok) return null
        out.sortBy { it.id }
        return out
    }

    private companion object {
        const val PREFS = "zyntap_main_buttons"
        const val KEY = "buttons"
    }
}
