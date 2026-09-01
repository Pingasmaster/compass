package com.compass.app.update

import android.os.Build
import android.util.Log
import com.compass.app.BuildConfig
import com.compass.app.R
import com.compass.app.data.preferences.FutureUpgradeChoices
import com.compass.app.data.preferences.UserPreferences
import com.compass.app.util.isAtLeastCinnamonBun
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** UI-facing shape of the update flow. Shared between Settings and the startup dialog. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState

    /** Server said a newer build exists. Awaiting user confirmation. */
    data class Available(
        val versionName: String,
        val apkUrl: String,
        /** Release body (Markdown), shown in the update dialog. Empty when none. */
        val releaseNotes: String,
        /** Lowercase-hex SHA-256 of the APK from the release asset digest, or null when none. */
        val apkSha256: String? = null,
    ) : UpdateUiState

    /** APK is streaming. [progress] is 0f..1f, or `null` when no Content-Length was sent. */
    data class Downloading(val versionName: String, val progress: Float?) : UpdateUiState
}

/**
 * Process-wide owner of the self-update state so the Settings screen and the
 * cold-start dialog share a single [UpdateUiState] flow. Without this the two
 * surfaces would each run their own check, have divergent progress bars, and
 * fight over the download coroutine.
 *
 * Cold start: [com.compass.app.CompassApplication.onCreate] calls
 * [checkSilently] once. If a newer APK exists, state moves to
 * [UpdateUiState.Available] and the MainActivity dialog host surfaces a
 * prompt. Manual re-checks from Settings go through [checkManually]
 * (emits a string-resource id on the [messages] flow that only Settings /
 * MainActivity listen to - we do not want startup toasts).
 */
