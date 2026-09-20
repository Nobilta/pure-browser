package com.mybrowser.core

import android.app.Activity
import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebViewPoolTest {
    @Test fun viewsStartWithTheirFinalActivityAndNeverCrossHosts() {
        val first = Robolectric.buildActivity(Activity::class.java).setup()
        val second = Robolectric.buildActivity(Activity::class.java).setup()
        val pool = WebViewPool(first.get())
        val next = WebViewPool(second.get())
        try {
            val view = pool.acquireFresh()
            assertSame(first.get(), view.context)
            assertNull(view.url) // Profile and popup transport must attach before navigation.
            pool.close()
            assertTrue(shadowOf(view).wasDestroyCalled())
            assertFalse(pool.owns(view))
            assertThrows(IllegalStateException::class.java) { pool.acquireFresh() }
            val replacement = next.acquireFresh()
            assertNotSame(view, replacement)
            assertSame(second.get(), replacement.context)
        } finally {
            pool.close(); next.close()
            first.pause().stop().destroy(); second.pause().stop().destroy()
        }
    }

    @Test fun closeDuringPopupHandoffDefersNativeDestructionButDisablesCallbacks() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val pool = WebViewPool(controller.get())
        try {
            val opener = pool.acquireFresh()
            val popup = pool.acquireFresh()
            val transfer = pool.holdForPopup(opener, popup)
            pool.close()
            assertFalse(pool.owns(opener))
            assertFalse(shadowOf(opener).wasDestroyCalled())
            assertFalse(shadowOf(popup).wasDestroyCalled())
            assertSame(DetachedWebViewClient, opener.webViewClient)
            transfer.close()
            transfer.close()
            pool.discard(opener)
            assertTrue(shadowOf(opener).wasDestroyCalled())
            assertTrue(shadowOf(popup).wasDestroyCalled())
        } finally { pool.close(); controller.pause().stop().destroy() }
    }

    @Test fun overlappingTransportsReleaseEachViewOnlyAfterItsLastConsumer() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val pool = WebViewPool(controller.get())
        try {
            val opener = pool.acquireFresh()
            val first = pool.acquireFresh()
            val second = pool.acquireFresh()
            val a = pool.holdForPopup(opener, first)
            val b = pool.holdForPopup(opener, second)
            pool.close()
            a.close()
            assertTrue(shadowOf(first).wasDestroyCalled())
            assertFalse(shadowOf(opener).wasDestroyCalled())
            b.close()
            assertTrue(shadowOf(opener).wasDestroyCalled())
            assertTrue(shadowOf(second).wasDestroyCalled())
        } finally { pool.close(); controller.pause().stop().destroy() }
    }
}
