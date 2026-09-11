package com.mybrowser.privacy

import org.junit.Assert.*
import org.junit.Test

class IncognitoSessionRegistryTest {
    private class Store {
        val names = mutableSetOf("Default", "work", "incognito")
        val loaded = mutableSetOf<String>()
        val deleted = mutableListOf<String>()
        fun registry() = IncognitoSessionRegistry({ names.toList() }) { name ->
            if (name in loaded) throw IllegalStateException("Profile loaded")
            deleted += name
            names.remove(name)
        }
    }

    @Test fun loadedProfileDeletionFailureCannotReconnectTheNextSession() {
        val store = Store()
        val sessions = store.registry()
        val old = sessions.begin()
        store.names += old
        store.loaded += old
        assertFalse(sessions.end())
        assertNull(sessions.activeName)
        assertNotEquals(old, sessions.begin())
        assertTrue(old in store.names)
    }

    @Test fun recreationKeepsTheCurrentSessionAndCleanupNeverDeletesIt() {
        val store = Store()
        val sessions = store.registry()
        val current = sessions.begin()
        store.names += current
        assertEquals(current, sessions.begin())
        assertTrue(sessions.cleanupStale())
        assertTrue(current in store.names)
        assertFalse(current in store.deleted)
    }

    @Test fun nextProcessDeletesRetiredProfilesAndLegacyNameWithoutTouchingNormalProfiles() {
        val store = Store()
        val old = store.registry().begin()
        store.names += old
        store.names += "incognito"
        assertTrue(store.registry().cleanupStale())
        assertEquals(setOf("Default", "work"), store.names)
        assertTrue(store.deleted.containsAll(listOf(old, "incognito")))
    }

    @Test fun repeatedSessionsRemainDistinctWhenEveryLoadedProfileMustWaitForRestart() {
        val store = Store()
        val sessions = store.registry()
        val seen = mutableSetOf<String>()
        repeat(100) {
            val name = sessions.begin()
            assertTrue(seen.add(name))
            store.names += name
            store.loaded += name
            assertFalse(sessions.end())
        }
        assertEquals(100, seen.size)
    }

    @Test fun missingProfileIsAlreadyCleanAndRepeatedExitIsHarmless() {
        val store = Store()
        val sessions = store.registry()
        sessions.begin()
        assertTrue(sessions.end())
        assertTrue(sessions.end())
        assertNull(sessions.activeName)
    }
}
