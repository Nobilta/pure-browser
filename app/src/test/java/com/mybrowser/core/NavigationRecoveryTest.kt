package com.mybrowser.core

import com.mybrowser.site.SitePermission
import org.junit.Assert.*
import org.junit.Test

class NavigationRecoveryTest {
    @Test fun repeatedRendererFailuresStopEvenIfUrlChangesThenManualRetryWorks() {
        val recovery = RendererRecovery()
        assertTrue(recovery.shouldReload("a", 0))
        assertTrue(recovery.shouldReload("a", 100))
        assertFalse(recovery.shouldReload("a", 200))
        assertFalse(recovery.shouldReload("a", 1_000))
        assertTrue(recovery.shouldReload("b", 1_100))
        recovery.retry("a")
        assertTrue(recovery.shouldReload("a", 1_200))
        assertTrue(recovery.shouldReload("a", 62_000))
    }

    @Test fun unpromptedSubframesAreBlockedEvenForAllowedSites() {
        val gate = ExternalNavigationGate()
        assertEquals(ExternalNavigationGate.Decision.BLOCK, gate.decide(SitePermission.ALLOW, false, false, false, 0))
        assertEquals(ExternalNavigationGate.Decision.ASK, gate.decide(SitePermission.ASK, false, true, false, 1))
        assertEquals(ExternalNavigationGate.Decision.BLOCK, gate.decide(SitePermission.ASK, true, true, true, 5000))
    }

    @Test fun allowedAppsHaveCooldownAndWindowBudget() {
        val gate = ExternalNavigationGate()
        fun request(time: Long) = gate.decide(SitePermission.ALLOW, true, true, false, time)
        assertEquals(ExternalNavigationGate.Decision.OPEN, request(0))
        assertEquals(ExternalNavigationGate.Decision.BLOCK, request(1000))
        assertEquals(ExternalNavigationGate.Decision.OPEN, request(2500))
        assertEquals(ExternalNavigationGate.Decision.OPEN, request(5000))
        assertEquals(ExternalNavigationGate.Decision.BLOCK, request(8000))
        assertEquals(ExternalNavigationGate.Decision.OPEN, request(30_001))
        assertEquals(ExternalNavigationGate.Decision.BLOCK, gate.decide(SitePermission.BLOCK, true, true, false, 40_000))
    }
}
