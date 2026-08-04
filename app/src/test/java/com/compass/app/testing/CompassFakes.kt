package com.compass.app.testing

import com.compass.app.data.preferences.Responsiveness
import com.compass.app.data.preferences.ThemeMode
import com.compass.app.data.preferences.UserPreferences
import com.compass.app.domain.location.LocationProvider
import com.compass.app.domain.location.PermissionChecker
import com.compass.app.domain.model.CompassReading
import com.compass.app.domain.model.GeoFix
import com.compass.app.domain.sensor.HeadingSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [UserPreferences]. Each pref is backed by a MutableStateFlow whose
 * value-dedup mirrors the production per-key distinctUntilChanged contract:
 * assigning a NEW value re-emits, which makes the setTrueNorth(false) self-heal
 * feedback loop actually loop in tests. Every setter also records into a public
 * write list so tests can assert on write traffic independently of state.
 */
class FakeUserPreferences(initialTrueNorth: Boolean = false, initialLocationPrompted: Boolean = false) : UserPreferences {
    val themeModeState = MutableStateFlow(ThemeMode.SYSTEM)
    val dynamicColorState = MutableStateFlow(true)
    val oledBlackState = MutableStateFlow(false)
    val trueNorthState = MutableStateFlow(initialTrueNorth)
    val locationPromptedState = MutableStateFlow(initialLocationPrompted)
    val responsivenessState = MutableStateFlow(Responsiveness.NORMAL)

    val themeWrites = mutableListOf<ThemeMode>()
    val dynamicColorWrites = mutableListOf<Boolean>()
    val oledBlackWrites = mutableListOf<Boolean>()
    val trueNorthWrites = mutableListOf<Boolean>()
    val responsivenessWrites = mutableListOf<Responsiveness>()
    val locationPromptedWrites = mutableListOf<Boolean>()

    override val themeMode: Flow<ThemeMode> = themeModeState
    override val dynamicColorEnabled: Flow<Boolean> = dynamicColorState
    override val oledBlackEnabled: Flow<Boolean> = oledBlackState
    override val trueNorthEnabled: Flow<Boolean> = trueNorthState
    override val locationPrompted: Flow<Boolean> = locationPromptedState
    override val responsiveness: Flow<Responsiveness> = responsivenessState

    override suspend fun setThemeMode(mode: ThemeMode) {
        themeWrites += mode
        themeModeState.value = mode
    }

    override suspend fun setDynamicColor(enabled: Boolean) {
        dynamicColorWrites += enabled
        dynamicColorState.value = enabled
    }

    override suspend fun setOledBlack(enabled: Boolean) {
        oledBlackWrites += enabled
        oledBlackState.value = enabled
    }

    override suspend fun setTrueNorth(enabled: Boolean) {
        trueNorthWrites += enabled
        trueNorthState.value = enabled
    }

    override suspend fun setResponsiveness(mode: Responsiveness) {
        responsivenessWrites += mode
        responsivenessState.value = mode
    }

    override suspend fun setLocationPrompted(value: Boolean) {
        locationPromptedWrites += value
        locationPromptedState.value = value
    }
}

/** Recording [HeadingSource]; [hasSensor] is a constructor val so it stays synchronously answerable. */
class FakeHeadingSource(override val hasSensor: Boolean = true) : HeadingSource {
    val emitted = MutableSharedFlow<CompassReading>()
    override val readings: Flow<CompassReading> = emitted

    val trueNorthCalls = mutableListOf<Boolean>()
    val fixes = mutableListOf<GeoFix>()

    override fun setTrueNorthEnabled(enabled: Boolean) {
        trueNorthCalls += enabled
    }

    override fun updateLocation(fix: GeoFix) {
        fixes += fix
    }
}

/**
 * Recording [LocationProvider]. When [lastKnownFix] is set, [requestUpdates]
 * invokes the callback synchronously, simulating the production
 * getLastKnownLocation seeding that happens before periodic registration.
 */
class FakeLocationProvider : LocationProvider {
    var requestCount = 0
    var stopCount = 0
    var active = false
    var lastOnFix: ((GeoFix) -> Unit)? = null
    var lastKnownFix: GeoFix? = null

    override fun requestUpdates(onFix: (GeoFix) -> Unit) {
        requestCount += 1
        active = true
        lastOnFix = onFix
        lastKnownFix?.let(onFix)
    }

    override fun stopUpdates() {
        stopCount += 1
        active = false
        lastOnFix = null
    }
}

class FakePermissionChecker(var granted: Boolean) : PermissionChecker {
    override fun hasCoarseLocationPermission(): Boolean = granted
}
