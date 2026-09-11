package com.mybrowser.core

import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView

/**
 * Pooled WebView instances.
 *
 * Two problems this solves.
 *
 * First, cost. The first `WebView(context)` in a process pays several hundred
 * milliseconds of Chromium initialisation, and each live instance holds tens of MB.
 * Ten tabs of ten WebViews is an OOM, so tabs must be data with a small pool of real
 * views behind them.
 *
 * Second, context. A WebView needs an Activity Context for dialogs, fullscreen video
 * and file pickers, but holding one pins that Activity and prevents both pre-warming
 * before an Activity exists and reuse across Activity recreation. The fix is
 * [MutableContextWrapper]: construct against the Application context, then swap in the
 * Activity right before the view is attached. This is the standard technique for
 * pre-created and pooled WebViews.
 *
 * Main-thread only. WebView requires it, so no locking here is deliberate rather than
 * an oversight.
 */
class WebViewPool(
    private val appContext: Context,
    /**
     * Admission limit when returning an instance to the idle pool. Fresh acquisition
     * can exceed this count; each window separately bounds its retained page instances.
     */
    private val maxSize: Int = 4,
) {

    private val idle = ArrayDeque<WebView>()
    private val active = mutableSetOf<WebView>()
    private val instances = mutableSetOf<WebView>()

    /** Live instance count, for logging and idle admission. */
    private var created = 0

    /**
     * Builds one instance ahead of time so the first navigation does not pay Chromium
     * startup. Call on the main thread when prewarming fits the startup budget.
     */
    fun preWarm() {
        if (created > 0 || idle.isNotEmpty()) return
        val webView = create()
        idle.addLast(webView)
        Log.d(TAG, "pre-warmed one instance")
    }

    /**
     * A WebView bound to [activity], ready to attach.
     *
     * Reuses an idle instance when one exists. The returned view is not yet in any
     * hierarchy; the caller adds it.
     */
    fun acquire(activity: Activity): WebView {
        val webView = idle.removeFirstOrNull() ?: create()
        bindTo(webView, activity)
        active += webView
        return webView
    }

    /**
     * Creates a never-before-used instance instead of reusing an idle one.
     *
     * WebView profiles are attached before the first navigation and cannot be changed
     * after an instance has been used. Incognito transitions therefore need this stricter
     * acquire path; reusing a pooled normal-profile WebView would silently downgrade the
     * session to shared storage. Popup transports also require an instance that has
     * never navigated; even the about:blank reset of an idle view is too late.
     */
    fun acquireFresh(activity: Activity): WebView {
        val webView = create()
        bindTo(webView, activity)
        active += webView
        return webView
    }

    /**
     * Returns a WebView to the pool.
     *
     * The order of operations matters: detach from the parent before touching anything
     * else, reset page state, then drop the Activity reference by rebinding to the
     * Application context. Skipping that last step leaks the Activity for as long as the
     * instance stays pooled.
     */
    fun release(webView: WebView) {
        if (webView !in instances) return
        active -= webView

        detach(webView)
        reset(webView)
        bindTo(webView, appContext)

        // active already excludes [webView] here. Count the instance we are about to put
        // back; the old comparison allowed one extra WebView after acquireFresh() during
        // an incognito transition.
        if (idle.size + active.size + 1 > maxSize) {
            destroy(webView)
        } else {
            idle.addLast(webView)
        }
    }

    /**
     * Permanently discards an instance whose renderer died or whose profile must not be
     * reused (for example when leaving incognito). A crashed WebView is never returned to
     * the idle queue: Chromium marks its renderer unusable and a later acquire would crash
     * again in a loop.
     */
    fun discard(webView: WebView) {
        if (webView !in instances) return
        active -= webView
        idle.remove(webView)
        destroy(webView)
    }

    /**
     * Frees idle instances under memory pressure. Called from
     * Application.onTrimMemory; active instances are left alone because destroying the
     * visible page would be worse than the memory it costs.
     */
    fun trim() {
        val n = idle.size
        while (idle.isNotEmpty()) {
            destroy(idle.removeFirst())
        }
        if (n > 0) Log.d(TAG, "trimmed $n idle instances")
    }

    /** Full teardown. */
    fun destroyAll() {
        idle.forEach { destroy(it) }
        idle.clear()
        active.toList().forEach { destroy(it) }
        active.clear()
    }

    // --- internals ---

    private fun create(): WebView {
        // Wrapped from the start so the base can be swapped later without recreating.
        val webView = BrowserWebView(MutableContextWrapper(appContext))
        WebViewConfig.apply(webView)
        instances += webView
        created++
        Log.d(TAG, "created instance #$created")
        return webView
    }

    private fun bindTo(webView: WebView, context: Context) {
        (webView.context as? MutableContextWrapper)?.baseContext = context
    }

    private fun detach(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
    }

    private fun reset(webView: WebView) {
        (webView as? BrowserWebView)?.keepMediaOnWindowHidden = false
        webView.setOnScrollChangeListener(null)
        webView.setOnLongClickListener(null)
        webView.setFindListener(null)
        // Cut the host callbacks before the asynchronous blank navigation. Otherwise a
        // pooled view can deliver onPageStarted/onPageFinished to an Activity that has
        // already released it (especially during renderer replacement).
        runCatching { webView.webChromeClient = null }
        runCatching { webView.webViewClient = DetachedWebViewClient }
        runCatching { webView.stopLoading() }
        // about:blank before clearHistory: clearing while a page is loaded leaves the
        // current entry behind, so the next restore would see stale history.
        runCatching { webView.loadUrl(ABOUT_BLANK) }
        runCatching { webView.clearHistory() }
        runCatching { webView.onFocusChangeListener = null }
        runCatching { webView.setDownloadListener(null) }
    }

    private fun destroy(webView: WebView) {
        if (!instances.remove(webView)) return
        // Mandatory order. destroy() on a still-attached WebView either leaks the view
        // hierarchy or crashes in the renderer teardown.
        detach(webView)
        // Clear clients before the blanking navigation for the same reason as reset().
        // A crashed renderer can still queue one last callback, and it must not reach the
        // Activity that is discarding this instance.
        runCatching { webView.webChromeClient = null }
        runCatching { webView.webViewClient = DetachedWebViewClient }
        runCatching { webView.setOnLongClickListener(null) }
        runCatching { webView.setFindListener(null) }
        runCatching { webView.setOnScrollChangeListener(null) }
        runCatching { webView.setDownloadListener(null) }
        // A renderer that has already gone can reject stop/load calls.  Teardown must be
        // best-effort so the recovery path itself cannot crash the process while discarding
        // the broken instance.
        runCatching { webView.stopLoading() }
        runCatching { webView.loadUrl(ABOUT_BLANK) }
        runCatching { webView.destroy() }
        created--
    }

    private companion object {
        const val TAG = "WebViewPool"
        const val ABOUT_BLANK = "about:blank"
    }
}
