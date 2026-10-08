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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /**
     * Whether this WebView belongs to a private session.
     *
     * Scripts run in both modes — an extension the user installed is theirs, not the page's, and
     * turning it off made incognito useless for the scripts people actually use private mode for.
     * What the mode changes is where a script's own data may live: GM values are held in memory for
     * the session and never written to the file a normal session would read.
     */
    private val isPrivate: () -> Boolean,
    private val isCurrent: () -> Boolean = { true },
) {
    private val bridgeName = "__pureScript_" + UUID.randomUUID().toString().replace("-", "")
    private val tokens = mutableMapOf<String, String>()
    private val identities = mutableMapOf<String, String>()
    private data class Registration(val script: InstalledUserScript, val handler: ScriptHandler)
    private val registrations = mutableMapOf<String, Registration>()
    private var observer: Job? = null
    /** Per script, the value text its current registration was built from. */
    private val builtValues = mutableMapOf<String, String>()
    private var bridgeInstalled = false
    private var closed = false
    private var awaitingPopupContents = false
    /**
     * The ids registered for the document currently on screen.
     *
     * Registrations apply to *future* documents: installing or enabling a script does not put it
     * into the page the user is looking at. The menu has to describe the page, not the store, or it
     * claims a script is running before the reload that would start it. Only the two document
     * transitions below change this set.
     */
    private var runningIds: Set<String> = emptySet()
    /**
     * The ids that were registered when the current document started loading.
     *
     * A registration only applies to documents that load after it, so the set that can have reached
     * this document is the one that existed at [onDocumentChanged], not the set that exists when it
     * finishes. Installing or enabling a script mid-load adds a registration this document never
     * received, and reading the map at finish used to report it as having run here.
     */
    private var documentIds: Set<String> = emptySet()
    private var inFlight = 0

    /**
     * What this document got. Registered scripts are known only at registration time here, which is
     * exactly the moment that decides it, and the URL is captured with them so a status can never be
     * read against a different page than the one it describes.
     */
    private val _status = MutableStateFlow(UserScriptStatus())
    val status: StateFlow<UserScriptStatus> = _status.asStateFlow()
    val documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    private val messageSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    private val source by lazy { view.context.assets.open("userscript-runtime.js").bufferedReader().use { it.readText() } }

    fun install() {
        if (closed) return
        if (documentStartSupported && messageSupported) {
            WebViewCompat.addWebMessageListener(view, bridgeName, setOf("*")) { _, message, origin, mainFrame, reply ->
                val raw = message.data ?: return@addWebMessageListener
                if (closed || !isCurrent() || raw.length > 96 * 1024) return@addWebMessageListener
                val data = runCatching { JSONObject(raw) }.getOrNull() ?: return@addWebMessageListener
                val id = data.optString("id")
                // Nothing is answered before the token authenticates it: an unauthenticated message
                // gets silence rather than a reply a page could use to probe the bridge.
                val token = tokens[id] ?: return@addWebMessageListener
                if (data.optString("token") != token) return@addWebMessageListener
                val requestId = data.optInt("requestId", -1)
                if (requestId < 0) return@addWebMessageListener
                // Past the token, every outcome is answered. A refusal used to be dropped on the
                // floor, which left the script's promise pending until its own 15-second timeout —
                // the script could not tell "denied" from "the browser is busy". The reply carries
                // the id and request id only, so it grants nothing the request did not already have.
                val script = store.scripts.value.find { it.metadata.id == id && it.enabled }
                val url = data.optString("url")
                val accepted = script != null && inFlight < 32 &&
                    !(!mainFrame && script.metadata.noframes) && script.metadata.matchesUrl(url) &&
                    sameOrigin(origin, Uri.parse(url))
                if (!accepted) {
                    reply(reply, id, requestId, false)
                    return@addWebMessageListener
                }
                // Read here, not inside the coroutine: a private session can be left between the
                // message being accepted and the write running, and re-reading the mode then would
                // route a private script's value to the file the next normal session reads.
                val private = isPrivate()
                inFlight++
                scope.launch {
                    try {
                        val ok = runCatching {
                            store.changeValue(id, data.optString("operation"), data.optString("key"), data.opt("value"),
                                private = private)
                        }.getOrDefault(false)
                        if (!closed) reply(reply, id, requestId, ok)
                    } finally {
                        inFlight--
                    }
                }
            }
            bridgeInstalled = true
        }
        refresh()
        // Two signals, one rebuild: the script list changes when a script is installed, enabled or
        // its source edited, and the program revision changes when the values a script is injected
        // with do — including a private GM write, which leaves every script equal.
        observer = scope.launch {
            kotlinx.coroutines.flow.combine(
                store.scripts,
                store.programs,
            ) { _: List<InstalledUserScript>, _: Long -> Unit }.collect { refresh() }
        }
    }

    /** Registration changes affect future documents. Already running page code needs a reload. */
    fun refresh() {
        if (closed || awaitingPopupContents) return
        val scripts = store.scripts.value.filter { it.enabled && it.metadata.supported }
        // What is *installed* changed, not what the document *ran*: the status keeps describing the
        // page on screen, which is why it is derived from [runningIds] rather than from this list.
        publishStatus(_status.value.url.ifEmpty { view.url.orEmpty() })
        val ids = scripts.map { it.metadata.id }.toSet()
        builtValues.keys.retainAll(ids)
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
            if (runnableOn(script.metadata.needsStorage, documentStartSupported, bridgeInstalled)) {
                // A GM write updates one script's next-document values. Rebuilding every
                // installed program here copied and registered up to 12 MiB on each write, so the
                // change is detected per script: a private write leaves the script itself equal and
                // shows up only in the values it is injected with, which is why those are compared
                // too rather than trusting the script list to have changed.
                val values = store.valuesFor(id, isPrivate())
                if (registrations[id]?.script != script || builtValues[id] != values) {
                    builtValues[id] = values
                    registrations.remove(id)?.handler?.remove()
                    registrations[id] = Registration(script,
                        WebViewCompat.addDocumentStartJavaScript(view, program(script), setOf("*")))
                }
            }
        }
    }

    /**
     * Publishes what the document at [url] got, for a UI that otherwise has nothing to show.
     *
     * "Ran here" is the id set recorded for the document on screen, so a script installed or
     * enabled since that document loaded is counted as not matching instead. The counts below come
     * from what is installed right now, which is what those two describe.
     */
    private fun publishStatus(url: String) {
        val installed = store.scripts.value.filter { it.enabled && it.metadata.supported }
        // A document the runtime cannot fully serve must not be told a script ran. The gate is
        // shared with [refresh] rather than restated here, because the two disagreeing is what put a
        // never-injected script in the not-matching count.
        fun runnable(script: InstalledUserScript): Boolean =
            runnableOn(script.metadata.needsStorage, documentStartSupported, bridgeInstalled)
        // Each installed script lands in exactly one bucket: it either cannot run at all, it ran
        // here, or it was not covered by this page's rules.
        val ready = installed.filter { runnable(it) }
        // Only enabled scripts still count: a script disabled after this document ran is no longer
        // something the browser is doing, even though the page still holds its code.
        val active = ready.filter { it.metadata.id in runningIds }
        val running = active.map { it.metadata.name }
        val status = UserScriptStatus(
            running = running,
            runningIds = active.mapTo(mutableSetOf()) { it.metadata.id },
            // Counted from the ids, not the names: two scripts may share a name.
            installedNotMatching = (ready.size - active.size).coerceAtLeast(0),
            // Every enable path already ANDs `supported` (see UserScriptStore), so an enabled
            // script is always a supported one; what can still hold it back is the runtime.
            unsupportedRuntime = installed.size - ready.size,
            incognito = isPrivate(),
            url = url,
        )
        // Only the transitions are worth a recomposition: the observer fires on every script edit,
        // and assigning an equal value would push a new state through Compose each time.
        if (status != _status.value) _status.value = status
    }

    /**
     * Remove native script handles before Chromium replaces the popup's contents.
     *
     * The window between this and [onPopupContentsAttached] is when the transport is in flight, and
     * registering a document-start script there would have it discarded by the replacement anyway.
     * If the transfer is abandoned the flag stays set, which is harmless: the only other caller is
     * [install], and a runtime in that state is one whose WebView is being torn down, where [close]
     * has already run and every entry point checks [closed] first.
     */
    fun prepareForPopup() {
        awaitingPopupContents = true
        registrations.values.forEach { it.handler.remove() }
        registrations.clear()
    }

    fun onPopupContentsAttached() {
        awaitingPopupContents = false
        refresh()
        // The popup document begins when Chromium attaches its contents, which is here: the
        // registrations just rebuilt are the ones it can receive, and the load finished later must
        // not credit itself with a script registered after this. The status URL is the popup's own.
        documentIds = registrations.values.map { it.script.metadata.id }.toSet()
        runningIds = emptySet()
        publishStatus(view.url.orEmpty())
    }

    /**
     * The document at [url] has loaded: record what it got, and inject the fallback when needed.
     *
     * This is the one place the running set is decided, because it is the only moment the document
     * on screen and the registrations for it are both known. Document-start registrations apply to
     * documents that load *after* them, so re-reading the store later would report scripts this page
     * never received.
     *
     * Old WebViews get DOM-only main-frame scripts here instead; no late privileged bridge.
     */
    fun onPageFinished(url: String) {
        if (closed || !isCurrent()) return
        runningIds = if (documentStartSupported) {
            // What was actually handed to the WebView when this document started, narrowed to the
            // scripts this page is covered by.
            registeredFor(registrations.values.map { it.script.metadata.id to it.script.metadata.matchesUrl(url) },
                documentIds)
        } else {
            val matching = store.scripts.value.filter {
                it.enabled && it.metadata.supported && !it.metadata.needsStorage && it.metadata.matchesUrl(url)
            }
            matching.forEach { view.evaluateJavascript(program(it), null) }
            matching.map { it.metadata.id }.toSet()
        }
        publishStatus(url)
    }

    /**
     * A new document is loading: record what it can receive, and clear what it has not.
     *
     * Called from the start of the load, which is the only moment the answer is knowable — a
     * registration made after this applies to a later document. Clearing the recorded set is what
     * keeps the previous page’s scripts from being reported for the page now loading.
     */
    fun onDocumentChanged(url: String) {
        if (closed) return
        documentIds = registrations.values.map { it.script.metadata.id }.toSet()
        runningIds = emptySet()
        publishStatus(url)
    }

    private fun program(script: InstalledUserScript): String {
        val metadata = script.metadata
        val config = JSONObject()
            .put("id", metadata.id).put("name", metadata.name).put("namespace", metadata.namespace)
            .put("version", metadata.version).put("description", metadata.description)
            .put("browserVersion", runCatching { view.context.packageManager.getPackageInfo(view.context.packageName, 0).versionName }.getOrNull().orEmpty())
            .put("matches", JSONArray(metadata.matches)).put("includes", JSONArray(metadata.includes))
            .put("excludes", JSONArray(metadata.excludes)).put("excludeMatches", JSONArray(metadata.excludeMatches))
            .put("grants", JSONArray(metadata.grants)).put("noframes", metadata.noframes)
            .put("runAt", metadata.runAt).put("needsStorage", metadata.needsStorage)
            .put("values", store.valuesFor(metadata.id, isPrivate())).put("token", tokens[metadata.id])
            .put("resources", JSONObject().also { obj -> script.resources.forEach { (name, resource) -> obj.put(name, resource.runtimeJson()) } })
            .put("bridge", if (bridgeInstalled) bridgeName else "")
            .put("meta", script.source.substringBefore("// ==/UserScript==") + "// ==/UserScript==")
        val args = "GM, GM_info, GM_addStyle, GM_getValue, GM_setValue, GM_deleteValue, GM_listValues, GM_log, GM_openInTab, GM_getResourceText, GM_getResourceURL, unsafeWindow"
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

    /**
     * Replies carry the script id and the request id only.
     *
     * The token authenticates messages *from* a script and must never travel back out. The
     * bridge object is exposed to the page's own scripts, which can attach their own
     * `message` listener, so echoing the token let a page read it and then replay it to write
     * that script's private storage. The runtime matches replies on the two ids instead — both
     * are already unique per document. A page can still forge a reply and settle a pending
     * write early, which costs the script a misleading promise but grants no storage access,
     * because every inbound message is still checked against the token.
     */
    private fun reply(proxy: JavaScriptReplyProxy, id: String, request: Int, ok: Boolean) {
        runCatching { proxy.postMessage(JSONObject().put("id", id).put("requestId", request).put("ok", ok).toString()) }
    }

    private fun sameOrigin(first: Uri, second: Uri): Boolean {
        fun port(uri: Uri) = if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80
        return first.scheme in listOf("http", "https") && first.scheme == second.scheme &&
            first.host.equals(second.host, true) && port(first) == port(second)
    }

    internal companion object {
        /**
         * Whether a script can be handed to a document at all on this WebView.
         *
         * The single gate: [refresh] decides what to register with it, and [publishStatus] decides
         * what to count as held back with it. Stated twice, the two drifted, and a privileged script
         * on a WebView with document-start but no message bridge was never injected yet reported as
         * merely not matching the page.
         */
        internal fun runnableOn(needsStorage: Boolean, documentStart: Boolean, bridge: Boolean): Boolean =
            documentStart && (!needsStorage || bridge)

        /**
         * Which registered scripts this document can actually have received.
         *
         * A registration applies to documents that load after it, so one created while this page was
         * loading is not in [snapshot] and must not be reported as having run here. [candidates] is
         * each registered id with whether the page is covered by its rules.
         */
        internal fun registeredFor(candidates: List<Pair<String, Boolean>>, snapshot: Set<String>): Set<String> =
            candidates.filter { (id, matches) -> matches && id in snapshot }.mapTo(mutableSetOf()) { it.first }
    }
}
