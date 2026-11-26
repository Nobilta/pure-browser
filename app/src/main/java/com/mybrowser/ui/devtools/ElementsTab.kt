package com.mybrowser.ui.devtools

import androidx.compose.ui.res.pluralStringResource
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.ui.components.BrowserActionPill
import com.mybrowser.devtools.DevToolsSession
import com.mybrowser.devtools.DomNode
import com.mybrowser.devtools.DomNodeKind
import com.mybrowser.devtools.DomSearchResult
import com.mybrowser.devtools.DomSearchResults
import com.mybrowser.devtools.EnhancedCaptureState
import com.mybrowser.devtools.PageResult
import com.mybrowser.devtools.SourceInfo
import com.mybrowser.devtools.SourcePage
import com.mybrowser.ui.shell.BrowserDropdownMenu
import com.mybrowser.ui.shell.BrowserIconAction
import com.mybrowser.ui.shell.localizedResources
import com.mybrowser.ui.theme.BrowserColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

internal fun domTextPreview(text: String): String = buildString {
    text.forEach { character ->
        append(when (character) {
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            '\u000c' -> "\\f"
            '\\' -> "\\\\"
            else -> character.toString()
        })
    }
    if (text.isNotEmpty() && text.all { it == ' ' }) {
        clear()
        repeat(text.length) { append('·') }
    }
}

internal enum class DomNotice { NONE, UNAVAILABLE, STALE, FAILED, EMPTY }

/** Deep nodes share a capped gutter so their text stays inside the phone viewport. */
private fun domIndent(depth: Int) = (depth.coerceIn(0, 16) * 10).dp

internal data class DomRow(
    val node: DomNode?,
    val depth: Int,
    val expanded: Boolean = false,
    val moreParent: String? = null,
    val loaded: Int = 0,
    val total: Int = 0,
)

/** Paged DOM expansion state. Only expanded branches are requested and flattened. */
internal class DomTreeState(private val session: DevToolsSession) {
    var root by mutableStateOf<DomNode?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var notice by mutableStateOf(DomNotice.NONE)
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set
    var selectedName by mutableStateOf("")
        private set
    var showWhitespace by mutableStateOf(false)
        private set
    val rows = mutableStateListOf<DomRow>()

    fun setWhitespaceVisible(visible: Boolean) {
        showWhitespace = visible
        rebuild()
        if (rows.none { it.node?.id == selectedId }) {
            selectedId = null
            selectedName = ""
        }
    }

    private val children = mutableStateMapOf<String, List<DomNode>>()
    private val totals = mutableStateMapOf<String, Int>()
    private val expanded = mutableStateMapOf<String, Boolean>()
    private var documentId = -1L
    var revision by mutableIntStateOf(0)
        private set

    fun reset() {
        revision++
        root = null
        rows.clear()
        children.clear()
        totals.clear()
        expanded.clear()
        selectedId = null
        selectedName = ""
        notice = DomNotice.NONE
        documentId = session.documentId
    }

    suspend fun load(force: Boolean = false) {
        if (!session.dom.isAvailable()) {
            notice = DomNotice.UNAVAILABLE
            return
        }
        if (documentId != session.documentId) reset()
        if (root != null && !force) return
        loading = true
        notice = DomNotice.NONE
        try {
            when (val result = session.dom.root()) {
                is PageResult.Ok -> {
                    val node = result.value
                    loadChildren(node.id, 0)
                    root = node
                    expanded[node.id] = true
                    rebuild()
                    if (node.childCount == 0) notice = DomNotice.EMPTY
                }
                PageResult.Invalid -> notice = DomNotice.STALE
                PageResult.Unavailable -> notice = DomNotice.UNAVAILABLE
                is PageResult.Failed -> notice = DomNotice.FAILED
            }
        } finally {
            loading = false
        }
    }

    suspend fun refresh() {
        reset()
        session.dom.reset()
        load(force = true)
    }

    suspend fun toggle(node: DomNode) {
        if (node.childCount == 0) return
        if (expanded[node.id] == true) {
            expanded[node.id] = false
        } else {
            expanded[node.id] = true
            if (children[node.id].isNullOrEmpty()) loadChildren(node.id, 0)
        }
        rebuild()
    }

