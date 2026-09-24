package com.mybrowser.download

import android.app.Application
import android.content.Context
import android.webkit.CookieManager
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadHandlerTest {
    /** Robolectric starts with no usable network; a transfer without one waits instead of failing. */
    private fun connectedContext(): Context {
        val context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        val shadow = org.robolectric.Shadows.shadowOf(connectivity)
        shadow.setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, true))
        shadow.setNetworkCapabilities(connectivity.activeNetwork,
            org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also {
                org.robolectric.Shadows.shadowOf(it).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            })
        return context
    }

    @Test fun repeatedPauseResumeKeepsTheSameTaskAndCancelCleansUpAfterWritersFinish() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val total = 4 * 1024 * 1024
        server.createContext("/file") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            val (first, last) = range?.removePrefix("bytes=")?.split('-')?.map(String::toInt)
                ?: listOf(0, total - 1)
            exchange.responseHeaders.add("ETag", "\"handler-stable\"")
            if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $first-$last/$total")
            exchange.sendResponseHeaders(if (range == null) 200 else 206, (last - first + 1).toLong())
            runCatching { exchange.responseBody.use { output ->
                var remaining = last - first + 1
                val block = ByteArray(16384) { 3 }
                while (remaining > 0) {
                    val count = minOf(remaining, block.size)
                    output.write(block, 0, count); output.flush(); remaining -= count
                    Thread.sleep(5)
                }
            } }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        server.start()
        val context = RuntimeEnvironment.getApplication()
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        val connectivityShadow = org.robolectric.Shadows.shadowOf(connectivity)
        connectivityShadow.setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, true))
        connectivityShadow.setNetworkCapabilities(connectivity.activeNetwork,
            org.robolectric.shadows.ShadowNetworkCapabilities.newInstance().also {
                org.robolectric.Shadows.shadowOf(it).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            })
        val handler = DownloadHandler(context)
        try {
            val id = requireNotNull(handler.enqueue("http://127.0.0.1:${server.address.port}/file", null, null, null))
            withTimeout(10_000) { handler.downloads.first { list -> list.any { it.id == id && it.bytesDownloaded > 0 } } }
            repeat(10) {
                handler.pause(id)
                assertEquals(DownloadStatus.PAUSED, handler.downloads.value.single { it.id == id }.status)
                assertEquals(id, handler.retry(id))
            }
            handler.pause(id)
            delay(300)
            assertEquals(DownloadStatus.PAUSED, handler.downloads.value.single { it.id == id }.status)
            val directory = File(context.noBackupFilesDir, "browser-downloads/$id")
            assertTrue(directory.exists())
            handler.cancel(id)
            withTimeout(5_000) { while (directory.exists()) delay(20) }
            assertFalse(handler.downloads.value.any { it.id == id })
        } finally { handler.close(); server.stop(0); (server.executor as java.util.concurrent.ExecutorService).shutdownNow() }
    }

    @Test fun privateDownloadsKeepTheRecordButNotTheSourceUrlOrRefererOnDisk() {
        val context = RuntimeEnvironment.getApplication()
        val data = """[{"version":1,"id":-21,"backend":"LOCAL",""" +
            """"url":"https://private.example/file?token=secret-value&user=42",""" +
            """"filename":"private.bin","referer":"https://private.example/inbox?thread=99",""" +
            """"status":"DOWNLOADING","autoResumeAllowed":false,"privateScope":"private-1",""" +
            """"bytesDownloaded":123}]"""
        context.getSharedPreferences("downloads", Context.MODE_PRIVATE).edit()
            .putString("entries", data).commit()
        val handler = DownloadHandler(context)
        try {
            // Restoring an interrupted record rewrites the snapshot, which is the moment the
            // record would otherwise carry the private session's URL onto disk.
            handler.resumeInterrupted()
            val saved = JSONArray(
                context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
                    .getString("entries", "[]"),
            ).getJSONObject(0)
            assertEquals("https://private.example/file", saved.getString("url"))
            assertEquals("", saved.getString("referer"))
            // The record itself survives, which is what the feature documentation promises.
            assertEquals("private.bin", saved.getString("filename"))
            assertEquals(DownloadStatus.PAUSED, handler.downloads.value.single().status)
        } finally {
            handler.close()
        }
    }

    /**
     * The whole resume boundary, from the manager's side: a private task is resumable only inside
     * the session that created it, while an ordinary task stays resumable anywhere. A normal task
     * may keep using the cookie jar because that is the identity it was started under.
     */
    @Test fun onlyTheSessionThatCreatedAPrivateTaskCanResumeIt() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // A failing endpoint puts the task into the retryable state without a long transfer.
        server.createContext("/gone") { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.responseBody.close()
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        server.start()
        val context = connectedContext()
        val handler = DownloadHandler(context)
        try {
            val url = "http://127.0.0.1:${server.address.port}/gone"
            handler.rotatePrivateScope()
            val privateId = requireNotNull(handler.enqueue(url, null, null, null, isPrivate = true))
            withTimeout(10_000) {
                handler.downloads.first { list -> list.any { it.id == privateId && it.status == DownloadStatus.FAILED } }
            }
            assertEquals("its own session resumes it", privateId, handler.retry(privateId))

            val normalId = requireNotNull(handler.enqueue("$url?normal", null, null, null))
            withTimeout(10_000) {
                handler.downloads.first { list -> list.any { it.id == normalId && it.status == DownloadStatus.FAILED } }
            }

            // The next private session did not create it...
            handler.endPrivateScope()
            handler.rotatePrivateScope()
            assertNull(handler.retry(privateId))
            // ...ordinary browsing did not either...
            handler.endPrivateScope()
            assertNull(handler.retry(privateId))
            // ...and the ordinary task is unaffected by either boundary.
            assertEquals(normalId, handler.retry(normalId))
        } finally {
            handler.close(); server.stop(0)
            (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
        }
    }

    /**
     * The reported leak, end to end: a private download's URL must not survive anywhere on disk.
     * The record JSON was redacted, but the resume sidecar beside the partial bytes still named it,
     * so pausing a private task and leaving the session left the query string behind — and nothing
     * removed it, because the directory is kept for as long as the record says "paused".
     */
    @Test fun aPausedPrivateDownloadLeavesNoUrlInItsPartialFiles() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val total = 4 * 1024 * 1024
        // Ranges and a validator, so the transfer takes the segmented path — the one that writes a
        // sidecar before moving bytes. A plain 200 without a validator writes none at all.
        server.createContext("/big") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            val (first, last) = range?.removePrefix("bytes=")?.split('-')?.map(String::toInt)
                ?: listOf(0, total - 1)
            exchange.responseHeaders.add("ETag", "\"sidecar-stable\"")
            if (range != null) exchange.responseHeaders.add("Content-Range", "bytes $first-$last/$total")
            exchange.sendResponseHeaders(if (range == null) 200 else 206, (last - first + 1).toLong())
            runCatching { exchange.responseBody.use { output ->
                var remaining = last - first + 1
                val block = ByteArray(16384) { 5 }
                while (remaining > 0) {
                    val count = minOf(remaining, block.size)
                    output.write(block, 0, count); output.flush(); remaining -= count
                    Thread.sleep(5)
                }
            } }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        server.start()
        val context = connectedContext()
        val handler = DownloadHandler(context)
        try {
            val url = "http://127.0.0.1:${server.address.port}/big?token=secret-value&user=42"
            handler.rotatePrivateScope()
            val id = requireNotNull(handler.enqueue(url, null, null, null, isPrivate = true))
            withTimeout(10_000) { handler.downloads.first { list -> list.any { it.id == id && it.bytesDownloaded > 0 } } }
            handler.pause(id)
            assertEquals(DownloadStatus.PAUSED, handler.downloads.value.single { it.id == id }.status)

            val directory = File(context.noBackupFilesDir, "browser-downloads/$id")
            val sidecar = File(directory, "resume.properties")
            assertTrue("the paused task must have written a sidecar", sidecar.isFile)
            val text = sidecar.readText()
            assertFalse("the sidecar must not name the URL: $text", text.contains("token"))
            assertFalse(text.contains("secret-value"))
            assertFalse(text.contains("127.0.0.1"))
        } finally {
            handler.close(); server.stop(0)
            (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
        }
    }

    /**
     * A private task from another session can never be resumed, so its partial files — and the
     * sidecar older builds wrote with the URL in it — are removed when the process starts rather
     * than kept for a resume that cannot happen.
     */
    @Test fun startupDropsPartialFilesOfAPrivateTaskItCannotResume() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val records = org.json.JSONArray()
            .put(
                org.json.JSONObject().put("id", -80L).put("backend", "LOCAL")
                    .put("url", "https://private.example/kept").put("filename", "kept.bin")
                    .put("status", "PAUSED").put("identity", "private:previous-session"),
            )
            .put(
                org.json.JSONObject().put("id", -81L).put("backend", "LOCAL")
                    .put("url", "https://normal.example/download").put("filename", "normal.bin")
                    .put("status", "PAUSED").put("identity", "normal"),
            )
        context.getSharedPreferences("downloads", Context.MODE_PRIVATE).edit()
            .putString("entries", records.toString()).commit()
        // The shape older builds left: a sidecar naming the URL, beside its partial bytes.
        val stale = File(context.noBackupFilesDir, "browser-downloads/-80").also { it.mkdirs() }
        File(stale, "resume.properties").writeText(
            "version=1\nurl=https://private.example/kept?token=secret-value\ntotal=4096\nvalidator=\"v\"\nthreads=1\n" +
                "entityUrl=https://private.example/kept?token=secret-value\n",
        )
        val resumable = File(context.noBackupFilesDir, "browser-downloads/-81").also { it.mkdirs() }

        val handler = DownloadHandler(context)
        try {
            // The cleanup runs off the init path, so wait for it rather than assuming it landed.
            withTimeout(10_000) { while (stale.exists()) delay(20) }
            assertTrue("a resumable task keeps its partial files", resumable.exists())
        } finally {
            handler.close()
        }
    }

    /**
     * A private task must run as its own session's identity: the cookie captured in memory, never
     * the ordinary profile's jar. Nothing asserted this, so the branch could have been replaced by
     * a jar read and the whole suite stayed green.
     */
    @Test fun aPrivateRetryUsesTheSessionCookieAndNeverTheJar() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.createContext("/private-cookie") { exchange ->
            seen.add(exchange.requestHeaders.getFirst("Cookie").orEmpty())
            exchange.sendResponseHeaders(500, -1)
            exchange.responseBody.close()
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        server.start()
        val context = connectedContext()
        val handler = DownloadHandler(context)
        try {
            val url = "http://127.0.0.1:${server.address.port}/private-cookie"
            handler.rotatePrivateScope()
            val id = requireNotNull(
                handler.enqueueOrGetExisting(url, null, null, null, isPrivate = true, cookieHeader = "private=1")
                    .let { (it as? DownloadHandler.EnqueueOutcome.Started)?.id },
            )
            withTimeout(10_000) {
                handler.downloads.first { list -> list.any { it.id == id && it.status == DownloadStatus.FAILED } }
            }
            // Whatever the ordinary profile holds now must not reach this transfer.
            CookieManager.getInstance().setCookie(url, "ordinary=2")
            seen.clear()
            assertEquals(id, handler.retry(id))
            withTimeout(10_000) { while (seen.isEmpty()) delay(20) }
            assertEquals(listOf("private=1"), seen.distinct())
        } finally {
            handler.close(); server.stop(0)
            (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
            CookieManager.getInstance().removeAllCookies(null)
        }
    }

    /**
     * A private completion is not announced. The gate is one line, so it needed a test that fails
     * when it goes: the running-notification title is pinned elsewhere, this is the posted one.
     */
    @Test fun aPrivateCompletionIsNotAnnounced() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val payload = ByteArray(2_048) { 7 }
        server.createContext("/tiny") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool { task -> Thread(task).apply { isDaemon = true } }
        server.start()
        val context = connectedContext()
        val notifications = org.robolectric.Shadows.shadowOf(
            context.getSystemService(android.app.NotificationManager::class.java),
        )
        notifications.setNotificationsEnabled(true)
        val handler = DownloadHandler(context)
        try {
            val url = "http://127.0.0.1:${server.address.port}/tiny"
            // Ordinary download: announced, which is also the control for the assertion below.
            val normalId = requireNotNull(handler.enqueue("$url?normal", null, null, null))
            withTimeout(10_000) {
                handler.downloads.first { list -> list.any { it.id == normalId && it.status == DownloadStatus.COMPLETED } }
            }
            withTimeout(5_000) { while (notifications.activeNotifications.isEmpty()) delay(20) }
            val afterNormal = notifications.activeNotifications.size

            handler.rotatePrivateScope()
            val privateId = requireNotNull(handler.enqueue("$url?private", null, null, null, isPrivate = true))
            withTimeout(10_000) {
                handler.downloads.first { list -> list.any { it.id == privateId && it.status == DownloadStatus.COMPLETED } }
            }
            // It is announced after the snapshot is published, so a bare assertion would race.
            delay(500)
            assertEquals("a private completion must not be posted", afterNormal, notifications.activeNotifications.size)
        } finally {
            handler.close(); server.stop(0)
            (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
        }
    }

    /**
     * The persisted format after the identity replaced the flag pair: one field either way, and the
     * legacy names gone. Records seeded in the old shape are rewritten once, which is also what
     * applies the identity's redaction rule to a private URL written by an older build.
     */
    @Test fun persistedRecordsCarryTheIdentityAndDropTheLegacyPair() {
        val context = RuntimeEnvironment.getApplication()
        // Built with JSONObject rather than concatenated text: these fixtures assert on what the
        // reader makes of each shape, so a quoting slip in the fixture itself would be read as a
        // malformed record and silently delete the whole set.
        val records = org.json.JSONArray()
            .put(legacyRecord(-50, "https://normal.example/a?t=1", "a.bin", "COMPLETED", true, null)
                .put("referer", "https://normal.example/from"))
            .put(legacyRecord(-51, "https://private.example/b?token=2", "b.bin", "PAUSED", false, "private-77"))
            .put(legacyRecord(-52, "https://private.example/c#access_token=3", "c.bin", "PAUSED", false, "private-77"))
        context.getSharedPreferences("downloads", Context.MODE_PRIVATE).edit()
            .putString("entries", records.toString()).commit()
        val handler = DownloadHandler(context)
        try {
            val saved = JSONArray(
                context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
                    .getString("entries", "[]"),
            )
            // By name, not by index: the snapshot is ordered by timestamp and these were restored
            // in the same millisecond, so their relative order is not defined.
            val byName = (0 until saved.length()).associate {
                saved.getJSONObject(it).getString("filename") to saved.getJSONObject(it)
            }
            val normal = byName.getValue("a.bin")
            val private = byName.getValue("b.bin")

            assertEquals("normal", normal.getString("identity"))
            assertEquals("private:private-77", private.getString("identity"))
            for (index in 0 until saved.length()) {
                val record = saved.getJSONObject(index)
                assertFalse("the legacy pair must no longer be written", record.has("autoResumeAllowed"))
                assertFalse(record.has("privateScope"))
            }

            // The identity decides redaction: an ordinary record keeps its source URL and referer,
            // a private one keeps neither. The fragment case is here on purpose: a fragment can
            // carry a token the way a query can, and a fixture with a query only would not notice
            // the second cut being dropped.
            assertEquals("https://normal.example/a?t=1", normal.getString("url"))
            assertEquals("https://normal.example/from", normal.getString("referer"))
            assertEquals("https://private.example/b", private.getString("url"))
            assertEquals("", private.getString("referer"))
            assertEquals("https://private.example/c", byName.getValue("c.bin").getString("url"))
        } finally {
            handler.close()
        }
    }

    /** A record in the current shape. */
    private fun identityRecord(id: Long, url: String, filename: String, status: String, identity: String) =
        org.json.JSONObject()
            .put("id", id)
            .put("backend", "LOCAL")
            .put("url", url)
            .put("filename", filename)
            .put("status", status)
            .put("identity", identity)

    /** A record in the shape written before the identity field existed. */
    private fun legacyRecord(
        id: Long,
        url: String,
        filename: String,
        status: String,
        autoResumeAllowed: Boolean,
        privateScope: String?,
    ) = org.json.JSONObject()
        .put("id", id)
        .put("backend", "LOCAL")
        .put("url", url)
        .put("filename", filename)
        .put("status", status)
        .put("autoResumeAllowed", autoResumeAllowed)
        .apply { if (privateScope != null) put("privateScope", privateScope) }

    /**
     * A field that is present but unreadable is not the same as an absent one. Falling back to the
     * legacy pair would read a private record as an ordinary download — and then resume it against
     * the cookie jar, which is the boundary the identity exists to hold.
     */
    @Test fun aRecordWithAnUnreadableIdentityIsDroppedRatherThanReadAsOrdinary() {
        val context = RuntimeEnvironment.getApplication()
        val oversized = "s".repeat(DownloadIdentity.MAX_SCOPE_LENGTH + 1)
        val records = org.json.JSONArray()
            .put(identityRecord(-70, "https://private.example/c", "c.bin", "PAUSED", "private:$oversized"))
            .put(identityRecord(-71, "https://private.example/d", "d.bin", "PAUSED", "PRIVATE:7-1"))
            .put(identityRecord(-72, "https://normal.example/e", "e.bin", "COMPLETED", "normal"))
        context.getSharedPreferences("downloads", Context.MODE_PRIVATE).edit()
            .putString("entries", records.toString()).commit()
        val handler = DownloadHandler(context)
        try {
            // The unreadable pair is gone; the readable record beside them is untouched.
            assertEquals(listOf("e.bin"), handler.downloads.value.map { it.filename })
            assertNull(handler.retry(-70))
            assertNull(handler.retry(-71))
        } finally {
            handler.close()
        }
    }

    /**
     * What the transfer service reads to decide the running notification's title. The identity
     * answers whether a task may be named at all; the item carries that conclusion so the service
     * never compares identities itself.
     */
    @Test fun onlyAnOrdinaryRecordIsProjectedAsNameableInNotifications() {
        val context = RuntimeEnvironment.getApplication()
        val records = org.json.JSONArray()
            .put(identityRecord(-60, "https://normal.example/a", "a.bin", "COMPLETED", "normal"))
            .put(identityRecord(-61, "https://private.example/b", "b.bin", "COMPLETED", "private:private-88"))
        context.getSharedPreferences("downloads", Context.MODE_PRIVATE).edit()
            .putString("entries", records.toString()).commit()
        val handler = DownloadHandler(context)
        try {
            val items = handler.downloads.value.associateBy { it.filename }
            assertEquals(2, items.size)
            assertTrue(items.getValue("a.bin").showsFilenameInNotification)
            assertFalse(items.getValue("b.bin").showsFilenameInNotification)
            // The same projection tells the list what it may offer: no private session is running
            // here, so the record belongs to a session that is gone and cannot be resumed.
            assertTrue(items.getValue("a.bin").canResume)
            assertFalse(items.getValue("b.bin").canResume)
        } finally {
            handler.close()
        }
    }

    /**
     * Resumability follows the running session, and nothing about the task changes when the session
     * does — so the projection has to be rebuilt on the session boundary rather than only when a
     * download is touched. Without that rebuild the list kept offering "continue" for a task the
     * manager then refused.
     */
    /**
     * A launch that opens straight into private mode has no session start to mint a scope: the
     * first private request does, and every later request in that session has to join it. The
     * minting is a read-decide-write on the session identity, so this pins the contract the lock
     * around it protects — one session, one scope, whether or not anything announced the session.
     */
    @Test fun aPrivateSessionThatWasNeverStartedStillOwnsEveryRequestItMakes() {
        val context = RuntimeEnvironment.getApplication()
        val handler = DownloadHandler(context)
        try {
            // No rotatePrivateScope(): the first request mints the session.
            val first = requireNotNull(handler.enqueue("https://private.example/a", null, null, null, isPrivate = true))
            requireNotNull(handler.enqueue("https://private.example/b", null, null, null, isPrivate = true))
            // Both tasks belong to the session, so both are resumable inside it...
            assertTrue(handler.downloads.value.first { it.id == first }.canResume)
            // ...and the same URL asked again coalesces instead of starting a parallel copy, which
            // is only possible while every request resolves to the same identity.
            val again = handler.enqueueOrGetExisting("https://private.example/a", null, null, null, isPrivate = true)
            assertTrue(again is DownloadHandler.EnqueueOutcome.Existing)
        } finally {
            handler.close()
        }
    }

    @Test fun resumabilityFollowsTheRunningPrivateSession() {
        val context = RuntimeEnvironment.getApplication()
        val handler = DownloadHandler(context)
        try {
            handler.rotatePrivateScope()
            val id = requireNotNull(
                handler.enqueue("https://private.example/c", null, null, null, isPrivate = true),
            )
            assertTrue(
                "the session that created the task resumes it",
                handler.downloads.value.single { it.id == id }.canResume,
            )
            handler.endPrivateScope()
            assertFalse(
                "the task is not resumable once its session has ended",
                handler.downloads.value.single { it.id == id }.canResume,
            )
            // Ordinary tasks are unaffected by either boundary.
            val normal = requireNotNull(handler.enqueue("https://normal.example/d", null, null, null))
            assertTrue(handler.downloads.value.single { it.id == normal }.canResume)
        } finally {
            handler.close()
        }
    }

    @Test fun aLegacyPrivateRecordIsSanitisedOnRestoreAndStaysUnresumable() {
        val context = RuntimeEnvironment.getApplication()
        // The shape written before the scope field existed: private, but with no privateScope.
        // Its scope therefore reads back as null, which is also what normal browsing has.
        val data = """[{"id":-40,"backend":"LOCAL","url":"https://private.example/f?token=secret&id=7",""" +
            """"filename":"f.bin","status":"PAUSED","autoResumeAllowed":false}]"""
        context.getSharedPreferences("downloads", Context.MODE_PRIVATE).edit()
            .putString("entries", data).commit()
        val handler = DownloadHandler(context)
        try {
            // Restoring rewrites the snapshot in the current format, which is what applies the
            // current URL rule: without that, the token would sit in preferences until some
            // other write happened to occur, and a finished record reaches no other write.
            val saved = JSONArray(
                context.getSharedPreferences("downloads", Context.MODE_PRIVATE)
                    .getString("entries", "[]"),
            ).getJSONObject(0)
            assertEquals("https://private.example/f", saved.getString("url"))
            assertEquals(1, saved.getInt("version"))
            // Normal browsing is not the session that created it, so it cannot be resumed —
            // the URL it would fetch is no longer the one the transfer started from.
            assertNull(handler.retry(-40))
        } finally {
            handler.close()
        }
    }

    @Test fun aRecordFromANewerSchemaIsDroppedRatherThanReinterpreted() {
        val context = RuntimeEnvironment.getApplication()
        val records = org.json.JSONArray()
            .put(
                org.json.JSONObject().put("version", 99).put("id", -30L)
                    .put("backend", "SOME_FUTURE_BACKEND").put("url", "https://example.org/a")
                    .put("filename", "a.bin").put("status", "COMPLETED"),
            )
            .put(
                org.json.JSONObject().put("version", 1).put("id", -31L)
                    .put("backend", "LOCAL").put("url", "https://example.org/b")
                    .put("filename", "b.bin").put("status", "UNKNOWN_STATUS"),
            )
            // Valid in every other respect, so only the version gate can be what drops it: beside a
            // bogus backend the enum check would take the blame instead.
            .put(identityRecord(-32, "https://example.org/c", "c.bin", "COMPLETED", "normal").put("version", 2))
        context.getSharedPreferences("downloads", Context.MODE_PRIVATE).edit()
            .putString("entries", records.toString()).commit()
        val handler = DownloadHandler(context)
        try {
            // None of them may surface as a download this build thinks it can act on.
            assertTrue(handler.downloads.value.isEmpty())
        } finally {
            handler.close()
        }
    }

    @Test fun startupMarksInterruptedTasksPausedButDoesNotAutomaticallyResumePrivateTasks() {
        val context = RuntimeEnvironment.getApplication()
        val data = """[{"id":-15,"backend":"LOCAL","url":"https://private.example/file","filename":"private.bin","status":"DOWNLOADING","autoResumeAllowed":false,"bytesDownloaded":123}]"""
        context.getSharedPreferences("downloads", Context.MODE_PRIVATE).edit().putString("entries", data).commit()
        val handler = DownloadHandler(context)
        try {
            assertEquals(DownloadStatus.PAUSED, handler.downloads.value.single().status)
            handler.resumeInterrupted()
            assertEquals(DownloadStatus.PAUSED, handler.downloads.value.single().status)
            val saved = JSONArray(context.getSharedPreferences("downloads", Context.MODE_PRIVATE).getString("entries", "[]"))
            assertFalse(saved.getJSONObject(0).has("cookie"))
        } finally { handler.close() }
    }
}
