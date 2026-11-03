package com.mybrowser.ui.devtools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mybrowser.R
import com.mybrowser.devtools.CookieResult
import com.mybrowser.devtools.CookieRow
import com.mybrowser.devtools.DevToolsSession
import com.mybrowser.devtools.PageResult
import com.mybrowser.devtools.StorageEntry
import com.mybrowser.devtools.StorageSnapshot
import com.mybrowser.ui.shell.BrowserIconAction
import com.mybrowser.ui.shell.LibrarySearchField
import com.mybrowser.ui.shell.localizedResources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

private const val COOKIE_TAB = 0
private const val LOCAL_TAB = 1
private const val SESSION_TAB = 2
private const val MAX_COPY_CHARS = 8 * 1024 * 1024

@Composable
internal fun StorageTab(
    session: DevToolsSession?,
    pageUrl: String?,
) {
    val resources = localizedResources()
    var tab by rememberSaveable { mutableIntStateOf(COOKIE_TAB) }
    var query by rememberSaveable { mutableStateOf("") }
    var refresh by rememberSaveable { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cookies by remember { mutableStateOf<CookieResult?>(null) }
    var snapshot by remember { mutableStateOf<StorageSnapshot?>(null) }
    var selectedCookie by remember { mutableStateOf<CookieRow?>(null) }
    var selectedEntry by remember { mutableStateOf<StorageEntry?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(tab, refresh, session?.documentId, pageUrl) {
        if (session == null) {
            error = resources.getString(R.string.dev_storage_error, "WebView")
            return@LaunchedEffect
        }
        loading = true
        error = null
        selectedCookie = null
        selectedEntry = null
        try {
            when (tab) {
                COOKIE_TAB -> {
                    if (pageUrl.isNullOrBlank()) {
                        error = resources.getString(R.string.dev_storage_error, "URL")
                    } else when (val result = session.storage.cookies(pageUrl)) {
                        is PageResult.Ok -> cookies = result.value
                        is PageResult.Failed -> error = result.message ?: resources.getString(R.string.dev_storage_access_denied)
                        PageResult.Invalid, PageResult.Unavailable ->
                            error = resources.getString(R.string.dev_storage_access_denied)
                    }
                }
                else -> {
                    val storageKind = if (tab == LOCAL_TAB) com.mybrowser.devtools.StorageReader.Kind.LOCAL
                        else com.mybrowser.devtools.StorageReader.Kind.SESSION
                    when (val result = session.storage.list(storageKind)) {
                        is PageResult.Ok -> snapshot = result.value
                        is PageResult.Failed -> error = result.message ?: resources.getString(R.string.dev_storage_access_denied)
                        PageResult.Invalid, PageResult.Unavailable ->
                            error = resources.getString(R.string.dev_storage_access_denied)
                    }
                }
            }
        } finally {
            loading = false
        }
    }
    val selected = selectedCookie ?: selectedEntry
    if (selected != null && session != null) {
        if (selectedCookie != null) CookieDetail(selectedCookie!!, onBack = { selectedCookie = null })
        else StorageEntryDetail(
            session = session,
            kind = if (tab == LOCAL_TAB) com.mybrowser.devtools.StorageReader.Kind.LOCAL
                else com.mybrowser.devtools.StorageReader.Kind.SESSION,
            entry = selectedEntry!!,
            origin = snapshot?.origin.orEmpty(),
            onBack = { selectedEntry = null },
        )
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(tab == COOKIE_TAB, { tab = COOKIE_TAB; query = "" }, label = { Text(stringResource(R.string.dev_storage_cookies)) })
            Spacer(Modifier.width(8.dp))
            FilterChip(tab == LOCAL_TAB, { tab = LOCAL_TAB; query = "" }, label = { Text(stringResource(R.string.dev_storage_local)) })
            Spacer(Modifier.width(8.dp))
            FilterChip(tab == SESSION_TAB, { tab = SESSION_TAB; query = "" }, label = { Text(stringResource(R.string.dev_storage_session)) })
            Spacer(Modifier.weight(1f))
            BrowserIconAction(R.drawable.ic_reload, stringResource(R.string.dev_storage_refresh)) { refresh++ }
        }
        if (tab != COOKIE_TAB) {
            snapshot?.origin?.let { Text(
                stringResource(R.string.dev_storage_origin, it),
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ) }
        }
        Text(
            when (tab) {
                COOKIE_TAB -> stringResource(R.string.dev_storage_cookie_scope)
                SESSION_TAB -> stringResource(R.string.dev_storage_session_note)
                else -> stringResource(R.string.dev_storage_refresh_note)
            },
            Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (tab == COOKIE_TAB && cookies?.richAttributes == false) Text(
            stringResource(R.string.dev_storage_cookie_fallback),
            Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.padding(horizontal = 16.dp)) {
            LibrarySearchField(query, stringResource(R.string.dev_storage_search)) { query = it }
        }
        when {
            loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp))
            }
            error != null -> Text(
                error.orEmpty(),
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> when (tab) {
                COOKIE_TAB -> CookieList(cookies, query, listState) { selectedCookie = it }
                else -> StorageList(snapshot, query, listState) { selectedEntry = it }
            }
        }
    }
}

