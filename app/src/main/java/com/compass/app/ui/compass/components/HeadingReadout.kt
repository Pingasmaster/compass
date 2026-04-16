package com.compass.app.ui.compass.components

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.compass.app.domain.model.toCardinal
import com.compass.app.ui.theme.headingDegrees
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HeadingReadout(
    azimuthDegrees: Float,
    isTrueNorth: Boolean,
    declination: Float,
    modifier: Modifier = Modifier,
) {
    val heading = ((azimuthDegrees.roundToInt() % 360) + 360) % 360
    val cardinal = azimuthDegrees.toCardinal()
    val motionScheme = MaterialTheme.motionScheme
    val slowEffects = motionScheme.slowEffectsSpec<Float>()
    val fastSpatial = motionScheme.fastSpatialSpec<androidx.compose.ui.unit.IntOffset>()

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Degree number — animated slide-in on each integer change via AnimatedContent.
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center,
        ) {
            AnimatedContent(
                targetState = heading,
                transitionSpec = {
                    val up = targetState > initialState ||
                        (initialState == 359 && targetState == 0)
                    val direction = if (up) -1 else 1
                    (slideInVertically(animationSpec = fastSpatial) { it * direction } +
                        fadeIn(animationSpec = slowEffects))
                        .togetherWith(
                            slideOutVertically(animationSpec = fastSpatial) { -it * direction } +
                                fadeOut(animationSpec = slowEffects)
                        )
                },
                label = "headingDegreesAnim",
            ) { value ->
                Text(
                    text = "$value",
                    style = MaterialTheme.typography.headingDegrees,
                    color = LocalContentColor.current,
                    modifier = Modifier.widthIn(min = 168.dp),
                )
            }
            Text(
                text = "°",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }

        // Cardinal (N / NE / E / …) — crossfade + slide when it changes.
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

        val subtitle = if (isTrueNorth) {
            "True north · declination ${"%+.1f".format(declination)}°"
        } else {
            "Magnetic north"
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
