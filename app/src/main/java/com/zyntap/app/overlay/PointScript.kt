package com.zyntap.app.overlay

/** Strict data-only DSL. Never pass user text to a shell. Coordinates use physical screen pixels. */
sealed class PointStep {
    data class Tap(val x: Int, val y: Int) : PointStep()
    /**
     * Click TẤT CẢ các điểm CÙNG MỘT LÚC (nhiều ngón chạm xuống cùng lúc). Mỗi dòng `Multiclick x, y` là MỘT điểm;
     * các dòng Multiclick nằm liền nhau gộp thành một cụm. Chỉ có 1 dòng thì chỉ click 1 điểm.
     */
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
    private val clickPrefix = Regex("^click\\s*:?\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val multiPrefix = Regex("^multi(?:click)?\\s*:?\\s*(.*)$", RegexOption.IGNORE_CASE)
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
            val c = clickPrefix.matchEntire(line)
            val m = multiPrefix.matchEntire(line)
            // `Click x, y` (định dạng mới) hoặc `x, y` (định dạng cũ vẫn dùng được)
            val p = point.matchEntire(c?.groupValues?.get(1)?.trim() ?: line)
            val mp = m?.let { point.matchEntire(it.groupValues[1].trim()) }
            val w = wait.matchEntire(line)
            val error = when {
                m != null -> {
                    val x = mp?.groupValues?.get(1)?.toIntOrNull()
                    val y = mp?.groupValues?.get(2)?.toIntOrNull()
                    val last = steps.lastOrNull()
                    when {
                        x == null || y == null || x !in 0 until width || y !in 0 until height ->
                            "Multiclick cần dạng Multiclick x, y (tọa độ trong màn hình)"
                        last is PointStep.Multi && last.points.size >= MAX_MULTI -> "Multiclick tối đa $MAX_MULTI điểm liền nhau"
                        last is PointStep.Multi -> { steps[steps.lastIndex] = PointStep.Multi(last.points + (x to y)); null }
                        else -> { steps += PointStep.Multi(listOf(x to y)); null }
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
                else -> "Dùng Click x, y · Multiclick x, y · 300ms"
            }
            if (error != null && errors.size < 8) errors += "Dòng ${index + 1}: $error"
        }
        return PointScript(steps, errors)
    }
}
