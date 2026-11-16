package com.mybrowser.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FtpTargetTest {

    @Test
    fun `credentials port and path come from the URL`() {
        val target = requireNotNull(FtpTarget.parse("ftp://alice:s3cret@files.example.test:2121/pub/report%20final.pdf"))

        assertEquals("files.example.test", target.host)
        assertEquals(2121, target.port)
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
    fun `an address that cannot name one file is not a target`() {
        assertNull(FtpTarget.parse("ftp://files.example.test/"))
        assertNull(FtpTarget.parse("ftp://files.example.test"))
        assertNull(FtpTarget.parse("https://files.example.test/report.bin"))
        assertNull(FtpTarget.parse("ftp:///report.bin"))
    }
}

class HlsPlaylistTest {

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

        assertTrue(failure.exceptionOrNull() is IOException)
    }

    @Test
    fun `the default IV is the segment number`() {
        assertEquals(
            "000000000000000000000000000002a4",
            HlsPlaylist.defaultIv(0x2a4).joinToString("") { "%02x".format(it) },
        )
    }
}
