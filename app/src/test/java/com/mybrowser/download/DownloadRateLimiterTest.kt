package com.mybrowser.download

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRateLimiterTest {
    @Test fun concurrentTasksShareOnePayloadBudget() = runTest {
        val limiter = DownloadRateLimiter({ testScheduler.currentTime * 1_000_000 }, { delay(it) })
        limiter.setLimit(32 * 1024)
        val tasks = List(4) { async { limiter.acquire(32 * 1024) } }
        tasks.awaitAll()
        assertTrue("128 KiB must take at least four seconds in total", testScheduler.currentTime >= 4000)
    }

    @Test fun cancelledWaiterDoesNotReserveFutureBandwidth() = runTest {
        val limiter = DownloadRateLimiter({ testScheduler.currentTime * 1_000_000 }, { delay(it) })
        limiter.setLimit(16 * 1024)
        val cancelled = launch { limiter.acquire(1024 * 1024) }
        runCurrent()
        cancelled.cancelAndJoin()
        limiter.acquire(16 * 1024)
        assertTrue("Only the surviving slice should consume time", testScheduler.currentTime in 1000L..1100L)
    }

    @Test fun removingLimitReleasesWaitingTasksAndUnlimitedDoesNotWait() = runTest {
        val limiter = DownloadRateLimiter({ testScheduler.currentTime * 1_000_000 }, { delay(it) })
        limiter.acquire(1024 * 1024)
        assertEquals(0L, testScheduler.currentTime)
        limiter.setLimit(1024)
        val waiting = async { limiter.acquire(32 * 1024) }
        runCurrent()
        assertFalse(waiting.isCompleted)
        limiter.setLimit(0)
        waiting.await()
        assertTrue(testScheduler.currentTime <= 100)
    }
}
