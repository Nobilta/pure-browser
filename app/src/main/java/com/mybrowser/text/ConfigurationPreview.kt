package com.mybrowser.text

import com.mybrowser.ui.devtools.SourceHighlighter
import com.mybrowser.ui.devtools.SourceToken

/** Lexical coloring only: preserves spelling, whitespace and malformed/incomplete input. */
internal fun configurationPreview(body: String, format: TextFormat): String = buildString {
    var limit = minOf(body.length, SourceHighlighter.MAX_SOURCE_CHARS)
    if (limit < body.length && limit > 0 && body[limit - 1].isHighSurrogate() && body[limit].isLowSurrogate()) limit--
    var position = 0
    var lineStart = true
    fun token(end: Int, kind: SourceToken) {
        append("<span class=\"t${kind.ordinal}\">")
        append(escapeText(body.substring(position, end)))
        append("</span>")
        position = end
    }
    while (position < limit) {
        val char = body[position]
        if (char == '\n' || char == '\r') {
            append(char)
            position++
            lineStart = true
            continue
        }
        if (char == ' ' || char == '\t') { append(char); position++; continue }
        val start = position
        val quoted = (char == '"' || char == '\'') && format != TextFormat.PROPERTIES && run {
            var before = start - 1
            while (before >= 0 && body[before] in " \t") before--
            val delimiters = when (format) {
                TextFormat.YAML -> ":[{,-"
                TextFormat.TOML -> "=[{,"
                else -> "="
            }
            lineStart || before >= 0 && body[before] in delimiters
        }
        val comment = char == '#' && (format != TextFormat.YAML || start == 0 || body[start - 1].isWhitespace()) ||
            lineStart && (format == TextFormat.INI && char == ';' || format == TextFormat.PROPERTIES && char == '!')
        val sequence = lineStart && format == TextFormat.YAML && char == '-' &&
            (start + 1 == limit || body[start + 1].isWhitespace())
        if (comment) {
            val end = body.indexOfFirstFrom(start, limit) { it == '\n' }
            token(end, SourceToken.COMMENT)
        } else if (quoted) {
            val triple = format == TextFormat.TOML && body.startsWith(char.toString().repeat(3), start)
            val width = if (triple) 3 else 1
            val ending = char.toString().repeat(width)
            var end = minOf(start + width, limit)
            while (end < limit) {
                if (!triple && format != TextFormat.YAML && body[end] in "\r\n") break
                if (char == '"' && body[end] == '\\') { end = minOf(end + 2, limit); continue }
                if (body.startsWith(ending, end)) {
                    end = minOf(end + width, limit)
                    if (!triple && char == '\'' && format == TextFormat.YAML && end < limit && body[end] == '\'') { end++; continue }
                    break
                }
                end++
            }
            val after = body.indexOfFirstFrom(end, limit) { it != ' ' && it != '\t' }
            token(end, if (lineStart && after < limit && body[after] in "=:") SourceToken.ATTRIBUTE else SourceToken.STRING)
        } else if (lineStart && char == '[' && format in listOf(TextFormat.TOML, TextFormat.INI)) {
            token(body.indexOfFirstFrom(start, limit) { it == '\n' }, SourceToken.TAG)
        } else if (sequence) {
            token(start + 1, SourceToken.PUNCTUATION)
        } else {
            // A key belongs to the start of a line. Values such as https:// keep their spelling.
            var keyEnd = start
            if (lineStart && char !in "[]{}#=:\"'") {
                while (keyEnd < limit && body[keyEnd] !in "\r\n=:#") keyEnd++
            }
            if (keyEnd > start && keyEnd < limit && body[keyEnd] in "=:") {
                token(keyEnd, SourceToken.ATTRIBUTE)
            } else if (char in "{}[]=:,-") {
                token(start + 1, SourceToken.PUNCTUATION)
            } else {
                var end = start + 1
                while (end < limit && !body[end].isWhitespace() && body[end] !in "{}[]=:,#") end++
                val value = body.substring(start, end)
                if (configurationValue.matches(value)) {
                    token(end, if (value.first().isDigit() || value.first() in "+-") SourceToken.NUMBER else SourceToken.KEYWORD)
                } else {
                    append(escapeText(value))
                    position = end
                }
            }
        }
        // A YAML sequence may contain a mapping on the same line.
        lineStart = sequence
    }
    append(escapeText(body.substring(position)))
}

private val configurationValue = Regex("(?:[+-]?(?:0[xob][0-9a-fA-F_]+|[0-9][0-9_.]*(?:[eE][+-]?[0-9]+)?)|true|false|null|yes|no|on|off|~)", RegexOption.IGNORE_CASE)

private inline fun String.indexOfFirstFrom(start: Int, limit: Int, predicate: (Char) -> Boolean): Int {
    var index = start
    while (index < limit && !predicate(this[index])) index++
    return index
}
