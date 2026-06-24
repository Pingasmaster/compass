package com.compass.app

import android.app.Application
import android.content.ComponentCallbacks2
import com.compass.app.data.preferences.UserPreferences

class CompassApplication : Application() {

    lateinit var userPreferences: UserPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        userPreferences = UserPreferences(this)
    }

    /**
     * Voluntarily release caches the OS can regenerate cheaply. Per the
     * Android 17 memory-efficiency guidance, focus on the two levels the OS
     * raises when the UI is no longer visible: TRIM_MEMORY_UI_HIDDEN and
     * TRIM_MEMORY_BACKGROUND. The compass app holds no significant
     * in-memory caches (DataStore-backed UserPreferences is cheap to
     * re-read), so the override is the canonical landing pad for future
     * trim hooks.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            -> Unit
        }
    }
}
