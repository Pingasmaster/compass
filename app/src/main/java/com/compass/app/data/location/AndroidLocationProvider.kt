package com.compass.app.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.compass.app.domain.location.LocationProvider
import com.compass.app.domain.location.LocationRequestOutcome
import com.compass.app.domain.location.PermissionChecker
import com.compass.app.domain.model.GeoFix

/**
 * [LocationProvider] backed by [LocationManager]'s network provider. The permission
 * check is injected so the ContextCompat lookup exists exactly once in production.
 *
 * Declination barely changes over kilometres, so this is a one-shot: a fresh
 * last-known fix is enough, and a standing 60 s listener is not.
 */
class AndroidLocationProvider(context: Context, private val permissionChecker: PermissionChecker) : LocationProvider {

    private val locationManager: LocationManager? =
        context.applicationContext.getSystemService(LocationManager::class.java)

    private var listener: LocationListener? = null

    // Permission is re-checked below; the suppression covers the lint pass that
    // can't follow the guard back to the callsite.
    @SuppressLint("MissingPermission")
    override fun requestFix(onFix: (GeoFix) -> Unit): LocationRequestOutcome {
        val manager = locationManager ?: return LocationRequestOutcome.UNAVAILABLE
        if (!permissionChecker.hasCoarseLocationPermission()) {
            return LocationRequestOutcome.MISSING_PERMISSION
        }
        // GPS_PROVIDER requires ACCESS_FINE_LOCATION on API 28+; the app only
        // declares coarse, so the GPS path would crash with SecurityException.
        // Network-provider precision is plenty for declination correction.
        if (!manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            return LocationRequestOutcome.PROVIDER_DISABLED
        }
        stopUpdates()
        val provider = LocationManager.NETWORK_PROVIDER
        return try {
            val cached = manager.getLastKnownLocation(provider)
            if (cached != null) {
                onFix(cached.toGeoFix())
                if (isFreshEnough(cached)) return LocationRequestOutcome.REQUESTED
            }
            requestOneShot(manager, provider, onFix)
            LocationRequestOutcome.REQUESTED
        } catch (_: SecurityException) {
            stopUpdates()
            LocationRequestOutcome.MISSING_PERMISSION
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestOneShot(manager: LocationManager, provider: String, onFix: (GeoFix) -> Unit) {
        // LocationListener default methods for onStatusChanged /
        // onProviderEnabled / onProviderDisabled landed in API 29. Compat
        // runs on API 26-28, so implement all callbacks to avoid
        // AbstractMethodError when the framework invokes them.
        val frameworkListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onFix(location.toGeoFix())
                stopUpdates()
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        listener = frameworkListener
        manager.requestLocationUpdates(provider, 0L, 0f, frameworkListener)
    }

    override fun stopUpdates() {
        listener?.let { locationManager?.removeUpdates(it) }
        listener = null
    }

    private companion object {
        // World Magnetic Model declination is stable over a city for days.
        // One hour still refreshes after a long pause or a flight.
        const val LOCATION_MAX_AGE_MS = 60L * 60L * 1000L

        fun isFreshEnough(location: Location): Boolean {
            if (location.time <= 0L) return false
            return System.currentTimeMillis() - location.time < LOCATION_MAX_AGE_MS
        }
    }
}

private fun Location.toGeoFix(): GeoFix = GeoFix(latitude, longitude, altitude, time)
