package com.mybrowser.ui.devtools

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class JsonViewerTest {
    @Test fun keysContainingSlashesGetDistinctPaths() {
        val rows = jsonRows("""{"a":{"b":1},"a/b":2}""", setOf("$", jsonChildPath("$", "a"), jsonChildPath("$", "a/b")))!!
        val keys = rows.map { it.path }
        assertEquals(keys.size, keys.distinct().size)
        assertTrue(keys.any { it.endsWith(":b") })
        assertTrue(keys.any { it.endsWith(":a/b") })
    }

    @Test fun lengthPrefixSeparatesSegmentsWithColons() {
        val rows = jsonRows("""{"a:b":1,"a":{"b":2}}""", setOf("$"))!!
        val paths = rows.map { it.path }
        assertEquals(paths.size, paths.distinct().size)
        assertTrue(jsonChildPath("$", "a/b") != jsonChildPath(jsonChildPath("$", "a"), "b"))
    }
}
