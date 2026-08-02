package com.compass.app.ui.compass.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.graphics.Path as ComposePath

internal fun DrawScope.roseRadiusPx(): Float = minOf(size.width, size.height) / 2f - 14.dp.toPx()

internal fun DrawScope.drawTargetLine(centerX: Float, centerY: Float, radius: Float, angleDeg: Float, color: Color) {
    rotate(degrees = angleDeg, pivot = Offset(centerX, centerY)) {
        drawLine(
            color = color.copy(alpha = 0.95f),
            start = Offset(centerX, centerY),
            end = Offset(centerX, centerY - radius),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
        )
        val tip = Offset(centerX, centerY - radius)
        val path = ComposePath().apply {
            moveTo(tip.x, tip.y - 4.dp.toPx())
            lineTo(tip.x - 7.dp.toPx(), tip.y + 6.dp.toPx())
            lineTo(tip.x + 7.dp.toPx(), tip.y + 6.dp.toPx())
            close()
        }
        drawPath(path, color = color)
    }
}

internal fun DrawScope.drawTicks(centerX: Float, centerY: Float, outerRadius: Float, majorColor: Color, minorColor: Color) {
    val majorLen = outerRadius * 0.12f
    val minorLen = outerRadius * 0.055f
    for (i in 0 until TICK_COUNT) {
        val angleDeg = i * TICK_STEP_DEG
        val isMajor = angleDeg % MAJOR_TICK_STEP_DEG == 0f
        val len = if (isMajor) majorLen else minorLen
        val color = if (isMajor) majorColor else minorColor
        val strokeWidth = if (isMajor) 3.dp.toPx() else 1.5.dp.toPx()
        rotate(degrees = angleDeg, pivot = Offset(centerX, centerY)) {
            drawLine(
                color = color,
                start = Offset(centerX, centerY - outerRadius),
                end = Offset(centerX, centerY - outerRadius + len),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

internal fun DrawScope.drawCardinals(
    layouts: List<Pair<CardinalMarker, TextLayoutResult>>,
    centerX: Float,
    centerY: Float,
    radius: Float,
) {
    for ((marker, layout) in layouts) {
        val rad = Math.toRadians((marker.angle - 90.0))
        val x = centerX + (radius * cos(rad)).toFloat() - layout.size.width / 2f
        val y = centerY + (radius * sin(rad)).toFloat() - layout.size.height / 2f
        drawText(textLayoutResult = layout, topLeft = Offset(x, y))
    }
}

internal fun DrawScope.drawNeedle(centerX: Float, centerY: Float, length: Float, halfWidth: Float, northColor: Color, southColor: Color) {
    val northPath = ComposePath().apply {
        moveTo(centerX, centerY - length)
        lineTo(centerX - halfWidth, centerY)
        lineTo(centerX + halfWidth, centerY)
        close()
    }
    drawPath(northPath, color = northColor)

    val southPath = ComposePath().apply {
        moveTo(centerX, centerY + length)
        lineTo(centerX - halfWidth, centerY)
        lineTo(centerX + halfWidth, centerY)
        close()
    }
    drawPath(southPath, color = southColor)
}

private const val TICK_COUNT = 72
private const val TICK_STEP_DEG = 5f
private const val MAJOR_TICK_STEP_DEG = 15f
