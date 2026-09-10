package com.mybrowser.core

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.util.Log
import android.webkit.HttpAuthHandler
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.SafeBrowsingResponse

/**
 * Navigation, errors, and the render-process lifecycle.
 *
 * Everything requiring UI (dialogs, prompts) is delegated upward through [Listener]
 * rather than handled here, so this class stays usable from a pooled WebView whose
 * host Activity may be swapped.
 */
@SuppressLint("MissingOnRenderProcessGone")
class BrowserWebViewClient(
    private val listener: Listener,
) : WebViewClient() {

    interface Listener {
        /** Returns false for a pooled instance that has already been detached. */
        fun isCurrentWebView(view: WebView): Boolean = true

        fun onPageStarted(url: String)
        fun onMainFrameNavigation(url: String) {}
        fun onPageFinished(url: String, canGoBack: Boolean, canGoForward: Boolean)
        fun onHistoryUpdated(url: String, canGoBack: Boolean, canGoForward: Boolean) {}
        fun onPageError(url: String, code: Int, description: String)

        /** A non-main-frame HTTP response with an error status (for developer tools). */
        fun onNetworkHttpError(
            request: WebResourceRequest,
            statusCode: Int,
            reasonPhrase: String?,
            responseHeaders: Map<String, String>?,
        ) {}

        /** A resource-level transport failure (for developer tools). */
        fun onNetworkResourceError(
            request: WebResourceRequest,
            code: Int,
            description: String,
        ) {}

        /** Navigation the WebView cannot perform; caller opens it elsewhere. */
        fun onExternalUrl(url: String): Boolean
        fun onUserScriptUrl(url: String): Boolean = false

        /**
         * Certificate problem. Implementations MUST NOT call proceed() without asking
         * the user; doing so accepts any MITM and is a real attack surface.
         */
        fun onSslError(handler: SslErrorHandler, error: SslError, url: String?)

        fun onHttpAuthRequest(handler: HttpAuthHandler, host: String, realm: String)

        /** Safe Browsing hit. The host must answer the callback exactly once. */
        fun onSafeBrowsingHit(
            view: WebView,
            request: WebResourceRequest,
            threatType: Int,
            callback: SafeBrowsingResponse,
        )

        /**
         * The renderer died. The WebView instance is unusable; the host must detach and
         * destroy it, then rebuild the tab.
         */
        fun onRenderProcessGone(webView: WebView, crashed: Boolean)

        /** Hook for the filter engine (step 5). Null means "don't block". */
        fun onInterceptRequest(request: WebResourceRequest): WebResourceResponse?
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest,
    ): Boolean {
        if (!listener.isCurrentWebView(view)) return true
        val url = request.url.toString()
        if (request.isForMainFrame && request.hasGesture() && listener.onUserScriptUrl(url)) return true

        // Let the WebView handle anything it can render itself.
        if (UrlUtils.isInternalScheme(url)) {
            if (request.isForMainFrame) listener.onMainFrameNavigation(url)
            return false
        }

        // Everything else is a handoff to another app. request.hasGesture() is the
        // signal that distinguishes a user tap from a page trying to launch an app on
        // its own; without it, an ad iframe can pull the user out of the browser.
        if (!request.hasGesture() && !request.isForMainFrame) {
            Log.i(TAG, "ignoring gestureless subframe scheme handoff: $url")
            return true
        }

        return listener.onExternalUrl(url)
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (!listener.isCurrentWebView(view)) return null
        // Called on a background thread, once per subresource. Anything expensive here
        // is multiplied by 50-200 per page load and blocks that request until it returns.
        return listener.onInterceptRequest(request)
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        if (!listener.isCurrentWebView(view)) return
        listener.onPageStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (!listener.isCurrentWebView(view)) return
        listener.onPageFinished(url, view.canGoBack(), view.canGoForward())
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        if (!listener.isCurrentWebView(view) || url.isNullOrBlank()) return
        listener.onHistoryUpdated(url, view.canGoBack(), view.canGoForward())
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (!listener.isCurrentWebView(view)) return
        listener.onNetworkResourceError(
            request,
            error.errorCode,
            error.description?.toString().orEmpty(),
        )
        // Subresource failures are normal and constant (blocked ads, dead trackers).
        // Only a main-frame failure is worth telling the user about.
        if (!request.isForMainFrame) return
        listener.onPageError(
            request.url.toString(),
            error.errorCode,
            error.description?.toString() ?: "",
        )
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (!listener.isCurrentWebView(view)) return
        listener.onNetworkHttpError(
            request,
            errorResponse.statusCode,
            errorResponse.reasonPhrase,
            errorResponse.responseHeaders,
        )
    }

    override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: SslError,
    ) {
        if (!listener.isCurrentWebView(view)) {
            runCatching { handler.cancel() }
            return
        }
        // Default WebView behaviour is cancel(). We delegate so the user can decide,
        // but the decision is never made here.
        listener.onSslError(handler, error, error.url)
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        host: String,
        realm: String,
    ) {
        if (!listener.isCurrentWebView(view)) {
            runCatching { handler.cancel() }
            return
        }
        listener.onHttpAuthRequest(handler, host, realm)
    }

    override fun onSafeBrowsingHit(
        view: WebView,
        request: WebResourceRequest,
        threatType: Int,
        callback: SafeBrowsingResponse,
    ) {
        if (!listener.isCurrentWebView(view)) {
            runCatching { callback.backToSafety(true) }
            return
        }
        listener.onSafeBrowsingHit(view, request, threatType, callback)
    }

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail,
    ): Boolean {
        // Returning true means "handled, do not kill the app". Without this override the
        // default is to terminate the whole process, which is the single largest crash
        // source in a WebView browser on Android 8+.
        Log.w(TAG, "render process gone, crashed=${detail.didCrash()}")
        listener.onRenderProcessGone(view, detail.didCrash())
        return true
    }

    private companion object {
        const val TAG = "BrowserWVClient"
    }
}
