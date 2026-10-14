package com.mybrowser.download

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DownloadMetadataStoreTest {
    private val prefs get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("metadata-store-test", Context.MODE_PRIVATE)

    private fun record(id: Long) = DownloadMetadata(
        id = -id, backend = DownloadBackend.LOCAL, url = "https://example.com/$id",
        userAgent = "agent", cookie = "session-secret", contentDisposition = null,
        mimeType = "application/octet-stream", filename = "$id.bin", referer = null,
        timestamp = id, status = DownloadStatus.COMPLETED,
    )

    @Test fun snapshotsKeepOnlyTheNewest256RecordsAndNeverSerializeCookies() {
        val store = DownloadMetadataStore(prefs)
        store.save((1L..300L).map(::record))
        val raw = requireNotNull(prefs.getString("entries", null))
        assertFalse(raw.contains("session-secret"))
        val restored = store.load()
        assertEquals(256, restored.entries.size)
        assertEquals(-300L, restored.entries.first().id)
        assertEquals(-45L, restored.entries.last().id)
        assertTrue(restored.interruptedIds.isEmpty())
        assertTrue(restored.entries.all { it.cookie == null })
    }

    @Test fun largeSnapshotsStayValidJsonWithinTheCharacterBudgetAndCanBeReplacedByEmptyOnes() {
        val store = DownloadMetadataStore(prefs)
        store.save((1L..256L).map { record(it).copy(url = "https://example.com/" + "x".repeat(8_000)) })
        val raw = requireNotNull(prefs.getString("entries", null))
        assertTrue(raw.length <= 512 * 1024)
        val count = JSONArray(raw).length()
        assertTrue(count in 1..255)
        assertEquals(count, store.load().entries.size)
        store.save(emptyList())
        assertEquals("[]", prefs.getString("entries", null))
        assertTrue(store.load().entries.isEmpty())
    }

    @Test fun corruptAndOversizedPreferencesAreDiscardedBeforeTheNextSave() {
        val store = DownloadMetadataStore(prefs)
        for (invalid in listOf("not-json", " ".repeat(512 * 1024 + 1))) {
            prefs.edit { putString("entries", invalid) }
            assertTrue(store.load().entries.isEmpty())
            assertFalse(prefs.contains("entries"))
            store.save(listOf(record(1)))
            assertEquals(-1L, store.load().entries.single().id)
        }
    }
}
