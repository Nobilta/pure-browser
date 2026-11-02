package com.mybrowser.text

/** Plain text stays inert, including markup and leading newlines. Built off the UI thread. */
internal fun textHtml(body: String, background: Int, foreground: Int): String = buildString {
    val bg = "#" + Integer.toHexString(background and 0xFFFFFF).padStart(6, '0')
    val fg = "#" + Integer.toHexString(foreground and 0xFFFFFF).padStart(6, '0')
    append("""
<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style>
html,body{margin:0;padding:0;background:$bg;color:$fg;}
pre{margin:0;padding:16px;font-family:monospace;font-size:14px;line-height:20px;white-space:pre-wrap;overflow-wrap:anywhere;tab-size:4;}
</style></head><body><pre>
""")
    for (character in body) {
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            else -> append(character)
        }
    }
    append("</pre></body></html>")
}
