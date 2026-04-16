package com.compass.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "compass_prefs")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class Responsiveness { SLOWEST, SLOW, NORMAL, FAST, FASTEST }

class UserPreferences(private val context: Context) {
    private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
    private val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color")
    private val OLED_BLACK_KEY = booleanPreferencesKey("oled_black")
    private val TRUE_NORTH_KEY = booleanPreferencesKey("true_north")
    private val RESPONSIVENESS_KEY = stringPreferencesKey("responsiveness")

    val themeMode: Flow<ThemeMode> =
        context.dataStore.data.map {
            when (it[THEME_MODE_KEY]) {
                "light" -> ThemeMode.LIGHT
                "dark" -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
        }

    val dynamicColorEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[DYNAMIC_COLOR_KEY] ?: true }

    val oledBlackEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[OLED_BLACK_KEY] ?: false }

    val trueNorthEnabled: Flow<Boolean> =
        context.dataStore.data.map { it[TRUE_NORTH_KEY] ?: false }

    val responsiveness: Flow<Responsiveness> =
        context.dataStore.data.map {
            when (it[RESPONSIVENESS_KEY]) {
                "slowest" -> Responsiveness.SLOWEST
                "slow" -> Responsiveness.SLOW
                "fast" -> Responsiveness.FAST
                "fastest" -> Responsiveness.FASTEST
                else -> Responsiveness.NORMAL
            }
        }

    suspend fun setResponsiveness(mode: Responsiveness) {
        context.dataStore.edit {
            it[RESPONSIVENESS_KEY] = when (mode) {
                Responsiveness.SLOWEST -> "slowest"
                Responsiveness.SLOW -> "slow"
                Responsiveness.NORMAL -> "normal"
                Responsiveness.FAST -> "fast"
                Responsiveness.FASTEST -> "fastest"
            }
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit {
            it[THEME_MODE_KEY] = when (mode) {
                ThemeMode.SYSTEM -> "system"
                ThemeMode.LIGHT -> "light"
                ThemeMode.DARK -> "dark"
            }
        }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.dataStore.edit { it[DYNAMIC_COLOR_KEY] = enabled }
    }

    suspend fun setOledBlack(enabled: Boolean) {
        context.dataStore.edit { it[OLED_BLACK_KEY] = enabled }
    }

    suspend fun setTrueNorth(enabled: Boolean) {
        context.dataStore.edit { it[TRUE_NORTH_KEY] = enabled }
    }
}
