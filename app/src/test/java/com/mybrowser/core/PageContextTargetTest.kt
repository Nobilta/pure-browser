package com.mybrowser.core

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class)
class PageContextTargetTest {
    @Test
    fun linkedImageKeepsTwoDistinctTargets() {
        val target = PageContextTarget.create("https://example.com/page", "https://cdn.example.com/image.png", "Picture")!!
        assertEquals("https://example.com/page", target.linkUrl)
        assertEquals("https://cdn.example.com/image.png", target.imageUrl)
        assertEquals("Picture", target.title)
    }

    @Test
    fun rejectsScriptLocalAndMalformedTargets() {
        listOf("javascript:alert(1)", "file:///data/secret", "data:text/html,test", "https://", "https://ex\nample.com").forEach {
            assertNull(PageContextTarget.create(it, it))
        }
        val target = PageContextTarget.create("javascript:alert(1)", "https://example.com/image.png")!!
        assertNull(target.linkUrl)
        assertNotNull(target.imageUrl)
    }

    @Test
    fun boundsUntrustedPageMetadata() {
        assertNull(PageContextTarget.create("https://example.com/" + "x".repeat(8192), null))
        val target = PageContextTarget.create(" https://example.com/ ", null, "\n" + "x".repeat(1024))!!
        assertEquals("https://example.com/", target.linkUrl)
        assertEquals(512, target.title.length)
        assertFalse(target.title.contains('\n'))
    }
}
