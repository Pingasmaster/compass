package com.compass.app.ui.compass

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.compass.app.CompassApplication
import com.compass.app.data.location.AndroidLocationProvider
import com.compass.app.data.location.AndroidPermissionChecker
import com.compass.app.data.preferences.Responsiveness
import com.compass.app.data.preferences.ThemeMode
import com.compass.app.data.preferences.UserPreferences
import com.compass.app.domain.location.LocationIssue
import com.compass.app.domain.location.LocationProvider
import com.compass.app.domain.location.LocationRequestOutcome
import com.compass.app.domain.location.PermissionChecker
import com.compass.app.domain.model.CompassReading
import com.compass.app.domain.sensor.CompassSensor
import com.compass.app.domain.sensor.HeadingSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val KEY_TARGET_ANGLE = "target_angle"
private const val DEGREES_CIRCLE = 360f

/**
 * SensorManager listeners often go silent across the permission-dialog pause if
 * they stay registered. Zero stop timeout tears the callbackFlow down on
 * unsubscribe so resume re-registers and heading moves again. Pair with
 * `collectAsStateWithLifecycle(minActiveState = RESUMED)`.
 */
internal const val READINGS_STOP_TIMEOUT_MS = 0L

/** Wrap [degrees] into `[0, 360)`. */
internal fun normalizeBearingDegrees(degrees: Float): Float = ((degrees % DEGREES_CIRCLE) + DEGREES_CIRCLE) % DEGREES_CIRCLE

/**
 * Settings write intents, all dispatched in `viewModelScope` so an in-flight
 * DataStore edit survives sheet dismissal, back-press, and activity recreation.
 */
@Immutable
class SettingsActions(
    val onThemeChange: (ThemeMode) -> Unit,
    val onDynamicColorChange: (Boolean) -> Unit,
    val onOledBlackChange: (Boolean) -> Unit,
    val onTrueNorthChange: (Boolean) -> Unit,
    val onResponsivenessChange: (Responsiveness) -> Unit,
)

class CompassViewModel(
    val prefs: UserPreferences,
    private val headingSource: HeadingSource,
    private val locationProvider: LocationProvider,
    private val permissionChecker: PermissionChecker,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    // True while the shared readings flow is collecting upstream, i.e. while the
    // UI is resumed. Used to bound the one-shot location request to UI visibility,
    // mirroring the sensor registration.
    private val readingsActive = MutableStateFlow(false)

    // Sensor flow - stateIn drives registration via WhileSubscribed. Pause/resume
    // is owned by collectAsStateWithLifecycle(RESUMED) plus a zero stop timeout:
    // a 5 s grace left SensorManager registered through the permission dialog
    // and the heading froze at the initial 0 deg until process death.
    val readings: StateFlow<CompassReading> =
        headingSource.readings
            .onStart { readingsActive.value = true }
            .onCompletion { readingsActive.value = false }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(READINGS_STOP_TIMEOUT_MS),
                initialValue = CompassReading(hasSensor = headingSource.hasSensor),
            )

    private val _targetAngle = MutableStateFlow(savedState.get<Float?>(KEY_TARGET_ANGLE))
    val targetAngle: StateFlow<Float?> = _targetAngle.asStateFlow()

    private val _locationIssue = MutableStateFlow<LocationIssue?>(null)
    val locationIssue: StateFlow<LocationIssue?> = _locationIssue.asStateFlow()

    val settingsActions = SettingsActions(
        onThemeChange = { mode -> viewModelScope.launch { prefs.setThemeMode(mode) } },
        onDynamicColorChange = { enabled -> viewModelScope.launch { prefs.setDynamicColor(enabled) } },
        onOledBlackChange = { enabled -> viewModelScope.launch { prefs.setOledBlack(enabled) } },
        onTrueNorthChange = { enabled -> viewModelScope.launch { prefs.setTrueNorth(enabled) } },
        onResponsivenessChange = { mode -> viewModelScope.launch { prefs.setResponsiveness(mode) } },
    )

    fun setTargetAngle(value: Float?) {
        val normalised = value?.let(::normalizeBearingDegrees)
        _targetAngle.value = normalised
        savedState[KEY_TARGET_ANGLE] = normalised
    }

    /** Persists the system permission result; must outlive the composition. */
    fun onLocationPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            prefs.setLocationPrompted(true)
            prefs.setTrueNorth(granted)
        }
    }

    init {
        viewModelScope.launch {
            combine(prefs.trueNorthEnabled, readingsActive) { enabled, active -> enabled to active }
                .distinctUntilChanged()
                .collect { (enabled, active) ->
                    // If the pref says true but the runtime permission is gone (revoked
                    // between sessions, or never granted), pull the toggle back to false
                    // so the readout doesn't claim "+0.0 deg declination" while silently
                    // showing magnetic readings.
                    if (enabled && !permissionChecker.hasCoarseLocationPermission()) {
                        prefs.setTrueNorth(false)
                        _locationIssue.value = null
                        return@collect
                    }
                    headingSource.setTrueNorthEnabled(enabled)
                    if (!enabled) {
                        _locationIssue.value = null
                    }
                    // One-shot fix only while the UI is actually collecting
                    // readings: a backgrounded app should not keep an in-flight
                    // LocationManager request alive.
                    if (enabled && active) {
                        requestDeclinationFix()
                    } else {
                        locationProvider.stopUpdates()
                    }
                }
        }
    }

    private fun requestDeclinationFix() {
        var gotFix = false
        val outcome = locationProvider.requestFix { fix ->
            gotFix = true
            headingSource.updateLocation(fix)
            _locationIssue.value = null
        }
        _locationIssue.value = when (outcome) {
            LocationRequestOutcome.REQUESTED -> if (gotFix) null else LocationIssue.WAITING
            LocationRequestOutcome.MISSING_PERMISSION -> null
            LocationRequestOutcome.PROVIDER_DISABLED -> LocationIssue.PROVIDER_DISABLED
            LocationRequestOutcome.UNAVAILABLE -> LocationIssue.UNAVAILABLE
        }
    }

    override fun onCleared() {
        // No super call - ViewModel.onCleared is @EmptySuper and lint flags it.
        locationProvider.stopUpdates()
    }

    companion object {
        /**
         * Factory that reads [UserPreferences] from [CompassApplication] via
         * [ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] (no Hilt).
         */
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                require(modelClass.isAssignableFrom(CompassViewModel::class.java)) {
                    "Unknown ViewModel class: ${modelClass.name}"
                }
                val app = checkNotNull(
                    extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY],
                ) {
                    "APPLICATION_KEY missing from CreationExtras"
                } as CompassApplication
                val savedState = extras.createSavedStateHandle()
                val permissionChecker = AndroidPermissionChecker(app)
                val viewModel = CompassViewModel(
                    prefs = app.userPreferences,
                    headingSource = CompassSensor(app),
                    locationProvider = AndroidLocationProvider(app, permissionChecker),
                    permissionChecker = permissionChecker,
                    savedState = savedState,
                )
                return checkNotNull(modelClass.cast(viewModel)) {
                    "Unable to cast ViewModel to ${modelClass.name}"
                }
            }
        }
    }
}
