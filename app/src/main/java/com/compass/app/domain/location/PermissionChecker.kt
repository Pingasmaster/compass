package com.compass.app.domain.location

/** Deliberately narrow: ACCESS_COARSE_LOCATION is the only runtime permission the app declares. */
fun interface PermissionChecker {
    fun hasCoarseLocationPermission(): Boolean
}
