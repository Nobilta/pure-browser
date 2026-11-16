package com.mybrowser.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * An FTP file named by a URL, in the form the client needs it.
 *
 * The path is the decoded one: FTP has no quoting, so a name with spaces or non-ASCII characters
 * is sent exactly as the server named it. A URL that names a directory cannot become a file and is
 * rejected here rather than failing later in SIZE or RETR.
 */
internal data class FtpTarget(
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    val path: String,
) {
    companion object {
        fun parse(url: String): FtpTarget? = runCatching {
            val uri = URI(url)
            if (!uri.scheme.equals("ftp", ignoreCase = true)) return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val path = uri.path.orEmpty()
            if (path.isBlank() || path.endsWith("/")) return null
            val userInfo = uri.userInfo.orEmpty()
            val user = userInfo.substringBefore(':').ifBlank { ANONYMOUS_USER }
            FtpTarget(
                host = host,
                port = if (uri.port > 0) uri.port else DEFAULT_PORT,
                user = user,
                password = if (userInfo.contains(':')) userInfo.substringAfter(':') else ANONYMOUS_PASSWORD,
                path = path,
            )
        }.getOrNull()
    }
}

/**
 * An FTP task's bytes.
 *
 * FTP is one stream, so a task is one connection: SIZE gives the entity length, REST starts the
 * stream after the bytes a pause left on disk, and TYPE I stops the server rewriting line endings.
 * Passive mode only — active mode would need the phone to listen for the server, and every public
 * server supports passive.
 *
 * Deliberately absent: FTPS, EPSV, proxies, directory listings and ASCII mode. A URL whose server
 * refuses REST restarts from zero instead of keeping bytes it cannot vouch for.
 */
internal class FtpDownloadEngine(
    private val rateLimiter: DownloadRateLimiter = DownloadRateLimiter(),
) : DownloadTransport {

    override suspend fun download(
        url: String,
        headers: DownloadRequestHeaders,
        requestedThreads: Int,
        tempDirectory: File,
        onProgress: (downloaded: Long, total: Long, actualThreads: Int) -> Unit,
    ): DownloadPayload = withContext(Dispatchers.IO) {
        val target = FtpTarget.parse(url) ?: throw IOException("Unsupported FTP URL")
        if (!tempDirectory.mkdirs() && !tempDirectory.isDirectory) {
            throw IOException("Unable to create download temporary directory")
        }
        val destination = File(tempDirectory, "part-0")
        val progress = TransferProgress(onProgress)
        val session = FtpSession(target.host, target.port)
        // A blocking socket read ignores Thread.interrupt, so the only reliable cancellation is
        // closing the sockets the transfer is waiting on.
        val cancelGuard = currentCoroutineContext().job.invokeOnCompletion { session.close() }
        try {
            session.login(target)
            session.command("TYPE I")
            val total = session.size(target.path)
            val already = destination.length()
            if (total != null && already >= total) {
                progress.update(already, total, force = true)
                return@withContext DownloadPayload(listOf(destination), total, 1)
            }
            val offset = if (already > 0 && session.rest(already)) already else 0L
            if (offset == 0L) destination.delete()
            progress.update(offset, total ?: 0L, force = true)
            val data = session.retrieve(target.path)
            var downloaded = offset
            try {
                FileOutputStream(destination, offset > 0L).use { output ->
                    val input = data.getInputStream()
                    val buffer = ByteArray(BUFFER_SIZE_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        rateLimiter.acquire(count)
                        output.write(buffer, 0, count)
                        downloaded += count
                        progress.update(downloaded, total ?: 0L)
                    }
                }
            } finally {
                runCatching { data.close() }
            }
            session.finish()
            progress.update(downloaded, total ?: downloaded, force = true)
            return@withContext DownloadPayload(listOf(destination), total ?: downloaded, 1)
        } finally {
            cancelGuard.dispose()
            session.close()
        }
    }

    private companion object {
        const val BUFFER_SIZE_BYTES = 64 * 1024
    }
}

private data class FtpReply(val code: Int, val text: String)

