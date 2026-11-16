package com.zyntap.app.overlay

import android.content.res.Resources
import android.widget.TextView
import kotlin.math.min

object UiScale {
    private const val REF_DP = 420f
    private const val MIN = 0.75f
    private const val MAX = 1.0f

    fun factor(r: Resources): Float {
        val m = r.displayMetrics
        val shortDp = min(m.widthPixels, m.heightPixels) / m.density
        return (shortDp / REF_DP).coerceIn(MIN, MAX)
    }

    fun density(r: Resources): Float = r.displayMetrics.density * factor(r)

    fun sp(r: Resources, v: Float): Float = v * factor(r)
}

fun TextView.scaledTextSize(sp: Float) {
    textSize = UiScale.sp(resources, sp)
}
