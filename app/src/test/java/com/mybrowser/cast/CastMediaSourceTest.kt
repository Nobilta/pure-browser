package com.mybrowser.cast

import org.junit.Assert.*
import org.junit.Test

class CastMediaSourceTest {
    @Test fun signedManifestUrlIsPreservedWithoutCredentialsOrScriptSchemes() {
        val url = "https://media.example/live.m3u8?token=a%2Fb&expires=123"
        assertEquals(url, CastMediaSource.parse(url, "HLS")?.url)
        assertEquals("application/x-mpegURL", CastMediaSource.parse(url, "HLS")?.mime)
        assertNull(CastMediaSource.parse("https://user:pass@example/live.m3u8", "HLS"))
        assertNull(CastMediaSource.parse("blob:https://example/123", "HLS"))
        assertNull(CastMediaSource.parse("javascript:alert(1)", "HLS"))
        assertNull(CastMediaSource.parse("https://example/" + "a".repeat(8192), "HLS"))
    }
}
