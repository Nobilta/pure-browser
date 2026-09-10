package com.mybrowser.backup

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mybrowser.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** SAF owns the external grant; only the validated preview can start a restore. */
class BackupDocuments(private val activity: ComponentActivity, private val scope: CoroutineScope,
    private val beforeRestart: () -> Unit, private val message: (String) -> Unit) {
    var visible by mutableStateOf(false)
    var preview by mutableStateOf<JSONObject?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    private var exportBytes: ByteArray? = null

    private val open = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runJob {
            preview = withContext(Dispatchers.IO) {
                requireNotNull(activity.contentResolver.openInputStream(uri)).use {
                    BackupFormat.decode(BackupStorage.readBounded(it))
                }
            }
            visible = true
        }
    }
    private val create = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val bytes = exportBytes
        exportBytes = null
        if (uri != null) {
            if (bytes == null) message(activity.getString(R.string.backup_export_retry))
            else runJob {
                withContext(Dispatchers.IO) {
                    requireNotNull(activity.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
                }
                message(activity.getString(R.string.backup_exported))
            }
        }
    }

    fun importFile() {
        if (busy) return
        runCatching { open.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }
            .onFailure { message(activity.getString(R.string.backup_failed)) }
    }
    fun exportFile(sections: Set<BackupSection>) = runJob {
        exportBytes = withContext(Dispatchers.IO) { BackupFormat.encode(BackupStorage(activity).snapshot(sections)) }
        create.launch("PureBrowser-backup.json")
    }
    fun dismiss() { if (!busy) { visible = false; preview = null } }
    fun clearPreview() { if (!busy) preview = null }

    fun restore(choice: RestoreChoice) {
        val document = preview ?: return
        runJob {
            // Check the complete merge and its size limits before closing any pages.
            withContext(Dispatchers.IO) {
                val storage = BackupStorage(activity)
                BackupMerge.plan(storage.snapshot(choice.sections), document, choice)
                storage.queue(document, choice)
            }
            beforeRestart()
            activity.startActivity(Intent(activity, RestoreActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }

    private fun runJob(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { message(activity.getString(R.string.backup_failed)) }
            finally { busy = false }
        }
    }
}
