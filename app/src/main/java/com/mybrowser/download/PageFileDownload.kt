package com.mybrowser.download

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Saving a file a page built in memory instead of serving from an address.
 *
 * A page that reads its own bytes, shows its own progress and then clicks an anchor at
 * `URL.createObjectURL(blob)` hands the download engine an address nothing can fetch: the data is in
 * the renderer and the `blob:` URL means nothing outside it. Chromium reports the download anyway, so
 * the app is asked for a file it can neither name nor get. Every browser answers this by asking the
 * page for the bytes, and this is that conversation — a message channel to the injected
 * `blob-download.js`, one slice at a time, each slice requested only after the previous one reached
 * the disk.
 *
 * The channel is visible to the page, so nothing it says is an instruction: the size and the name it
 * reports only fill a confirmation the user still has to accept, and the transfer can never write
 * more bytes than that confirmed size.
 *
 * Threading matters here. Every callback in this class arrives on the WebView's thread, so nothing in
 * it may touch the disk: [Sink.write] is documented as a hand-off, and the owner writes on its own
 * coroutine and calls [requestNext] when it is ready for more. That is also what makes the flow
 * self-limiting — the page cannot run ahead of the file the bytes are going into.
 *
 * Two steps, because the confirmation has to describe the file: [inspect] asks what an address holds
 * and reports [Listener.onOffer], then [start] moves the bytes into a sink the caller opened.
 */
/** A memory-backed file a page is offering, before any of it has been read. */
data class PageFileOffer(
    val url: String,
    val pageUrl: String,
    val mimeType: String?,
    val size: Long,
    val name: String?,
)

/**
 * Where each slice goes.
 *
 * Called on the WebView's thread, so an implementation must hand the array off — copy it into a
 * queue, or write it somewhere that does not block — and return promptly. The next slice is only
 * requested once the owner calls [PageFileSource.requestNext].
 */
fun interface PageFileSink {
    fun write(bytes: ByteArray)
}

/**
 * One inspect question, and what the request for it already knows.
 *
 * [probe] is the correlation id the page echoes back in its answer; it is what ties an answer to
 * the question that asked for it, since the page half is a script this side does not control.
 */
internal class PageFileQuestion(val url: String, val pageUrl: String, val mimeType: String?, val probe: Long)

/**
 * The inspect questions the page has not answered yet.
 *
 * Split out from the WebView-backed class so the bookkeeping can be pinned by a test: what matters
 * is that two questions asked before either answer arrives both survive, and that an answer only
 * ever resolves the question whose probe it carries.
 */
internal class PendingQuestions {
    private val open = LinkedHashMap<Long, PageFileQuestion>()

    /** Records a question. Bounded: a page can ask in a loop. */
    fun add(question: PageFileQuestion) {
        open[question.probe] = question
        while (open.size > MAX) open.remove(open.keys.first())
    }

    /** Removes and returns the question [probe] answers, or null when nothing asked about it. */
    fun resolve(probe: Long): PageFileQuestion? = open.remove(probe)

    fun forget(probe: Long) { open.remove(probe) }

    fun clear() = open.clear()

    fun owns(url: String): Boolean = open.values.any { it.url == url }

    internal companion object {
        /** How many unanswered questions one document may have in flight. */
        internal const val MAX = 8
    }
}

/**
 * The page side of a page-file transfer, as the download owner needs it.
 *
 * An interface rather than the WebView-backed class because the owner only ever pulls slices and asks
 * whether more exist; code that must not depend on a WebView — and the tests that replace this half
 * with a stand-in — are what it is here for.
 */
interface PageFileSource {
    /** Starts one transfer; false means nothing was handed over. */
    fun start(id: Long, url: String, size: Long, sink: PageFileSink): Boolean

    /** Asks for the slice at [written]. False means the source has nothing more for this transfer. */
    fun requestNext(id: Long, written: Long): Boolean

    /** Whether task [id] still has a transfer here. Once false, no further bytes exist for it. */
    fun isTransferring(id: Long): Boolean

