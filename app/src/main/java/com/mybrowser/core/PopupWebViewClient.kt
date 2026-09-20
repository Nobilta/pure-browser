package com.mybrowser.core

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.*

/**
 * A hidden but live member of a JavaScript window group. It can finish HTTP(S)
 * navigation and fetches, but cannot raise UI, launch apps or accept unsafe certificates.
 * Its listener owns only that page's state, never the selected tab's state.
 */
class PopupWebViewClient(private val listener: Listener) : WebViewClient() {
    interface Listener {
        fun isAlive(): Boolean
        fun onNavigation(url: String)
        fun onStarted(url: String)
        fun onFinished(url: String)
        fun onHistory(url: String)
        fun onError(url: String, code: Int, description: String)
        fun onIntercept(request: WebResourceRequest): WebResourceResponse?
        fun onGone(view: WebView, crashed: Boolean)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!listener.isAlive() || !UrlUtils.isInternalScheme(request.url.toString())) return true
        if (request.isForMainFrame) listener.onNavigation(request.url.toString())
        return false
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        if (listener.isAlive()) listener.onIntercept(request) else null

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        if (listener.isAlive()) listener.onStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (listener.isAlive()) listener.onFinished(url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        if (listener.isAlive() && !url.isNullOrBlank()) listener.onHistory(url)
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (listener.isAlive() && request.isForMainFrame) {
            listener.onError(request.url.toString(), error.errorCode, error.description.toString())
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        if (listener.isAlive()) listener.onError(error.url, ERROR_FAILED_SSL_HANDSHAKE, "TLS")
    }

    override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
        handler.cancel()
    }

    override fun onSafeBrowsingHit(view: WebView, request: WebResourceRequest, threatType: Int, callback: SafeBrowsingResponse) {
        callback.backToSafety(true)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        listener.onGone(view, detail.didCrash())
        return true
    }
}
