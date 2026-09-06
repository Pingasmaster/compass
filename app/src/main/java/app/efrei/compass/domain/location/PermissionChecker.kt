package app.efrei.compass.domain.location

/** Deliberately narrow: ACCESS_COARSE_LOCATION is the only runtime permission the app declares. */
fun interface PermissionChecker {
    fun hasCoarseLocationPermission(): Boolean
}
