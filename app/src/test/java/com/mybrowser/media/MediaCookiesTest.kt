package com.mybrowser.media

import android.app.Application
import android.webkit.CookieManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaCookiesTest {
    @Test fun returningToTheInitiatingSiteRestoresItsCookiesWithoutLeakingThemToTheMiddleHop() {
        val manager = CookieManager.getInstance()
        val info = listOf("strict=1; SameSite=Strict", "lax=1; SameSite=Lax",
            "none=1; SameSite=None; Secure", "invalid=1; SameSite=None")
        val main = MediaCookies(manager, "https://a.test/watch", true, false) { _, url ->
            if (url.startsWith("https://a.test/")) info else listOf("b=1; SameSite=Strict")
        }
        assertEquals("strict=1; lax=1; none=1", main.header("https://a.test/start", null))
        assertNull(main.header("https://b.test/hop", null))
        assertEquals("strict=1; lax=1; none=1", main.header("https://a.test/final", null))
        val subresource = MediaCookies(manager, "https://a.test/watch", false, false) { _, _ -> info }
        assertNull(subresource.header("https://b.test/hop", null))
        assertEquals("strict=1; lax=1; none=1", subresource.header("https://a.test/final", null))
    }

    @Test fun eachHostUsesItsOwnJarAndResponseCookiesUpdateOnlyThatHost() {
        val manager = CookieManager.getInstance()
        val a = "https://media.example.test/video"
        val b = "https://cdn.example.test/video"
        manager.setCookie(a, "session=media; Path=/")
        manager.setCookie(b, "session=cdn; Path=/")
        val cookies = MediaCookies(manager, a, true, true) { jar, url -> jar.getCookie(url)?.split("; ") ?: emptyList() }
        try {
            assertEquals("session=media", cookies.header(a, "session=stale"))
            assertEquals("session=cdn", cookies.header(b, null))
            cookies.store(b, "session=updated; Path=/")
            assertEquals("session=updated", cookies.header(b, null))
            assertEquals("session=media", manager.getCookie(a))
        } finally { manager.removeAllCookies(null) }
    }

    @Test fun crossSiteCookiesRespectRequestTypeThirdPartySettingAndPartitionLimit() {
        val manager = CookieManager.getInstance()
        val info = listOf("strict=1; SameSite=Strict", "lax=1; SameSite=Lax", "none=1; SameSite=None; Secure",
            "partition=1; SameSite=None; Secure; Partitioned")
        fun header(main: Boolean, thirdParty: Boolean): String? {
            val cookies = MediaCookies(manager, "https://site.test", main, thirdParty) { _, _ -> info }
            return cookies.header("https://cdn.test/video", null)
        }
        assertEquals("lax=1; none=1", header(true, false))
        assertEquals("none=1", header(false, true))
        assertNull(header(false, false))
        val legacy = MediaCookies(manager, "https://site.test", true, true) { _, _ -> null }
        manager.setCookie("https://cdn.test", "secret=1")
        try {
            assertNull(legacy.header("https://cdn.test", null))
        } finally { manager.removeAllCookies(null) }
    }
}
