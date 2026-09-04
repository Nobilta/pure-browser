package com.mybrowser.core

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * The half of WebView integration that needs an Activity: progress, fullscreen video,
 * file pickers, permission prompts, and JS dialogs.
 *
 * Every callback here is one that, left unimplemented, produces a visibly broken
 * browser rather than a degraded one. `<input type=file>` does nothing, fullscreen video
 * shows a black box, `target=_blank` silently fails.
 */
class BrowserChromeClient(
    private val listener: Listener,
    /** WebChromeClient's console callback has no WebView argument. */
    private val sourceView: WebView,
) : WebChromeClient() {

    interface Listener {
        /** Returns false for callbacks arriving from a view already detached from the UI. */
        fun isCurrentWebView(view: WebView): Boolean = true

        fun onProgressChanged(progress: Int)
        fun onTitleChanged(title: String?)
        fun onIconChanged(icon: Bitmap?)

        /** JavaScript console output from the current WebView. */
        fun onConsoleMessage(message: ConsoleMessage): Boolean = false

        /** Video went fullscreen. Host must add [view] over everything else. */
        fun onEnterFullscreen(view: View, callback: CustomViewCallback)
        fun onExitFullscreen()

        /**
         * Show a file picker. Returning false means we could not, and the caller will
         * release the WebView's callback so the page is not left waiting forever.
         */
        fun onShowFileChooser(
            callback: ValueCallback<Array<Uri>?>,
            params: FileChooserParams,
        ): Boolean

        /** Camera / microphone / DRM. Host checks the app-level permission first. */
        fun onPermissionRequest(request: PermissionRequest)
        fun onPermissionRequestCanceled(request: PermissionRequest) {}
        fun onGeolocationRequest(origin: String, callback: GeolocationPermissions.Callback)

        /** target=_blank or window.open(). Host creates a tab and returns its WebView. */
        fun onCreateWindow(isDialog: Boolean, isUserGesture: Boolean): WebView?
        fun onCloseWindow()

        /**
         * JS dialog. Host must eventually call confirm()/cancel() on the result, and
         * should show [origin] so a page cannot impersonate the browser chrome.
         */
        fun onJsDialog(
            type: JsDialogType,
            origin: String,
            message: String,
            defaultValue: String?,
            result: JsResult,
        ): Boolean
    }

    enum class JsDialogType { ALERT, CONFIRM, PROMPT, BEFORE_UNLOAD }

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        if (!listener.isCurrentWebView(view)) return
        listener.onProgressChanged(newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        if (!listener.isCurrentWebView(view)) return
        listener.onTitleChanged(title)
    }

    override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
        if (!listener.isCurrentWebView(view)) return
        listener.onIconChanged(icon)
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        if (!listener.isCurrentWebView(sourceView)) return true
        return listener.onConsoleMessage(consoleMessage)
    }

    // --- Fullscreen video ---

    private var customViewCallback: CustomViewCallback? = null

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        // WebChromeClient does not expose the originating WebView in this callback, so
        // the host remains responsible for rejecting a stale custom view if necessary.
        // A second request while already fullscreen must be refused, or the first
        // callback leaks and the video can never be exited.
        if (customViewCallback != null) {
            callback.onCustomViewHidden()
            return
        }
        customViewCallback = callback
        listener.onEnterFullscreen(view, callback)
    }

    override fun onHideCustomView() {
        customViewCallback = null
        listener.onExitFullscreen()
    }

    // --- File upload ---

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>?>,
        fileChooserParams: FileChooserParams,
    ): Boolean {
        if (!listener.isCurrentWebView(webView)) {
            filePathCallback.onReceiveValue(null)
            return false
        }
        val handled = listener.onShowFileChooser(filePathCallback, fileChooserParams)
        if (!handled) {
            // Releasing the callback is mandatory. Without it the page's file input is
            // permanently stuck and cannot be retried.
            filePathCallback.onReceiveValue(null)
        }
        return handled
    }

    // --- Permissions ---

    override fun onPermissionRequest(request: PermissionRequest) {
        listener.onPermissionRequest(request)
    }

    override fun onPermissionRequestCanceled(request: PermissionRequest) {
        listener.onPermissionRequestCanceled(request)
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String,
        callback: GeolocationPermissions.Callback,
    ) {
        listener.onGeolocationRequest(origin, callback)
    }

    // --- Windows ---

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message,
    ): Boolean {
        if (!listener.isCurrentWebView(view)) return false

        // Validate the hand-off object before asking the host to create a tab.  Chromium
        // normally supplies WebViewTransport, but malformed/provider-specific messages do
        // occur.  Creating a tab first would leave an orphan WebView and tab even though
        // there is nowhere to send the popup navigation.
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        val newWebView = listener.onCreateWindow(isDialog, isUserGesture)
            ?: return false

        // The transport handoff: the new WebView must be attached to the message before
        // sending, and sendToTarget is what actually starts the load. Skipping either
        // leaves a blank popup with no error.
        transport.webView = newWebView
        resultMsg.sendToTarget()
        return true
    }

    override fun onCloseWindow(window: WebView) {
        if (!listener.isCurrentWebView(window)) return
        listener.onCloseWindow()
    }

    // --- JS dialogs ---
    // Handled ourselves so the origin is shown. The stock dialogs render without any
    // indication of which site produced them, which a page can exploit to imitate
    // browser or system UI.

    override fun onJsAlert(
        view: WebView,
        url: String?,
        message: String?,
        result: JsResult,
    ): Boolean {
        if (!listener.isCurrentWebView(view)) {
            runCatching { result.cancel() }
            return true
        }
        return listener.onJsDialog(
            JsDialogType.ALERT, originOf(url), message.orEmpty(), null, result,
        )
    }

    override fun onJsConfirm(
        view: WebView,
        url: String?,
        message: String?,
        result: JsResult,
    ): Boolean {
        if (!listener.isCurrentWebView(view)) {
            runCatching { result.cancel() }
            return true
        }
        return listener.onJsDialog(
            JsDialogType.CONFIRM, originOf(url), message.orEmpty(), null, result,
        )
    }

    override fun onJsPrompt(
        view: WebView,
        url: String?,
        message: String?,
        defaultValue: String?,
        result: JsPromptResult,
    ): Boolean {
        if (!listener.isCurrentWebView(view)) {
            runCatching { result.cancel() }
            return true
        }
        return listener.onJsDialog(
            JsDialogType.PROMPT, originOf(url), message.orEmpty(), defaultValue, result,
        )
    }

    override fun onJsBeforeUnload(
        view: WebView,
        url: String?,
        message: String?,
        result: JsResult,
    ): Boolean {
        if (!listener.isCurrentWebView(view)) {
            runCatching { result.cancel() }
            return true
        }
        return listener.onJsDialog(
            JsDialogType.BEFORE_UNLOAD, originOf(url), message.orEmpty(), null, result,
        )
    }

    private fun originOf(url: String?): String =
        url?.let { UrlUtils.hostOf(it) } ?: ""
}