    /** Stops whatever is in flight. The owner keeps whatever bytes already reached its sink. */
    fun cancel()
}

@SuppressLint("RequiresFeature")
class PageFileDownload(
    val owningView: WebView,
) : PageFileSource {
    private val view: WebView get() = owningView

    interface Listener {
        /** The page's half is loaded and the app can answer on its channel. */
        fun onReady()

        /** The page answered about an address the user asked for. */
        fun onOffer(offer: PageFileOffer)

        /**
         * The page does not hold the file it was asked about — usually because the address was
         * released before the download event arrived.
         */
        fun onUnavailable()

        /**
         * The transfer stopped before the promised bytes were all handed over. [reason] is a short
         * token such as `unavailable` or `error`.
         */
        fun onFailed(id: Long, reason: String)
    }

    var listener: Listener? = null

    private var closed = false
    private var installed = false
    private var script: ScriptHandler? = null
    private var reply: JavaScriptReplyProxy? = null
    private var nextProbe = 1L

    /**
     * The addresses the page reported files at and the user has not resolved yet.
     *
     * A page can trigger several blob downloads in a row — a gallery saving three images, a script
     * exporting twice — and each one raises its own confirmation. Remembering only the last left the
     * first dialog unable to find its owner again, so confirming it failed and the file was simply
     * not saved. The set is bounded because a page can fire these in a loop.
     */
    private val offered = LinkedHashSet<String>()
    private var transfer: Transfer? = null

    /**
     * The questions the page has not answered yet, keyed by the probe that names them.
     *
     * A map rather than a single slot: a page can fire two blob downloads before either answer
     * arrives, and one slot meant the second question replaced the first. The first answer then
     * matched nothing and was dropped, so the user saw only the later download and the earlier file
     * was silently not saved.
     */
    private val questions = PendingQuestions()

    /** One in-flight transfer; [id] is the download task the bytes belong to. */
    private class Transfer(
        val id: Long,
        val url: String,
        val size: Long,
        val sink: PageFileSink,
    ) {
        var written = 0L
        var chunkPending = false
    }

    /** Whether [candidate] is the WebView this channel is attached to. */
    fun isOwnedBy(candidate: WebView?): Boolean = candidate != null && candidate === owningView

    /**
     * Whether this channel is the one that reported, or is servicing, [url].
     *
     * The remembered offer is what covers the gap between the page answering and the user confirming:
     * at that point the transfer has not started, so neither the open questions nor [transfer] hold
     * the URL.
     */
    fun owns(url: String): Boolean = questions.owns(url) || url in offered || transfer?.url == url

    /**
     * Attaches the channel while the WebView is configured, like the app's other channels and for the
     * same reason: attaching a listener to a document that has already settled, and then changing that
     * document, has taken the process down inside `libwebviewchromium`.
     *
     * The reply proxy is kept from the first message on the channel: AndroidX reuses one proxy per
     * page, and holding it is what lets the app answer a slice after the message that carried it —
     * the same pattern the media bridge uses to push commands back to a page.
     */
    fun install(): Boolean {
        if (closed || installed) return installed
        val supported = runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_ARRAY_BUFFER)
        }.getOrDefault(false)
        if (!supported) return false
        return runCatching {
            WebViewCompat.addWebMessageListener(view, BRIDGE, setOf("*"),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        source: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy,
                    ) {
                        // The sender is the WebView this channel is attached to, one channel per
                        // WebView. It is deliberately *not* filtered on which tab is on screen:
                        // switching tabs parks the page but does not stop its JavaScript, and
                        // dropping its slices would strand the transfer in a wait that nothing
                        // can end. A slice from a background page is still that page’s data.
                        if (closed || source !== view || !isMainFrame) return
                        reply = replyProxy
                        if (message.type == WebMessageCompat.TYPE_ARRAY_BUFFER) acceptBytes(message.arrayBuffer)
                        else acceptControl(message.data)
                    }
                })
            installed = true
            true
        }.getOrElse { false }
    }

    /** Loads the page-side half into every document; false when this WebView cannot run one. */
    fun startScript(): Boolean {
        if (closed) return false
        return runCatching {
            val source = view.context.assets.open(SCRIPT).bufferedReader().use { it.readText() }
            script = WebViewCompat.addDocumentStartJavaScript(view, "$source(window);", setOf("*"))
            true
        }.getOrElse { false }
    }

    /**
     * Ends everything the previous document was holding.
     *
     * Called when a new main frame starts. A blob address, the question about it, and the transfer
     * reading from it all belong to the document that produced them, so none of the three can
     * survive a navigation: the new document has no Blob behind that address, and a transfer left
     * marked live would keep the writer polling for slices that can never arrive. Cancelling it is
     * what lets the task reach its failure cleanup instead of waiting forever.
     */
    fun onDocumentChanged() {
        questions.clear()
        offered.clear()
        cancel()
    }

    /** Drops the page half while Chromium replaces a popup’s contents. */
    fun prepareForPopup() {
        script?.let { runCatching { it.remove() } }
        script = null
    }

    fun onPopupContentsAttached() {
        if (closed) return
        startScript()
    }

    fun close() {
        if (closed) return
        closed = true
        cancel()
        questions.clear()
        script?.let { runCatching { it.remove() } }
        script = null
        reply = null
        if (installed) runCatching { WebViewCompat.removeWebMessageListener(view, BRIDGE) }
    }

    /**
     * Asks the page what it holds at [url].
     *
     * False means the question could not even be asked — no channel, or a transfer already running —
     * and the caller should treat the address as one the browser cannot save at all. A question that
     * was asked is answered through [Listener.onOffer] or [Listener.onUnavailable].
     */
    fun inspect(url: String, pageUrl: String, mimeType: String?): Boolean {
        if (closed || !installed || transfer != null) return false
        val probe = nextProbe++
        questions.add(PageFileQuestion(url, pageUrl, mimeType, probe))
        if (!post(JSONObject().put("type", "inspect").put("url", url).put("probe", probe))) {
            questions.forget(probe)
            return false
        }
        return true
    }

    /**
     * Asks the page for the slice that starts at [written].
     *
     * Called only once the preceding slice has been written, which is what keeps the page from
     * running ahead and what makes the whole file never exist twice. Returns false when the transfer
     * is no longer there to continue.
     */
    override fun requestNext(id: Long, written: Long): Boolean {
        val current = transfer ?: return false
        if (current.id != id) return false
        // A request for bytes already written would re-send them; the pump never makes one, and
        // refusing here is what keeps a duplicated request from corrupting the file.
        if (written != current.written) return false
        return post(JSONObject().put("type", "advance").put("transfer", id).put("written", written))
    }

    /**
     * Moves [url]'s bytes into [sink] for the download task [id].
     *
     * False means nothing was started; a sink that then fails is reported through
     * [Listener.onFailed] instead.
     */
    override fun start(id: Long, url: String, size: Long, sink: PageFileSink): Boolean {
        if (closed || !installed || transfer != null) return false
        val current = Transfer(id, url, size.coerceAtLeast(0L), sink)
        transfer = current
        // The confirmation is answered and the transfer carries the address from here, so the
        // unconfirmed entry goes.
        offered.remove(url)
        if (!post(JSONObject().put("type", "start").put("url", url).put("transfer", id))) {
            transfer = null
            return false
        }
        return true
    }

    /** Whether task [id] still has a transfer here. Once false, no further bytes exist for it. */
    override fun isTransferring(id: Long): Boolean = transfer?.id == id

    /** Stops whatever is in flight. The task keeps whatever bytes already reached its sink. */
    override fun cancel() {
        transfer?.let { current ->
            transfer = null
            post(JSONObject().put("type", "cancel").put("transfer", current.id))
        }
    }

    private fun acceptControl(raw: String?) {
        if (raw == null || raw.length > MAX_CONTROL_MESSAGE) return
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (json.optString("type")) {
            "hello" -> listener?.onReady()
            "info" -> {
                val question = correlated(json) ?: return
                val size = json.optLong("size", -1L)
                if (size < 0) return
                // Bounded: a page can raise these in a loop, and each entry only lives until its
                // confirmation is answered.
                offered.add(question.url)
                while (offered.size > MAX_OFFERED) offered.remove(offered.first())
                listener?.onOffer(
                    PageFileOffer(question.url, question.pageUrl, question.mimeType, size, name(json.optString("name"))),
                )
            }
            "unknown" -> {
                correlated(json) ?: return
                listener?.onUnavailable()
            }
            "begin" -> {
                val current = transfer ?: return
                if (json.optLong("transfer", -1L) != current.id) return
                // A size that differs from the one the user confirmed is refused: they agreed to a
                // number, and continuing would write a different file than the one described. The
                // first slice is not requested here — the owner asks once its file is open.
                if (json.optLong("size", -1L) != current.size) fail(current, "size-changed")
            }
            "chunk" -> {
                val current = transfer ?: return
                if (json.optLong("transfer", -1L) != current.id) return
                // The slice's bytes follow this description in order; the offset check is what keeps
                // a reordered or replayed message from writing bytes at the wrong place.
                if (json.optLong("offset", -1L) != current.written) { fail(current, "out-of-order"); return }
                current.chunkPending = true
            }
            "end" -> {
                val current = transfer ?: return
                if (json.optLong("transfer", -1L) != current.id) return
                // The page has nothing more to send. A short file is a failure; a complete one ends
                // the transfer here, which is what makes the owner's next request answer false and
                // stop the pump.
                if (current.written < current.size) {
                    fail(current, "short")
                } else {
                    transfer = null
                    // Only this transfer's own entry: another address may still have its own
                    // unanswered confirmation on screen.
                    offered.remove(current.url)
                }
            }
            "error" -> {
                transfer?.let {
                    if (json.optLong("transfer", -1L) == it.id) fail(it, json.optString("reason", "error"))
                }
            }
        }
    }

    /**
     * The open question, when [json] answers it; null for a reply about anything else.
     *
     * A page can send whatever it likes on this channel, so an id it chose is never treated as an
     * answer to a question that is not open.
     */
    private fun correlated(json: JSONObject): PageFileQuestion? =
        questions.resolve(json.optLong("probe", -1L))

    private fun acceptBytes(bytes: ByteArray?) {
        val current = transfer ?: return
        if (!current.chunkPending || bytes == null) return
        current.chunkPending = false
        val remaining = current.size - current.written
        if (remaining <= 0L) return
        // A page can send more than it declared; only the promised bytes reach the sink, so the size
        // the user agreed to is the size that gets written.
        val accepted = if (bytes.size.toLong() > remaining) bytes.copyOf(remaining.toInt()) else bytes
        current.written += accepted.size
        // The hand-off itself: the sink must not write to disk on this thread. A sink that throws is
        // reporting that it cannot accept the bytes, so the transfer stops rather than silently
        // dropping them.
        runCatching { current.sink.write(accepted) }.onFailure { fail(current, "write") }
    }

    private fun fail(current: Transfer, reason: String) {
        transfer = null
        offered.remove(current.url)
        post(JSONObject().put("type", "cancel").put("transfer", current.id))
        listener?.onFailed(current.id, reason)
    }

    private fun post(payload: JSONObject): Boolean {
        val channel = reply ?: return false
        return runCatching { channel.postMessage(payload.toString()); true }.getOrDefault(false)
    }

    private fun name(raw: String): String? = raw.trim()
        .takeIf { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH && it.none(Char::isISOControl) }

    private companion object {
        const val SCRIPT = "blob-download.js"
        const val BRIDGE = "mybrowserPageFile"
        const val MAX_CONTROL_MESSAGE = 4 * 1024
        const val MAX_NAME_LENGTH = 127

        /** How many unconfirmed offers one document may hold while their dialogs are open. */
        const val MAX_OFFERED = 8

    }
}
