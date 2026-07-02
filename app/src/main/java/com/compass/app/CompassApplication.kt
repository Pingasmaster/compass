package com.compass.app

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.util.Log
import com.compass.app.data.preferences.UserPreferences

class CompassApplication : Application() {

    lateinit var userPreferences: UserPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        // Wire StrictMode policies in debug builds to surface main-thread disk/network
        // calls and leaked Closeables/Activities during development. Must run before any
        // UI-thread work so the Looper's violation handler is in place for early calls.
        StrictModeBootstrap.init(this)
        userPreferences = UserPreferences(this)
        // Capture previous-process exit reasons on this launch so ANRs, OOMs, and native
        // crashes that happened while the app was dead become visible in logcat instead
        // of just "process died" with no diagnostic. minSdk=31 ≥ API 30 (ApplicationExitInfo
        // added in API 30) so no runtime SDK gate is required.
        capturePreviousExitReasons()
    }

    private fun capturePreviousExitReasons() {
        val am = getSystemService(ActivityManager::class.java) ?: return
        try {
            am.getHistoricalProcessExitReasons(packageName, android.os.Process.myPid(), MAX_EXIT_REASONS)
                .filter { it.reason != ApplicationExitInfo.REASON_OTHER }
                .filter { it.reason != ApplicationExitInfo.REASON_EXIT_SELF }
                .forEach { info ->
                    Log.w(
                        TAG,
                        "previous exit reason=${info.reason} importance=${info.importance} " +
                            "pss=${info.pss} rss=${info.rss} description=${info.description}",
                    )
                }
        } catch (_: SecurityException) {
            // Defensive: never let diagnostics crash app startup. SecurityException is
            // the only checked-like failure mode the ActivityManager API can throw here
            // (process gone, permission flip mid-call). Other Throwables are intentionally
            // not swallowed — they'd indicate a programming bug worth surfacing.
            Log.w(TAG, "getHistoricalProcessExitReasons denied")
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "getHistoricalProcessExitReasons invalid args", e)
        }
    }

    /**
     * Voluntarily release caches the OS can regenerate cheaply. Per the
     * Android 17 memory-efficiency guidance, focus on the two levels the OS
     * raises when the UI is no longer visible: TRIM_MEMORY_UI_HIDDEN and
     * TRIM_MEMORY_BACKGROUND. The compass app holds no significant
     * in-memory caches (DataStore-backed UserPreferences is cheap to
     * re-read), so the override is the canonical landing pad for future
     * trim hooks. Remaining trim levels are listed explicitly to satisfy
     * SwitchIntDef lint — they remain no-ops. The remaining levels are
     * marked @Deprecated in the platform since API 35 but are still part
     * of the @IntDef that SwitchIntDef validates against.
     */
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            -> Unit

            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            -> Unit
        }
    }

    private companion object {
        // ActivityManager.getHistoricalProcessExitReasons caps how far back it scans;
        // 10 is enough to catch the most recent ANR/OOM cluster without burning memory.
        const val TAG = "CompassApplication"
        const val MAX_EXIT_REASONS = 10
    }
}
