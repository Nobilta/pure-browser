package com.mybrowser.privacy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyCleanupBarrierTest {
    @Test fun navigationWaitsForCleanupAcrossHostReplacement() = runTest {
        val barrier = PrivacyCleanupBarrier(backgroundScope)
        val complete = CompletableDeferred<Unit>()
        barrier.enqueue { complete.await() }
        var oldNavigated = false
        var newNavigated = false
        val oldHost = launch { barrier.awaitReady(); oldNavigated = true }
        runCurrent()
        oldHost.cancel()
        launch { barrier.awaitReady(); newNavigated = true }
        runCurrent()
        assertFalse(newNavigated)
        complete.complete(Unit)
        runCurrent()
        assertFalse(oldNavigated)
        assertTrue(newNavigated)
    }

    @Test fun failedCleanupBlocksNavigationUntilSuccessfulExplicitRetry() = runTest {
        val barrier = PrivacyCleanupBarrier(backgroundScope)
        var attempts = 0
        barrier.enqueue { if (++attempts == 1) throw java.io.IOException("storage") }
        var navigated = false
        launch { barrier.awaitReady(); navigated = true }
        runCurrent()
        assertEquals(PrivacyCleanupBarrier.State.FAILED, barrier.state.value)
        assertFalse(navigated)
        barrier.retry()
        barrier.retry() // repeated taps do not start concurrent wipes
        runCurrent()
        assertEquals(2, attempts)
        assertTrue(navigated)
    }

    @Test fun closingDuringEntryCleanupQueuesExitBeforeNextSessionCanNavigate() = runTest {
        val barrier = PrivacyCleanupBarrier(backgroundScope)
        val entry = CompletableDeferred<Unit>()
        val exit = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        barrier.enqueue { entry.await(); events += "entry" }
        barrier.enqueue { exit.await(); events += "exit" }
        launch { barrier.awaitReady(); events += "navigate" }
        entry.complete(Unit)
        runCurrent()
        assertEquals(listOf("entry"), events)
        exit.complete(Unit)
        runCurrent()
        assertEquals(listOf("entry", "exit", "navigate"), events)
    }
}