    suspend fun loadMore(parentId: String) {
        loadChildren(parentId, children[parentId]?.size ?: 0)
        rebuild()
    }

    fun select(node: DomNode) {
        selectedId = node.id
        selectedName = node.displayName
    }

    /** Attribute/text edits update one cached row without collapsing unrelated branches. */
    suspend fun refreshEditedNode(id: String) {
        if (root?.id == id) {
            when (val result = session.dom.root()) {
                is PageResult.Ok -> {
                    root = result.value
                    rebuild()
                }
                PageResult.Invalid -> notice = DomNotice.STALE
                PageResult.Unavailable -> notice = DomNotice.UNAVAILABLE
                is PageResult.Failed -> notice = DomNotice.FAILED
            }
            return
        }
        val parent = children.entries.firstOrNull { (_, nodes) -> nodes.any { it.id == id } } ?: return
        val index = parent.value.indexOfFirst { it.id == id }
        when (val result = session.dom.children(parent.key, index)) {
            is PageResult.Ok -> {
                val updated = result.value.nodes.firstOrNull { it.id == id }
                if (updated == null) notice = DomNotice.STALE
                else {
                    children[parent.key] = children[parent.key].orEmpty().map { if (it.id == id) updated else it }
                    rebuild()
                    if (selectedId == id && !showWhitespace && updated.formattingWhitespace) {
                        selectedId = null
                        selectedName = ""
                    }
                }
            }
            PageResult.Invalid -> notice = DomNotice.STALE
            PageResult.Unavailable -> notice = DomNotice.UNAVAILABLE
            is PageResult.Failed -> notice = DomNotice.FAILED
        }
    }

    suspend fun copyHtml(id: String): Pair<String, Boolean>? {
        return when (val result = session.dom.html(id)) {
            is PageResult.Ok -> result.value
            PageResult.Invalid -> {
                notice = DomNotice.STALE
                null
            }
            PageResult.Unavailable -> {
                notice = DomNotice.UNAVAILABLE
                null
            }
            is PageResult.Failed -> {
                notice = DomNotice.FAILED
                null
            }
        }
    }

    /** Expands ancestors and returns the row index to scroll to, or null when it is gone. */
    suspend fun reveal(result: DomSearchResult): Int? {
        val path = result.path
        if (path.isEmpty()) return null
        for (index in 0 until path.size - 1) {
            val parent = path[index]
            val childIndex = path[index + 1].index.coerceAtLeast(0)
            expanded[parent.id] = true
            if (!ensureChildLoaded(parent.id, childIndex)) return null
        }
        // Searching an explicitly requested whitespace node must still reveal it.
        if (children.values.any { nodes -> nodes.any { it.id == result.id && it.formattingWhitespace } }) {
            showWhitespace = true
        }
        selectedId = result.id
        selectedName = result.name
        rebuild()
        return rows.indexOfFirst { it.node?.id == result.id }.takeIf { it >= 0 }
    }

    private suspend fun ensureChildLoaded(parentId: String, childIndex: Int): Boolean {
        var total = totals[parentId]
        var guard = 0
        while (guard++ < 100) {
            val loaded = children[parentId] ?: emptyList()
            if (loaded.size > childIndex) return true
            if (total != null && loaded.size >= total) return false
            val page = when (val result = session.dom.children(parentId, loaded.size)) {
                is PageResult.Ok -> result.value
                PageResult.Invalid -> {
                    notice = DomNotice.STALE
                    return false
                }
                PageResult.Unavailable -> {
                    notice = DomNotice.UNAVAILABLE
                    return false
                }
                is PageResult.Failed -> {
                    notice = DomNotice.FAILED
                    return false
                }
            }
            if (page.nodes.isEmpty()) return false
            children[parentId] = loaded + page.nodes
            totals[parentId] = page.total
            total = page.total
        }
        return false
    }

