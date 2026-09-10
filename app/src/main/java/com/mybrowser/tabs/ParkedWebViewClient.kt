package com.mybrowser.tabs

import android.annotation.SuppressLint
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/** Parked pages keep their DOM but cannot navigate, launch apps, or start more requests. */
@SuppressLint("MissingOnRenderProcessGone")
class ParkedWebViewClient(private val gone: (WebView) -> Unit) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        gone(view)
        return true
    }
}
