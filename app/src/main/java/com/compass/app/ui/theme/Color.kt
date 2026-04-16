package com.compass.app.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.ui.graphics.Color

// Seed: deep nautical teal — reads as "compass"
val CompassSeed = Color(0xFF1E6F82)

// Directional accent colours used by CompassRose.
val NorthRed = Color(0xFFD6384C)
val NorthRedDark = Color(0xFFFF8A95)

// ----- Light scheme: teal-seeded palette -----
val md_light_primary = Color(0xFF006879)
val md_light_onPrimary = Color(0xFFFFFFFF)
val md_light_primaryContainer = Color(0xFFAAEDFF)
val md_light_onPrimaryContainer = Color(0xFF001F26)

val md_light_secondary = Color(0xFF4B6269)
val md_light_onSecondary = Color(0xFFFFFFFF)
val md_light_secondaryContainer = Color(0xFFCEE7EF)
val md_light_onSecondaryContainer = Color(0xFF061F24)

val md_light_tertiary = Color(0xFF565D7E)
val md_light_onTertiary = Color(0xFFFFFFFF)
val md_light_tertiaryContainer = Color(0xFFDDE1FF)
val md_light_onTertiaryContainer = Color(0xFF131A37)

val md_light_error = Color(0xFFBA1A1A)
val md_light_onError = Color(0xFFFFFFFF)
val md_light_errorContainer = Color(0xFFFFDAD6)
val md_light_onErrorContainer = Color(0xFF410002)

val md_light_background = Color(0xFFF5FAFC)
val md_light_onBackground = Color(0xFF171C1E)
val md_light_surface = Color(0xFFF5FAFC)
val md_light_onSurface = Color(0xFF171C1E)
val md_light_surfaceVariant = Color(0xFFDBE4E7)
val md_light_onSurfaceVariant = Color(0xFF3F484B)
val md_light_outline = Color(0xFF6F797B)
val md_light_outlineVariant = Color(0xFFBFC8CB)
val md_light_inverseSurface = Color(0xFF2B3133)
val md_light_inverseOnSurface = Color(0xFFECF1F3)
val md_light_inversePrimary = Color(0xFF58D5EE)
val md_light_surfaceTint = Color(0xFF006879)
val md_light_scrim = Color(0xFF000000)

// ----- Dark scheme: teal-seeded palette -----
val md_dark_primary = Color(0xFF58D5EE)
val md_dark_onPrimary = Color(0xFF003640)
val md_dark_primaryContainer = Color(0xFF004E5B)
val md_dark_onPrimaryContainer = Color(0xFFAAEDFF)

val md_dark_secondary = Color(0xFFB3CBD2)
val md_dark_onSecondary = Color(0xFF1D343A)
val md_dark_secondaryContainer = Color(0xFF344A51)
val md_dark_onSecondaryContainer = Color(0xFFCEE7EF)

val md_dark_tertiary = Color(0xFFBEC5EB)
val md_dark_onTertiary = Color(0xFF282F4D)
val md_dark_tertiaryContainer = Color(0xFF3F4665)
val md_dark_onTertiaryContainer = Color(0xFFDDE1FF)

val md_dark_error = Color(0xFFFFB4AB)
val md_dark_onError = Color(0xFF690005)
val md_dark_errorContainer = Color(0xFF93000A)
val md_dark_onErrorContainer = Color(0xFFFFDAD6)

val md_dark_background = Color(0xFF0F1416)
val md_dark_onBackground = Color(0xFFDEE3E5)
val md_dark_surface = Color(0xFF0F1416)
val md_dark_onSurface = Color(0xFFDEE3E5)
val md_dark_surfaceVariant = Color(0xFF3F484B)
val md_dark_onSurfaceVariant = Color(0xFFBFC8CB)
val md_dark_outline = Color(0xFF899295)
val md_dark_outlineVariant = Color(0xFF3F484B)
val md_dark_inverseSurface = Color(0xFFDEE3E5)
val md_dark_inverseOnSurface = Color(0xFF2B3133)
val md_dark_inversePrimary = Color(0xFF006879)
val md_dark_surfaceTint = Color(0xFF58D5EE)
val md_dark_scrim = Color(0xFF000000)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
val LightColorScheme = expressiveLightColorScheme().copy(
    primary = md_light_primary,
    onPrimary = md_light_onPrimary,
    primaryContainer = md_light_primaryContainer,
    onPrimaryContainer = md_light_onPrimaryContainer,
    secondary = md_light_secondary,
    onSecondary = md_light_onSecondary,
    secondaryContainer = md_light_secondaryContainer,
    onSecondaryContainer = md_light_onSecondaryContainer,
    tertiary = md_light_tertiary,
    onTertiary = md_light_onTertiary,
    tertiaryContainer = md_light_tertiaryContainer,
    onTertiaryContainer = md_light_onTertiaryContainer,
    error = md_light_error,
    onError = md_light_onError,
    errorContainer = md_light_errorContainer,
    onErrorContainer = md_light_onErrorContainer,
    background = md_light_background,
    onBackground = md_light_onBackground,
    surface = md_light_surface,
    onSurface = md_light_onSurface,
    surfaceVariant = md_light_surfaceVariant,
    onSurfaceVariant = md_light_onSurfaceVariant,
    outline = md_light_outline,
    outlineVariant = md_light_outlineVariant,
    inverseSurface = md_light_inverseSurface,
    inverseOnSurface = md_light_inverseOnSurface,
    inversePrimary = md_light_inversePrimary,
    surfaceTint = md_light_surfaceTint,
    scrim = md_light_scrim,
)

val DarkColorScheme = darkColorScheme(
    primary = md_dark_primary,
    onPrimary = md_dark_onPrimary,
    primaryContainer = md_dark_primaryContainer,
    onPrimaryContainer = md_dark_onPrimaryContainer,
    secondary = md_dark_secondary,
    onSecondary = md_dark_onSecondary,
    secondaryContainer = md_dark_secondaryContainer,
    onSecondaryContainer = md_dark_onSecondaryContainer,
    tertiary = md_dark_tertiary,
    onTertiary = md_dark_onTertiary,
    tertiaryContainer = md_dark_tertiaryContainer,
    onTertiaryContainer = md_dark_onTertiaryContainer,
    error = md_dark_error,
    onError = md_dark_onError,
    errorContainer = md_dark_errorContainer,
    onErrorContainer = md_dark_onErrorContainer,
    background = md_dark_background,
    onBackground = md_dark_onBackground,
    surface = md_dark_surface,
    onSurface = md_dark_onSurface,
    surfaceVariant = md_dark_surfaceVariant,
    onSurfaceVariant = md_dark_onSurfaceVariant,
    outline = md_dark_outline,
    outlineVariant = md_dark_outlineVariant,
    inverseSurface = md_dark_inverseSurface,
    inverseOnSurface = md_dark_inverseOnSurface,
    inversePrimary = md_dark_inversePrimary,
    surfaceTint = md_dark_surfaceTint,
    scrim = md_dark_scrim,
)
