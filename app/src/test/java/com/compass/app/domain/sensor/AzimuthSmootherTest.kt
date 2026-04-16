package com.compass.app.domain.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AzimuthSmootherTest {

    @Test
    fun `first update returns the input unchanged`() {
        val s = AzimuthSmoother(alpha = 0.2f)
        assertEquals(42f, s.update(42f), 0.01f)
    }

    @Test
    fun `repeated same input converges to that input`() {
        val s = AzimuthSmoother(alpha = 0.2f)
        repeat(200) { s.update(123f) }
        assertEquals(123f, s.update(123f), 0.01f)
    }

    @Test
    fun `output is always in 0 to 360`() {
        val s = AzimuthSmoother()
        for (value in listOf(-180f, -45f, 0f, 45f, 180f, 359f, 361f, 720f)) {
            val out = s.update(value)
            assertTrue("out $out out of range for input $value", out in 0f..360f)
        }
    }

    @Test
    fun `seam from 359 to 1 does not overshoot through 180`() {
        // Naïve EMA would average 359 and 1 to 180. sin/cos filter must avoid that.
        val s = AzimuthSmoother(alpha = 0.5f)
        s.update(359f)
        val out = s.update(1f)
        // Expected smoothed value near 0 (or 360), certainly not ~180.
        val wrapped = if (out > 180f) out - 360f else out
        assertTrue("unexpected seam overshoot: $out", Math.abs(wrapped) < 5f)
    }

    @Test
    fun `reset clears history`() {
        val s = AzimuthSmoother(alpha = 0.2f)
        repeat(50) { s.update(200f) }
        s.reset()
        assertEquals(10f, s.update(10f), 0.01f)
    }
}

class UnwrapAngleTest {

    @Test
    fun `zero delta returns previous`() {
        assertEquals(42f, unwrapAngle(42f, 42f), 0.001f)
    }

    @Test
    fun `small forward delta adds directly`() {
        assertEquals(52f, unwrapAngle(42f, 52f), 0.001f)
    }

    @Test
    fun `small backward delta subtracts directly`() {
        assertEquals(32f, unwrapAngle(42f, 32f), 0.001f)
    }

    @Test
    fun `shortest path across zero seam goes backwards past zero`() {
        // prev=5, new=355: shortest delta is -10 (not +350).
        assertEquals(-5f, unwrapAngle(5f, 355f), 0.001f)
    }

    @Test
    fun `shortest path across zero seam goes forwards past 360`() {
        // prev=355, new=5: shortest delta is +10 (not -350).
        assertEquals(365f, unwrapAngle(355f, 5f), 0.001f)
    }

    @Test
    fun `repeated wrapping keeps extending in one direction`() {
        var cumulative = 0f
        val sequence = listOf(350f, 340f, 330f, 320f, 10f, 20f)
        val expectedDeltas = listOf(-10f, -10f, -10f, -10f, 50f, 10f)
        for ((i, new) in sequence.withIndex()) {
            val next = unwrapAngle(cumulative, new)
            assertEquals(
                "step $i delta",
                expectedDeltas[i],
                next - cumulative,
                0.001f,
            )
            cumulative = next
        }
    }

    @Test
    fun `antipode distance resolves deterministically`() {
        // Exactly 180° apart — the formula rounds toward -180 by convention.
        val out = unwrapAngle(0f, 180f)
        assertEquals(-180f, out, 0.001f)
    }
}
