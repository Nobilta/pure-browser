package com.mybrowser.print

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView

/**
 * System printing and "save as PDF" for a loaded page.
 *
 * Printing uses the WebView's own adapter, so the system print UI previews the real page.
 * "Save as PDF" is one of the destinations that dialog offers; Android exposes no supported
 * API to drive that adapter into a file directly (its result callbacks cannot be constructed
 * outside the framework), so the menu deliberately opens this one flow.
 */
object PagePrinter {

    fun print(context: Context, webView: WebView, jobName: String): Boolean {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return false
        val adapter = runCatching { webView.createPrintDocumentAdapter(jobName) }.getOrNull() ?: return false
        return runCatching {
            manager.print(jobName, adapter, PrintAttributes.Builder().build())
            true
        }.getOrDefault(false)
    }
}
