package com.compass.app.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.compass.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Startup benchmarks — measures cold start time of MainActivity with and without
 * the baseline profile baked into the release AAB.
 *
 * Pairing `CompilationMode.None()` (no AOT, baseline profile OFF) against
 * `CompilationMode.Partial(BaselineProfileMode.Require)` (baseline profile ON)
 * is what surfaces the PGO delta. Without the profile, `Require` will fail the
 * run, so the test class is intentionally split into two separate @Test methods.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {

    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldNone() {
        rule.measureRepeated(
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.None(),
            startupMode = StartupMode.COLD,
            iterations = 10,
            setupBlock = { pressHome() },
            measureBlock = { startActivityAndWait(MainActivity::class.java) },
        )
    }

    @Test
    fun coldBaselineProfile() {
        rule.measureRepeated(
            metrics = listOf(StartupTimingMetric()),
            compilationMode = CompilationMode.Partial(
                baselineProfileMode = BaselineProfileMode.Require,
            ),
            startupMode = StartupMode.COLD,
            iterations = 10,
            setupBlock = { pressHome() },
            measureBlock = { startActivityAndWait(MainActivity::class.java) },
        )
    }

    /**
     * Pre-grant the coarse-location permission once before the measured block so
     * the system permission dialog never fires mid-iteration (which would skew
     * startup timing). We rely on UiAutomator's text selector because the system
     * permission dialog has no stable resource id across AOSP vs. Pixel builds.
     */
    @Suppress("unused")
    private fun grantLocationPermission() {
        val allow = device.findObject(
            By.res("com.android.permissioncontroller:id/permission_allow_foreground_only_button"),
        ) ?: device.findObject(By.textContains("Allow"))
        allow?.click()
        device.waitForIdle()
    }
}