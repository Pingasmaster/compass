package com.compass.app.domain.sensor

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.location.Location
import android.view.Display
import android.view.Surface
import androidx.core.content.ContextCompat
import com.compass.app.domain.model.CompassAccuracy
import com.compass.app.domain.model.CompassReading
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine

/**
 * Wraps [SensorManager] for a compass. Prefers TYPE_ROTATION_VECTOR (gyro+accel+mag fusion),
 * falls back to TYPE_GEOMAGNETIC_ROTATION_VECTOR on gyro-less devices.
 *
 * Public API is a cold [readings] Flow: collectors drive registration via [callbackFlow],
 * so the sensor only runs while something is observing. True-north and location inputs are
 * MutableStateFlows combined into the output so the ViewModel can update them independently.
 */
class CompassSensor(context: Context) {

    private val appContext = context.applicationContext
    private val sensorManager: SensorManager? =
        ContextCompat.getSystemService(appContext, SensorManager::class.java)
    private val displayManager: DisplayManager? =
        ContextCompat.getSystemService(appContext, DisplayManager::class.java)

    private val rotationSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    private val trueNorth = MutableStateFlow(false)
    private val declination = MutableStateFlow(0f)

    val hasSensor: Boolean get() = rotationSensor != null

    fun setTrueNorthEnabled(enabled: Boolean) {
        trueNorth.value = enabled
    }

    fun updateLocation(location: Location) {
        declination.value = GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            location.altitude.toFloat(),
            // Prefer the fix time where available — `GeomagneticField` interprets the
            // millis as "time for which to compute the field", which conceptually matches
            // when the location was observed, not now.
            if (location.time > 0L) location.time else System.currentTimeMillis(),
        ).declination
    }

    /**
     * Cold flow of compass readings. Registers the sensor on first collector, unregisters
     * on last. Also emits when true-north or declination changes so downstream state
     * reflects toggle changes even without a new sensor event.
     */
    val readings: Flow<CompassReading> = combine(
        rawReadings(),
        trueNorth,
        declination,
    ) { raw, useTrue, decl ->
        val correctedAzimuth = if (useTrue) raw.azimuth + decl else raw.azimuth
        raw.copy(
            azimuth = ((correctedAzimuth % 360f) + 360f) % 360f,
            declination = decl,
        )
    }

    private fun rawReadings(): Flow<CompassReading> = callbackFlow {
        val manager = sensorManager
        val sensor = rotationSensor
        val initial = CompassReading(hasSensor = sensor != null)
        trySend(initial)
        if (manager == null || sensor == null) {
            awaitClose { }
            return@callbackFlow
        }

        val smoother = AzimuthSmoother(alpha = 0.15f)
        val rotationMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        var latest = initial

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
                    event.sensor.type != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
                ) return

                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                val (axisX, axisY) = when (currentDisplayRotation()) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
                SensorManager.getOrientation(remappedMatrix, orientation)

                val rawAzimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                val smoothed = smoother.update(rawAzimuthDeg)

                latest = latest.copy(
                    azimuth = smoothed,
                    pitch = Math.toDegrees(orientation[1].toDouble()).toFloat(),
                    roll = Math.toDegrees(orientation[2].toDouble()).toFloat(),
                )
                trySend(latest)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                latest = latest.copy(accuracy = CompassAccuracy.fromSensorStatus(accuracy))
                trySend(latest)
            }
        }

        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { manager.unregisterListener(listener) }
    }

    private fun currentDisplayRotation(): Int {
        // DisplayManager is safe to query from the application context, unlike
        // Context.getDisplay() which throws UnsupportedOperationException there.
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        return display?.rotation ?: Surface.ROTATION_0
    }
}
