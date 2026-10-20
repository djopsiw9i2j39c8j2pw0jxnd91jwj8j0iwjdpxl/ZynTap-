package com.zyntap.app.overlay

/** Strict data-only DSL. Never pass user text to a shell. Coordinates use physical screen pixels. */
sealed class PointStep {
    data class Tap(val x: Int, val y: Int) : PointStep()
    /** Click TẤT CẢ các điểm CÙNG MỘT LÚC (nhiều ngón chạm xuống cùng lúc). Dòng: `multi x1,y1 | x2,y2 | ...` */
    data class Multi(val points: List<Pair<Int, Int>>) : PointStep()
    data class Wait(val millis: Long) : PointStep()
}

data class PointScript(val steps: List<PointStep>, val errors: List<String>) {
    val tapCount get() = steps.fold(0) { acc, st -> acc + when (st) { is PointStep.Tap -> 1; is PointStep.Multi -> st.points.size; is PointStep.Wait -> 0 } }
    /** Mọi điểm chạm của kịch bản (cả Click point lẫn Multiclick point). */
    val allPoints: List<Pair<Int, Int>> get() = steps.flatMap {
        when (it) {
            is PointStep.Tap -> listOf(it.x to it.y)
            is PointStep.Multi -> it.points
            else -> emptyList()
        }
    }
    val valid get() = errors.isEmpty() && tapCount > 0
}

object PointScriptParser {
    const val MAX_TEXT = 100_000
    const val MAX_STEPS = 2000
    const val MAX_DELAY = 3_600_000L
    /** Số điểm tối đa của một dòng Multiclick (khớp helper; chừa chỗ pointer cho ngón thật). */
    const val MAX_MULTI = 10
    private val multiPrefix = Regex("^multi\\s*:?\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val point = Regex("^(\\d+)\\s*,\\s*(\\d+)$")
    private val wait = Regex("^(\\d+)\\s*ms$", RegexOption.IGNORE_CASE)

    fun parse(text: String, width: Int = Int.MAX_VALUE, height: Int = Int.MAX_VALUE): PointScript {
        val steps = mutableListOf<PointStep>()
        val errors = mutableListOf<String>()
        if (text.length > MAX_TEXT) return PointScript(emptyList(), listOf("Nội dung vượt $MAX_TEXT ký tự"))
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            if (steps.size >= MAX_STEPS) {
                if (errors.size < 8) errors += "Tối đa $MAX_STEPS bước"
                return@forEachIndexed
            }
            val p = point.matchEntire(line)
            val w = wait.matchEntire(line)
            val m = multiPrefix.matchEntire(line)
            val error = when {
                m != null -> {
                    val parts = m.groupValues[1].split('|').map { it.trim() }
                    val pts = mutableListOf<Pair<Int, Int>>()
                    var bad: String? = null
                    for (part in parts) {
                        val mp = point.matchEntire(part)
                        val x = mp?.groupValues?.get(1)?.toIntOrNull()
                        val y = mp?.groupValues?.get(2)?.toIntOrNull()
                        if (x == null || y == null || x !in 0 until width || y !in 0 until height) {
                            bad = "Multiclick cần dạng multi x1,y1 | x2,y2 (tọa độ trong màn hình)"; break
                        }
                        pts += x to y
                    }
                    when {
                        bad != null -> bad
                        pts.size > MAX_MULTI -> "Multiclick tối đa $MAX_MULTI điểm"
                        else -> { steps += PointStep.Multi(pts); null }
                    }
                }
                p != null -> {
                    val x = p.groupValues[1].toIntOrNull()
                    val y = p.groupValues[2].toIntOrNull()
                    if (x == null || y == null || x !in 0 until width || y !in 0 until height) {
                        "Tọa độ ngoài màn hình hoặc quá lớn"
                    } else { steps += PointStep.Tap(x, y); null }
                }
                w != null -> {
                    val ms = w.groupValues[1].toLongOrNull()
                    if (ms == null || ms !in 0..MAX_DELAY) "Delay cần nằm trong 0..${MAX_DELAY}ms"
                    else { steps += PointStep.Wait(ms); null }
                }
                else -> "Dùng x, y · 300ms · multi x1,y1 | x2,y2"
            }
            if (error != null && errors.size < 8) errors += "Dòng ${index + 1}: $error"
        }
        return PointScript(steps, errors)
    }
}
