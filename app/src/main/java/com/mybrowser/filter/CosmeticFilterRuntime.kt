package com.mybrowser.filter

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.mybrowser.site.SiteOrigin
import com.mybrowser.site.SiteSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/** Read-only CSS access. The capability stays inside the document-start closure,
 * never in the DOM or in a late-injected program that page code could intercept. */
internal class CosmeticRuleBridge(private val lookup: (String) -> String) {
    val capability: String = UUID.randomUUID().toString()
    @Volatile private var closed = false

    @JavascriptInterface
    fun css(capability: String?, url: String?): String {
        if (closed || capability != this.capability || url == null ||
            url.length > FilterController.MAX_URL_LENGTH || SiteOrigin.of(url) == null) return ""
        return runCatching { lookup(url) }.getOrDefault("")
    }

    fun close() { closed = true }
}

/** One registration per WebView, including pooled pages and private profiles. */
@SuppressLint("RequiresFeature")
class CosmeticFilterRuntime(
    private val view: WebView,
    private val filter: FilterController,
    private val sites: SiteSettingsRepository,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var script: ScriptHandler? = null
    private var fallback: Job? = null
    private var generation = 0L
    private var closed = false
    private var bridgeInstalled = false
    @Volatile var excludedOrigins: Set<String> = emptySet()
    private val bridge = CosmeticRuleBridge(::cssFor)
    private val source: String = com.mybrowser.core.BundledScripts.read(view.context, "cosmetic-style.js")

    // The bridge runs on WebView's JavaScript interface thread. StateFlow and the
    // immutable exclusion snapshot are safe there; no Activity/Compose state is read.
    private fun cssFor(url: String): String = filter.cosmeticCss(url,
        sites.get(url).filtering && SiteOrigin.of(url) !in excludedOrigins)

    fun install() {
        if (closed || bridgeInstalled) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        view.addJavascriptInterface(bridge, BRIDGE)
        bridgeInstalled = true
        installScript()
        if (script == null) {
            view.removeJavascriptInterface(BRIDGE)
            bridgeInstalled = false
        }
    }

    private fun installScript() {
        script = runCatching {
            // Synchronous, local-only lookup: an asynchronous message reply could
            // arrive after first paint. The loaded Rust engine already caches CSS.
            val program = "(function(){if(window!==window.top||!/^https?:$/.test(location.protocol))return;" +
                "try{var css=window.$BRIDGE.css(${JSONObject.quote(bridge.capability)},location.href);" +
                "$source(window,css);}catch(e){}})();"
            WebViewCompat.addDocumentStartJavaScript(view, program, setOf("*"))
        }.getOrNull()
    }

    fun onDocumentChanged(url: String) {
        ++generation
        fallback?.cancel()
        if (script == null) refresh(url)
    }

    /** Older providers retry at commit, before waiting for slow images/onload. */
    fun onPageCommitVisible(url: String) { if (script == null) refresh(url) }

    /** Also picks up a changed filter switch and repairs a missing popup bootstrap. */
    fun refresh(url: String) {
        if (closed) return
        fallback?.cancel()
        val epoch = generation
        fallback = scope.launch {
            val css = withContext(Dispatchers.Default) { cssFor(url) }
            if (!closed && generation == epoch && view.url == url) {
                // Never send the bridge capability through evaluateJavascript: this
                // document may already have replaced the public bridge object.
                view.evaluateJavascript("(function(){if(location.href!==${JSONObject.quote(url)})return;" +
                    "$source(window,${JSONObject.quote(css)});})();", null)
            }
        }
    }

    fun prepareForPopup() {
        script?.let { runCatching { it.remove() } }
        script = null
    }

    fun onPopupContentsAttached() {
        if (!closed && bridgeInstalled) installScript()
    }

    override fun close() {
        if (closed) return
        closed = true
        bridge.close()
        scope.cancel()
        prepareForPopup()
        if (bridgeInstalled) runCatching { view.removeJavascriptInterface(BRIDGE) }
        bridgeInstalled = false
    }

    private companion object { const val BRIDGE = "mybrowserCosmeticRules" }
}
