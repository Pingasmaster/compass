package com.compass.app.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.compass.app.domain.location.LocationProvider
import com.compass.app.domain.location.PermissionChecker
import com.compass.app.domain.model.GeoFix

/**
 * [LocationProvider] backed by [LocationManager]'s network provider. The permission
 * check is injected so the ContextCompat lookup exists exactly once in production.
 */
class AndroidLocationProvider(context: Context, private val permissionChecker: PermissionChecker) : LocationProvider {

    private val locationManager: LocationManager? =
        context.applicationContext.getSystemService(LocationManager::class.java)

    private var listener: LocationListener? = null

    // Permission is re-checked below; the suppression covers the lint pass that
    // can't follow the guard back to the callsite. (Moved from CompassViewModel.)
    @SuppressLint("MissingPermission")
    override fun requestUpdates(onFix: (GeoFix) -> Unit) {
        val manager = locationManager ?: return
        if (!permissionChecker.hasCoarseLocationPermission()) return
        // GPS_PROVIDER requires ACCESS_FINE_LOCATION on API 28+; the app only
        // declares coarse, so the GPS path would crash with SecurityException.
        // Network-provider precision is plenty for declination correction.
        if (!manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return
        stopUpdates()
        val provider = LocationManager.NETWORK_PROVIDER
        // LocationListener default methods for onStatusChanged /
        // onProviderEnabled / onProviderDisabled landed in API 29. Compat
        // runs on API 26-28, so implement all callbacks to avoid
        // AbstractMethodError when the framework invokes them.
        val frameworkListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onFix(location.toGeoFix())
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        listener = frameworkListener
        manager.getLastKnownLocation(provider)?.let { onFix(it.toGeoFix()) }
        manager.requestLocationUpdates(
            provider,
            LOCATION_MIN_TIME_MS,
            LOCATION_MIN_DISTANCE_M,
            frameworkListener,
        )
    }

    override fun stopUpdates() {
        listener?.let { locationManager?.removeUpdates(it) }
        listener = null
    }

    private companion object {
        const val LOCATION_MIN_TIME_MS = 60_000L
        const val LOCATION_MIN_DISTANCE_M = 100f
    }
}

private fun Location.toGeoFix(): GeoFix = GeoFix(latitude, longitude, altitude, time)
