package com.mybrowser.update

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import com.mybrowser.core.Hex
import com.mybrowser.update.UpdateRepository.Problem
import com.mybrowser.update.UpdateRepository.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateRepositoryTransferTest {
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getApplicationContext(): Context = this
        override fun getPackageName() = UpdateRepository.PACKAGE
    }
    private val preferences get() = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val folder get() = File(context.cacheDir, "updates")
    private val requests = mutableListOf<String>()
    private val responses = mutableMapOf<String, () -> HttpURLConnection>()
    private var clock = 1_000_000L
    private lateinit var archive: PackageInfo
    private lateinit var apk: ByteArray
    private val giteeManifest = Source.GITEE.asset("v2.0", "update.json")
    private val assetName = "PureBrowser-v2.0-release.apk"

    @Before fun reset() {
        preferences.edit().clear().commit()
        folder.deleteRecursively()
        requests.clear()
        responses.clear()
        clock = 1_000_000L
        ShadowBuild.setSupportedAbis(arrayOf("arm64-v8a"))
        shadowOf(context.packageManager).installPackage(packageInfo(1, "1.0"))
        archive = packageInfo(2, "2.0")
        apk = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.setLevel(0)
                zip.putNextEntry(ZipEntry("lib/arm64-v8a/libfixture.so").apply { time = 0 })
                zip.write(ByteArray(200_000) { (it % 251).toByte() })
                zip.closeEntry()
            }
        }.toByteArray()
        responses[UpdateRepository.GITEE_LATEST] = { Response(latest().toByteArray()) }
        responses[giteeManifest] = { Response(manifest().toString().toByteArray()) }
        responses[UpdateRepository.MANIFEST] = { Response(manifest().toString().toByteArray()) }
        for (source in Source.entries) responses[source.asset("v2.0", assetName)] = { Response(apk) }
    }

    private fun packageInfo(code: Long, version: String, certificate: String = "1234") = PackageInfo().apply {
        packageName = UpdateRepository.PACKAGE
        longVersionCode = code
        versionName = version
        applicationInfo = ApplicationInfo().apply {
            packageName = UpdateRepository.PACKAGE
            minSdkVersion = 29
        }
        signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature(certificate))) }
    }

    private fun latest(tag: String = "v2.0", prerelease: Boolean = false) = JSONObject()
        .put("tag_name", tag).put("prerelease", prerelease).toString()

    private fun manifest() = JSONObject()
        .put("schemaVersion", 1).put("channel", "stable").put("packageName", UpdateRepository.PACKAGE)
        .put("versionCode", 2).put("versionName", "2.0").put("minSdk", 29)
        .put("artifacts", JSONArray().put(JSONObject()
            .put("abi", "arm64-v8a").put("assetName", assetName).put("size", apk.size)
            .put("sha256", Hex.encode(MessageDigest.getInstance("SHA-256").digest(apk)))))

    private fun repository() = UpdateRepository(context, openConnection = { url ->
        requests += url
        folder.listFiles()?.filter { it.extension == "part" }?.forEach {
            shadowOf(context.packageManager).setPackageArchiveInfo(it.path, archive)
        }
        responses.getValue(url).invoke()
    }, now = { clock })

    private suspend fun fails(problem: Problem, action: suspend () -> Unit) {
        val error = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected $problem, got $error", error is UpdateRepository.Failure)
        assertEquals(problem, (error as UpdateRepository.Failure).problem)
    }

    @Test fun giteeIsPreferredAndItsCdnManifestAndSourceAreCached() = runBlocking {
        val cdn = "https://foruda.gitee.com/attach/update.json"
        responses[giteeManifest] = { Response(status = 302, headers = mapOf("Location" to cdn)) }
        responses[cdn] = { Response(manifest().toString().toByteArray()) }
        val release = repository().check()!!
        assertEquals(Source.GITEE, release.source)
        assertEquals(Source.GITEE.asset("v2.0", assetName), release.url)
        assertEquals(release, repository().check())
        assertEquals(listOf(UpdateRepository.GITEE_LATEST, giteeManifest, cdn), requests)
    }

    @Test fun unavailableGiteeFallsBackOnceAndKeepsGithubAsTheDownloadSource() = runBlocking {
        val unavailable = listOf<() -> HttpURLConnection>(
            { throw SocketTimeoutException("connect timeout") },
            { throw IOException("connection reset") },
            { Response(status = 404) }, { Response(status = 429) }, { Response(status = 503) },
            { Response(latest(prerelease = true).toByteArray()) },
        )
        for (response in unavailable) {
            reset()
            responses[UpdateRepository.GITEE_LATEST] = response
            val release = repository().check()!!
            assertEquals(Source.GITHUB, release.source)
            assertEquals(release, repository().check())
            assertEquals(listOf(UpdateRepository.GITEE_LATEST, UpdateRepository.MANIFEST), requests)
        }
    }

    @Test fun missingGiteeManifestAlsoFallsBack() = runBlocking {
        responses[giteeManifest] = { Response(status = 404) }
        assertEquals(Source.GITHUB, repository().check()!!.source)
        assertEquals(listOf(UpdateRepository.GITEE_LATEST, giteeManifest, UpdateRepository.MANIFEST), requests)
    }

    @Test fun oneSourcesRateLimitDoesNotBlockTheOtherAndExpires() = runBlocking {
        responses[UpdateRepository.GITEE_LATEST] = {
            Response(status = 429, headers = mapOf("Retry-After" to "900"))
        }
        assertEquals(Source.GITHUB, repository().check()!!.source)
        requests.clear()
        clock += 6 * 60_000
        assertEquals(Source.GITHUB, repository().check()!!.source)
        assertEquals(listOf(UpdateRepository.MANIFEST), requests)
        requests.clear()
        clock += 10 * 60_000
        responses[UpdateRepository.GITEE_LATEST] = { Response(latest().toByteArray()) }
        assertEquals(Source.GITEE, repository().check()!!.source)
        assertEquals(listOf(UpdateRepository.GITEE_LATEST, giteeManifest), requests)
    }

    @Test fun legacyCacheWithoutASourceIsRefreshedFromGitee() = runBlocking {
        preferences.edit().putLong("checked_at", clock).putString("manifest", manifest().toString())
            .putLong("retry_after", clock + 900_000).commit()
        assertEquals(Source.GITEE, repository().check()!!.source)
        assertEquals(listOf(UpdateRepository.GITEE_LATEST, giteeManifest), requests)
    }

    @Test fun invalidMetadataAndUnexpectedRedirectsDoNotSwitchSources() = runBlocking {
        val invalid = listOf<() -> HttpURLConnection>(
            { Response("not json".toByteArray()) },
            { Response(manifest().put("packageName", "another.app").toString().toByteArray()) },
            { Response(manifest().put("versionName", "3.0").toString().toByteArray()) },
            { Response(status = 302, headers = mapOf("Location" to "https://evil.example/update.json")) },
        )
        for (response in invalid) {
            reset()
            responses[giteeManifest] = response
            fails(Problem.INVALID) { repository().check() }
            assertEquals(listOf(UpdateRepository.GITEE_LATEST, giteeManifest), requests)
        }
        reset()
        responses[UpdateRepository.GITEE_LATEST] = { Response(latest("v../../other").toByteArray()) }
        fails(Problem.INVALID) { repository().check() }
        assertEquals(listOf(UpdateRepository.GITEE_LATEST), requests)
    }

    @Test fun bothUnavailableSourcesStopAfterOneAttemptEach() = runBlocking {
        responses[UpdateRepository.GITEE_LATEST] = { throw SocketTimeoutException() }
        responses[UpdateRepository.MANIFEST] = { Response(status = 503) }
        fails(Problem.NETWORK) { repository().check() }
        assertEquals(listOf(UpdateRepository.GITEE_LATEST, UpdateRepository.MANIFEST), requests)
    }

    @Test fun interruptedDownloadsRestartTheSameVersionOnTheOtherSource() = runBlocking {
        for (preferred in Source.entries) {
            reset()
            val release = UpdateRepository.parse(manifest(), listOf("arm64-v8a"), 34, preferred)
            responses[release.url] = { Response(apk, stream = ByteArrayInputStream(apk.copyOf(150_000))) }
            val progress = mutableListOf<Long>()
            val file = repository().download(release) { progress += it }
            assertArrayEquals(apk, file.readBytes())
            val other = Source.entries.single { it != preferred }
            assertEquals(listOf(release.url, other.asset("v2.0", assetName)), requests)
            assertEquals(2, progress.count { it == 0L })
            assertTrue(progress[1] > 0)
            assertEquals(apk.size.toLong(), progress.last())
            assertEquals(listOf(file), folder.listFiles()!!.toList())
            shadowOf(context.packageManager).setPackageArchiveInfo(file.path, archive)
            repository().verify(file, release)
        }
    }

    @Test fun downloadRateLimitFallsBackAndAVerifiedFileNeedsNoNetwork() = runBlocking {
        val release = repository().check()!!
        requests.clear()
        responses[release.url] = { Response(status = 429) }
        val file = repository().download(release) {}
        assertEquals(listOf(release.url, Source.GITHUB.asset("v2.0", assetName)), requests)
        shadowOf(context.packageManager).setPackageArchiveInfo(file.path, archive)
        requests.clear()
        assertEquals(file, repository().download(release) {})
        assertTrue(requests.isEmpty())
    }

    @Test fun hashAndSignatureFailuresDoNotTryTheOtherSource() = runBlocking {
        for (badSignature in listOf(false, true)) {
            reset()
            val release = repository().check()!!
            requests.clear()
            if (badSignature) archive = packageInfo(2, "2.0", "5678")
            else responses[release.url] = { Response(apk.copyOf().apply { this[lastIndex] = (last() + 1).toByte() }) }
            fails(Problem.VERIFICATION) { repository().download(release) {} }
            assertEquals(listOf(release.url), requests)
            assertTrue(folder.listFiles()!!.isEmpty())
        }
    }

    @Test fun cancellationAndLocalFileFailureDoNotSwitchSources() = runBlocking {
        val release = repository().check()!!
        requests.clear()
        responses[release.url] = { Response(apk, stream = object : InputStream() {
            override fun read(): Int = throw CancellationException("cancelled")
        }) }
        assertTrue(runCatching { repository().download(release) {} }.exceptionOrNull() is CancellationException)
        assertEquals(listOf(release.url), requests)
        assertTrue(folder.listFiles()!!.isEmpty())
        requests.clear()
        fails(Problem.FILE) {
            repository().download(release) {
                val part = folder.listFiles()!!.single()
                assertTrue(part.delete())
                assertTrue(part.mkdir()) // The output cannot be opened as a file.
            }
        }
        assertTrue(requests.isEmpty())
    }

    private class Response(
        private val body: ByteArray = byteArrayOf(),
        private val status: Int = 200,
        private val headers: Map<String, String> = emptyMap(),
        private val stream: InputStream = ByteArrayInputStream(body),
    ) : HttpURLConnection(URL("https://fixture.invalid")) {
        override fun getResponseCode() = status
        override fun getContentLengthLong() = body.size.toLong()
        override fun getHeaderField(name: String) = headers[name]
        override fun getInputStream() = stream
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
    }
}
