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
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.compass.app.R
import com.compass.app.data.preferences.Responsiveness
import com.compass.app.data.preferences.ThemeMode
import com.compass.app.domain.model.toCardinal
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
    val scope = rememberCoroutineScope()

    // Sensor registration is driven by StateFlow subscription (callbackFlow inside
    // CompassSensor), so `collectAsStateWithLifecycle` is what starts and stops it —
    // no separate DisposableLifecycle needed.
    val reading by viewModel.readings.collectAsStateWithLifecycle()
    val themeMode by viewModel.prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val dynamicColor by viewModel.prefs.dynamicColorEnabled.collectAsStateWithLifecycle(initialValue = true)
    val oledBlack by viewModel.prefs.oledBlackEnabled.collectAsStateWithLifecycle(initialValue = false)
    val trueNorth by viewModel.prefs.trueNorthEnabled.collectAsStateWithLifecycle(initialValue = false)
    val responsiveness by viewModel.prefs.responsiveness.collectAsStateWithLifecycle(initialValue = Responsiveness.NORMAL)
    val targetAngle by viewModel.targetAngle.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }
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
            // Throttle the TalkBack description: only recompute on cardinal change or
            // crossing a 10° bucket, so we don't read out every sensor tick.
            val roseBucket by remember {
                derivedStateOf {
                    val cardinal = reading.azimuth.toCardinal()
                    val bucketed = ((reading.azimuth / 10f).roundToInt() * 10 + 360) % 360
                    cardinal to bucketed
                }
            }
            val roseDescription = stringResource(
                R.string.rose_content_description,
                roseBucket.second,
                roseBucket.first,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .aspectRatio(1f)
                    .semantics(mergeDescendants = true) {
                        contentDescription = roseDescription
                    },
            ) {
                CompassRose(
                    azimuthDegrees = reading.azimuth,
                    isDark = isDark,
                    calibrating = reading.accuracy.needsCalibration,
                    targetAngle = targetAngle,
                    responsiveness = responsiveness,
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
        TargetAngleSheet(
            currentTarget = targetAngle,
            onConfirm = { value ->
                viewModel.setTargetAngle(value)
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
            responsiveness = responsiveness,
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
            onResponsivenessChange = { scope.launch { viewModel.prefs.setResponsiveness(it) } },
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
                text = stringResource(R.string.title_compass),
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
                    contentDescription = stringResource(R.string.action_set_target_angle),
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
                    contentDescription = stringResource(R.string.action_settings),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TargetAngleSheet(
    currentTarget: Float?,
    onConfirm: (Float?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var bearing by remember {
        mutableFloatStateOf(currentTarget?.coerceIn(0f, 360f) ?: 0f)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Inherit the Expressive sheet shape token instead of hard-coding 28dp.
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.target_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.target_sheet_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(24.dp))

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "${bearing.toInt()}",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = "°  ${bearing.toCardinal()}",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                )
            }

            Spacer(Modifier.height(8.dp))

            // M3 Slider with a floating value indicator (pill above thumb during drag).
            val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val isDragged by interactionSource.collectIsDraggedAsState()
            val isPressed by interactionSource.collectIsPressedAsState()
            val showIndicator = isDragged || isPressed

            androidx.compose.foundation.layout.BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
            ) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                val trackWidthPx = with(density) { maxWidth.toPx() }
                val fraction = (bearing / 360f).coerceIn(0f, 1f)
                // Thumb radius ≈ 10dp; label should sit above the thumb, centered.
                val labelWidthDp = 56.dp
                val indicatorOffsetX = with(density) {
                    (fraction * trackWidthPx - labelWidthDp.toPx() / 2f).toDp()
                }.coerceIn(0.dp, maxWidth - labelWidthDp)

                Column {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                    ) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = showIndicator,
                            enter = androidx.compose.animation.fadeIn() +
                                androidx.compose.animation.scaleIn(),
                            exit = androidx.compose.animation.fadeOut() +
                                androidx.compose.animation.scaleOut(),
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .offset(x = indicatorOffsetX),
                        ) {
                            androidx.compose.material3.Surface(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.inverseSurface,
                                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                                modifier = Modifier
                                    .width(labelWidthDp)
                                    // Decorative — the Slider itself announces the value.
                                    .clearAndSetSemantics {},
                            ) {
                                Text(
                                    text = stringResource(R.string.target_sheet_value_label, bearing.toInt()),
                                    style = MaterialTheme.typography.labelLarge,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    Slider(
                        value = bearing,
                        onValueChange = { bearing = it },
                        valueRange = 0f..360f,
                        steps = 7, // ticks at N NE E SE S SW W NW
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.tertiary,
                            activeTrackColor = MaterialTheme.colorScheme.tertiary,
                        ),
                        interactionSource = interactionSource,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Cardinal labels under the slider for context.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW", "N").forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                // Use the no-arg ButtonDefaults.shapes() — gives the M3 Expressive
                // round-to-square press morph for free.
                TextButton(
                    shapes = ButtonDefaults.shapes(),
                    onClick = { onConfirm(null) },
                ) { Text(stringResource(R.string.target_sheet_clear)) }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    shapes = ButtonDefaults.shapes(),
                    onClick = onDismiss,
                ) { Text(stringResource(R.string.target_sheet_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    shapes = ButtonDefaults.shapes(),
                    onClick = { onConfirm(((bearing % 360f) + 360f) % 360f) },
                ) { Text(stringResource(R.string.target_sheet_set)) }
            }

            Spacer(Modifier.height(16.dp))
        }
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
