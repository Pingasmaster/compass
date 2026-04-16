package com.compass.app.ui.compass.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.compass.app.data.preferences.Responsiveness
import com.compass.app.domain.sensor.unwrapAngle
import com.compass.app.ui.theme.NorthRed
import com.compass.app.ui.theme.NorthRedDark
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.graphics.Path as ComposePath

/**
 * Rotating compass rose. The outer ring is [MaterialShapes.Cookie12Sided] — the M3
 * Expressive preset polygon. Tick marks and cardinal letters ride on an inner rotating
 * disc; a fixed red needle and top triangle indicate the current heading.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CompassRose(
    azimuthDegrees: Float,
    modifier: Modifier = Modifier,
    isDark: Boolean,
    calibrating: Boolean = false,
    targetAngle: Float? = null,
    targetColor: Color = MaterialTheme.colorScheme.tertiary,
    responsiveness: Responsiveness = Responsiveness.NORMAL,
) {
    val cumulativeAngle = remember { Animatable(0f) }
    val animSpec: AnimationSpec<Float> = remember(responsiveness) { responsiveness.toSpringSpec() }
    // Drive the animation from a snapshotFlow instead of keying LaunchedEffect on the
    // rapidly-changing Float — avoids re-launching the coroutine on every sensor tick
    // and lets Animatable retarget smoothly without allocating new Jobs.
    LaunchedEffect(Unit) {
        snapshotFlow { azimuthDegrees }
            .collectLatest { target ->
                cumulativeAngle.animateTo(
                    targetValue = unwrapAngle(cumulativeAngle.value, -target),
                    animationSpec = animSpec,
                )
            }
    }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val errorColor = MaterialTheme.colorScheme.error
    val needleNorth = if (isDark) NorthRedDark else NorthRed
    val needleSouth = MaterialTheme.colorScheme.surfaceContainerHighest

    // rememberInfiniteTransition ticks even when its animated value is constant, so
    // keep it off the composition entirely while idle.
    val ringColor = if (calibrating) {
        pulsingRingColor(base = outlineVariant, pulse = errorColor)
    } else {
        outlineVariant
    }

    val cardinalStyle = MaterialTheme.typography.headlineSmall
    val intercardinalStyle = MaterialTheme.typography.labelLarge
    val textMeasurer = rememberTextMeasurer()
    // Pre-measure the eight cardinal/intercardinal labels once per colour+style pair —
    // the rose redraws at sensor rate (~50 Hz), so doing it inside draw was cheap but
    // not free.
    val cardinalLayouts = remember(
        cardinalStyle, intercardinalStyle, textMeasurer,
        needleNorth, onSurface, onSurfaceVariant,
    ) {
        CardinalMarkers.map { marker ->
            val style = if (marker.main) cardinalStyle else intercardinalStyle
            val color = when {
                marker.label == "N" -> needleNorth
                marker.main -> onSurface
                else -> onSurfaceVariant
            }
            marker to textMeasurer.measure(
                text = marker.label,
                style = style.copy(color = color, textAlign = TextAlign.Center),
            )
        }
    }


    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            // Rose fills the canvas; reserve a small margin for the fixed heading pointer.
            val roseRadius = minOf(w, h) / 2f - 14.dp.toPx()

            // Rose base disc with a ring outline. When calibrating, the ring pulses
            // toward the error colour — no extra backdrop shape.
            drawCircle(
                color = surfaceContainer,
                radius = roseRadius,
                center = Offset(cx, cy),
            )
            drawCircle(
                color = ringColor,
                radius = roseRadius,
                center = Offset(cx, cy),
                style = Stroke(width = 2.dp.toPx()),
            )

            // Rotating rose: ticks + letters.
            rotate(degrees = cumulativeAngle.value, pivot = Offset(cx, cy)) {
                drawTicks(
                    centerX = cx,
                    centerY = cy,
                    outerRadius = roseRadius * 0.99f,
                    majorColor = onSurface,
                    minorColor = onSurfaceVariant,
                )
                drawCardinals(
                    layouts = cardinalLayouts,
                    centerX = cx,
                    centerY = cy,
                    radius = roseRadius * 0.82f,
                )

                // Target line — rotates with the rose so it stays locked to the cardinal
                // direction the user chose, regardless of the phone's orientation.
                if (targetAngle != null) {
                    drawTargetLine(
                        centerX = cx,
                        centerY = cy,
                        radius = roseRadius * 0.90f,
                        angleDeg = targetAngle,
                        color = targetColor,
                    )
                }
            }

            // Fixed needle.
            val needleLen = roseRadius * 0.70f
            val needleHalfWidth = roseRadius * 0.045f
            drawNeedle(
                centerX = cx,
                centerY = cy,
                length = needleLen,
                halfWidth = needleHalfWidth,
                northColor = needleNorth,
                southColor = needleSouth,
            )

            // Hub.
            drawCircle(color = primary, radius = roseRadius * 0.045f, center = Offset(cx, cy))
            drawCircle(color = onPrimary, radius = roseRadius * 0.018f, center = Offset(cx, cy))

            // Fixed top indicator triangle (outside the rose, points at current heading).
            val tipY = cy - roseRadius - 4.dp.toPx()
            val trianglePath = ComposePath().apply {
                moveTo(cx, tipY - 10.dp.toPx())
                lineTo(cx - 10.dp.toPx(), tipY + 8.dp.toPx())
                lineTo(cx + 10.dp.toPx(), tipY + 8.dp.toPx())
                close()
            }
            drawPath(trianglePath, color = primary)
        }
    }
}


private fun Responsiveness.toSpringSpec(): AnimationSpec<Float> = when (this) {
    Responsiveness.SLOWEST -> spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = 30f,
    )
    Responsiveness.SLOW -> spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = 80f,
    )
    Responsiveness.NORMAL -> spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow,
    )
    Responsiveness.FAST -> spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
    Responsiveness.FASTEST -> spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium,
    )
}

private fun DrawScope.drawTargetLine(
    centerX: Float,
    centerY: Float,
    radius: Float,
    angleDeg: Float,
    color: Color,
) {
    // angle 0° points north (up); x-axis in screen coords goes right.
    rotate(degrees = angleDeg, pivot = Offset(centerX, centerY)) {
        drawLine(
            color = color.copy(alpha = 0.95f),
            start = Offset(centerX, centerY),
            end = Offset(centerX, centerY - radius),
            strokeWidth = 4.dp.toPx(),
            cap = StrokeCap.Round,
        )
        // Small arrowhead at the tip for directionality.
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

private fun DrawScope.drawTicks(
    centerX: Float,
    centerY: Float,
    outerRadius: Float,
    majorColor: Color,
    minorColor: Color,
) {
    val majorLen = outerRadius * 0.12f
    val minorLen = outerRadius * 0.055f
    for (i in 0 until 72) {
        val angleDeg = i * 5f
        val isMajor = angleDeg % 15f == 0f
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

internal data class CardinalMarker(val label: String, val angle: Float, val main: Boolean)

internal val CardinalMarkers: List<CardinalMarker> = listOf(
    CardinalMarker("N", 0f, true),
    CardinalMarker("NE", 45f, false),
    CardinalMarker("E", 90f, true),
    CardinalMarker("SE", 135f, false),
    CardinalMarker("S", 180f, true),
    CardinalMarker("SW", 225f, false),
    CardinalMarker("W", 270f, true),
    CardinalMarker("NW", 315f, false),
)

private fun DrawScope.drawCardinals(
    layouts: List<Pair<CardinalMarker, androidx.compose.ui.text.TextLayoutResult>>,
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

@Composable
private fun pulsingRingColor(base: Color, pulse: Color): Color {
    val transition = rememberInfiniteTransition(label = "calibrationPulse")
    val alpha by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "calibrationPulseAlpha",
    )
    return androidx.compose.ui.graphics.lerp(base, pulse, alpha)
}

private fun DrawScope.drawNeedle(
    centerX: Float,
    centerY: Float,
    length: Float,
    halfWidth: Float,
    northColor: Color,
    southColor: Color,
) {
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
