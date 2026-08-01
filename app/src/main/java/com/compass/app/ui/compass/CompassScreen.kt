package com.compass.app.ui.compass

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.rememberBottomSheetState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.window.core.layout.WindowSizeClass
import com.compass.app.R
import com.compass.app.data.preferences.Responsiveness
import com.compass.app.data.preferences.ThemeMode
import com.compass.app.domain.model.CompassAccuracy
import com.compass.app.domain.model.toCardinal
import com.compass.app.ui.compass.components.AccuracyChip
import com.compass.app.ui.compass.components.CalibrationBanner
import com.compass.app.ui.compass.components.CompassRose
import com.compass.app.ui.compass.components.HeadingReadout
import com.compass.app.ui.settings.SettingsSheet
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Soft upper bound for the rose. Phones rarely hit this (width is smaller); tablets
 * and desktop windows would otherwise grow a square that dominates or clips the pane.
 * Chosen below the old hard 520.dp cap so medium/expanded windows stay balanced.
 */
private val MaxRoseSize = 400.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CompassScreen(isDark: Boolean, modifier: Modifier = Modifier, viewModel: CompassViewModel = viewModel()) {
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

    // Drive the True-north pref from the launcher result rather than optimistically
    // flipping it on tap: granted → pref true (the ViewModel collect attaches the
    // listener); denied → pref stays false. The ViewModel also auto-clears the pref
    // when it observes pref=true with no permission, covering revoked-between-sessions.
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        scope.launch {
            viewModel.prefs.setLocationPrompted(true)
            viewModel.prefs.setTrueNorth(granted)
        }
    }

    // Ask once on first launch so True North works without the user having to dig
    // into settings. After the system dialog has been shown once, never auto-prompt
    // again — repeated requests get auto-denied by the OS, and the settings toggle
    // remains as the explicit re-prompt path. If permission is already granted (e.g.
    // pre-granted via system settings), just record the prompt as done.
    val locationPrompted by viewModel.prefs.locationPrompted.collectAsStateWithLifecycle(initialValue = true)
    LaunchedEffect(locationPrompted) {
        if (locationPrompted) return@LaunchedEffect
        if (hasCoarseLocationPermission(context)) {
            viewModel.prefs.setLocationPrompted(true)
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_compass)) },
                subtitle = {
                    AccuracyChip(
                        accuracy = reading.accuracy,
                        hasSensor = reading.hasSensor,
                        modifier = Modifier.padding(top = 6.dp),
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
                containerColor = if (targetActive) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                },
                contentColor = if (targetActive) {
                    MaterialTheme.colorScheme.onTertiaryContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
                shape = FloatingActionButtonDefaults.shape,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_gps_fixed_24),
                    contentDescription = stringResource(R.string.action_set_target_angle),
                )
            }
        },
    ) { innerPadding ->
        // M3 Adaptive: window size classes drive the content-level layout (column vs
        // dual-pane). Nested rose sizing uses BoxWithConstraints so the square never
        // exceeds the slot it is given - the old fillMaxWidth+aspectRatio(1) forced
        // height = width up to 520.dp and clipped on tablets / landscape.
        val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
        val dualPane = windowSizeClass.isWidthAtLeastBreakpoint(
            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
        ) && !windowSizeClass.isHeightAtLeastBreakpoint(
            WindowSizeClass.HEIGHT_DP_EXPANDED_LOWER_BOUND,
        )

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

        if (dualPane) {
            DualPaneCompassBody(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                roseDescription = roseDescription,
                azimuthDegrees = reading.azimuth,
                isDark = isDark,
                calibrating = reading.accuracy.needsCalibration,
                targetAngle = targetAngle,
                responsiveness = responsiveness,
                isTrueNorth = trueNorth,
                declination = reading.declination,
                accuracy = reading.accuracy,
            )
        } else {
            SinglePaneCompassBody(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp),
                roseDescription = roseDescription,
                azimuthDegrees = reading.azimuth,
                isDark = isDark,
                calibrating = reading.accuracy.needsCalibration,
                targetAngle = targetAngle,
                responsiveness = responsiveness,
                isTrueNorth = trueNorth,
                declination = reading.declination,
                accuracy = reading.accuracy,
            )
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
                } else {
                    scope.launch { viewModel.prefs.setTrueNorth(enabled) }
                }
            },
            onResponsivenessChange = { scope.launch { viewModel.prefs.setResponsiveness(it) } },
            onDismiss = { showSettings = false },
        )
    }
}

/**
 * Portrait / tall windows: rose stacked above the heading readout.
 * The rose lives in a weighted [BoxWithConstraints] so its square side is
 * `min(availableWidth, availableHeight, [MaxRoseSize])` - never taller than the slot.
 */
@Composable
private fun SinglePaneCompassBody(
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
private fun DualPaneCompassBody(
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
 * Per Compose adaptive docs, nested composables should use [BoxWithConstraints]
 * rather than window metrics so padding, app bars, and dual-pane splits are respected.
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RowScope.TopBarActions(onSettings: () -> Unit) {
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
            painter = painterResource(R.drawable.ic_settings_24),
            contentDescription = stringResource(R.string.action_settings),
        )
    }
    Spacer(Modifier.width(8.dp))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TargetAngleSheet(currentTarget: Float?, onConfirm: (Float?) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
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
            // the slider only drives the field while the field is unfocused. On blur
            // we always snap the field back to the slider so out-of-range or empty
            // typed values can't outlive the focus and mislead the Set button.
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
                    .onFocusChanged { focusState ->
                        val wasFocused = fieldFocused
                        fieldFocused = focusState.isFocused
                        if (wasFocused && !focusState.isFocused) {
                            fieldText = sliderValueInt.toString()
                        }
                    },
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
                        is PressInteraction.Press,
                        -> tooltipState.show()

                        is DragInteraction.Stop,
                        is DragInteraction.Cancel,
                        is PressInteraction.Release,
                        is PressInteraction.Cancel,
                        -> tooltipState.dismiss()
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

private fun hasCoarseLocationPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
