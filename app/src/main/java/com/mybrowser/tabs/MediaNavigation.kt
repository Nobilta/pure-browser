package com.mybrowser.tabs

import androidx.core.net.toUri
import com.mybrowser.core.UrlUtils

/** Session-only source of a media navigation, scoped to its current URL and HTTP redirects. */
class MediaNavigation private constructor(val url: String, val referer: String, private val redirects: Int = 0) {
    val canFollowRedirect: Boolean get() = redirects < 10

    fun forLoad(): MediaNavigation = MediaNavigation(url, referer)
    fun matches(target: String): Boolean = target.substringBefore('#') == url

    fun headersFor(target: String): Map<String, String> =
        if (matches(target)) mapOf("Referer" to referer) else emptyMap()

    /** A candidate rediscovered inside the media document still came from the original page. */
    fun sourceFor(target: String, pageUrl: String?): String? =
        if (matches(target) && pageUrl != null && matches(pageUrl)) referer else pageUrl

    fun follow(target: String, isRedirect: Boolean): MediaNavigation? = when {
        isRedirect -> if (canFollowRedirect) create(target, referer)?.let { MediaNavigation(it.url, it.referer, redirects + 1) } else null
        matches(target) -> this
        else -> null
    }

    companion object {
        fun create(url: String, pageUrl: String?): MediaNavigation? {
            if (!UrlUtils.isHttpUrl(url, maxLength = 8_192) ||
                pageUrl == null || !UrlUtils.isHttpUrl(pageUrl, maxLength = 8_192)) return null
            val source = pageUrl.toUri()
            // Referrers contain neither embedded credentials nor fragments. Do not send an
            // HTTPS page's URL to an HTTP target, including a downgrade in the redirect chain.
            if (source.scheme.equals("https", true) && url.startsWith("http://", true)) return null
            val referer = source.buildUpon()
                .encodedAuthority(source.encodedAuthority?.substringAfterLast('@'))
                .fragment(null).build().toString()
            return MediaNavigation(url.substringBefore('#'), referer)
        }
    }
}
