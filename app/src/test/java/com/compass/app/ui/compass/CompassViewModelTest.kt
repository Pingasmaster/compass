package com.compass.app.ui.compass

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.compass.app.data.preferences.Responsiveness
import com.compass.app.data.preferences.ThemeMode
import com.compass.app.domain.model.CompassReading
import com.compass.app.domain.model.GeoFix
import com.compass.app.testing.FakeHeadingSource
import com.compass.app.testing.FakeLocationProvider
import com.compass.app.testing.FakePermissionChecker
import com.compass.app.testing.FakeUserPreferences
import com.compass.app.testing.MainDispatcherRule
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private fun createViewModel(
    prefs: FakeUserPreferences,
    heading: FakeHeadingSource,
    location: FakeLocationProvider,
    permissions: FakePermissionChecker,
    savedState: SavedStateHandle = SavedStateHandle(),
): CompassViewModel = CompassViewModel(
    prefs = prefs,
    headingSource = heading,
    locationProvider = location,
    permissionChecker = permissions,
    savedState = savedState,
)

class CompassViewModelReconciliationTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `enabling true north with permission granted reaches the heading source`() = runTest {
        val prefs = FakeUserPreferences(initialTrueNorth = true)
        val heading = FakeHeadingSource()
        val location = FakeLocationProvider()
        val permissions = FakePermissionChecker(granted = true)
        createViewModel(prefs, heading, location, permissions)
        advanceUntilIdle()

