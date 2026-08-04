package com.compass.app.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Swaps [Dispatchers.Main] for a [StandardTestDispatcher] around each test. When
 * Main is a TestDispatcher, plain `runTest` reuses its scheduler, so `viewModelScope`
 * and the test body share virtual time; `advanceTimeBy` drives `WhileSubscribed(5_000)`.
 * StandardTestDispatcher is chosen over Unconfined for deterministic queue ordering;
 * tests advance explicitly via advanceUntilIdle/runCurrent.
 */
class MainDispatcherRule(val dispatcher: TestDispatcher = StandardTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

    override fun finished(description: Description) = Dispatchers.resetMain()
}
