package com.mybrowser.core

import android.webkit.WebSettings

/**
 * User-agent strings.
 *
 * The stock WebView UA contains "; wv" (and on some builds "Version/4.0"), which a
 * number of sites use to detect an embedded WebView and then either degrade the page
 * or refuse to serve it. Stripping those tokens makes us look like Chrome mobile,
 * which is what every lightweight WebView browser does.
 */
object UserAgent {

    /** Cached because WebSettings.getDefaultUserAgent() is a JNI call into Chromium. */
    @Volatile
    private var cachedDefault: String? = null

    fun default(settings: WebSettings): String =
        cachedDefault ?: settings.userAgentString.also { cachedDefault = it }

    /**
     * Mobile UA with the WebView markers removed.
     *
     * "Mozilla/5.0 (Linux; Android 15; Pixel 9 Build/...; wv) AppleWebKit/537.36
     *  (KHTML, like Gecko) Version/4.0 Chrome/131.0.0.0 Mobile Safari/537.36"
     * becomes the same string without "; wv" and without "Version/4.0 ".
     */
    fun mobile(settings: WebSettings): String =
        default(settings)
            .replace("; wv)", ")")
            .replace(Regex("""\sVersion/\d+\.\d+"""), "")

    /**
     * Desktop UA. Reports Linux x86_64 and drops the Mobile token so sites serve their
     * desktop layout.
     *
     * Note that swapping the UA is only half of desktop mode; the page's own viewport
     * meta tag still constrains layout. See [WebViewConfig.applyDesktopMode].
     */
    fun desktop(settings: WebSettings): String {
        val chromeVersion = Regex("""Chrome/(\d+)""")
            .find(default(settings))
            ?.groupValues
            ?.get(1)
            ?: "131"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chromeVersion.0.0.0 Safari/537.36"
    }
}
