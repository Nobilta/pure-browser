package com.mybrowser.media

import android.annotation.SuppressLint
import android.webkit.CookieManager
import androidx.core.net.toUri
import androidx.webkit.CookieManagerCompat
import androidx.webkit.WebViewFeature
import com.mybrowser.core.UrlUtils

/** Reads the owning profile for each hop; raw Cookie headers never cross a redirect. */
internal class MediaCookies(
    private val manager: CookieManager?,
    private val initiator: String,
    private val topLevel: Boolean,
    private val allowThirdParty: Boolean,
    private val cookieInfo: (CookieManager, String) -> List<String>? = ::readCookieInfo,
) {
    @SuppressLint("RequiresFeature")
    fun header(url: String, initialHeader: String?): String? {
        if (manager == null) return initialHeader
        // Chromium's default redirect policy compares the current hop to the initiator.
        // Returning to the initiating site must not inherit the intermediate host's policy.
        val same = sameSite(initiator, url)
        if (!topLevel && !same && !allowThirdParty) return null
        val info = cookieInfo(manager, url)
        return if (info != null) {
            info
                .filter { allowed(it, same) }.joinToString("; ") { it.substringBefore(';') }.ifEmpty { null }
        } else {
            // The legacy API exposes no SameSite/partition attributes. A header already
            // selected by WebView is safe; do not invent cross-site credentials from its jar.
            initialHeader ?: if (same) manager.getCookie(url) else null
        }
    }

    fun store(url: String, value: String) {
        val same = sameSite(initiator, url)
        if ((topLevel || same || allowThirdParty) && !partitioned(value) && (topLevel || allowed(value, same))) manager?.setCookie(url, value)
    }

    private fun attributes(value: String): Map<String, String> = value.split(';').drop(1).associate {
        it.substringBefore('=').trim().lowercase(java.util.Locale.ROOT) to it.substringAfter('=', "").trim().lowercase(java.util.Locale.ROOT)
    }
    private fun partitioned(value: String) = "partitioned" in attributes(value)

    private fun allowed(value: String, same: Boolean): Boolean {
        val attrs = attributes(value)
        // CookieManager's string API cannot represent the media document's partition key.
        if ("partitioned" in attrs) return false
        return when (attrs["samesite"]) {
            "strict" -> same
            "none" -> "secure" in attrs
            else -> same || topLevel // All intercepted requests are GET.
        }
    }

    companion object {
        @SuppressLint("RequiresFeature")
        private fun readCookieInfo(manager: CookieManager, url: String): List<String>? =
            if (WebViewFeature.isFeatureSupported(WebViewFeature.GET_COOKIE_INFO))
                CookieManagerCompat.getCookieInfo(manager, url) else null
        private fun sameSite(left: String, right: String): Boolean = runCatching {
            val a = left.toUri(); val b = right.toUri()
            fun site(host: String?) = host?.lowercase(java.util.Locale.ROOT)?.let { UrlUtils.registrableDomain(it) ?: it }
            a.scheme.equals(b.scheme, true) && a.host != null && site(a.host) == site(b.host)
        }.getOrDefault(false)
    }
}