/** One control connection plus the passive data connection a single RETR uses. */
private class FtpSession(host: String, port: Int) : Closeable {
    private val control = Socket().apply {
        connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        soTimeout = READ_TIMEOUT_MS
        tcpNoDelay = true
    }
    private val reader = BufferedInputStream(control.getInputStream())
    private val writer = BufferedOutputStream(control.getOutputStream())
    private var data: Socket? = null

    fun login(target: FtpTarget) {
        val greeting = readReply()
        if (greeting.code !in 200..299) throw IOException("FTP server refused the connection: ${greeting.text}")
        val user = command("USER ${target.user}")
        when (user.code) {
            230 -> Unit
            331 -> {
                val accepted = command("PASS ${target.password}")
                if (accepted.code != 230) throw IOException("FTP login failed: ${accepted.text}")
            }
            else -> throw IOException("FTP login refused: ${user.text}")
        }
    }

    fun command(line: String): FtpReply {
        writer.write((line + "\r\n").toByteArray(Charsets.UTF_8))
        writer.flush()
        return readReply()
    }

    fun size(path: String): Long? {
        val reply = command("SIZE $path")
        if (reply.code != 213) return null
        return reply.text.substringAfter(' ', "").trim().toLongOrNull()?.takeIf { it >= 0 }
    }

    /** Whether the server will continue the next RETR at [offset]; 350 is the only "yes". */
    fun rest(offset: Long): Boolean = command("REST $offset").code == 350

    fun retrieve(path: String): Socket {
        val opened = passive()
        val reply = command("RETR $path")
        if (reply.code !in 125..150) {
            runCatching { opened.close() }
            throw IOException("FTP download refused: ${reply.text}")
        }
        data = opened
        return opened
    }

    /** Reads the "transfer complete" reply that follows the data connection's end of stream. */
    fun finish() {
        val reply = readReply()
        if (reply.code !in 200..299) throw IOException("FTP transfer failed: ${reply.text}")
    }

    private fun passive(): Socket {
        val reply = command("PASV")
        if (reply.code != 227) throw IOException("FTP passive mode refused: ${reply.text}")
        val open = reply.text.indexOf('(')
        val close = reply.text.indexOf(')', open + 1)
        if (open < 0 || close < 0) throw IOException("Malformed FTP passive reply: ${reply.text}")
        val numbers = reply.text.substring(open + 1, close).split(',')
            .map { it.trim().toIntOrNull() ?: throw IOException("Malformed FTP passive reply: ${reply.text}") }
        if (numbers.size != 6 || numbers.any { it !in 0..255 }) {
            throw IOException("Malformed FTP passive reply: ${reply.text}")
        }
        // The advertised address is often a private one on a server behind NAT; the control
        // connection already reached it, so only the port is worth taking from the reply.
        return Socket().apply {
            connect(InetSocketAddress(control.inetAddress, numbers[4] * 256 + numbers[5]), CONNECT_TIMEOUT_MS)
            soTimeout = READ_TIMEOUT_MS
        }
    }

    private fun readReply(): FtpReply {
        val first = readLine() ?: throw IOException("FTP connection closed")
        val code = first.substring(0, 3).toIntOrNull()
            ?: throw IOException("Malformed FTP reply: $first")
        if (first.length > 3 && first[3] == '-') {
            // Multi-line replies repeat the code with a space on their last line.
            val terminator = "$code "
            var line = readLine() ?: throw IOException("FTP connection closed")
            while (!line.startsWith(terminator)) {
                line = readLine() ?: throw IOException("FTP connection closed")
            }
            return FtpReply(code, line)
        }
        return FtpReply(code, first)
    }

    private fun readLine(): String? {
        val line = StringBuilder()
        while (true) {
            val value = reader.read()
            if (value < 0) return if (line.isEmpty()) null else line.toString()
            if (value == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length >= MAX_REPLY_LENGTH) throw IOException("FTP reply too long")
            line.append(value.toChar())
        }
    }

    override fun close() {
        runCatching { data?.close() }
        runCatching { control.close() }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_REPLY_LENGTH = 4 * 1024
    }
}

private const val ANONYMOUS_USER = "anonymous"
private const val ANONYMOUS_PASSWORD = "anonymous@"
private const val DEFAULT_PORT = 21
