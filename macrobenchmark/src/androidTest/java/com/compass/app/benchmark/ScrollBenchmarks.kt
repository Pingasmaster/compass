/*
 * Frame-timing benchmarks for Compass.
 *
 * Compass is not a scrolling-list app, so we approximate scroll-equivalent
 * movement by swiping across the compass rose. This still drives the
 * rendering path (CompassRose drawing, HeadingReadout recomposition,
 * sensor-driven updates) which is the actual PGO hot path on this app.
 *
 * Run from the project root with:
 *
 *   ./gradlew :macrobenchmark:pixel7aApi37DebugAndroidTest
 */
package com.compass.app.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE_NAME = "com.compass.app"

@RunWith(AndroidJUnit4::class)
class ScrollBenchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun scrollNone() = benchmark(CompilationMode.None())

    @Test
    fun scrollBaselineProfile() = benchmark(
        CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
    )

    private fun benchmark(compilationMode: CompilationMode) {
        rule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = compilationMode,
            iterations = 5,
            setupBlock = { pressHome() },
        ) {
            // Use the MacrobenchmarkScope-receiver trailing lambda so the
            // device / startActivityAndWait / swipe extensions are in scope.
            startActivityAndWait()
            device.waitForIdle()

            val rose = device.findObject(By.descContains("North"))
            val bounds = rose?.visibleBounds ?: return@measureRepeated
            val centerX = bounds.centerX()
            val centerY = bounds.centerY()
            val radius = (bounds.width() / 2).coerceAtLeast(50)

            repeat(20) { step ->
                val angle = (step * 18f) // 360 deg / 20 swipes
                val fromX = centerX + (kotlin.math.cos(Math.toRadians(angle.toDouble())) * radius).toInt()
                val fromY = centerY + (kotlin.math.sin(Math.toRadians(angle.toDouble())) * radius).toInt()
                val toX = centerX + (kotlin.math.cos(Math.toRadians((angle + 30f).toDouble())) * radius).toInt()
                val toY = centerY + (kotlin.math.sin(Math.toRadians((angle + 30f).toDouble())) * radius).toInt()
                device.swipe(fromX, fromY, toX, toY, 8)
            }
        }
    }
}
