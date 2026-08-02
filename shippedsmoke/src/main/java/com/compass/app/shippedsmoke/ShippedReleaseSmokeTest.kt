package com.compass.app.shippedsmoke

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "com.compass.app"
private const val LAUNCH_TIMEOUT_MS = 60_000L
private const val UI_TIMEOUT_MS = 30_000L

/**
 * Smoke test for the APK as SHIPPED. Deliberately shallow: cold-start reaches
 * the compass UI under real R8, then the process stays in the foreground.
 */
@RunWith(AndroidJUnit4::class)
class ShippedReleaseSmokeTest {

    private lateinit var device: UiDevice

    private fun launchApp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressHome()
        val context = InstrumentationRegistry.getInstrumentation().context
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE)
        assertNotNull("no launch intent for $PACKAGE - is the release APK installed?", intent)
        context.startActivity(intent!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK))

        assertTrue(
            "$PACKAGE never reached the foreground - the shipped APK most likely crashed on launch",
            device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), LAUNCH_TIMEOUT_MS),
        )
    }

    @Test
    fun shippedApk_coldStarts_withoutCrash() {
        launchApp()

        val settingsVisible = device.wait(Until.hasObject(By.desc("Settings")), UI_TIMEOUT_MS)
        assertTrue(
            "Settings control never rendered - R8 likely stripped Compose UI",
            settingsVisible,
        )
        assertTrue(
            "$PACKAGE left the foreground after launch",
            device.hasObject(By.pkg(PACKAGE).depth(0)),
        )
    }
}
