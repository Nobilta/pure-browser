package com.mybrowser.text

import android.app.Application
import com.mybrowser.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class)
class TextPreviewTest {
    @Test(timeout = 5000) fun yamlSequenceMarkersDoNotRescanTheRemainderOfTheLine() {
        val input = "- ".repeat(100_000)
        val html = configurationPreview(input, TextFormat.YAML)
        assertEquals(escapeText(input), html.replace(Regex("<span class=\"t[0-9]+\">|</span>"), ""))
        val mapping = configurationPreview("- enabled: true\n-key: value", TextFormat.YAML)
        assertTrue(mapping.contains("<span class=\"t1\">enabled</span>"))
        assertTrue(mapping.contains("<span class=\"t1\">-key</span>"))
    }

    @Test fun configurationTokensStopAtTheHighlightBudgetAndKeepTheOriginalTail() {
        val limit = com.mybrowser.ui.devtools.SourceHighlighter.MAX_SOURCE_CHARS
        for ((prefix, format) in listOf("#" to TextFormat.YAML, "\"" to TextFormat.YAML,
            "[" to TextFormat.TOML, "key=" to TextFormat.INI, "key" to TextFormat.PROPERTIES)) {
            val input = prefix + "a".repeat(limit) + "TAIL"
            val html = configurationPreview(input, format)
            assertEquals(escapeText(input), html.replace(Regex("<span class=\"t[0-9]+\">|</span>"), ""))
            assertTrue(html.endsWith(escapeText(input.substring(limit))))
            assertFalse(html.contains("TAIL</span>"))
        }
    }

    @Test fun configurationBudgetDoesNotSplitSupplementaryCharactersAcrossMarkup() {
        val limit = com.mybrowser.ui.devtools.SourceHighlighter.MAX_SOURCE_CHARS
        for (prefix in listOf("#", "\"", "key: ")) {
            val input = prefix + "a".repeat(limit - prefix.length - 1) + "😀TAIL"
            val html = configurationPreview(input, TextFormat.YAML)
            assertTrue(html.endsWith("😀TAIL"))
            assertEquals(escapeText(input), html.replace(Regex("<span class=\"t[0-9]+\">|</span>"), ""))
        }
    }

    @Test fun delimitedTablesPreserveQuotedDelimitersNewlinesEmptyCellsAndMarkup() {
        val csv = "name,note,empty\r\n\"中文,😀\",\"line one\r\n\"\"quoted\"\"\",\r\n<script>,=1+1,last"
        val html = textPreview(csv, TextFormat.CSV).html
        assertEquals(3, Regex("<tr>").findAll(html).count())
        assertEquals(9, Regex("<td>").findAll(html).count())
        assertTrue(html.contains("<td>中文,😀</td>"))
        assertTrue(html.contains("<td>line one\r\n\"quoted\"</td><td></td>"))
        assertTrue(html.contains("<td>&lt;script&gt;</td><td>=1+1</td>"))
        val tsv = textPreview("a\tb\n\"x\ty\"\t\"two\nlines\"\n", TextFormat.TSV)
        assertNull(tsv.errorMessage)
        assertTrue(tsv.html.contains("<td>x\ty</td><td>two\nlines</td>"))
        assertEquals(2, Regex("<tr>").findAll(tsv.html).count())
        assertEquals("<table class=\"delimited\"><tbody><tr><td>a</td><td></td></tr></tbody></table>", delimitedTable("a,", ',').html)
    }

    @Test fun denseTableFallsBackToCompleteOriginal() {
        val original = ",".repeat(MAX_TABLE_CELLS) + "last"
        val preview = textPreview(original, TextFormat.CSV)
        assertEquals(R.string.text_table_too_large, preview.errorMessage)
        assertEquals("<pre>\n$original</pre>", preview.html)
        assertNull(textPreview(",".repeat(MAX_TABLE_CELLS - 1), TextFormat.CSV).errorMessage)
    }

    @Test fun malformedTableShowsOriginalWithoutLosingData() {
        for (body in listOf("\"unfinished", "a,\"b\"trailing", "a,b\"c")) {
            val preview = textPreview(body, TextFormat.CSV)
            assertEquals(R.string.text_invalid_table, preview.errorMessage)
            assertEquals("<pre>\n${escapeText(body)}</pre>", preview.html)
        }
    }