    private suspend fun loadChildren(parentId: String, start: Int) {
        val page = when (val result = session.dom.children(parentId, start)) {
            is PageResult.Ok -> result.value
            PageResult.Invalid -> {
                notice = DomNotice.STALE
                return
            }
            PageResult.Unavailable -> {
                notice = DomNotice.UNAVAILABLE
                return
            }
            is PageResult.Failed -> {
                notice = DomNotice.FAILED
                return
            }
        }
        val existing = if (start == 0) emptyList() else children[parentId] ?: emptyList()
        val merged = (existing + page.nodes).distinctBy { it.id }
        children[parentId] = merged
        totals[parentId] = page.total
    }

    private fun rebuild() {
        rows.clear()
        val rootNode = root ?: return
        val ancestors = HashSet<String>()
        fun add(node: DomNode, depth: Int) {
            if (!showWhitespace && node.formattingWhitespace) return
            if (depth > MAX_DOM_DEPTH || rows.size >= MAX_DOM_ROWS) return
            // A malformed page-side mapping must not recurse forever or repeat an ancestor.
            if (node.id.isBlank() || !ancestors.add(node.id)) return
            val isExpanded = expanded[node.id] == true && node.childCount > 0
            val loaded = children[node.id] ?: emptyList()
            val total = totals[node.id] ?: node.childCount
            rows += DomRow(node, depth, isExpanded, loaded = loaded.size, total = total)
            if (isExpanded) {
                loaded.forEach { child -> add(child, depth + 1) }
                if (loaded.size < total) {
                    rows += DomRow(null, depth + 1, moreParent = node.id, loaded = loaded.size, total = total)
                }
            }
            ancestors.remove(node.id)
        }
        add(rootNode, 0)
    }

    private companion object {
        const val MAX_DOM_DEPTH = 512
        const val MAX_DOM_ROWS = 100_000
    }
}

/** One page of the current DOM serialization, with a back stack of page offsets. */
internal class SourceViewerState(private val session: DevToolsSession) {
    var info by mutableStateOf<SourceInfo?>(null)
        private set
    var page by mutableStateOf<SourcePage?>(null)
        private set
    var document by mutableStateOf<SourceDocument?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf(false)
        private set
    var exporting by mutableStateOf(false)
        private set
    private val history = mutableStateListOf<Int>()
    private var offset = 0
    private var documentId = -1L

    fun reset() {
        info = null
        page = null
        document = null
        history.clear()
        offset = 0
        error = false
        documentId = session.documentId
    }

    suspend fun loadIfNeeded() {
        if (documentId != session.documentId) reset()
        if (page == null) load()
    }

    suspend fun load(first: Boolean = true, targetOffset: Int = offset): Boolean {
        if (documentId != session.documentId) reset()
        if (!session.dom.isAvailable()) {
            error = true
            return false
        }
        if (first) {
            when (val metadata = session.source.info()) {
                is PageResult.Ok -> info = metadata.value
                PageResult.Invalid, PageResult.Unavailable -> {
                    error = true
                    return false
                }
                is PageResult.Failed -> {
                    error = true
                    return false
                }
            }
        }
        loading = true
        return try {
            when (val result = session.source.page(if (first) 0 else targetOffset)) {
                is PageResult.Ok -> {
                    val loaded = result.value
                    val parsed = withContext(Dispatchers.Default) {
                        SourceHighlighter.parseChunk(loaded.text, startLine = loaded.startLine + 1)
                    }
                    if (first) history.clear()
                    offset = loaded.offset
                    page = loaded
                    document = parsed
                    info = info ?: SourceInfo(loaded.totalChars, loaded.totalLines, "", "")
                    error = false
                    true
                }
                PageResult.Invalid, PageResult.Unavailable -> { error = true; false }
                is PageResult.Failed -> { error = true; false }
            }
        } finally {
            loading = false
        }
    }

    suspend fun next() {
        val current = page ?: return
        val parsed = document
        val nextOffset = if (parsed != null && parsed.characters < current.text.length) {
            current.offset + parsed.characters
        } else current.nextOffset
        if (nextOffset == null || nextOffset <= current.offset) return
        if (load(first = false, targetOffset = nextOffset)) history += current.offset
    }

    suspend fun previous() {
        if (history.isEmpty()) return
        if (load(first = false, targetOffset = history.last())) history.removeAt(history.lastIndex)
    }

    fun canGoBack(): Boolean = history.isNotEmpty()

