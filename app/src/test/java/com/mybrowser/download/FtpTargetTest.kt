package com.mybrowser.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FtpTargetTest {
    @Test fun `explicit FTP ports must be valid socket ports`() {
        listOf(0, 65536, 70000).forEach { assertNull(FtpTarget.parse("ftp://files.test:$it/file.bin")) }
        assertEquals(65535, FtpTarget.parse("ftp://files.test:65535/file.bin")?.port)
    }

    @Test
    fun `credentials port and path come from the URL`() {
        val target = requireNotNull(FtpTarget.parse("ftp://alice:s3cret@files.example.test:2121/pub/report%20final.pdf"))

        assertEquals("files.example.test", target.host)
        assertEquals(2121, target.port)
        assertEquals("domain:user+", FtpTarget.parse("ftp://domain%3Auser+:pwd@files.example.test/report.pdf")?.user)
        assertEquals("alice", target.user)
        assertEquals("s3cret", target.password)
        // The path is decoded: FTP has no quoting, so the server is asked for the name it printed.
        assertEquals("/pub/report final.pdf", target.path)
    }

    @Test
    fun `a URL without credentials logs in anonymously on the default port`() {
        val target = requireNotNull(FtpTarget.parse("ftp://files.example.test/pub/report.bin"))

        assertEquals("anonymous", target.user)
        assertEquals("anonymous@", target.password)
        assertEquals(21, target.port)
    }

    @Test
    fun `a decoded line break is refused instead of becoming a command`() {
        // FTP has no quoting: a CR/LF inside a component would arrive as a second command.
        assertNull(FtpTarget.parse("ftp://files.example.test/pub/a%0d%0aDELE%20important.txt"))
        assertNull(FtpTarget.parse("ftp://files.example.test/pub/a%0aDELE%20important.txt"))
        assertNull(FtpTarget.parse("ftp://user%0d%0aPASS%20x:pw@files.example.test/report.bin"))
        assertNull(FtpTarget.parse("ftp://user:pw%0d%0aDELE%20important.txt@files.example.test/report.bin"))
    }

    @Test
    fun `an address that cannot name one file is not a target`() {
        assertNull(FtpTarget.parse("ftp://files.example.test/"))
        assertNull(FtpTarget.parse("ftp://files.example.test"))
        assertNull(FtpTarget.parse("https://files.example.test/report.bin"))
        assertNull(FtpTarget.parse("ftp:///report.bin"))
    }
}

class HlsPlaylistTest {
    @Test fun `only HTTP playlists use the HLS transport`() {
        assertTrue(HlsPlaylist.isPlaylist("https://example.test/x.m3u8?q=1", ""))
        assertTrue(HlsPlaylist.isPlaylist("HTTP://example.test/stream", "application/vnd.apple.mpegurl"))
        assertTrue(!HlsPlaylist.isPlaylist("ftp://example.test/x.m3u8", "application/vnd.apple.mpegurl"))
        assertTrue(!HlsPlaylist.isPlaylist("ftp://example.test/stream", "audio/x-mpegurl"))
    }

    @Test fun `a radio URL list is unsupported HLS`() {
        val failure = runCatching {
            HlsPlaylist.media("#EXTM3U\nhttps://radio.test/live\n", "https://example.test/radio.m3u8")
        }.exceptionOrNull()
        assertTrue(failure is UnsupportedHlsException)
    }

