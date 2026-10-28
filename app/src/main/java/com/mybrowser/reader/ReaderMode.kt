package com.mybrowser.reader

import android.content.Context
import android.webkit.WebView
import com.mybrowser.core.UrlUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONTokener

/**
 * In-page reader mode: Readability (Apache-2.0) plus our own presentation script.
 *
 * The article replaces the live document rather than opening a second one, so Back and the
 * address bar keep pointing at the article's own URL; exiting swaps the original body node
 * back, so listeners and form values survive. A navigation drops the saved state with the
 * document, which is why nothing is persisted.
 */
object ReaderMode {

    enum class Outcome { ENTERED, EXITED, UNSUPPORTED }

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var controllerPayload: String? = null
    @Volatile private var fullPayload: String? = null

    fun toggle(webView: WebView, darkTheme: Boolean, onResult: (Outcome) -> Unit) {
        val url = webView.url
        if (url.isNullOrBlank() || !UrlUtils.isHttpUrl(url)) {
            onResult(Outcome.UNSUPPORTED)
            return
        }
        // The full payload is ~85 KB of Readability; after the first injection the marker lets
        // exit and re-entry send only the small controller. Loading the assets moved off the
        // main thread for the first use, and both payloads are cached for later pages.
        webView.evaluateJavascript("window.__pureReaderLoaded === true") { marker ->
            if (marker?.trim() == "true") {
                send(webView, darkTheme, controller(webView.context), onResult)
            } else {
                io.launch {
                    val script = full(webView.context)
                    webView.post { send(webView, darkTheme, script, onResult) }
                }
            }
        }
    }

    private fun send(
        webView: WebView,
        darkTheme: Boolean,
        script: String,
        onResult: (Outcome) -> Unit,
    ) {
        if (webView.url.isNullOrBlank()) {
            onResult(Outcome.UNSUPPORTED)
            return
        }
        val prefix = "window.__pureReaderLoaded = true;\nwindow.__pureReaderDark = $darkTheme;\n"
        webView.evaluateJavascript(prefix + script) { raw ->
            onResult(
                when (decodeString(raw)) {
                    "on" -> Outcome.ENTERED
                    "off" -> Outcome.EXITED
                    else -> Outcome.UNSUPPORTED
                },
            )
        }
    }

    private fun controller(context: Context): String = controllerPayload ?: context.assets.open("reader.js")
        .bufferedReader().use { it.readText() }
        .also { controllerPayload = it }

    /** Readability must exist before the controller runs; only the first page in a document needs it. */
    private fun full(context: Context): String = fullPayload ?: run {
        val readability = context.assets.open("readability.js")
            .bufferedReader().use { it.readText() }
        (readability + "\n" + controller(context)).also { fullPayload = it }
    }

    private fun decodeString(raw: String?): String? = runCatching {
        JSONTokener(raw ?: return null).nextValue() as? String
    }.getOrNull()
}