    @Test fun configurationHighlightingPreservesOriginalAndKeepsCommentsInsideStrings() {
        val values = mapOf(
            TextFormat.YAML to "name: \"中文 # literal\"\n- enabled: true\nquote: 'it''s valid'\nport: 8080 # note\nhtml: <script>&😀",
            TextFormat.TOML to "[server]\nport = 8080\ntext = \"\"\"multi\n# literal\"\"\"\nactive = true # note",
            TextFormat.INI to "[server]\n; note\nport=8080\nname=中文",
            TextFormat.PROPERTIES to "! note\nport=8080\nname:中文\nurl=https://example.com"
        )
        for ((format, original) in values) {
            val html = configurationPreview(original, format)
            assertEquals(format.name, escapeText(original), html.replace(Regex("<span class=\"t[0-9]+\">|</span>"), ""))
            assertTrue(html.contains("class=\"t1\""))
            assertTrue(html.contains("class=\"t3\""))
            assertTrue(html.contains("class=\"t5\""))
            assertFalse(html.contains("<script>"))
        }
        assertTrue(configurationPreview("key: \"# literal\" # comment", TextFormat.YAML)
            .contains("<span class=\"t2\">\"# literal\"</span>"))
        val long = "value: " + "x".repeat(1_100_000) + "\nlast: 😀"
        assertEquals(escapeText(long), configurationPreview(long, TextFormat.YAML)
            .replace(Regex("<span class=\"t[0-9]+\">|</span>"), ""))
    }
    @Test(timeout = 5000) fun longConfigurationLineWithoutSeparatorPreservesText() {
        val original = "key" + " ".repeat(200_000) + "tail"
        assertEquals(original, configurationPreview(original, TextFormat.YAML))
    }

    @Test fun plainApostrophesDoNotConsumeFollowingConfigurationLines() {
        val values = mapOf(
            TextFormat.YAML to "description: It's fine\nmessage: He said 'hello\nport: 8080\nenabled: true\n",
            TextFormat.PROPERTIES to "name=O'Reilly\nquote='literal\nport=8080\nenabled=true\n",
            TextFormat.INI to "name=O'Reilly\nquote='unfinished\nport=8080\nenabled=true\n",
            TextFormat.TOML to "name='unfinished\nport=8080\nenabled=true\n"
        )
        for ((format, original) in values) {
            val html = configurationPreview(original, format)
            assertEquals(format.name, escapeText(original), html.replace(Regex("<span class=\"t[0-9]+\">|</span>"), ""))
            assertTrue(format.name, html.contains("<span class=\"t1\">port</span>"))
            assertTrue(format.name, html.contains("<span class=\"t5\">8080</span>"))
            assertTrue(format.name, html.contains("<span class=\"t4\">true</span>"))
        }
    }

    @Test fun actualQuotedConfigurationStringsKeepTheirBoundaries() {
        for ((format, original) in listOf(
            TextFormat.YAML to "name: 'it''s\nstill quoted'\nport: 8080",
            TextFormat.TOML to "name = '''it's\nstill quoted'''\nport = 8080",
            TextFormat.INI to "name = \"it's quoted\"\nport = 8080"
        )) {
            val html = configurationPreview(original, format)
            assertEquals(escapeText(original), html.replace(Regex("<span class=\"t[0-9]+\">|</span>"), ""))
            assertTrue(format.name, html.contains("<span class=\"t2\">"))
            assertTrue(format.name, html.contains("<span class=\"t5\">8080</span>"))
        }
    }

    @Test fun markdownRendersStructureButKeepsEmbeddedHtmlInert() {
        val preview = textPreview("""
            # 中文 😀

            - **重点**

            | 名称 | 值 |
            | --- | --- |
            | a | 1 |

            ```html
            <script>alert(1)</script>
            ```

            <script>alert(2)</script>

            [safe](https://example.com) [unsafe](javascript:alert)
        """.trimIndent(), TextFormat.MARKDOWN).html
        assertTrue(preview.contains("<h1>中文 😀</h1>"))
        assertTrue(preview.contains("<strong>重点</strong>"))
        assertTrue(preview.contains("<table>"))
        assertTrue(preview.contains("&lt;script&gt;"))
        assertFalse(preview.contains("<script>"))
        assertTrue(preview.contains("href=\"https://example.com\""))
        assertFalse(preview.contains("javascript:"))
    }

