package com.zyntap.app.overlay

/** Strict data-only DSL. Never pass user text to a shell. Coordinates use physical screen pixels. */
sealed class PointStep {
    data class Tap(val x: Int, val y: Int) : PointStep()
    data class Wait(val millis: Long) : PointStep()
}

data class PointScript(val steps: List<PointStep>, val errors: List<String>) {
    val tapCount get() = steps.count { it is PointStep.Tap }
    val valid get() = errors.isEmpty() && tapCount > 0
}

object PointScriptParser {
    const val MAX_TEXT = 100_000
    const val MAX_STEPS = 2000
    const val MAX_DELAY = 3_600_000L
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
            val error = when {
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
                else -> "Dùng x, y hoặc 300ms"
            }
            if (error != null && errors.size < 8) errors += "Dòng ${index + 1}: $error"
        }
        return PointScript(steps, errors)
    }
}
