package com.compass.app.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Compass theme.
 *
 * - Dynamic color on API 31+ (the compass app's minSdk), with the seeded
 *   fallback scheme when dynamic is disabled.
 * - Colours animate on theme/scheme change with a two-speed spec:
 *   accent slots fade fast, surface/background slots fade slowly so a
 *   light ↔ dark flip doesn't flash.
 * - OLED-black variant blends existing surfaces toward pure black so the
 *   underlying seed/dynamic hue isn't lost.
 * - [motionScheme] is overridable in case a caller wants to A/B a different
 *   spec (e.g. `MotionScheme.standard()`).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CompassTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    oledBlack: Boolean = false,
    motionScheme: MotionScheme = MotionScheme.expressive(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val finalColorScheme = if (oledBlack && darkTheme) {
        colorScheme.toOledBlack()
    } else {
        colorScheme
    }

    val accentSpec = remember(motionScheme) { motionScheme.fastEffectsSpec<Color>() }
    val surfaceSpec = remember(motionScheme) { motionScheme.slowEffectsSpec<Color>() }

    MaterialExpressiveTheme(
        colorScheme = finalColorScheme.animated(accentSpec = accentSpec, surfaceSpec = surfaceSpec),
        typography = CompassTypography,
        motionScheme = motionScheme,
        shapes = Shapes(),
        content = content,
    )
}

/**
 * Push each surface slot a fixed percentage toward black so dynamic-colour
 * tinting survives the "OLED dark" preference instead of being replaced by
 * neutral greys.
 */
private fun ColorScheme.toOledBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = surfaceBright.lerpToBlack(0.92f),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = surfaceContainerLow.lerpToBlack(0.95f),
    surfaceContainer = surfaceContainer.lerpToBlack(0.90f),
    surfaceContainerHigh = surfaceContainerHigh.lerpToBlack(0.80f),
    surfaceContainerHighest = surfaceContainerHighest.lerpToBlack(0.70f),
)

private fun Color.lerpToBlack(amount: Float): Color =
    androidx.compose.ui.graphics.lerp(this, Color.Black, amount.coerceIn(0f, 1f))

@Composable
private fun ColorScheme.animated(
    accentSpec: AnimationSpec<Color>,
    surfaceSpec: AnimationSpec<Color>,
): ColorScheme {
    return copy(
        primary = animateColorAsState(primary, accentSpec).value,
        onPrimary = animateColorAsState(onPrimary, accentSpec).value,
        primaryContainer = animateColorAsState(primaryContainer, accentSpec).value,
        onPrimaryContainer = animateColorAsState(onPrimaryContainer, accentSpec).value,
        inversePrimary = animateColorAsState(inversePrimary, accentSpec).value,
        secondary = animateColorAsState(secondary, accentSpec).value,
        onSecondary = animateColorAsState(onSecondary, accentSpec).value,
        secondaryContainer = animateColorAsState(secondaryContainer, accentSpec).value,
        onSecondaryContainer = animateColorAsState(onSecondaryContainer, accentSpec).value,
        tertiary = animateColorAsState(tertiary, accentSpec).value,
        onTertiary = animateColorAsState(onTertiary, accentSpec).value,
        tertiaryContainer = animateColorAsState(tertiaryContainer, accentSpec).value,
        onTertiaryContainer = animateColorAsState(onTertiaryContainer, accentSpec).value,
        // Background / surface slots use the slow spec to avoid theme-flip flash.
        background = animateColorAsState(background, surfaceSpec).value,
        onBackground = animateColorAsState(onBackground, surfaceSpec).value,
        surface = animateColorAsState(surface, surfaceSpec).value,
        onSurface = animateColorAsState(onSurface, surfaceSpec).value,
        surfaceVariant = animateColorAsState(surfaceVariant, surfaceSpec).value,
        onSurfaceVariant = animateColorAsState(onSurfaceVariant, surfaceSpec).value,
        surfaceTint = animateColorAsState(surfaceTint, surfaceSpec).value,
        inverseSurface = animateColorAsState(inverseSurface, surfaceSpec).value,
        inverseOnSurface = animateColorAsState(inverseOnSurface, surfaceSpec).value,
        error = animateColorAsState(error, accentSpec).value,
        onError = animateColorAsState(onError, accentSpec).value,
        errorContainer = animateColorAsState(errorContainer, accentSpec).value,
        onErrorContainer = animateColorAsState(onErrorContainer, accentSpec).value,
        outline = animateColorAsState(outline, surfaceSpec).value,
        outlineVariant = animateColorAsState(outlineVariant, surfaceSpec).value,
        scrim = animateColorAsState(scrim, surfaceSpec).value,
        surfaceBright = animateColorAsState(surfaceBright, surfaceSpec).value,
        surfaceDim = animateColorAsState(surfaceDim, surfaceSpec).value,
        surfaceContainer = animateColorAsState(surfaceContainer, surfaceSpec).value,
        surfaceContainerHigh = animateColorAsState(surfaceContainerHigh, surfaceSpec).value,
        surfaceContainerHighest = animateColorAsState(surfaceContainerHighest, surfaceSpec).value,
        surfaceContainerLow = animateColorAsState(surfaceContainerLow, surfaceSpec).value,
        surfaceContainerLowest = animateColorAsState(surfaceContainerLowest, surfaceSpec).value,
    )
}
