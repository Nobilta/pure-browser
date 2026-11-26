package com.mybrowser.devtools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads the current DOM lazily through the page-side accessor.
 *
 * Node ids belong to the script instance of one document. Navigation invalidates the whole
 * reader; a stale id is reported as invalid rather than guessed at. Children are read one
 * page at a time so the native side never receives a complete DOM as one JSON document.
 */
internal class DomReader(private val session: DevToolsSession) {
    fun isAvailable(): Boolean = session.canInspectDocument() && session.namespace() != null

    suspend fun reset(): Boolean {
        val namespace = session.namespace() ?: return false
        val expected = session.documentId
        val raw = session.evaluateInDocument(DevToolsJs.call(namespace, "a.reset();return true;"))
        return raw != null && session.isCurrentDocument(expected)
    }

    suspend fun root(): PageResult<DomNode> = read({ ns -> DevToolsJs.callFunction(ns, "domRoot") }) { json ->
        val root = json.optJSONObject("root") ?: return@read null
        node(root)
    }

    suspend fun children(parentId: String, start: Int): PageResult<DomChildrenPage> =
        read({ ns -> DevToolsJs.callFunction(ns, "domChildren", JSONObject.quote(parentId),
            start.toString(), DevToolsBudgets.DOM_CHILDREN_PER_PAGE.toString()) }) { json ->
            val array = json.optJSONArray("nodes") ?: return@read null
            val nodes = ArrayList<DomNode>(array.length())
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { nodes += node(it) }
            }
            DomChildrenPage(nodes, json.optInt("start", start), json.optInt("total", nodes.size))
        }

    suspend fun html(nodeId: String): PageResult<Pair<String, Boolean>> =
        read({ ns -> DevToolsJs.callFunction(ns, "domHtml", JSONObject.quote(nodeId)) }) { json ->
            val html = json.optString("html")
            html to json.optBoolean("truncated")
        }

    suspend fun path(nodeId: String): PageResult<List<DomPathEntry>> =
        read({ ns -> DevToolsJs.callFunction(ns, "domPath", JSONObject.quote(nodeId)) }) { json ->
            val array = json.optJSONArray("path") ?: return@read null
            entries(array)
        }

    suspend fun search(query: String): PageResult<DomSearchResults> =
        read({ ns -> DevToolsJs.callFunction(ns, "domSearch", JSONObject.quote(query.take(200)),
            "50") }) { json ->
            val array = json.optJSONArray("results") ?: return@read null
            val results = ArrayList<DomSearchResult>(array.length())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                results += DomSearchResult(
                    id = id,
                    name = item.optString("name").take(256),
                    preview = item.optString("preview").take(DevToolsBudgets.DOM_TEXT_PREVIEW_CHARS),
                    match = item.optString("match").take(32),
                    path = entries(item.optJSONArray("path")),
                )
            }
            DomSearchResults(results, json.optBoolean("truncated"))
        }

    suspend fun picked(): PageResult<DomSearchResult> = read({ ns ->
        DevToolsJs.callFunction(ns, "domPicked")
    }) { json -> DomSearchResult(json.getString("id"), json.getString("name"), "", "", entries(json.optJSONArray("path"))) }

    suspend fun details(nodeId: String): PageResult<DomDetails> {
        val expected = session.documentId
        return read({ ns -> DevToolsJs.callFunction(ns, "domDetails", JSONObject.quote(nodeId)) }) { json ->
            fun pairs(key: String): List<DomAttribute> {
                val array = json.optJSONArray(key) ?: return emptyList()
                val names = HashSet<String>()
                return List(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    val name = item.getString("name")
                    if (!names.add(name)) throw JSONException("Duplicate DOM detail name")
                    DomAttribute(name, item.getString("value"))
                }
            }
            DomDetails(nodeId, expected, json.getString("doc"), json.getString("kind"),
                pairs("attributes"), pairs("styles"), pairs("computed"), json.optString("box"),
                if (json.has("text")) json.getString("text") else null, json.getBoolean("supportsInlineStyle"))
        }
    }

    suspend fun edit(details: DomDetails, kind: String, name: String, value: String?): PageResult<Unit> {
        val namespace = session.namespace() ?: return PageResult.Unavailable
        val script = DevToolsJs.call(namespace,
            "if(a.doc!==${JSONObject.quote(details.pageToken)})return {invalid:true};" +
                "return a.domEdit(${JSONObject.quote(details.nodeId)},${JSONObject.quote(kind)}," +
                "${JSONObject.quote(name)},${value?.let(JSONObject::quote) ?: "null"});")
        val raw = session.evaluateInDocument(script, details.documentId) ?: return PageResult.Unavailable
        val decoded = DevToolsJs.decode(raw) ?: return PageResult.Unavailable
        val json = runCatching { JSONObject(decoded) }.getOrNull() ?: return PageResult.Failed(null)
        return when {
            json.optBoolean("invalid") -> PageResult.Invalid
            json.optBoolean("ok") -> PageResult.Ok(Unit)
            else -> PageResult.Failed(json.optString("error"))
        }
    }

    private suspend fun <T> read(
        build: (String) -> String,
        parse: (JSONObject) -> T?,
    ): PageResult<T> {
        val namespace = session.namespace() ?: return PageResult.Unavailable
        val expected = session.documentId
        val raw = session.evaluateInDocument(build(namespace)) ?: return PageResult.Unavailable
        if (!session.isCurrentDocument(expected)) return PageResult.Unavailable
        if (raw == DevToolsJs.UNAVAILABLE) return PageResult.Unavailable
        val decoded = DevToolsJs.decode(raw) ?: return PageResult.Failed(null)
        if (decoded == DevToolsJs.UNAVAILABLE) return PageResult.Unavailable
        return withContext(Dispatchers.Default) {
            val json = runCatching { JSONObject(decoded) }.getOrNull()
                ?: return@withContext PageResult.Failed(null)
            if (json.optBoolean("invalid")) return@withContext PageResult.Invalid
            json.optString("error").takeIf { it.isNotBlank() }?.let {
                return@withContext PageResult.Failed(it.take(256))
            }
            if (json.has("empty") && json.optBoolean("empty")) return@withContext PageResult.Invalid
            val value = try { parse(json) } catch (_: JSONException) { null }
                ?: return@withContext PageResult.Failed(null)
            PageResult.Ok(value)
        }
    }

    private fun node(json: JSONObject): DomNode {
        val kind = when (json.optString("kind")) {
            "element" -> DomNodeKind.ELEMENT
            "text" -> DomNodeKind.TEXT
            "comment" -> DomNodeKind.COMMENT
            "doctype" -> DomNodeKind.DOCTYPE
            else -> DomNodeKind.OTHER
        }
        val attributes = ArrayList<DomAttribute>()
        json.optJSONArray("attributes")?.let { array ->
            for (index in 0 until minOf(array.length(), DevToolsBudgets.HEADER_COUNT)) {
                val item = array.optJSONObject(index) ?: continue
                attributes += DomAttribute(
                    item.optString("name").take(256),
                    item.optString("value").take(DevToolsBudgets.HEADER_VALUE_CHARS),
                )
            }
        }
        return DomNode(
            id = json.optString("id"),
            kind = kind,
            name = json.optString("name").take(256),
            attributes = attributes,
            text = json.optString("text").take(DevToolsBudgets.DOM_TEXT_PREVIEW_CHARS),
            textTruncated = json.optBoolean("textTruncated"),
            textLength = json.optInt("textLength", 0),
            childCount = json.optInt("childCount", 0),
            formattingWhitespace = kind == DomNodeKind.TEXT && json.optBoolean("formattingWhitespace"),
        )
    }

    private fun entries(array: JSONArray?): List<DomPathEntry> {
        if (array == null) return emptyList()
        val result = ArrayList<DomPathEntry>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id")
            if (id.isNotBlank()) result += DomPathEntry(id, item.optInt("index", 0))
        }
        return result
    }
}

internal data class DomDetails(
    val nodeId: String, val documentId: Long, val pageToken: String, val kind: String,
    val attributes: List<DomAttribute>, val styles: List<DomAttribute>, val computed: List<DomAttribute>,
    val box: String, val text: String?, val supportsInlineStyle: Boolean,
)
