package com.mybrowser.download

import kotlinx.coroutines.runBlocking
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
private class FakeFtpServer(private val payload: ByteArray) : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val commands = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val worker = Thread({ accept() }, "fake-ftp").apply { isDaemon = true; start() }

    fun url(path: String = "/dir/file.bin") = "ftp://127.0.0.1:${server.localPort}$path"

    private fun accept() {
        while (!server.isClosed) {
            val control = runCatching { server.accept() }.getOrNull() ?: return
            control.use { session(it) }
        }
    }

    private fun session(control: java.net.Socket) {
        val reader = control.getInputStream().bufferedReader()
        val writer = control.getOutputStream().bufferedWriter()
        fun reply(line: String) {
            writer.write(line + "\r\n")
            writer.flush()
        }
        reply("220 fake ftp ready")
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
                        socket.getOutputStream().write(payload, offset, payload.size - offset)
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
        runCatching { server.close() }
        worker.join(1_000)
    }
}
