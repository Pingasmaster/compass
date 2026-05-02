package com.compass.app.ui.compass

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
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

class CompassViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
) : AndroidViewModel(application) {

    val prefs: UserPreferences = (application as CompassApplication).userPreferences

    private val sensor = CompassSensor(application)
    private val locationManager =
        application.getSystemService(LocationManager::class.java)

    private var locationListener: LocationListener? = null

    // Sensor flow — stateIn drives registration via WhileSubscribed. The ViewModel no longer
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
        val normalised = value?.let { ((it % 360f) + 360f) % 360f }
        _targetAngle.value = normalised
        savedState[KEY_TARGET_ANGLE] = normalised
    }

    init {
        viewModelScope.launch {
            prefs.trueNorthEnabled.collect { enabled ->
                // If the pref says true but the runtime permission is gone (revoked
                // between sessions, or never granted), pull the toggle back to false
                // so the readout doesn't claim "+0.0° declination" while silently
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

    private fun hasCoarseLocationPermission(): Boolean =
        getApplication<Application>().checkSelfPermission(
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
        manager.requestLocationUpdates(provider, 60_000L, 100f, listener)
    }

    private fun stopLocationUpdates() {
        locationListener?.let { locationManager?.removeUpdates(it) }
        locationListener = null
    }

    override fun onCleared() {
        // No super call — ViewModel.onCleared is @EmptySuper and lint flags it.
        stopLocationUpdates()
    }
}
