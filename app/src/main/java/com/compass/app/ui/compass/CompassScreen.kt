package com.compass.app.ui.compass

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GpsFixed
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconButtonShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CompassScreen(
    isDark: Boolean,
    viewModel: CompassViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Sensor registration is driven by StateFlow subscription (callbackFlow inside
    // CompassSensor), so `collectAsStateWithLifecycle` is what starts and stops it —
    // no separate DisposableLifecycle needed.
    val reading by viewModel.readings.collectAsStateWithLifecycle()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_compass)) },
                subtitle = {
                    AccuracyChip(
                        accuracy = reading.accuracy,
                        hasSensor = reading.hasSensor,
                    )
                },
                actions = {
                    TopBarActions(onSettings = { showSettings = true })
                },
            )
        },
        floatingActionButton = {
            val targetActive = targetAngle != null
            FloatingActionButton(
                onClick = { showTargetDialog = true },
                containerColor = if (targetActive) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (targetActive) MaterialTheme.colorScheme.onTertiaryContainer
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                shape = FloatingActionButtonDefaults.shape,
            ) {
                Icon(
                    imageVector = Icons.Rounded.GpsFixed,
                    contentDescription = stringResource(R.string.action_set_target_angle),
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Throttle the TalkBack description: only recompute on cardinal change or
            // when crossing a 10° bucket, so we don't re-announce every sensor tick.
            val roseBucket by remember {
                derivedStateOf {
                    val cardinal = reading.azimuth.toCardinal()
                    val bucketed = ((reading.azimuth / 10f).roundToInt() * 10 + 360) % 360
                    cardinal to bucketed
                }
            }
            val roseDescription = pluralStringResource(
                R.plurals.rose_content_description,
                roseBucket.second,
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
        val themeMode by viewModel.prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
        val dynamicColor by viewModel.prefs.dynamicColorEnabled.collectAsStateWithLifecycle(initialValue = true)
        val oledBlack by viewModel.prefs.oledBlackEnabled.collectAsStateWithLifecycle(initialValue = false)
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
private fun TopBarActions(onSettings: () -> Unit) {
    FilledIconButton(
        onClick = onSettings,
        shapes = IconButtonShapes(
            shape = IconButtonDefaults.largeRoundShape,
            pressedShape = IconButtonDefaults.largePressedShape,
        ),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = Modifier.size(56.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Settings,
            contentDescription = stringResource(R.string.action_settings),
        )
    }
    Spacer(Modifier.width(8.dp))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TargetAngleSheet(
    currentTarget: Float?,
    onConfirm: (Float?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sliderState = remember {
        SliderState(
            value = currentTarget?.coerceIn(0f, 360f) ?: 0f,
            valueRange = 0f..360f,
        )
    }
    val bearing = sliderState.value

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
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

            Spacer(Modifier.height(12.dp))

            // Numpad text entry — lets the user type an exact bearing. Bidirectional
            // with the slider; the field only drives the slider while focused, and
            // the slider only drives the field while the field is unfocused.
            val focusManager = LocalFocusManager.current
            val sliderValueInt by remember {
                derivedStateOf { sliderState.value.toInt() }
            }
            var fieldFocused by remember { mutableStateOf(false) }
            var fieldText by remember { mutableStateOf(sliderValueInt.toString()) }
            LaunchedEffect(sliderValueInt) {
                if (!fieldFocused) fieldText = sliderValueInt.toString()
            }
            OutlinedTextField(
                value = fieldText,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(3)
                    fieldText = digits
                    digits.toIntOrNull()?.let { v ->
                        if (v in 0..360) sliderState.value = v.toFloat()
                    }
                },
                singleLine = true,
                label = { Text(stringResource(R.string.target_sheet_field_label)) },
                suffix = { Text("°", style = MaterialTheme.typography.titleLarge) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { fieldFocused = it.isFocused },
            )

            Spacer(Modifier.height(12.dp))

            // Slider with a built-in M3 PlainTooltip above the thumb for the value
            // indicator — fires on drag/press via the interactionSource.
            val interactionSource = remember { MutableInteractionSource() }
            val tooltipState = rememberTooltipState(isPersistent = true)
            LaunchedEffect(interactionSource) {
                interactionSource.interactions.collect { interaction ->
                    when (interaction) {
                        is DragInteraction.Start,
                        is PressInteraction.Press -> tooltipState.show()
                        is DragInteraction.Stop,
                        is DragInteraction.Cancel,
                        is PressInteraction.Release,
                        is PressInteraction.Cancel -> tooltipState.dismiss()
                    }
                }
            }
            // Keep the displayed label current while the tooltip is visible.
            val tooltipValue by remember {
                derivedStateOf { sliderState.value.toInt() }
            }

            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                    TooltipAnchorPosition.Above,
                ),
                tooltip = {
                    PlainTooltip {
                        Text(stringResource(R.string.target_sheet_value_label, tooltipValue))
                    }
                },
                state = tooltipState,
                focusable = false,
                enableUserInput = false,
            ) {
                Slider(
                    state = sliderState,
                    interactionSource = interactionSource,
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.tertiary,
                        activeTrackColor = MaterialTheme.colorScheme.tertiary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
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
                    onClick = { onConfirm(((sliderState.value % 360f) + 360f) % 360f) },
                ) { Text(stringResource(R.string.target_sheet_set)) }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun hasCoarseLocationPermission(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}
