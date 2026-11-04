package com.mybrowser.core

import android.webkit.WebResourceRequest
import org.junit.Assert.*
import org.junit.Test

class WebRequestHeadersTest {
    private fun request(headers: Map<String, String>?) = object : WebResourceRequest {
        override fun getUrl(): android.net.Uri = throw UnsupportedOperationException()
        override fun isForMainFrame() = true
        override fun isRedirect() = true
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String>? = headers
    }

    @Test fun missingNativeHeaderMapsAreDistinctFromAnExplicitlyAbsentReferrer() {
        assertNull(request(null).observedReferer())
        assertNull(request(emptyMap()).observedReferer())
        assertEquals("", request(mapOf("Referer" to "")).observedReferer())
        assertEquals("https://site.test/", request(mapOf("rEfErEr" to "https://site.test/")).observedReferer())
    }
}
