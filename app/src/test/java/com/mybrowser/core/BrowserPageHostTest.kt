package com.mybrowser.core

import android.app.Activity
import android.app.Application
import android.webkit.WebView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BrowserPageHostTest {
    @Test fun failedConfigurationReleasesTheSameViewBeforeEvictionAndRetry() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val events = mutableListOf<String>()
        val released = mutableListOf<WebView>()
        val host = BrowserPageHost(activity.get()) { released += it; events += "release" }
        var attempts = 0
        try {
            val view = host.acquire(configure = {
                if (attempts++ == 0) throw OutOfMemoryError("fixture")
            }, evict = {
                assertTrue(shadowOf(released.single()).wasDestroyCalled())
                events += "evict"
                true
            })
            assertEquals(listOf("release", "evict"), events)
            assertNotSame(released.single(), view)
            host.current = view
            host.close()
            assertNull(host.current)
            assertEquals(2, released.size)
            assertTrue(shadowOf(view).wasDestroyCalled())
        } finally { host.close(); activity.pause().stop().destroy() }
    }

    @Test fun closingDuringPopupTransportReleasesChannelsOnceAndDefersNativeContents() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val released = mutableListOf<WebView>()
        val host = BrowserPageHost(activity.get()) { released += it }
        try {
            val opener = host.acquire({}, { false })
            val popup = host.acquire({}, { false })
            host.holdForPopup(opener, popup)
            host.current = popup
            host.ownerTabId = "popup"
            host.close()
            assertEquals(setOf(opener, popup), released.toSet())
            assertFalse(shadowOf(opener).wasDestroyCalled())
            assertFalse(shadowOf(popup).wasDestroyCalled())
            assertNull(host.ownerTabId)
            host.onPopupContentsAttached(popup, attach = false)
            host.discard(popup)
            assertTrue(shadowOf(opener).wasDestroyCalled())
            assertTrue(shadowOf(popup).wasDestroyCalled())
            assertEquals(2, released.size)
        } finally { host.close(); activity.pause().stop().destroy() }
    }
}
