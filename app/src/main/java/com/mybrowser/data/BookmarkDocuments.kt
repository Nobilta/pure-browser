package com.mybrowser.data

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mybrowser.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class BookmarkExportResult(val count: Int, val omitted: Int, val unsupported: Int)

/** SAF grant and preview are required before a bookmark file changes the library. */
class BookmarkDocuments(private val activity: ComponentActivity, private val scope: CoroutineScope,
    private val repository: () -> BookmarkManager, private val onChanged: () -> Unit,
    private val message: (Boolean, String) -> Unit) {
    var preview by mutableStateOf<BookmarkImport?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var exportResult by mutableStateOf(
        activity.savedStateRegistry.consumeRestoredStateForKey(EXPORT_RESULT_KEY)
            ?.takeIf { it.containsKey("count") }?.let {
            BookmarkExportResult(it.getInt("count"), it.getInt("omitted"), it.getInt("unsupported"))
        },
    )
        private set
    private data class Export(val bytes: ByteArray, val count: Int, val omitted: Int, val unsupported: Int)
    private var exportSnapshot: Export? = null

    init {
        activity.savedStateRegistry.registerSavedStateProvider(EXPORT_RESULT_KEY) {
            Bundle().apply {
                exportResult?.let {
                    putInt("count", it.count)
                    putInt("omitted", it.omitted)
                    putInt("unsupported", it.unsupported)
                }
            }
        }
    }

    private val open = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runJob {
            preview = withContext(Dispatchers.IO) {
                val bytes = requireNotNull(activity.contentResolver.openInputStream(uri)).use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        check(output.size() + read <= BookmarkHtml.MAX_BYTES)
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
                BookmarkHtml.parse(text).also { require(it.entries.isNotEmpty() || it.folders.isNotEmpty()) }
            }
        }
    }

    private val create = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        val snapshot = exportSnapshot
        exportSnapshot = null
        if (uri != null && snapshot != null) runJob {
            withContext(Dispatchers.IO) {
                requireNotNull(activity.contentResolver.openOutputStream(uri, "wt")).use {
                    it.write(snapshot.bytes)
                }
            }
            exportResult = BookmarkExportResult(snapshot.count, snapshot.omitted, snapshot.unsupported)
        }
    }

    fun importFile() {
        if (!busy) runCatching { open.launch(arrayOf("text/html", "application/xhtml+xml", "text/plain")) }
            .onFailure { message(true, activity.getString(R.string.bookmarks_transfer_failed)) }
    }

    fun exportFile() {
        if (busy) return
        runJob {
            exportSnapshot = withContext(Dispatchers.IO) {
                val manager = repository()
                val entries = manager.bookmarksForExport()
                val encoded = BookmarkHtml.encodeCapped(entries, manager.getFolders())
                val exportable = manager.countExportableBookmarks()
                val total = manager.countBookmarks()
                Export(
                    bytes = encoded.bytes,
                    count = encoded.included,
                    omitted = (exportable - encoded.included).coerceAtLeast(0),
                    unsupported = (total - exportable).coerceAtLeast(0),
                )
            }
            create.launch("PureBrowser-bookmarks.html")
        }
    }

    fun dismissPreview() { if (!busy) preview = null }

    fun dismissExportResult() { exportResult = null }

    fun confirmImport() {
        val data = preview ?: return
        if (busy) return
        runJob {
            val inserted = withContext(Dispatchers.IO) { repository().importBookmarks(data.entries, data.folders) }
            preview = null
            onChanged()
            message(true, activity.getString(R.string.bookmarks_imported, inserted, data.entries.size - inserted + data.skipped))
        }
    }

    private fun runJob(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { message(true, activity.getString(R.string.bookmarks_transfer_failed)) }
            finally { busy = false }
        }
    }

    private companion object {
        const val EXPORT_RESULT_KEY = "bookmark_export_result"
    }
}
