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
    fun childReturnsToItsOpenerEvenAfterOtherTabsWereSelectedOrReordered() {
        val manager = TabManager()
        try {
            val opener = manager.currentTab!!.id
            val child = manager.createTab("https://child.example", openerTabId = opener)
            manager.createTab("https://other.example")
            manager.move(opener, 1)
            manager.switchToIndex(manager.tabs.indexOfFirst { it.id == child })
            manager.closeTab(manager.currentIndex)
            assertEquals(opener, manager.currentTab!!.id)
            assertEquals(2, manager.count)
        } finally { manager.cleanup() }
    }

    @Test
    fun nestedChildrenReturnOneOpenerAtATime() {
        val manager = TabManager()
        try {
            val root = manager.currentTab!!.id
            val child = manager.createTab("https://child.example", openerTabId = root)
            manager.createTab("https://grandchild.example", openerTabId = child)
            manager.closeTab(manager.currentIndex)
            assertEquals(child, manager.currentTab!!.id)
            manager.closeTab(manager.currentIndex)
            assertEquals(root, manager.currentTab!!.id)
        } finally { manager.cleanup() }
    }

    @Test
    fun closingOpenerClearsReferencesAndFallsBackToTheMostRecentlyVisitedTab() {
        val manager = TabManager()
        try {
            val opener = manager.currentTab!!.id
            val child = manager.createTab("https://child.example", openerTabId = opener)
            val recent = manager.createTab("https://recent.example")
            manager.createTab("https://never-visited.example", select = false)
            manager.switchToIndex(manager.tabs.indexOfFirst { it.id == child })
            manager.closeTab(manager.tabs.indexOfFirst { it.id == opener })
            assertEquals(child, manager.currentTab!!.id)
            assertNull(manager.currentTab!!.openerTabId)
            manager.closeTab(manager.currentIndex)
            assertEquals(recent, manager.currentTab!!.id)
        } finally { manager.cleanup() }
    }

    @Test
    fun unknownOpenerCannotCrossTabManagersAndClosingOthersClearsRelationships() {
        val normal = TabManager()
        val privateTabs = TabManager()
        try {
            privateTabs.createTab("https://private.example", openerTabId = normal.currentTab!!.id)
            assertNull(privateTabs.currentTab!!.openerTabId)
            val root = normal.currentTab!!.id
            val child = normal.createTab("https://child.example", openerTabId = root)
            normal.closeOtherTabs()
            assertEquals(child, normal.currentTab!!.id)
            assertNull(normal.currentTab!!.openerTabId)
        } finally { normal.cleanup(); privateTabs.cleanup() }
    }

    @Test
    fun restoringMetadataDoesNotPersistSessionOnlyOpenerRelationships() {
        val original = TabManager()
        val restored = TabManager()
        try {
            original.createTab("https://child.example", openerTabId = original.currentTab!!.id)
            assertTrue(restored.restoreMetadata(original.snapshotMetadata()))
            assertTrue(restored.tabs.all { it.openerTabId == null })
        } finally { original.cleanup(); restored.cleanup() }
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
        manager.switchToIndex(manager.tabs.indexOfFirst { it.id == backgroundId })
        assertEquals("Later", manager.currentTab!!.title)
        manager.cleanup()
    }

    @Test
    fun closingTabsAndClosingOthersKeepSelectedTab() {
        val manager = TabManager()
        val selected = manager.createTab("https://selected.example")
        manager.createTab("https://background.example", select = false)
        manager.closeTab(0)
        assertEquals(selected, manager.currentTab!!.id)
        assertEquals(0, manager.currentIndex)
        manager.closeOtherTabs()
        assertEquals(listOf(selected), manager.tabs.map { it.id })
        manager.closeTab(0)
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
        manager.closeTab(1)
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
