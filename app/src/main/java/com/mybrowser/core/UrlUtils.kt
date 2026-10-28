package com.mybrowser.core

import android.net.Uri
import androidx.core.net.toUri
import java.util.Locale

/**
 * Deciding whether omnibar text is a URL or a search query.
 *
 * Uses native Rust implementation for better performance when available,
 * falls back to Kotlin implementation otherwise.
 */
object UrlUtils {

    /** Whether the native URL utils library is available */
    private val nativeAvailable: Boolean = try {
        // A library can be present but still miss a symbol after an app upgrade.  Loading
        // is therefore only an optimisation hint; every call below also catches linkage
        // errors and falls back to the Kotlin implementation.
        System.loadLibrary("mybrowser_url_utils")
        true
    } catch (_: Throwable) {
        false
    }

    /** Schemes we load in the WebView ourselves. */
    private val INTERNAL_SCHEMES = setOf(
        "http", "https", "file", "data", "about", "javascript", "blob", "content",
    )

    /**
     * Schemes that must never leave the browser for another app, even though the WebView
     * cannot render them either. Everything else is a handoff candidate: an allow-list of
     * the schemes we happen to recognise silently swallows every other app's deep link.
     * The per-site "open external apps" prompt, the user-gesture rule and
     * [ExternalIntentHandler]'s sanitising are what make an arbitrary handoff safe, not the
     * set of names below.
     */
    private val BLOCKED_HANDOFF_SCHEMES = setOf(
        "chrome", "chrome-native", "devtools", "view-source", "resource", "ftp",
    )

    /**
     * Opaque `scheme:payload` input the omnibar treats as a visit. App schemes in
     * `scheme://…` form are visits too; these are the ones that normally have no authority
     * part. A bare `word:word` stays a search, so typed prose is not launched by accident.
     */
    private val OPAQUE_HANDOFF_SCHEMES = setOf(
        "tel", "sms", "smsto", "mailto", "geo", "market", "intent", "android-app",
    )

    /**
     * Hosts that are valid without a dot. Without this, "localhost:8080" would be
     * treated as a search query.
     */
    private val DOTLESS_HOSTS = setOf("localhost")

    /** Characters a URI authority may not contain unescaped; keeps this parser Android-free. */
    private val FORBIDDEN_HOST_CHARS = charArrayOf('\\', '<', '>', '"', '^', '`', '{', '}', '|')

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val PORT = Regex("""^\d{1,5}$""")

