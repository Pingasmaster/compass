package com.compass.app.baselineprofile

import android.content.Intent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET_PACKAGE = "com.compass.app"
private const val MAIN_ACTIVITY = "com.compass.app.MainActivity"

/**
 * Captures baseline + startup profiles for the Compass app.
 *
 * CUJs:
 *  1. cold_start_mainactivity
 *  2. grant_location_permission
 *  3. rotate_to_north (best-effort sensor tick)
 *  4. toggle_true_north_vs_magnetic (best-effort)
 *
 * Does NOT depend on :app on the classpath - drives the installed app via
 * UiAutomator like the other apps' generators.
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
            val launchIntent = Intent().apply {
                setClassName(TARGET_PACKAGE, MAIN_ACTIVITY)
            }
            startActivityAndWait(launchIntent)
            device.waitForIdle()
            Thread.sleep(1_500)

            val allowByDesc = device.findObject(
                By.res("com.android.permissioncontroller:id/permission_allow_foreground_only_button"),
            )
            if (allowByDesc != null) {
                allowByDesc.click()
            } else {
                device.findObject(By.textContains("Allow"))?.click()
            }
            device.waitForIdle()
            Thread.sleep(500)

            device.findObject(By.descContains("North"))
            Thread.sleep(1_500)

            device.findObject(By.desc("Settings"))?.click()
            device.waitForIdle()
            Thread.sleep(800)

            device.findObject(By.textContains("True North"))?.click()
            device.waitForIdle()
            Thread.sleep(500)

            device.pressBack()
            device.waitForIdle()
            Thread.sleep(500)
        }
    }
}
