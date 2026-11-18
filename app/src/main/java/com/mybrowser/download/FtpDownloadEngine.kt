package com.mybrowser.download

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
        private fun containsLineBreak(value: String) = value.any { it == '\r' || it == '\n' || it == '\u0000' }

        fun parse(url: String): FtpTarget? = runCatching {
            val uri = URI(url)
            if (!uri.scheme.equals("ftp", ignoreCase = true)) return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val path = uri.path.orEmpty()
            if (path.isBlank() || path.endsWith("/")) return null
            val userInfo = uri.userInfo.orEmpty()
            val user = userInfo.substringBefore(':').ifBlank { ANONYMOUS_USER }
            val password = if (userInfo.contains(':')) userInfo.substringAfter(':') else ANONYMOUS_PASSWORD
            // These come from the URL percent-decoded and go into commands verbatim. FTP has no
            // quoting, so a line break in any of them would arrive as an extra command: the
            // address is refused instead.
            if (listOf(path, user, password).any(::containsLineBreak)) return null
            FtpTarget(
                host = host,
                port = if (uri.port > 0) uri.port else DEFAULT_PORT,
                user = user,
                password = password,
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
        // closing the sockets the transfer is waiting on. A completion handler would run only
        // once this coroutine finishes, which is exactly what the blocked read prevents: the
        // watcher is a child coroutine, so its finally runs as soon as we are cancelled. The
        // session's sockets exist from its construction for that reason: a connect still in
        // progress is waited on the same way, and closing it is what aborts it.
        val cancelGuard = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                session.close()
            }
        }
        try {
            session.connect()
            session.login(target)
            session.command("TYPE I")
            val total = session.size(target.path)
            val already = destination.length()
            // Even a full-length part can belong to a RETR whose final reply was a failure.
            val offset = if (total != null && already > 0 && already < total && session.rest(already)) already else 0L
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
            // A server may end the stream early and still answer 226. Only the length it announced
            // proves the file holds the entire entity.
            if (total != null && downloaded != total) {
                throw IOException("FTP transfer stopped at $downloaded of $total bytes")
            }
            progress.update(downloaded, total ?: downloaded, force = true)
            return@withContext DownloadPayload(listOf(destination), total ?: downloaded, 1)
        } finally {
            cancelGuard.cancel()
            session.close()
        }
    }

    private companion object {
        const val BUFFER_SIZE_BYTES = 64 * 1024
    }
}

private data class FtpReply(val code: Int, val text: String)

/** One control connection plus the passive data connection a single RETR uses. */
private class FtpSession(private val host: String, private val port: Int) : Closeable {
    // Both sockets are created here and connected afterwards, so a task that is cancelled while
    // one of them is still connecting has something to close — closing is the only way to stop a
    // connect that has not returned.
    private val control = Socket()
    private lateinit var reader: BufferedInputStream
    private lateinit var writer: BufferedOutputStream
    /** Written by the transfer coroutine, read by the cancellation watcher: guarded by [lock]. */
    private val lock = Any()
    private var data: Socket? = null
    private var closed = false

    fun connect() {
        control.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        control.soTimeout = READ_TIMEOUT_MS
        control.tcpNoDelay = true
        reader = BufferedInputStream(control.getInputStream())
        writer = BufferedOutputStream(control.getOutputStream())
    }

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
            forgetData(opened)
            runCatching { opened.close() }
            throw IOException("FTP download refused: ${reply.text}")
        }
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
        val opened = openData()
        try {
            opened.connect(InetSocketAddress(control.inetAddress, numbers[4] * 256 + numbers[5]), CONNECT_TIMEOUT_MS)
            opened.soTimeout = READ_TIMEOUT_MS
        } catch (error: Exception) {
            forgetData(opened)
            runCatching { opened.close() }
            throw error
        }
        return opened
    }

    /**
     * The data socket, registered before it connects so cancellation can close it.
     *
     * Registration and [close] are serialized: a cancellation that already ran must not leave a
     * socket connecting that nobody will close, and one that runs later must see this one.
     */
    private fun openData(): Socket = synchronized(lock) {
        if (closed) throw IOException("FTP transfer cancelled")
        Socket().also { data = it }
    }

    private fun forgetData(socket: Socket) = synchronized(lock) {
        if (data === socket) data = null
    }

    private fun readReply(): FtpReply {
        val first = readLine() ?: throw IOException("FTP connection closed")
        val code = first.take(3).toIntOrNull()
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
        val opened = synchronized(lock) {
            closed = true
            data.also { data = null }
        }
        runCatching { opened?.close() }
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
