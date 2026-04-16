package com.compass.app.domain.sensor

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.view.Surface
import androidx.core.content.ContextCompat
import com.compass.app.domain.model.CompassAccuracy
import com.compass.app.domain.model.CompassReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wraps [SensorManager] for a compass. Prefers TYPE_ROTATION_VECTOR (gyro+accel+mag fusion),
 * falls back to TYPE_GEOMAGNETIC_ROTATION_VECTOR on gyro-less devices.
 *
 * Call [start] when the screen becomes visible and [stop] when it hides.
 * Optionally call [updateLocation] to feed GPS fix for true-north declination correction.
 */
class CompassSensor(context: Context) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager: SensorManager? =
        ContextCompat.getSystemService(appContext, SensorManager::class.java)

    private val rotationSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    private val smoother = AzimuthSmoother(alpha = 0.15f)
    private var declination: Float = 0f
    private var useTrueNorth: Boolean = false

    private val rotationMatrix = FloatArray(9)
    private val remappedMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private val _readings = MutableStateFlow(
        CompassReading(hasSensor = rotationSensor != null)
    )
    val readings: StateFlow<CompassReading> = _readings.asStateFlow()

    fun start() {
        val manager = sensorManager ?: return
        val sensor = rotationSensor ?: return
        smoother.reset()
        manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    fun setTrueNorthEnabled(enabled: Boolean) {
        useTrueNorth = enabled
    }

    fun updateLocation(location: Location) {
        declination = GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            location.altitude.toFloat(),
            System.currentTimeMillis(),
        ).declination
    }

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
        val corrected = if (useTrueNorth) rawAzimuthDeg + declination else rawAzimuthDeg
        val smoothed = smoother.update(corrected)

        _readings.value = _readings.value.copy(
            azimuth = smoothed,
            pitch = Math.toDegrees(orientation[1].toDouble()).toFloat(),
            roll = Math.toDegrees(orientation[2].toDouble()).toFloat(),
            declination = declination,
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        _readings.value = _readings.value.copy(
            accuracy = CompassAccuracy.fromSensorStatus(accuracy)
        )
    }

    private fun currentDisplayRotation(): Int {
        return try {
            @Suppress("UNNECESSARY_SAFE_CALL")
            (appContext.display?.rotation) ?: Surface.ROTATION_0
        } catch (t: Throwable) {
            Surface.ROTATION_0
        }
    }
}
