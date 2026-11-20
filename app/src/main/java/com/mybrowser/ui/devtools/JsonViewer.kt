package com.mybrowser.ui.devtools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Length-prefixed path segment: keys containing "/" cannot collide with nested keys. */
internal fun jsonPathSegment(key: String): String = "${key.length}:$key"

internal fun jsonChildPath(parent: String, key: String): String = "$parent/${jsonPathSegment(key)}"

internal data class JsonRow(
    val path: String,
    val depth: Int,
    val label: String,
    val value: String,
    val expandable: Boolean,
    val expanded: Boolean,
    val summary: String = "",
)

/** Parsed once per response body; expansion only traverses this read-only tree. */
internal class JsonDocument private constructor(private val root: Any) {
    fun rows(expanded: Set<String>): List<JsonRow> {
        val rows = ArrayList<JsonRow>()
        fun walk(node: Any?, label: String, depth: Int, path: String) {
            if (rows.size >= 2000) return
            when (node) {
                is JSONObject -> {
                    val keys = node.keys().asSequence().toList()
                    rows += JsonRow(path, depth, label, "", expandable = keys.isNotEmpty(),
                        expanded = path in expanded, summary = "{${keys.size}}")
                    if (path in expanded) keys.take(500).forEach { key ->
                        walk(node.opt(key), key, depth + 1, jsonChildPath(path, key))
                    }
                }
                is JSONArray -> {
                    rows += JsonRow(path, depth, label, "", expandable = node.length() > 0,
                        expanded = path in expanded, summary = "[${node.length()}]")
                    if (path in expanded) for (index in 0 until minOf(node.length(), 500)) {
                        walk(node.opt(index), index.toString(), depth + 1, jsonChildPath(path, index.toString()))
                    }
                }
                else -> {
                    val rendered = when (node) {
                        null, JSONObject.NULL -> "null"
                        is String -> if (node.length > 1024) "\"${node.take(1024)}…\"" else "\"$node\""
                        else -> node.toString()
                    }
                    rows += JsonRow(path, depth, label, rendered, expandable = false, expanded = false)
                }
            }
        }
        walk(root, "", 0, "$")
        return rows
    }

    fun initialExpanded(): Set<String> {
        val children = if (root is JSONObject) root.keys().asSequence()
            else (0 until (root as JSONArray).length()).asSequence().map(Int::toString)
        return buildSet {
            add("$")
            children.take(100).forEach { add(jsonChildPath("$", it)) }
        }
    }

    companion object {
        fun parse(text: String): JsonDocument? {
            if (text.length > 512 * 1024) return null
            if (text.firstOrNull { !it.isWhitespace() } !in listOf('{', '[')) return null
            val value = try {
                val parser = JSONTokener(text)
                parser.nextValue().takeIf { parser.nextClean() == '\u0000' }
            } catch (_: org.json.JSONException) { null }
            catch (_: StackOverflowError) { null } // Platform parser recursion on deeply nested response bodies.
            return if (value is JSONObject || value is JSONArray) JsonDocument(value) else null
        }
    }
}

@Composable
internal fun JsonRowView(row: JsonRow, onToggle: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .padding(start = (row.depth.coerceAtMost(30) * 12).dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.expandable) {
            Text(if (row.expanded) "▾" else "▸", Modifier.width(40.dp).clickable { onToggle(row.path) }.padding(vertical = 10.dp),
                color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            Text(" ", Modifier.width(40.dp))
        }
        SelectableText(
            (if (row.label.isNotEmpty()) "${row.label}: " else "") +
                if (row.summary.isNotEmpty()) row.summary else row.value,
            Modifier.weight(1f), color = colors.onSurface, fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
internal fun JsonToggle(showFormatted: Boolean, onToggle: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onToggle) {
        Text(stringResource(if (showFormatted) R.string.dev_storage_json_raw else R.string.dev_storage_json_format))
    }
}
