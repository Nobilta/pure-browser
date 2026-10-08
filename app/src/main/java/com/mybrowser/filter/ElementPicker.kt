package com.mybrowser.filter

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.mybrowser.core.UrlUtils
import com.mybrowser.site.SiteOrigin
import org.json.JSONObject

/**
 * Blocking an element by pointing at it.
 *
 * The page keeps the part that cannot leave it: the outline follows a finger, which no round trip
 * through this class could do at the speed a finger moves. Choosing an element, moving up and down
 * the tree, and drawing them are the page's; what to do with the answer is this side's, and the
 * two talk over a message channel the app already uses elsewhere.
 *
 * The channel is open to the page, so it is treated as page input: a message counts only while a
 * picker this app started is running, only from the top frame, and only from the origin the picker
 * was started on. That is not a boundary against the page itself — a page can always post to a
 * channel it can see — so nothing here is trusted as an instruction: the app reports what the page
 * claims was chosen, and the user still presses the button that writes the rule.
 */
// The feature guard is a runCatching around isFeatureSupported, which lint cannot see through;
// the same annotation the app's other two message-channel bridges carry for the same reason.
@SuppressLint("RequiresFeature")
internal class ElementPicker(private val webView: WebView) {

    /** What the page reports about the element the user has chosen. */
    data class Selection(
        val selector: String,
        val matchCount: Int,
        val canParent: Boolean,
        val canChild: Boolean,
    )

    /** How a pick ended; the caller owns the message, so the reason has to cross the boundary. */
    enum class End { STOPPED, REFUSED }

    private var closed = false
    private var installed = false
    private var active = false
    private var origin: String? = null
    private var report: ((Selection?) -> Unit)? = null
    private var onEnd: ((End) -> Unit)? = null

    /**
     * Puts the picker on the page and starts reporting what is pointed at.
     *
     * False when this page cannot host one: no script, no address, a document that is not a page, or
     * a WebView whose channel was never attached. The last one is refused rather than attached here:
     * [install] explains what attaching it late costs. The caller says so rather than leaving a bar
     * over a page nothing will ever answer from.
     */
    fun start(accent: Int, bottomInset: Int, onSelection: (Selection?) -> Unit, onEnd: (End) -> Unit = {}): Boolean {
        if (closed || active || !installed) return false
        if (!webView.settings.javaScriptEnabled) return false
        val url = webView.url ?: return false
        if (!UrlUtils.isHttpUrl(url)) return false
        val page = SiteOrigin.of(url) ?: return false
        // Reading the script is part of starting, not of installing: a broken asset has to answer
        // false here rather than throw out of the click that opened the menu. install() has the
        // same reason for wrapping its call, and this is the only other place the asset is read.
        val script = runCatching { startScript(accent, bottomInset) }.getOrNull() ?: return false
        origin = page
        report = onSelection
        this.onEnd = onEnd
        active = true
        webView.evaluateJavascript(script) { raw ->
            // The page refused -- an embedded frame, or a document that is not a page -- after this
            // side had already reported a successful start. Nothing is on screen, so finish() ends
            // the pick, and the caller is told it was refused rather than left showing a bar over a
            // page that will never answer.
            if (raw != "true" && active) finish(End.REFUSED)
        }
        return true
    }

    /** Moves one level towards the document, or back into the chosen element. */
    fun step(towardsParent: Boolean) {
        if (closed || !active) return
        val delta = if (towardsParent) 1 else -1
        webView.evaluateJavascript(
            "(function(){var api=window.$API;return api?api.step($delta):false})()", null)
    }

    /** Takes the picker off the page. [onSelection] hears nothing more afterwards. */
    fun stop() {
        if (!active) return
        webView.evaluateJavascript("(function(){var api=window.$API;if(api)api.stop()})()", null)
        finish()
    }

