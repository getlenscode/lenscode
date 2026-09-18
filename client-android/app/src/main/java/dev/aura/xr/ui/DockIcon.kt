package dev.aura.xr.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

/** A consistent 24-unit line icon set. The adjacent dock label names each action. */
@Composable
fun DockIcon(id: String, color: Color) {
    Canvas(Modifier.size(22.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            val stroke = Stroke(1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(vararg points: Float) {
                val path = Path().apply {
                    moveTo(points[0], points[1])
                    for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1])
                }
                drawPath(path, color, style = stroke)
            }
            fun box(x: Float, y: Float, w: Float, h: Float, radius: Float = 2f) {
                drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(radius), style = stroke)
            }
            fun node(x: Float, y: Float) = drawCircle(color, 2f, Offset(x, y), style = stroke)
            when (id) {
                "home" -> {
                    line(3f, 11f, 12f, 3f, 21f, 11f)
                    line(5f, 10f, 5f, 21f, 19f, 21f, 19f, 10f)
                    line(10f, 21f, 10f, 14f, 14f, 14f, 14f, 21f)
                }
                "code" -> {
                    line(8f, 6f, 2f, 12f, 8f, 18f)
                    line(16f, 6f, 22f, 12f, 16f, 18f)
                    line(14f, 4f, 10f, 20f)
                }
                "terminal" -> {
                    box(2f, 4f, 20f, 16f)
                    line(6f, 9f, 9f, 12f, 6f, 15f)
                    line(12f, 15f, 18f, 15f)
                }
                "diff" -> {
                    box(3f, 3f, 18f, 18f)
                    line(7f, 8f, 13f, 8f)
                    line(10f, 5f, 10f, 11f)
                    line(11f, 16f, 17f, 16f)
                }
                "git" -> {
                    node(6f, 4f); node(6f, 20f); node(18f, 5f)
                    line(6f, 6f, 6f, 18f)
                    line(18f, 7f, 18f, 10f, 6f, 16f)
                }
                "agents" -> {
                    box(4f, 6f, 16f, 14f, 3f)
                    line(12f, 3f, 12f, 6f)
                    line(8f, 11f, 8f, 13f); line(16f, 11f, 16f, 13f)
                    line(9f, 17f, 15f, 17f)
                }
                "permissions" -> {
                    line(12f, 2f, 20f, 5f, 20f, 12f, 17f, 18f, 12f, 22f,
                        7f, 18f, 4f, 12f, 4f, 5f, 12f, 2f)
                    line(8f, 12f, 11f, 15f, 16f, 9f)
                }
                "remote" -> {
                    box(2f, 3f, 20f, 14f)
                    line(12f, 17f, 12f, 21f); line(7f, 21f, 17f, 21f)
                    line(7f, 10f, 17f, 10f); line(14f, 7f, 17f, 10f, 14f, 13f)
                }
                "voice" -> {
                    box(9f, 2f, 6f, 13f, 3f)
                    drawArc(color, 0f, 180f, false, Offset(5f, 5f), Size(14f, 14f), style = stroke)
                    line(12f, 19f, 12f, 22f); line(8f, 22f, 16f, 22f)
                }
                "snip" -> {
                    // Scissors: two finger-loop circles, each with a blade
                    // line converging on the same crossing point.
                    node(6f, 6f); node(6f, 18f)
                    line(6f, 6f, 17f, 15f)
                    line(6f, 18f, 17f, 9f)
                    line(17f, 9f, 21f, 6f)
                    line(17f, 15f, 21f, 18f)
                }
                "browser" -> {
                    box(2f, 4f, 20f, 16f, 3f)
                    line(2f, 9f, 22f, 9f)
                    node(5.5f, 6.5f); node(8.5f, 6.5f)
                }
                "settings" -> {
                    drawCircle(color, 3.5f, Offset(12f, 12f), style = stroke)
                    val spokes = 8
                    for (i in 0 until spokes) {
                        val angle = (2 * Math.PI * i / spokes).toFloat()
                        val cos = kotlin.math.cos(angle)
                        val sin = kotlin.math.sin(angle)
                        line(12f + cos * 7.5f, 12f + sin * 7.5f, 12f + cos * 10f, 12f + sin * 10f)
                    }
                }
            }
        }
    }
}
