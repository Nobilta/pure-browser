package com.mybrowser.filter

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CosmeticRuleBridgeTest {
    @Test fun untrustedCallsAndClosedViewsNeverReachTheRuleEngine() {
        val queries = mutableListOf<String>()
        val bridge = CosmeticRuleBridge { queries += it; ".ad{display:none!important}" }
        assertEquals("", bridge.css("page-supplied", "https://example.com/"))
        assertEquals("", bridge.css(null, "https://example.com/"))
        for (url in listOf("file:///data/private", "about:blank", "https://", "https://example.com/" + "a".repeat(8192))) {
            assertEquals("", bridge.css(bridge.capability, url))
        }
        assertTrue(queries.isEmpty())
        assertTrue(bridge.css(bridge.capability, "https://example.com/redirected").contains("display:none"))
        assertEquals(listOf("https://example.com/redirected"), queries)
        bridge.close()
        assertEquals("", bridge.css(bridge.capability, "https://example.com/"))
        assertEquals(1, queries.size)
    }

    @Test fun capabilitiesArePerViewAndLookupFailuresDoNotAbortPageLoading() {
        val first = CosmeticRuleBridge { "css" }
        val second = CosmeticRuleBridge { error("engine unavailable") }
        assertNotEquals(first.capability, second.capability)
        assertEquals("", first.css(second.capability, "https://example.com/"))
        assertEquals("", second.css(second.capability, "https://example.com/"))
    }
}
