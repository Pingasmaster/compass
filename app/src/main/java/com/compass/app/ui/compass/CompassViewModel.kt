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
import androidx.lifecycle.viewModelScope
import com.compass.app.CompassApplication
import com.compass.app.data.preferences.UserPreferences
import com.compass.app.domain.model.CompassReading
import com.compass.app.domain.sensor.CompassSensor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CompassViewModel(application: Application) : AndroidViewModel(application) {

    val prefs: UserPreferences = (application as CompassApplication).userPreferences

    private val sensor = CompassSensor(application)
    private val locationManager =
        ContextCompat.getSystemService(application, LocationManager::class.java)

    private var locationListener: LocationListener? = null

    val readings: StateFlow<CompassReading> =
        sensor.readings.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CompassReading(),
        )

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

    fun onResume() {
        sensor.start()
    }

    fun onPause() {
        sensor.stop()
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
        sensor.stop()
        super.onCleared()
    }
}
