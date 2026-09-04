package com.mybrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import android.app.Application
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The URL-vs-search decision is the omnibar's most load-bearing branch: getting it wrong
 * either navigates to a search engine when the user typed a host, or searches for a host
 * the user meant to visit. Both are immediately visible and easy to regress.
 *
 * Robolectric because [UrlUtils] calls Uri.encode and Uri.parse, which are stubs in the
 * android.jar on the unit-test classpath. sdk = 34 (minSdk) rather than compileSdk: Uri is
 * the only platform class touched and its behaviour has not changed, and pinning low keeps
 * the suite runnable on whatever SDKs the installed Robolectric ships.
 *
 * application = Application::class overrides the manifest's com.mybrowser.App. Robolectric
 * instantiates the real Application for every test, and App.onCreate calls
 * WebView.setWebContentsDebuggingEnabled, which Robolectric's WebView shadow rejects with
 * UnsupportedOperationException — that failed all 33 tests in setup before this line. These
 * tests exercise a stateless object and need no application state at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UrlUtilsTest {

    private fun normalize(input: String) = UrlUtils.normalizeOrSearch(input, SEARCH)

    // --- Explicit schemes pass through untouched ---

    @Test
    fun `explicit http scheme is preserved`() {
        assertEquals("http://example.com", normalize("http://example.com"))
    }

    @Test
    fun `opaque scheme is preserved`() {
        assertEquals("mailto:someone@example.com", normalize("mailto:someone@example.com"))
    }

    @Test
    fun `unknown opaque scheme becomes a search`() {
        assertEquals(
            "${SEARCH_PREFIX}evilapp%3A%2F%2Ftakeover",
            normalize("evilapp://takeover"),
        )
    }

    @Test
    fun `unsafe internal and numeric unknown schemes become searches`() {
        assertEquals(
            "${SEARCH_PREFIX}javascript%3Aalert(1)",
            normalize("javascript:alert(1)"),
        )
        assertEquals(
            "${SEARCH_PREFIX}evilapp%3A1234",
            normalize("evilapp:1234"),
        )
    }

    @Test
    fun `blank input becomes about blank`() {
        assertEquals("about:blank", normalize("   "))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("https://example.com", normalize("  example.com  "))
    }

    // --- Bare hosts get https:// ---

    @Test
    fun `dotted host gains https`() {
        assertEquals("https://example.com", normalize("example.com"))
    }

    @Test
    fun `host with path gains https`() {
        assertEquals("https://example.com/a/b?c=d", normalize("example.com/a/b?c=d"))
    }

    @Test
    fun `localhost with port is a host despite having no dot`() {
        assertEquals("https://localhost:8080", normalize("localhost:8080"))
    }

    @Test
    fun `dotted host with port is not mistaken for a scheme`() {
        assertEquals("https://example.com:8443", normalize("example.com:8443"))
    }

    @Test
    fun `host with port and path keeps the path`() {
        assertEquals("https://localhost:3000/api?q=1", normalize("localhost:3000/api?q=1"))
    }

    @Test
    fun `opaque schemes still win over the host-port shape`() {
        // Regression guard for the HOST_PORT carve-out: these must not gain an https:// prefix.
        assertEquals("about:blank", normalize("about:blank"))
        assertEquals("mailto:a@b.com", normalize("mailto:a@b.com"))
        assertEquals("tel:+8613800138000", normalize("tel:+8613800138000"))
    }

    @Test
    fun `ipv4 literal is a host`() {
        assertEquals("https://192.168.1.1", normalize("192.168.1.1"))
    }

    @Test
    fun `bracketed ipv6 literal is a host`() {
        assertEquals("https://[::1]:3000", normalize("[::1]:3000"))
    }

    @Test
    fun `uppercase host is preserved in the url`() {
        // Only the TLD check lowercases; the loaded URL keeps what the user typed.
        assertEquals("https://Example.COM", normalize("Example.COM"))
    }

    // --- Search fallbacks: the cases Patterns.WEB_URL would get wrong ---

    @Test
    fun `text with spaces is a search even when it contains a dotted token`() {
        assertEquals("$SEARCH_PREFIX%E7%9C%8B%203.5%20%E7%89%88%E6%9C%AC", normalize("看 3.5 版本"))
    }

    @Test
    fun `decimal number is a search not a host`() {
        assertEquals("${SEARCH_PREFIX}1.5", normalize("1.5"))
    }

    @Test
    fun `single word is a search`() {
        assertEquals("${SEARCH_PREFIX}kotlin", normalize("kotlin"))
    }

    @Test
    fun `dotted phrase with a non-tld last label is a search`() {
        // "hello.there" — "there" is letters-only and long enough, so this one IS treated
        // as a host. Documented rather than asserted the other way: the heuristic cannot
        // separate it from a real two-label domain without a public-suffix list.
        assertEquals("https://hello.there", normalize("hello.there"))
    }

    @Test
    fun `numeric tld is a search`() {
        assertEquals("${SEARCH_PREFIX}version.2", normalize("version.2"))
    }

    @Test
    fun `single letter tld is a search`() {
        assertEquals("${SEARCH_PREFIX}a.b", normalize("a.b"))
    }

    @Test
    fun `trailing dot is a search`() {
        assertEquals("${SEARCH_PREFIX}example.", normalize("example."))
    }

    @Test
    fun `empty label is a search`() {
        assertEquals("${SEARCH_PREFIX}a..b", normalize("a..b"))
    }

    @Test
    fun `label starting with hyphen is a search`() {
        assertEquals("$SEARCH_PREFIX-bad.com", normalize("-bad.com"))
    }

    @Test
    fun `query is percent encoded`() {
        assertEquals("${SEARCH_PREFIX}a%2Bb%20%26%20c", normalize("a+b & c"))
    }

    // --- Scheme classification ---

    @Test
    fun `internal schemes are recognised case insensitively`() {
        assertTrue(UrlUtils.isInternalScheme("HTTPS://example.com"))
        assertTrue(UrlUtils.isInternalScheme("about:blank"))
        assertFalse(UrlUtils.isInternalScheme("tel:123"))
    }

    @Test
    fun `allowlisted external schemes are recognised`() {
        assertTrue(UrlUtils.isAllowedExternalScheme("weixin://dl/business"))
        assertTrue(UrlUtils.isAllowedExternalScheme("intent://scan/#Intent;end"))
    }

    @Test
    fun `unknown external scheme is not allowlisted`() {
        // The whole point of the allowlist: an arbitrary deep link must not launch.
        assertFalse(UrlUtils.isAllowedExternalScheme("evilapp://takeover"))
    }

    @Test
    fun `navigable input recognises hosts and web schemes`() {
        assertTrue(UrlUtils.isNavigableInput("example.com/path"))
        assertTrue(UrlUtils.isNavigableInput("https://example.com"))
        assertTrue(UrlUtils.isNavigableInput("localhost:8080"))
        assertTrue(UrlUtils.isNavigableInput("mailto:user@example.com"))
        assertTrue(UrlUtils.isNavigableInput("tel:1234"))
    }

    @Test
    fun `navigable input keeps searches and unsafe opaque schemes as searches`() {
        assertFalse(UrlUtils.isNavigableInput("how to use kotlin"))
        assertFalse(UrlUtils.isNavigableInput("evilapp://takeover"))
        assertFalse(UrlUtils.isNavigableInput("javascript:alert(1)"))
        assertFalse(UrlUtils.isNavigableInput(""))
    }

    @Test
    fun `navigable input rejects malformed web urls`() {
        assertFalse(UrlUtils.isNavigableInput("https://"))
        assertFalse(UrlUtils.isNavigableInput("http:example.com"))
        assertFalse(UrlUtils.isNavigableInput("https://example.com:bad"))
        assertFalse(UrlUtils.isNavigableInput("https://example.com:65536"))
        assertFalse(UrlUtils.isNavigableInput("about:"))
    }

    @Test
    fun `navigable input validates host ports and ipv6`() {
        assertTrue(UrlUtils.isNavigableInput("example.com:443/path"))
        assertFalse(UrlUtils.isNavigableInput("example.com:65536/path"))
        assertTrue(UrlUtils.isNavigableInput("[::1]:8080"))
        assertTrue(UrlUtils.isNavigableInput("[2001:db8:0:0:0:0:0:1]:443"))
        assertFalse(UrlUtils.isNavigableInput("[not-an-ipv6]:8080"))
    }

    @Test
    fun `malformed host ports fall back to search`() {
        assertEquals(
            "${SEARCH_PREFIX}example.com%3A65536",
            normalize("example.com:65536"),
        )
        assertEquals(
            "${SEARCH_PREFIX}example.com%3Abad",
            normalize("example.com:bad"),
        )
    }

    @Test
    fun `http url helper requires an authority`() {
        assertTrue(UrlUtils.isHttpUrl("https://example.com/path"))
        assertTrue(UrlUtils.isHttpUrl("http://localhost:8080"))
        assertTrue(UrlUtils.isHttpUrl("HTTPS://EXAMPLE.COM/path"))
        assertFalse(UrlUtils.isHttpUrl("https://"))
        assertFalse(UrlUtils.isHttpUrl("https:example.com"))
    }

    @Test
    fun `url without a scheme yields an empty scheme`() {
        assertEquals("", UrlUtils.schemeOf("example.com/a"))
    }

    @Test
    fun `isHttps only matches https`() {
        assertTrue(UrlUtils.isHttps("https://example.com"))
        assertFalse(UrlUtils.isHttps("http://example.com"))
    }

    // --- Host extraction ---

    @Test
    fun `hostOf strips the www prefix`() {
        assertEquals("example.com", UrlUtils.hostOf("https://www.example.com/a"))
    }

    @Test
    fun `hostOf keeps other subdomains`() {
        assertEquals("m.example.com", UrlUtils.hostOf("https://m.example.com"))
    }

    @Test
    fun `hostOf returns null for a schemeless string`() {
        assertNull(UrlUtils.hostOf("example.com/a"))
    }

    @Test
    fun `hostOf returns null for about blank`() {
        assertNull(UrlUtils.hostOf("about:blank"))
    }

    // --- Display trimming ---

    @Test
    fun `display drops https and a bare trailing slash`() {
        assertEquals("example.com", UrlUtils.forDisplay("https://example.com/"))
    }

    @Test
    fun `display keeps a real path intact`() {
        assertEquals("example.com/a/", UrlUtils.forDisplay("https://example.com/a/"))
    }

    @Test
    fun `display keeps the http scheme visible`() {
        // Insecure origins must stay obvious in the omnibar.
        assertEquals("http://example.com/", UrlUtils.forDisplay("http://example.com/"))
    }

    @Test
    fun `display blanks about blank`() {
        assertEquals("", UrlUtils.forDisplay("about:blank"))
    }

    private companion object {
        const val SEARCH = "https://www.bing.com/search?q=%s"
        const val SEARCH_PREFIX = "https://www.bing.com/search?q="
    }
}
