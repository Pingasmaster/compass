package com.compass.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.compass.app.data.preferences.ThemeMode
import com.compass.app.ui.compass.CompassScreen
import com.compass.app.ui.theme.CompassTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = (application as CompassApplication).userPreferences

        setContent {
            val themeMode by prefs.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val dynamicColor by prefs.dynamicColorEnabled.collectAsStateWithLifecycle(initialValue = true)
            val oledBlack by prefs.oledBlackEnabled.collectAsStateWithLifecycle(initialValue = false)

            val isDark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            CompassTheme(
                darkTheme = isDark,
                dynamicColor = dynamicColor,
                oledBlack = oledBlack,
            ) {
                CompassScreen()
            }
        }
    }
}
