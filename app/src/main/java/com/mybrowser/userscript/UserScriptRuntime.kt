package com.mybrowser.userscript

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A private capability per script grants only bounded storage writes, never Android APIs. */
@SuppressLint("RequiresFeature")
class UserScriptRuntime(
    private val view: WebView,
    private val store: UserScriptStore,
    private val scope: CoroutineScope,
    private val isAllowed: () -> Boolean,
) {
    private val bridgeName = "__pureScript_" + UUID.randomUUID().toString().replace("-", "")
    private val tokens = mutableMapOf<String, String>()
    private val identities = mutableMapOf<String, String>()
    private data class Registration(val script: InstalledUserScript, val handler: ScriptHandler)
    private val registrations = mutableMapOf<String, Registration>()
    private var observer: Job? = null
    private var bridgeInstalled = false
    private var closed = false
    private var awaitingPopupContents = false
    private var inFlight = 0
    val documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    private val messageSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    private val source by lazy { view.context.assets.open("userscript-runtime.js").bufferedReader().use { it.readText() } }

    fun install() {
        if (closed) return
        if (documentStartSupported && messageSupported) {
            WebViewCompat.addWebMessageListener(view, bridgeName, setOf("*")) { _, message, origin, mainFrame, reply ->
                val raw = message.data ?: return@addWebMessageListener
                if (closed || !isAllowed() || raw.length > 96 * 1024 || inFlight >= 32) return@addWebMessageListener
                val data = runCatching { JSONObject(raw) }.getOrNull() ?: return@addWebMessageListener
                val id = data.optString("id")
                val token = tokens[id] ?: return@addWebMessageListener
                if (data.optString("token") != token) return@addWebMessageListener
                val script = store.scripts.value.find { it.metadata.id == id && it.enabled } ?: return@addWebMessageListener
                val url = data.optString("url")
                if ((!mainFrame && script.metadata.noframes) || !script.metadata.matchesUrl(url) || !sameOrigin(origin, Uri.parse(url))) return@addWebMessageListener
                val requestId = data.optInt("requestId", -1)
                if (requestId < 0) return@addWebMessageListener
                inFlight++
                scope.launch {
                    try {
                        val ok = runCatching { store.changeValue(id, data.optString("operation"), data.optString("key"), data.opt("value")) }.getOrDefault(false)
                        if (!closed) reply(reply, id, token, requestId, ok)
                    } finally {
                        inFlight--
                    }
                }
            }
            bridgeInstalled = true
        }
        refresh()
        observer = scope.launch { store.scripts.collect { refresh() } }
    }

    /** Registration changes affect future documents. Already running page code needs a reload. */
    fun refresh() {
        if (closed || awaitingPopupContents) return
        val scripts = store.scripts.value.filter { it.enabled && it.metadata.supported && isAllowed() }
        val ids = scripts.map { it.metadata.id }.toSet()
        registrations.keys.filterNot { it in ids }.forEach { id ->
            registrations.remove(id)?.handler?.remove()
        }
        tokens.keys.retainAll(ids)
        identities.keys.retainAll(ids)
        scripts.forEach { script ->
            val id = script.metadata.id
            if (identities[id] != script.source) {
                tokens[id] = UUID.randomUUID().toString()
                identities[id] = script.source
            }
            if (documentStartSupported && (!script.metadata.needsStorage || bridgeInstalled)) {
                // A GM write updates one script's next-document values. Rebuilding every
                // installed program here copied and registered up to 12 MiB on each write.
                if (registrations[id]?.script != script) {
                    registrations.remove(id)?.handler?.remove()
                    registrations[id] = Registration(script,
                        WebViewCompat.addDocumentStartJavaScript(view, program(script), setOf("*")))
                }
            }
        }
    }

    /** Remove native script handles before Chromium replaces the popup's contents. */
    fun prepareForPopup() {
        awaitingPopupContents = true
        registrations.values.forEach { it.handler.remove() }
        registrations.clear()
    }

    fun onPopupContentsAttached() {
        awaitingPopupContents = false
        refresh()
    }

    /** Old WebViews get DOM-only main-frame scripts at page finish; no late privileged bridge. */
    fun onPageFinished(url: String) {
        if (closed || !isAllowed() || documentStartSupported) return
        store.scripts.value.filter { it.enabled && it.metadata.supported && !it.metadata.needsStorage && it.metadata.matchesUrl(url) }
            .forEach { view.evaluateJavascript(program(it), null) }
    }

    private fun program(script: InstalledUserScript): String {
        val metadata = script.metadata
        val config = JSONObject()
            .put("id", metadata.id).put("name", metadata.name).put("namespace", metadata.namespace)
            .put("version", metadata.version).put("description", metadata.description)
            .put("matches", JSONArray(metadata.matches)).put("includes", JSONArray(metadata.includes))
            .put("excludes", JSONArray(metadata.excludes)).put("excludeMatches", JSONArray(metadata.excludeMatches))
            .put("grants", JSONArray(metadata.grants)).put("noframes", metadata.noframes)
            .put("runAt", metadata.runAt).put("needsStorage", metadata.needsStorage)
            .put("values", script.values).put("token", tokens[metadata.id])
            .put("bridge", if (bridgeInstalled) bridgeName else "")
            .put("meta", script.source.substringBefore("// ==/UserScript==") + "// ==/UserScript==")
        val args = "GM, GM_info, GM_addStyle, GM_getValue, GM_setValue, GM_deleteValue, GM_listValues, GM_log, GM_openInTab, unsafeWindow"
        return source + "(" + config.toString() + ", function(api) {\nconst {" + args + "} = api;\n" +
            script.requiredCode.joinToString("\n;\n") + "\n;\n" + script.source +
            "\n});\n//# sourceURL=pure-userscript-" + metadata.id + ".js"
    }

    fun close() {
        if (closed) return
        closed = true
        observer?.cancel()
        registrations.values.forEach { it.handler.remove() }
        registrations.clear()
        if (bridgeInstalled) WebViewCompat.removeWebMessageListener(view, bridgeName)
        tokens.clear()
        identities.clear()
    }

    private fun reply(proxy: JavaScriptReplyProxy, id: String, token: String, request: Int, ok: Boolean) {
        runCatching { proxy.postMessage(JSONObject().put("id", id).put("token", token).put("requestId", request).put("ok", ok).toString()) }
    }

    private fun sameOrigin(first: Uri, second: Uri): Boolean {
        fun port(uri: Uri) = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
        return first.scheme in listOf("http", "https") && first.scheme == second.scheme &&
            first.host.equals(second.host, true) && port(first) == port(second)
    }
}
