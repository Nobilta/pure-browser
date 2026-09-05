package com.mybrowser.tabs

import android.app.Application
import android.content.Context
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TabManagerTest {
    @Test
    fun snapshotRestoresTabsAndSelectedIndex() {
        val original = TabManager()
        val restored = TabManager()
        try {
            original.currentTab!!.url = "https://one.example/page"
            original.currentTab!!.title = "One"
            original.createTab("https://two.example/page")
            original.switchToIndex(0)
            assertTrue(restored.restoreMetadata(original.snapshotMetadata()))
            assertEquals(2, restored.count)
            assertEquals(0, restored.currentIndex)
            assertEquals("One", restored.currentTab!!.title)
            assertEquals(original.tabs.map { it.id }, restored.tabs.map { it.id })
        } finally {
            original.cleanup()
            restored.cleanup()
        }
    }

    @Test
    fun clearingSavedSessionPreventsLaterRestore() {
        val app = RuntimeEnvironment.getApplication()
        val manager = TabManager()
        try {
            manager.currentTab!!.url = "https://example.com/private"
            manager.saveMetadata(app, "test_tabs")
            assertTrue(manager.restoreMetadata(app, "test_tabs"))
            manager.clearMetadata(app, "test_tabs")
            assertFalse(manager.restoreMetadata(app, "test_tabs"))
            assertTrue(app.getSharedPreferences("test_tabs", Context.MODE_PRIVATE).all.isEmpty())
        } finally { manager.cleanup() }
    }

    @Test
    fun corruptAndUnsafeSnapshotsDoNotNavigateToLocalContent() {
        val manager = TabManager()
        try {
            assertFalse(manager.restoreMetadata(Bundle().apply { putString("tabs", "invalid") }))
            assertTrue(manager.restoreMetadata(Bundle().apply {
                putString("tabs", """[{"url":"file:///data/secret","title":"test"}]""")
                putInt("current", 900)
            }))
            assertEquals("", manager.currentTab!!.url)
            assertEquals(0, manager.currentIndex)
        } finally { manager.cleanup() }
    }
}
