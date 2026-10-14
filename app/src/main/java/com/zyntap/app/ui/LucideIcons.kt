package com.zyntap.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Các icon Lucide (viewBox 24x24, nét 2, đầu tròn) dựng từ chính dữ liệu path trong SVG.
 * Màu thật do tham số `tint` của Icon quyết định.
 */
object Lucide {
    val Menu: ImageVector by lazy { build("menu", "M4 5h16", "M4 12h16", "M4 19h16") }
    val ChevronRight: ImageVector by lazy { build("chevron-right", "m9 18 6-6-6-6") }
    val ChevronLeft: ImageVector by lazy { build("chevron-left", "m15 18-6-6 6-6") }
    val Plus: ImageVector by lazy { build("plus", "M5 12h14", "M12 5v14") }
    val Minus: ImageVector by lazy { build("minus", "M5 12h14") }
    val Check: ImageVector by lazy { build("check", "M20 6 9 17l-5-5") }
    val Pencil: ImageVector by lazy {
        build(
            "pencil",
            "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z",
            "m15 5 4 4",
        )
    }

    private fun build(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