    @Test
    fun `a master playlist resolves to its highest bandwidth variant`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
            high/index.m3u8
        """.trimIndent()

        assertEquals(
            "https://cdn.example.test/media/high/index.m3u8",
            HlsPlaylist.variant(master, "https://cdn.example.test/media/master.m3u8"),
        )
        assertNull(HlsPlaylist.variant("#EXTM3U\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST", "https://cdn.example.test/x.m3u8"))
    }

    @Test
    fun `a higher bandwidth external audio variant yields to the best self contained rendition`() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="external",URI="audio.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="embedded",NAME="In-band"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="embedded",NAME="Alternative",URI="alternative.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=300,AUDIO="external"
            external.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=200,AUDIO="embedded"
            high.m3u8
        """.trimIndent()
        assertEquals("https://cdn.example.test/high.m3u8", HlsPlaylist.variant(master, "https://cdn.example.test/master.m3u8"))
    }

    @Test
    fun `IV accepts a short number but still rejects invalid or overflowing values`() {
        assertArrayEquals(HlsPlaylist.defaultIv(0xabc), HlsPlaylist.parseIv("0Xabc"))
        assertArrayEquals(ByteArray(16) { -1 }, HlsPlaylist.parseIv("0xffffffffffffffffffffffffffffffff"))
        listOf("0x", "0xgg", "0x100000000000000000000000000000000").forEach {
            assertNull(HlsPlaylist.parseIv(it))
        }
    }

    @Test
    fun `a master playlist without bandwidth still names its variant`() {
        // BANDWIDTH is required by RFC 8216, but a master playlist that omits it is still a master
        // playlist; treating it as media would fail with "no segments" instead of downloading.
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=640x360\nlow/index.m3u8\n"

        assertEquals(
            "https://cdn.example.test/media/low/index.m3u8",
            HlsPlaylist.variant(master, "https://cdn.example.test/media/master.m3u8"),
        )
    }

    @Test
    fun `a map after the last segment applies to nothing`() {
        val media = "#EXTM3U\n#EXTINF:4.0,\na.ts\n#EXT-X-MAP:URI=\"late.mp4\"\n#EXT-X-ENDLIST"

        val parsed = HlsPlaylist.media(media, "https://cdn.example.test/v/index.m3u8")

        assertEquals(listOf("https://cdn.example.test/v/a.ts"), parsed.items.map { it.uri })
    }

    @Test
    fun `a media playlist lists segments in order with its end marker`() {
        val media = """
            #EXTM3U
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:4.0,
            seg-7.m4s
            #EXTINF:4.0,
            seg-8.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        val parsed = HlsPlaylist.media(media, "https://cdn.example.test/v/index.m3u8")

        assertTrue(parsed.complete)
        assertEquals(
            listOf(
                "https://cdn.example.test/v/init.mp4",
                "https://cdn.example.test/v/seg-7.m4s",
                "https://cdn.example.test/v/seg-8.m4s",
            ),
            parsed.items.map { it.uri },
        )
        assertEquals(listOf(7L, 7L, 8L), parsed.items.map { it.sequence })
    }

    @Test
    fun `an AES-128 key applies to the segments that follow it`() {
        val iv = "0x0123456789abcdef0123456789abcdef"
        val media = """
            #EXTM3U
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=$iv
            #EXTINF:4.0,
            one.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4.0,
            two.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val parsed = HlsPlaylist.media(media, "https://cdn.example.test/v/index.m3u8")

        assertEquals("AES-128", parsed.items[0].key?.method)
        assertEquals("https://cdn.example.test/v/key.bin", parsed.items[0].key?.uri)
        assertArrayEquals(HlsPlaylist.parseIv(iv), parsed.items[0].key?.iv)
        assertNull(parsed.items[1].key)
    }

    @Test
    fun `a live playlist is parsed but not complete`() {
        val media = "#EXTM3U\n#EXTINF:4,\na.ts\n"

        assertTrue(!HlsPlaylist.media(media, "https://cdn.example.test/live.m3u8").complete)
    }

    @Test
    fun `a playlist without segments is refused`() {
        val failure = runCatching { HlsPlaylist.media("#EXTM3U\n#EXT-X-ENDLIST", "https://cdn.example.test/e.m3u8") }

        assertTrue(failure.exceptionOrNull() is UnsupportedHlsException)
    }

    @Test
    fun `the default IV is the segment number`() {
        assertEquals(
            "000000000000000000000000000002a4",
            HlsPlaylist.defaultIv(0x2a4).joinToString("") { "%02x".format(it) },
        )
    }
    @Test fun `identity keys survive alternate formats in either order and NONE clears encryption`() {
        val identity = "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\""
        val alternate = "#EXT-X-KEY:METHOD=AES-128,URI=\"other.bin\",KEYFORMAT=\"other\""
        for (keys in listOf("$identity\n$alternate", "$alternate\n$identity")) {
            val media = HlsPlaylist.media("#EXTM3U\n$keys\n#EXTINF:4,\na.ts\n" +
                "#EXT-X-KEY:METHOD=NONE\n#EXTINF:4,\nb.ts\n#EXT-X-ENDLIST", "https://example.com/list.m3u8")
            assertEquals("https://example.com/key.bin", media.items[0].key?.uri)
            assertNull(media.items[1].key)
        }
    }

    @Test fun `deterministic playlist errors are unsupported instead of network failures`() {
        for (tag in listOf(
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key\",KEYFORMAT=\"other\"",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n#EXT-X-MAP:URI=\"init\"",
            "#EXT-X-MAP:BYTERANGE=\"1@0\",URI=\"init\"",
            "#EXT-X-MAP:OTHER=1",
            "#EXT-X-KEY:METHOD=AES-128",
            "#EXT-X-KEY:URI=\"key\"",
            "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"key\"",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0xzz",
            "#EXT-X-MAP:URI=\"bad uri\"",
        )) {
            val failure = runCatching {
                HlsPlaylist.media("#EXTM3U\n$tag\n#EXTINF:4,\na.ts\n#EXT-X-ENDLIST", "https://example.com/list.m3u8")
            }.exceptionOrNull()
            assertTrue(tag, failure is UnsupportedHlsException)
        }
    }

}
