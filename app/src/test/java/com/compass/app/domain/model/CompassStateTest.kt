package com.compass.app.domain.model

import android.hardware.SensorManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassAccuracyTest {

    @Test
    fun `fromSensorStatus maps each constant`() {
        assertEquals(
            CompassAccuracy.HIGH,
            CompassAccuracy.fromSensorStatus(SensorManager.SENSOR_STATUS_ACCURACY_HIGH),
        )
        assertEquals(
            CompassAccuracy.MEDIUM,
            CompassAccuracy.fromSensorStatus(SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM),
        )
        assertEquals(
            CompassAccuracy.LOW,
            CompassAccuracy.fromSensorStatus(SensorManager.SENSOR_STATUS_ACCURACY_LOW),
        )
        assertEquals(
            CompassAccuracy.UNRELIABLE,
            CompassAccuracy.fromSensorStatus(SensorManager.SENSOR_STATUS_UNRELIABLE),
        )
        assertEquals(
            CompassAccuracy.UNRELIABLE,
            CompassAccuracy.fromSensorStatus(SensorManager.SENSOR_STATUS_NO_CONTACT),
        )
        assertEquals(CompassAccuracy.UNKNOWN, CompassAccuracy.fromSensorStatus(Int.MIN_VALUE))
    }

    @Test
    fun `needsCalibration is true only for LOW and UNRELIABLE`() {
        assertTrue(CompassAccuracy.LOW.needsCalibration)
        assertTrue(CompassAccuracy.UNRELIABLE.needsCalibration)
        assertFalse(CompassAccuracy.HIGH.needsCalibration)
        assertFalse(CompassAccuracy.MEDIUM.needsCalibration)
        assertFalse(CompassAccuracy.UNKNOWN.needsCalibration)
    }
}

class ToCardinalTest {

    @Test
    fun `main cardinals`() {
        assertEquals("N", 0f.toCardinal())
        assertEquals("E", 90f.toCardinal())
        assertEquals("S", 180f.toCardinal())
        assertEquals("W", 270f.toCardinal())
    }

    @Test
    fun `intercardinals`() {
        assertEquals("NE", 45f.toCardinal())
        assertEquals("SE", 135f.toCardinal())
        assertEquals("SW", 225f.toCardinal())
        assertEquals("NW", 315f.toCardinal())
    }

    @Test
    fun `boundary just before north`() {
        assertEquals("N", 359f.toCardinal())
        assertEquals("N", 1f.toCardinal())
    }

    @Test
    fun `handles negative and out of range`() {
        assertEquals("N", (-1f).toCardinal())
        assertEquals("E", 450f.toCardinal())
    }
}
