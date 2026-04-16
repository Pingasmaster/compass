package com.compass.app.domain.model

import android.hardware.SensorManager
import androidx.compose.runtime.Immutable

enum class CompassAccuracy {
    UNKNOWN,
    UNRELIABLE,
    LOW,
    MEDIUM,
    HIGH;

    val needsCalibration: Boolean
        get() = this == UNRELIABLE || this == LOW

    companion object {
        fun fromSensorStatus(status: Int): CompassAccuracy = when (status) {
            SensorManager.SENSOR_STATUS_NO_CONTACT,
            SensorManager.SENSOR_STATUS_UNRELIABLE -> UNRELIABLE
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> LOW
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> MEDIUM
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> HIGH
            else -> UNKNOWN
        }
    }
}

@Immutable
data class CompassReading(
    val azimuth: Float = 0f,      // degrees, 0..360, magnetic north by default
    val pitch: Float = 0f,        // degrees
    val roll: Float = 0f,         // degrees
    val declination: Float = 0f,  // degrees, applied when trueNorth is on
    val accuracy: CompassAccuracy = CompassAccuracy.UNKNOWN,
    val hasSensor: Boolean = true,
)

// Cardinal abbreviations are kept in English by design — "N/E/S/W" is the
// conventional compass notation across most locales this app targets, and
// localising them would mismatch the rose letters drawn by CompassRose.
fun Float.toCardinal(): String {
    val normalised = ((this % 360f) + 360f) % 360f
    return when {
        normalised < 22.5f || normalised >= 337.5f -> "N"
        normalised < 67.5f -> "NE"
        normalised < 112.5f -> "E"
        normalised < 157.5f -> "SE"
        normalised < 202.5f -> "S"
        normalised < 247.5f -> "SW"
        normalised < 292.5f -> "W"
        else -> "NW"
    }
}
