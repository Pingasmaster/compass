package com.compass.app.domain.model

/**
 * Framework-free location fix, decoupled from android.location.Location so JVM
 * unit tests can construct one. [timeMillis] is the fix time as reported by the
 * source; 0 means unknown.
 */
data class GeoFix(val latitude: Double, val longitude: Double, val altitude: Double, val timeMillis: Long)
