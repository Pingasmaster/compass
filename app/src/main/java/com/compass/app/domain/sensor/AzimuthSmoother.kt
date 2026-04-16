package com.compass.app.domain.sensor

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Low-pass filter over angles using separate sin/cos EMAs so that the
 * 0°/360° seam is handled correctly. Naïve EMA on raw degrees would
 * generate a 359° → 0° average of ~180°, which is visibly wrong.
 *
 * @param alpha 0..1 — higher tracks faster, lower is smoother.
 */
class AzimuthSmoother(private val alpha: Float = 0.15f) {
    init {
        require(alpha in 0f..1f) { "alpha must be in [0, 1], got $alpha" }
    }

    private var sinAcc: Float = 0f
    private var cosAcc: Float = 0f
    private var initialised = false

    fun reset() {
        sinAcc = 0f
        cosAcc = 0f
        initialised = false
    }

    /** Feed a degree reading in any range; returns smoothed 0..360 degrees. */
    fun update(degrees: Float): Float {
        val rad = Math.toRadians(degrees.toDouble())
        val s = sin(rad).toFloat()
        val c = cos(rad).toFloat()
        if (!initialised) {
            sinAcc = s
            cosAcc = c
            initialised = true
        } else {
            sinAcc = alpha * s + (1f - alpha) * sinAcc
            cosAcc = alpha * c + (1f - alpha) * cosAcc
        }
        val smoothed = Math.toDegrees(atan2(sinAcc, cosAcc).toDouble()).toFloat()
        return ((smoothed % 360f) + 360f) % 360f
    }
}

/**
 * Unwraps a sequence of [0, 360) angles into a monotonic-ish float so an
 * [androidx.compose.animation.core.Animatable] takes the short route across
 * the 0/360 seam. Returns the cumulative angle that differs from [previous]
 * by the shortest signed delta to [newAngle].
 *
 * At an exact ±180° antipode the naïve `(x % 360 + 540) % 360 - 180` formula
 * rounds delta to 0, making the rose freeze. We bias toward +180 whenever the
 * signed delta lands on −180 so opposite headings always animate.
 */
fun unwrapAngle(previous: Float, newAngle: Float): Float {
    var diff = ((newAngle - previous) % 360f + 540f) % 360f - 180f
    if (diff == -180f) diff = 180f
    return previous + diff
}
