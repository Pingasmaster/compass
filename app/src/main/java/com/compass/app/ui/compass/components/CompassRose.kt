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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.compass.app.data.preferences.Responsiveness
import com.compass.app.domain.sensor.unwrapAngle
import com.compass.app.ui.theme.NorthRed
import com.compass.app.ui.theme.NorthRedDark
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.graphics.Path as ComposePath

/**
 * Rotating compass rose. Tick marks and cardinal letters ride on an inner rotating
 * disc; a fixed red needle and top triangle indicate the current heading.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CompassRose(
    azimuthDegrees: Float,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    calibrating: Boolean = false,
    targetAngle: Float? = null,
    targetColor: Color = MaterialTheme.colorScheme.tertiary,
    responsiveness: Responsiveness = Responsiveness.NORMAL,
) {
    val cumulativeAngle = remember { Animatable(0f) }
    val animSpec: AnimationSpec<Float> = remember(responsiveness) { responsiveness.toSpringSpec() }
    // rememberUpdatedState + snapshotFlow so azimuth changes restart the spring without
    // capturing a stale Float; keyed on animSpec so Settings responsiveness changes apply.
    val latestAzimuth by rememberUpdatedState(azimuthDegrees)
    LaunchedEffect(animSpec) {
        snapshotFlow { latestAzimuth }
            .collectLatest { target ->
                cumulativeAngle.animateTo(
                    targetValue = unwrapAngle(cumulativeAngle.value, -target),
                    animationSpec = animSpec,
                )
            }
    }

    val colors = rememberRoseColors(isDark = isDark)
    val ringColor = if (calibrating) {
        pulsingRingColor(base = colors.outlineVariant, pulse = colors.error)
    } else {
        colors.outlineVariant
    }
    val cardinalLayouts = rememberCardinalLayouts(colors = colors)

    Box(modifier = modifier) {
        RoseBaseLayer(
            surfaceContainer = colors.surfaceContainer,
            ringColor = ringColor,
        )
        RoseRotatingLayer(
            rotationDegrees = { cumulativeAngle.value },
            layouts = cardinalLayouts,
            onSurface = colors.onSurface,
            onSurfaceVariant = colors.onSurfaceVariant,
            targetAngle = targetAngle,
            targetColor = targetColor,
        )
        RoseNeedleLayer(
            needleNorth = colors.needleNorth,
            needleSouth = colors.needleSouth,
            primary = colors.primary,
            onPrimary = colors.onPrimary,
        )
    }
}

@Immutable
private data class RoseColors(
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val primary: Color,
    val onPrimary: Color,
    val surfaceContainer: Color,
    val outlineVariant: Color,
    val error: Color,
    val needleNorth: Color,
    val needleSouth: Color,
)

@Composable
private fun rememberRoseColors(isDark: Boolean): RoseColors {
    val scheme = MaterialTheme.colorScheme
    val needleNorth = if (isDark) NorthRedDark else NorthRed
    return remember(
        scheme.onSurface,
        scheme.onSurfaceVariant,
        scheme.primary,
        scheme.onPrimary,
        scheme.surfaceContainer,
        scheme.outlineVariant,
        scheme.error,
        scheme.surfaceContainerHighest,
        needleNorth,
    ) {
        RoseColors(
            onSurface = scheme.onSurface,
            onSurfaceVariant = scheme.onSurfaceVariant,
            primary = scheme.primary,
            onPrimary = scheme.onPrimary,
            surfaceContainer = scheme.surfaceContainer,
            outlineVariant = scheme.outlineVariant,
            error = scheme.error,
            needleNorth = needleNorth,
            needleSouth = scheme.surfaceContainerHighest,
        )
    }
}

@Composable
private fun rememberCardinalLayouts(colors: RoseColors): List<Pair<CardinalMarker, TextLayoutResult>> {
    val cardinalStyle = MaterialTheme.typography.headlineSmall
    val intercardinalStyle = MaterialTheme.typography.labelLarge
    val textMeasurer = rememberTextMeasurer()
    return remember(
        cardinalStyle,
        intercardinalStyle,
        textMeasurer,
        colors.needleNorth,
        colors.onSurface,
        colors.onSurfaceVariant,
    ) {
        CardinalMarkers.map { marker ->
            val style = if (marker.main) cardinalStyle else intercardinalStyle
            val color = when {
                marker.label == "N" -> colors.needleNorth
                marker.main -> colors.onSurface
                else -> colors.onSurfaceVariant
            }
            marker to textMeasurer.measure(
                text = marker.label,
                style = style.copy(color = color, textAlign = TextAlign.Center),
            )
        }
    }
}

@Composable
private fun RoseBaseLayer(surfaceContainer: Color, ringColor: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val roseRadius = roseRadiusPx()
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color = surfaceContainer, radius = roseRadius, center = center)
        drawCircle(
            color = ringColor,
            radius = roseRadius,
            center = center,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

@Composable
private fun RoseRotatingLayer(
    rotationDegrees: () -> Float,
    layouts: List<Pair<CardinalMarker, TextLayoutResult>>,
    onSurface: Color,
    onSurfaceVariant: Color,
    targetAngle: Float?,
    targetColor: Color,
) {
    // graphicsLayer reads rotation so spring frames only re-apply a transform.
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { rotationZ = rotationDegrees() },
    ) {
        val roseRadius = roseRadiusPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawTicks(
            centerX = cx,
            centerY = cy,
            outerRadius = roseRadius * 0.99f,
            majorColor = onSurface,
            minorColor = onSurfaceVariant,
        )
        drawCardinals(layouts = layouts, centerX = cx, centerY = cy, radius = roseRadius * 0.82f)
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
}

@Composable
private fun RoseNeedleLayer(needleNorth: Color, needleSouth: Color, primary: Color, onPrimary: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val roseRadius = roseRadiusPx()
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawNeedle(
            centerX = cx,
            centerY = cy,
            length = roseRadius * 0.70f,
            halfWidth = roseRadius * 0.045f,
            northColor = needleNorth,
            southColor = needleSouth,
        )
        drawCircle(color = primary, radius = roseRadius * 0.045f, center = Offset(cx, cy))
        drawCircle(color = onPrimary, radius = roseRadius * 0.018f, center = Offset(cx, cy))
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

@Immutable
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
