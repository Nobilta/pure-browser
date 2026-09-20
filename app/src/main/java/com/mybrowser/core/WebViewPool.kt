package com.mybrowser.core

import android.app.Activity
import android.view.ViewGroup
import android.webkit.WebView

/**
 * WebViews owned by one Activity. Resident tabs are cached by the tab host, not here.
 * Autofill services are obtained during construction; rebinding a MutableContextWrapper
 * cannot move them to a new Activity. Instances start with their final host and die with it.
 * Main-thread only. Fresh instances have never navigated when profiles/transports attach.
 */
class WebViewPool(private val activity: Activity) : AutoCloseable {
    private val instances = mutableSetOf<WebView>()
    private val transfers = mutableMapOf<WebView, Int>()
    private val pendingDisposal = mutableSetOf<WebView>()
    private var closed = false

    fun acquireFresh(): WebView {
        check(!closed) { "WebView host is closed" }
        val view = BrowserWebView(activity)
        instances += view
        try {
            WebViewConfig.apply(view)
        } catch (error: Throwable) {
            discard(view)
            throw error
        }
        return view
    }

    fun owns(view: WebView): Boolean = !closed && view in instances && view !in pendingDisposal

    /** Chromium must consume a pending transport before either native contents is destroyed. */
    fun holdForPopup(opener: WebView, popup: WebView): AutoCloseable {
        check(owns(opener) && owns(popup))
        val views = setOf(opener, popup)
        views.forEach { transfers[it] = (transfers[it] ?: 0) + 1 }
        var released = false
        return AutoCloseable {
            if (!released) {
                released = true
                views.forEach { view ->
                    val remaining = checkNotNull(transfers[view]) - 1
                    if (remaining > 0) transfers[view] = remaining
                    else {
                        transfers.remove(view)
                        if (pendingDisposal.remove(view)) destroy(view)
                    }
                }
            }
        }
    }

    fun discard(view: WebView) {
        if (view !in instances) return
        detach(view)
        // Disable callbacks now, even when native teardown must await a transport.
        runCatching { view.webChromeClient = null }
        runCatching { view.webViewClient = DetachedWebViewClient }
        if (view in transfers) pendingDisposal += view else destroy(view)
    }

    override fun close() {
        if (closed) return
        closed = true
        instances.toList().forEach(::discard)
    }

    private fun detach(view: WebView) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun destroy(view: WebView) {
        if (!instances.remove(view)) return
        pendingDisposal.remove(view)
        detach(view)
        runCatching { view.webChromeClient = null }
        runCatching { view.webViewClient = DetachedWebViewClient }
        runCatching { view.setOnLongClickListener(null) }
        runCatching { view.setFindListener(null) }
        runCatching { view.setOnScrollChangeListener(null) }
        runCatching { view.setDownloadListener(null) }
        runCatching { view.stopLoading() }
        runCatching { view.destroy() }
    }
}
