package com.mybrowser.reader

import android.content.Context
import android.webkit.WebView
import com.mybrowser.core.UrlUtils
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

    @Volatile private var payload: String? = null

    fun toggle(webView: WebView, darkTheme: Boolean, onResult: (Outcome) -> Unit) {
        val url = webView.url
        if (url.isNullOrBlank() || !UrlUtils.isHttpUrl(url)) {
            onResult(Outcome.UNSUPPORTED)
            return
        }
        val script = payload ?: load(webView.context).also { payload = it }
        webView.evaluateJavascript("window.__pureReaderDark = $darkTheme;\n$script") { raw ->
            onResult(
                when (decodeString(raw)) {
                    "on" -> Outcome.ENTERED
                    "off" -> Outcome.EXITED
                    else -> Outcome.UNSUPPORTED
                },
            )
        }
    }

    /** Both assets in one payload: Readability must exist before the controller runs. */
    private fun load(context: Context): String = context.assets.open("readability.js")
        .bufferedReader().use { it.readText() } +
        "\n" + context.assets.open("reader.js").bufferedReader().use { it.readText() }

    private fun decodeString(raw: String?): String? = runCatching {
        JSONTokener(raw ?: return null).nextValue() as? String
    }.getOrNull()
}
