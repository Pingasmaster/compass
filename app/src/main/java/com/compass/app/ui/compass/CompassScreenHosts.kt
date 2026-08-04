package com.compass.app.ui.compass

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.compass.app.data.preferences.Responsiveness
import com.compass.app.data.preferences.ThemeMode
import com.compass.app.data.preferences.UserPreferences
import com.compass.app.ui.settings.SettingsSheet

@Composable
internal fun CompassTrueNorthPromptEffect(
    prefs: UserPreferences,
    context: Context,
    launcher: ActivityResultLauncher<String>,
    onSkipPrompt: () -> Unit,
) {
    // Ask once on first launch so True North works without digging into settings.
    // After the system dialog has been shown once, never auto-prompt again.
    // onSkipPrompt records "prompted" when permission is already granted.
    val locationPrompted by prefs.locationPrompted.collectAsStateWithLifecycle(initialValue = true)
    val currentOnSkipPrompt by rememberUpdatedState(onSkipPrompt)
    LaunchedEffect(locationPrompted) {
        if (locationPrompted) return@LaunchedEffect
        if (hasCoarseLocationPermission(context)) {
            currentOnSkipPrompt()
        } else {
            launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
}

@Composable
internal fun CompassSettingsHost(
    prefs: UserPreferences,
    actions: SettingsActions,
    trueNorth: Boolean,
    responsiveness: Responsiveness,
    context: Context,
    locationPermissionLauncher: ActivityResultLauncher<String>,
    onDismiss: () -> Unit,
) {
    val themeMode by prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val dynamicColor by prefs.dynamicColorEnabled.collectAsStateWithLifecycle(initialValue = true)
    val oledBlack by prefs.oledBlackEnabled.collectAsStateWithLifecycle(initialValue = false)
    // Writes go through SettingsActions (viewModelScope) instead of a
    // rememberCoroutineScope: this host leaves composition the moment the
    // sheet is dismissed, which would cancel any in-flight DataStore edit
    // and silently drop the last toggle.
    SettingsSheet(
        themeMode = themeMode,
        dynamicColor = dynamicColor,
        oledBlack = oledBlack,
        trueNorth = trueNorth,
        responsiveness = responsiveness,
        onThemeChange = actions.onThemeChange,
        onDynamicColorChange = actions.onDynamicColorChange,
        onOledBlackChange = actions.onOledBlackChange,
        onTrueNorthChange = { enabled ->
            if (enabled && !hasCoarseLocationPermission(context)) {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            } else {
                actions.onTrueNorthChange(enabled)
            }
        },
        onResponsivenessChange = actions.onResponsivenessChange,
        onDismiss = onDismiss,
    )
}

internal fun hasCoarseLocationPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
