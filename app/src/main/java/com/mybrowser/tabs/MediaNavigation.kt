package com.mybrowser.tabs

import androidx.core.net.toUri
import com.mybrowser.core.UrlUtils

/** Session-only request context; never persisted with tab history. */
class MediaNavigation private constructor(
    val url: String,
    val referer: String,
    val sourcePageUrl: String,
    val policy: Policy,
    private val redirects: Int = 0,
    val loadId: RequestLifetime = RequestLifetime(),
) {
    class RequestLifetime {
        @Volatile var cancelled: Boolean = false
        @Volatile var failure: com.mybrowser.media.MediaRequestInterceptor.Failure? = null
        @Volatile internal var fallbackRedirects: List<MediaNavigation> = emptyList()
    }

    enum class Policy(val token: String) {
        NONE("no-referrer"), SAME_ORIGIN("same-origin"), ORIGIN("origin"),
        STRICT_ORIGIN("strict-origin"), ORIGIN_CROSS("origin-when-cross-origin"),
        STRICT_CROSS("strict-origin-when-cross-origin"), DOWNGRADE("no-referrer-when-downgrade"),
        FULL("unsafe-url");
        companion object {
            fun parse(value: String?): Policy? = value?.split(',')?.mapNotNull { word ->
                entries.firstOrNull { it.token == word.trim().lowercase(java.util.Locale.ROOT) }
            }?.lastOrNull()
        }
    }

    val canFollowRedirect: Boolean get() = redirects < 10
    fun forLoad(): MediaNavigation = MediaNavigation(url, referer, sourcePageUrl, policy)
    fun matches(target: String): Boolean = target.substringBefore('#') == url
    fun sameSourceAs(other: MediaNavigation?): Boolean = other != null && url == other.url &&
        referer == other.referer && sourcePageUrl == other.sourcePageUrl && policy == other.policy

    fun headersFor(target: String): Map<String, String> =
        if (matches(target) && referer.isNotEmpty()) mapOf("Referer" to referer) else emptyMap()

    /** null is unrelated; empty deliberately suppresses the ordinary page referrer. */
    fun downloadRefererFor(target: String): String? = if (matches(target)) referer else null

    /** Native fallback redirects already apply the server's Referrer-Policy. Never
     * replay the old header or reconstruct a header absent from the observed request. */
    fun followNativeRedirect(target: String, observedReferer: String?): MediaNavigation? {
        val next = follow(target, isRedirect = true) ?: return null
        // Older providers omit headers from shouldOverrideUrlLoading. The
        // interceptor has already read these response policies before fallback.
        loadId.fallbackRedirects.firstOrNull { it.url == next.url && it.redirects == next.redirects }?.let { return it }
        val observed = create(target, sourcePageUrl, observedReferer.orEmpty()) ?: return null
        return MediaNavigation(next.url, observed.referer, sourcePageUrl, observed.policy,
            next.redirects, loadId)
    }

    fun sourceFor(target: String, pageUrl: String?): String? =
        if (matches(target) && pageUrl != null && matches(pageUrl)) sourcePageUrl else pageUrl

    fun follow(target: String, isRedirect: Boolean, responsePolicy: String? = null): MediaNavigation? = when {
        isRedirect && canFollowRedirect && valid(target) && !(url.startsWith("https://", true) && target.startsWith("http://", true)) -> {
            val nextPolicy = Policy.parse(responsePolicy) ?: policy
            // Once a hop has removed a path/query (or the whole referrer), never reconstruct it.
            MediaNavigation(target.substringBefore('#'), applyPolicy(referer, target, nextPolicy),
                sourcePageUrl, nextPolicy, redirects + 1, loadId)
        }
        !isRedirect && matches(target) -> this
        else -> null
    }

    companion object {
        /** null = no network observation; empty = the observed request sent no Referer. */
        fun create(url: String, pageUrl: String?, observedReferer: String? = null): MediaNavigation? {
            if (!valid(url) || pageUrl == null || !valid(pageUrl)) return null
            val source = sanitize(pageUrl)
            if (source.startsWith("https://", true) && url.startsWith("http://", true)) return null
            val observed = observedReferer?.let { if (valid(it)) sanitize(it) else "" }
            // A full cross-origin header proves that the original request explicitly allowed it.
            // A same-origin observation says nothing about the policy for later cross-origin hops.
            val policy = if (observed != null && observed.isNotEmpty() &&
                origin(observed) != origin(url) && observed != origin(observed)) Policy.DOWNGRADE else Policy.STRICT_CROSS
            val ref = observed ?: applyPolicy(source, url, policy)
            return MediaNavigation(url.substringBefore('#'), applyPolicy(ref, url, policy), source, policy)
        }

        private fun valid(url: String) = UrlUtils.isHttpUrl(url, maxLength = 8_192)
        private fun sanitize(url: String): String = url.toUri().let { uri ->
            uri.buildUpon().encodedAuthority(uri.encodedAuthority?.substringAfterLast('@')).fragment(null).build().toString()
        }
        fun origin(url: String): String = url.toUri().let { uri ->
            val scheme = uri.scheme.orEmpty().lowercase(java.util.Locale.ROOT)
            val port = uri.port.takeIf { it >= 0 && it != if (scheme == "https") 443 else 80 }
            val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT).let { if (':' in it && !it.startsWith('[')) "[$it]" else it }
            "$scheme://$host${port?.let { ":$it" }.orEmpty()}/"
        }
        private fun applyPolicy(source: String, target: String, policy: Policy): String {
            if (source.isEmpty() || source.startsWith("https://", true) && target.startsWith("http://", true)) return ""
            val same = origin(source) == origin(target)
            return when (policy) {
                Policy.NONE -> ""
                Policy.SAME_ORIGIN -> if (same) source else ""
                Policy.ORIGIN, Policy.STRICT_ORIGIN -> origin(source)
                Policy.ORIGIN_CROSS, Policy.STRICT_CROSS -> if (same) source else origin(source)
                Policy.DOWNGRADE, Policy.FULL -> source
            }
        }
    }
}
