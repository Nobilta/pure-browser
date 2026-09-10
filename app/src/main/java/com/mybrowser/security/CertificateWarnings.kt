package com.mybrowser.security

import androidx.compose.runtime.mutableStateMapOf
import java.net.URI
import java.util.Locale

/** Mirrors explicit SSL overrides for the lifetime of WebView's in-process SSL cache. */
class CertificateWarnings {
    private val origins = mutableStateMapOf<String, Boolean>()

    fun remember(url: String) { origin(url)?.let { origins[it] = true } }
    fun contains(url: String): Boolean = origin(url)?.let { origins[it] == true } ?: false
    fun clear() = origins.clear()

    private fun origin(url: String): String? = runCatching {
        val uri = URI(url)
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank()) return@runCatching null
        "${uri.host.lowercase(Locale.ROOT)}:${uri.port.takeIf { it >= 0 } ?: 443}"
    }.getOrNull()
}
