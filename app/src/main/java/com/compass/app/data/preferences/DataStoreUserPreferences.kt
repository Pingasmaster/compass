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

class DataStoreUserPreferences(private val context: Context) : UserPreferences {
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val dynamicColorKey = booleanPreferencesKey("dynamic_color")
    private val oledBlackKey = booleanPreferencesKey("oled_black")
    private val trueNorthKey = booleanPreferencesKey("true_north")
    private val responsivenessKey = stringPreferencesKey("responsiveness")
    private val locationPromptedKey = booleanPreferencesKey("location_prompted")
    private val autoUpdateCheckKey = booleanPreferencesKey("auto_update_check")
    private val futureUpgradeChoiceKey = stringPreferencesKey("future_upgrade_choice")

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
    // re-notifies every collector (e.g. re-requesting a declination fix when
    // only the theme changed).
    override val themeMode: Flow<ThemeMode> =
        safeData.map {
            when (it[themeModeKey]) {
                "light" -> ThemeMode.LIGHT
                "dark" -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
        }.distinctUntilChanged()

    override val dynamicColorEnabled: Flow<Boolean> =
        safeData.map { it[dynamicColorKey] ?: true }.distinctUntilChanged()

    override val oledBlackEnabled: Flow<Boolean> =
        safeData.map { it[oledBlackKey] ?: false }.distinctUntilChanged()

    override val trueNorthEnabled: Flow<Boolean> =
        safeData.map { it[trueNorthKey] ?: false }.distinctUntilChanged()

    override val locationPrompted: Flow<Boolean> =
        safeData.map { it[locationPromptedKey] ?: false }.distinctUntilChanged()

    override val autoUpdateCheckEnabled: Flow<Boolean> =
        safeData.map { it[autoUpdateCheckKey] ?: true }.distinctUntilChanged()

    override val futureUpgradeChoice: Flow<String> =
        safeData.map { it[futureUpgradeChoiceKey] ?: FutureUpgradeChoices.UNSET }.distinctUntilChanged()

    override val responsiveness: Flow<Responsiveness> =
        safeData.map {
            when (it[responsivenessKey]) {
                "slowest" -> Responsiveness.SLOWEST
                "slow" -> Responsiveness.SLOW
                "fast" -> Responsiveness.FAST
                "fastest" -> Responsiveness.FASTEST
                else -> Responsiveness.NORMAL
            }
        }.distinctUntilChanged()

    override suspend fun setResponsiveness(mode: Responsiveness) = writePrefs { prefs ->
        prefs[responsivenessKey] = when (mode) {
            Responsiveness.SLOWEST -> "slowest"
            Responsiveness.SLOW -> "slow"
            Responsiveness.NORMAL -> "normal"
            Responsiveness.FAST -> "fast"
            Responsiveness.FASTEST -> "fastest"
        }
    }

    override suspend fun setThemeMode(mode: ThemeMode) = writePrefs { prefs ->
        prefs[themeModeKey] = when (mode) {
            ThemeMode.SYSTEM -> "system"
            ThemeMode.LIGHT -> "light"
            ThemeMode.DARK -> "dark"
        }
    }

    override suspend fun setDynamicColor(enabled: Boolean) = writePrefs { it[dynamicColorKey] = enabled }

    override suspend fun setOledBlack(enabled: Boolean) = writePrefs { it[oledBlackKey] = enabled }

    override suspend fun setTrueNorth(enabled: Boolean) = writePrefs { it[trueNorthKey] = enabled }

    override suspend fun setLocationPrompted(value: Boolean) = writePrefs { it[locationPromptedKey] = value }

    override suspend fun setAutoUpdateCheckEnabled(enabled: Boolean) = writePrefs { it[autoUpdateCheckKey] = enabled }

    override suspend fun setFutureUpgradeChoice(choice: String) = writePrefs { it[futureUpgradeChoiceKey] = choice }

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
