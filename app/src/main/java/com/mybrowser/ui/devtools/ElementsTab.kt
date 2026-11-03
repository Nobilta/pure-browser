package com.mybrowser.ui.devtools

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.devtools.DevToolsBudgets
import com.mybrowser.devtools.DevToolsSession
import com.mybrowser.devtools.DomChildrenPage
import com.mybrowser.devtools.DomNode
import com.mybrowser.devtools.DomNodeKind
import com.mybrowser.devtools.DomSearchResult
import com.mybrowser.devtools.DomSearchResults
import com.mybrowser.devtools.EnhancedCaptureState
import com.mybrowser.devtools.PageResult
import com.mybrowser.devtools.SourceInfo
import com.mybrowser.devtools.SourcePage
import com.mybrowser.ui.shell.BrowserIconAction
import com.mybrowser.ui.shell.LibrarySearchField
import com.mybrowser.ui.shell.localizedResources
import com.mybrowser.ui.theme.BrowserColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

internal enum class DomNotice { NONE, UNAVAILABLE, STALE, FAILED, EMPTY }

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
    val rows = mutableStateListOf<DomRow>()

    private val children = mutableStateMapOf<String, List<DomNode>>()
    private val totals = mutableStateMapOf<String, Int>()
    private val expanded = mutableStateMapOf<String, Boolean>()
    private var documentId = -1L

    fun reset() {
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
                    root = node
                    expanded[node.id] = true
                    loadChildren(node.id, 0)
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
        session.dom.reset()
        reset()
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

    suspend fun copyHtml(): String? {
        val id = selectedId ?: return null
        return when (val result = session.dom.html(id)) {
            is PageResult.Ok -> result.value.first
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
        fun add(node: DomNode, depth: Int) {
            val isExpanded = expanded[node.id] == true && node.childCount > 0
            val loaded = children[node.id] ?: emptyList()
            val total = totals[node.id] ?: node.childCount
            rows += DomRow(node, depth, isExpanded, loaded = loaded.size, total = total)
            if (!isExpanded) return
            loaded.forEach { child -> add(child, depth + 1) }
            if (loaded.size < total) {
                rows += DomRow(null, depth + 1, moreParent = node.id, loaded = loaded.size, total = total)
            }
        }
        add(rootNode, 0)
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

    suspend fun load(first: Boolean = true) {
        if (documentId != session.documentId) reset()
        if (!session.dom.isAvailable()) {
            error = true
            return
        }
        if (first) {
            history.clear()
            offset = 0
            when (val metadata = session.source.info()) {
                is PageResult.Ok -> info = metadata.value
                PageResult.Invalid, PageResult.Unavailable -> {
                    error = true
                    return
                }
                is PageResult.Failed -> {
                    error = true
                    return
                }
            }
        }
        loading = true
        try {
            when (val result = session.source.page(offset)) {
                is PageResult.Ok -> {
                    val loaded = result.value
                    val parsed = withContext(Dispatchers.Default) {
                        SourceHighlighter.parseChunk(loaded.text, startLine = loaded.startLine + 1)
                    }
                    page = loaded
                    document = parsed
                    info = info ?: SourceInfo(loaded.totalChars, loaded.totalLines, "", "")
                    error = false
                }
                PageResult.Invalid, PageResult.Unavailable -> error = true
                is PageResult.Failed -> error = true
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
        history += current.offset
        offset = nextOffset
        load(first = false)
    }

    suspend fun previous() {
        if (history.isEmpty()) return
        offset = history.removeAt(history.lastIndex)
        load(first = false)
    }

    fun canGoBack(): Boolean = history.isNotEmpty()

    suspend fun refresh() {
        // Source refresh must not clear the page-side node registry: an expanded DOM tree
        // on screen would otherwise keep ids that no longer exist.
        session.source.resetCache()
        session.source.invalidate()
        reset()
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
    onEnableCapture: () -> Unit,
    domState: DomTreeState?,
    sourceState: SourceViewerState?,
) {
    val resources = localizedResources()
    val scope = rememberCoroutineScope()
    var view by rememberSaveable { mutableIntStateOf(0) }
    val available = capture.enabled || capture.stopped
    if (session == null || domState == null || sourceState == null) {
        EmptyState(resources.getString(R.string.dev_elements_guide))
        return
    }
    if (!available) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(resources.getString(R.string.dev_elements_guide_title),
                style = MaterialTheme.typography.titleMedium)
            Text(
                resources.getString(R.string.dev_elements_guide),
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onEnableCapture,
                modifier = Modifier.padding(top = 16.dp),
                enabled = capture.supported,
            ) { Text(resources.getString(R.string.dev_enhanced_enable)) }
            if (!capture.supported) Text(
                resources.getString(R.string.dev_enhanced_limited),
                Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        return
    }
    if (!capture.ready && capture.enabled) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                resources.getString(
                    if (capture.notice == com.mybrowser.devtools.CaptureNotice.SCRIPT_MISSING)
                        R.string.dev_enhanced_script_missing else R.string.dev_enhanced_document_pending
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LaunchedEffect(capture.documentId) {
        domState.reset()
        sourceState.reset()
        if (view == 0) domState.load() else sourceState.load()
    }
    LaunchedEffect(view) {
        if (view == 0 && domState.rows.isEmpty()) domState.load()
        if (view == 1 && sourceState.page == null) sourceState.load()
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FilterChip(view == 0, { view = 0 }, label = { Text(resources.getString(R.string.dev_elements_dom)) })
            Spacer(Modifier.width(8.dp))
            FilterChip(view == 1, { view = 1 }, label = { Text(resources.getString(R.string.dev_elements_source)) })
            Spacer(Modifier.weight(1f))
            if (view == 0) BrowserIconAction(R.drawable.ic_reload, resources.getString(R.string.dev_dom_refresh)) {
                scope.launch {
                    domState.refresh()
                    sourceState.reset()
                }
            } else BrowserIconAction(R.drawable.ic_reload, resources.getString(R.string.dev_dom_refresh)) {
                scope.launch { sourceState.refresh() }
            }
        }
        if (view == 0) {
            DomTreeView(domState, session, scope)
        } else {
            SourceView(sourceState, scope)
        }
    }
}

@Composable
private fun DomTreeView(state: DomTreeState, session: DevToolsSession, scope: kotlinx.coroutines.CoroutineScope) {
    val resources = localizedResources()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<DomSearchResults?>(null) }
    var searching by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(250)
        searching = true
        results = when (val found = session.dom.search(query)) {
            is PageResult.Ok -> found.value
            else -> null
        }
        searching = false
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 16.dp)) {
            LibrarySearchField(query, stringResource(R.string.dev_dom_search)) { query = it }
        }
        Text(
            stringResource(R.string.dev_dom_refresh_note) + " " + stringResource(R.string.dev_dom_boundary_note),
            Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
        results?.let { found ->
            if (found.results.isEmpty()) Text(
                stringResource(R.string.dev_dom_search_empty),
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ) else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
                items(found.results, key = { "search-${it.id}" }) { result ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
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
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            state.root?.let {
                Text(stringResource(R.string.dev_dom_child_count, it.childCount),
                    Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } ?: Spacer(Modifier.weight(1f))
            if (state.selectedId != null) {
                Text(state.selectedName, style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.tertiary)
                TextButton(onClick = {
                    scope.launch {
                        state.copyHtml()?.let { html -> copyText(context, html) }
                    }
                }) { Text(stringResource(R.string.dev_dom_copy_html)) }
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
                        modifier = Modifier.padding(start = ((row.depth * 10) + 12).dp),
                    ) { Text(stringResource(R.string.dev_dom_more_children) + " (${row.loaded}/${row.total})") }
                } else {
                    DomNodeRow(row, state, palette) {
                        state.select(it)
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
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { onSelect(node) }
            .padding(start = (row.depth * 10).dp, top = 2.dp, bottom = 2.dp, end = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (node.childCount > 0) {
            Text(
                if (row.expanded) "▾" else "▸",
                Modifier.width(20.dp).clickable { scope.launch { state.toggle(node) } },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else Spacer(Modifier.width(20.dp))
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
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DomNodeKind.TEXT -> Text(
                "\"${node.text}\"${if (node.textTruncated) "…" else ""}",
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
    val exportName = stringResource(R.string.dev_source_export_name)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri != null) scope.launch {
            val ok = state.export(context, uri)
            showToast(context, resources.getString(if (ok) R.string.dev_source_exported else R.string.dev_source_export_failed))
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.dev_source_note),
            Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            TextButton(onClick = { scope.launch { state.previous() } }, enabled = state.canGoBack() && !state.loading) {
                Text(stringResource(R.string.dev_source_prev))
            }
            TextButton(
                onClick = { scope.launch { state.next() } },
                enabled = page != null && !state.loading &&
                    (page.nextOffset != null || (document != null && document.characters < page.text.length)),
            ) {
                Text(stringResource(R.string.dev_source_next))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page != null) TextButton(onClick = { copyText(context, page.text) }) {
                Text(stringResource(R.string.dev_source_copy_page))
            }
            TextButton(onClick = {
                if (state.info != null) exporter.launch(exportName)
                else scope.launch { state.load(first = true); if (state.info != null) exporter.launch(exportName) }
            }, enabled = !state.exporting) {
                Text(stringResource(if (state.exporting) R.string.dev_source_exporting else R.string.dev_source_export))
            }
            if (page?.continued == true) Text(
                stringResource(R.string.dev_source_continued),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val palette = rememberSourcePalette()
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
            state.error -> EmptyState(stringResource(R.string.dev_source_read_failed))
            page == null || document == null -> EmptyState(stringResource(R.string.dev_source_loading))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(document.blocks.size, key = { index -> "source-$index" }) { index ->
                    SourceBlockRow(document.blocks[index], palette)
                }
            }
        }
    }
}
