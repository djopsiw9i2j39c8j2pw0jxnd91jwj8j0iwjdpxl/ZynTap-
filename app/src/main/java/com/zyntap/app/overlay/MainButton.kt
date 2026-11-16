package com.zyntap.app.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class MainButton(
    val id: Int,
    var name: String,
    var mode: Int = MODE_TAP,
    var alphaPct: Int = DEFAULT_ALPHA,
    var sizeDp: Int = DEFAULT_SIZE,
    var x: Float = 0.8f,
    var y: Float = 0.5f,
    var pointScript: String = "",
    var pointWidth: Int = 0,
    var pointHeight: Int = 0,
    var pointRotation: Int = 0,
     
    var passThrough: Boolean = false,
     
    var assistEnabled: Boolean = false,
    var assistMode: Int = ASSIST_PULL_UP,
     
    var assistSpeed: Int = DEFAULT_ASSIST_SPEED,
     
    var assistLength: Int = DEFAULT_ASSIST_LENGTH,
     
    var assistDelay: Int = DEFAULT_ASSIST_DELAY,
     
    var boostGain: Int = DEFAULT_BOOST_GAIN,
     
    var delayEnabled: Boolean = false,
    var delayMs: Int = DEFAULT_DELAY_MS,
) {
    fun scriptFor(width: Int, height: Int, rotation: Int): PointScript {
        val w = pointWidth.takeIf { it > 0 } ?: width
        val h = pointHeight.takeIf { it > 0 } ?: height
        return PointCoordinates.transform(PointScriptParser.parse(pointScript, w, h),
            w, h, if (pointWidth > 0 && pointHeight > 0) pointRotation else rotation, width, height, rotation)
    }

    fun textFor(width: Int, height: Int, rotation: Int): String {
        if (pointWidth <= 0 || pointHeight <= 0) return pointScript
        return PointCoordinates.reframeText(pointScript, pointWidth, pointHeight, pointRotation, width, height, rotation)
    }

    val code: String get() = "main$id"

    val delayActive: Boolean get() = delayEnabled && !assistEnabled && mode != MODE_HOLD

    companion object {
        const val MODE_TAP = 0      
        const val MODE_HOLD = 1     
        const val MODE_RELEASE = 2  
        val MODE_LABELS = listOf("Nhấn", "Giữ", "Thả")

        const val MIN_ALPHA = 0
        const val MAX_ALPHA = 100
        const val DEFAULT_ALPHA = 80
         
        const val MENU_MIN_ALPHA = 20
        const val MIN_SIZE = 32
        const val MAX_SIZE = 120
        const val DEFAULT_SIZE = 56
        const val MAX_NAME = 24

        const val ASSIST_PULL_UP = 0    
        const val ASSIST_HOLD_DOWN = 1  
        const val ASSIST_BOOST = 2      
        val ASSIST_LABELS = listOf("Kéo tâm", "Ghìm tâm", "Buff lực vuốt")
        const val MIN_ASSIST_SPEED = 20
        const val MAX_ASSIST_SPEED = 3000
        const val DEFAULT_ASSIST_SPEED = 300
        const val MIN_ASSIST_LENGTH = 20
        const val MAX_ASSIST_LENGTH = 400
        const val DEFAULT_ASSIST_LENGTH = 80
        const val MIN_ASSIST_DELAY = 0
        const val MAX_ASSIST_DELAY = 1000
        const val DEFAULT_ASSIST_DELAY = 0
        const val MIN_BOOST_GAIN = 100
        const val MAX_BOOST_GAIN = 500
        const val DEFAULT_BOOST_GAIN = 200

        const val MIN_DELAY_MS = 100
        const val MAX_DELAY_MS = 600_000   
        const val DEFAULT_DELAY_MS = 3000

        fun delaySecondsText(ms: Int): String {
            val t = String.format(java.util.Locale.US, "%.2f", ms / 1000.0).trimEnd('0').trimEnd('.')
            return t.ifEmpty { "0" }
        }

        fun parseDelaySeconds(text: String): Int? {
            val sec = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
            if (sec.isNaN() || sec.isInfinite()) return null
            return Math.round(sec * 1000.0).coerceIn(MIN_DELAY_MS.toLong(), MAX_DELAY_MS.toLong()).toInt()
        }

        fun modeLabel(mode: Int) = MODE_LABELS.getOrElse(mode) { MODE_LABELS[0] }
    }
}

class MainButtonRepo(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val buttons: MutableList<MainButton> = load()

    fun nextId(): Int {
        var n = 1
        while (buttons.any { it.id == n }) n++
        return n
    }

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

    fun bindLegacyCoordinates(width: Int, height: Int, rotation: Int) {
        var changed = false
        buttons.filter { it.pointWidth <= 0 || it.pointHeight <= 0 }.forEach {
            it.pointWidth = width; it.pointHeight = height; it.pointRotation = rotation
            changed = true
        }
        if (changed) save()
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
                    .put("pointWidth", b.pointWidth)
                    .put("pointHeight", b.pointHeight)
                    .put("pointRotation", b.pointRotation)
                    .put("passThrough", b.passThrough)
                    .put("assistEnabled", b.assistEnabled)
                    .put("assistMode", b.assistMode)
                    .put("assistSpeed", b.assistSpeed)
                    .put("assistLength", b.assistLength)
                    .put("assistDelay", b.assistDelay)
                    .put("boostGain", b.boostGain)
                    .put("delayEnabled", b.delayEnabled)
                    .put("delayMs", b.delayMs),
            )
        }
        return arr
    }

    fun exportJson(): String = toArray().toString()

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
                        pointWidth = o.optInt("pointWidth", 0).coerceAtLeast(0),
                        pointHeight = o.optInt("pointHeight", 0).coerceAtLeast(0),
                        pointRotation = o.optInt("pointRotation", 0).coerceIn(0, 3),
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
                        delayEnabled = o.optBoolean("delayEnabled", false),
                        delayMs = o.optInt("delayMs", MainButton.DEFAULT_DELAY_MS)
                            .coerceIn(MainButton.MIN_DELAY_MS, MainButton.MAX_DELAY_MS),
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