    suspend fun refresh() {
        // Source refresh must not clear the page-side node registry: an expanded DOM tree
        // on screen would otherwise keep ids that no longer exist.
        reset()
        session.source.resetCache()
        load(first = true)
    }

    suspend fun export(context: Context, uri: Uri): Boolean {
        exporting = true
        return try {
            val metadata = info
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                    val exportedAt = DateFormat.getDateTimeInstance().format(Date())
                    writer.write("<!-- Pure Browser: current DOM serialization (document.documentElement.outerHTML), not the original server response.\n")
                    writer.write("URL: ${metadata?.url.orEmpty()}\nExported: $exportedAt\n-->\n")
                    if (!session.source.forEachPage { p -> writer.write(p.text) }) return@withContext false
                    true
                } ?: false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            exporting = false
        }
    }
}

@Composable
internal fun ElementsTab(
    session: DevToolsSession?,
    capture: EnhancedCaptureState,
    domState: DomTreeState?,
    sourceState: SourceViewerState?,
    onInspectNode: () -> Unit,
    onEnableCapture: () -> Unit,
    header: @Composable () -> Unit,
) {
    val resources = localizedResources()
    val scope = rememberCoroutineScope()
    var view by rememberSaveable { mutableIntStateOf(0) }
    var detailsId by rememberSaveable(capture.documentId) { mutableStateOf<String?>(null) }
    val viewStates = rememberSaveableStateHolder()
    var options by remember { mutableStateOf(false) }
    val available = capture.enabled || capture.stopped
    if (session == null || domState == null || sourceState == null || !available) {
        DevToolsPage(controls = header) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                EmptyState(resources.getString(R.string.dev_elements_guide))
                BrowserActionPill(resources.getString(R.string.dev_enhanced_enable), onEnableCapture,
                    enabled = session != null && capture.supported && !capture.installing,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        return
    }
    if (!capture.ready && capture.enabled) {
        DevToolsPage(controls = header) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            EmptyState(resources.getString(if (capture.notice == com.mybrowser.devtools.CaptureNotice.SCRIPT_MISSING)
                R.string.dev_enhanced_script_missing else R.string.dev_enhanced_document_pending))
            }
        }
        return
    }
    LaunchedEffect(capture.documentId, view) {
        if (view == 0) domState.load() else sourceState.loadIfNeeded()
    }
    val toolbar: @Composable () -> Unit = {
        header()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrowserActionPill(resources.getString(R.string.dev_elements_dom), { view = 0 }, selected = view == 0)
                BrowserActionPill(resources.getString(R.string.dev_elements_source), { view = 1 }, selected = view == 1)
            }
            if (view == 0) BrowserIconAction(R.drawable.ic_ui_search, resources.getString(R.string.dev_dom_inspect), onClick = onInspectNode)
            BrowserIconAction(R.drawable.ic_ui_refresh, resources.getString(R.string.dev_dom_refresh),
                enabled = !domState.loading && !sourceState.loading) {
                scope.launch {
                    if (view == 0) { domState.refresh(); sourceState.reset() } else sourceState.refresh()
                }
            }
            if (view == 0) Box {
                BrowserIconAction(R.drawable.ic_ui_more, resources.getString(R.string.dev_more)) { options = true }
                BrowserDropdownMenu(options, { options = false }) {
                    DropdownMenuItem(text = { Text(resources.getString(R.string.dev_dom_show_whitespace)) },
                        trailingIcon = { Checkbox(domState.showWhitespace, onCheckedChange = null) },
                        onClick = { domState.setWhitespaceVisible(!domState.showWhitespace); options = false })
                }
            }
        }
    }
    viewStates.SaveableStateProvider(view) {
        if (view == 0) DomTreeView(domState, session, scope, detailsId,
            onDetailsChange = { detailsId = it }, onChanged = { sourceState.reset() }, header = toolbar)
        else DevToolsPage(controls = toolbar) { SourceView(sourceState, scope) }
    }
}

