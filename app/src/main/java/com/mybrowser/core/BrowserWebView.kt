package com.mybrowser.core

import android.content.Context
import android.webkit.WebView

/** Chromium pauses video on window hiding; only an opted-in playing page may stay active. */
class BrowserWebView(context: Context) : WebView(context) {
    private var actualVisibility = VISIBLE
    var keepMediaOnWindowHidden = false
        set(value) {
            if (field == value) return
            field = value
            super.onWindowVisibilityChanged(if (value) VISIBLE else actualVisibility)
        }
    override fun onWindowVisibilityChanged(visibility: Int) {
        actualVisibility = visibility
        super.onWindowVisibilityChanged(if (keepMediaOnWindowHidden) VISIBLE else visibility)
    }
}
