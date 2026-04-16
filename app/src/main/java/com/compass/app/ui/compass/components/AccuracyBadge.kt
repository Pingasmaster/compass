package com.compass.app.ui.compass.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExploreOff
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.compass.app.R
import com.compass.app.domain.model.CompassAccuracy

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CalibrationBanner(
    accuracy: CompassAccuracy,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = accuracy.needsCalibration,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // M3 Expressive LoadingIndicator — polygon sequence that morphs while spinning.
                ContainedLoadingIndicator(
                    modifier = Modifier.size(44.dp),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    indicatorColor = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.calibration_banner_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.calibration_banner_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
fun AccuracyChip(
    accuracy: CompassAccuracy,
    hasSensor: Boolean,
    modifier: Modifier = Modifier,
) {
    val (labelRes, container, onContainer) = when {
        !hasSensor -> Triple(
            R.string.accuracy_no_sensor,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        accuracy == CompassAccuracy.HIGH -> Triple(
            R.string.accuracy_high,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        accuracy == CompassAccuracy.MEDIUM -> Triple(
            R.string.accuracy_medium,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
        )
        accuracy == CompassAccuracy.LOW -> Triple(
            R.string.accuracy_low,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        accuracy == CompassAccuracy.UNRELIABLE -> Triple(
            R.string.accuracy_unreliable,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        else -> Triple(
            R.string.accuracy_unknown,
            MaterialTheme.colorScheme.surfaceContainerHigh,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Surface(
        color = container,
        contentColor = onContainer,
        shape = CircleShape,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!hasSensor) {
                Icon(
                    imageVector = Icons.Rounded.ExploreOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
            } else {
                AccuracyDot(color = onContainer.copy(alpha = 0.9f))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun AccuracyDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "accuracyDot")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "accuracyDotAlpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha)),
    )
}
