package app.efrei.compass.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.efrei.compass.domain.location.PermissionChecker

/** [PermissionChecker] backed by [ContextCompat.checkSelfPermission] against the application context. */
class AndroidPermissionChecker(context: Context) : PermissionChecker {
    private val appContext = context.applicationContext

    override fun hasCoarseLocationPermission(): Boolean = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
}
