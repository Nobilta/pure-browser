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
 * The article is appended to the live document rather than opening a second one, so Back and the
 * address bar keep pointing at the article's own URL. The original body children are hidden,
 * not detached, so listeners, form values and iframe documents survive; a navigation drops the
 * saved state with the document, which is why nothing is persisted.
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
        // The full payload is ~85 KB of Readability; once it has run, only the small controller
        // is needed. The marker is set by our own payload, not by a page, and both the async
        // load and the final evaluation re-check the document URL so a navigation cannot receive
        // a controller without Readability.
        webView.evaluateJavascript("window.__pureReaderReady === true") { marker ->
            if (webView.url != url) {
                onResult(Outcome.UNSUPPORTED)
            } else if (marker?.trim() == "true") {
                send(webView, url, darkTheme, controller(webView.context), onResult)
            } else {
                io.launch {
                    val script = full(webView.context)
                    webView.post {
                        if (webView.url == url) {
                            send(webView, url, darkTheme, script, onResult)
                        } else {
                            onResult(Outcome.UNSUPPORTED)
                        }
                    }
                }
            }
        }
    }

    private fun send(
        webView: WebView,
        expectedUrl: String,
        darkTheme: Boolean,
        script: String,
        onResult: (Outcome) -> Unit,
    ) {
        if (webView.url != expectedUrl) {
            onResult(Outcome.UNSUPPORTED)
            return
        }
        val prefix = "window.__pureReaderDark = $darkTheme;\n"
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
        // The marker is set only after Readability has actually executed, so a controller-only
        // injection is never sent to a document where Readability is missing.
        (readability + "\nwindow.__pureReaderReady = true;\n" + controller(context))
            .also { fullPayload = it }
    }

    private fun decodeString(raw: String?): String? = runCatching {
        JSONTokener(raw ?: return null).nextValue() as? String
    }.getOrNull()
}
