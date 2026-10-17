package com.zyntap.app.overlay

import java.text.Normalizer

/** Loại điểm "giữ liên tục khi đang giữ nút main": kéo tâm lên, ghìm tâm xuống, buff lực kéo ngón thật. */
enum class PullKind(val keyword: String, val label: String, val dir: Int) {
    UP("kéo", "Kéo tâm", -1),
    DOWN("ghìm", "Ghìm tâm", 1),
    BUFF("buff", "Buff lực kéo", 0);

    /** Kéo/ghìm dùng chung 1 kênh (ngón giả); buff là kênh riêng nên chạy song song được với kéo/ghìm. */
    val isPull get() = this != BUFF
}

/** Strict data-only DSL. Never pass user text to a shell. Coordinates use physical screen pixels. */
sealed class PointStep {
    data class Tap(val x: Int, val y: Int) : PointStep()
    data class Wait(val millis: Long) : PointStep()
    /** Bật kéo/ghìm/buff; chạy liên tục tới khi NHẢ nút main (không chặn các bước sau). [power] 1..100. */
    data class Pull(val kind: PullKind, val x: Int, val y: Int, val power: Int) : PointStep()
}

data class PointScript(val steps: List<PointStep>, val errors: List<String>) {
    val tapCount get() = steps.count { it is PointStep.Tap }
    val pullCount get() = steps.count { it is PointStep.Pull }
    val valid get() = errors.isEmpty() && (tapCount > 0 || pullCount > 0)
}

object PointScriptParser {
    const val MAX_TEXT = 100_000
    const val MAX_STEPS = 2000
    const val MAX_DELAY = 3_600_000L
    const val MIN_POWER = 1
    const val MAX_POWER = 100
    const val DEFAULT_POWER = 50
    private val point = Regex("^(\\d+)\\s*,\\s*(\\d+)$")
    private val wait = Regex("^(\\d+)\\s*ms$", RegexOption.IGNORE_CASE)
    // kéo|keo|ghìm|ghim|buff  x, y [, lực]
    private val pull = Regex("^(kéo|keo|ghìm|ghim|buff)\\s+(\\d+)\\s*,\\s*(\\d+)(?:\\s*,\\s*(\\d+))?$")

    private fun kindOf(word: String): PullKind = when (word) {
        "kéo", "keo" -> PullKind.UP
        "ghìm", "ghim" -> PullKind.DOWN
        else -> PullKind.BUFF
    }

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
            val f = pull.matchEntire(Normalizer.normalize(line.lowercase(), Normalizer.Form.NFC))
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
                f != null -> {
                    val x = f.groupValues[2].toIntOrNull()
                    val y = f.groupValues[3].toIntOrNull()
                    val power = f.groupValues[4].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: DEFAULT_POWER
                    when {
                        x == null || y == null || x !in 0 until width || y !in 0 until height ->
                            "Tọa độ ngoài màn hình hoặc quá lớn"
                        power !in MIN_POWER..MAX_POWER -> "Lực cần nằm trong $MIN_POWER..$MAX_POWER"
                        else -> { steps += PointStep.Pull(kindOf(f.groupValues[1]), x, y, power); null }
                    }
                }
                else -> "Dùng x, y · kéo x, y, lực · ghìm x, y, lực · buff x, y, lực · 300ms"
            }
            if (error != null && errors.size < 8) errors += "Dòng ${index + 1}: $error"
        }
        return PointScript(steps, errors)
    }
}
