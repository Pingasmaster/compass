package com.compass.app.update

import app.cash.turbine.test
import com.compass.app.R
import com.compass.app.data.preferences.FutureUpgradeChoices
import com.compass.app.testing.FakeUserPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Regression coverage for the cold-start silent-check + shared-state flow.
 *
 * Settings and the MainActivity startup dialog share one
 * [AppUpdateController] so both see the same state and cannot race on the
 * download coroutine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateControllerTest {

    private val dispatcher = StandardTestDispatcher()
    private val service = mockk<AppUpdateService>(relaxed = true)
    private val prefs = FakeUserPreferences()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        prefs.autoUpdateCheckState.value = true
        prefs.futureUpgradeChoiceState.value = FutureUpgradeChoices.UNSET
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `checkSilently moves state to Available when service returns an update`() = runTest(dispatcher) {
        coEvery { service.checkForUpdate(any(), any()) } returns AppUpdateService.AvailableUpdate(
            versionName = "9.9.9",
            apkDownloadUrl = "https://releases/9.9.9/app-release.apk",
            releaseNotes = "notes",
        )
        val controller = AppUpdateController(service, prefs, dispatcher).also { it.scope = this }

        controller.checkSilently()
        advanceUntilIdle()

        val state = controller.state.value
        assertThat(state).isInstanceOf(UpdateUiState.Available::class.java)
        assertThat((state as UpdateUiState.Available).versionName).isEqualTo("9.9.9")
    }

    @Test fun `checkSilently keeps state Idle when no update is available`() = runTest(dispatcher) {
        coEvery { service.checkForUpdate(any(), any()) } returns null
        val controller = AppUpdateController(service, prefs, dispatcher).also { it.scope = this }

        controller.checkSilently()
        advanceUntilIdle()

        assertThat(controller.state.value).isEqualTo(UpdateUiState.Idle)
    }

    @Test fun `checkSilently swallows failures - no message, no state change`() = runTest(dispatcher) {
        coEvery { service.checkForUpdate(any(), any()) } throws IOException("network dead")
        val controller = AppUpdateController(service, prefs, dispatcher).also { it.scope = this }
        val messagesSeen = mutableListOf<Int>()
        backgroundScope.launch {
            controller.messages.collect { messagesSeen += it }
        }
        advanceUntilIdle()

        controller.checkSilently()
        advanceUntilIdle()

        assertThat(controller.state.value).isEqualTo(UpdateUiState.Idle)
        assertThat(messagesSeen).isEmpty()
    }

    @Test fun `checkSilently is idempotent per process - second call is a no-op`() = runTest(dispatcher) {
        coEvery { service.checkForUpdate(any(), any()) } returns AppUpdateService.AvailableUpdate(
            versionName = "9.9.9",
            apkDownloadUrl = "https://x/app-release.apk",
            releaseNotes = "notes",
        )
        val controller = AppUpdateController(service, prefs, dispatcher).also { it.scope = this }

        controller.checkSilently()
        controller.checkSilently()
        controller.checkSilently()
        advanceUntilIdle()

        coVerify(exactly = 1) { service.checkForUpdate(any(), any()) }
    }

    @Test fun `checkSilently is a no-op when auto-update checks are disabled`() = runTest(dispatcher) {
        prefs.autoUpdateCheckState.value = false
        coEvery { service.checkForUpdate(any(), any()) } returns AppUpdateService.AvailableUpdate(
            versionName = "9.9.9",
            apkDownloadUrl = "https://x/app-release.apk",
            releaseNotes = "notes",
        )
        val controller = AppUpdateController(service, prefs, dispatcher).also { it.scope = this }

        controller.checkSilently()
        advanceUntilIdle()

        assertThat(controller.state.value).isEqualTo(UpdateUiState.Idle)
        coVerify(exactly = 0) { service.checkForUpdate(any(), any()) }
    }

    @Test fun `checkManually still checks even when auto-update is disabled`() = runTest(dispatcher) {
        prefs.autoUpdateCheckState.value = false
        coEvery { service.checkForUpdate(any(), any()) } returns AppUpdateService.AvailableUpdate(
            versionName = "9.9.9",
            apkDownloadUrl = "https://x/app-release.apk",
            releaseNotes = "notes",
        )
        val controller = AppUpdateController(service, prefs, dispatcher).also { it.scope = this }

        controller.checkManually()
        advanceUntilIdle()

        assertThat(controller.state.value).isInstanceOf(UpdateUiState.Available::class.java)
        coVerify(exactly = 1) { service.checkForUpdate(any(), any()) }
    }

    @Test fun `checkSilently does not clobber an in-flight Downloading state`() = runTest(dispatcher) {
        coEvery { service.checkForUpdate(any(), any()) } returns AppUpdateService.AvailableUpdate(
            versionName = "9.9.9",
            apkDownloadUrl = "https://x/app-release.apk",
            releaseNotes = "notes",
        )
        val hang = Job()
        every { service.downloadApk(any(), any()) } returns flow {
            emit(AppUpdateService.DownloadProgress(25, 100))
            hang.join()
        }
        val controller = AppUpdateController(service, prefs, dispatcher).also { it.scope = this }
        controller.checkManually()
        advanceUntilIdle()
        controller.confirmDownload()
        advanceUntilIdle()
        assertThat(controller.state.value).isInstanceOf(UpdateUiState.Downloading::class.java)

        controller.checkSilently()
        advanceUntilIdle()

        assertThat(controller.state.value).isInstanceOf(UpdateUiState.Downloading::class.java)
        hang.cancel()
        advanceUntilIdle()
    }

    @Test fun `checkManually emits no-update message on null`() = runTest(dispatcher) {
        coEvery { service.checkForUpdate(any(), any()) } returns null
        val controller = AppUpdateController(service, prefs, dispatcher).also { it.scope = this }

        controller.messages.test {
            controller.checkManually()
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(R.string.settings_update_no_update)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(controller.state.value).isEqualTo(UpdateUiState.Idle)
    }

    @Test fun `checkManually emits check-failed message on exception`() = runTest(dispatcher) {
        coEvery { service.checkForUpdate(any(), any()) } throws IOException("boom")
        val controller = AppUpdateController(service, prefs, dispatcher).also { it.scope = this }

        controller.messages.test {
            controller.checkManually()
            advanceUntilIdle()
            assertThat(awaitItem()).isEqualTo(R.string.settings_update_check_failed)
            cancelAndIgnoreRemainingEvents()
        }
        assertThat(controller.state.value).isEqualTo(UpdateUiState.Idle)
    }

    @Test fun `confirmDownload forwards the release sha256 to the service`() = runTest(dispatcher) {
        val url = "https://efrei.app:50002/hub/admin/compass/releases/assets/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        val sha = "ab".repeat(32)
        coEvery { service.checkForUpdate(any(), any()) } returns AppUpdateService.AvailableUpdate(
            versionName = "9.9.9",
            apkDownloadUrl = url,
            releaseNotes = "notes",
            apkSha256 = sha,
        )
        every { service.downloadApk(any(), any()) } returns emptyFlow()
        val controller = AppUpdateController(service, prefs, dispatcher).also { it.scope = this }
        controller.checkSilently()
        advanceUntilIdle()

        controller.confirmDownload()
        advanceUntilIdle()

        verify { service.downloadApk(url, sha) }
    }

    @Test fun `dismiss moves Available state back to Idle`() = runTest(dispatcher) {
        coEvery { service.checkForUpdate(any(), any()) } returns AppUpdateService.AvailableUpdate(
            versionName = "9.9.9",
            apkDownloadUrl = "https://x/app-release.apk",
            releaseNotes = "notes",
        )
        val controller = AppUpdateController(service, prefs, dispatcher).also { it.scope = this }
        controller.checkSilently()
        advanceUntilIdle()
        assertThat(controller.state.value).isInstanceOf(UpdateUiState.Available::class.java)

        controller.dismiss()

        assertThat(controller.state.value).isEqualTo(UpdateUiState.Idle)
    }

    @Test fun `confirmDownload parks pending install when unknown-sources is required`() = runTest(dispatcher) {
        val url = "https://efrei.app:50002/hub/admin/compass/releases/assets/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        coEvery { service.checkForUpdate(any(), any()) } returns AppUpdateService.AvailableUpdate(
            versionName = "9.9.9",
            apkDownloadUrl = url,
            releaseNotes = "notes",
        )
        every { service.downloadApk(any(), any()) } returns emptyFlow()
        every { service.launchInstaller() } throws InstallPermissionRequiredException()
        every { service.hasDownloadedApk() } returns true
        every { service.canRequestPackageInstalls() } returns false
        val controller = AppUpdateController(service, prefs, dispatcher).also { it.scope = this }
        controller.checkSilently()
        advanceUntilIdle()

        controller.confirmDownload()
        advanceUntilIdle()

        assertThat(controller.state.value).isEqualTo(UpdateUiState.Idle)
        controller.retryPendingInstallIfReady()
        advanceUntilIdle()
        verify(exactly = 1) { service.launchInstaller() }
    }

    @Test fun `retryPendingInstallIfReady launches cached APK after unknown-sources grant`() = runTest(dispatcher) {
        val url = "https://efrei.app:50002/hub/admin/compass/releases/assets/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        coEvery { service.checkForUpdate(any(), any()) } returns AppUpdateService.AvailableUpdate(
            versionName = "9.9.9",
            apkDownloadUrl = url,
            releaseNotes = "notes",
        )
        every { service.downloadApk(any(), any()) } returns emptyFlow()
        every { service.launchInstaller() } throws InstallPermissionRequiredException() andThen Unit
        every { service.hasDownloadedApk() } returns true
        every { service.canRequestPackageInstalls() } returnsMany listOf(false, true)
        val controller = AppUpdateController(service, prefs, dispatcher).also { it.scope = this }
        controller.checkSilently()
        advanceUntilIdle()
        controller.confirmDownload()
        advanceUntilIdle()

        every { service.canRequestPackageInstalls() } returns true
        controller.retryPendingInstallIfReady()
        advanceUntilIdle()

        verify(exactly = 2) { service.launchInstaller() }
        controller.retryPendingInstallIfReady()
        advanceUntilIdle()
        verify(exactly = 2) { service.launchInstaller() }
    }

    @Test fun `deferred future upgrade auto-downloads the future apk on silent check`() = runTest(dispatcher) {
        prefs.futureUpgradeChoiceState.value = FutureUpgradeChoices.DEFER
        coEvery { service.checkForUpdate(any(), any()) } returns AppUpdateService.AvailableUpdate(
            versionName = "9.9.9",
            apkDownloadUrl = "https://x/app-release-future.apk",
            releaseNotes = "notes",
        )
        every { service.downloadApk(any(), any()) } returns emptyFlow()
        val controller = AppUpdateController(service, prefs, dispatcher).also { it.scope = this }

        controller.checkSilently()
        advanceUntilIdle()

        coVerify { service.checkForUpdate("future", false) }
        verify { service.downloadApk("https://x/app-release-future.apk", null) }
    }

    @Test fun `maybeOfferFutureUpgrade shows the prompt once when eligible and unanswered`() = runTest(dispatcher) {
        prefs.futureUpgradeChoiceState.value = FutureUpgradeChoices.UNSET
        val controller = AppUpdateController(service, prefs, dispatcher).also {
            it.scope = this
            it.futureUpgradeEligibleOverride = true
        }

        controller.maybeOfferFutureUpgrade()
        advanceUntilIdle()

        assertThat(controller.showFutureUpgradePrompt.value).isTrue()
    }

    @Test fun `maybeOfferFutureUpgrade is a no-op when the user already answered`() = runTest(dispatcher) {
        prefs.futureUpgradeChoiceState.value = FutureUpgradeChoices.NO
        val controller = AppUpdateController(service, prefs, dispatcher).also {
            it.scope = this
            it.futureUpgradeEligibleOverride = true
        }

        controller.maybeOfferFutureUpgrade()
        advanceUntilIdle()

        assertThat(controller.showFutureUpgradePrompt.value).isFalse()
    }

    @Test fun `upgradeToFutureNow requests the future apk including same version`() = runTest(dispatcher) {
        coEvery { service.checkForUpdate(any(), any()) } returns AppUpdateService.AvailableUpdate(
            versionName = "1.0.45",
            apkDownloadUrl = "https://x/app-release-future.apk",
            releaseNotes = "notes",
        )
        every { service.downloadApk(any(), any()) } returns emptyFlow()
        val controller = AppUpdateController(service, prefs, dispatcher).also { it.scope = this }

        controller.upgradeToFutureNow()
        advanceUntilIdle()

        coVerify { service.checkForUpdate("future", true) }
        assertThat(prefs.futureUpgradeChoiceWrites).contains(FutureUpgradeChoices.ACCEPTED)
        verify { service.downloadApk("https://x/app-release-future.apk", null) }
    }

    @Test fun `declineFutureUpgrade persists no and hides the prompt`() = runTest(dispatcher) {
        val controller = AppUpdateController(service, prefs, dispatcher).also {
            it.scope = this
            it.futureUpgradeEligibleOverride = true
        }
        prefs.futureUpgradeChoiceState.value = FutureUpgradeChoices.UNSET
        controller.maybeOfferFutureUpgrade()
        advanceUntilIdle()
        assertThat(controller.showFutureUpgradePrompt.value).isTrue()

        controller.declineFutureUpgrade()
        advanceUntilIdle()

        assertThat(controller.showFutureUpgradePrompt.value).isFalse()
        assertThat(prefs.futureUpgradeChoiceWrites).contains(FutureUpgradeChoices.NO)
    }
}
