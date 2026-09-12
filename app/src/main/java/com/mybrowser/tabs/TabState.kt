package com.mybrowser.tabs

import android.graphics.Bitmap
import android.os.Bundle

/**
 * State of a single browser tab.
 *
 * A tab is a lightweight record: URL, title, scroll position, and the WebView's saved
 * state bundle. The actual WebView is pooled and swapped between tabs.
 */
data class TabState(
    val id: String,
    var url: String = "",
    var title: String = "",
    var favicon: Bitmap? = null,
    var thumbnail: Bitmap? = null,
    var savedState: Bundle? = null,
    var group: String = "",
)
