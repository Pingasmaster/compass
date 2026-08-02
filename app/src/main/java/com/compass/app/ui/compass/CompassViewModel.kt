package com.compass.app.ui.compass

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.compass.app.CompassApplication
import com.compass.app.data.preferences.UserPreferences
import com.compass.app.domain.model.CompassReading
import com.compass.app.domain.sensor.CompassSensor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val KEY_TARGET_ANGLE = "target_angle"
private const val DEGREES_CIRCLE = 360f

/** Wrap [degrees] into `[0, 360)`. */
internal fun normalizeBearingDegrees(degrees: Float): Float = ((degrees % DEGREES_CIRCLE) + DEGREES_CIRCLE) % DEGREES_CIRCLE

class CompassViewModel(val prefs: UserPreferences, appContext: Context, private val savedState: SavedStateHandle) : ViewModel() {

    private val appContext = appContext.applicationContext

    private val sensor = CompassSensor(this.appContext)
    private val locationManager =
        this.appContext.getSystemService(LocationManager::class.java)

    private var locationListener: LocationListener? = null

    // Sensor flow - stateIn drives registration via WhileSubscribed. The ViewModel no longer
    // needs onResume/onPause hooks; the composable's `collectAsStateWithLifecycle` controls it.
    val readings: StateFlow<CompassReading> =
        sensor.readings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CompassReading(hasSensor = sensor.hasSensor),
        )

    private val _targetAngle = MutableStateFlow(savedState.get<Float?>(KEY_TARGET_ANGLE))
    val targetAngle: StateFlow<Float?> = _targetAngle.asStateFlow()

    fun setTargetAngle(value: Float?) {
        val normalised = value?.let(::normalizeBearingDegrees)
        _targetAngle.value = normalised
        savedState[KEY_TARGET_ANGLE] = normalised
    }

    init {
        viewModelScope.launch {
            prefs.trueNorthEnabled.collect { enabled ->
                // If the pref says true but the runtime permission is gone (revoked
                // between sessions, or never granted), pull the toggle back to false
                // so the readout doesn't claim "+0.0 deg declination" while silently
                // showing magnetic readings.
                if (enabled && !hasCoarseLocationPermission()) {
                    prefs.setTrueNorth(false)
                    return@collect
                }
                sensor.setTrueNorthEnabled(enabled)
                if (enabled) requestLocationIfPermitted() else stopLocationUpdates()
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
        // LocationListener gained default implementations for onStatusChanged,
        // onProviderEnabled and onProviderDisabled in API 29, so on minSdk 31 we
        // only need to override onLocationChanged.
        val listener = LocationListener { location -> sensor.updateLocation(location) }
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
