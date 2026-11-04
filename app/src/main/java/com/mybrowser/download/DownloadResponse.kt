package com.mybrowser.download

import java.io.Closeable
import java.io.InputStream

/** An already opened response: confirmation transfers ownership without another HTTP request. */
class DownloadResponse(val body: InputStream, val length: Long) : Closeable {
    override fun close() { runCatching { body.close() } }
}
