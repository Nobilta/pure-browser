package com.mybrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedJsonTest {
    @Test fun delimitersAndEscapedContentStayWithinBudget() {
        val records = sequenceOf("\"a\\\"b\"", "123", "true")
        val result = boundedJsonArray(records, 12) { it }
        assertEquals("[\"a\\\"b\",123]", result)
        assertTrue(result.length <= 12)
    }

    @Test fun oversizedRecordsDoNotDiscardLaterSmallRecords() {
        assertEquals("[1,2]", boundedJsonArray(sequenceOf("123456789", "1", "999999", "2"), 5) { it })
        assertEquals("[]", boundedJsonArray(sequenceOf("1"), 2) { it })
    }
}
