package com.compass.app.ui.compass

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
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
        ContextCompat.getSystemService(application, LocationManager::class.java)

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
                sensor.setTrueNorthEnabled(enabled)
                if (enabled) {
                    requestLocationIfPermitted(application)
                } else {
                    stopLocationUpdates()
                }
            }
        }
    }

    /** Called by UI after a successful permission grant to attach a location listener. */
    fun onLocationPermissionGranted() {
        requestLocationIfPermitted(getApplication())
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationIfPermitted(context: Context) {
        val manager = locationManager ?: return
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        stopLocationUpdates()
        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                sensor.updateLocation(location)
            }
            @Deprecated("legacy")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
        }
        locationListener = listener
        manager.getLastKnownLocation(provider)?.let(sensor::updateLocation)
        manager.requestLocationUpdates(provider, 60_000L, 100f, listener)
    }

    private fun stopLocationUpdates() {
        locationListener?.let { locationManager?.removeUpdates(it) }
        locationListener = null
    }

    override fun onCleared() {
        stopLocationUpdates()
    }
}
