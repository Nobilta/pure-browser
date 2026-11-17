package com.mybrowser.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files

/**
 * FTP transfer against a server that speaks the few commands the client uses.
 *
 * The server is scripted rather than real so the test can assert what the client asked for —
 * passive mode, binary type, the REST offset a pause left behind — instead of only that bytes
 * arrived.
 */
class FtpDownloadEngineTest {

    private val payload = ByteArray(300_000) { (it % 251).toByte() }

    private fun tempDirectory(): File = Files.createTempDirectory("ftp-test").toFile()

    @Test
    fun `anonymous login downloads one file and reports its size`() = runBlocking {
        FakeFtpServer(payload).use { server ->
            val directory = tempDirectory()
            val progress = mutableListOf<Pair<Long, Long>>()
            val result = FtpDownloadEngine().download(
                url = server.url(),
                headers = DownloadRequestHeaders("test", null, null),
                requestedThreads = 4,
                tempDirectory = directory,
                onProgress = { downloaded, total, _ -> progress += downloaded to total },
            )

            assertArrayEquals(payload, result.parts.single().readBytes())
            assertEquals(payload.size.toLong(), result.totalBytes)
            assertEquals(1, result.actualThreadCount)
            assertEquals(payload.size.toLong(), progress.last().first)
            assertEquals(payload.size.toLong(), progress.last().second)
            assertTrue(server.commands.contains("USER anonymous"))
            assertTrue(server.commands.contains("TYPE I"))
            assertTrue(server.commands.contains("PASV"))
        }
    }

    @Test
    fun `an interrupted transfer resumes at the bytes already on disk`() = runBlocking {
        FakeFtpServer(payload).use { server ->
            val directory = tempDirectory()
            File(directory, "part-0").writeBytes(payload.copyOfRange(0, 120_000))

            val result = FtpDownloadEngine().download(
                url = server.url(), headers = DownloadRequestHeaders("test", null, null),
                requestedThreads = 1, tempDirectory = directory, onProgress = { _, _, _ -> },
            )

            assertArrayEquals(payload, result.parts.single().readBytes())
            assertTrue(server.commands.contains("REST 120000"))
            assertEquals(payload.size.toLong(), result.totalBytes)
        }
    }

    @Test
    fun `a URL that hides a line break is refused before any command is sent`() = runBlocking {
        FakeFtpServer(payload).use { server ->
            val failure = runCatching {
                FtpDownloadEngine().download(
                    url = server.url("/dir/a%0d%0aDELE%20important.txt"),
                    headers = DownloadRequestHeaders("test", null, null),
                    requestedThreads = 1, tempDirectory = tempDirectory(), onProgress = { _, _, _ -> },
                )
            }.exceptionOrNull()

            assertTrue(failure is IOException)
            // The whole point: nothing was sent, so the server never saw DELE.
            assertTrue(server.commands.isEmpty())
        }
    }

    @Test
    fun `cancelling during the control connect does not wait for the timeout`() = runBlocking {
        val job = launch(Dispatchers.IO) {
            runCatching {
                FtpDownloadEngine().download(
                    // TEST-NET-1 has no listener and no route that answers, so the connect itself
                    // is what blocks; the point is that cancellation does not have to outwait it.
                    url = "ftp://192.0.2.1/file.bin",
                    headers = DownloadRequestHeaders("test", null, null),
                    requestedThreads = 1,
                    tempDirectory = tempDirectory(),
                    onProgress = { _, _, _ -> },
                )
            }
        }
        delay(500)
        val stillConnecting = job.isActive
        if (!stillConnecting) job.cancelAndJoin()
        // A network that refuses or answers immediately has no connect left to cancel.
        org.junit.Assume.assumeTrue("the connect returned before it could be cancelled", stillConnecting)

        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
    }

