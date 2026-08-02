package com.compass.app.ui.compass

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.compass.app.data.preferences.Responsiveness
import com.compass.app.data.preferences.ThemeMode
import com.compass.app.data.preferences.UserPreferences
import com.compass.app.ui.settings.SettingsSheet
import kotlinx.coroutines.launch

@Composable
internal fun CompassTrueNorthPromptEffect(prefs: UserPreferences, context: Context, launcher: ActivityResultLauncher<String>) {
    // Ask once on first launch so True North works without digging into settings.
    // After the system dialog has been shown once, never auto-prompt again.
    val locationPrompted by prefs.locationPrompted.collectAsStateWithLifecycle(initialValue = true)
    LaunchedEffect(locationPrompted) {
        if (locationPrompted) return@LaunchedEffect
        if (hasCoarseLocationPermission(context)) {
            prefs.setLocationPrompted(true)
        } else {
            launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
}

@Composable
internal fun CompassSettingsHost(
    prefs: UserPreferences,
    trueNorth: Boolean,
    responsiveness: Responsiveness,
    context: Context,
    locationPermissionLauncher: ActivityResultLauncher<String>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val themeMode by prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val dynamicColor by prefs.dynamicColorEnabled.collectAsStateWithLifecycle(initialValue = true)
    val oledBlack by prefs.oledBlackEnabled.collectAsStateWithLifecycle(initialValue = false)
    SettingsSheet(
        themeMode = themeMode,
        dynamicColor = dynamicColor,
        oledBlack = oledBlack,
        trueNorth = trueNorth,
        responsiveness = responsiveness,
        onThemeChange = { scope.launch { prefs.setThemeMode(it) } },
        onDynamicColorChange = { scope.launch { prefs.setDynamicColor(it) } },
        onOledBlackChange = { scope.launch { prefs.setOledBlack(it) } },
        onTrueNorthChange = { enabled ->
            if (enabled && !hasCoarseLocationPermission(context)) {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            } else {
                scope.launch { prefs.setTrueNorth(enabled) }
            }
        },
        onResponsivenessChange = { scope.launch { prefs.setResponsiveness(it) } },
        onDismiss = onDismiss,
    )
}

internal fun hasCoarseLocationPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
