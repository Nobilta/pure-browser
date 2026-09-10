package com.mybrowser.core

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * Central WebSettings configuration.
 *
 * Every setting here is a deliberate choice; the defaults are wrong for a browser in
 * several places. Grouped by concern with the reasoning inline, because six months from
 * now the non-obvious ones look like mistakes.
 */
object WebViewConfig {

    /** Slot for the desktop-mode viewport override script, per WebView. */
    private const val DESKTOP_VIEWPORT_JS = """
        (function() {
          var m = document.querySelector('meta[name=viewport]');
          if (m) { m.setAttribute('content', 'width=1024'); }
          else {
            m = document.createElement('meta');
            m.name = 'viewport';
            m.content = 'width=1024';
            document.head.appendChild(m);
          }
        })();
    """

    @SuppressLint("SetJavaScriptEnabled")
    fun apply(webView: WebView) {
        val s = webView.settings

        // --- Content ---
        // A browser without JS is not a browser. The risk this carries is managed by
        // never exposing a @JavascriptInterface to untrusted origins.
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        // Deliberately not setting databaseEnabled: it controlled WebSQL, which Chromium
        // has removed outright, so the setting is deprecated and does nothing.
        s.loadsImagesAutomatically = true
        s.blockNetworkImage = false

        // Popups. Without this, target=_blank links silently do nothing, which reads as
        // a broken page. Requires WebChromeClient.onCreateWindow to be implemented.
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true

        // --- Layout ---
        // Required for pages that declare a viewport wider than the screen. Without the
        // pair, wide pages get clipped instead of scaled to fit.
        s.useWideViewPort = true
        s.loadWithOverviewMode = true

        // Pinch-zoom on, but no on-screen zoom buttons: they overlap page content and
        // every browser has used pinch for a decade.
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false

        // Chromium's own text autosizing. Better than a fixed textZoom because it only
        // adjusts pages that would otherwise render unreadably small.
        s.layoutAlgorithm = WebSettings.LayoutAlgorithm.TEXT_AUTOSIZING

        // --- Media ---
        // Requiring a gesture blocks autoplay. This is both the polite default and what
        // stops a background tab from making noise.
        s.mediaPlaybackRequiresUserGesture = true

        // --- Storage and cache ---
        s.cacheMode = WebSettings.LOAD_DEFAULT

        // --- Security ---
        // file:// access off. A malicious page reaching local files is the classic
        // WebView exploit; the two "universal/file access from file URLs" flags are
        // security holes and stay off even though they default off, because a future
        // refactor might enable file access without realising these follow.
        s.allowFileAccess = false
        s.allowContentAccess = false
        @Suppress("DEPRECATION")
        s.allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        s.allowUniversalAccessFromFileURLs = false

        // Mixed content: COMPATIBILITY_MODE lets images/media load over http on an
        // https page but still blocks scripts and iframes. NEVER_ALLOW breaks a large
        // number of sites; ALWAYS_ALLOW is a downgrade attack. This is the middle path
        // Chrome itself takes.
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // Safe Browsing. Costs a lookup per navigation but catches phishing; the
        // interstitial is handled in WebViewClient.onSafeBrowsingHit.
        s.safeBrowsingEnabled = true

        // --- Cookies ---
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        // The Activity replaces this default with the current origin's cookie choice.
        cookies.setAcceptThirdPartyCookies(webView, true)

        // --- Dark mode ---
        // setForceDark is deprecated (API 33). Algorithmic darkening is the replacement:
        // it respects a site's own prefers-color-scheme when present and only inverts
        // pages that have no dark theme of their own.
        applyDarkening(webView, true)

        // --- Rendering ---
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
        webView.isScrollbarFadingEnabled = true
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false
        // Suppresses the blue overscroll glow, which looks wrong over page content.
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER

        // Text selection and long-press are handled by Chromium; we only need to not
        // fight it.
        webView.isLongClickable = true

        setUserAgent(webView, desktop = false)
    }

    fun setUserAgent(webView: WebView, desktop: Boolean) {
        val s = webView.settings
        val userAgent = if (desktop) UserAgent.desktop(s) else UserAgent.mobile(s)
        // Changing the UA during navigation can restart the load. Redirect and page
        // callbacks reapply site settings, so leave an already-correct UA untouched.
        if (s.userAgentString != userAgent) s.userAgentString = userAgent
    }

    /**
     * Switches a WebView between mobile and desktop presentation.
     *
     * Changing the UA alone is not enough: a page that declares
     * `<meta name=viewport content="width=device-width">` will still lay out at phone
     * width. Call [applyDesktopViewport] from onPageFinished, once the new document
     * exists. Injecting it here would mutate the previous page during navigation.
     */
    fun applyDesktopMode(webView: WebView, desktop: Boolean) {
        setUserAgent(webView, desktop)
        val s = webView.settings
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
    }

    /** Applies the desktop viewport to the document that is currently loaded. */
    fun applyDesktopViewport(webView: WebView, width: Int = 1024) {
        if (width == 0) return
        if (webView.url.isNullOrBlank() || webView.url == "about:blank") return
        webView.evaluateJavascript(DESKTOP_VIEWPORT_JS.replace("1024", width.coerceIn(980, 1440).toString()), null)
    }

    /** No-image mode, for slow connections. */
    fun setImagesEnabled(webView: WebView, enabled: Boolean) {
        webView.settings.apply {
            loadsImagesAutomatically = enabled
            blockNetworkImage = !enabled
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun applySiteSettings(webView: WebView, settings: com.mybrowser.site.SiteSettings) {
        applyDarkening(webView, settings.webDarkening)
        webView.settings.javaScriptEnabled = settings.javaScript
        webView.settings.textZoom = settings.textZoom
        setImagesEnabled(webView, settings.images)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, settings.thirdPartyCookies)
        applyDesktopMode(webView, settings.desktop)
    }

    private fun applyDarkening(webView: WebView, enabled: Boolean) {
        // Some framework/provider combinations advertise the feature but reject its
        // settings bridge. A display preference must not interrupt page navigation.
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING))
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, enabled)
        }
    }
}
