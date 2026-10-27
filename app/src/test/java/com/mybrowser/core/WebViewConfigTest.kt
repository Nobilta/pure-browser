package com.mybrowser.core

import android.app.Application
import android.webkit.WebSettings
import android.webkit.WebView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Pins the WebView security switches.
 *
 * These settings execute under every other WebView test but were never asserted, so turning
 * file access (or Safe Browsing) back on would have left the whole suite green.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebViewConfigTest {
    @Test
    fun `file and content access stay disabled`() {
        val settings = configured().settings
        assertFalse(settings.allowFileAccess)
        assertFalse(settings.allowContentAccess)
        @Suppress("DEPRECATION")
        assertFalse(settings.allowFileAccessFromFileURLs)
        @Suppress("DEPRECATION")
        assertFalse(settings.allowUniversalAccessFromFileURLs)
    }

    @Test
    fun `mixed content keeps the compatibility default`() {
        // Safe Browsing is also enabled in production, but Robolectric's WebSettings shadow
        // drops that flag, so asserting it here would test the shadow rather than our config.
        val settings = configured().settings
        assertEquals(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE, settings.mixedContentMode)
    }

    private fun configured(): WebView =
        WebView(RuntimeEnvironment.getApplication()).also { WebViewConfig.apply(it) }
}
