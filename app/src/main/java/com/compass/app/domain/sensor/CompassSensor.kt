package com.compass.app.domain.sensor

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import com.compass.app.domain.model.CompassAccuracy
import com.compass.app.domain.model.CompassReading
import com.compass.app.domain.model.GeoFix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlin.math.roundToInt

/**
 * Wraps [SensorManager] for a compass. Prefers TYPE_ROTATION_VECTOR (gyro+accel+mag fusion),
 * falls back to TYPE_GEOMAGNETIC_ROTATION_VECTOR on gyro-less devices.
 *
 * Public API is a cold [readings] Flow: collectors drive registration via [callbackFlow],
 * so the sensor only runs while something is observing. True-north and location inputs are
 * MutableStateFlows combined into the output so the ViewModel can update them independently.
 */
class CompassSensor(context: Context) : HeadingSource {

    private val appContext = context.applicationContext
    private val sensorManager: SensorManager? =
        appContext.getSystemService(SensorManager::class.java)
    private val displayManager: DisplayManager? =
        appContext.getSystemService(DisplayManager::class.java)

    private val rotationSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    private val trueNorth = MutableStateFlow(false)
    private val declination = MutableStateFlow(0f)

    override val hasSensor: Boolean get() = rotationSensor != null

    override fun setTrueNorthEnabled(enabled: Boolean) {
        trueNorth.value = enabled
    }

    override fun updateLocation(fix: GeoFix) {
        declination.value = GeomagneticField(
            fix.latitude.toFloat(),
            fix.longitude.toFloat(),
            fix.altitude.toFloat(),
            // Prefer the fix time where available - `GeomagneticField` interprets the
            // millis as "time for which to compute the field", which conceptually matches
            // when the location was observed, not now.
            if (fix.timeMillis > 0L) fix.timeMillis else System.currentTimeMillis(),
        ).declination
    }

    /**
     * Cold flow of compass readings. Registers the sensor on first collector, unregisters
     * on last. Also emits when true-north or declination changes so downstream state
     * reflects toggle changes even without a new sensor event.
     */
    override val readings: Flow<CompassReading> = combine(
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

        // Cache the display rotation instead of re-querying DisplayManager on every
        // sensor tick (~50 Hz). A DisplayListener keeps the cache in sync for the
        // rare runtime rotation.
        var displayRotation = currentDisplayRotation()
        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    displayRotation = currentDisplayRotation()
                }
            }
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }
        displayManager?.registerDisplayListener(displayListener, null)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
                    event.sensor.type != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
                ) {
                    return
                }

                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                val (axisX, axisY) = when (displayRotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remappedMatrix)
                SensorManager.getOrientation(remappedMatrix, orientation)

                val rawAzimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
                val smoothed = smoother.update(rawAzimuthDeg)

                // Quantize to 0.1 deg and drop unchanged readings: the EMA output
                // jitters slightly on every ~20 ms event even when the device is
                // motionless, and forwarding each tick keeps the whole UI tree
                // recomposing ~50x/s. Sub-0.1-degree changes are invisible - the
                // rose spring animation smooths far coarser steps than that.
                val next = latest.copy(
                    azimuth = quantizeDegrees(smoothed),
                    pitch = quantizeDegrees(Math.toDegrees(orientation[1].toDouble()).toFloat()),
                    roll = quantizeDegrees(Math.toDegrees(orientation[2].toDouble()).toFloat()),
                )
                if (next != latest) {
                    latest = next
                    trySend(latest)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                latest = latest.copy(accuracy = CompassAccuracy.fromSensorStatus(accuracy))
                trySend(latest)
            }
        }

        // Collection is tied to RESUMED with a zero WhileSubscribed timeout so
        // this registration is torn down across the permission-dialog pause and
        // rebuilt on resume. Leaving the listener registered is what froze the
        // heading at 0 deg until the process was killed.
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        awaitClose {
            manager.unregisterListener(listener)
            displayManager?.unregisterDisplayListener(displayListener)
        }
    }

    private fun quantizeDegrees(value: Float): Float = (value * DEGREE_QUANTIZATION).roundToInt() / DEGREE_QUANTIZATION

    private fun currentDisplayRotation(): Int {
        // DisplayManager is safe to query from the application context, unlike
        // Context.getDisplay() which throws UnsupportedOperationException there.
        // Known limitation: this returns DEFAULT_DISPLAY's rotation, which is
        // wrong on foldables / external-display activities. Fine for phones.
        val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        return display?.rotation ?: Surface.ROTATION_0
    }

    private companion object {
        // 1/step: 10f quantizes degrees to 0.1-degree steps.
        const val DEGREE_QUANTIZATION = 10f
    }
}
