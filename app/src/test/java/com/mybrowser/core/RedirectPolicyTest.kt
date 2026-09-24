package com.mybrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URL

/**
 * The shared hop rule. What a client is willing to talk to stays the caller's test; what must not
 * differ between clients is decided here, and that is the part a page can attack: a redirect that
 * leaves http(s), or one that walks an https request down to http.
 */
class RedirectPolicyTest {

    private val https = URL("https://example.test/list.txt")
    private val http = URL("http://example.test/list.txt")

    private fun httpOnly(value: String) = value.startsWith("http://") || value.startsWith("https://")

    @Test
    fun aRelativeLocationResolvesAgainstTheCurrentHop() {
        assertEquals(
            "https://example.test/moved/list.txt",
            RedirectPolicy.next(https, "/moved/list.txt") { httpOnly(it) }?.toString(),
        )
        assertEquals(
            "https://other.test/list.txt",
            RedirectPolicy.next(https, "https://other.test/list.txt") { httpOnly(it) }?.toString(),
        )
    }

    @Test
    fun aHopThatLeavesHttpIsRefused() {
        for (location in listOf("file:///etc/hosts", "data:text/plain,x", "javascript:alert(1)", "ftp://example.test/a")) {
            assertNull(location, RedirectPolicy.next(https, location) { httpOnly(it) })
        }
    }

    @Test
    fun httpsIsNeverDowngradedButHttpMayBeUpgraded() {
        assertNull(RedirectPolicy.next(https, "http://example.test/list.txt") { httpOnly(it) })
        assertEquals(
            "https://example.test/list.txt",
            RedirectPolicy.next(http, "https://example.test/list.txt") { httpOnly(it) }?.toString(),
        )
    }

    @Test
    fun absentOrUnparsableLocationsAreRefusedRatherThanGuessedAt() {
        assertNull(RedirectPolicy.next(https, null) { httpOnly(it) })
        assertNull(RedirectPolicy.next(https, "") { httpOnly(it) })
        assertNull(RedirectPolicy.next(https, "   ") { httpOnly(it) })
        // A malformed port is what the platform's URL parser rejects; the hop must not be followed.
        assertNull(RedirectPolicy.next(https, "https://example.test:notaport/x") { httpOnly(it) })
    }

    @Test
    fun theCallersOwnTestDecidesWhetherATargetIsAcceptable() {
        // The updater's shape: a client that only accepts its own hosts refuses a valid http URL.
        assertNull(RedirectPolicy.next(https, "https://elsewhere.test/a.apk") { false })
        assertEquals(5, RedirectPolicy.MAX_HOPS)
    }
}