    @Test
    fun `cancelling closes the blocked read instead of waiting for the timeout`() = runBlocking {
        FakeFtpServer(payload, stallAfter = 4_096).use { server ->
            val job = launch(Dispatchers.IO) {
                runCatching {
                    FtpDownloadEngine().download(
                        url = server.url(), headers = DownloadRequestHeaders("test", null, null),
                        requestedThreads = 1, tempDirectory = tempDirectory(), onProgress = { _, _, _ -> },
                    )
                }
            }
            withTimeout(5_000) {
                while (!server.transferStarted) delay(10)
            }
            delay(200)

            // A completion handler would only run once the read returns — thirty seconds later.
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }

    @Test
    fun `a reply shorter than a status code is a protocol error`() = runBlocking {
        FakeFtpServer(payload, greeting = "OK").use { server ->
            val failure = runCatching {
                FtpDownloadEngine().download(
                    url = server.url(), headers = DownloadRequestHeaders("test", null, null),
                    requestedThreads = 1, tempDirectory = tempDirectory(), onProgress = { _, _, _ -> },
                )
            }.exceptionOrNull()

            // A substring(0, 3) here used to escape as StringIndexOutOfBoundsException, which the
            // retry loop does not treat as a failed transfer.
            assertTrue(failure is IOException)
        }
    }

    @Test
    fun `a short entity is refused instead of published`() = runBlocking {
        FakeFtpServer(payload, truncateTo = 1_000).use { server ->
            val failure = runCatching {
                FtpDownloadEngine().download(
                    url = server.url(), headers = DownloadRequestHeaders("test", null, null),
                    requestedThreads = 1, tempDirectory = tempDirectory(), onProgress = { _, _, _ -> },
                )
            }.exceptionOrNull()

            assertTrue(failure is IOException)
        }
    }

    @Test
    fun `a URL that names a directory is refused before any connection`() = runBlocking {
        val failure = runCatching {
            FtpDownloadEngine().download(
                url = "ftp://example.test/", headers = DownloadRequestHeaders("test", null, null),
                requestedThreads = 1, tempDirectory = tempDirectory(), onProgress = { _, _, _ -> },
            )
        }.exceptionOrNull()

        assertTrue(failure is IOException)
    }
}

/** Just enough of RFC 959 to serve one file, in the order the client drives it. */
private class FakeFtpServer(
    private val payload: ByteArray,
    /** Bytes to send before holding the data connection open, so a read blocks. */
    private val stallAfter: Int = Int.MAX_VALUE,
    /** Announce the full entity but send only this many bytes, then still answer 226. */
    private val truncateTo: Int = Int.MAX_VALUE,
    private val greeting: String = "220 fake ftp ready",
) : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val commands = java.util.Collections.synchronizedList(mutableListOf<String>())
    @Volatile var transferStarted = false
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val worker = Thread({ accept() }, "fake-ftp").apply { isDaemon = true; start() }

    fun url(path: String = "/dir/file.bin") = "ftp://127.0.0.1:${server.localPort}$path"

    private fun accept() {
        while (!server.isClosed) {
            val control = runCatching { server.accept() }.getOrNull() ?: return
            control.use { runCatching { session(it) } }
        }
    }

    private fun session(control: java.net.Socket) {
        val reader = control.getInputStream().bufferedReader()
        val writer = control.getOutputStream().bufferedWriter()
        fun reply(line: String) {
            writer.write(line + "\r\n")
            writer.flush()
        }
        reply(greeting)
        var data: ServerSocket? = null
        var offset = 0
        while (true) {
            val line = reader.readLine() ?: break
            commands += line
            when (line.substringBefore(' ').uppercase()) {
                "USER" -> reply("331 password required")
                "PASS" -> reply("230 logged in")
                "TYPE" -> reply("200 binary mode")
                "SIZE" -> reply("213 ${payload.size}")
                "REST" -> {
                    offset = line.substringAfter(' ').trim().toInt()
                    reply("350 restarting at $offset")
                }
                "PASV" -> {
                    val passive = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                    data = passive
                    val port = passive.localPort
                    reply("227 Entering Passive Mode (127,0,0,1,${port / 256},${port % 256})")
                }
                "RETR" -> {
                    val passive = data ?: throw IllegalStateException("RETR before PASV")
                    reply("150 opening data connection")
                    passive.accept().use { socket ->
                        transferStarted = true
                        val remaining = payload.size - offset
                        val sent = minOf(stallAfter, truncateTo, remaining)
                        socket.getOutputStream().use { out ->
                            out.write(payload, offset, sent)
                            out.flush()
                            // Only hold the connection open when the test asked for a stall.
                            if (sent < minOf(remaining, truncateTo)) {
                                var waited = 0
                                while (!closed.get() && waited < 60_000) {
                                    Thread.sleep(100)
                                    waited += 100
                                }
                            }
                        }
                    }
                    passive.close()
                    data = null
                    offset = 0
                    reply("226 transfer complete")
                }
                "QUIT" -> {
                    reply("221 bye")
                    return
                }
                else -> reply("502 not implemented")
            }
        }
    }

    override fun close() {
        closed.set(true)
        runCatching { server.close() }
        worker.join(1_000)
    }
}
