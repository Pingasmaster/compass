package com.compass.app.smoke

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.compass.app.testing.SmokeTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "com.compass.app"
private const val LAUNCH_TIMEOUT_MS = 60_000L

/**
 * Tier 2 on-device smoke: cold-start keeps [PACKAGE] in the foreground.
 */
@SmokeTest
@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {

    @Test
    fun app_launches_and_staysInForeground() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.pressHome()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE)
        assertNotNull("no launch intent for $PACKAGE", intent)
        context.startActivity(
            intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )

        assertTrue(
            "$PACKAGE never reached the foreground",
            device.wait(Until.hasObject(By.pkg(PACKAGE).depth(0)), LAUNCH_TIMEOUT_MS),
        )
        assertTrue(
            "$PACKAGE left the foreground after launch",
            device.hasObject(By.pkg(PACKAGE).depth(0)),
        )
    }
}
