package com.compass.app.ui.compass.components

import android.graphics.Matrix as AndroidMatrix
import android.graphics.Path as AndroidPath
import androidx.compose.animation.core.Animatable
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
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
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
    onTargetHitColor: Color = Color(0xFF2EB872),
) {
    val cumulativeAngle = remember { Animatable(0f) }
    LaunchedEffect(azimuthDegrees) {
        val target = unwrapAngle(cumulativeAngle.value, -azimuthDegrees)
        cumulativeAngle.animateTo(
            targetValue = target,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }

    // Morph between two truly-wavy M3 Expressive presets for the calibration cue
    // (Puffy ↔ SoftBurst — both have soft round bumps, never spiky petals, so the
    // shape always reads as a wavy RING around the inner rose, not as spikes).
    // Both shapes are `.normalized()` so bounds are (0,0)-(1,1), which lets
    // `polygonPathCentered` scale them to a consistent size.
    val baseShape = remember { MaterialShapes.Puffy.normalized() }
    val calibrationEnd = remember { MaterialShapes.SoftBurst.normalized() }
    val morph = remember { Morph(start = baseShape, end = calibrationEnd) }
    val morphTransition = rememberInfiniteTransition(label = "calibrationMorph")
    val morphProgress by morphTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (calibrating) 1f else 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "calibrationMorphProgress",
    )

    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer
    val surfaceContainerHigh = MaterialTheme.colorScheme.surfaceContainerHigh
    val outline = MaterialTheme.colorScheme.outlineVariant
    val needleNorth = if (isDark) NorthRedDark else NorthRed
    val needleSouth = MaterialTheme.colorScheme.surfaceContainerHighest

    val cardinalStyle = MaterialTheme.typography.headlineSmall
    val intercardinalStyle = MaterialTheme.typography.labelLarge
    val textMeasurer = rememberTextMeasurer()


    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            // Outer cookie now fills the canvas fully (minus a 2dp stroke margin).
            val outerRadius = minOf(w, h) / 2f - 2.dp.toPx()

            // Expressive wavy background: Puffy (resting) ↔ SoftBurst (calibrating).
            val discPath = if (calibrating) {
                morphPathCentered(morph, morphProgress, cx, cy, outerRadius, cumulativeAngle.value)
            } else {
                polygonPathCentered(baseShape, cx, cy, outerRadius, cumulativeAngle.value)
            }
            drawPath(path = discPath, color = surfaceContainerHigh)
            drawPath(
                path = discPath,
                color = outline,
                style = Stroke(width = 2.dp.toPx()),
            )

            // Inner rose disc — well under the shape's minimum concave dip so the
            // wavy ring is clearly visible all around, not just at the bumps.
            val innerRadius = outerRadius * 0.55f
            drawCircle(
                color = surfaceContainer,
                radius = innerRadius,
                center = Offset(cx, cy),
            )

            // Rotating rose: ticks + letters.
            rotate(degrees = cumulativeAngle.value, pivot = Offset(cx, cy)) {
                drawTicks(
                    centerX = cx,
                    centerY = cy,
                    outerRadius = innerRadius * 0.96f,
                    majorColor = onSurface,
                    minorColor = onSurfaceVariant,
                )
                drawCardinals(
                    textMeasurer = textMeasurer,
                    centerX = cx,
                    centerY = cy,
                    radius = innerRadius * 0.72f,
                    cardinalStyle = cardinalStyle,
                    intercardinalStyle = intercardinalStyle,
                    northColor = needleNorth,
                    otherColor = onSurface,
                    subColor = onSurfaceVariant,
                )

                // Target line — rotates with the rose so it stays locked to the cardinal
                // direction the user chose, regardless of the phone's orientation.
                if (targetAngle != null) {
                    drawTargetLine(
                        centerX = cx,
                        centerY = cy,
                        radius = outerRadius * 0.98f,
                        angleDeg = targetAngle,
                        color = onTargetHitColor,
                    )
                }
            }

            // Fixed needle.
            val needleLen = innerRadius * 0.92f
            val needleHalfWidth = outerRadius * 0.045f
            drawNeedle(
                centerX = cx,
                centerY = cy,
                length = needleLen,
                halfWidth = needleHalfWidth,
                northColor = needleNorth,
                southColor = needleSouth,
            )

            // Hub.
            drawCircle(color = primary, radius = outerRadius * 0.05f, center = Offset(cx, cy))
            drawCircle(color = onPrimary, radius = outerRadius * 0.02f, center = Offset(cx, cy))

            // Fixed top indicator triangle (outside the cookie).
            val tipY = cy - outerRadius - 6.dp.toPx()
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

/**
 * Center-and-scale a [RoundedPolygon] so that its tightest bounds are sized to fit
 * within a square of side `2 * radius` centered at `(cx, cy)`. Mirrors the Androidify
 * reference pattern for decorative MaterialShapes backdrops.
 */
private fun polygonPathCentered(
    polygon: RoundedPolygon,
    cx: Float,
    cy: Float,
    radius: Float,
    rotationDeg: Float,
): ComposePath {
    val bounds = polygon.calculateMaxBounds(FloatArray(4))
    // bounds layout: [minX, minY, maxX, maxY]
    val shapeCenterX = (bounds[0] + bounds[2]) / 2f
    val shapeCenterY = (bounds[1] + bounds[3]) / 2f
    val shapeHalfExtent = maxOf(
        (bounds[2] - bounds[0]) / 2f,
        (bounds[3] - bounds[1]) / 2f,
    ).coerceAtLeast(1e-4f)
    val scale = radius / shapeHalfExtent

    val androidPath = polygon.toPath(AndroidPath())
    val matrix = AndroidMatrix().apply {
        postTranslate(-shapeCenterX, -shapeCenterY)
        postScale(scale, scale)
        postRotate(rotationDeg)
        postTranslate(cx, cy)
    }
    androidPath.transform(matrix)
    return androidPath.asComposePath()
}

/**
 * Same centering behaviour for a [Morph]. The morph is assumed to have been built from
 * two already-`.normalized()` polygons, so its bounds are roughly `(0,0)-(1,1)` and we
 * can center at `(0.5, 0.5)` and scale by `2 * radius`.
 */
private fun morphPathCentered(
    morph: Morph,
    progress: Float,
    cx: Float,
    cy: Float,
    radius: Float,
    rotationDeg: Float,
): ComposePath {
    val composePath = morph.toPath(progress = progress, path = ComposePath())
    val matrix = AndroidMatrix().apply {
        postTranslate(-0.5f, -0.5f)
        postScale(2f * radius, 2f * radius)
        postRotate(rotationDeg)
        postTranslate(cx, cy)
    }
    val android = AndroidPath().apply {
        set(composePath.asAndroidPath())
        transform(matrix)
    }
    return android.asComposePath()
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

private fun DrawScope.drawCardinals(
    textMeasurer: TextMeasurer,
    centerX: Float,
    centerY: Float,
    radius: Float,
    cardinalStyle: TextStyle,
    intercardinalStyle: TextStyle,
    northColor: Color,
    otherColor: Color,
    subColor: Color,
) {
    data class Marker(val label: String, val angle: Float, val main: Boolean)
    val markers = listOf(
        Marker("N", 0f, true),
        Marker("NE", 45f, false),
        Marker("E", 90f, true),
        Marker("SE", 135f, false),
        Marker("S", 180f, true),
        Marker("SW", 225f, false),
        Marker("W", 270f, true),
        Marker("NW", 315f, false),
    )
    for (marker in markers) {
        val style = if (marker.main) cardinalStyle else intercardinalStyle
        val color = when {
            marker.label == "N" -> northColor
            marker.main -> otherColor
            else -> subColor
        }
        val layout = textMeasurer.measure(
            text = marker.label,
            style = style.copy(color = color, textAlign = TextAlign.Center),
        )
        val rad = Math.toRadians((marker.angle - 90.0))
        val x = centerX + (radius * cos(rad)).toFloat() - layout.size.width / 2f
        val y = centerY + (radius * sin(rad)).toFloat() - layout.size.height / 2f
        drawText(textLayoutResult = layout, topLeft = Offset(x, y))
    }
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
