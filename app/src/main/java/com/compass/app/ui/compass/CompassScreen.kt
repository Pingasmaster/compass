package com.compass.app.ui.compass

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Scaffold
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import com.compass.app.R
import com.compass.app.data.preferences.Responsiveness
import com.compass.app.data.preferences.UserPreferences
import com.compass.app.domain.location.LocationIssue
import com.compass.app.domain.model.CompassAccuracy
import com.compass.app.domain.model.toCardinal
import com.compass.app.ui.compass.components.CompassTargetFab
import com.compass.app.ui.compass.components.CompassTopBar
import com.compass.app.ui.compass.components.DualPaneCompassBody
import com.compass.app.ui.compass.components.SinglePaneCompassBody
import com.compass.app.ui.compass.components.TargetAngleSheet
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CompassScreen(
    isDark: Boolean,
    modifier: Modifier = Modifier,
    viewModel: CompassViewModel = viewModel(factory = CompassViewModel.Factory),
) {
    val prefs = viewModel.prefs
    val reading by viewModel.readings.collectAsStateWithLifecycle(
        minActiveState = Lifecycle.State.RESUMED,
    )
    val trueNorth by prefs.trueNorthEnabled.collectAsStateWithLifecycle(initialValue = false)
    val locationPrompted by prefs.locationPrompted.collectAsStateWithLifecycle(initialValue = false)
    val responsiveness by prefs.responsiveness.collectAsStateWithLifecycle(
        initialValue = Responsiveness.NORMAL,
    )
    val targetAngle by viewModel.targetAngle.collectAsStateWithLifecycle()
    val locationIssue by viewModel.locationIssue.collectAsStateWithLifecycle()

    CompassTrueNorthPermissionGate(
        alreadyPrompted = locationPrompted,
        onPermissionResult = viewModel::onLocationPermissionResult,
        onEnableTrueNorth = { viewModel.settingsActions.onTrueNorthChange(true) },
    ) { requestTrueNorth ->
        CompassScreenContent(
            isDark = isDark,
            roseAzimuth = reading.azimuth,
            accuracy = reading.accuracy,
            hasSensor = reading.hasSensor,
            calibrating = reading.accuracy.needsCalibration,
            isTrueNorth = trueNorth,
            declination = reading.declination,
            locationIssue = locationIssue,
            targetAngle = targetAngle,
            responsiveness = responsiveness,
            prefs = prefs,
            settingsActions = viewModel.settingsActions,
            onRequestTrueNorth = requestTrueNorth,
            onSetTargetAngle = viewModel::setTargetAngle,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CompassScreenContent(
    isDark: Boolean,
    roseAzimuth: Float,
    accuracy: CompassAccuracy,
    hasSensor: Boolean,
    calibrating: Boolean,
    isTrueNorth: Boolean,
    declination: Float,
    locationIssue: LocationIssue?,
    targetAngle: Float?,
    responsiveness: Responsiveness,
    prefs: UserPreferences,
    settingsActions: SettingsActions,
    onRequestTrueNorth: () -> Unit,
    onSetTargetAngle: (Float?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showTargetDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            CompassTopBar(
                accuracy = accuracy,
                hasSensor = hasSensor,
                onSettings = { showSettings = true },
            )
        },
        floatingActionButton = {
            CompassTargetFab(
                targetActive = targetAngle != null,
                onClick = { showTargetDialog = true },
            )
        },
    ) { innerPadding ->
        CompassScaffoldBody(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            roseAzimuth = roseAzimuth,
            isDark = isDark,
            calibrating = calibrating,
            targetAngle = targetAngle,
            responsiveness = responsiveness,
            isTrueNorth = isTrueNorth,
            declination = declination,
            accuracy = accuracy,
            locationIssue = locationIssue,
        )
    }

    CompassScreenSheets(
        showTargetDialog = showTargetDialog,
        showSettings = showSettings,
        targetAngle = targetAngle,
        trueNorth = isTrueNorth,
        responsiveness = responsiveness,
        prefs = prefs,
        settingsActions = settingsActions,
        onRequestTrueNorth = onRequestTrueNorth,
        onTargetConfirm = { value ->
            onSetTargetAngle(value)
            showTargetDialog = false
        },
        onTargetDismiss = { showTargetDialog = false },
        onSettingsDismiss = { showSettings = false },
    )
}

@Composable
private fun CompassScreenSheets(
    showTargetDialog: Boolean,
    showSettings: Boolean,
    targetAngle: Float?,
    trueNorth: Boolean,
    responsiveness: Responsiveness,
    prefs: UserPreferences,
    settingsActions: SettingsActions,
    onRequestTrueNorth: () -> Unit,
    onTargetConfirm: (Float?) -> Unit,
    onTargetDismiss: () -> Unit,
    onSettingsDismiss: () -> Unit,
) {
    if (showTargetDialog) {
        TargetAngleSheet(
            currentTarget = targetAngle,
            onConfirm = onTargetConfirm,
            onDismiss = onTargetDismiss,
        )
    }
    if (showSettings) {
        CompassSettingsHost(
            prefs = prefs,
            actions = settingsActions,
            trueNorth = trueNorth,
            responsiveness = responsiveness,
            onRequestTrueNorth = onRequestTrueNorth,
            onDismiss = onSettingsDismiss,
        )
    }
}

@Composable
private fun CompassScaffoldBody(
    roseAzimuth: Float,
    isDark: Boolean,
    calibrating: Boolean,
    targetAngle: Float?,
    responsiveness: Responsiveness,
    isTrueNorth: Boolean,
    declination: Float,
    accuracy: CompassAccuracy,
    locationIssue: LocationIssue?,
    modifier: Modifier = Modifier,
) {
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val dualPane = windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
    ) && !windowSizeClass.isHeightAtLeastBreakpoint(
        WindowSizeClass.HEIGHT_DP_EXPANDED_LOWER_BOUND,
    )

    // Bucket the azimuth to 10-degree steps so the TalkBack description only
    // changes on meaningful heading changes. Computed inline: this composable
    // already recomposes on every azimuth change, and a keyless
    // remember { derivedStateOf { ... } } over a plain parameter would freeze
    // at the first composition's value (derivedStateOf only tracks snapshot
    // state reads, and a captured Float parameter is not one).
    val roseCardinal = roseAzimuth.toCardinal()
    val roseBucketed = ((roseAzimuth / 10f).roundToInt() * 10 + 360) % 360
    val roseDescription = pluralStringResource(
        R.plurals.rose_content_description,
        roseBucketed,
        roseBucketed,
        roseCardinal,
    )

    val bodyModifier = if (dualPane) {
        modifier.padding(horizontal = 24.dp, vertical = 8.dp)
    } else {
        modifier.padding(horizontal = 20.dp)
    }

    if (dualPane) {
        DualPaneCompassBody(
            modifier = bodyModifier,
            roseDescription = roseDescription,
            azimuthDegrees = roseAzimuth,
            isDark = isDark,
            calibrating = calibrating,
            targetAngle = targetAngle,
            responsiveness = responsiveness,
            isTrueNorth = isTrueNorth,
            declination = declination,
            accuracy = accuracy,
            locationIssue = locationIssue,
        )
    } else {
        SinglePaneCompassBody(
            modifier = bodyModifier,
            roseDescription = roseDescription,
            azimuthDegrees = roseAzimuth,
            isDark = isDark,
            calibrating = calibrating,
            targetAngle = targetAngle,
            responsiveness = responsiveness,
            isTrueNorth = isTrueNorth,
            declination = declination,
            accuracy = accuracy,
            locationIssue = locationIssue,
        )
    }
}
