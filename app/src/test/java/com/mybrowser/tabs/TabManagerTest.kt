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
    @Test fun reopeningTheLastClosedPageReplacesOnlyTheBlankPlaceholder() {
        val manager = TabManager(maxTabs = 1)
        manager.currentTab!!.url = "https://last.example"
        manager.currentTab!!.title = "Last"
        manager.closeTab(0)
        val closed = manager.recentlyClosed.single()
        assertEquals("https://last.example", manager.reopenClosed(closed.id)!!.url)
        assertEquals(1, manager.count)
        assertTrue(manager.recentlyClosed.isEmpty())
        assertNull(manager.currentTab!!.savedState)
        assertNull(manager.currentTab!!.thumbnail)
    }

    @Test fun closedHistoryIsBoundedRestoresIndependentlyAndCanBeCleared() {
        val context = RuntimeEnvironment.getApplication()
        val manager = TabManager()
        repeat(25) {
            manager.currentTab!!.url = "https://example.com/$it"
            manager.closeTab(0)
        }
        assertEquals(20, manager.recentlyClosed.size)
        assertEquals("https://example.com/24", manager.recentlyClosed.first().url)
        manager.saveRecentlyClosed(context)
        val restored = TabManager()
        restored.restoreRecentlyClosed(context)
        assertEquals(manager.recentlyClosed.map { it.url }, restored.recentlyClosed.map { it.url })
        restored.clearRecentlyClosed()
        restored.saveRecentlyClosed(context)
        manager.restoreRecentlyClosed(context)
        assertTrue(manager.recentlyClosed.isEmpty())
    }

    @Test fun privateAndNonWebTabsNeverEnterClosedHistory() {
        val private = TabManager(rememberClosedTabs = false)
        private.currentTab!!.url = "https://private.example"
        private.closeTab(0)
        assertTrue(private.recentlyClosed.isEmpty())
        assertNull(private.snapshotMetadata().getString("recent"))
        val normal = TabManager()
        normal.currentTab!!.url = "file:///private"
        normal.closeTab(0)
        assertTrue(normal.recentlyClosed.isEmpty())
    }

    @Test fun restoreAtCapacityDoesNotConsumeClosedEntryOrReplaceRealPage() {
        val manager = TabManager(maxTabs = 2)
        manager.currentTab!!.url = "https://one.example"
        manager.createTab("https://two.example")
        manager.closeTab(0)
        val closed = manager.recentlyClosed.single()
        manager.createTab("https://three.example")
        assertNull(manager.reopenClosed(closed.id))
        assertEquals(listOf(closed), manager.recentlyClosed)
        manager.closeTab(1)
        manager.reopenClosed(closed.id)
        assertEquals(listOf("https://one.example", "https://two.example"), manager.tabs.map { it.url })
        assertEquals(0, manager.currentIndex)
    }

    @Test
    fun backgroundTabsStayUnloadedAndKeepCurrentSelection() {
        val manager = TabManager(maxTabs = 2)
        val currentId = manager.currentTab!!.id
        val backgroundId = manager.createTab("https://example.com/background", select = false, title = "Later")
        assertEquals(currentId, manager.currentTab!!.id)
        assertEquals(2, manager.count)
        assertNull(manager.tabs.last().savedState)
        assertNull(manager.tabs.last().thumbnail)
        assertFalse(manager.canCreateTab)
        manager.createTab("https://example.com/overflow", select = false)
        assertEquals(2, manager.count)
        manager.switchToId(backgroundId)
        assertEquals("Later", manager.currentTab!!.title)
        manager.cleanup()
    }

    @Test
    fun closingByIdAndClosingOthersKeepSelectedTab() {
        val manager = TabManager()
        val first = manager.currentTab!!.id
        val selected = manager.createTab("https://selected.example")
        manager.createTab("https://background.example", select = false)
        manager.closeTabById(first)
        assertEquals(selected, manager.currentTab!!.id)
        assertEquals(0, manager.currentIndex)
        manager.closeOtherTabs()
        assertEquals(listOf(selected), manager.tabs.map { it.id })
        manager.closeTabById(selected)
        assertEquals(1, manager.count)
        assertNotEquals(selected, manager.currentTab!!.id)
        assertTrue(manager.currentTab!!.url.isEmpty())
        manager.cleanup()
    }

    @Test
    fun restoredDuplicateIdsCannotSelectOrCloseWrongTab() {
        val manager = TabManager()
        assertTrue(manager.restoreMetadata(Bundle().apply {
            putString("tabs", """[{"id":"duplicate","url":"https://one.example"},{"id":"duplicate","url":"https://two.example"}]""")
        }))
        assertEquals(2, manager.tabs.map { it.id }.distinct().size)
        manager.closeTabById(manager.tabs[1].id)
        assertEquals("https://one.example", manager.currentTab!!.url)
        manager.cleanup()
    }

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
