package com.mybrowser.tabs

import org.junit.Assert.*
import org.junit.Test

class PopupSessionStoreTest {
    @Test fun ordinaryTabsHaveNoLiveWindowRelationship() {
        val store = PopupSessionStore<Any>()
        assertNull(store["manual-opener"])
        assertNull(store.releaseIfUnlinked("manual-child"))
    }

    @Test fun nestedWindowsKeepBothEndpointsUntilTheirOwnRelationshipsEnd() {
        val store = PopupSessionStore<Any>()
        val a = Any(); val b = Any(); val c = Any()
        store.connect("a", a, "b", b)
        store.connect("b", b, "c", c)
        assertNull(store.releaseIfUnlinked("a"))
        assertNull(store.releaseIfUnlinked("b"))
        store.remove("c")
        assertNull(store.releaseIfUnlinked("b"))
        store.remove("b")
        // Callback fetches on an unseen opener remain possible after its popup closes.
        assertSame(a, store["a"])
        assertSame(a, store.releaseIfUnlinked("a"))
        assertNull(store["a"])
    }

    @Test fun closingOneOfMultiplePopupsDoesNotReleaseTheOpener() {
        val store = PopupSessionStore<Any>()
        val opener = Any()
        store.connect("a", opener, "b", Any())
        store.connect("a", opener, "c", Any())
        store.remove("b")
        assertNull(store.releaseIfUnlinked("a"))
        assertSame(opener, store["a"])
        assertEquals("a", store.idOf { it === opener })
    }

    @Test fun closeOthersAndPrivateSessionExitLeaveNoOrphanRelationships() {
        val store = PopupSessionStore<Any>()
        val a = Any(); val b = Any(); val c = Any()
        store.connect("a", a, "b", b)
        store.connect("b", b, "c", c)
        assertEquals(setOf(a, c), store.retain(setOf("b")).toSet())
        assertSame(b, store.releaseIfUnlinked("b"))
        store.connect("a", a, "b", b)
        assertEquals(setOf(a, b), store.clear().toSet())
        assertNull(store["a"])
        assertNull(store["b"])
        assertTrue(store.clear().isEmpty())
    }
}
