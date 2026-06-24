/*
 * Startup benchmarks for Compass.
 *
 * Measures the cold-start time of MainActivity, with and without the
 * baseline profile applied. Run from the project root with:
 *
 *   ./gradlew :macrobenchmark:pixel6Api33AndroidTest
 *
 * These benchmarks are NOT executed in the local CI build — they
 * require a managed device (Pixel 6 / API 33 / AOSP). They are intended
 * for the optional `baseline-profile` GitHub Actions workflow which has
 * KVM access.
 */
package com.compass.app.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE_NAME = "com.compass.app"

@RunWith(AndroidJUnit4::class)
class StartupBenchmarks {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldNone() = benchmark(CompilationMode.None())

    @Test
    fun coldBaselineProfile() =
        benchmark(
            CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
        )

    private fun benchmark(compilationMode: CompilationMode) {
        rule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = 10,
            setupBlock = { pressHome() },
        ) {
            // Use the MacrobenchmarkScope-receiver trailing lambda so the
            // startActivityAndWait extension defined on it is in scope.
            startActivityAndWait()
        }
    }
}
