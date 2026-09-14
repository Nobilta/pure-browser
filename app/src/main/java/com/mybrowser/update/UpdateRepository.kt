package com.mybrowser.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** User-initiated updates from the public repository, isolated from webpage downloads. */
class UpdateRepository internal constructor(
    context: Context,
    private val openConnection: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val app = context.applicationContext
    data class Release(val versionCode: Long, val versionName: String, val notes: String,
        val minSdk: Int, val abi: String, val name: String, val url: String, val size: Long, val sha256: String)
    enum class Problem { NETWORK, NO_RELEASE, RATE_LIMIT, INVALID, INCOMPATIBLE, FILE, VERIFICATION }
    class Failure(val problem: Problem) : IOException(problem.name)

    private val installed get() = app.packageManager.getPackageInfo(app.packageName, 0)
    val currentVersion: String get() = installed.versionName.orEmpty()
    val currentCode: Long get() = installed.longVersionCode
    val supported: Boolean get() = app.packageName == PACKAGE
    private val preferences = app.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val directory get() = File(app.cacheDir, "updates").apply { mkdirs() }

    suspend fun check(): Release? = withContext(Dispatchers.IO) { checkLock.withLock {
        if (!supported) throw Failure(Problem.INCOMPATIBLE)
        try {
            if (preferences.getLong("retry_after", 0) > now()) throw Failure(Problem.RATE_LIMIT)
            val age = now() - preferences.getLong("checked_at", 0)
            val cached = age in 0 until CACHE_MS
            val manifestText = if (cached) preferences.getString("manifest", null) else null
            // GitHub's latest-release asset redirect excludes drafts/prereleases without
            // spending the shared 60 requests/hour anonymous REST API budget.
            val manifest = JSONObject(manifestText ?: text(MANIFEST, 128 * 1024))
            val release = parse(manifest, Build.SUPPORTED_ABIS.toList(), Build.VERSION.SDK_INT)
            if (manifestText == null) {
                preferences.edit().putString("manifest", manifest.toString())
                    .putLong("checked_at", now()).remove("retry_after").apply()
            }
            if (release.versionCode <= currentCode) null else release
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Failure) { throw e }
        catch (e: org.json.JSONException) { throw Failure(Problem.INVALID) }
        catch (e: Exception) { throw Failure(Problem.NETWORK) }
    } }

    suspend fun download(release: Release, progress: suspend (Long) -> Unit): File = withContext(Dispatchers.IO) { downloadLock.withLock {
        val folder = directory
        val apk = File(folder, "${release.sha256}.apk")
        var part: File? = null
        try {
            if (!supported || release.versionCode <= currentCode) throw Failure(Problem.INCOMPATIBLE)
            if (!folder.isDirectory || folder.usableSpace < release.size * 2) throw Failure(Problem.FILE)
            // Keep a verified package immutable while the system installer may be reading it.
            // The lock also prevents a cancelled dialog's cleanup from touching a new transfer.
            folder.listFiles()?.filter { it.extension == "part" || now() - it.lastModified() > 24 * 60 * 60_000L }
                ?.filter { it != apk }?.forEach { it.delete() }
            if (apk.exists()) {
                try { verify(apk, release); progress(release.size); return@withLock apk }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { if (!apk.delete()) throw Failure(Problem.FILE) }
            }
            val temporary = File.createTempFile("download-", ".part", folder).also { part = it }
            request(release.url) { connection ->
                val reported = connection.contentLengthLong
                if (reported >= 0 && reported != release.size) throw Failure(Problem.VERIFICATION)
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                connection.inputStream.use { input -> temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastProgress = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > release.size) throw Failure(Problem.VERIFICATION)
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        if (written - lastProgress >= 128 * 1024 || written == release.size) {
                            progress(written); lastProgress = written
                        }
                    }
                    output.fd.sync()
                } }
                if (written != release.size || hex(digest.digest()) != release.sha256) throw Failure(Problem.VERIFICATION)
            }
            coroutineContext.ensureActive()
            verifyPackage(temporary, release)
            if (!temporary.renameTo(apk)) throw Failure(Problem.FILE)
            apk
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Failure) { throw e }
        catch (e: java.io.FileNotFoundException) { throw Failure(Problem.FILE) }
        catch (e: Exception) { throw Failure(Problem.NETWORK) }
        finally { part?.delete() }
    } }

    /** Recheck the exact internal file before sharing it with the system installer. */
    suspend fun verify(file: File, release: Release) = withContext(Dispatchers.IO) {
        if (file.canonicalFile != File(directory, "${release.sha256}.apk").canonicalFile || file.length() != release.size)
            throw Failure(Problem.VERIFICATION)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        if (hex(digest.digest()) != release.sha256) throw Failure(Problem.VERIFICATION)
        verifyPackage(file, release)
    }

    private fun verifyPackage(file: File, release: Release) {
        val pm = app.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw Failure(Problem.VERIFICATION)
        val current = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signatures = archive.signingInfo?.apkContentsSigners.orEmpty()
        val trusted = current.signingInfo?.apkContentsSigners.orEmpty()
        // Key rotation is deliberately not inferred from untrusted signing history.
        if (archive.packageName != PACKAGE || archive.longVersionCode != release.versionCode ||
            archive.longVersionCode <= current.longVersionCode || archive.versionName != release.versionName ||
            archive.applicationInfo?.minSdkVersion != release.minSdk ||
            signatures.size != 1 || trusted.size != 1 || signatures[0] != trusted[0]) throw Failure(Problem.VERIFICATION)
        java.util.zip.ZipFile(file).use { zip ->
            val abis = zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }
                .map { it.split('/')[1] }.toSet()
            if (release.abi !in abis || release.abi !in Build.SUPPORTED_ABIS) throw Failure(Problem.INCOMPATIBLE)
        }
    }

    private suspend fun text(url: String, limit: Int): String = request(url) { connection ->
        if (connection.contentLengthLong > limit) throw Failure(Problem.INVALID)
        connection.inputStream.use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > limit) throw Failure(Problem.INVALID)
                bytes.write(buffer, 0, count)
            }
            bytes.toString(Charsets.UTF_8.name())
        }
    }

    private suspend fun <T> request(url: String, read: suspend (HttpURLConnection) -> T): T {
        var current = url
        repeat(6) { hop ->
            coroutineContext.ensureActive()
            if (!safeUrl(current)) throw Failure(Problem.INVALID)
            val connection = openConnection(current)
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", "PureBrowser-Updater")
                connection.setRequestProperty("Accept-Encoding", "identity")
                when (connection.responseCode) {
                    200 -> return read(connection)
                    301, 302, 303, 307, 308 -> {
                        if (hop == 5) throw Failure(Problem.NETWORK)
                        current = URI(current).resolve(connection.getHeaderField("Location") ?: throw Failure(Problem.NETWORK)).toString()
                    }
                    404 -> throw Failure(Problem.NO_RELEASE)
                    403, 429 -> {
                        val seconds = connection.getHeaderField("Retry-After")?.toLongOrNull()
                        val reset = connection.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
                        val delay = (seconds?.coerceIn(60, 86_400)?.times(1000)
                            ?: reset?.let { it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 - now() }
                            ?: 15 * 60_000L).coerceIn(60_000L, 24 * 60 * 60_000L)
                        preferences.edit().putLong("retry_after", now() + delay).apply()
                        throw Failure(Problem.RATE_LIMIT)
                    }
                    else -> throw Failure(Problem.NETWORK)
                }
            } finally { connection.disconnect() }
        }
        throw Failure(Problem.NETWORK)
    }

    companion object {
        const val PACKAGE = "com.mybrowser"
        const val REPOSITORY = "https://github.com/Nobilta/pure-browser"
        const val RELEASES = "$REPOSITORY/releases"
        const val MANIFEST = "$RELEASES/latest/download/update.json"
        private const val CACHE_MS = 5 * 60_000L
        private val checkLock = Mutex()
        private val downloadLock = Mutex()
        private val hosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
        internal fun safeUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "https" && uri.host in hosts && uri.rawUserInfo == null &&
                uri.port in listOf(-1, 443) && uri.rawFragment == null && value.length <= 16_384
        }.getOrDefault(false)
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        private fun integer(json: JSONObject, key: String): Long = when (val value = json.get(key)) {
            is Int -> value.toLong()
            is Long -> value
            else -> throw Failure(Problem.INVALID)
        }

        internal fun parse(manifest: JSONObject, abis: List<String>, sdk: Int): Release {
            if (integer(manifest, "schemaVersion") != 1L || manifest.getString("packageName") != PACKAGE ||
                manifest.optString("channel") != "stable")
                throw Failure(Problem.INVALID)
            val version = integer(manifest, "versionCode")
            val versionName = manifest.getString("versionName")
            val minSdk = integer(manifest, "minSdk")
            if (version !in 1..2_100_000_000L || !versionName.matches(Regex("[0-9A-Za-z][0-9A-Za-z.+_-]{0,99}")) ||
                minSdk !in 1..Int.MAX_VALUE.toLong())
                throw Failure(Problem.INVALID)
            if (minSdk > sdk) throw Failure(Problem.INCOMPATIBLE)
            val artifacts = manifest.getJSONArray("artifacts")
            val candidates = (0 until artifacts.length()).map { artifacts.getJSONObject(it) }
            val artifact = abis.firstNotNullOfOrNull { abi -> candidates.singleOrNull { it.optString("abi") == abi } }
                ?: throw Failure(Problem.INCOMPATIBLE)
            val abi = artifact.getString("abi")
            val name = artifact.getString("assetName")
            val size = integer(artifact, "size")
            val hash = artifact.getString("sha256").lowercase()
            if (!name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,150}\\.apk")) || size !in 1..(128L * 1024 * 1024) ||
                !hash.matches(Regex("[a-f0-9]{64}"))) throw Failure(Problem.INVALID)
            // Never accept an arbitrary server URL from update metadata.
            val url = "$RELEASES/download/v$versionName/$name"
            return Release(version, versionName, manifest.optString("releaseNotes").take(12_000), minSdk.toInt(), abi, name, url, size, hash)
        }
    }
}
