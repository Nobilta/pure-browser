package com.mybrowser.core

import android.app.Application
import com.mybrowser.search.SearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression tests for the single omnibar submission policy. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NavigationPolicyTest {

    private val engine = SearchEngine(
        id = "test",
        name = "Test",
        searchUrlTemplate = "https://search.example/?q=%s",
    )

    @Test
    fun `host input becomes a web load`() {
        assertEquals(
            NavigationTarget.Load("https://example.com/path"),
            NavigationPolicy.resolve("example.com/path", engine),
        )
    }

    @Test
    fun `ordinary text becomes an encoded search`() {
        assertEquals(
            NavigationTarget.Load("https://search.example/?q=kotlin%20coroutines"),
            NavigationPolicy.resolve("kotlin coroutines", engine),
        )
    }

    @Test
    fun `allowlisted opaque scheme is handed to another app`() {
        assertEquals(
            NavigationTarget.External("mailto:user@example.com"),
            NavigationPolicy.resolve("mailto:user@example.com", engine),
        )
    }

    @Test
    fun `unsafe opaque scheme cannot bypass the search branch`() {
        val result = NavigationPolicy.resolve("javascript:alert(1)", engine)
        assertTrue(result is NavigationTarget.Load)
        assertEquals(
            "https://search.example/?q=javascript%3Aalert(1)",
            (result as NavigationTarget.Load).url,
        )
    }

    @Test
    fun `malformed http input is searched`() {
        assertEquals(
            NavigationTarget.Load("https://search.example/?q=https%3A%2F%2F"),
            NavigationPolicy.resolve("https://", engine),
        )
    }
}
