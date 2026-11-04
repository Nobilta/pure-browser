package com.mybrowser.media

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.mybrowser.tabs.MediaNavigation

/** A redirected document is committed at its real URL using the response already fetched. */
internal class MediaResponseHandoff : AutoCloseable {
    private data class Pending(val navigation: MediaNavigation, val response: WebResourceResponse)
    private var pending: Pending? = null

    @Synchronized
    fun offer(navigation: MediaNavigation, response: WebResourceResponse) {
        close()
        pending = Pending(navigation, response)
    }

    @Synchronized
    fun take(request: WebResourceRequest, navigation: MediaNavigation?): WebResourceResponse? {
        val value = pending ?: return null
        if (value.navigation.loadId.cancelled || value.navigation.loadId !== navigation?.loadId) {
            close()
            return null
        }
        if (!request.isForMainFrame || request.method != "GET" || !value.navigation.matches(request.url.toString())) return null
        pending = null
        return value.response
    }

    @Synchronized
    fun discard(response: WebResourceResponse) {
        if (pending?.response === response) close()
    }

    @Synchronized
    fun retain(navigation: MediaNavigation?) {
        if (pending?.navigation?.loadId !== navigation?.loadId) close()
    }

    @Synchronized
    override fun close() {
        val value = pending
        pending = null
        runCatching { value?.response?.data?.close() }
    }
}
