package com.zyntap.app.overlay

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

internal fun addTabHeader(ctx: Context, page: LinearLayout, title: String, subtitle: String): TextView {
    val d = UiScale.density(ctx.resources)
    
    val titleBox = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }
    titleBox.addView(TextView(ctx).apply {
        text = title
        setTextColor(0xFFE8EEFA.toInt())
        scaledTextSize(17f)
        setTypeface(typeface, Typeface.BOLD)
    })
    val sub = TextView(ctx).apply {
        text = subtitle
        setTextColor(0xFF8D9DBA.toInt())
        scaledTextSize(12f)
    }
    titleBox.addView(sub)
    val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    wrap.addView(
        titleBox,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (HEADER_H_DP * d).toInt())
            .apply { marginEnd = (128 * d).toInt() },
    )
    
    page.addView(
        wrap,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = (11 * d).toInt() },
    )
    return sub
}

internal const val HEADER_H_DP = 38

internal fun accentDivider(ctx: Context): View = View(ctx).apply {
    background = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(0xFF1E9BFF.toInt(), 0x551E9BFF, 0x001E9BFF),
    )
}
