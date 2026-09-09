package com.moa.calendar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable fun LineIcon(name: String, color: Color = Ink, modifier: Modifier = Modifier, label: String? = null) {
    Canvas(modifier.size(22.dp).semantics { if (label != null) contentDescription = label }) {
        val scale = size.width / 24f
        fun point(x: Float, y: Float) = Offset(x * scale, y * scale)
        val stroke = Stroke(1.6f * scale, cap = StrokeCap.Round)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, point(x, y), point(x2, y2), stroke.width, StrokeCap.Round)
        fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 3f) = drawRoundRect(color, point(x, y), Size(w * scale, h * scale), CornerRadius(r * scale), style = stroke)
        when (name) {
            "calendar" -> { rect(3f, 5f, 18f, 16f); line(3f, 10f, 21f, 10f); line(8f, 3f, 8f, 7f); line(16f, 3f, 16f, 7f); rect(8f, 14f, 3f, 3f, .5f) }
            "agenda" -> { for (y in listOf(6f, 12f, 18f)) { drawCircle(color, 1.2f * scale, point(4f, y)); line(9f, y, 20f, y) } }
            "widget" -> { rect(3f, 3f, 7f, 7f, 2f); rect(14f, 3f, 7f, 7f, 2f); rect(3f, 14f, 7f, 7f, 2f); rect(14f, 14f, 7f, 7f, 2f) }
            "settings" -> { for ((y, x) in listOf(5f to 8f, 12f to 16f, 19f to 10f)) { line(3f, y, x - 3, y); line(x + 3, y, 21f, y); drawCircle(color, 2.5f * scale, point(x, y), style = stroke) } }
            "search" -> { drawCircle(color, 7f * scale, point(10f, 10f), style = stroke); line(15f, 15f, 21f, 21f) }
            "plus" -> { line(12f, 4f, 12f, 20f); line(4f, 12f, 20f, 12f) }
            "left" -> { line(14f, 6f, 8f, 12f); line(8f, 12f, 14f, 18f) }
            "right" -> { line(10f, 6f, 16f, 12f); line(16f, 12f, 10f, 18f) }
            "close" -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            "clock" -> { drawCircle(color, 9f * scale, point(12f, 12f), style = stroke); line(12f, 7f, 12f, 12f); line(12f, 12f, 16f, 14f) }
            "check" -> { line(5f, 12f, 10f, 17f); line(10f, 17f, 20f, 6f) }
            "sync" -> { drawArc(color, 30f, 280f, false, point(4f, 4f), Size(16 * scale, 16 * scale), style = stroke); line(19f, 3f, 20f, 9f); line(20f, 9f, 15f, 8f) }
            "pin" -> { drawCircle(color, 6f * scale, point(12f, 9f), style = stroke); line(7f, 13f, 12f, 21f); line(12f, 21f, 17f, 13f); drawCircle(color, 2f * scale, point(12f, 9f), style = stroke) }
            "link" -> { rect(2f, 7f, 12f, 10f, 5f); rect(10f, 7f, 12f, 10f, 5f); line(8f, 12f, 16f, 12f) }
            "moon" -> { val path = Path().apply { moveTo(17 * scale, 3 * scale); cubicTo(2 * scale, 1 * scale, 1 * scale, 23 * scale, 19 * scale, 20 * scale); cubicTo(9 * scale, 20 * scale, 8 * scale, 8 * scale, 17 * scale, 3 * scale) }; drawPath(path, color, style = stroke) }
        }
    }
}