@Composable
private fun CookieList(
    result: CookieResult?,
    query: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpen: (CookieRow) -> Unit,
) {
    val filtered = result?.cookies.orEmpty().filter {
        query.isBlank() || it.name.contains(query, true) || it.value.contains(query, true) ||
            it.domain.orEmpty().contains(query, true)
    }
    if (filtered.isEmpty()) {
        Text(stringResource(R.string.dev_storage_empty),
            Modifier.fillMaxWidth().padding(24.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        itemsIndexed(filtered, key = { index, cookie ->
            "${cookie.name}/${cookie.domain.orEmpty()}/${cookie.path.orEmpty()}/$index"
        }) { _, cookie ->
            Card(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onOpen(cookie) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(cookie.name, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(cookie.domain.orEmpty().ifBlank { stringResource(R.string.dev_storage_unknown) },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(cookie.value.take(300).ifBlank { stringResource(R.string.dev_storage_empty) },
                        Modifier.padding(top = 2.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Row {
                        Text(cookie.path.orEmpty().ifBlank { stringResource(R.string.dev_storage_unknown) },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(cookie.expires ?: stringResource(R.string.dev_storage_unknown),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (cookie.httpOnly != null) Text(" · HttpOnly", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (cookie.secure != null) Text(" · Secure", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageList(
    snapshot: StorageSnapshot?,
    query: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onOpen: (StorageEntry) -> Unit,
) {
    val filtered = snapshot?.entries.orEmpty().filter {
        query.isBlank() || it.name.contains(query, true) || it.value.contains(query, true)
    }
    if (filtered.isEmpty()) {
        Text(stringResource(R.string.dev_storage_empty),
            Modifier.fillMaxWidth().padding(24.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.dev_storage_total, filtered.size) +
                if (snapshot?.truncated == true) " · " + stringResource(R.string.dev_storage_truncated, filtered.size) else "",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.fillMaxSize(), state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            items(filtered, key = { it.name }) { entry ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).clickable { onOpen(entry) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Row {
                            Text(entry.name, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            Text("${entry.valueChars}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(entry.value.take(300).ifBlank { stringResource(R.string.dev_storage_empty) },
                            Modifier.padding(top = 2.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun CookieDetail(cookie: CookieRow, onBack: () -> Unit) {
    val resources = localizedResources()
    val context = LocalContext.current
    val value = cookie.value
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BrowserIconAction(R.drawable.ic_back, stringResource(R.string.cd_back), onClick = onBack)
            Text(cookie.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { copyText(context, value) }) { Text(stringResource(R.string.dev_copy)) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            DetailRow(stringResource(R.string.dev_storage_name), cookie.name)
            DetailRow(stringResource(R.string.dev_storage_value), value.ifBlank { stringResource(R.string.dev_storage_empty) })
            DetailRow(stringResource(R.string.dev_storage_domain), cookie.domain ?: stringResource(R.string.dev_storage_unknown))
            DetailRow(stringResource(R.string.dev_storage_path), cookie.path ?: stringResource(R.string.dev_storage_unknown))
            DetailRow(stringResource(R.string.dev_storage_expires), cookie.expires ?: stringResource(R.string.dev_storage_unknown))
            DetailRow(stringResource(R.string.dev_storage_secure),
                cookie.secure?.let { yesNo(it, resources) } ?: stringResource(R.string.dev_storage_unknown))
            DetailRow(stringResource(R.string.dev_storage_httponly),
                cookie.httpOnly?.let { yesNo(it, resources) } ?: stringResource(R.string.dev_storage_unknown))
            DetailRow(stringResource(R.string.dev_storage_samesite), cookie.sameSite ?: stringResource(R.string.dev_storage_unknown))
        }
    }
}

private fun yesNo(value: String, resources: android.content.res.Resources): String = when {
    value.equals("true", true) -> resources.getString(R.string.dev_storage_yes)
    value.equals("false", true) -> resources.getString(R.string.dev_storage_no)
    value.isNotEmpty() -> resources.getString(R.string.dev_storage_yes)
    else -> resources.getString(R.string.dev_storage_no)
}

@Composable
private fun StorageEntryDetail(
    session: DevToolsSession,
    kind: com.mybrowser.devtools.StorageReader.Kind,
    entry: StorageEntry,
    origin: String,
    onBack: () -> Unit,
) {
    val resources = localizedResources()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf<String?>(null) }
    var total by remember { mutableIntStateOf(entry.valueChars) }
    var offset by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var formatted by rememberSaveable { mutableStateOf(true) }
    var copying by remember { mutableStateOf(false) }
    LaunchedEffect(entry.name) {
        when (val result = session.storage.value(kind, entry.name, 0)) {
            is PageResult.Ok -> {
                value = result.value.value
                total = result.value.totalChars
                offset = result.value.offset + result.value.value.length
            }
            else -> error = true
        }
        loading = false
    }
    val json = remember(value, formatted) {
        if (value == null || !formatted) value
        else formatJson(value.orEmpty())
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BrowserIconAction(R.drawable.ic_back, stringResource(R.string.cd_back), onClick = onBack)
            Text(entry.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { formatted = !formatted }) {
                Text(if (formatted) stringResource(R.string.dev_storage_json_raw)
                    else stringResource(R.string.dev_storage_json_format))
            }
        }
        if (origin.isNotBlank()) Text(stringResource(R.string.dev_storage_origin, origin),
            Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loading) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp))
        } else if (error) Text(stringResource(R.string.dev_storage_access_denied),
            Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        else {
            if (value != null && value!!.length < total) Text(
                stringResource(R.string.dev_storage_value_truncated, value!!.length, total),
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                Modifier.weight(1f).fillMaxWidth().padding(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    json.orEmpty(),
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (offset < total) TextButton(onClick = {
                    scope.launch {
                        when (val result = session.storage.value(kind, entry.name, offset)) {
                            is PageResult.Ok -> {
                                value = (value.orEmpty() + result.value.value)
                                offset = result.value.offset + result.value.value.length
                            }
                            else -> error = true
                        }
                    }
                }) { Text(stringResource(R.string.dev_storage_value_load_more)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch {
                        copying = true
                        val all = readAll(session, kind, entry.name)
                        if (all != null) copyText(context, all) else showToast(context, resources.getString(R.string.dev_copy_failed))
                        copying = false
                    }
                }, enabled = !copying) { Text(stringResource(if (copying) R.string.dev_storage_loading else R.string.dev_storage_copy_all)) }
            }
        }
    }
}

private suspend fun readAll(session: DevToolsSession, kind: com.mybrowser.devtools.StorageReader.Kind, name: String): String? {
    val builder = StringBuilder()
    var offset = 0
    while (builder.length <= MAX_COPY_CHARS) {
        val result = session.storage.value(kind, name, offset) as? PageResult.Ok ?: return null
        val value = result.value
        builder.append(value.value)
        if (value.value.isEmpty()) return builder.toString()
        offset = value.offset + value.value.length
        if (offset >= value.totalChars) return builder.toString()
    }
    return null
}

private fun formatJson(text: String): String {
    if (text.length > 1024 * 1024) return text
    val trimmed = text.trim()
    return runCatching {
        when (val value = JSONTokener(trimmed).nextValue()) {
            is JSONObject -> value.toString(2)
            is JSONArray -> value.toString(2)
            else -> text
        }
    }.getOrDefault(text)
}
