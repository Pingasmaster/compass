package com.compass.app.ui.compass

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.compass.app.data.preferences.ThemeMode
import com.compass.app.ui.compass.components.AccuracyChip
import com.compass.app.ui.compass.components.CalibrationBanner
import com.compass.app.ui.compass.components.CompassRose
import com.compass.app.ui.compass.components.HeadingReadout
import com.compass.app.ui.settings.SettingsSheet
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CompassScreen(viewModel: CompassViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    DisposableLifecycle(
        onResume = viewModel::onResume,
        onPause = viewModel::onPause,
        lifecycleOwner = lifecycleOwner,
    )

    val reading by viewModel.readings.collectAsStateWithLifecycle()
    val themeMode by viewModel.prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val dynamicColor by viewModel.prefs.dynamicColorEnabled.collectAsStateWithLifecycle(initialValue = true)
    val oledBlack by viewModel.prefs.oledBlackEnabled.collectAsStateWithLifecycle(initialValue = false)
    val trueNorth by viewModel.prefs.trueNorthEnabled.collectAsStateWithLifecycle(initialValue = false)

    var showSettings by remember { mutableStateOf(false) }
    var targetAngle by rememberSaveable { mutableStateOf<Float?>(null) }
    var showTargetDialog by remember { mutableStateOf(false) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.onLocationPermissionGranted()
        } else {
            scope.launch { viewModel.prefs.setTrueNorth(false) }
        }
    }

    val isDark = isSystemInDarkThemeEffective(themeMode)

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TopBar(
                hasSensor = reading.hasSensor,
                accuracyLabel = reading.accuracy,
                targetAngle = targetAngle,
                onTarget = { showTargetDialog = true },
                onSettings = { showSettings = true },
            )

            // Rose fills the available width (up to 520dp) as a plain transparent
            // square — the expressive cookie shape lives inside CompassRose itself.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .aspectRatio(1f),
            ) {
                CompassRose(
                    azimuthDegrees = reading.azimuth,
                    isDark = isDark,
                    calibrating = reading.accuracy.needsCalibration,
                    targetAngle = targetAngle,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            HeadingReadout(
                azimuthDegrees = reading.azimuth,
                isTrueNorth = trueNorth,
                declination = reading.declination,
                targetAngle = targetAngle,
            )

            Spacer(Modifier.weight(1f))

            CalibrationBanner(accuracy = reading.accuracy)

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showTargetDialog) {
        TargetAngleDialog(
            currentTarget = targetAngle,
            onConfirm = { value ->
                targetAngle = value
                showTargetDialog = false
            },
            onDismiss = { showTargetDialog = false },
        )
    }

    if (showSettings) {
        SettingsSheet(
            themeMode = themeMode,
            dynamicColor = dynamicColor,
            oledBlack = oledBlack,
            trueNorth = trueNorth,
            onThemeChange = { scope.launch { viewModel.prefs.setThemeMode(it) } },
            onDynamicColorChange = { scope.launch { viewModel.prefs.setDynamicColor(it) } },
            onOledBlackChange = { scope.launch { viewModel.prefs.setOledBlack(it) } },
            onTrueNorthChange = { enabled ->
                if (enabled && !hasCoarseLocationPermission(context)) {
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    scope.launch { viewModel.prefs.setTrueNorth(true) }
                } else {
                    scope.launch { viewModel.prefs.setTrueNorth(enabled) }
                }
            },
            onDismiss = { showSettings = false },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TopBar(
    hasSensor: Boolean,
    accuracyLabel: com.compass.app.domain.model.CompassAccuracy,
    targetAngle: Float?,
    onTarget: () -> Unit,
    onSettings: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.align(Alignment.CenterStart)) {
            Text(
                text = "Compass",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AccuracyChip(
                accuracy = accuracyLabel,
                hasSensor = hasSensor,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(modifier = Modifier.align(Alignment.CenterEnd)) {
            val targetActive = targetAngle != null
            FilledTonalIconButton(
                onClick = onTarget,
                shapes = IconButtonShapes(
                    shape = IconButtonDefaults.mediumRoundShape,
                    pressedShape = IconButtonDefaults.mediumPressedShape,
                ),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (targetActive) MaterialTheme.colorScheme.tertiaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (targetActive) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.GpsFixed,
                    contentDescription = "Set target angle",
                )
            }
            Spacer(Modifier.width(8.dp))
            // M3 Expressive FilledIconButton — morphs from round to small pressed shape on press.
            FilledIconButton(
                onClick = onSettings,
                shapes = IconButtonShapes(
                    shape = IconButtonDefaults.mediumRoundShape,
                    pressedShape = IconButtonDefaults.mediumPressedShape,
                ),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = "Settings",
                )
            }
        }
    }
}

@Composable
private fun TargetAngleDialog(
    currentTarget: Float?,
    onConfirm: (Float?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember {
        mutableStateOf(currentTarget?.toInt()?.toString().orEmpty())
    }
    val parsed = text.toFloatOrNull()
    val valid = text.isBlank() || (parsed != null && parsed in 0f..360f)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Target angle") },
        text = {
            Column {
                Text(
                    text = "Enter a bearing (0–360°). Leave blank to clear.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { ch -> ch.isDigit() || ch == '.' } },
                    singleLine = true,
                    isError = !valid,
                    label = { Text("Degrees") },
                    trailingIcon = { Text("°", style = MaterialTheme.typography.titleLarge) },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val value = text.toFloatOrNull()
                    onConfirm(value?.let { ((it % 360f) + 360f) % 360f })
                },
            ) { Text(if (text.isBlank()) "Clear" else "Set") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DisposableLifecycle(
    onResume: () -> Unit,
    onPause: () -> Unit,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
) {
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> onResume()
                Lifecycle.Event.ON_PAUSE -> onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun isSystemInDarkThemeEffective(themeMode: ThemeMode): Boolean {
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    return when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

private fun hasCoarseLocationPermission(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}