@Composable
private fun DomTreeView(state: DomTreeState, session: DevToolsSession, scope: kotlinx.coroutines.CoroutineScope,
                        detailsId: String?, onDetailsChange: (String?) -> Unit, onChanged: () -> Unit, header: @Composable () -> Unit) {
    val resources = localizedResources()
    val context = LocalContext.current
    val feedback = LocalDevToolsFeedback.current
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<DomSearchResults?>(null) }
    var searching by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var editedNode by remember { mutableStateOf<String?>(null) }
    var editRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(editRevision) { editedNode?.let { state.refreshEditedNode(it) } }
    LaunchedEffect(session.inspectedNode) {
        session.inspectedNode?.let { picked ->
            onDetailsChange(null)
            state.reset()
            state.load()
            val index = state.reveal(picked)
            if (index != null) listState.scrollToItem(index)
            session.inspectedNode = null
            if (index != null) onDetailsChange(picked.id)
        }
    }
    detailsId?.let { id ->
        androidx.activity.compose.BackHandler { onDetailsChange(null) }
        DomNodeDetails(session, id, state.selectedName, onBack = { onDetailsChange(null) },
            onCopy = {
                scope.launch {
                    state.copyHtml(id)?.let { (html, truncated) ->
                        copyText(context, html, feedback, if (truncated) resources.getString(R.string.dev_dom_copied_truncated) else null)
                    }
                }
            }, onChanged = {
            onChanged()
            editedNode = id
            editRevision++
        })
        return
    }
    LaunchedEffect(query, state.revision) {
        results = null
        searching = false
        if (query.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        searching = true
        try {
            results = (session.dom.search(query) as? PageResult.Ok)?.value
        } finally { searching = false }
    }
    DevToolsPage(controls = {
        header()
        Box(Modifier.padding(horizontal = 16.dp)) {
            DevToolsSearchField(query, stringResource(R.string.dev_dom_search)) { query = it }
        }
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
    }) {
        results?.let { found ->
            if (found.results.isEmpty()) Text(
                stringResource(R.string.dev_dom_search_empty),
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ) else LazyColumn(Modifier.weight(0.35f, fill = false).fillMaxWidth().heightIn(max = 160.dp)) {
                items(found.results, key = { "search-${it.id}" }) { result ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) {
                            scope.launch {
                                val index = state.reveal(result)
                                if (index != null) listState.animateScrollToItem(index)
                            }
                        }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("<${result.name}>", fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Text(result.preview, Modifier.padding(start = 8.dp).weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        when (state.notice) {
            DomNotice.STALE -> Text(stringResource(R.string.dev_dom_stale),
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            DomNotice.UNAVAILABLE -> Text(stringResource(R.string.dev_elements_guide),
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            DomNotice.FAILED -> Text(stringResource(R.string.dev_dom_stale),
                Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
            DomNotice.EMPTY -> Text(stringResource(R.string.dev_dom_empty), Modifier.padding(16.dp))
            DomNotice.NONE -> Unit
        }
        if (state.loading) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
        }
        val palette = rememberSourcePalette()
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            items(state.rows, key = { row -> row.node?.id ?: "more-${row.moreParent}" }) { row ->
                if (row.moreParent != null) {
                    TextButton(
                        onClick = { scope.launch { state.loadMore(row.moreParent) } },
                        modifier = Modifier.padding(start = domIndent(row.depth) + 12.dp),
                    ) { Text(stringResource(R.string.dev_dom_more_children) + " (${row.loaded}/${row.total})") }
                } else {
                    DomNodeRow(row, state, palette) {
                        state.select(it)
                        onDetailsChange(it.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun DomNodeRow(row: DomRow, state: DomTreeState, palette: List<androidx.compose.ui.graphics.Color>, onSelect: (DomNode) -> Unit) {
    val node = row.node ?: return
    val scope = rememberCoroutineScope()
    val selected = state.selectedId == node.id
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(role = androidx.compose.ui.semantics.Role.Button) { onSelect(node) }
            .padding(start = domIndent(row.depth), top = 2.dp, bottom = 2.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (node.childCount > 0) {
            IconButton(onClick = { scope.launch { state.toggle(node) } }, modifier = Modifier.size(48.dp)) {
                Icon(
                    painterResource(R.drawable.ic_ui_chevron),
                    stringResource(if (row.expanded) R.string.dev_dom_collapse else R.string.dev_dom_expand, node.displayName),
                    Modifier.size(20.dp).rotate(if (row.expanded) 90f else 0f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else Spacer(Modifier.width(48.dp))
        when (node.kind) {
            DomNodeKind.ELEMENT -> {
                val primary = MaterialTheme.colorScheme.primary
                val tertiary = MaterialTheme.colorScheme.tertiary
                val stringColor = BrowserColors.secure
                val annotated = buildAnnotatedString {
                    withStyle(SpanStyle(color = primary)) { append("<${node.name}") }
                    node.attributes.forEach { attribute ->
                        append(" ")
                        withStyle(SpanStyle(color = tertiary)) { append(attribute.name) }
                        append("=")
                        withStyle(SpanStyle(color = stringColor)) {
                            append("\"${attribute.value.take(120)}\"")
                        }
                    }
                    append(">")
                }
                Column {
                    Text(annotated, fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall)
                    if (node.name == "iframe" || node.name == "frame") Text(
                        stringResource(R.string.dev_dom_frame_boundary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DomNodeKind.TEXT -> Text(
                "\"${domTextPreview(node.text)}\"${if (node.textTruncated) "…" else ""}",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = BrowserColors.secure,
            )
            DomNodeKind.COMMENT -> Text(
                "<!-- ${node.text}${if (node.textTruncated) "…" else ""} -->",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DomNodeKind.DOCTYPE -> Text("<!DOCTYPE ${node.name}>",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            DomNodeKind.OTHER -> Text(node.displayName, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SourceView(
    state: SourceViewerState,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val resources = localizedResources()
    val context = LocalContext.current
    val feedback = LocalDevToolsFeedback.current
    val scroll = rememberLazyListState()
    var previousOffset by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.page?.offset) {
        val offset = state.page?.offset
        if (previousOffset != null && offset != null && previousOffset != offset) scroll.scrollToItem(0)
        previousOffset = offset
    }
    val exportName = stringResource(R.string.dev_source_export_name)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri != null) scope.launch {
            val ok = state.export(context, uri)
            feedback(resources.getString(if (ok) R.string.dev_source_exported else R.string.dev_source_export_failed))
        }
    }
    Column(Modifier.fillMaxSize()) {
        val page = state.page
        val document = state.document
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                page?.let {
                    stringResource(
                        R.string.dev_source_range,
                        it.startLine + 1,
                        maxOf(it.startLine + 1, it.endLine),
                        it.totalLines,
                    )
                } ?: stringResource(R.string.dev_source_title),
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BrowserIconAction(R.drawable.ic_ui_back, stringResource(R.string.dev_source_prev),
                state.canGoBack() && !state.loading) { scope.launch { state.previous() } }
            BrowserIconAction(R.drawable.ic_ui_forward, stringResource(R.string.dev_source_next),
                page != null && !state.loading &&
                    (page.nextOffset != null || (document != null && document.characters < page.text.length))) {
                scope.launch { state.next() }
            }
            var options by remember { mutableStateOf(false) }
            Box {
                BrowserIconAction(R.drawable.ic_ui_more, stringResource(R.string.dev_more)) { options = true }
                BrowserDropdownMenu(options, { options = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.dev_source_copy_page)) },
                        enabled = page != null, onClick = { options = false; page?.let { copyText(context, it.text, feedback) } })
                    DropdownMenuItem(text = { Text(stringResource(if (state.exporting) R.string.dev_source_exporting else R.string.dev_source_export)) },
                        enabled = !state.exporting, onClick = {
                            options = false
                            if (state.info != null) exporter.launch(exportName)
                            else scope.launch { state.load(); if (state.info != null) exporter.launch(exportName) }
                        })
                    if (page?.continued == true) Text(stringResource(R.string.dev_source_continued),
                        Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val palette = rememberSourcePalette()
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
            state.error -> EmptyState(stringResource(R.string.dev_source_read_failed))
            page == null || document == null -> EmptyState(stringResource(R.string.dev_source_loading))
            else -> LazyColumn(Modifier.fillMaxSize(), state = scroll) {
                items(document.blocks.size, key = { index -> "source-$index" }) { index ->
                    SourceBlockRow(document.blocks[index], palette)
                }
            }
        }
    }
}