        assertEquals(listOf(true), heading.trueNorthCalls)
        assertTrue(prefs.trueNorthWrites.isEmpty())
        assertEquals(0, location.requestCount)
    }

    @Test
    fun `true north pref without permission is pulled back to false`() = runTest {
        val prefs = FakeUserPreferences(initialTrueNorth = true)
        val heading = FakeHeadingSource()
        val location = FakeLocationProvider()
        val permissions = FakePermissionChecker(granted = false)
        createViewModel(prefs, heading, location, permissions)
        advanceUntilIdle()

        // The guard early-returned before setTrueNorthEnabled(true); the recorded false
        // came from the re-emitted (false, active) pair, proving the self-heal feedback
        // loop completed.
        assertEquals(listOf(false), prefs.trueNorthWrites)
        assertFalse(prefs.trueNorthState.value)
        assertEquals(listOf(false), heading.trueNorthCalls)
        assertEquals(0, location.requestCount)
    }

    @Test
    fun `location updates start only when true north on and readings collected`() = runTest {
        val prefs = FakeUserPreferences(initialTrueNorth = true)
        val heading = FakeHeadingSource()
        val location = FakeLocationProvider()
        val permissions = FakePermissionChecker(granted = true)
        val vm = createViewModel(prefs, heading, location, permissions)
        advanceUntilIdle()
        assertEquals(0, location.requestCount)

        backgroundScope.launch { vm.readings.collect { } }
        runCurrent()
        advanceUntilIdle()

        assertEquals(1, location.requestCount)
        assertTrue(location.active)
        assertEquals(listOf(true, true), heading.trueNorthCalls)
    }

    @Test
    fun `stopping readings collection stops location updates after the grace period`() = runTest {
        val prefs = FakeUserPreferences(initialTrueNorth = true)
        val heading = FakeHeadingSource()
        val location = FakeLocationProvider()
        val permissions = FakePermissionChecker(granted = true)
        val vm = createViewModel(prefs, heading, location, permissions)
        advanceUntilIdle()
        val collector = backgroundScope.launch { vm.readings.collect { } }
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, location.requestCount)
        val stopsBefore = location.stopCount

        collector.cancel()
        runCurrent()
        // WhileSubscribed's 5 s grace still holds the upstream open.
        assertEquals(stopsBefore, location.stopCount)
        assertTrue(location.active)

        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(stopsBefore + 1, location.stopCount)
        assertFalse(location.active)
    }

    @Test
    fun `resubscribing within the grace period does not restart location updates`() = runTest {
        val prefs = FakeUserPreferences(initialTrueNorth = true)
        val heading = FakeHeadingSource()
        val location = FakeLocationProvider()
        val permissions = FakePermissionChecker(granted = true)
        val vm = createViewModel(prefs, heading, location, permissions)
        advanceUntilIdle()
        val collector = backgroundScope.launch { vm.readings.collect { } }
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, location.requestCount)

        collector.cancel()
        advanceTimeBy(1_000)
        backgroundScope.launch { vm.readings.collect { } }
        runCurrent()
        advanceTimeBy(10_000)

        // The upstream never stopped, readingsActive never flipped, and
        // distinctUntilChanged suppressed a re-fire.
        assertEquals(1, location.requestCount)
        assertTrue(location.active)
    }

    @Test
    fun `disabling true north while active stops location updates`() = runTest {
        val prefs = FakeUserPreferences(initialTrueNorth = true)
        val heading = FakeHeadingSource()
        val location = FakeLocationProvider()
        val permissions = FakePermissionChecker(granted = true)
        val vm = createViewModel(prefs, heading, location, permissions)
        advanceUntilIdle()
        backgroundScope.launch { vm.readings.collect { } }
        runCurrent()
        advanceUntilIdle()
        assertTrue(location.active)
        val stopsBefore = location.stopCount

        vm.settingsActions.onTrueNorthChange(false)
        advanceUntilIdle()

        assertFalse(prefs.trueNorthWrites.last())
        assertFalse(heading.trueNorthCalls.last())
        assertFalse(location.active)
        assertEquals(stopsBefore + 1, location.stopCount)
    }

    @Test
    fun `re-enabling true north after permission revocation self-heals again`() = runTest {
        val prefs = FakeUserPreferences(initialTrueNorth = false)
        val heading = FakeHeadingSource()
        val location = FakeLocationProvider()
        val permissions = FakePermissionChecker(granted = false)
        createViewModel(prefs, heading, location, permissions)
        advanceUntilIdle()

        // A stale toggle flips the pref back on while permission is still gone.
        prefs.setTrueNorth(true)
        advanceUntilIdle()

        assertFalse(prefs.trueNorthState.value)
        assertEquals(0, location.requestCount)
        assertFalse(heading.trueNorthCalls.contains(true))
    }

    @Test
    fun `permission revoked mid-session heals on the next reconciliation trigger`() = runTest {
        val prefs = FakeUserPreferences(initialTrueNorth = true)
        val heading = FakeHeadingSource()
        val location = FakeLocationProvider()
        val permissions = FakePermissionChecker(granted = true)
        val vm = createViewModel(prefs, heading, location, permissions)
        advanceUntilIdle()
        val collector = backgroundScope.launch { vm.readings.collect { } }
        runCurrent()
        advanceUntilIdle()
        assertEquals(1, location.requestCount)

        permissions.granted = false
        advanceUntilIdle()
        // Documents real behavior: revocation alone does not re-trigger the combine.
        assertTrue(prefs.trueNorthWrites.isEmpty())
        assertTrue(location.active)

        collector.cancel()
        advanceTimeBy(5_001)
        runCurrent()
        backgroundScope.launch { vm.readings.collect { } }
        runCurrent()
        advanceUntilIdle()

        assertTrue(prefs.trueNorthWrites.contains(false))
        assertEquals(1, location.requestCount)
    }

    @Test
    fun `location fixes are forwarded to the heading source`() = runTest {
        val prefs = FakeUserPreferences(initialTrueNorth = true)
        val heading = FakeHeadingSource()
        val location = FakeLocationProvider()
        val permissions = FakePermissionChecker(granted = true)
        val seeded = GeoFix(48.85, 2.35, 35.0, 123L)
        location.lastKnownFix = seeded
        val vm = createViewModel(prefs, heading, location, permissions)
        backgroundScope.launch { vm.readings.collect { } }
        runCurrent()
        advanceUntilIdle()

        // requestUpdates(headingSource::updateLocation) wiring + last-known seeding.
        assertEquals(seeded, heading.fixes.first())

        val periodic = GeoFix(1.0, 2.0, 3.0, 456L)
        checkNotNull(location.lastOnFix).invoke(periodic)
        assertEquals(2, heading.fixes.size)
        assertEquals(periodic, heading.fixes.last())
    }
}

class CompassViewModelPermissionFlowTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `permission granted result persists prompted and enables true north`() = runTest {
        val prefs = FakeUserPreferences()
        val heading = FakeHeadingSource()
        val location = FakeLocationProvider()
        val permissions = FakePermissionChecker(granted = true)
        val vm = createViewModel(prefs, heading, location, permissions)
        backgroundScope.launch { vm.readings.collect { } }
        runCurrent()
        advanceUntilIdle()

        vm.onLocationPermissionResult(true)
        advanceUntilIdle()

