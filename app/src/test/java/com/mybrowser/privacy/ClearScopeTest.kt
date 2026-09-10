package com.mybrowser.privacy

import org.junit.Assert.*
import org.junit.Test

class ClearScopeTest {
    @Test fun isolatedPrivateCleanupNeverSelectsNormalStorageImplicitly() {
        assertEquals(setOf(DataProfile.PRIVATE), ClearScope.CURRENT.targets(true, true))
        assertEquals(setOf(DataProfile.NORMAL), ClearScope.NORMAL.targets(true, true))
        assertEquals(setOf(DataProfile.NORMAL, DataProfile.PRIVATE), ClearScope.ALL.targets(true, true))
    }
    @Test fun fallbackIsExplicitlyTheOneSharedProfile() {
        ClearScope.entries.forEach {
            assertEquals(setOf(DataProfile.NORMAL), it.targets(true, false))
            assertEquals(setOf(DataProfile.NORMAL), it.targets(false, false))
        }
    }
}
