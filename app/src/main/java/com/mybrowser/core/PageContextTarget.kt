package com.mybrowser.core

import android.os.Handler
import android.os.Looper
import android.webkit.WebView

data class PageContextTarget(val linkUrl: String?, val imageUrl: String?, val title: String) {
    companion object {
        fun create(linkUrl: String?, imageUrl: String?, title: String? = null): PageContextTarget? {
            fun safe(value: String?): String? = value?.trim()?.takeIf {
                it.length <= 8192 && it.none(Char::isISOControl) && UrlUtils.isHttpUrl(it)
            }
            val link = safe(linkUrl)
            val image = safe(imageUrl)
            if (link == null && image == null) return null
            return PageContextTarget(link, image, title.orEmpty().filterNot(Char::isISOControl).take(512))
        }
    }
}

/** Resolves image links using WebView's hit-test API, leaving text selection to Chromium. */
class PageContextMenuController(
    private val isCurrent: (WebView) -> Boolean,
    private val onTarget: (PageContextTarget) -> Unit,
) {
    private var generation = 0L

    fun invalidate() { generation++ }

    fun attach(view: WebView) {
        view.setOnLongClickListener {
            if (!isCurrent(view)) return@setOnLongClickListener false
            val hit = view.hitTestResult ?: return@setOnLongClickListener false
            val request = ++generation
            when (hit.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    val target = PageContextTarget.create(hit.extra, null) ?: return@setOnLongClickListener false
                    onTarget(target)
                    true
                }
                WebView.HitTestResult.IMAGE_TYPE -> {
                    val target = PageContextTarget.create(null, hit.extra) ?: return@setOnLongClickListener false
                    onTarget(target)
                    true
                }
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val handler = Handler(Looper.getMainLooper()) { message ->
                        if (generation == request && isCurrent(view)) {
                            val data = message.data
                            PageContextTarget.create(data.getString("url"), data.getString("src") ?: hit.extra,
                                data.getString("title"))?.let(onTarget)
                        }
                        true
                    }
                    view.requestFocusNodeHref(handler.obtainMessage())
                    true
                }
                else -> false
            }
        }
    }
}
