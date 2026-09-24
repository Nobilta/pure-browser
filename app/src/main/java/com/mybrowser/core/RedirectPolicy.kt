package com.mybrowser.core

import java.net.URL

/**
 * The redirect rule the app's HTTP clients share.
 *
 * A hop must be a URL the caller accepts — it supplies that test, because each client keeps its own
 * idea of a valid target — and it must not downgrade https to http. Only the decision lives here:
 * callers keep their own status handling, headers and error type.
 *
 * It is written once because the same decision had been spelled out three times, in
 * [TextDownloader], the user-script resource fetch and the search suggestion download, and a
 * fourth client (the updater) had already drifted into its own resolution and error codes. The
 * caller that walks redirects with `java.net.URL` and reports `IOException` should use this;
 * the updater stays separate because it resolves with `URI` and maps every hop to a typed
 * `UpdateRepository.Failure` after re-checking its host allowlist.
 */
internal object RedirectPolicy {

    /** Redirects a client follows before giving up. */
    const val MAX_HOPS = 5

    /**
     * The target of one hop, or null when [location] is absent, is not a URL [allows] accepts, or
     * would downgrade [current] from https to http.
     */
    fun next(current: URL, location: String?, allows: (String) -> Boolean): URL? {
        val value = location?.takeIf { it.isNotBlank() } ?: return null
        val next = runCatching { URL(current, value) }.getOrNull() ?: return null
        if (!allows(next.toString())) return null
        if (current.protocol == "https" && next.protocol != "https") return null
        return next
    }
}
