package com.mybrowser.site

import androidx.core.net.toUri

/**
 * Presentation preference shared by the bare host and its conventional mobile/desktop
 * aliases. This is deliberately separate from SiteOrigin: permissions, JavaScript,
 * cookies and filtering must never inherit another origin's settings.
 */
object DesktopSite {
    fun of(url: String): String? {
        val origin = SiteOrigin.of(url) ?: return null
        val uri = origin.toUri()
        val host = uri.host ?: return null
        var labels = host.split('.')
        // Keep IP literals, single-label hosts and names such as m.com intact. Only
        // remove conventional presentation prefixes, not arbitrary subdomains.
        while (labels.size > 2 && labels.first() in PRESENTATION_PREFIXES) labels = labels.drop(1)
        val siteHost = labels.joinToString(".")
        if (siteHost == host) return origin
        return "${uri.scheme}://$siteHost${if (uri.port != -1) ":${uri.port}" else ""}"
    }

    private val PRESENTATION_PREFIXES = setOf("m", "mobile", "www")
}
