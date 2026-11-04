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
    @Test fun eachHostUsesItsOwnJarAndResponseCookiesUpdateOnlyThatHost() {
        val manager = CookieManager.getInstance()
        val a = "https://media.example.test/video"
        val b = "https://cdn.example.test/video"
        manager.setCookie(a, "session=media; Path=/")
        manager.setCookie(b, "session=cdn; Path=/")
        val cookies = MediaCookies(manager, a, true, true) { jar, url -> jar.getCookie(url)?.split("; ") ?: emptyList() }
        try {
            cookies.visit(a)
            assertEquals("session=media", cookies.header(a, "session=stale"))
            cookies.visit(b)
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
            cookies.visit("https://cdn.test/video")
            return cookies.header("https://cdn.test/video", null)
        }
        assertEquals("lax=1; none=1", header(true, false))
        assertEquals("none=1", header(false, true))
        assertNull(header(false, false))
        val legacy = MediaCookies(manager, "https://site.test", true, true) { _, _ -> null }
        manager.setCookie("https://cdn.test", "secret=1")
        try {
            legacy.visit("https://cdn.test")
            assertNull(legacy.header("https://cdn.test", null))
        } finally { manager.removeAllCookies(null) }
    }
}
