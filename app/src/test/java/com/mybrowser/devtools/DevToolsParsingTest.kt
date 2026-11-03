package com.mybrowser.devtools

import org.junit.Assert.*
import org.junit.Test

class DevToolsParsingTest {
    @Test fun queryParametersKeepDuplicatesEmptyValuesAndRawEncoding() {
        val params = parseQuery("https://example.com/p?a=1&a=2&empty=&flag&sp=a%20b&plus=a+b&unicode=%E4%B8%AD")
        assertEquals(listOf("a", "a", "empty", "flag", "sp", "plus", "unicode"), params.map { it.name })
        assertEquals(listOf("1", "2", "", null, "a b", "a b", "中"), params.map { it.value })
        assertEquals("sp=a%20b", params[4].raw)
        assertTrue(params[4].encodedDiffers)
        assertFalse(params[0].encodedDiffers)
        assertTrue(params[5].encodedDiffers)
    }

    @Test fun cookieInfoLineDoesNotSplitOnExpiryCommaAndKeepsUnknownAttributes() {
        val cookie = StorageReader.parseCookieInfoLine(
            "sid=a=b; Domain=.example.com; Path=/app; Expires=Wed, 21 Oct 2037 07:28:00 GMT; Secure; HttpOnly; SameSite=Lax"
        )!!
        assertEquals("sid", cookie.name)
        assertEquals("a=b", cookie.value)
        assertEquals(".example.com", cookie.domain)
        assertEquals("/app", cookie.path)
        assertEquals("Wed, 21 Oct 2037 07:28:00 GMT", cookie.expires)
        assertEquals("true", cookie.secure)
        assertEquals("true", cookie.httpOnly)
        assertEquals("Lax", cookie.sameSite)
    }

    @Test fun fallbackCookieHeaderDoesNotInventAttributes() {
        val cookies = StorageReader.parseCookieHeader("a=1; b=x=y; c=; broken")
        assertEquals(listOf("a", "b", "c", "broken"), cookies.map { it.name })
        assertEquals(listOf("1", "x=y", "", ""), cookies.map { it.value })
        assertNull(cookies[0].secure)
        assertNull(cookies[0].httpOnly)
        assertNull(cookies[0].sameSite)
    }

    @Test fun bodyStatesMapFromPageValues() {
        assertEquals(BodyState.TRUNCATED, BodyReason.ofState("TRUNCATED"))
        assertEquals(BodyState.NOT_COLLECTED, BodyReason.ofState("nonsense"))
        assertEquals(BodyReason.SIZE_LIMIT, BodyReason.of("size_limit"))
        assertEquals(BodyReason.NONE, BodyReason.of("page supplied prose"))
    }
}
