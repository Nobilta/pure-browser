package com.mybrowser.backup

import org.junit.Assert.*
import org.junit.Test

class ImportPreviewTextTest {
    @Test fun limitsTextBeforeLayoutWithoutBreakingSurrogatePairs() {
        assertEquals("0.11", ImportPreviewText.limit("0.11"))
        assertEquals("abc", ImportPreviewText.limit("abc", 3))
        assertEquals("abc…", ImportPreviewText.limit("abc😀tail", 5))
        val result = ImportPreviewText.limit("x".repeat(1_500_000))
        assertEquals(768, result.length)
        assertTrue(result.endsWith("…"))
    }

    @Test fun formatsOnlyABoundedPrefixOfUnknownIdsBeforeJoining() {
        val large = "x".repeat(1_500_000)
        val ids = object : AbstractList<String>() {
            override val size = 10_000
            override fun get(index: Int): String {
                check(index < 8) { "Preview must not format the undisplayed entries" }
                return large
            }
        }
        val result = ImportPreviewText.unknownIds(ids)
        assertTrue(result.length <= 8 * SettingsBackupCodec.MAX_BUILT_IN_ID_CHARS + 17)
        assertTrue(result.endsWith(", …"))
        assertEquals("future_one, future_two", ImportPreviewText.unknownIds(listOf("future_one", "future_two")))
        assertEquals("", ImportPreviewText.unknownIds(emptyList()))
    }
}