        assertEquals(listOf(true), prefs.locationPromptedWrites)
        assertEquals(listOf(true), prefs.trueNorthWrites)
        assertEquals(1, location.requestCount)
    }

    @Test
    fun `permission denied result persists prompted and keeps true north off`() = runTest {
        val prefs = FakeUserPreferences()
        val heading = FakeHeadingSource()
        val location = FakeLocationProvider()
        val permissions = FakePermissionChecker(granted = false)
        val vm = createViewModel(prefs, heading, location, permissions)
        advanceUntilIdle()

        vm.onLocationPermissionResult(false)
        advanceUntilIdle()

        assertEquals(listOf(true), prefs.locationPromptedWrites)
        assertEquals(listOf(false), prefs.trueNorthWrites)
        assertEquals(0, location.requestCount)
        assertFalse(heading.trueNorthCalls.contains(true))
    }

    @Test
    fun `markLocationPrompted persists the prompted flag only`() = runTest {
        val prefs = FakeUserPreferences()
        val heading = FakeHeadingSource()
        val location = FakeLocationProvider()
        val permissions = FakePermissionChecker(granted = true)
        val vm = createViewModel(prefs, heading, location, permissions)
        advanceUntilIdle()

        vm.markLocationPrompted()
        advanceUntilIdle()

        assertEquals(listOf(true), prefs.locationPromptedWrites)
        assertTrue(prefs.trueNorthWrites.isEmpty())
    }

    @Test
    fun `settings actions persist preference writes`() = runTest {
        val prefs = FakeUserPreferences()
        val heading = FakeHeadingSource()
        val location = FakeLocationProvider()
        val permissions = FakePermissionChecker(granted = true)
        val vm = createViewModel(prefs, heading, location, permissions)
        advanceUntilIdle()

        vm.settingsActions.onThemeChange(ThemeMode.DARK)
        vm.settingsActions.onResponsivenessChange(Responsiveness.FAST)
        advanceUntilIdle()

        assertEquals(ThemeMode.DARK, prefs.themeModeState.value)
        assertEquals(Responsiveness.FAST, prefs.responsivenessState.value)
    }
}

class CompassViewModelReadingsAndTargetTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `readings initial value reflects a missing sensor`() = runTest {
        val heading = FakeHeadingSource(hasSensor = false)
        val vm = createViewModel(
            prefs = FakeUserPreferences(),
            heading = heading,
            location = FakeLocationProvider(),
            permissions = FakePermissionChecker(granted = true),
        )

        // Asserted BEFORE any collection: the eager synchronous hasSensor read
        // feeds the stateIn initial value.
        assertEquals(CompassReading(hasSensor = false), vm.readings.value)
    }

    @Test
    fun `readings mirror heading source emissions while collected`() = runTest {
        val heading = FakeHeadingSource()
        val vm = createViewModel(
            prefs = FakeUserPreferences(),
            heading = heading,
            location = FakeLocationProvider(),
            permissions = FakePermissionChecker(granted = true),
        )
        backgroundScope.launch { vm.readings.collect { } }
        runCurrent()
        advanceUntilIdle()

        heading.emitted.emit(CompassReading(azimuth = 123.4f, hasSensor = true))
        runCurrent()

        assertEquals(123.4f, vm.readings.value.azimuth, 0f)
    }

    @Test
    fun `setTargetAngle normalizes into 0 to 360 and persists to saved state`() = runTest {
        val savedState = SavedStateHandle()
        val vm = createViewModel(
            prefs = FakeUserPreferences(),
            heading = FakeHeadingSource(),
            location = FakeLocationProvider(),
            permissions = FakePermissionChecker(granted = true),
            savedState = savedState,
        )

        vm.setTargetAngle(-90f)
        assertEquals(270f, checkNotNull(vm.targetAngle.value), 0f)
        assertEquals(270f, checkNotNull(savedState.get<Float?>("target_angle")), 0f)

        vm.setTargetAngle(null)
        assertNull(vm.targetAngle.value)
        assertNull(savedState.get<Float?>("target_angle"))
    }

    @Test
    fun `target angle is restored from saved state`() = runTest {
        val vm = createViewModel(
            prefs = FakeUserPreferences(),
            heading = FakeHeadingSource(),
            location = FakeLocationProvider(),
            permissions = FakePermissionChecker(granted = true),
            savedState = SavedStateHandle(mapOf("target_angle" to 45f)),
        )

        assertEquals(45f, checkNotNull(vm.targetAngle.value), 0f)
    }

    @Test
    fun `clearing the view model stops location updates`() = runTest {
        val location = FakeLocationProvider()
        val vm = createViewModel(
            prefs = FakeUserPreferences(),
            heading = FakeHeadingSource(),
            location = location,
            permissions = FakePermissionChecker(granted = true),
        )
        advanceUntilIdle()
        val stopsBefore = location.stopCount

        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = checkNotNull(modelClass.cast(vm))
        }
        ViewModelProvider(store, factory)[CompassViewModel::class.java]
        store.clear()

        assertEquals(stopsBefore + 1, location.stopCount)
    }
}
