package com.mybrowser.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.webkit.WebViewFeature
import com.mybrowser.R
import com.mybrowser.core.TextDownloader
import com.mybrowser.userscript.InstalledUserScript
import com.mybrowser.userscript.UserScriptMetadata
import com.mybrowser.userscript.UserScriptStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ScriptDraft(val source: String, val url: String?, val metadata: UserScriptMetadata, val previewOnly: Boolean = false)

@Composable
fun UserScriptsSheet(store: UserScriptStore, initialUrl: String?, onUrlConsumed: () -> Unit, onDismiss: () -> Unit) {
    val res = localizedResources()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scripts by store.scripts.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var inputMode by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<ScriptDraft?>(null) }
    var deleting by remember { mutableStateOf<InstalledUserScript?>(null) }
    val fullRuntime = remember {
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
    }
    suspend fun attempt(action: suspend () -> Unit) {
        busy = true
        error = null
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = res.getString(R.string.script_operation_failed) }
        finally { busy = false }
    }
    suspend fun loadUrl(url: String) {
        attempt {
            val source = TextDownloader().get(url.trim(), UserScriptMetadata.MAX_SOURCE_BYTES).text ?: error("Empty script")
            val metadata = withContext(Dispatchers.Default) { UserScriptMetadata.parse(source) }
            draft = ScriptDraft(source, url.trim(), metadata)
            inputMode = null
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            attempt {
                val source = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        TextDownloader.readText(it, UserScriptMetadata.MAX_SOURCE_BYTES)
                    } ?: error("Unable to open script")
                }
                draft = ScriptDraft(source, null, withContext(Dispatchers.Default) { UserScriptMetadata.parse(source) })
            }
        }
    }
    LaunchedEffect(initialUrl) {
        store.initialize()
        if (store.loadFailed) error = res.getString(R.string.script_saved_read_failed)
        if (initialUrl != null) {
            loadUrl(initialUrl)
            onUrlConsumed()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ApplySheetSystemBars()
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(painterResource(R.drawable.ic_back), res.getString(R.string.cd_back)) }
                Text(res.getString(R.string.script_title), style = MaterialTheme.typography.titleLarge)
            }
            Text(res.getString(R.string.script_summary), Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall)
            if (!fullRuntime) Text(res.getString(R.string.script_old_webview), Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = { inputMode = "url" }, enabled = !busy) { Text(res.getString(R.string.script_import_url)) }
                TextButton(onClick = { inputMode = "code" }, enabled = !busy) { Text(res.getString(R.string.script_paste)) }
                TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy) { Text(res.getString(R.string.script_import_file)) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
                if (scripts.isEmpty()) item { Text(res.getString(R.string.script_empty), Modifier.padding(8.dp)) }
                items(scripts, key = { it.metadata.id }) { script ->
                    val metadata = script.metadata
                    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(metadata.name, style = MaterialTheme.typography.titleMedium)
                                    if (metadata.version.isNotBlank()) Text(metadata.version, style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(script.enabled, modifier = Modifier.semantics { contentDescription = metadata.name },
                                    enabled = !busy && (script.enabled ||
                                    (metadata.supported && (fullRuntime || !metadata.needsStorage))), onCheckedChange = { value ->
                                    scope.launch { attempt { store.setEnabled(metadata.id, value) } }
                                })
                            }
                            if (metadata.description.isNotBlank()) Text(metadata.description, maxLines = 3, style = MaterialTheme.typography.bodySmall)
                            Text((metadata.matches + metadata.includes).joinToString("\n"), maxLines = 3, style = MaterialTheme.typography.bodySmall)
                            if (!metadata.supported) Text(res.getString(R.string.script_unsupported, metadata.unsupported.joinToString(", ")),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { deleting = script }, enabled = !busy) { Text(res.getString(R.string.cd_delete)) }
                                TextButton(onClick = { draft = ScriptDraft(script.source, script.sourceUrl, metadata, previewOnly = true) }) {
                                    Text(res.getString(R.string.script_details))
                                }
                                if (script.sourceUrl != null) TextButton(onClick = { scope.launch { loadUrl(script.sourceUrl) } }, enabled = !busy) {
                                    Text(res.getString(R.string.script_update))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (inputMode != null) {
        // Source drafts are deliberately not put into a Bundle (scripts can be 1 MiB).
        var input by remember(inputMode) { mutableStateOf("") }
        val urlInput = inputMode == "url"
        AlertDialog(onDismissRequest = { if (!busy) inputMode = null },
            title = { Text(res.getString(if (urlInput) R.string.script_import_url else R.string.script_paste)) },
            text = {
                Column {
                    OutlinedTextField(input, { if (it.length <= UserScriptMetadata.MAX_SOURCE_BYTES) input = it },
                        label = { Text(if (urlInput) "URL" else "// ==UserScript==") },
                        singleLine = urlInput, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(enabled = input.isNotBlank() && !busy, onClick = {
                scope.launch {
                    if (urlInput) loadUrl(input)
                    else attempt {
                        draft = ScriptDraft(input, null, withContext(Dispatchers.Default) { UserScriptMetadata.parse(input) })
                        inputMode = null
                    }
                }
            }) { Text(res.getString(R.string.script_review)) } },
            dismissButton = { TextButton(onClick = { inputMode = null }, enabled = !busy) { Text(res.getString(R.string.action_cancel)) } })
    }
    draft?.let { current ->
        val metadata = current.metadata
        val replacing = scripts.any { it.metadata.id == metadata.id }
        AlertDialog(onDismissRequest = { if (!busy) draft = null },
            title = { Text(metadata.name) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(res.getString(R.string.script_trust_notice))
                    Text(res.getString(R.string.script_match_sites, (metadata.matches + metadata.includes).joinToString("\n")), Modifier.padding(top = 12.dp))
                    if (metadata.excludes.isNotEmpty() || metadata.excludeMatches.isNotEmpty()) Text(
                        res.getString(R.string.script_excluded_sites, (metadata.excludes + metadata.excludeMatches).joinToString("\n")))
                    Text(res.getString(R.string.script_grants, metadata.grants.joinToString(", ")), Modifier.padding(top = 8.dp))
                    Text(res.getString(R.string.script_timing, metadata.runAt))
                    if (metadata.requires.isNotEmpty()) Text(res.getString(R.string.script_requires, metadata.requires.joinToString("\n")))
                    if (!metadata.supported) Text(res.getString(R.string.script_unsupported, metadata.unsupported.joinToString(", ")),
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                    if (replacing && !current.previewOnly) Text(res.getString(R.string.script_replace_notice), Modifier.padding(top = 8.dp))
                    Text(res.getString(R.string.script_source_preview), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                    Text(current.source.take(6000), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    if (current.previewOnly) draft = null
                    else scope.launch { attempt { store.install(current.source, current.url); draft = null } }
                }) { Text(res.getString(if (current.previewOnly) R.string.script_done else if (replacing) R.string.script_replace else R.string.script_install)) }
            },
            dismissButton = { if (!current.previewOnly) TextButton(onClick = { draft = null }, enabled = !busy) { Text(res.getString(R.string.action_cancel)) } })
    }
    deleting?.let { script ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(res.getString(R.string.cd_delete)) },
            text = { Text(res.getString(R.string.script_delete_notice, script.metadata.name)) },
            confirmButton = { TextButton(onClick = {
                scope.launch { attempt { store.remove(script.metadata.id) } }; deleting = null
            }) { Text(res.getString(R.string.cd_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(res.getString(R.string.action_cancel)) } })
    }
}
