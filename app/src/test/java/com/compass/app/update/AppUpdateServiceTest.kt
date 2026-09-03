@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.compass.app.update

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/**
 * Regression coverage for the self-update check.
 *
 * Specifically locks in the pre-alpha requirement: every CI build ships as a
 * efreihub *release*, so the check MUST NOT filter them out. The pre-fix
 * code did - and the user reported it "never detects newer versions".
 */
class AppUpdateServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File
    private val context = mockk<Context>(relaxed = true)
    private val client = OkHttpClient()
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        cacheDir = Files.createTempDirectory("update-test-cache").toFile()
        every { context.cacheDir } returns cacheDir
    }

    @After fun tearDown() {
        server.shutdown()
        cacheDir.deleteRecursively()
    }

    @Test fun `detects a newer PRE-RELEASE (regression - pre-alpha ships everything as prerelease)`() = runTest {
        server.enqueue(MockResponse().setBody(releasesJson(release(tag = "v0.3.50", prerelease = true))))
        val svc = testService(installed = "0.3.46")

        val update = svc.checkForUpdate()
        assertThat(update).isNotNull()
        checkNotNull(update)
        assertThat(update.versionName).isEqualTo("0.3.50")
        assertThat(update.apkDownloadUrl).endsWith(AppUpdateService.selectedApkAsset())
    }

    @Test fun `detects a newer stable release too`() = runTest {
        server.enqueue(MockResponse().setBody(releasesJson(release(tag = "v1.0.0", prerelease = false))))
        val svc = testService(installed = "0.9.9")

        assertThat(svc.checkForUpdate()?.versionName).isEqualTo("1.0.0")
    }

    @Test fun `returns null when the only release is older than installed`() = runTest {
        server.enqueue(MockResponse().setBody(releasesJson(release(tag = "v0.3.40", prerelease = true))))
        val svc = testService(installed = "0.3.46")

        assertThat(svc.checkForUpdate()).isNull()
    }

    @Test fun `returns null when latest matches installed exactly`() = runTest {
        server.enqueue(MockResponse().setBody(releasesJson(release(tag = "v0.3.46", prerelease = true))))
        val svc = testService(installed = "0.3.46")

        assertThat(svc.checkForUpdate()).isNull()
    }

    @Test fun `picks the first (newest) entry and ignores older ones even if out of version order`() = runTest {
        // The releases API returns entries newest-first by publish date. Our code relies on that - the version
        // comparison gate catches the edge case where the first entry is older than the installed
        // build (which happens when we've locally moved past the latest CI release).
        server.enqueue(
            MockResponse().setBody(
                releasesJson(
                    release(tag = "v0.3.50", prerelease = true),
                    release(tag = "v0.3.49", prerelease = true),
                    release(tag = "v0.3.48", prerelease = true),
                ),
            ),
        )
        val svc = testService(installed = "0.3.46")

        assertThat(svc.checkForUpdate()?.versionName).isEqualTo("0.3.50")
    }

    @Test fun `skips drafts`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                releasesJson(
                    release(tag = "v0.3.60", prerelease = true, draft = true),
                    release(tag = "v0.3.55", prerelease = true),
                ),
            ),
        )
        val svc = testService(installed = "0.3.46")

        // Draft at top is skipped; we fall through to the v0.3.55 prerelease.
        assertThat(svc.checkForUpdate()?.versionName).isEqualTo("0.3.55")
    }

    @Test fun `skips releases with no apk asset`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                releasesJson(
                    release(tag = "v0.3.60", prerelease = true, assetName = "source.zip"),
                    release(tag = "v0.3.55", prerelease = true, assetName = AppUpdateService.selectedApkAsset()),
                ),
            ),
        )
        val svc = testService(installed = "0.3.46")

        assertThat(svc.checkForUpdate()?.versionName).isEqualTo("0.3.55")
    }

    @Test fun `returns null on empty releases list`() = runTest {
        server.enqueue(MockResponse().setBody("[]"))
        val svc = testService(installed = "0.3.46")

        assertThat(svc.checkForUpdate()).isNull()
    }

    @Test fun `throws on non-2xx response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val svc = testService(installed = "0.3.46")

        val ex = runCatching { svc.checkForUpdate() }.exceptionOrNull()
        assertThat(ex?.message).contains("HTTP 503")
    }

    @Test fun `strips leading v from tag name`() = runTest {
        server.enqueue(MockResponse().setBody(releasesJson(release(tag = "v2.0.0", prerelease = true))))
        val svc = testService(installed = "1.0.0")

        assertThat(svc.checkForUpdate()?.versionName).isEqualTo("2.0.0")
    }

    @Test fun `isNewer compares dotted-int versions correctly`() {
        with(AppUpdateService) {
            // Strictly greater - patch bump
            assertThat(isNewer("0.3.47", "0.3.46")).isTrue()
            // Minor bump, not just a patch increment (user raised this explicitly)
            assertThat(isNewer("0.4.0", "0.3.99")).isTrue()
            assertThat(isNewer("0.4.0", "0.3.46")).isTrue()
            // Major bump
            assertThat(isNewer("1.0.0", "0.99.99")).isTrue()
            assertThat(isNewer("1.0.0", "0.3.46")).isTrue()
            // Multi-digit components (don't lex-compare)
            assertThat(isNewer("0.10.0", "0.9.99")).isTrue()
            assertThat(isNewer("0.3.100", "0.3.99")).isTrue()
            // Equal
            assertThat(isNewer("0.3.46", "0.3.46")).isFalse()
            // Strictly less
            assertThat(isNewer("0.3.45", "0.3.46")).isFalse()
            assertThat(isNewer("0.2.99", "0.3.0")).isFalse()
            // Mismatched length - shorter implies .0
            assertThat(isNewer("0.4", "0.3.99")).isTrue()
            assertThat(isNewer("0.3", "0.3.0")).isFalse()
            assertThat(isNewer("1", "0.99.99")).isTrue()
            // A suffixed component compares by its leading digit run, so
            // "47-hotfix" orders as 47 - not as 0 (the pre-fix coercion that
            // made suffixed tags look OLDER and never be offered).
            assertThat(isNewer("0.3.47-hotfix", "0.3.46")).isTrue()
            assertThat(isNewer("0.3.46-hotfix", "0.3.46")).isFalse() // equal, not strictly newer
            assertThat(isNewer("0.3.45-rc1", "0.3.46")).isFalse()
            // A fourth numeric stamp (CI unix epoch) is strictly newer than
            // the three-component base versionName.
            assertThat(isNewer("0.5.40.1756570000", "0.5.40")).isTrue()
            assertThat(isNewer("0.5.40.1756570000", "0.5.40-legacy")).isTrue()
            assertThat(isNewer("0.5.40.1756570100", "0.5.40.1756570000")).isTrue()
            assertThat(isNewer("0.5.40.1756570000", "0.5.40.1756570000")).isFalse()
            // A remote component with NO leading digits is unparseable:
            // explicitly not-newer, never "coerced to 0 and compared anyway".
            assertThat(isNewer("0.3.hotfix", "0.3.46")).isFalse()
            assertThat(isNewer("nightly", "0.0.1")).isFalse()
            assertThat(isNewer("v.1.0", "0.0.1")).isFalse()
            assertThat(isSameOrNewer("0.5.41", "0.5.41")).isTrue()
            assertThat(isSameOrNewer("0.5.41", "0.5.41-legacy")).isTrue()
            assertThat(isSameOrNewer("0.5.42", "0.5.41")).isTrue()
            assertThat(isSameOrNewer("0.5.40", "0.5.41")).isFalse()
            assertThat(isSameOrNewer("hotfix", "0.5.41")).isFalse()
        }
    }

    @Test fun `end-to-end minor bump (0-4-0) is detected from prerelease`() = runTest {
        server.enqueue(
            MockResponse().setBody(releasesJson(release(tag = "v0.4.0", prerelease = true))),
        )
        val svc = testService(installed = "0.3.46")

        assertThat(svc.checkForUpdate()?.versionName).isEqualTo("0.4.0")
    }

    @Test fun `end-to-end major bump (1-0-0) is detected from prerelease`() = runTest {
        server.enqueue(
            MockResponse().setBody(releasesJson(release(tag = "v1.0.0", prerelease = true))),
        )
        val svc = testService(installed = "0.99.99")

        assertThat(svc.checkForUpdate()?.versionName).isEqualTo("1.0.0")
    }

    @Test fun `selectedApkAsset maps future and compat flavors`() {
        assertThat(AppUpdateService.selectedApkAsset("future"))
            .isEqualTo(AppUpdateService.FUTURE_APK_ASSET)
        assertThat(AppUpdateService.selectedApkAsset("compat"))
            .isEqualTo(AppUpdateService.COMPAT_APK_ASSET)
        // Multi-dimension flavor names that still contain the api flavor.
        assertThat(AppUpdateService.selectedApkAsset("freeCompat"))
            .isEqualTo(AppUpdateService.COMPAT_APK_ASSET)
        assertThat(AppUpdateService.selectedApkAsset("futureRelease"))
            .isEqualTo(AppUpdateService.FUTURE_APK_ASSET)
        assertThat(AppUpdateService.selectedApkAssets("compat")).containsExactly(
            AppUpdateService.COMPAT_APK_ASSET,
            AppUpdateService.COMPAT_APK_ASSET_FALLBACK,
        ).inOrder()
        assertThat(AppUpdateService.selectedApkAssets("future")).containsExactly(
            AppUpdateService.FUTURE_APK_ASSET,
            AppUpdateService.FUTURE_APK_ASSET_FALLBACK,
        ).inOrder()
        assertThat(
            AppUpdateService.selectedApkAssets("compat", listOf(AppUpdateService.ABI_ARM64)),
        ).containsExactly(
            AppUpdateService.COMPAT_APK_ASSET,
            "app-release-arm64-v8a.apk",
            AppUpdateService.COMPAT_APK_ASSET_FALLBACK,
        ).inOrder()
        assertThat(
            AppUpdateService.selectedApkAssets("future", listOf(AppUpdateService.ABI_X86_64)),
        ).containsExactly(
            AppUpdateService.FUTURE_APK_ASSET,
            "app-release-future-x86_64.apk",
            AppUpdateService.FUTURE_APK_ASSET_FALLBACK,
        ).inOrder()
        assertThat(
            AppUpdateService.apkAssetName("compat", AppUpdateService.ABI_RISCV64),
        ).isEqualTo("app-release-riscv64.apk")
    }

    @Test fun `picks the apk matching this device abi when split assets ship`() = runTest {
        val flavorAsset = AppUpdateService.selectedApkAsset()
        val abiAsset = AppUpdateService.apkAssetName(
            if (flavorAsset == AppUpdateService.COMPAT_APK_ASSET) "compat" else "future",
            AppUpdateService.ABI_ARM64,
        )
        val otherAbi = AppUpdateService.apkAssetName(
            if (flavorAsset == AppUpdateService.COMPAT_APK_ASSET) "compat" else "future",
            AppUpdateService.ABI_X86_64,
        )
        val release = """
            {
              "tag_name": "v1.2.3",
              "name": "v1.2.3",
              "body": "notes",
              "prerelease": true,
              "draft": false,
              "assets": [
                {"name": "$otherAbi", "browser_download_url": "https://releases.example/v1.2.3/$otherAbi"},
                {"name": "$abiAsset", "browser_download_url": "https://releases.example/v1.2.3/$abiAsset"},
                {"name": "$flavorAsset", "browser_download_url": "https://releases.example/v1.2.3/$flavorAsset"}
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody("[$release]"))
        val svc = testService(installed = "1.0.0", deviceAbis = listOf(AppUpdateService.ABI_ARM64))

        assertThat(svc.checkForUpdate()?.apkDownloadUrl).endsWith(flavorAsset)
    }

    @Test fun `falls back to this device abi split when the fat name is absent`() = runTest {
        val flavorAsset = AppUpdateService.selectedApkAsset()
        val abiAsset = AppUpdateService.apkAssetName(
            if (flavorAsset == AppUpdateService.COMPAT_APK_ASSET) "compat" else "future",
            AppUpdateService.ABI_ARM64,
        )
        val release = """
            {
              "tag_name": "v1.2.3",
              "name": "v1.2.3",
              "body": "notes",
              "prerelease": true,
              "draft": false,
              "assets": [
                {"name": "$abiAsset", "browser_download_url": "https://releases.example/v1.2.3/$abiAsset"}
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody("[$release]"))
        val svc = testService(installed = "1.0.0", deviceAbis = listOf(AppUpdateService.ABI_ARM64))

        assertThat(svc.checkForUpdate()?.apkDownloadUrl).endsWith(abiAsset)
    }

    @Test fun `allowEqualVersion offers the same tag so compat can jump to future`() = runTest {
        val body = releasesJson(release(tag = "v1.2.3", prerelease = true))
        server.enqueue(MockResponse().setBody(body))
        server.enqueue(MockResponse().setBody(body))
        val svc = testService(installed = "1.2.3")

        assertThat(svc.checkForUpdate(allowEqualVersion = false)).isNull()
        assertThat(svc.checkForUpdate(allowEqualVersion = true)?.versionName).isEqualTo("1.2.3")
    }

    @Test fun `flavor override picks the future fat apk from a compat install`() = runTest {
        val release = """
            {
              "tag_name": "v1.2.3",
              "name": "v1.2.3",
              "body": "notes",
              "prerelease": true,
              "draft": false,
              "assets": [
                {"name": "app-release.apk", "browser_download_url": "https://releases.example/v1.2.3/app-release.apk"},
                {"name": "app-release-future.apk", "browser_download_url": "https://releases.example/v1.2.3/app-release-future.apk"}
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody("[$release]"))
        server.enqueue(MockResponse().setBody("[$release]"))
        val svc = testService(installed = "1.0.0")

        assertThat(svc.checkForUpdate(flavor = "compat")?.apkDownloadUrl).endsWith("app-release.apk")
        assertThat(svc.checkForUpdate(flavor = "future")?.apkDownloadUrl).endsWith("app-release-future.apk")
    }

    @Test fun `picks the apk asset matching this build flavor when both ship`() = runTest {
        // Each release ships TWO apks side by side:
        //   app-release.apk         -> compat (Android 8-16)
        //   app-release-future.apk  -> future (Android 17)
        // This build must get its own flavor's apk; otherwise the updater
        // silently cross-installs the wrong minSdk APK.
        val release = """
            {
              "tag_name": "v1.2.3",
              "name": "v1.2.3",
              "body": "notes",
              "prerelease": true,
              "draft": false,
              "assets": [
                {"name": "app-release.apk", "browser_download_url": "https://releases.example/v1.2.3/app-release.apk"},
                {"name": "app-release-future.apk", "browser_download_url": "https://releases.example/v1.2.3/app-release-future.apk"}
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody("[$release]"))
        val svc = testService(installed = "1.0.0")

        val update = svc.checkForUpdate()
        assertThat(update?.apkDownloadUrl).endsWith(AppUpdateService.selectedApkAsset())
    }

    @Test fun `falls back to compass asset names when documented names are absent`() = runTest {
        // GitHub releases may ship compass*.apk; the updater also
        // accepts app-release*.apk. Prefer the documented app-release name
        // when present; otherwise accept the compass upload.
        val preferred = AppUpdateService.selectedApkAsset()
        val fallback = AppUpdateService.selectedApkAssets().last()
        val release = """
            {
              "tag_name": "v1.2.3",
              "name": "v1.2.3",
              "body": "notes",
              "prerelease": true,
              "draft": false,
              "assets": [
                {"name": "$fallback", "browser_download_url": "https://releases.example/v1.2.3/$fallback"}
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody("[$release]"))
        val svc = testService(installed = "1.0.0")

        val update = svc.checkForUpdate()
        assertThat(update?.apkDownloadUrl).endsWith(fallback)
        assertThat(preferred).isNotEqualTo(fallback)
    }

    @Test fun `prefers documented app-release name over compass fallback when both ship`() = runTest {
        val preferred = AppUpdateService.selectedApkAsset()
        val fallback = AppUpdateService.selectedApkAssets().last()
        val release = """
            {
              "tag_name": "v1.2.3",
              "name": "v1.2.3",
              "body": "notes",
              "prerelease": true,
              "draft": false,
              "assets": [
                {"name": "$fallback", "browser_download_url": "https://releases.example/v1.2.3/$fallback"},
                {"name": "$preferred", "browser_download_url": "https://releases.example/v1.2.3/$preferred"}
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody("[$release]"))
        val svc = testService(installed = "1.0.0")

        assertThat(svc.checkForUpdate()?.apkDownloadUrl).endsWith(preferred)
    }

    @Test fun `skips release that only has the other flavor apk`() = runTest {
        val otherAsset = if (AppUpdateService.selectedApkAsset() == AppUpdateService.FUTURE_APK_ASSET) {
            AppUpdateService.COMPAT_APK_ASSET
        } else {
            AppUpdateService.FUTURE_APK_ASSET
        }
        val release = """
            {
              "tag_name": "v9.9.9",
              "name": "v9.9.9",
              "body": "notes",
              "prerelease": true,
              "draft": false,
              "assets": [
                {"name": "$otherAsset", "browser_download_url": "https://releases.example/v9.9.9/$otherAsset"}
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody("[$release]"))
        val svc = testService(installed = "1.0.0")

        assertThat(svc.checkForUpdate()).isNull()
    }

    @Test fun `falls through when the top release has no asset yet (CI still uploading)`() = runTest {
        // Shape: newest tag was just published and its APK hasn't finished
        // uploading (assets=0), while the next-newest is a prerelease that DOES
        // have an APK. The buggy pre-fix code filtered the top one out (no
        // APK) AND the next one (prerelease) and returned null; the fix falls
        // through to the assetful prerelease.
        val asset = AppUpdateService.selectedApkAsset()
        server.enqueue(
            MockResponse().setBody(
                releasesJson(
                    release(tag = "v9.9.9", prerelease = false, assetName = null),
                    release(tag = "v9.9.8", prerelease = true, assetName = asset),
                    release(tag = "v9.9.7", prerelease = true, assetName = asset),
                ),
            ),
        )
        val svc = testService(installed = "9.9.0")

        val update = svc.checkForUpdate()
        assertThat(update?.versionName).isEqualTo("9.9.8")
    }

    @Test fun `checkForUpdate surfaces the asset sha256 digest - and null when absent or not sha256`() = runTest {
        val hex = "ab".repeat(32)
        server.enqueue(
            MockResponse().setBody(releasesJson(release(tag = "v9.9.9", prerelease = true, digest = "sha256:$hex"))),
        )
        server.enqueue(
            MockResponse().setBody(releasesJson(release(tag = "v9.9.9", prerelease = true, digest = "md5:abcdef"))),
        )
        server.enqueue(
            MockResponse().setBody(releasesJson(release(tag = "v9.9.9", prerelease = true, digest = null))),
        )
        val svc = testService(installed = "0.1.0")

        assertThat(svc.checkForUpdate()?.apkSha256).isEqualTo(hex)
        assertThat(svc.checkForUpdate()?.apkSha256).isNull() // non-sha256 digest is ignored
        assertThat(svc.checkForUpdate()?.apkSha256).isNull() // no digest field at all
    }

    @Test fun `downloadApk verifies integrity - digest match, missing digest fails closed, mismatch aborts`() = runTest {
        val svc = testService(installed = "0.1.0", trustAllDownloadUrls = true)
        val url = server.url("/update.apk").toString()
        val updates = File(cacheDir, "updates")
        val body = "fake apk bytes for the digest check"
        val bodySha256 = MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray())
            .joinToString("") { "%02x".format(it) }

        // Matching digest: update.apk lands with the exact bytes.
        server.enqueue(MockResponse().setBody(body))
        svc.downloadApk(url, bodySha256).collect { }
        assertThat(File(updates, "update.apk").readText()).isEqualTo(body)

        // Production fail-closed: no digest from GitHub refuses the installable file.
        server.enqueue(MockResponse().setBody(body))
        val missing = runCatching { svc.downloadApk(url, null).collect { } }.exceptionOrNull()
        assertThat(missing).isInstanceOf(IOException::class.java)
        assertThat(missing?.message).contains("digest required")

        // Tests may still exercise Content-Length fallback with requireApkDigest=false.
        val lenSvc = testService(installed = "0.1.0", trustAllDownloadUrls = true, requireDigest = false)
        server.enqueue(MockResponse().setBody(body))
        lenSvc.downloadApk(url, null).collect { }
        assertThat(File(updates, "update.apk").readText()).isEqualTo(body)

        // Digest mismatch (tampered bytes): abort, delete, nothing installable left.
        server.enqueue(MockResponse().setBody("tampered apk bytes"))
        val ex = runCatching { svc.downloadApk(url, "0".repeat(64)).collect { } }.exceptionOrNull()
        assertThat(ex).isInstanceOf(IOException::class.java)
        assertThat(ex?.message).contains("SHA-256 mismatch")
        assertThat(File(updates, "update.apk").exists()).isFalse()
        assertThat(File(updates, "update.apk.tmp").exists()).isFalse()
    }

    @Test fun happy_pins_efrei_asset_url_to_port_50002() = runTest {
        val assetId = "11111111-1111-1111-1111-111111111111"
        val listed = "https://efrei.app/hub/admin/compass/releases/assets/$assetId"
        server.enqueue(
            MockResponse().setBody(
                releasesJson(
                    release(
                        tag = "v0.9.0",
                        prerelease = true,
                        downloadUrl = listed,
                    ),
                ),
            ),
        )
        val svc = testService(installed = "0.1.0")
        val update = svc.checkForUpdate()
        assertThat(update).isNotNull()
        checkNotNull(update)
        assertThat(update.apkDownloadUrl)
            .isEqualTo("https://efrei.app:50002/hub/admin/compass/releases/assets/$assetId")
    }

    @Test fun `downloadApk refuses untrusted URLs without touching the network`() = runTest {
        val assetId = "11111111-1111-1111-1111-111111111111"
        val trusted = "https://efrei.app:50002/hub/admin/compass/releases/assets/$assetId"
        with(AppUpdateService) {
            assertThat(isTrustedReleaseAssetUrl(trusted)).isTrue()
            assertThat(
                isTrustedReleaseAssetUrl(
                    "https://efrei.app/hub/admin/compass/releases/assets/$assetId",
                ),
            ).isTrue()
            assertThat(
                isTrustedReleaseAssetUrl(
                    "http://efrei.app:50002/hub/admin/compass/releases/assets/$assetId",
                ),
            ).isFalse()
            assertThat(isTrustedReleaseAssetUrl("https://evil.example/update.apk")).isFalse()
            assertThat(
                isTrustedReleaseAssetUrl(
                    "https://efrei.app.evil.example/hub/admin/compass/releases/assets/$assetId",
                ),
            ).isFalse()
            assertThat(
                isTrustedReleaseAssetUrl(
                    "https://notefrei.app:50002/hub/admin/compass/releases/assets/$assetId",
                ),
            ).isFalse()
            assertThat(isTrustedReleaseAssetUrl("not a url")).isFalse()
        }

        val svc = testService(installed = "0.1.0")
        val httpEx = runCatching {
            svc.downloadApk(
                "http://efrei.app:50002/hub/admin/compass/releases/assets/$assetId",
            ).collect { }
        }.exceptionOrNull()
        assertThat(httpEx).isInstanceOf(IOException::class.java)
        assertThat(httpEx?.message).contains("untrusted")
        val hostEx = runCatching { svc.downloadApk("https://evil.example/update.apk").collect { } }.exceptionOrNull()
        assertThat(hostEx).isInstanceOf(IOException::class.java)
        assertThat(hostEx?.message).contains("untrusted")
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun happy_trusted_efrei_asset_url() {
        val id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        assertThat(
            AppUpdateService.isTrustedReleaseAssetUrl(
                "https://efrei.app:50002/hub/admin/compass/releases/assets/$id",
            ),
        ).isTrue()
    }

    @Test fun bad_http_or_query_efrei_asset_url() {
        val id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        assertThat(
            AppUpdateService.isTrustedReleaseAssetUrl(
                "http://efrei.app:50002/hub/admin/compass/releases/assets/$id",
            ),
        ).isFalse()
        assertThat(
            AppUpdateService.isTrustedReleaseAssetUrl(
                "https://efrei.app:50002/hub/admin/compass/releases/assets/$id?token=1",
            ),
        ).isFalse()
        assertThat(
            AppUpdateService.isTrustedReleaseAssetUrl(
                "https://efrei.app:8443/hub/admin/compass/releases/assets/$id",
            ),
        ).isFalse()
    }

    @Test fun evil_lookalike_host_and_wrong_repo_asset_url() {
        val id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        assertThat(
            AppUpdateService.isTrustedReleaseAssetUrl(
                "https://efrei.app.evil.example/hub/admin/compass/releases/assets/$id",
            ),
        ).isFalse()
        assertThat(
            AppUpdateService.isTrustedReleaseAssetUrl(
                "https://efrei.app:50002/hub/admin/other_repo/releases/assets/$id",
            ),
        ).isFalse()
        assertThat(
            AppUpdateService.isTrustedReleaseAssetUrl(
                "https://efrei.app:50002/hub/admin/compass/releases/assets/../$id",
            ),
        ).isFalse()
        assertThat(
            AppUpdateService.isTrustedReleaseAssetUrl(
                "https://user:pass@efrei.app:50002/hub/admin/compass/releases/assets/$id",
            ),
        ).isFalse()
        assertThat(
            AppUpdateService.isTrustedReleaseAssetUrl(
                "https://github.com/Pingasmaster/compass/releases/download/v1.0.0/a.apk",
            ),
        ).isFalse()
    }

    @Test fun `launchInstaller refuses when signing digests do not overlap`() {
        val updates = File(cacheDir, "updates").apply { mkdirs() }
        val apk = File(updates, "update.apk").apply { writeText("fake-apk") }
        every { context.packageManager.canRequestPackageInstalls() } returns true

        val svc = testService(
            installed = "0.1.0",
            requireSigning = true,
            installedDigests = setOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            apkDigests = setOf("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
        )
        val ex = runCatching { svc.launchInstaller() }.exceptionOrNull()
        assertThat(ex).isInstanceOf(IOException::class.java)
        checkNotNull(ex)
        assertThat(ex.message).contains("signing")
        assertThat(apk.exists()).isFalse()
    }

    @Test fun `launchInstaller refuses when installed signing digests are empty`() {
        File(File(cacheDir, "updates").apply { mkdirs() }, "update.apk").writeText("fake-apk")
        every { context.packageManager.canRequestPackageInstalls() } returns true

        val svc = testService(
            installed = "0.1.0",
            requireSigning = true,
            installedDigests = emptySet(),
            apkDigests = setOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
        )
        val ex = runCatching { svc.launchInstaller() }.exceptionOrNull()
        assertThat(ex).isInstanceOf(IOException::class.java)
        checkNotNull(ex)
        assertThat(ex.message).contains("cannot read installed")
    }

    @Test fun `launchInstaller refuses when apk signing digests are empty`() {
        val apk = File(File(cacheDir, "updates").apply { mkdirs() }, "update.apk").apply { writeText("fake-apk") }
        every { context.packageManager.canRequestPackageInstalls() } returns true

        val svc = testService(
            installed = "0.1.0",
            requireSigning = true,
            installedDigests = setOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
            apkDigests = emptySet(),
        )
        val ex = runCatching { svc.launchInstaller() }.exceptionOrNull()
        assertThat(ex).isInstanceOf(IOException::class.java)
        checkNotNull(ex)
        assertThat(ex.message).contains("cannot read downloaded")
        assertThat(apk.exists()).isTrue()
    }

    // --- helpers ------------------------------------------------------------

    private fun testService(
        installed: String,
        trustAllDownloadUrls: Boolean = false,
        requireDigest: Boolean = true,
        requireSigning: Boolean = false,
        installedDigests: Set<String>? = null,
        apkDigests: Set<String>? = null,
        deviceAbis: List<String> = emptyList(),
    ): AppUpdateService = object : AppUpdateService(client, context, testDispatcher) {
        override val releasesUrl: String = server.url("/releases").toString()
        override val installedVersion: String = installed
        override fun deviceAbis(): List<String> = deviceAbis

        // MockWebServer serves from http://localhost, which the production
        // allowlist rightly refuses; download tests opt in to trust it.
        override fun isTrustedDownloadUrl(url: String): Boolean = trustAllDownloadUrls || super.isTrustedDownloadUrl(url)

        override fun requireApkDigest(): Boolean = requireDigest

        // Download-path unit tests skip PackageManager signing by default;
        // signing-match tests opt in via [requireSigning] + digest fakes.
        override fun requireSigningMatch(): Boolean = requireSigning

        override fun installedSigningCertSha256Digests(): Set<String> = installedDigests ?: super.installedSigningCertSha256Digests()

        override fun apkSigningCertSha256Digests(apk: File): Set<String> = apkDigests ?: super.apkSigningCertSha256Digests(apk)
    }

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
        assetName: String? = AppUpdateService.selectedApkAsset(),
        digest: String? = null,
        downloadUrl: String? = null,
    ): String {
        val assetsJson = if (assetName == null) {
            "[]"
        } else {
            val digestJson = if (digest == null) "" else """, "digest": "$digest""""
            val url = downloadUrl ?: "https://releases.example/$tag/$assetName"
            """[{"name": "$assetName", "browser_download_url": "$url"$digestJson}]"""
        }
        return """
            {
              "tag_name": "$tag",
              "name": "$tag",
              "body": "notes",
              "prerelease": $prerelease,
              "draft": $draft,
              "assets": $assetsJson
            }
        """.trimIndent()
    }

    private fun releasesJson(vararg entries: String): String = entries.joinToString(prefix = "[", postfix = "]")
}
