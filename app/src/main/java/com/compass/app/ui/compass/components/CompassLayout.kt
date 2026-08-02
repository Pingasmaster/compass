package com.compass.app.ui.compass.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.compass.app.data.preferences.Responsiveness
import com.compass.app.domain.model.CompassAccuracy

/**
 * Soft upper bound for the rose. Phones rarely hit this (width is smaller); tablets
 * and desktop windows would otherwise grow a square that dominates or clips the pane.
 */
private val MaxRoseSize = 400.dp

/**
 * Portrait / tall windows: rose stacked above the heading readout.
 * The rose lives in a weighted [BoxWithConstraints] so its square side is
 * `min(availableWidth, availableHeight, [MaxRoseSize])` - never taller than the slot.
 */
@Composable
fun SinglePaneCompassBody(
    roseDescription: String,
    azimuthDegrees: Float,
    isDark: Boolean,
    calibrating: Boolean,
    targetAngle: Float?,
    responsiveness: Responsiveness,
    isTrueNorth: Boolean,
    declination: Float,
    accuracy: CompassAccuracy,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            AdaptiveCompassRose(
                roseDescription = roseDescription,
                azimuthDegrees = azimuthDegrees,
                isDark = isDark,
                calibrating = calibrating,
                targetAngle = targetAngle,
                responsiveness = responsiveness,
            )
        }

        HeadingReadout(
            azimuthDegrees = azimuthDegrees,
            isTrueNorth = isTrueNorth,
            declination = declination,
            targetAngle = targetAngle,
        )

        CalibrationBanner(accuracy = accuracy)

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Wide + not-tall windows (phone landscape, most tablet landscape, split-screen):
 * rose and readout sit side by side so neither clips. Matches M3 Adaptive guidance
 * to switch content-level layout at window size class breakpoints rather than by
 * device type.
 */
@Composable
fun DualPaneCompassBody(
    roseDescription: String,
    azimuthDegrees: Float,
    isDark: Boolean,
    calibrating: Boolean,
    targetAngle: Float?,
    responsiveness: Responsiveness,
    isTrueNorth: Boolean,
    declination: Float,
    accuracy: CompassAccuracy,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            AdaptiveCompassRose(
                roseDescription = roseDescription,
                azimuthDegrees = azimuthDegrees,
                isDark = isDark,
                calibrating = calibrating,
                targetAngle = targetAngle,
                responsiveness = responsiveness,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 420.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeadingReadout(
                azimuthDegrees = azimuthDegrees,
                isTrueNorth = isTrueNorth,
                declination = declination,
                targetAngle = targetAngle,
            )
            CalibrationBanner(accuracy = accuracy)
        }
    }
}

/**
 * Sizes the rose to the space actually offered by the parent (not the raw window).
 * Nested composables should use [BoxWithConstraints] rather than window metrics so
 * padding, app bars, and dual-pane splits are respected.
 */
@Composable
private fun AdaptiveCompassRose(
    roseDescription: String,
    azimuthDegrees: Float,
    isDark: Boolean,
    calibrating: Boolean,
    targetAngle: Float?,
    responsiveness: Responsiveness,
    maxSize: Dp = MaxRoseSize,
) {
    BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        val side = min(min(maxWidth, maxHeight), maxSize)
        Box(
            modifier = Modifier
                .size(side)
                .semantics(mergeDescendants = true) {
                    contentDescription = roseDescription
                },
        ) {
            CompassRose(
                azimuthDegrees = azimuthDegrees,
                isDark = isDark,
                calibrating = calibrating,
                targetAngle = targetAngle,
                responsiveness = responsiveness,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
