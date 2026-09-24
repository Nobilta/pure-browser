package com.mybrowser.userscript

import android.util.Base64
import com.mybrowser.core.RedirectPolicy
import com.mybrowser.core.TextDownloader
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Install-time, cookie-free resources; page code receives immutable bytes, never a fetch capability. */
data class ScriptResource(val mime: String, val base64: String) {
    fun bytes(): ByteArray = Base64.decode(base64, Base64.NO_WRAP)
    fun json(): JSONObject = JSONObject().put("mime", mime).put("base64", base64)
    fun runtimeJson(): JSONObject = JSONObject().put("text", bytes().toString(Charsets.UTF_8))
        .put("url", "data:$mime;base64,$base64")
    companion object {
        const val MAX_BYTES = 256 * 1024
        fun decode(value: JSONObject): ScriptResource {
            val mime = value.getString("mime")
            require(mime.length <= 96 && Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+").matches(mime))
            val encoded = value.getString("base64"); require(encoded.length <= (MAX_BYTES + 2) / 3 * 4)
            return ScriptResource(mime, encoded).also { require(it.bytes().size <= MAX_BYTES) }
        }
        suspend fun fetch(raw: String): ScriptResource {
            require(TextDownloader.isHttpUrl(raw))
            var url = URL(raw)
            repeat(6) { hop ->
                coroutineContext.ensureActive()
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15_000; connection.readTimeout = 20_000
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.setRequestProperty("User-Agent", "PureBrowser")
                    when (connection.responseCode) {
                        301, 302, 303, 307, 308 -> {
                            if (hop == RedirectPolicy.MAX_HOPS) throw IOException("Too many redirects")
                            val location = connection.getHeaderField("Location") ?: throw IOException("Missing redirect")
                            url = RedirectPolicy.next(url, location) { TextDownloader.isHttpUrl(it) }
                                ?: throw IOException("Unsafe redirect")
                        }
                        200 -> {
                            require(connection.contentLengthLong <= MAX_BYTES)
                            val buffer = ByteArray(8192); val output = ByteArrayOutputStream()
                            connection.inputStream.use { input ->
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val size = input.read(buffer); if (size < 0) break
                                    require(output.size() + size <= MAX_BYTES); output.write(buffer, 0, size)
                                }
                            }
                            val mime = connection.contentType.orEmpty().substringBefore(';').trim()
                                .takeIf { Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+").matches(it) && it.length <= 96 }
                                ?: "application/octet-stream"
                            return ScriptResource(mime, Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
                        }
                        else -> throw IOException("HTTP ${connection.responseCode}")
                    }
                } finally { connection.disconnect() }
            }
            // The loop above always returns or throws; Kotlin needs a terminal statement anyway.
            throw IOException("Too many redirects")
        }
        fun readMap(value: JSONObject): Map<String, ScriptResource> {
            require(value.length() <= 8)
            return value.keys().asSequence().associateWith { name ->
                require(Regex("[A-Za-z_][A-Za-z0-9_.-]{0,63}").matches(name)); decode(value.getJSONObject(name))
            }
        }
    }
}
