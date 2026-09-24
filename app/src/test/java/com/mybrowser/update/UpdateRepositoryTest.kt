package com.mybrowser.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The update path is the one place the app installs code, and it is also the part the device
 * regression cannot reach — `TESTING_GUIDE` records that the emulator stage does not download
 * or install a release. These cover the two pure functions that decide whether a manifest is
 * even considered, so a regression in the allow-list or the signer comparison is caught here
 * rather than by shipping a bad update.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateRepositoryTest {

    private fun manifest(
        versionCode: Long = 27,
        versionName: String = "0.13",
        minSdk: Long = 29,
        abi: String = "arm64-v8a",
        assetName: String = "PureBrowser-v0.13-release.apk",
        size: Long = 4_900_000,
        sha256: String = "a".repeat(64),
        packageName: String = UpdateRepository.PACKAGE,
        channel: String = "stable",
        schemaVersion: Long = 1,
    ) = JSONObject()
        .put("schemaVersion", schemaVersion)
        .put("packageName", packageName)
        .put("channel", channel)
        .put("versionCode", versionCode)
        .put("versionName", versionName)
        .put("minSdk", minSdk)
        .put(
            "artifacts",
            org.json.JSONArray().put(
                JSONObject().put("abi", abi).put("assetName", assetName)
                    .put("size", size).put("sha256", sha256),
            ),
        )

    @Test
    fun acceptedManifestProducesThePinnedDownloadUrl() {
        val release = UpdateRepository.parse(manifest(), listOf("arm64-v8a"), sdk = 34)
        assertEquals(27L, release.versionCode)
        assertEquals("0.13", release.versionName)
        assertEquals("arm64-v8a", release.abi)
        // The asset URL is rebuilt from the release name, never taken from the manifest.
        assertEquals(
            "${UpdateRepository.RELEASES}/download/v0.13/PureBrowser-v0.13-release.apk",
            release.url,
        )
    }

    @Test
    fun assetUrlInTheManifestIsIgnoredEvenWhenItPointsSomewhereElse() {
        val hostile = manifest().put(
            "artifacts",
            org.json.JSONArray().put(
                JSONObject().put("abi", "arm64-v8a")
                    .put("assetName", "PureBrowser-v0.13-release.apk")
                    .put("size", 4_900_000).put("sha256", "a".repeat(64))
                    .put("url", "https://evil.example/PureBrowser.apk"),
            ),
        )
        assertEquals(
            "${UpdateRepository.RELEASES}/download/v0.13/PureBrowser-v0.13-release.apk",
            UpdateRepository.parse(hostile, listOf("arm64-v8a"), sdk = 34).url,
        )
    }

    @Test
    fun malformedManifestsAreRejectedRatherThanGuessedAt() {
        val cases = mapOf(
            "wrong package" to manifest(packageName = "com.example.browser"),
            "wrong channel" to manifest(channel = "beta"),
            "unknown schema" to manifest(schemaVersion = 2),
            "version code out of range" to manifest(versionCode = 0),
            "version name with a path character" to manifest(versionName = "../../etc"),
            "version name with a slash" to manifest(versionName = "0.13/../x"),
            "oversized version name" to manifest(versionName = "0".repeat(101)),
            "zero size" to manifest(size = 0),
            "oversized archive" to manifest(size = 129L * 1024 * 1024),
            "short hash" to manifest(sha256 = "abc"),
            "non-hex hash" to manifest(sha256 = "z".repeat(64)),
            "asset name without an apk extension" to manifest(assetName = "PureBrowser-v0.13-release.zip"),
            "asset name starting with a separator" to manifest(assetName = "/PureBrowser.apk"),
        )
        for ((label, value) in cases) {
            val problem = runCatching { UpdateRepository.parse(value, listOf("arm64-v8a"), sdk = 34) }
                .exceptionOrNull()
            assertTrue("$label must be rejected", problem is UpdateRepository.Failure)
            assertEquals("$label", UpdateRepository.Problem.INVALID, (problem as UpdateRepository.Failure).problem)
        }
    }

    @Test
    fun aReleaseForAnotherDeviceOrANewerPlatformIsIncompatibleNotInvalid() {
        val wrongAbi = runCatching { UpdateRepository.parse(manifest(abi = "x86_64"), listOf("arm64-v8a"), sdk = 34) }
            .exceptionOrNull() as UpdateRepository.Failure
        assertEquals(UpdateRepository.Problem.INCOMPATIBLE, wrongAbi.problem)

        val tooNew = runCatching { UpdateRepository.parse(manifest(minSdk = 35), listOf("arm64-v8a"), sdk = 34) }
            .exceptionOrNull() as UpdateRepository.Failure
        assertEquals(UpdateRepository.Problem.INCOMPATIBLE, tooNew.problem)
    }

    @Test
    fun onlyTheAppsOwnHttpsHostsAreSafeToFetch() {
        val safe = listOf(
            "https://github.com/Nobilta/pure-browser/releases/download/v0.13/a.apk",
            "https://release-assets.githubusercontent.com/github-production-release-asset/1/2",
            "https://objects.githubusercontent.com/x",
        )
        for (value in safe) assertTrue(value, UpdateRepository.safeUrl(value))

        val unsafe = listOf(
            "http://github.com/Nobilta/pure-browser/releases/download/v0.13/a.apk",
            "https://evil.example/a.apk",
            // A host that merely ends with an allowed name must not pass.
            "https://github.com.evil.example/a.apk",
            // Credentials in the authority are a classic way to disguise the real host.
            "https://github.com@evil.example/a.apk",
            "https://user:pass@github.com/a.apk",
            "https://github.com:8443/a.apk",
            "https://github.com/a.apk#fragment",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "https://" + "a".repeat(20_000) + ".github.com/",
        )
        for (value in unsafe) assertFalse(value, UpdateRepository.safeUrl(value))
    }
}
