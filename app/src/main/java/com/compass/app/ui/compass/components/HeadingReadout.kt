package com.compass.app.ui.compass.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.compass.app.domain.model.toCardinal
import com.compass.app.domain.sensor.unwrapAngle
import com.compass.app.ui.theme.headingDegrees
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HeadingReadout(
    azimuthDegrees: Float,
    isTrueNorth: Boolean,
    declination: Float,
    modifier: Modifier = Modifier,
    targetAngle: Float? = null,
    targetHitColor: Color = MaterialTheme.colorScheme.tertiary,
) {
    // Unwrap to a cumulative angle so animateFloatAsState takes the shortest path
    // across the 0°/360° seam; then normalise back to [0,360) for display.
    val cumulative = remember { androidx.compose.runtime.mutableFloatStateOf(azimuthDegrees) }
    cumulative.floatValue = unwrapAngle(cumulative.floatValue, azimuthDegrees)

    val motionScheme = MaterialTheme.motionScheme
    val animated by animateFloatAsState(
        targetValue = cumulative.floatValue,
        animationSpec = motionScheme.defaultSpatialSpec(),
        label = "headingSmoothed",
    )
    val display = ((animated.roundToInt() % 360) + 360) % 360
    val cardinal = animated.toCardinal()

    // Proximity factor: 1f at target, 0f at ≥10° off. Below-threshold angles tint
    // the heading number toward targetHitColor via color lerp.
    val defaultHeadingColor = LocalContentColor.current
    val proximity = targetAngle?.let { t ->
        val delta = shortestAngularDiff(animated, t)
        (1f - (abs(delta) / 10f)).coerceIn(0f, 1f)
    } ?: 0f
    val headingColor = if (targetAngle == null) defaultHeadingColor
        else lerp(defaultHeadingColor, targetHitColor, proximity)

    val slowEffects = motionScheme.slowEffectsSpec<Float>()
    val fastSpatial = motionScheme.fastSpatialSpec<IntOffset>()

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Plain integer readout — the underlying float is motion-scheme-animated
        // so digits change smoothly without per-change slide animations flickering.
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "$display",
                style = MaterialTheme.typography.headingDegrees,
                color = headingColor,
                modifier = Modifier.widthIn(min = 168.dp),
            )
            Text(
                text = "°",
                style = MaterialTheme.typography.displayMedium,
                color = if (targetAngle == null) MaterialTheme.colorScheme.primary
                    else lerp(MaterialTheme.colorScheme.primary, targetHitColor, proximity),
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }

        // Cardinal label jumps discretely every ~22.5° — AnimatedContent fits here
        // because changes are rare enough that the slide/fade finishes before the next.
        AnimatedContent(
            targetState = cardinal,
            transitionSpec = {
                (fadeIn(animationSpec = slowEffects) + slideInVertically(animationSpec = fastSpatial) { it / 3 })
                    .togetherWith(
                        fadeOut(animationSpec = slowEffects) +
                            slideOutVertically(animationSpec = fastSpatial) { -it / 3 }
                    )
            },
            label = "cardinalAnim",
        ) { value ->
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        val baseSubtitle = if (isTrueNorth) {
            "True north · declination ${"%+.1f".format(declination)}°"
        } else {
            "Magnetic north"
        }
        val subtitle = if (targetAngle != null) {
            val normalisedTarget = ((targetAngle.toInt() % 360) + 360) % 360
            "Target ${normalisedTarget}° · $baseSubtitle"
        } else {
            baseSubtitle
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private fun shortestAngularDiff(from: Float, to: Float): Float {
    val diff = ((to - from) % 360f + 540f) % 360f - 180f
    return diff
}
