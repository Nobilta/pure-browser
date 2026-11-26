package com.mybrowser.text

import com.mybrowser.R

internal const val MAX_TABLE_CELLS = 50_000

/** CSV quoting also applies to tab-separated exports. Rows need not have equal widths. */
internal fun delimitedTable(body: String, delimiter: Char): TextPreview {
    fun original(message: Int) = TextPreview("<pre>\n${escapeText(body)}</pre>", message)
    if (body.isEmpty()) return TextPreview("<table class=\"delimited\"></table>")
    return TextPreview(buildString {
        append("<table class=\"delimited\"><tbody>")
        var position = 0
        var cells = 0
        while (position < body.length) {
            append("<tr>")
            while (true) {
                // A small delimiter-heavy file can otherwise allocate millions of DOM nodes.
                if (++cells > MAX_TABLE_CELLS) return original(R.string.text_table_too_large)
                val cell = StringBuilder()
                if (position < body.length && body[position] == '"') {
                    position++
                    var closed = false
                    while (position < body.length) {
                        val char = body[position++]
                        if (char != '"') cell.append(char)
                        else if (position < body.length && body[position] == '"') {
                            cell.append('"')
                            position++
                        } else {
                            closed = true
                            break
                        }
                    }
                    if (!closed) return original(R.string.text_invalid_table)
                } else {
                    while (position < body.length && body[position] != delimiter && body[position] != '\r' && body[position] != '\n') {
                        if (body[position] == '"') return original(R.string.text_invalid_table)
                        cell.append(body[position++])
                    }
                }
                append("<td>").append(escapeText(cell.toString())).append("</td>")
                if (position == body.length) break
                when (body[position++]) {
                    delimiter -> continue
                    '\r' -> if (position < body.length && body[position] == '\n') position++
                    '\n' -> Unit
                    else -> return original(R.string.text_invalid_table)
                }
                break
            }
            append("</tr>")
        }
        append("</tbody></table>")
    })
}
