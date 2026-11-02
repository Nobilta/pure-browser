package com.mybrowser.data

import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkHtmlTest {
    @Test
    fun `capped export drops rows instead of failing`() {
        val url = "https://example.test/" + "a".repeat(8_000)
        val rows = List(1_100) { index ->
            Bookmark(
                id = index.toLong(),
                title = "bookmark $index",
                url = url,
                faviconUrl = null,
                createdAt = 0L,
                folderId = 0L,
                position = index.toLong(),
            )
        }
        val result = BookmarkHtml.encodeCapped(rows)
        assertTrue("must report the omitted rows", result.included < rows.size)
        assertTrue(
            "encoded file must stay inside the importer limit",
            result.bytes.size <= BookmarkHtml.MAX_BYTES,
        )
    }
}
