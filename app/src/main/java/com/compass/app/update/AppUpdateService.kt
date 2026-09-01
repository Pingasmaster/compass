package com.compass.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.compass.app.BuildConfig
import com.compass.app.util.isAtLeastP
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Lightweight self-update for pre-alpha. Polls the efreihub releases API
 * on efrei.app:50002, compares the latest tag to [BuildConfig.VERSION_NAME],
 * and (when newer) downloads the fat APK for this flavor (or future, when
 * the user deferred a compat-to-future switch) + hands it to the system
 * installer via FileProvider.
 *
 * Pre-alpha policy: this is the ONLY self-update mechanism (no Play Store, no
 * WorkManager job). It runs on a silent cold-start check fired from
 * [com.compass.app.CompassApplication.onCreate] via
 * [AppUpdateController.checkSilently], and on the manual "Search for updates"
 * button in Settings. The cold-start check can be turned off with the
 * "Automatic update checks" toggle in Settings; the manual button never.
 */
open class AppUpdateService(
    // No callTimeout: the APK download can outlive a 30s whole-call cap on
    // any real-world connection.
    private val client: OkHttpClient,
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * Overridable in tests so MockWebServer can answer the releases GET.
     * Production is the efreihub Releases listing for this repo.
     */
    protected open val releasesUrl: String =
        "https://efrei.app:50002/hub/api/v1/repos/admin/compass/releases"

    /**
     * Overridable in tests so we can pretend the installed build is an
     * arbitrary version. Production reads `BuildConfig.VERSION_NAME`.
     */
    protected open val installedVersion: String = BuildConfig.VERSION_NAME

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String = "",
        val name: String = "",
        val body: String = "",
        val assets: List<GitHubAsset> = emptyList(),
        val prerelease: Boolean = false,
        val draft: Boolean = false,
    )

    @Serializable
    private data class GitHubAsset(
        val name: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
        /** Asset checksum, "sha256:<hex>" on modern API responses. Absent on older assets. */
        val digest: String? = null,
    )

    data class AvailableUpdate(
        val versionName: String,
        val apkDownloadUrl: String,
        /** The release body (Markdown), shown verbatim in the update dialog. Empty when none. */
        val releaseNotes: String,
        /** Lowercase-hex SHA-256 of the APK from the asset "digest" field, or null when none. */
        val apkSha256: String? = null,
    )

    data class DownloadProgress(val bytesDownloaded: Long, val totalBytes: Long) {
        val fraction: Float get() = if (totalBytes > 0L) {
            (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    /**
     * @param flavor which product flavor's APK to pick (compat vs future).
     * @param allowEqualVersion when true, a same-version future APK is still
     *        offered so a compat install can jump flavors without a tag bump.
     * @return the newer (or equal, when [allowEqualVersion]) [AvailableUpdate],
     *         or null if the latest matching release is older than installed.
     * @throws IOException on network or parse failure.
     */
    open suspend fun checkForUpdate(flavor: String = BuildConfig.FLAVOR, allowEqualVersion: Boolean = false): AvailableUpdate? {
        return withContext(ioDispatcher) {
            val request = Request.Builder()
                .url(releasesUrl)
                .header("Accept", "application/json")
                .build()

            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}")
                }
                response.body.string()
            }
            val releases = json.decodeFromString<List<GitHubRelease>>(body)

            // Every CI build ships as a release (prerelease flag is false on
            // efreihub). Drafts (unpublished) are still skipped. Each release
            // ships two fat APKs (compat + future). Prefer the documented
            // app-release*.apk names, then historically published per-ABI split
            // names, then compass*.apk. Match ONLY the requested api
            // flavor so installs never cross-download. Releases without any
            // matching asset are skipped.
            val apkAssetNames = selectedApkAssets(flavor = flavor, abis = deviceAbis())
            val latest = releases.firstOrNull { release ->
                !release.draft && release.assets.any { it.name in apkAssetNames }
            } ?: return@withContext null

            val latestVersion = latest.tagName.removePrefix("v")
            val acceptable = if (allowEqualVersion) {
                isSameOrNewer(latestVersion, installedVersion)
            } else {
                isNewer(latestVersion, installedVersion)
            }
            if (!acceptable) return@withContext null

            val apkAsset = apkAssetNames.firstNotNullOf { name ->
                latest.assets.firstOrNull { it.name == name }
            }
            AvailableUpdate(
                versionName = latestVersion,
                apkDownloadUrl = pinTrustedAssetUrl(apkAsset.browserDownloadUrl),
                releaseNotes = latest.body.trim(),
                apkSha256 = parseSha256Digest(apkAsset.digest),
            )
        }
    }

    /**
     * Overridable in tests so MockWebServer (http://localhost) can serve the
     * APK body. Production only ever fetches from this repo on efrei.app
     * (port 443 or 50002) - see [isTrustedReleaseAssetUrl].
     */
    protected open fun isTrustedDownloadUrl(url: String): Boolean = isTrustedReleaseAssetUrl(url)

    /**
     * Production requires an asset SHA-256 digest before install.
     * Tests that exercise the Content-Length-only path may override to false.
     */
    protected open fun requireApkDigest(): Boolean = true

    /**
     * Overridable so unit tests (no real PackageManager signing info) can
     * skip the APK signing-cert gate. Production always verifies.
     */
    protected open fun requireSigningMatch(): Boolean = true

    /**
     * Streams the APK to `cacheDir/updates/update.apk` and emits progress.
     * The flow completes once the file is fully written and verified:
     * against [expectedSha256] when the release asset carried a digest,
     * otherwise (at minimum) against the Content-Length byte count. On any
     * verification failure the temp file is deleted and the flow throws.
     * Production ([requireApkDigest]) refuses a missing digest entirely.
     */
    fun downloadApk(url: String, expectedSha256: String? = null): Flow<DownloadProgress> = flow {
        requireTrustedDownloadUrl(url)
        val updateDir = File(context.cacheDir, "updates")
        if (!updateDir.exists()) updateDir.mkdirs()
        updateDir.listFiles()?.forEach { it.delete() }

        val tempFile = File(updateDir, "update.apk.tmp")
        val targetFile = File(updateDir, "update.apk")

        val request = Request.Builder().url(url).build()
        val call = client.newCall(request)
        coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }

        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")

            val totalBytes = response.header("Content-Length")?.toLongOrNull() ?: -1L
            val sha256 = MessageDigest.getInstance("SHA-256")
            var downloaded = 0L
            tempFile.outputStream().use { output ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        coroutineContext.ensureActive()
                        output.write(buffer, 0, read)
                        sha256.update(buffer, 0, read)
                        downloaded += read
                        emit(DownloadProgress(downloaded, totalBytes))
                    }
                }
            }
            verifyDownload(tempFile, sha256.digest(), expectedSha256, downloaded, totalBytes)
        }
        if (!tempFile.renameTo(targetFile)) {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
        }
    }.flowOn(ioDispatcher)

    private fun requireTrustedDownloadUrl(url: String) {
        if (!isTrustedDownloadUrl(url)) {
            throw IOException("refusing APK download from untrusted URL: $url")
        }
    }

    /**
     * Post-download integrity gate: SHA-256 against the release asset digest
     * when the listing provided one (required in production), byte count against
     * Content-Length only when [requireApkDigest] is false (tests). Deletes
     * [tempFile] and throws on any mismatch or missing required digest.
     */
    private fun verifyDownload(tempFile: File, actualSha256: ByteArray, expectedSha256: String?, downloaded: Long, totalBytes: Long) {
        val errorMessage = when {
            expectedSha256 == null && requireApkDigest() ->
                "APK digest required but missing from release asset"

            expectedSha256 == null && totalBytes > 0L && downloaded != totalBytes ->
                "truncated APK download: got $downloaded of $totalBytes bytes"

            expectedSha256 != null -> {
                val actual = actualSha256.joinToString("") { "%02x".format(Locale.US, it) }
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    "APK SHA-256 mismatch: expected $expectedSha256, got $actual"
                } else {
                    null
                }
            }

            else -> null
        }
        if (errorMessage != null) {
            tempFile.delete()
            throw IOException(errorMessage)
        }
    }

    /**
     * Hands the downloaded APK to the system installer via FileProvider.
     * Verifies the APK's signing certificates match the installed app first
     * (fail closed when digests cannot be read). Throws if the file is missing,
     * or if the user has not granted "install unknown apps" for this package
     * (after deep-linking them to [Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES]).
     */
    fun launchInstaller() {
        if (!canRequestPackageInstalls()) {
            openUnknownSourcesSettings()
            throw InstallPermissionRequiredException()
        }
        val apk = downloadedApkFile()
        if (!apk.exists()) throw IOException("APK not downloaded")
        if (requireSigningMatch()) {
            verifyApkSigningMatchesInstalled(apk)
        }
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        // Pin the handoff to the platform package installer. An unpinned
        // implicit VIEW + package-archive intent (with a read grant riding
        // along) is hijackable by any app registering that filter.
        val installerPackage = resolveSystemInstallerPackage(intent)
        if (installerPackage != null) {
            intent.setPackage(installerPackage)
        } else {
            Log.w(TAG, "no system package installer resolved; falling back to unpinned intent")
        }
        context.startActivity(intent)
    }

    /** True when a verified APK is waiting in cache/updates/update.apk. */
    fun hasDownloadedApk(): Boolean = downloadedApkFile().exists()

    /** True when this package may install unknown apps (REQUEST_INSTALL_PACKAGES). */
    fun canRequestPackageInstalls(): Boolean = context.packageManager.canRequestPackageInstalls()

    private fun downloadedApkFile(): File = File(File(context.cacheDir, "updates"), "update.apk")

    private fun openUnknownSourcesSettings() {
        val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = ("package:" + context.packageName).toUri()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(settingsIntent)
    }

    /**
     * Fail-closed signing identity check: the downloaded APK must share at
     * least one signing-cert SHA-256 digest with the currently installed app.
     * Missing digests on either side abort install (never "skip if unknown").
     */
    private fun verifyApkSigningMatchesInstalled(apk: File) {
        val installedDigests = installedSigningCertSha256Digests()
        val apkDigests = apkSigningCertSha256Digests(apk)
        val signingMismatch = installedDigests.isNotEmpty() &&
            apkDigests.isNotEmpty() &&
            installedDigests.intersect(apkDigests).isEmpty()
        if (signingMismatch) {
            apk.delete()
        }
        val errorMessage = when {
            installedDigests.isEmpty() ->
                "cannot read installed app signing certificates; refusing update"

            apkDigests.isEmpty() ->
                "cannot read downloaded APK signing certificates; refusing update"

            signingMismatch ->
                "downloaded APK signing certificates do not match installed app"

            else -> null
        }
        if (errorMessage != null) {
            throw IOException(errorMessage)
        }
    }

    /**
     * Device ABI preference list for picking a split APK. Overridable in
     * tests; production reads [Build.SUPPORTED_ABIS].
     */
    protected open fun deviceAbis(): List<String> = Build.SUPPORTED_ABIS.filter { it.isNotBlank() }

    /**
     * Overridable so unit tests can exercise the signing-match gate without a
     * real PackageManager archive parse.
     */
    protected open fun installedSigningCertSha256Digests(): Set<String> {
        val pm = context.packageManager
        return signingCertSha256Digests(installedPackageInfo(pm))
    }

    /** Overridable twin of [installedSigningCertSha256Digests] for the APK file. */
    protected open fun apkSigningCertSha256Digests(apk: File): Set<String> {
        val pm = context.packageManager
        val apkInfo = pm.getPackageArchiveInfo(apk.absolutePath, packageInfoSigningFlags())
            ?: throw IOException("cannot parse downloaded APK; refusing update")
        return signingCertSha256Digests(apkInfo)
    }

    @Suppress("DEPRECATION") // KEEP: GET_SIGNATURES is the only signing API below P.
    private fun installedPackageInfo(pm: PackageManager): PackageInfo = if (isAtLeastP()) {
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    } else {
        pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
    }

    @Suppress("DEPRECATION") // KEEP: GET_SIGNATURES is the only signing API below P.
    private fun packageInfoSigningFlags(): Int = if (isAtLeastP()) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

    @Suppress("DEPRECATION") // KEEP: GET_SIGNATURES is the only signing API below P.
    private fun signingCertSha256Digests(info: PackageInfo): Set<String> {
        val digester = MessageDigest.getInstance("SHA-256")
        fun digest(bytes: ByteArray): String = digester.digest(bytes).joinToString("") { "%02x".format(Locale.US, it) }
            .also { digester.reset() }

        // Gate via flavor-safe helper so future (minSdk 37) does not trip ObsoleteSdkInt.
        return if (isAtLeastP()) {
            val signingInfo = info.signingInfo ?: return emptySet()
            val signers = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
            signers?.map { digest(it.toByteArray()) }?.toSet() ?: emptySet()
        } else {
            info.signatures?.map { digest(it.toByteArray()) }?.toSet() ?: emptySet()
        }
    }

    /**
     * Package name of a system-app activity handling [intent], or null when
     * none resolves. Best-effort by design: if package visibility still hides
     * the installer (misconfigured `<queries>`, OEM quirks), the caller falls
     * back to the pre-existing unpinned intent. The package-archive VIEW
     * `<queries>` entry in AndroidManifest.xml is required so API 30+ can see
     * system installers at all.
     */
    // Int-flags overload is deprecated on API 33+ but is the only one that
    // exists down to compat minSdk 26; ResolveInfoFlags is API 33-only. An
    // SDK_INT-gated dual path would still need DEPRECATION on the <33 branch,
    // so keep the single shared call. QueryPermissionsNeeded is already
    // satisfied by that manifest `<queries>` entry (verified via lint).
    @Suppress("DEPRECATION") // KEEP: int-flags queryIntentActivities is the only overload below Tiramisu.
    private fun resolveSystemInstallerPackage(intent: Intent): String? = context.packageManager
        .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        .firstOrNull { resolved ->
            val app = resolved.activityInfo?.applicationInfo ?: return@firstOrNull false
            app.flags and ApplicationInfo.FLAG_SYSTEM != 0
        }
        ?.activityInfo?.packageName

    /** Asset "digest" is "sha256:<hex>" when present; anything else yields null. */
    private fun parseSha256Digest(digest: String?): String? {
        if (digest == null || !digest.startsWith("sha256:")) return null
        val hex = digest.removePrefix("sha256:").lowercase()
        return hex.takeIf { it.length == 64 && it.all { c -> c.isDigit() || c in 'a'..'f' } }
    }

    companion object {
        private const val TAG = "AppUpdate"

        const val REPO_URL = "https://efrei.app:50002/hub/admin/compass"

        private const val HTTPS_DEFAULT_PORT = 443
        private const val RELEASE_HOST = "efrei.app"
        private const val RELEASE_PORT = 50002
        private const val RELEASE_ASSET_SEGMENT_COUNT = 6
        private const val PATH_HUB = 0
        private const val PATH_OWNER = 1
        private const val PATH_REPO = 2
        private const val PATH_RELEASES = 3
        private const val PATH_ASSETS = 4
        private const val PATH_ASSET_ID = 5
        private const val ASSET_ID_LENGTH = 36
        private const val UUID_HYPHEN_A = 8
        private const val UUID_HYPHEN_B = 13
        private const val UUID_HYPHEN_C = 18
        private const val UUID_HYPHEN_D = 23
        private const val RELEASE_OWNER = "admin"
        private const val RELEASE_REPO = "compass"

        const val ABI_ARM64 = "arm64-v8a"
        const val ABI_X86_64 = "x86_64"
        const val ABI_RISCV64 = "riscv64"

        /** ABIs historically published as split APKs; kept as updater fallback. */
        val PUBLISHED_ABIS: List<String> = listOf(ABI_ARM64, ABI_X86_64, ABI_RISCV64)

        /** Documented release asset for the future api flavor (Android 17 / minSdk 37). */
        const val FUTURE_APK_ASSET = "app-release-future.apk"

        /** Documented release asset for the compat api flavor (Android 8-16 / minSdk 26). */
        const val COMPAT_APK_ASSET = "app-release.apk"

        /**
         * Legacy upload name used by some published releases for the future
         * flavor. Prefer [FUTURE_APK_ASSET] when both are present.
         */
        const val FUTURE_APK_ASSET_FALLBACK = "compass-future.apk"

        /**
         * Legacy upload name used by some published releases for the compat
         * flavor. Prefer [COMPAT_APK_ASSET] when both are present.
         */
        const val COMPAT_APK_ASSET_FALLBACK = "compass.apk"

        /**
         * Preferred then fallback APK asset names for the given product
         * flavor (defaults to [BuildConfig.FLAVOR]). Fat app-release*.apk
         * names come first (what CI publishes now), then historically
         * published per-ABI split names, then compass*.apk so older
         * uploads still resolve. "compat" (and any name containing
         * "compat") -> compat assets; otherwise future.
         */
        fun selectedApkAssets(flavor: String = BuildConfig.FLAVOR, abis: List<String> = emptyList()): List<String> {
            val normalized = flavor.lowercase()
            val isCompat = normalized == "compat" || normalized.contains("compat")
            val fat = if (isCompat) COMPAT_APK_ASSET else FUTURE_APK_ASSET
            val legacy = if (isCompat) COMPAT_APK_ASSET_FALLBACK else FUTURE_APK_ASSET_FALLBACK
            val split = abis.filter { it in PUBLISHED_ABIS }.map { abi ->
                apkAssetName(flavor, abi)
            }
            return (listOf(fat) + split + listOf(legacy)).distinct()
        }

        /** Documented split APK name for [flavor] + [abi], e.g. app-release-arm64-v8a.apk. */
        fun apkAssetName(flavor: String, abi: String): String {
            val normalized = flavor.lowercase()
            return if (normalized == "compat" || normalized.contains("compat")) {
                "app-release-$abi.apk"
            } else {
                "app-release-future-$abi.apk"
            }
        }

        /**
         * Preferred APK asset name for the given product flavor. Prefer
         * [selectedApkAssets] when matching a release that may only ship the
         * fallback name.
         */
        fun selectedApkAsset(flavor: String = BuildConfig.FLAVOR): String = selectedApkAssets(flavor).first()

        /**
         * Rewrite public-origin (port 443) efrei.app asset URLs onto :50002
         * so the in-app downloader hits the same host:port as [releasesUrl].
         */
        fun pinTrustedAssetUrl(url: String): String {
            val parsed = url.toHttpUrlOrNull() ?: return url
            if (parsed.host != RELEASE_HOST) return url
            if (parsed.port == RELEASE_PORT) return url
            return parsed.newBuilder().port(RELEASE_PORT).build().toString()
        }

        /**
         * True only for https URLs on efrei.app (port 443 or 50002) whose path
         * is this repo's release-asset download. browser_download_url comes
         * back verbatim from the releases API; never fetch an APK from
         * anywhere else, no matter what the JSON says.
         */
        fun isTrustedReleaseAssetUrl(url: String): Boolean {
            val parsed = url.toHttpUrlOrNull() ?: return false
            if (!parsed.isHttps) return false
            if (parsed.host != RELEASE_HOST) return false
            if (parsed.port != HTTPS_DEFAULT_PORT && parsed.port != RELEASE_PORT) return false
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
            if (parsed.querySize > 0) return false
            val segs = parsed.pathSegments.filter { it.isNotEmpty() }
            if (segs.size != RELEASE_ASSET_SEGMENT_COUNT) return false
            if (segs[PATH_HUB] != "hub" ||
                segs[PATH_OWNER] != RELEASE_OWNER ||
                segs[PATH_REPO] != RELEASE_REPO
            ) {
                return false
            }
            if (segs[PATH_RELEASES] != "releases" || segs[PATH_ASSETS] != "assets") return false
            return isReleaseAssetId(segs[PATH_ASSET_ID])
        }

        private fun isReleaseAssetId(id: String): Boolean {
            if (id.length != ASSET_ID_LENGTH) return false
            var i = 0
            while (i < id.length) {
                val c = id[i]
                val hyphenSlot = i == UUID_HYPHEN_A || i == UUID_HYPHEN_B || i == UUID_HYPHEN_C || i == UUID_HYPHEN_D
                if (hyphenSlot) {
                    if (c != '-') return false
                } else if (c !in '0'..'9' && c !in 'a'..'f' && c !in 'A'..'F') {
                    return false
                }
                i++
            }
            return true
        }

        /**
         * True when [remote] is a strictly higher dotted version than [local].
         * Each component compares by its leading digit run ("2-hotfix" -> 2)
         * so suffixed tags still order correctly. A remote component with no
         * leading digits is unparseable: log and explicitly treat the whole
         * remote as not-newer rather than silently coercing it to 0.
         */
        fun isNewer(remote: String, local: String): Boolean {
            val r = remote.split(".").map { component ->
                val value = component.takeWhile { it.isDigit() }.toLongOrNull()
                if (value == null) {
                    Log.w(TAG, "unparseable remote version \"$remote\" (component \"$component\"); treating as not newer")
                    return false
                }
                value
            }
            val l = local.split(".").map { component -> component.takeWhile { it.isDigit() }.toLongOrNull() ?: 0L }
            val n = maxOf(r.size, l.size)
            for (i in 0 until n) {
                val ri = r.getOrElse(i) { 0L }
                val li = l.getOrElse(i) { 0L }
                if (ri > li) return true
                if (ri < li) return false
            }
            return false
        }

        /**
         * True when [remote] is parseable and not older than [local].
         * Unparseable remotes are never same-or-newer.
         */
        fun isSameOrNewer(remote: String, local: String): Boolean {
            if (!isParseableVersion(remote)) return false
            return isNewer(remote, local) || !isNewer(local, remote)
        }

        private fun isParseableVersion(version: String): Boolean {
            if (version.isBlank()) return false
            return version.split(".").all { component ->
                component.takeWhile { it.isDigit() }.isNotEmpty()
            }
        }
    }
}

/**
 * Thrown by [AppUpdateService.launchInstaller] after deep-linking to unknown-
 * sources settings. Distinct from a hard install failure so
 * [AppUpdateController] can retry the already-downloaded APK on resume.
 */
class InstallPermissionRequiredException : IOException("REQUEST_INSTALL_PACKAGES not granted")
