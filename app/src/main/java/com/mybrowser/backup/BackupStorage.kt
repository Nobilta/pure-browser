package com.mybrowser.backup

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import com.mybrowser.core.TextDownloader
import com.mybrowser.core.writeUtf8
import com.mybrowser.data.BookmarkManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** Restore runs with the browsing process stopped; a journal makes interruption reversible. */
class BackupStorage(private val context: Context, private val afterWrite: (String) -> Unit = {}) {
    private val journal = AtomicFile(File(context.noBackupFilesDir, "restore-journal.json"))
    private val pending = AtomicFile(File(context.noBackupFilesDir, "restore-pending.json"))
    private val result = AtomicFile(File(context.noBackupFilesDir, "restore-result.txt"))

    fun hasPending() = pending.baseFile.exists() || File(pending.baseFile.path + ".bak").exists()
    fun discardPending() = pending.delete()
    fun writeResult(value: String) = result.writeUtf8(value)
    fun takeResult(): String? = runCatching {
        result.openRead().use { TextDownloader.readText(it, 64) }.also { result.delete() }
    }.getOrNull()

    fun snapshot(selected: Set<BackupSection> = BackupSection.entries.toSet(), validate: Boolean = true): JSONObject {
        val sections = JSONObject()
        selected.forEach { section ->
            val value = if (section == BackupSection.BOOKMARKS) {
                val manager = BookmarkManager(context)
                try { manager.backupSnapshot() } finally { manager.close() }
            } else JSONObject()
            val prefs = JSONObject()
            BackupFormat.prefs(section).forEach { name ->
                val values = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
                val entries = JSONObject()
                BackupFormat.preferenceKeys.getValue(name).forEach { (key, type) ->
                    values[key]?.let { entries.put(key, JSONObject().put("type", type).put("value", it)) }
                }
                prefs.put(name, entries)
            }
            value.put("preferences", prefs)
            val files = JSONObject()
            storedFiles(section).forEach { name ->
                val file = AtomicFile(File(context.filesDir, name))
                val bytes = file.openRead().use { input -> readBounded(input) }
                files.put(name, Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
            value.put("files", files)
            pruneFiles(section, value)
            sections.put(section.name, value)
        }
        return BackupFormat.create(sections).also { if (validate) BackupFormat.validate(it) }
    }

    fun queue(document: JSONObject, choice: RestoreChoice) {
        BackupFormat.validate(document)
        require(choice.sections.isNotEmpty() && choice.sections.all { document.getJSONObject("sections").has(it.name) })
        val request = JSONObject().put("document", document).put("sections", JSONArray(choice.sections.map { it.name }))
            .put("replace", choice.replace).put("permissions", choice.permissions)
        pending.writeUtf8(request.toString())
    }

    fun consumePending() {
        val request = pending.openRead().use { JSONObject(TextDownloader.readText(it, BackupFormat.MAX_BYTES + 4096)) }
        val sections = request.getJSONArray("sections")
        val choice = RestoreChoice((0 until sections.length()).map { BackupSection.valueOf(sections.getString(it)) }.toSet(),
            request.optBoolean("replace"), request.optBoolean("permissions"))
        restore(request.getJSONObject("document"), choice)
        // Keep the validated request after a rolled-back failure so Retry actually
        // retries that restore instead of reporting success for an empty queue.
        pending.delete()
    }

    fun restore(document: JSONObject, choice: RestoreChoice) {
        BackupFormat.validate(document)
        val before = snapshot(choice.sections, validate = false)
        val plan = BackupMerge.plan(before, document, choice)
        // Persist the original data before touching any repository. Recovery restores
        // original bookmark IDs too, including a crash after SQLite has committed.
        journal.writeUtf8(BackupFormat.encode(before).toString(Charsets.UTF_8))
        try {
            apply(plan, notify = true)
            journal.delete()
            check(!journal.baseFile.exists()) { "Unable to finish restore journal" }
        } catch (error: Exception) {
            try { apply(before, notify = false); journal.delete() }
            catch (rollback: Exception) { error.addSuppressed(rollback) }
            throw error
        }
    }

    fun recoverIfNeeded(): Boolean {
        if (!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists()) return false
        val previous = journal.openRead().use { JSONObject(TextDownloader.readText(it, BackupFormat.MAX_BYTES)) }
        apply(previous, notify = false)
        journal.delete()
        check(!journal.baseFile.exists())
        return true
    }

    @android.annotation.SuppressLint("UseKtx")
    private fun apply(document: JSONObject, notify: Boolean) {
        val sections = document.getJSONObject("sections")
        sections.keys().forEach { key ->
            val section = BackupSection.valueOf(key)
            val value = sections.getJSONObject(key)
            val files = value.optJSONObject("files") ?: JSONObject()
            files.keys().forEach { name ->
                require(BackupFormat.allowedFile(section, name))
                val target = AtomicFile(File(context.filesDir, name))
                target.baseFile.parentFile?.mkdirs()
                val stream = target.startWrite()
                try { stream.write(requireNotNull(BackupFormat.fileBytes(value, name))); target.finishWrite(stream) }
                catch (error: Exception) { target.failWrite(stream); throw error }
                if (notify) afterWrite(name)
            }
            storedFiles(section).filter { !files.has(it) }.forEach { name -> AtomicFile(File(context.filesDir, name)).delete() }
            val prefs = value.optJSONObject("preferences") ?: JSONObject()
            BackupFormat.prefs(section).forEach { name ->
                val edit = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                BackupFormat.preferenceKeys.getValue(name).keys.forEach(edit::remove)
                val entries = prefs.optJSONObject(name) ?: JSONObject()
                entries.keys().forEach { field ->
                    require(field in BackupFormat.preferenceKeys.getValue(name))
                    val entry = entries.getJSONObject(field)
                    when (entry.getString("type")) {
                        "boolean" -> edit.putBoolean(field, entry.getBoolean("value"))
                        "int" -> edit.putInt(field, entry.getInt("value"))
                        "float" -> edit.putFloat(field, entry.getDouble("value").toFloat())
                        "string" -> edit.putString(field, entry.getString("value"))
                        else -> error("Unknown preference type")
                    }
                }
                check(edit.commit()) { "Unable to restore preferences" }
                if (notify) afterWrite(name)
            }
            if (section == BackupSection.BOOKMARKS) {
                val manager = BookmarkManager(context)
                try { manager.restoreSnapshot(value) } finally { manager.close() }
                if (notify) afterWrite("bookmarks")
            }
        }
    }

    private fun storedFiles(section: BackupSection): List<String> {
        val candidates = when (section) {
            BackupSection.HOME -> File(context.filesDir, "homepage_icons").listFiles()?.map { "homepage_icons/" + it.name }.orEmpty()
            BackupSection.FILTERS -> File(context.filesDir, "filter_subscriptions").listFiles()?.map { "filter_subscriptions/" + it.name }.orEmpty()
            BackupSection.READING -> listOf("reading-list.json", "reading-positions.json")
            BackupSection.SCRIPTS -> listOf("userscripts/scripts.json")
            else -> emptyList()
        }
        return candidates.map { it.removeSuffix(".bak") }.distinct().filter { name ->
            BackupFormat.allowedFile(section, name) && (File(context.filesDir, name).isFile || File(context.filesDir, "$name.bak").isFile)
        }
    }

    companion object {
        internal fun pruneFiles(section: BackupSection, value: JSONObject) {
            val files = value.optJSONObject("files") ?: return
            val referenced = when (section) {
                BackupSection.HOME -> {
                    val rows = JSONArray(BackupFormat.prefValue(value, "browser_settings", "homepage_shortcuts") ?: "[]")
                    (0 until rows.length()).map { "homepage_icons/" + rows.getJSONObject(it).optString("icon") }.toSet()
                }
                BackupSection.FILTERS -> {
                    val rows = BackupFormat.fileArray(value, "filter_subscriptions/subscriptions.json")
                    (0 until rows.length()).map { "filter_subscriptions/" + rows.getJSONObject(it).optString("file") }.toSet() +
                        "filter_subscriptions/subscriptions.json"
                }
                else -> return
            }
            files.keys().asSequence().toList().filter { it !in referenced }.forEach(files::remove)
        }

        fun readBounded(input: java.io.InputStream): ByteArray {
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer); if (n < 0) break
                require(output.size() + n <= BackupFormat.MAX_BYTES); output.write(buffer, 0, n)
            }
            return output.toByteArray()
        }
        /** Browsing startup and the restore process cannot initialize writers concurrently. */
        fun <T> locked(context: Context, action: () -> T): T {
            val file = File(context.noBackupFilesDir, "restore.lock")
            file.parentFile?.mkdirs()
            return RandomAccessFile(file, "rw").use { handle -> handle.channel.lock().use { action() } }
        }
    }
}
