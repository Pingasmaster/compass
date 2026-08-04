package com.compass.app.ui.compass

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.compass.app.CompassApplication
import com.compass.app.data.preferences.Responsiveness
import com.compass.app.data.preferences.ThemeMode
import com.compass.app.data.preferences.UserPreferences
import com.compass.app.domain.model.CompassReading
import com.compass.app.domain.sensor.CompassSensor
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

class CompassViewModel(val prefs: UserPreferences, appContext: Context, private val savedState: SavedStateHandle) : ViewModel() {

    private val appContext = appContext.applicationContext

    private val sensor = CompassSensor(this.appContext)
    private val locationManager =
        this.appContext.getSystemService(LocationManager::class.java)

    private var locationListener: LocationListener? = null

    // True while the shared readings flow is collecting upstream, i.e. while the
    // UI is (or was within the last 5 s) actually visible. Used to bound the
    // location listener to UI visibility, mirroring the sensor registration.
    private val readingsActive = MutableStateFlow(false)

    // Sensor flow - stateIn drives registration via WhileSubscribed. The ViewModel no longer
    // needs onResume/onPause hooks; the composable's `collectAsStateWithLifecycle` controls it.
    val readings: StateFlow<CompassReading> =
        sensor.readings
            .onStart { readingsActive.value = true }
            .onCompletion { readingsActive.value = false }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = CompassReading(hasSensor = sensor.hasSensor),
            )

    private val _targetAngle = MutableStateFlow(savedState.get<Float?>(KEY_TARGET_ANGLE))
    val targetAngle: StateFlow<Float?> = _targetAngle.asStateFlow()

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

    fun markLocationPrompted() {
        viewModelScope.launch { prefs.setLocationPrompted(true) }
    }

    /** Persists the one-shot system permission result; must outlive the composition. */
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
                    if (enabled && !hasCoarseLocationPermission()) {
                        prefs.setTrueNorth(false)
                        return@collect
                    }
                    sensor.setTrueNorthEnabled(enabled)
                    // Location updates only while the UI is actually collecting
                    // readings: a backgrounded app should not keep an active
                    // LocationManager registration alive.
                    if (enabled && active) requestLocationIfPermitted() else stopLocationUpdates()
                }
        }
    }

    private fun hasCoarseLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    // Permission is re-checked below; the suppression covers the lint pass that
    // can't follow the guard back to the callsite.
    @SuppressLint("MissingPermission")
    private fun requestLocationIfPermitted() {
        val manager = locationManager ?: return
        if (!hasCoarseLocationPermission()) return
        // GPS_PROVIDER requires ACCESS_FINE_LOCATION on API 28+; the app only
        // declares coarse, so the GPS path would crash with SecurityException.
        // Network-provider precision is plenty for declination correction.
        if (!manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return
        stopLocationUpdates()
        val provider = LocationManager.NETWORK_PROVIDER
        // LocationListener default methods for onStatusChanged /
        // onProviderEnabled / onProviderDisabled landed in API 29. Compat
        // runs on API 26-28, so implement all callbacks to avoid
        // AbstractMethodError when the framework invokes them.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                sensor.updateLocation(location)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        locationListener = listener
        manager.getLastKnownLocation(provider)?.let(sensor::updateLocation)
        manager.requestLocationUpdates(
            provider,
            LOCATION_MIN_TIME_MS,
            LOCATION_MIN_DISTANCE_M,
            listener,
        )
    }

    private fun stopLocationUpdates() {
        locationListener?.let { locationManager?.removeUpdates(it) }
        locationListener = null
    }

    override fun onCleared() {
        // No super call - ViewModel.onCleared is @EmptySuper and lint flags it.
        stopLocationUpdates()
    }

    companion object {
        private const val LOCATION_MIN_TIME_MS = 60_000L
        private const val LOCATION_MIN_DISTANCE_M = 100f

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
                return checkNotNull(
                    modelClass.cast(CompassViewModel(app.userPreferences, app, savedState)),
                ) {
                    "Unable to cast ViewModel to ${modelClass.name}"
                }
            }
        }
    }
}
