package com.mybrowser.core

import android.annotation.SuppressLint
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Client installed while a pooled WebView is detached from an Activity.
 *
 * A plain [WebViewClient] is not enough here: Android Lint correctly warns that the
 * default implementation does not consume renderer-death callbacks.  A pooled view can
 * still have one callback queued after it is detached, and allowing that callback to
 * propagate to the framework may terminate the whole process.  This client deliberately
 * has no host references and treats the renderer event as handled; the pool owns the
 * subsequent discard/release decision.
 */
@SuppressLint("MissingOnRenderProcessGone")
object DetachedWebViewClient : WebViewClient() {

    override fun onRenderProcessGone(
        view: WebView,
        detail: RenderProcessGoneDetail,
    ): Boolean = true
}
