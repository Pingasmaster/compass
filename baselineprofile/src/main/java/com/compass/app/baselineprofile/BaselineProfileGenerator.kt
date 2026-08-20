package com.compass.app.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE = "com.compass.app"

/**
 * Captures baseline + startup profiles for the Compass app.
 *
 * CUJs:
 *  1. cold_start_mainactivity
 *  2. open settings / toggle true north (best-effort; may show an in-app rationale)
 *
 * Guest RAM/CPU is raised in scripts/gmd_ensure_avd.sh (4G / 6 cores).
 * Leave the app in the foreground when collect returns: MacrobenchmarkScope
 * sleeps 5s then SAVE_PROFILE, and ART requires a live process.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun collectBaselineProfiles() {
        rule.collect(
            packageName = TARGET_PACKAGE,
            maxIterations = 5,
            stableIterations = 3,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            device.waitForIdle()

            val allowForeground = device.findObject(
                By.res("com.android.permissioncontroller:id/permission_allow_foreground_only_button"),
            )
            if (allowForeground != null) {
                allowForeground.click()
            } else {
                device.findObject(By.textContains("Allow"))?.click()
            }
            device.waitForIdle()

            device.findObject(By.desc("Settings"))?.click()
            device.waitForIdle()

            device.findObject(By.textContains("True North"))?.click()
            device.waitForIdle()

            device.pressBack()
            device.waitForIdle()
        }
    }
}
