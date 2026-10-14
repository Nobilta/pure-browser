package com.mybrowser.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationReadinessTest {
    private class PendingNavigation(scope: TestScope) {
        val privacy = CompletableDeferred<Boolean>()
        val scripts = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val readiness = NavigationReadiness(
            scope = scope,
            privacyReady = { privacy.isCompleted },
            preparePrivacy = { privacy.await() },
            scriptsReady = { scripts.isCompleted },
            prepareScripts = { scripts.await() },
            refreshScripts = { events += "refresh" },
        )
    }

    @Test fun onlyTheLatestPrivacyNavigationRunsAfterCleanup() = runTest {
        val pending = PendingNavigation(this)
        assertTrue(pending.readiness.awaitPrivacy { pending.events += "old" })
        runCurrent()
        assertTrue(pending.readiness.awaitPrivacy { pending.events += "new" })
        runCurrent()
        pending.privacy.complete(true)
        runCurrent()
        assertEquals(listOf("new"), pending.events)
        assertFalse(pending.readiness.awaitPrivacy { fail("ready actions belong to the caller") })
    }

    @Test fun scriptsRefreshBeforeOnlyTheLatestDeferredNavigation() = runTest {
        val pending = PendingNavigation(this)
        pending.readiness.awaitScripts { pending.events += "old" }
        runCurrent()
        pending.readiness.awaitScripts { pending.events += "new" }
        runCurrent()
        pending.scripts.complete(Unit)
        runCurrent()
        assertEquals(listOf("refresh", "new"), pending.events)
        assertFalse(pending.readiness.awaitScripts { fail("already initialized") })
        assertEquals(listOf("refresh", "new", "refresh"), pending.events)
    }

    @Test fun switchingProfilesCancelsScriptsButCanFinishPrivacyCleanup() = runTest {
        val pending = PendingNavigation(this)
        pending.readiness.awaitPrivacy { pending.events += "privacy" }
        pending.readiness.awaitScripts { pending.events += "old-profile" }
        runCurrent()
        pending.readiness.cancelScripts()
        pending.scripts.complete(Unit)
        pending.privacy.complete(true)
        runCurrent()
        assertEquals(listOf("privacy"), pending.events)
    }

    @Test fun finishingHostCanDeclineNavigationAfterPrivacyCleanup() = runTest {
        val pending = PendingNavigation(this)
        pending.readiness.awaitPrivacy { fail("host is finishing") }
        runCurrent()
        pending.privacy.complete(false)
        runCurrent()
        assertTrue(pending.events.isEmpty())
    }

    @Test fun destroyingTheOwningScopeCancelsBothWaits() = runTest {
        val window = TestScope(testScheduler)
        val pending = PendingNavigation(window)
        pending.readiness.awaitPrivacy { fail("window was destroyed") }
        pending.readiness.awaitScripts { fail("window was destroyed") }
        runCurrent()
        window.cancel()
        pending.privacy.complete(true)
        pending.scripts.complete(Unit)
        runCurrent()
        assertTrue(pending.events.isEmpty())
    }
}
