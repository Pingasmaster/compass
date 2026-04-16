package com.compass.app

import android.app.Application
import com.compass.app.data.preferences.UserPreferences

class CompassApplication : Application() {

    lateinit var userPreferences: UserPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        userPreferences = UserPreferences(this)
    }
}
