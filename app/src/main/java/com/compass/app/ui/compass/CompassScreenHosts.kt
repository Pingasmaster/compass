package com.compass.app.ui.compass

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.compass.app.BuildConfig
import com.compass.app.CompassApplication
import com.compass.app.R
import com.compass.app.data.preferences.Responsiveness
import com.compass.app.data.preferences.ThemeMode
import com.compass.app.data.preferences.UserPreferences
import com.compass.app.ui.settings.SettingsSheet

@Composable
internal fun CompassTrueNorthPermissionGate(
    alreadyPrompted: Boolean,
    onPermissionResult: (Boolean) -> Unit,
    onEnableTrueNorth: () -> Unit,
    content: @Composable (requestTrueNorth: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val currentOnPermissionResult by rememberUpdatedState(onPermissionResult)
    val currentOnEnableTrueNorth by rememberUpdatedState(onEnableTrueNorth)
    var showLocationRationale by rememberSaveable { mutableStateOf(false) }
    var openAppSettingsInstead by rememberSaveable { mutableStateOf(false) }
    var awaitingSettingsGrant by rememberSaveable { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        currentOnPermissionResult(granted)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!awaitingSettingsGrant) return@LifecycleEventEffect
        awaitingSettingsGrant = false
        if (hasCoarseLocationPermission(context)) {
            currentOnEnableTrueNorth()
        }
    }

    content {
        requestTrueNorth(
            context = context,
            alreadyPrompted = alreadyPrompted,
            onEnabled = currentOnEnableTrueNorth,
            onShowRationale = { useSettings ->
                openAppSettingsInstead = useSettings
                showLocationRationale = true
            },
        )
    }

    if (showLocationRationale) {
        TrueNorthLocationRationaleDialog(
            openAppSettings = openAppSettingsInstead,
            onConfirm = {
                showLocationRationale = false
                if (openAppSettingsInstead) {
                    awaitingSettingsGrant = true
                    openAppSettings(context)
                } else {
                    launchCoarseLocationPermission(locationPermissionLauncher)
                }
            },
            onDismiss = { showLocationRationale = false },
        )
    }
}

internal fun requestTrueNorth(
    context: Context,
    alreadyPrompted: Boolean,
    onEnabled: () -> Unit,
    onShowRationale: (openAppSettings: Boolean) -> Unit,
) {
    if (hasCoarseLocationPermission(context)) {
        onEnabled()
        return
    }
    val activity = context as? Activity
    val showRationale = activity != null && shouldShowLocationRationale(activity)
    onShowRationale(
        needsAppSettingsForLocation(
            hasPermission = false,
            alreadyPrompted = alreadyPrompted,
            shouldShowRationale = showRationale,
        ),
    )
}

@Composable
internal fun CompassSettingsHost(
    prefs: UserPreferences,
    actions: SettingsActions,
    trueNorth: Boolean,
    responsiveness: Responsiveness,
    onRequestTrueNorth: () -> Unit,
    onDismiss: () -> Unit,
) {
    val themeMode by prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val dynamicColor by prefs.dynamicColorEnabled.collectAsStateWithLifecycle(initialValue = true)
    val oledBlack by prefs.oledBlackEnabled.collectAsStateWithLifecycle(initialValue = false)
    val autoUpdateCheck by prefs.autoUpdateCheckEnabled.collectAsStateWithLifecycle(initialValue = true)
    val app = LocalContext.current.applicationContext as CompassApplication
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
        autoUpdateCheck = autoUpdateCheck,
        versionName = BuildConfig.VERSION_NAME,
        onThemeChange = actions.onThemeChange,
        onDynamicColorChange = actions.onDynamicColorChange,
        onOledBlackChange = actions.onOledBlackChange,
        onTrueNorthChange = { enabled ->
            if (enabled) {
                onRequestTrueNorth()
            } else {
                actions.onTrueNorthChange(false)
            }
        },
        onResponsivenessChange = actions.onResponsivenessChange,
        onAutoUpdateCheckChange = actions.onAutoUpdateCheckChange,
        onCheckForUpdates = { app.appUpdateController.checkManually() },
        onDismiss = onDismiss,
    )
}

@Composable
internal fun TrueNorthLocationRationaleDialog(openAppSettings: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (openAppSettings) {
                        R.string.true_north_permission_blocked_title
                    } else {
                        R.string.true_north_permission_title
                    },
                ),
            )
        },
        text = {
            Text(
                stringResource(
                    if (openAppSettings) {
                        R.string.true_north_permission_blocked_body
                    } else {
                        R.string.true_north_permission_body
                    },
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(
                        if (openAppSettings) {
                            R.string.true_north_permission_open_settings
                        } else {
                            R.string.true_north_permission_continue
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.true_north_permission_not_now))
            }
        },
    )
}

internal fun hasCoarseLocationPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * First ask and "show rationale" both go through the in-app dialog then the
 * system prompt. After a permanent deny, [shouldShowRequestPermissionRationale]
 * is false and we already prompted, so the only path left is app settings.
 */
internal fun needsAppSettingsForLocation(hasPermission: Boolean, alreadyPrompted: Boolean, shouldShowRationale: Boolean): Boolean =
    !hasPermission && alreadyPrompted && !shouldShowRationale

internal fun shouldShowLocationRationale(activity: Activity): Boolean = ActivityCompat.shouldShowRequestPermissionRationale(
    activity,
    Manifest.permission.ACCESS_COARSE_LOCATION,
)

internal fun launchCoarseLocationPermission(launcher: ActivityResultLauncher<String>) {
    launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
}

internal fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        },
    )
}
