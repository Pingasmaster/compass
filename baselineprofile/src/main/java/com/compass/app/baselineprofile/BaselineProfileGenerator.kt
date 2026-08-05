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
 *  2. grant_location_permission (best-effort)
 *  3. open settings / toggle true north (best-effort)
 *
 * Kept short and sleep-light so the 2G GMD guest does not trip LMK during
 * google_apis cold boot + profile collection.
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
