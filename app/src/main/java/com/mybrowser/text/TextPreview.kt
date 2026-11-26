package com.mybrowser.text

import android.util.JsonReader
import android.util.JsonToken
import com.mybrowser.ui.devtools.SourceHighlighter
import com.mybrowser.R
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.json.JSONObject
import java.io.IOException
import java.io.StringReader

internal data class TextPreview(val html: String, val errorMessage: Int? = null)

/** Only generated markup enters the inert viewer; original text remains separate for copy/raw. */
internal fun textPreview(body: String, format: TextFormat): TextPreview = when (format) {
    TextFormat.MARKDOWN -> {
        val extensions = listOf(TablesExtension.create())
        val document = Parser.builder().extensions(extensions).build().parse(body)
        TextPreview(HtmlRenderer.builder().extensions(extensions).escapeHtml(true).sanitizeUrls(true)
            .build().render(document))
    }
    TextFormat.JSON -> try {
        jsonPreview(body)?.let { TextPreview("<pre>\n$it</pre>") }
            ?: TextPreview("<pre>\n${escapeText(body)}</pre>", R.string.text_json_too_large)
    }
        catch (_: IOException) { TextPreview("<pre>\n${escapeText(body)}</pre>", R.string.text_invalid_json) }
        // Android's JsonReader throws this for malformed Unicode escapes.
        catch (_: NumberFormatException) { TextPreview("<pre>\n${escapeText(body)}</pre>", R.string.text_invalid_json) }
    TextFormat.CSV, TextFormat.TSV -> delimitedTable(body, if (format == TextFormat.CSV) ',' else '\t')
    TextFormat.YAML, TextFormat.TOML, TextFormat.INI, TextFormat.PROPERTIES ->
        TextPreview("<pre>\n${configurationPreview(body, format)}</pre>")
    TextFormat.XML -> TextPreview(buildString {
        append("<pre>\n")
        val source = SourceHighlighter.parse(body)
        for (block in source.blocks) {
            var position = 0
            for (span in block.spans) {
                append(escapeText(block.text.substring(position, span.start)))
                append("<span class=\"t${span.token.ordinal}\">")
                append(escapeText(block.text.substring(span.start, span.end)))
                append("</span>")
                position = span.end
            }
            append(escapeText(block.text.substring(position)))
        }
        // The existing source lexer has a work budget. Keep the remainder readable in full.
        append(escapeText(body.substring(source.characters)))
        append("</pre>")
    })
    else -> TextPreview("<pre>\n${escapeText(body)}</pre>")
}

internal fun escapeText(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** Streaming tokens keep large numbers, duplicate keys and key order intact. No recursive tree. */
private fun jsonPreview(body: String): String? = JsonReader(StringReader("[$body]")).use { reader ->
    reader.beginArray() // Android's strict reader requires a container; the wrapper allows primitives.
    val output = StringBuilder()
    val containers = ArrayList<JsonToken>()
    val first = ArrayList<Boolean>()
    var rootRead = false
    fun token(text: String, color: Int) {
        output.append("<span class=\"t$color\">").append(escapeText(text)).append("</span>")
    }
    fun indent() {
        output.append('\n').append("  ".repeat(minOf(containers.size, 16)))
    }
    fun separator() {
        if (!first.last()) output.append(',')
        first[first.lastIndex] = false
        indent()
    }
    while (true) {
        // Dense arrays and deeply nested input can expand far beyond the original file size.
        if (output.length > SourceHighlighter.MAX_SOURCE_CHARS) return@use null
        val kind = reader.peek()
        if (containers.isEmpty() && rootRead) break
        if (kind == JsonToken.NAME) {
            separator()
            token(JSONObject.quote(reader.nextName()), 1)
            output.append(": ")
            continue
        }
        if (kind != JsonToken.END_ARRAY && kind != JsonToken.END_OBJECT) {
            if (containers.lastOrNull() == JsonToken.BEGIN_ARRAY) separator()
            rootRead = true
        }
        when (kind) {
            JsonToken.BEGIN_ARRAY, JsonToken.BEGIN_OBJECT -> {
                if (kind == JsonToken.BEGIN_ARRAY) { reader.beginArray(); output.append('[') }
                else { reader.beginObject(); output.append('{') }
                containers.add(kind)
                first.add(true)
            }
            JsonToken.END_ARRAY, JsonToken.END_OBJECT -> {
                if (containers.isEmpty()) throw IOException("Missing JSON value")
                val empty = first.removeAt(first.lastIndex)
                containers.removeAt(containers.lastIndex)
                if (!empty) indent()
                if (kind == JsonToken.END_ARRAY) { reader.endArray(); output.append(']') }
                else { reader.endObject(); output.append('}') }
            }
            JsonToken.STRING -> token(JSONObject.quote(reader.nextString()), 2)
            JsonToken.NUMBER -> token(reader.nextString(), 5)
            JsonToken.BOOLEAN -> token(reader.nextBoolean().toString(), 4)
            JsonToken.NULL -> { reader.nextNull(); token("null", 4) }
            else -> throw IOException("Invalid JSON value")
        }
    }
    if (reader.peek() != JsonToken.END_ARRAY) throw IOException("Extra JSON value")
    reader.endArray()
    if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Trailing JSON content")
    output.toString()
}

internal fun previewHtml(preview: TextPreview, background: Int, foreground: Int, well: Int,
                         border: Int, palette: List<Int>): String {
    fun color(value: Int) = "#" + Integer.toHexString(value and 0xFFFFFF).padStart(6, '0')
    val tokens = palette.mapIndexed { index, value -> ".t$index{color:${color(value)}}" }.joinToString("")
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style>
html,body{margin:0;background:${color(background)};color:${color(foreground)}}
body{padding:16px;font:16px/1.6 sans-serif;overflow-wrap:anywhere}
body>:first-child{margin-top:0}body>:last-child{margin-bottom:0}
h1,h2,h3{line-height:1.3}h1{font-size:1.75em}h2{font-size:1.5em}h3{font-size:1.25em}
a{color:${color(palette[0])}}blockquote{margin:16px 0;padding-left:16px;border-left:3px solid ${color(border)}}
pre,code{font-family:monospace;font-size:14px;line-height:20px;background:${color(well)}}
pre{margin:0 0 16px;padding:12px;border-radius:12px;white-space:pre-wrap;overflow-wrap:anywhere;tab-size:4}
code{padding:2px 4px}pre code{padding:0}ul,ol{padding-left:24px}
table{display:block;overflow-x:auto;border-collapse:collapse;margin:16px 0}
th,td{padding:8px 12px;border:1px solid ${color(border)};text-align:left}th{background:${color(well)}}
.delimited td{white-space:pre;overflow-wrap:normal}.delimited tr:first-child{background:${color(well)}}
hr{border:0;border-top:1px solid ${color(border)}}img{max-width:100%}
$tokens
</style></head><body>${preview.html}</body></html>"""
}
