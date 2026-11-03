package com.mybrowser.devtools

import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns one [DevToolsSession] per WebView (that is, per live tab).
 *
 * Enhanced capture is deliberately not persisted: a fresh WebView starts with a fresh,
 * disabled session. Detaching a tab closes its wrappers, listeners and in-memory records so
 * a later WebView can never inherit another document's data.
 */
internal class DevToolsController {
    private val sessions = ConcurrentHashMap<WebView, DevToolsSession>()

    fun attach(view: WebView): DevToolsSession {
        sessions.remove(view)?.close()
        return DevToolsSession(view).also { sessions[view] = it }
    }

    fun sessionFor(view: WebView?): DevToolsSession? = view?.let(sessions::get)

    fun detach(view: WebView) {
        sessions.remove(view)?.close()
    }

    fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }
}