    /** A scheme followed by "://", or a scheme followed by ":" for opaque URIs. */
    private val HAS_SCHEME = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*:""")

    /**
     * Converts raw omnibar input into something loadable.
     *
     * @param searchTemplate a URL containing "%s" where the encoded query goes.
     */
    fun normalizeOrSearch(input: String, searchTemplate: String): String {
        val text = input.trim()
        if (text.isEmpty()) return "about:blank"

        // Guard malformed web-looking input before treating a scheme as navigable. Without
        // this, values such as "https://" or "http:example.com" can be returned unchanged
        // and later mistaken for a page URL by the navigation policy.
        val explicitScheme = schemeOf(text)
        if ((explicitScheme == "http" || explicitScheme == "https") && !isHttpUrl(text)) {
            return buildSearchUrl(text, searchTemplate)
        }
        if (explicitScheme == "about" && !hasOpaquePayload(text)) {
            return buildSearchUrl(text, searchTemplate)
        }
        if (looksLikeInvalidHostPort(text)) {
            return buildSearchUrl(text, searchTemplate)
        }

        // The WebView itself supports additional internal schemes (for example data: and
        // javascript:), but an address-bar submission must not execute or expose those
        // opaque values. Known direct schemes take precedence over the host:port carve-out
        // so mailto:1234 and tel:1234 remain opaque hand-offs rather than local hosts.
        if (HAS_SCHEME.containsMatchIn(text)) {
            if (explicitScheme == "http" || explicitScheme == "https" ||
                explicitScheme == "about" || isTypedHandoff(text)) {
                return text
            }
            if (!isHostPortShape(text)) {
                return buildSearchUrl(text, searchTemplate)
            }
        }

        if (looksLikeHost(text)) {
            return "https://$text"
        }

        return buildSearchUrl(text, searchTemplate)
    }

    private fun buildSearchUrl(text: String, searchTemplate: String): String {
        val encoded = Uri.encode(text)
        return when {
            searchTemplate.contains("%s") -> searchTemplate.replace("%s", encoded)
            searchTemplate.contains("{query}") -> searchTemplate.replace("{query}", encoded)
            else -> "$searchTemplate$encoded"
        }
    }

    /** PSL registrable domain including private suffixes; absence must never widen scope. */
    fun registrableDomain(host: String): String? = if (nativeAvailable)
        runCatching { nativeRegistrableDomain(host) }.getOrNull() else null

    @JvmStatic
    private external fun nativeRegistrableDomain(host: String): String?

    /**
     * Whether text without a scheme should be treated as a host.
     *
     * Requires either a known dotless host, an IPv4 literal, a bracketed IPv6 literal, or a
     * dotted name whose last label looks like a TLD (letters only, at least two). Optional
     * ports are validated as numeric 0..65535 values so values such as "example.com:abc"
     * remain searches.
     */
    private fun looksLikeHost(text: String): Boolean {
        if (text.isEmpty() || text.any { it.isWhitespace() || it.isISOControl() }) return false
        val parts = parseAuthority(schemelessAuthority(text)) ?: return false
        return if (parts.host.startsWith('[')) {
            isIpv6Literal(parts.host)
        } else {
            isValidHostName(parts.host)
        }
    }

    private data class AuthorityParts(val host: String, val port: Int?)

    /** Returns the authority before the first path, query, or fragment delimiter. */
    private fun schemelessAuthority(text: String): String {
        var end = text.length
        for (index in text.indices) {
            if (text[index] == '/' || text[index] == '?' || text[index] == '#') {
                end = index
                break
            }
        }
        return text.substring(0, end)
    }

    /** Parses userinfo, bracketed IPv6, and an optional numeric port. */
    private fun parseAuthority(raw: String): AuthorityParts? {
        if (raw.isEmpty() || raw.any { it.isWhitespace() || it.isISOControl() }) return null
        val at = raw.lastIndexOf('@')
        val authority = if (at >= 0) raw.substring(at + 1) else raw
        if (authority.isEmpty()) return null

        val host: String
        val port: Int?
        if (authority.startsWith('[')) {
            val close = authority.indexOf(']')
            if (close <= 1) return null
            host = authority.substring(0, close + 1)
            val suffix = authority.substring(close + 1)
            port = when {
                suffix.isEmpty() -> null
                suffix.startsWith(':') -> parsePort(suffix.substring(1))
                else -> return null
            }
            if (suffix.isNotEmpty() && port == null) return null
            if (!isIpv6Literal(host)) return null
        } else {
            if (authority.contains('[') || authority.contains(']')) return null
            val colon = authority.indexOf(':')
            if (colon >= 0) {
                if (colon != authority.lastIndexOf(':')) return null
                host = authority.substring(0, colon)
                port = parsePort(authority.substring(colon + 1)) ?: return null
            } else {
                host = authority
                port = null
            }
        }

        if (host.isEmpty()) return null
        return AuthorityParts(host, port)
    }

    private fun parsePort(value: String): Int? {
        if (!PORT.matches(value)) return null
        return value.toIntOrNull()?.takeIf { it in 0..65_535 }
    }

    private fun isIpv6Literal(value: String): Boolean {
        if (!value.startsWith('[') || !value.endsWith(']')) return false
        val body = value.substring(1, value.length - 1)
        // Zone IDs (`%25wlan0`) are not accepted by Chromium in a URL either.
        if (body.isEmpty() || body.contains('%') || body.contains(":::")) return false
        val compressed = body.indexOf("::")
        if (compressed >= 0 && body.indexOf("::", compressed + 2) >= 0) return false
        val groups: List<String>
        var hasIpv4Tail = false
        if (compressed >= 0) {
            val left = body.substring(0, compressed)
            val right = body.substring(compressed + 2)
            val leftGroups = if (left.isEmpty()) emptyList() else left.split(':')
            val rightGroups = if (right.isEmpty()) emptyList() else right.split(':')
            groups = leftGroups + rightGroups
        } else {
            groups = body.split(':')
        }
        if (groups.isEmpty()) return compressed >= 0
        groups.forEachIndexed { index, group ->
            if (group.isEmpty()) return false
            val isTail = index == groups.lastIndex
            if (isTail && group.contains('.')) {
                if (!isIpv4Literal(group)) return false
                hasIpv4Tail = true
            } else if (group.length > 4 || group.any {
                    !it.isDigit() && it.lowercaseChar() !in 'a'..'f'
                }) {
                return false
            }
        }
        val units = groups.size + if (hasIpv4Tail) 1 else 0
        return if (compressed >= 0) units <= 7 else units == 8
    }

    private fun isIpv4Literal(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    private fun isHostPortShape(text: String): Boolean {
        val parts = parseAuthority(schemelessAuthority(text)) ?: return false
        if (parts.port == null) return false
        // Only a plausible host gets the schemeless `host:port` carve-out.  Treating every
        // opaque value such as `evilapp:1234` as a web host would make the native fast path
        // disagree with the Kotlin fallback and could turn a mistyped app link into a
        // network request.
        return if (parts.host.startsWith('[')) {
            isIpv6Literal(parts.host)
        } else {
            isValidHostName(parts.host)
        }
    }

    private fun looksLikeInvalidHostPort(text: String): Boolean {
        val authority = schemelessAuthority(text)
        if (authority.startsWith('[')) {
            val close = authority.indexOf(']')
            if (close < 0) return false
            val suffix = authority.substring(close + 1)
            return suffix.startsWith(':') && suffix.length > 1 &&
                !isKnownOpaqueScheme(authority.substring(0, close + 1)) &&
                parsePort(suffix.substring(1)) == null
        }
        val colon = authority.lastIndexOf(':')
        if (colon <= 0 || colon != authority.indexOf(':')) return false
        val host = authority.substring(0, colon)
        val port = authority.substring(colon + 1)
        return isValidHostName(host) && port.isNotEmpty() && parsePort(port) == null
    }

    private fun isKnownOpaqueScheme(value: String): Boolean =
        value.substringBefore(':').lowercase(Locale.ROOT) in
            (INTERNAL_SCHEMES + OPAQUE_HANDOFF_SCHEMES)

    private fun isValidHostName(host: String): Boolean {
        val lower = host.lowercase(Locale.ROOT)
        if (lower in DOTLESS_HOSTS) return true
        if (IPV4.matches(lower)) {
            return lower.split('.').all { it.toIntOrNull() in 0..255 }
        }
        if (!lower.contains('.') || lower.endsWith('.')) return false
        val labels = lower.split('.')
        val tld = labels.last()
        if (tld.length < 2 || !tld.all { it in 'a'..'z' }) return false
        return labels.all { label ->
            label.isNotEmpty() && !label.startsWith('-') && !label.endsWith('-') &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }

    private fun authorityForHttp(text: String): String? {
        val separator = text.indexOf("://")
        if (separator < 0) return null
        val start = separator + 3
        var end = text.length
        for (index in start until text.length) {
            if (text[index] == '/' || text[index] == '?' || text[index] == '#') {
                end = index
                break
            }
        }
        return text.substring(start, end)
    }

    /**
     * Conservative HTTP(S) URL validation shared by navigation, downloads and filter lists.
     *
     * [maxLength], [allowUserInfo] and [allowFragment] are the only differences callers ever
     * needed; every caller goes through this parser so a stricter rule cannot be applied in one
     * place and forgotten in the others.
     */
    fun isHttpUrl(
        url: String,
        maxLength: Int = Int.MAX_VALUE,
        allowUserInfo: Boolean = true,
        allowFragment: Boolean = true,
    ): Boolean {
        val text = url.trim()
        if (text.isEmpty() || text.length > maxLength) return false
        if (text.any { it.isWhitespace() || it.isISOControl() }) return false
        val scheme = schemeOf(text)
        if (scheme != "http" && scheme != "https") return false
        if (!text.startsWith("$scheme://", ignoreCase = true)) return false
        if (!allowFragment && text.indexOf('#') >= 0) return false
        val authority = authorityForHttp(text) ?: return false
        if (!allowUserInfo && authority.indexOf('@') >= 0) return false
        val parts = parseAuthority(authority) ?: return false
        if (parts.host.isEmpty()) return false
        return parts.host.none { it in FORBIDDEN_HOST_CHARS }
    }

    private fun hasOpaquePayload(text: String): Boolean {
        val colon = text.indexOf(':')
        if (colon <= 0 || colon == text.lastIndex) return false
        return text.substring(colon + 1).none { it.isWhitespace() || it.isISOControl() }
    }

    fun isInternalScheme(url: String): Boolean = schemeOf(url) in INTERNAL_SCHEMES

    /**
     * Whether a page may hand this URL to another app.
     *
     * Everything the WebView cannot render is a candidate except [BLOCKED_HANDOFF_SCHEMES]:
     * an installed app's scheme is unknowable here, and refusing names we did not enumerate
     * is what made every bank and local-service deep link disappear. Consent is the per-site
     * prompt, not this list; [ExternalIntentHandler] still strips components, packages and
     * URI grants before anything is launched.
     */
    fun isExternalHandoff(url: String): Boolean {
        val text = url.trim()
        if (!HAS_SCHEME.containsMatchIn(text)) return false
        val scheme = schemeOf(text)
        return scheme.isNotEmpty() &&
            scheme !in INTERNAL_SCHEMES &&
            scheme !in BLOCKED_HANDOFF_SCHEMES
    }

    /**
     * Whether typed omnibar text is an app handoff rather than a search.
     *
     * `scheme://…` is accepted for any app scheme so a pasted deep link still opens the app,
     * and a known opaque scheme must carry a payload. A bare `host:port` and prose such as
     * `note:buy` stay on the web/search side.
     */
    fun isTypedHandoff(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it.isISOControl() }) return false
        if (!isExternalHandoff(trimmed) || !hasOpaquePayload(trimmed)) return false
        val scheme = schemeOf(trimmed)
        return trimmed.substringAfter(':').startsWith("//") || scheme in OPAQUE_HANDOFF_SCHEMES
    }

    /**
     * Whether raw omnibar text represents something the browser can visit directly.
     *
     * This deliberately mirrors the navigation policy in [MainActivity]: ordinary web
     * URLs and app hand-offs in `scheme://…` form are visits, while unsafe or payload-less
     * opaque values (and strings that need a search engine) remain searches.  Keeping this
     * decision next to [normalizeOrSearch] prevents the action label in the address bar from
     * promising a direct visit when navigation would actually fall back to a search.
     */
    fun isNavigableInput(input: String): Boolean {
        val text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() || it.isISOControl() }) return false

        if (HAS_SCHEME.containsMatchIn(text)) {
            val scheme = schemeOf(text)
            when {
                scheme == "http" || scheme == "https" -> return isHttpUrl(text)
                scheme == "about" -> return hasOpaquePayload(text)
                isTypedHandoff(text) -> return true
            }

            // A schemeless host with a port also matches HAS_SCHEME ("localhost:8080").
            // Only let that shape through when the authority parser and hostname heuristic
            // both accept it; unknown opaque schemes remain searches.
            if (!isHostPortShape(text)) return false
        }

        return looksLikeHost(text)
    }

    fun schemeOf(url: String): String =
        url.trim().substringBefore(':', missingDelimiterValue = "").lowercase(Locale.ROOT)

    /** Host for display, or null when there isn't a meaningful one. */
    fun hostOf(url: String): String? = runCatching {
        val parsed = url.trim().toUri()
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        if (scheme.isNullOrEmpty() || scheme !in setOf("http", "https")) return@runCatching null
        parsed.host?.let { host ->
            if (host.startsWith("www.", ignoreCase = true)) host.substring(4) else host
        }
    }.getOrNull()

    fun isHttps(url: String): Boolean = schemeOf(url) == "https"

    /**
     * Trims a URL for omnibar display: drops the scheme for https and the trailing
     * slash on a bare host, so "https://example.com/" shows as "example.com".
     */
    fun forDisplay(url: String): String {
        if (url == "about:blank") return ""
        var s = url.trim()
        if (s.startsWith("https://", ignoreCase = true)) s = s.substring(8)
        // Only remove the slash from a bare HTTPS authority.  A path, query or fragment
        // must remain visible so copying/editing the omnibar is lossless.
        if (s.endsWith('/') && s.indexOf('/') == s.lastIndex &&
            !s.contains('?') && !s.contains('#')) {
            s = s.dropLast(1)
        }
        return s
    }
}