    /** The WebView is going away; drop the listener with it. */
    fun dispose() {
        closed = true
        active = false
        report = null
        onEnd = null
        // The listener is registered under a fixed name on the WebView, which outlives this object
        // when the instance is parked rather than destroyed. Leaving it attached keeps the callback
        // — and this instance with it — alive, and a re-configured WebView would try to register the
        // same name again.
        if (installed) {
            runCatching { WebViewCompat.removeWebMessageListener(webView, BRIDGE) }
            installed = false
        }
    }

    private fun finish(reason: End = End.STOPPED) {
        active = false
        origin = null
        val listener = report
        val ended = onEnd
        report = null
        onEnd = null
        listener?.invoke(null)
        ended?.invoke(reason)
    }

    private fun startScript(accent: Int, bottomInset: Int): String = "(function(){var api=" + source() + "(window);" +
        "if(!api)return false;return api.start({accent:" + JSONObject.quote(color(accent)) +
        ",bottomInset:" + bottomInset.coerceIn(0, MAX_INSET_PX) + "})})()"

    /** `#rrggbb` for the accent, taken from the app's own theme so the outline matches it. */
    private fun color(argb: Int): String = String.format("#%06X", 0xFFFFFF and argb)

    /**
     * Attaches this picker's message channel to the WebView.
     *
     * Called while the WebView is being configured, not when a pick starts, and the difference is
     * not stylistic: adding a message listener to a document that has already loaded and settled,
     * and then changing that document from script, took the whole app process down in
     * `libwebviewchromium` with a null dereference -- measured, repeatedly, on a build that did
     * exactly that and nowhere else. The channel's lifetime is the WebView's in any case, so it
     * belongs where the WebView's other channels are installed.
     */
    fun install(): Boolean {
        if (installed) return true
        val supported = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        }.getOrDefault(false)
        if (!supported) return false
        return runCatching {
            WebViewCompat.addWebMessageListener(webView, BRIDGE, setOf("*"),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy,
                    ) = onMessage(view, message.data, sourceOrigin, isMainFrame)
                })
            installed = true
        }.isSuccess
    }

    private fun onMessage(view: WebView, raw: String?, sourceOrigin: Uri, isMainFrame: Boolean) {
        if (closed || !active || view !== webView || !isMainFrame) return
        val page = origin ?: return
        if (SiteOrigin.of(sourceOrigin.toString()) != page) return
        val data = raw?.takeIf { it.length <= MAX_MESSAGE_LENGTH } ?: return
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return
        // The page can end a pick by itself — a cancelled touch is the case that matters — and the
        // bar is this side's, so nothing else would take it down.
        if (json.optString("type") == "ended") { finish(); return }
        if (json.optString("type") != "pick") return
        val selector = json.optString("selector").trim()
        if (selector.isEmpty() || selector.length > PageHide.MAX_SELECTOR_LENGTH) return
        // This channel is visible to the page, so a message is page input, not an answer: a page can
        // post a selector the picker itself would never produce — `*` above all, which hides
        // everything and, chosen for every site, everywhere. The rule writer refuses those too;
        // refusing here as well is what keeps the bar from offering to write one.
        if (!BlockRules.isUsableElementSelector(selector)) return
        report?.invoke(Selection(
            selector = selector,
            matchCount = json.optInt("count", 0).coerceIn(0, MAX_MATCHES),
            canParent = json.optBoolean("canParent"),
            canChild = json.optBoolean("canChild"),
        ))
    }

    private fun source(): String = cachedSource ?: synchronized(this) {
        cachedSource ?: webView.context.assets.open(SCRIPT).bufferedReader().use { it.readText() }
            .also { cachedSource = it }
    }

    private companion object {
        const val SCRIPT = "element-picker.js"
        const val BRIDGE = "mybrowserElementPicker"
        const val API = "__pureElementPicker"
        const val MAX_MESSAGE_LENGTH = 8 * 1024
        const val MAX_MATCHES = 100_000
        const val MAX_INSET_PX = 4_000

        /** Read once per process: the script is the same for every page and every WebView. */
        @Volatile private var cachedSource: String? = null
    }
}