class AppUpdateController(private val service: AppUpdateService, private val prefs: UserPreferences, ioDispatcher: CoroutineDispatcher) {
    /** Overridable in tests so a TestDispatcher can drive the internal scope. Set once, before first call. */
    internal var scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + ioDispatcher +
            CoroutineExceptionHandler { _, throwable ->
                Log.e(TAG, "Unhandled AppUpdateController coroutine error", throwable)
            },
    )

    /**
     * Test hook for the compat-to-future first-open prompt. Null means use
     * the production eligibility check (flavor + API + 64-bit ABI).
     */
    internal var futureUpgradeEligibleOverride: Boolean? = null

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _showFutureUpgradePrompt = MutableStateFlow(false)
    val showFutureUpgradePrompt: StateFlow<Boolean> = _showFutureUpgradePrompt.asStateFlow()

    /**
     * One-shot messages for the Settings row only (e.g. "no update" after a
     * manual check). Silent startup checks NEVER emit here; a "no update"
     * result from a silent check is just silent.
     */
    private val _messages = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 4)
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    @Volatile
    private var silentCheckStarted = false
    private var downloadJob: Job? = null

    /**
     * Set when [launchInstaller] deep-linked to unknown-sources settings
     * because REQUEST_INSTALL_PACKAGES was missing. Cleared after a successful
     * retry or when the cached APK disappears. [retryPendingInstallIfReady]
     * re-launches the installer without re-downloading.
     */
    @Volatile
    private var pendingInstallAfterPermission = false

    /**
     * Idempotent per process. Fires once from [com.compass.app.CompassApplication.onCreate].
     * All errors are swallowed - a startup update check must never surface
     * a toast or block the UI. Skips entirely when the user has turned off the
     * "Automatic update checks" toggle; the manual [checkManually] path is
     * never gated by that preference.
     */
    fun checkSilently() {
        if (silentCheckStarted) return
        silentCheckStarted = true
        scope.launch {
            ignoreErrors {
                val autoCheckEnabled = prefs.autoUpdateCheckEnabled.firstOrNull() ?: true
                if (!autoCheckEnabled) return@ignoreErrors
                val preferFuture = prefs.futureUpgradeChoice.firstOrNull() ==
                    FutureUpgradeChoices.DEFER
                val flavor = if (preferFuture) "future" else BuildConfig.FLAVOR
                val available = service.checkForUpdate(flavor, allowEqualVersion = false)
                    ?: return@ignoreErrors
                if (preferFuture) {
                    if (_state.value is UpdateUiState.Downloading) return@ignoreErrors
                    startDownload(available)
                    return@ignoreErrors
                }
                _state.update { current ->
                    // Respect an in-flight manual flow - the user is already
                    // looking at a dialog, we don't want to reset their state.
                    when (current) {
                        is UpdateUiState.Downloading, is UpdateUiState.Available -> current
                        else -> toAvailable(available)
                    }
                }
            }
        }
    }

    /**
     * User-triggered re-check from Settings. Emits a string resource on
     * [messages] for the "no update" / "check failed" cases so Settings can
     * snackbar it.
     */
    fun checkManually() {
        if (_state.value is UpdateUiState.Downloading) return
        scope.launch {
            _state.value = UpdateUiState.Checking
            try {
                val preferFuture = prefs.futureUpgradeChoice.firstOrNull() ==
                    FutureUpgradeChoices.DEFER
                val flavor = if (preferFuture) "future" else BuildConfig.FLAVOR
                val available = service.checkForUpdate(flavor, allowEqualVersion = false)
                if (available == null) {
                    _state.value = UpdateUiState.Idle
                    _messages.tryEmit(R.string.settings_update_no_update)
                } else {
                    _state.value = toAvailable(available)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = UpdateUiState.Idle
                _messages.tryEmit(R.string.settings_update_check_failed)
            }
        }
    }

    /** User confirmed the download; streams the APK + hands off to the installer. */
    fun confirmDownload() {
        val current = _state.value
        if (current !is UpdateUiState.Available) return
        startDownload(
            AppUpdateService.AvailableUpdate(
                versionName = current.versionName,
                apkDownloadUrl = current.apkUrl,
                releaseNotes = current.releaseNotes,
                apkSha256 = current.apkSha256,
            ),
        )
    }

    /**
     * Compat first-open: download the future fat APK of the latest release
     * (same tag is allowed; future versionCode is higher) and hand it to the
     * installer.
     */
    fun upgradeToFutureNow() {
        if (_state.value is UpdateUiState.Downloading) return
        _showFutureUpgradePrompt.value = false
        scope.launch {
            prefs.setFutureUpgradeChoice(FutureUpgradeChoices.ACCEPTED)
            _state.value = UpdateUiState.Checking
            try {
                val available = service.checkForUpdate(
                    flavor = "future",
                    allowEqualVersion = true,
                )
                if (available == null) {
                    _state.value = UpdateUiState.Idle
                    _messages.tryEmit(R.string.settings_update_no_update)
                } else {
                    startDownload(available)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = UpdateUiState.Idle
                _messages.tryEmit(R.string.settings_update_check_failed)
            }
        }
    }

    /** Stay on compat forever; do not show the first-open prompt again. */
    fun declineFutureUpgrade() {
        _showFutureUpgradePrompt.value = false
        scope.launch {
            prefs.setFutureUpgradeChoice(FutureUpgradeChoices.NO)
        }
    }

    /**
     * Stay on compat until the next version bump; that check then downloads
     * the future fat APK instead of compat.
     */
    fun deferFutureUpgrade() {
        _showFutureUpgradePrompt.value = false
        scope.launch {
            prefs.setFutureUpgradeChoice(FutureUpgradeChoices.DEFER)
        }
    }

    /**
     * First-open prompt for compat on Android 17 64-bit devices. No-op when
     * already answered, ineligible, or running under a test harness.
     */
    fun maybeOfferFutureUpgrade() {
        scope.launch {
            ignoreErrors {
                if (!isFutureUpgradeEligible()) return@ignoreErrors
                val choice = prefs.futureUpgradeChoice.firstOrNull()
                    ?: FutureUpgradeChoices.UNSET
                if (choice != FutureUpgradeChoices.UNSET) return@ignoreErrors
                _showFutureUpgradePrompt.value = true
            }
        }
    }

    /**
     * Called from Activity.onResume after the user may have granted
     * REQUEST_INSTALL_PACKAGES. If we previously parked a downloaded APK
     * waiting on that grant, launch the installer without re-downloading.
     */
    fun retryPendingInstallIfReady() {
        if (!pendingInstallAfterPermission) return
        if (!service.hasDownloadedApk()) {
            pendingInstallAfterPermission = false
            return
        }
        if (!service.canRequestPackageInstalls()) return
        scope.launch {
            try {
                service.launchInstaller()
                pendingInstallAfterPermission = false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (!service.hasDownloadedApk()) {
                    pendingInstallAfterPermission = false
                }
                _messages.tryEmit(R.string.settings_update_install_failed)
            }
        }
    }

    /** Moves state back to Idle. Does not cancel an in-flight download. */
    fun dismiss() {
        if (_state.value is UpdateUiState.Downloading) return
        _state.value = UpdateUiState.Idle
    }

    /**
     * Called by [com.compass.app.CompassApplication.onTrimMemory] when the OS
     * signals the UI is no longer visible. The Available snapshot (release
     * notes, apk URL) is cheap to rebuild on the next foreground; an in-flight
     * download is preserved. Does not clear [pendingInstallAfterPermission] -
     * the cached APK install retry must survive trim.
     */
    fun releaseOnTrim() {
        if (_state.value is UpdateUiState.Available) {
            _state.value = UpdateUiState.Idle
        }
        _showFutureUpgradePrompt.value = false
    }

    private fun startDownload(update: AppUpdateService.AvailableUpdate) {
        downloadJob?.cancel()
        downloadJob = scope.launch {
            _state.value = UpdateUiState.Downloading(update.versionName, 0f)
            try {
                service.downloadApk(update.apkDownloadUrl, update.apkSha256).collect { p ->
                    val frac = if (p.totalBytes > 0L) p.fraction else null
                    _state.value = UpdateUiState.Downloading(update.versionName, frac)
                }
                try {
                    service.launchInstaller()
                    pendingInstallAfterPermission = false
                } catch (_: InstallPermissionRequiredException) {
                    // APK is on disk; resume will retry once unknown-sources is granted.
                    pendingInstallAfterPermission = true
                } catch (_: IOException) {
                    _messages.tryEmit(R.string.settings_update_install_failed)
                }
                _state.value = UpdateUiState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = UpdateUiState.Idle
                _messages.tryEmit(R.string.settings_update_download_failed)
            }
        }
    }

    private fun isFutureUpgradeEligible(): Boolean {
        futureUpgradeEligibleOverride?.let { return it }
        val flavor = BuildConfig.FLAVOR.lowercase()
        val isCompat = flavor == "compat" || flavor.contains("compat")
        return !isAutomatedTestProcess() &&
            isCompat &&
            isAtLeastCinnamonBun() &&
            Build.SUPPORTED_ABIS.any { it in FUTURE_ABIS }
    }

    private companion object {
        const val TAG = "AppUpdateController"
        val FUTURE_ABIS = setOf(
            AppUpdateService.ABI_ARM64,
            AppUpdateService.ABI_X86_64,
            AppUpdateService.ABI_RISCV64,
        )

        fun toAvailable(update: AppUpdateService.AvailableUpdate) = UpdateUiState.Available(
            versionName = update.versionName,
            apkUrl = update.apkDownloadUrl,
            releaseNotes = update.releaseNotes,
            apkSha256 = update.apkSha256,
        )

        fun isAutomatedTestProcess(): Boolean = Build.FINGERPRINT.contains("robolectric", ignoreCase = true)

        suspend fun ignoreErrors(block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Silent path: never toast a startup check failure.
            }
        }
    }
}
