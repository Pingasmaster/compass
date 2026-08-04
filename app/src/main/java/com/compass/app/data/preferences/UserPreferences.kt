package com.compass.app.data.preferences

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

// The corruption handler replaces an unreadable compass_prefs.preferences_pb
// with defaults ONCE and re-enables persistence. Without it, CorruptionException
// (an IOException subclass) would be swallowed forever by safeData/writePrefs
// below: every read degrades to defaults and every write is dropped until the
// user clears app data.
private val Context.dataStore by preferencesDataStore(
    name = "compass_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class Responsiveness { SLOWEST, SLOW, NORMAL, FAST, FASTEST }

class UserPreferences(private val context: Context) {
    private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
    private val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color")
    private val OLED_BLACK_KEY = booleanPreferencesKey("oled_black")
    private val TRUE_NORTH_KEY = booleanPreferencesKey("true_north")
    private val RESPONSIVENESS_KEY = stringPreferencesKey("responsiveness")
    private val LOCATION_PROMPTED_KEY = booleanPreferencesKey("location_prompted")

    // Fall back to an empty preferences snapshot on IOException (no free inode,
    // SELinux denial) so a broken prefs store degrades to defaults instead of
    // surfacing as a crash in whatever collector is downstream. Corrupt files
    // never reach this catch: the ReplaceFileCorruptionHandler above resets them.
    private val safeData: Flow<Preferences> =
        context.dataStore.data.catch { e ->
            if (e is IOException) {
                Log.w(TAG, "prefs read failed, emitting defaults", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }

    // Every per-key flow is distinctUntilChanged: DataStore emits a new snapshot
    // on every edit of the file, so without it each unrelated settings write
    // re-notifies every collector (e.g. re-registering location updates when
    // only the theme changed).
    val themeMode: Flow<ThemeMode> =
        safeData.map {
            when (it[THEME_MODE_KEY]) {
                "light" -> ThemeMode.LIGHT
                "dark" -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
        }.distinctUntilChanged()

    val dynamicColorEnabled: Flow<Boolean> =
        safeData.map { it[DYNAMIC_COLOR_KEY] ?: true }.distinctUntilChanged()

    val oledBlackEnabled: Flow<Boolean> =
        safeData.map { it[OLED_BLACK_KEY] ?: false }.distinctUntilChanged()

    val trueNorthEnabled: Flow<Boolean> =
        safeData.map { it[TRUE_NORTH_KEY] ?: false }.distinctUntilChanged()

    val locationPrompted: Flow<Boolean> =
        safeData.map { it[LOCATION_PROMPTED_KEY] ?: false }.distinctUntilChanged()

    val responsiveness: Flow<Responsiveness> =
        safeData.map {
            when (it[RESPONSIVENESS_KEY]) {
                "slowest" -> Responsiveness.SLOWEST
                "slow" -> Responsiveness.SLOW
                "fast" -> Responsiveness.FAST
                "fastest" -> Responsiveness.FASTEST
                else -> Responsiveness.NORMAL
            }
        }.distinctUntilChanged()

    suspend fun setResponsiveness(mode: Responsiveness) = writePrefs { prefs ->
        prefs[RESPONSIVENESS_KEY] = when (mode) {
            Responsiveness.SLOWEST -> "slowest"
            Responsiveness.SLOW -> "slow"
            Responsiveness.NORMAL -> "normal"
            Responsiveness.FAST -> "fast"
            Responsiveness.FASTEST -> "fastest"
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) = writePrefs { prefs ->
        prefs[THEME_MODE_KEY] = when (mode) {
            ThemeMode.SYSTEM -> "system"
            ThemeMode.LIGHT -> "light"
            ThemeMode.DARK -> "dark"
        }
    }

    suspend fun setDynamicColor(enabled: Boolean) = writePrefs { it[DYNAMIC_COLOR_KEY] = enabled }

    suspend fun setOledBlack(enabled: Boolean) = writePrefs { it[OLED_BLACK_KEY] = enabled }

    suspend fun setTrueNorth(enabled: Boolean) = writePrefs { it[TRUE_NORTH_KEY] = enabled }

    suspend fun setLocationPrompted(value: Boolean) = writePrefs { it[LOCATION_PROMPTED_KEY] = value }

    // DataStore.edit is atomic: on IOException the previous value is preserved, so
    // logging + swallowing is enough to keep a background write failure from crashing
    // the caller's coroutine scope.
    private suspend fun writePrefs(block: suspend (MutablePreferences) -> Unit) {
        try {
            context.dataStore.edit(block)
        } catch (e: IOException) {
            Log.w(TAG, "prefs write failed", e)
        }
    }

    private companion object {
        const val TAG = "UserPreferences"
    }
}
