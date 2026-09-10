package com.mybrowser.backup

import android.app.Application
import android.content.Context
import com.mybrowser.data.BookmarkManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupStorageTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val sections = setOf(BackupSection.BOOKMARKS, BackupSection.SETTINGS)
    private fun seed(): Long {
        val manager = BookmarkManager(context)
        try {
            val folder = manager.addFolder("中文资料")
            val id = manager.addBookmark("Original", "https://original.test")
            manager.moveBookmarks(setOf(id), folder)
            context.getSharedPreferences("browser_preferences", Context.MODE_PRIVATE).edit()
                .putBoolean("bottom_address_bar", true).putString("do_not_export", "private").commit()
            return id
        } finally { manager.close() }
    }
    private fun changed(document: JSONObject): JSONObject {
        val copy = JSONObject(document.toString())
        copy.getJSONObject("sections").getJSONObject("BOOKMARKS").getJSONArray("bookmarks")
            .getJSONObject(0).put("title", "Imported").put("url", "https://imported.test")
        copy.getJSONObject("sections").getJSONObject("SETTINGS").getJSONObject("preferences")
            .getJSONObject("browser_preferences").getJSONObject("bottom_address_bar").put("value", false)
        return copy
    }
    private fun rows(): List<com.mybrowser.data.Bookmark> {
        val manager = BookmarkManager(context)
        return try { manager.getAllBookmarks() } finally { manager.close() }
    }

    @Test fun versionedSnapshotWhitelistsDataAndRoundTripsIdsFoldersAndTimestamps() {
        val id = seed()
        File(context.filesDir, "userscripts").mkdirs()
        File(context.filesDir, "userscripts/secret-values.json").writeText("secret")
        val storage = BackupStorage(context)
        val document = storage.snapshot()
        val text = BackupFormat.encode(document).toString(Charsets.UTF_8)
        assertFalse(text.contains("do_not_export")); assertFalse(text.contains("secret-values"))
        assertFalse(text.contains("history")); assertFalse(text.contains("cookies"))
        val previous = rows().single()
        val manager = BookmarkManager(context)
        try { manager.clearAll() } finally { manager.close() }
        storage.restore(BackupFormat.decode(text.toByteArray()), RestoreChoice(BackupSection.entries.toSet(), replace = true))
        assertEquals(previous, rows().single()); assertEquals(id, rows().single().id)
    }

    @Test fun mergeKeepsExistingRowsAndAllocatesIdsForDifferentUrls() {
        val id = seed()
        val storage = BackupStorage(context)
        storage.restore(changed(storage.snapshot(sections)), RestoreChoice(sections))
        assertEquals(2, rows().size)
        assertEquals("Original", rows().first { it.id == id }.title)
        assertEquals(2, rows().map { it.id }.distinct().size)
        assertTrue(context.getSharedPreferences("browser_preferences", 0).getBoolean("bottom_address_bar", false))
    }

    @Test fun failureAfterDatabaseAndPreferencesWriteRollsBackOriginalData() {
        seed()
        val before = BackupStorage(context).snapshot(sections)
        val storage = BackupStorage(context) { if (it == "browser_preferences") throw IOException("Injected disk failure") }
        assertThrows(IOException::class.java) { storage.restore(changed(before), RestoreChoice(sections, replace = true)) }
        assertEquals(before.getJSONObject("sections").toString(), BackupStorage(context).snapshot(sections).getJSONObject("sections").toString())
        assertFalse(BackupStorage(context).recoverIfNeeded())
    }

    @Test fun processInterruptionAfterCommitIsRecoveredFromJournal() {
        seed()
        val before = BackupStorage(context).snapshot(sections)
        val storage = BackupStorage(context) { if (it == "bookmarks") throw AssertionError("Simulated process death") }
        assertThrows(AssertionError::class.java) { storage.restore(changed(before), RestoreChoice(sections, replace = true)) }
        assertEquals("Imported", rows().single().title)
        assertTrue(BackupStorage(context).recoverIfNeeded())
        assertEquals(before.getJSONObject("sections").toString(), BackupStorage(context).snapshot(sections).getJSONObject("sections").toString())
    }

    @Test fun invalidVersionTraversalAndFractionalPreferenceDoNotChangeStorage() {
        seed()
        val storage = BackupStorage(context)
        val before = storage.snapshot(sections)
        val badVersion = JSONObject(before.toString()).put("version", 999)
        assertThrows(IllegalArgumentException::class.java) { storage.restore(badVersion, RestoreChoice(sections)) }
        val badFile = changed(before)
        badFile.getJSONObject("sections").getJSONObject("SETTINGS").getJSONObject("files").put("../outside", "aGVsbG8=")
        assertThrows(IllegalArgumentException::class.java) { storage.restore(badFile, RestoreChoice(sections)) }
        val badInt = changed(before)
        badInt.getJSONObject("sections").getJSONObject("SETTINGS").getJSONObject("preferences")
            .getJSONObject("browser_preferences").put("reading_text_zoom", JSONObject().put("type", "int").put("value", 100.5))
        assertThrows(IllegalArgumentException::class.java) { storage.restore(badInt, RestoreChoice(sections)) }
        assertEquals("Original", rows().single().title)
    }

    @Test fun importedScriptsStartDisabledAndPermissionsRequireSeparateChoice() {
        val storage = BackupStorage(context)
        val selected = setOf(BackupSection.SCRIPTS, BackupSection.SITES)
        val document = storage.snapshot(selected)
        val contents = document.getJSONObject("sections")
        val script = "// ==UserScript==\n// @name Backup fixture\n// @namespace qa\n// @match https://example.test/*\n// @grant none\n// ==/UserScript==\nvoid 0;"
        BackupFormat.putArray(contents.getJSONObject("SCRIPTS"), "userscripts/scripts.json",
            JSONArray().put(JSONObject().put("source", script).put("enabled", true).put("requires", JSONArray())))
        BackupFormat.putPref(contents.getJSONObject("SITES"), "site_settings", "sites",
            JSONObject().put("https://example.test", JSONObject().put("CAMERA", "ALLOW").put("externalApps", "ALLOW")).toString())
        storage.restore(document, RestoreChoice(selected, replace = true))
        val result = storage.snapshot(selected).getJSONObject("sections")
        assertFalse(BackupFormat.fileArray(result.getJSONObject("SCRIPTS"), "userscripts/scripts.json").getJSONObject(0).getBoolean("enabled"))
        var sites = JSONObject(BackupFormat.prefValue(result.getJSONObject("SITES"), "site_settings", "sites"))
        assertEquals("ASK", sites.getJSONObject("https://example.test").getString("CAMERA"))
        storage.restore(document, RestoreChoice(selected, replace = true, permissions = true))
        sites = JSONObject(context.getSharedPreferences("site_settings", 0).getString("sites", "{}"))
        assertEquals("ALLOW", sites.getJSONObject("https://example.test").getString("CAMERA"))
    }

    @Test fun queuedRestoreIsConsumedOnceAndLockReleasesAfterFailure() {
        seed()
        val storage = BackupStorage(context)
        storage.queue(changed(storage.snapshot(sections)), RestoreChoice(sections, replace = true))
        assertTrue(storage.hasPending())
        BackupStorage.locked(context) { storage.consumePending() }
        assertFalse(storage.hasPending()); assertEquals("Imported", rows().single().title)
        assertThrows(IOException::class.java) { BackupStorage.locked(context) { throw IOException("lock test") } }
        assertEquals(42, BackupStorage.locked(context) { 42 })
    }

    @Test fun failedQueuedRestoreKeepsRequestForAnActualRetry() {
        seed()
        val storage = BackupStorage(context)
        storage.queue(changed(storage.snapshot(sections)), RestoreChoice(sections, replace = true))
        val failing = BackupStorage(context) { throw IOException("injected write failure") }
        assertThrows(IOException::class.java) { failing.consumePending() }
        assertTrue(storage.hasPending())
        assertNotEquals("Imported", rows().single().title)
        storage.consumePending()
        assertFalse(storage.hasPending())
        assertEquals("Imported", rows().single().title)
    }
}
