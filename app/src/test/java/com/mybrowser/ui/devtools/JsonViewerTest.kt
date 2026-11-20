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
    @Test fun deeplyNestedResponseFallsBackToRawText() {
        val body = "[".repeat(20_000) + "0" + "]".repeat(20_000)
        assertEquals(null, JsonDocument.parse(body))
        assertEquals(1, JsonDocument.parse("[1]")!!.rows(setOf("$"))[1].value.toInt())
    }

    @Test fun aTreeMustAccountForTheWholeBody() {
        assertEquals(null, JsonDocument.parse("{\"one\":1}\n{\"two\":2}"))
        assertEquals(null, JsonDocument.parse("[] garbage"))
        assertEquals(null, JsonDocument.parse(""))
        assertEquals(1, JsonDocument.parse("[] \n")!!.rows(emptySet()).size)
    }

    @Test fun keysContainingSlashesGetDistinctPaths() {
        val rows = JsonDocument.parse("""{"a":{"b":1},"a/b":2}""")!!.rows(setOf("$", jsonChildPath("$", "a"), jsonChildPath("$", "a/b")))
        val keys = rows.map { it.path }
        assertEquals(keys.size, keys.distinct().size)
        assertTrue(keys.any { it.endsWith(":b") })
        assertTrue(keys.any { it.endsWith(":a/b") })
    }

    @Test fun lengthPrefixSeparatesSegmentsWithColons() {
        val rows = JsonDocument.parse("""{"a:b":1,"a":{"b":2}}""")!!.rows(setOf("$"))
        val paths = rows.map { it.path }
        assertEquals(paths.size, paths.distinct().size)
        assertTrue(jsonChildPath("$", "a/b") != jsonChildPath(jsonChildPath("$", "a"), "b"))
    }
    @Test fun expandingAndCollapsingReusesTheDocumentWithoutChangingItsValues() {
        val document = JsonDocument.parse("""{"nested":{"value":"kept"},"array":[true,null]}""")!!
        val expanded = document.initialExpanded()
        val rows = document.rows(expanded)
        assertEquals(listOf("\"kept\"", "true", "null"), rows.filterNot { it.expandable }.map { it.value })
        assertEquals(1, document.rows(emptySet()).size)
        assertEquals(rows, document.rows(expanded))
    }

    @Test fun parsingAndExpansionKeepTheExistingDisplayBudgets() {
        assertEquals(null, JsonDocument.parse("{broken"))
        assertEquals(null, JsonDocument.parse("42"))
        assertEquals(null, JsonDocument.parse(" ".repeat(512 * 1024 + 1)))
        val array = JsonDocument.parse((0..600).joinToString(prefix = "[", postfix = "]"))!!
        assertEquals(101, array.initialExpanded().size)
        assertEquals(501, array.rows(setOf("$")).size)
        val longString = JsonDocument.parse("[\"${"x".repeat(1100)}\"]")!!
        assertEquals("\"${"x".repeat(1024)}…\"", longString.rows(setOf("$"))[1].value)
    }

}