    @Test fun jsonKeepsPrecisionOrderAndDuplicateKeys() {
        val original = """{"z":123456789012345678901234567890,"a":1.234567890123456789e+200,"z":[true,null,"中文😀</pre>"]}"""
        val preview = textPreview(original, TextFormat.JSON)
        assertFalse((preview.errorMessage == R.string.text_invalid_json))
        assertTrue(preview.html.contains("123456789012345678901234567890"))
        assertTrue(preview.html.contains("1.234567890123456789e+200"))
        assertEquals(2, Regex("&quot;z&quot;|\"z\"").findAll(preview.html).count())
        assertTrue(preview.html.contains("\n  <span"))
        assertTrue(preview.html.contains(escapeText(org.json.JSONObject.quote("中文😀</pre>"))))
        assertTrue(preview.html.contains("class=\"t5\""))
    }

    @Test fun jsonAcceptsPrimitivesAndRejectsMalformedOrTrailingContent() {
        for (value in listOf("1", "true", "null", "\"text\"", "[]", "{}", "[1,[2,3]]")) {
            assertFalse(value, (textPreview(value, TextFormat.JSON).errorMessage == R.string.text_invalid_json))
        }
        for (value in listOf("", "{bad}", "{\"a\":1,}", "[1,]", "01", "NaN", "true false", "1,2", "1]garbage")) {
            val preview = textPreview(value, TextFormat.JSON)
            assertTrue(value, (preview.errorMessage == R.string.text_invalid_json))
            assertEquals("<pre>\n${escapeText(value)}</pre>", preview.html)
        }
    }

    @Test fun expandedJsonFallsBackToCompleteOriginal() {
        for (original in listOf("[\"<script>\"," + "0,".repeat(100_000) + "1]",
            "[".repeat(100_000) + "1" + "]".repeat(100_000))) {
            val preview = textPreview(original, TextFormat.JSON)
            assertEquals(R.string.text_json_too_large, preview.errorMessage)
            assertEquals("<pre>\n${escapeText(original)}</pre>", preview.html)
        }
    }

    @Test fun deeplyNestedJsonDoesNotUseRecursiveFormatting() {
        val value = "[".repeat(2000) + "1" + "]".repeat(2000)
        assertFalse((textPreview(value, TextFormat.JSON).errorMessage == R.string.text_invalid_json))
    }

    @Test fun malformedUnicodeEscapesInValuesAndKeysShowTheCompleteOriginal() {
        for (value in listOf("""{"name":"\uZZZZ"}""", """{"\u12XZ":"value"}""")) {
            val preview = textPreview(value, TextFormat.JSON)
            assertTrue((preview.errorMessage == R.string.text_invalid_json))
            assertEquals("<pre>\n${escapeText(value)}</pre>", preview.html)
        }
    }

    @Test fun typeTakesPriorityAndGenericTextUsesOnlySupportedExtensions() {
        assertEquals(TextFormat.JSON, TextFormat.detect("Application/JSON; charset=utf-8", "file.txt"))
        assertEquals(TextFormat.MARKDOWN, TextFormat.detect("text/plain", "Readme.MD"))
        assertEquals(TextFormat.XML, TextFormat.detect(null, "file.xml"))
        assertEquals(TextFormat.PLAIN, TextFormat.detect("text/plain", "file.html"))
        assertEquals(TextFormat.PLAIN, TextFormat.detect("text/x-python", "file.json"))
        assertEquals(TextFormat.YAML, TextFormat.detect("text/plain", "file.yaml"))
        assertEquals(TextFormat.CSV, TextFormat.detect("text/plain", "file.CSV"))
        assertEquals(TextFormat.TSV, TextFormat.detect("text/tab-separated-values", "file.txt"))
        assertEquals(TextFormat.TOML, TextFormat.detect("application/toml", "file.txt"))
        assertEquals(TextFormat.INI, TextFormat.detect(null, "file.ini"))
        assertEquals(TextFormat.PROPERTIES, TextFormat.detect(null, "file.properties"))
    }
}
