package com.zyntap.app.overlay

import kotlin.math.roundToInt

internal object FrameRateMath {
    fun calculate(timestamps: List<Long>, newest: Long): Int {
        val window = timestamps.filter { it > 0 && it != Long.MAX_VALUE && it >= newest - 1_000_000_000L && it <= newest }.distinct().sorted()
        if (window.size < 2) return -1
        val span = window.last() - window.first()
        if (span <= 0L) return -1
        return (((window.size - 1) * 1_000_000_000.0 / span).roundToInt()).coerceIn(0, 240)
    }
}
