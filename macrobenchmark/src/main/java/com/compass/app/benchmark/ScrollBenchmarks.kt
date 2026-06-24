package com.compass.app.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.compass.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame-timing benchmarks — exercises the main scrollable / animated surface
 * (the compass rose) and reports frame durations with and without the baseline
 * profile.
 *
 * Compass is not a scrolling list app, so we approximate scroll-equivalent
 * movement by swiping across the rose. This still drives the rendering path
 * (CompassRose drawing, HeadingReadout recomposition, sensor-driven updates)
 * which is the actual PGO hot path on this app.
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun scrollNone() {
        rule.measureRepeated(
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.None(),
            iterations = 5,
            setupBlock = { pressHome() },
            measureBlock = {
                startActivityAndWait(MainActivity::class.java)
                device.waitForIdle()

                val rose = device.findObject(By.descContains("North"))
                val bounds = rose?.visibleBounds ?: return@measureRepeated
                val centerX = bounds.centerX()
                val centerY = bounds.centerY()
                val radius = (bounds.width() / 2).coerceAtLeast(50)

                repeat(20) { step ->
                    val angle = (step * 18f) // 360° / 20 swipes
                    val fromX = centerX + (kotlin.math.cos(Math.toRadians(angle.toDouble())) * radius).toInt()
                    val fromY = centerY + (kotlin.math.sin(Math.toRadians(angle.toDouble())) * radius).toInt()
                    val toX = centerX + (kotlin.math.cos(Math.toRadians((angle + 30f).toDouble())) * radius).toInt()
                    val toY = centerY + (kotlin.math.sin(Math.toRadians((angle + 30f).toDouble())) * radius).toInt()
                    device.swipe(fromX, fromY, toX, toY, 8)
                }
            },
        )
    }

    @Test
    fun scrollBaselineProfile() {
        rule.measureRepeated(
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(
                baselineProfileMode = BaselineProfileMode.Require,
            ),
            iterations = 5,
            setupBlock = { pressHome() },
            measureBlock = {
                startActivityAndWait(MainActivity::class.java)
                device.waitForIdle()

                val rose = device.findObject(By.descContains("North"))
                val bounds = rose?.visibleBounds ?: return@measureRepeated
                val centerX = bounds.centerX()
                val centerY = bounds.centerY()
                val radius = (bounds.width() / 2).coerceAtLeast(50)

                repeat(20) { step ->
                    val angle = (step * 18f)
                    val fromX = centerX + (kotlin.math.cos(Math.toRadians(angle.toDouble())) * radius).toInt()
                    val fromY = centerY + (kotlin.math.sin(Math.toRadians(angle.toDouble())) * radius).toInt()
                    val toX = centerX + (kotlin.math.cos(Math.toRadians((angle + 30f).toDouble())) * radius).toInt()
                    val toY = centerY + (kotlin.math.sin(Math.toRadians((angle + 30f).toDouble())) * radius).toInt()
                    device.swipe(fromX, fromY, toX, toY, 8)
                }
            },
        )
    }
}