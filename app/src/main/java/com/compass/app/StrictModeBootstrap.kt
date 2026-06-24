package com.compass.app

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode

/**
 * Wires StrictMode policies in debug builds. Catches regressions before they ship —
 * a single `runOnUiThread { db.query() }` becomes a crash with stack trace during dev
 * instead of a Play-Console ANR.
 *
 * Uses [ApplicationInfo.FLAG_DEBUGGABLE] instead of `BuildConfig.DEBUG` so we don't have
 * to flip `buildConfig = true` (the project keeps it off per its AGP config).
 *
 * Only safe to call from [Application.onCreate]; the policies attach to the main-thread
 * Looper so they need to be set before any UI-thread work runs.
 */
internal object StrictModeBootstrap {

    fun init(application: Application) {
        if (!isDebuggable(application)) return

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build(),
        )

        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build(),
        )
    }

    private fun isDebuggable(application: Application): Boolean =
        (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
