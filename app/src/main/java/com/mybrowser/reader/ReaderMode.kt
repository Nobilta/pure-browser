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
 * The controller is injected once per document and stays a callable function, so later toggles
 * and style changes do not resend the script. The article is appended to the live document rather
 * than opening a second one, so Back and the address bar keep pointing at the article's own URL. The original body children are hidden,
 * not detached, so listeners, form values and iframe documents survive; a navigation drops the
 * saved state with the document, which is why nothing is persisted.
 */
internal class ReaderDocumentGeneration {
    @Volatile private var value = 0L

    fun onPageStarted() {
        value++
    }

    fun capture(): Long = value

    fun isCurrent(captured: Long): Boolean = captured == value
}

object ReaderMode {

    enum class Outcome { ENTERED, EXITED, UNSUPPORTED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var fullPayload: String? = null

    /**
     * URL comparison cannot tell a reload apart from the same address; a pending reader request
     * from the old document must not run on the new one.
     */
    private val documents = java.util.WeakHashMap<WebView, ReaderDocumentGeneration>()
    private fun generation(view: WebView) = documents.getOrPut(view) { ReaderDocumentGeneration() }

    fun onPageStarted(view: WebView) {
        generation(view).onPageStarted()
    }

    fun toggle(webView: WebView, style: ReaderStyle, darkTheme: Boolean, onResult: (Outcome) -> Unit) {
        val url = webView.url
        if (url.isNullOrBlank() || !UrlUtils.isHttpUrl(url)) {
            onResult(Outcome.UNSUPPORTED)
            return
        }
        val capturedGeneration = generation(webView).capture()
        // The controller stays on the window after the first injection, so a later toggle is one
        // short call. Only a document that never ran it needs the Readability payload.
        val call = "window.__pureReaderStyle = ${styleJson(style, darkTheme)};\n" +
            "typeof window.__pureReaderToggle === 'function' ? window.__pureReaderToggle() : 'missing'"
        webView.evaluateJavascript(call) { raw ->
            // A stale result belongs to a document that is gone; reporting it would clear the
            // reader state the new document may already have.
            if (!isCurrent(webView, url, capturedGeneration)) return@evaluateJavascript
            when (decodeString(raw)) {
                "on" -> onResult(Outcome.ENTERED)
                "off" -> onResult(Outcome.EXITED)
                "empty" -> onResult(Outcome.UNSUPPORTED)
                "missing" -> injectFull(webView, url, capturedGeneration, style, darkTheme, onResult)
                // An unexpected answer means a page replaced the function; do not re-inject
                // over it, or a toggle would silently change what the script does.
                else -> onResult(Outcome.UNSUPPORTED)
            }
        }
    }

    private fun injectFull(
        webView: WebView,
        expectedUrl: String,
        capturedGeneration: Long,
        style: ReaderStyle,
        darkTheme: Boolean,
        onResult: (Outcome) -> Unit,
    ) {
        val context = webView.context.applicationContext
        scope.launch {
            val script = kotlinx.coroutines.withContext(Dispatchers.IO) { full(context) }
            if (isCurrent(webView, expectedUrl, capturedGeneration)) {
                send(webView, expectedUrl, capturedGeneration, style, darkTheme, script, onResult)
            }
        }
    }

    fun readActive(view: WebView, onResult: (Boolean) -> Unit) {
        val document = generation(view)
        val captured = document.capture()
        view.evaluateJavascript("!!(window.__pureReaderState && window.__pureReaderState.active)") {
            if (document.isCurrent(captured)) onResult(it == "true")
        }
    }

    /** Re-paints an open reader without re-running Readability. */
    fun applyStyle(webView: WebView, style: ReaderStyle, darkTheme: Boolean) {
        webView.evaluateJavascript(
            "window.__pureReaderApply && window.__pureReaderApply(${styleJson(style, darkTheme)})", null,
        )
    }

    /** Physical pixels occupied by the reader controls, including their bottom gap. */
    fun setBottomInset(webView: WebView, pixels: Int) {
        webView.evaluateJavascript(
            "window.__pureReaderSetBottomInset && window.__pureReaderSetBottomInset($pixels)", null,
        )
    }

    /** Resolves paper "system" here: the page is told the final palette and never the app theme. */
    private fun styleJson(style: ReaderStyle, darkTheme: Boolean): String {
        val paper = when (style.paper) {
            ReaderStyle.Paper.SYSTEM -> if (darkTheme) "dark" else "light"
            ReaderStyle.Paper.WARM -> "warm"
            ReaderStyle.Paper.DARK -> "dark"
        }
        return "{\"size\":${style.size},\"lineHeight\":${style.lineHeight},\"paper\":\"$paper\"}"
    }

    private fun send(
        webView: WebView,
        expectedUrl: String,
        generation: Long,
        style: ReaderStyle,
        darkTheme: Boolean,
        script: String,
        onResult: (Outcome) -> Unit,
    ) {
        if (!isCurrent(webView, expectedUrl, generation)) return
        val prefix = "window.__pureReaderStyle = ${styleJson(style, darkTheme)};\n"
        webView.evaluateJavascript(prefix + script) { raw ->
            // The document can navigate or reload while the script runs; its result then belongs
            // to a page that is gone and must not drive the new one's reader state.
            if (!isCurrent(webView, expectedUrl, generation)) return@evaluateJavascript
            onResult(
                when (decodeString(raw)) {
                    "on" -> Outcome.ENTERED
                    "off" -> Outcome.EXITED
                    else -> Outcome.UNSUPPORTED
                },
            )
        }
    }

    private fun isCurrent(webView: WebView, url: String, capturedGeneration: Long): Boolean =
        generation(webView).isCurrent(capturedGeneration) && webView.url == url

    /** Readability plus the controller; both are packaged together and cached for the process. */
    private fun full(context: Context): String = fullPayload ?: run {
        val readability = context.assets.open("readability.js")
            .bufferedReader().use { it.readText() }
        val controller = context.assets.open("reader.js")
            .bufferedReader().use { it.readText() }
        (readability + "\n" + controller).also { fullPayload = it }
    }

    private fun decodeString(raw: String?): String? = runCatching {
        JSONTokener(raw ?: return null).nextValue() as? String
    }.getOrNull()
}
