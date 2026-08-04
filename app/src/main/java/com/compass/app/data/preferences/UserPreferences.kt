package com.compass.app.data.preferences

import kotlinx.coroutines.flow.Flow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class Responsiveness { SLOWEST, SLOW, NORMAL, FAST, FASTEST }

/**
 * User settings store. Implementations must uphold two load-bearing contracts:
 *
 * 1. Write-observability: every successful write is observable through the
 *    corresponding flow. The ViewModel's reconciliation self-heal depends on
 *    [setTrueNorth] `(false)` re-emitting through [trueNorthEnabled].
 * 2. Per-key flows are distinct-until-changed and always emit a current value
 *    to new collectors.
 */
interface UserPreferences {
    val themeMode: Flow<ThemeMode>
    val dynamicColorEnabled: Flow<Boolean>
    val oledBlackEnabled: Flow<Boolean>
    val trueNorthEnabled: Flow<Boolean>
    val locationPrompted: Flow<Boolean>
    val responsiveness: Flow<Responsiveness>

    suspend fun setThemeMode(mode: ThemeMode)

    suspend fun setDynamicColor(enabled: Boolean)

    suspend fun setOledBlack(enabled: Boolean)

    suspend fun setTrueNorth(enabled: Boolean)

    suspend fun setResponsiveness(mode: Responsiveness)

    suspend fun setLocationPrompted(value: Boolean)
}
